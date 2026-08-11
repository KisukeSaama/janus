package io.janus.gateway;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

import io.janus.credentials.Credential;
import io.janus.credentials.UpstreamTokenProvider;
import io.janus.openbao.OpenBaoClient;
import io.janus.shared.CorrelationIdFilter;

/**
 * Everything that happens after a call has been authorised and before its answer is written back:
 * reuse, waiting, retrying, and giving up gracefully.
 *
 * <p>The point of this class is that a client application should not have to implement any of it.
 * It sends an ordinary request; Janus decides whether the answer can be reused, whether the
 * provider can take the traffic right now, whether a failure is worth another attempt, and whether
 * a slightly old answer beats an error. Every decision is stated in response headers
 * ({@code X-Janus-Cache}, {@code X-Janus-RateLimit-*}, {@code Age}, {@code Retry-After}), so the
 * behaviour is observable rather than magical.
 *
 * <p>The order below is deliberate. The caller's own allowance is checked first, because it must be
 * felt whatever else happens. The store is consulted next, before OpenBao: a served hit means the
 * credential was never read and nothing left this process. Only then does Janus consider whether it
 * is allowed to speak to the provider at all.
 */
@Service
public class GatewayTrafficService {
    private static final Logger log = LoggerFactory.getLogger(GatewayTrafficService.class);

    public static final String CACHE_HEADER = "X-Janus-Cache";
    public static final String LIMIT_HEADER = "X-Janus-RateLimit-Limit";
    public static final String REMAINING_HEADER = "X-Janus-RateLimit-Remaining";
    public static final String RESET_HEADER = "X-Janus-RateLimit-Reset";
    public static final String ATTEMPTS_HEADER = "X-Janus-Upstream-Attempts";

    /** Methods whose answer may be reused. */
    private static final Set<HttpMethod> SAFE = Set.of(HttpMethod.GET, HttpMethod.HEAD);
    /** Methods a second attempt cannot duplicate the effect of. */
    private static final Set<HttpMethod> IDEMPOTENT =
            Set.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.PUT, HttpMethod.DELETE);
    /** Statuses that describe a moment rather than the request. */
    private static final Set<Integer> TRANSIENT = Set.of(429, 502, 503, 504);

    private final WebClient web;
    private final OpenBaoClient bao;
    private final UpstreamTokenProvider tokens;
    private final ResponseCache cache;
    private final RateLimiter limiter;
    private final UpstreamCooldown cooldown;
    private final GatewayTrafficProperties properties;
    private final long responseTimeoutMillis;

    /** Identical in-flight reads, so a cold entry under load costs one upstream call, not hundreds. */
    private final ConcurrentMap<String, CompletableFuture<Delivery>> inFlight = new ConcurrentHashMap<>();

    public GatewayTrafficService(
            WebClient gatewayWebClient,
            OpenBaoClient bao,
            UpstreamTokenProvider tokens,
            ResponseCache cache,
            RateLimiter limiter,
            UpstreamCooldown cooldown,
            GatewayTrafficProperties properties,
            @Value("${janus.gateway.response-timeout-seconds:30}") long responseTimeoutSeconds) {
        this.web = gatewayWebClient;
        this.bao = bao;
        this.tokens = tokens;
        this.cache = cache;
        this.limiter = limiter;
        this.cooldown = cooldown;
        this.properties = properties;
        this.responseTimeoutMillis = Math.max(1_000, responseTimeoutSeconds * 1000);
    }

    /**
     * One answer, from wherever it came, before Janus stamps its own headers on it.
     *
     * @param ageSeconds how old the answer is, or {@code null} when it was just fetched
     * @param note       what happened, for the audit trail; never any response content
     */
    private record Delivery(
            int status,
            HttpHeaders headers,
            byte[] body,
            int attempts,
            CacheStatus cacheStatus,
            Long ageSeconds,
            Long freshSeconds,
            String note) {}

    public GatewayOutcome forward(GatewayExchange exchange) {
        var provider = exchange.provider();
        var credential = exchange.grant().getCredential();

        // 1. The caller's own allowance. Never waited out: this limit exists to be felt.
        var client = limiter.tryAcquire(
                "grant:" + exchange.grant().getId(),
                exchange.grant().getRateLimitPerMinute(),
                exchange.grant().getRateLimitBurst());
        if (!client.allowed())
            throw new Throttled(
                    "Application rate limit for this provider exceeded",
                    client.retryAfterSeconds(),
                    rateLimitHeaders(client));

        // 2. What has already been fetched, if anything may be.
        //
        // A caller that set a condition of its own is normally none of the store's business. The one
        // exception is a bare If-None-Match, which asks a question a stored entry can answer — so the
        // store is read for it, and the request is forwarded untouched if it cannot.
        boolean revalidatingCaller = CachePolicy.callerRevalidatesOnly(exchange.headers());
        boolean conditionalCaller = CachePolicy.callerIsConditional(exchange.headers());
        boolean cacheable = cache.isEnabled()
                && provider.isCacheEnabled()
                && SAFE.contains(exchange.method())
                && (!conditionalCaller || revalidatingCaller);
        boolean mayStore = cacheable && !CachePolicy.callerRefusesStorage(exchange.headers());
        boolean mayReuse = mayStore && !CachePolicy.callerRefusesReuse(exchange.headers());
        String key = mayStore
                ? CachePolicy.key(
                        provider.getId(),
                        credential.getId(),
                        exchange.method().name(),
                        exchange.route(),
                        exchange.headers())
                : null;

        ResponseCache.Entry stored = null;
        if (mayReuse) {
            stored = cache.lookup(key, exchange.headers()).orElse(null);
            long now = System.currentTimeMillis();
            if (stored != null && stored.fresh(now)) {
                boolean unchanged = revalidatingCaller
                        && CachePolicy.matchesEtag(
                                exchange.headers().getFirst(HttpHeaders.IF_NONE_MATCH), stored.etag());
                cache.record(CacheStatus.HIT);
                return complete(
                        new Delivery(
                                unchanged ? HttpStatus.NOT_MODIFIED.value() : stored.status(),
                                stored.headers(),
                                unchanged ? new byte[0] : stored.body(),
                                0,
                                CacheStatus.HIT,
                                stored.ageSeconds(now),
                                stored.lifetimeSeconds(),
                                null),
                        client);
            }
        }

        // Nothing fresh to answer a condition with, so the caller's exchange goes out as its own:
        // no stored validator mixed into it, and nothing kept from what comes back.
        if (conditionalCaller) return complete(call(exchange, null, null), client);

        try {
            return complete(mayReuse ? coalesced(key, exchange, stored) : call(exchange, key, null), client);
        } catch (Throttled throttled) {
            // A refusal owed to the provider still reports the caller's own standing, so one 429 is
            // enough to tell a client whether it is the one going too fast.
            throttled.headers.addAll(rateLimitHeaders(client));
            throw throttled;
        }
    }

    /**
     * Runs the call once for however many identical requests arrive while it is running. Followers
     * neither read the credential nor spend a provider permit; they receive what the first caller
     * received. A follower that waits longer than the response timeout stops waiting and calls for
     * itself, so a stuck leader delays nobody indefinitely.
     */
    private Delivery coalesced(String key, GatewayExchange exchange, ResponseCache.Entry stored) {
        while (true) {
            var leader = inFlight.get(key);
            if (leader == null) {
                var mine = new CompletableFuture<Delivery>();
                if (inFlight.putIfAbsent(key, mine) != null) continue;
                try {
                    var delivery = call(exchange, key, stored);
                    mine.complete(delivery);
                    return delivery;
                } catch (RuntimeException ex) {
                    mine.completeExceptionally(ex);
                    throw ex;
                } finally {
                    inFlight.remove(key, mine);
                }
            }
            try {
                var delivery = leader.get(responseTimeoutMillis, TimeUnit.MILLISECONDS);
                cache.record(CacheStatus.COALESCED);
                return new Delivery(
                        delivery.status(),
                        delivery.headers(),
                        delivery.body(),
                        delivery.attempts(),
                        CacheStatus.COALESCED,
                        delivery.ageSeconds(),
                        delivery.freshSeconds(),
                        delivery.note());
            } catch (TimeoutException ex) {
                return call(exchange, key, stored);
            } catch (ExecutionException ex) {
                throw ex.getCause() instanceof RuntimeException runtime
                        ? runtime
                        : new IllegalStateException("Upstream request failed", ex.getCause());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for an identical request", ex);
            }
        }
    }

    /** Everything from "may we call this provider" to "what do we do when it will not answer". */
    private Delivery call(GatewayExchange exchange, String key, ResponseCache.Entry stored) {
        var provider = exchange.provider();
        var credential = exchange.grant().getCredential();
        String cooldownKey = UpstreamCooldown.key(provider.getId(), credential.getId());

        var paused = cooldown.remaining(cooldownKey);
        if (paused.isPresent())
            return stale(stored, "provider in cooldown")
                    .orElseThrow(() -> new Throttled(
                            "Provider asked for a pause and Janus is honouring it", paused.get(), new HttpHeaders()));

        var ceiling = limiter.acquire(
                "provider:" + provider.getId(),
                provider.getRateLimitPerMinute(),
                provider.getRateLimitBurst(),
                properties.throttle().maxWaitMillis());
        if (!ceiling.allowed())
            return stale(stored, "provider allowance exhausted")
                    .orElseThrow(() -> new Throttled(
                            "Provider rate limit reached", ceiling.retryAfterSeconds(), new HttpHeaders()));

        // Authorisation is complete and the answer cannot come from anywhere else; only now does
        // credential material exist in this process — and for an open API, never: nothing was stored
        // for it, so nothing is fetched, and the call goes out as anonymous as it was meant to be.
        String secret = credential.getAuthType().anonymous() ? null : bao.read(credential.getSecretPath());
        // What actually travels. For most strategies it is the stored value; for a client-credentials
        // exchange it is the bearer token Janus obtained with it, held until close to its expiry.
        //
        // Fails closed: a failed exchange throws rather than sending the request without credentials,
        // which is the one outcome that would look to the upstream like an anonymous call.
        String presented = credential.getAuthType().exchanged() ? tokens.tokenFor(credential, secret) : secret;
        boolean revalidating = stored != null && stored.revalidatable();

        int attempts = 0;
        RuntimeException transportFailure = null;
        ResponseEntity<byte[]> upstream = null;

        while (true) {
            attempts++;
            try {
                upstream = send(exchange, credential, presented, revalidating ? stored : null);
                transportFailure = null;
            } catch (GatewayHttpClientConfig.BlockedDestinationException ex) {
                throw ex;
            } catch (RuntimeException ex) {
                upstream = null;
                transportFailure = ex;
            }

            if (upstream == null) {
                if (!retryable(exchange.method(), attempts)) break;
                pause(backoffMillis(attempts));
                continue;
            }

            int status = upstream.getStatusCode().value();
            if (status == HttpStatus.NOT_MODIFIED.value() && revalidating)
                return revalidated(key, stored, upstream, exchange, attempts, presented, secret);
            if (!TRANSIENT.contains(status)) break;

            Long retryAfter = CachePolicy.retryAfterSeconds(upstream.getHeaders());
            // A pause longer than a retry could absorb is a rate limit, not a hiccup. Honour it for
            // everyone rather than letting each caller discover it in turn.
            if (retryAfter != null && retryAfter * 1000 > properties.retry().maxBackoffMillis()) {
                cooldown.pause(
                        cooldownKey,
                        provider.getId(),
                        status,
                        Math.min(retryAfter, properties.throttle().maxCooldownSeconds()));
                break;
            }
            if (!retryable(exchange.method(), attempts)) break;
            pause(retryAfter != null ? retryAfter * 1000 : backoffMillis(attempts));
        }

        if (transportFailure != null) {
            var rescued = stale(stored, "upstream unreachable");
            if (rescued.isEmpty()) throw transportFailure;
            log.warn("Serving a stale response after an upstream failure [correlationId={}]", exchange.correlationId());
            return rescued.get();
        }
        if (TRANSIENT.contains(upstream.getStatusCode().value())) {
            var rescued = stale(
                    stored, "upstream returned " + upstream.getStatusCode().value());
            if (rescued.isPresent()) return rescued.get();
        }
        // Both values are scrubbed: what was sent, and what it was obtained with. An upstream that
        // echoes either one back must not have it stored or returned.
        return deliver(upstream, exchange, key, attempts, presented, secret);
    }

    private ResponseEntity<byte[]> send(
            GatewayExchange exchange, Credential credential, String secret, ResponseCache.Entry validator) {
        var target = credential.getAuthType().inQuery()
                ? withQueryParameter(
                        exchange.route().toTargetUri(exchange.provider().getBaseUrl()),
                        credential.getQueryParameter(),
                        secret)
                : exchange.route().toTargetUri(exchange.provider().getBaseUrl());
        var spec = web.method(exchange.method()).uri(target).headers(headers -> {
            headers.addAll(exchange.headers());
            injectCredential(headers, credential, secret);
            headers.set(CorrelationIdFilter.REQUEST_HEADER, exchange.correlationId());
            if (validator != null) {
                if (validator.etag() != null) headers.set(HttpHeaders.IF_NONE_MATCH, validator.etag());
                else if (validator.lastModified() != null)
                    headers.set(HttpHeaders.IF_MODIFIED_SINCE, validator.lastModified());
            }
        });
        var outbound = exchange.body() != null && exchange.body().length > 0 ? spec.bodyValue(exchange.body()) : spec;
        var response =
                outbound.exchangeToMono(result -> result.toEntity(byte[].class)).block();
        if (response == null) throw new IllegalStateException("Provider returned no response");
        return response;
    }

    /**
     * Appends the key an API expects in its URL rather than in a header.
     *
     * <p>Done here, at the last moment, and never before the cache key is computed: a stored entry is
     * addressed by the credential it was fetched with, so a secret in the address would put the
     * secret itself into a cache key and into anything that ever prints one.
     */
    private static URI withQueryParameter(URI target, String parameter, String secret) {
        return UriComponentsBuilder.fromUri(target)
                .queryParam(parameter, UriUtils.encode(secret, StandardCharsets.UTF_8))
                .build(true)
                .toUri();
    }

    /** Turns an upstream answer into what the caller gets, and decides whether to keep a copy. */
    private Delivery deliver(
            ResponseEntity<byte[]> upstream, GatewayExchange exchange, String key, int attempts, String... secrets) {
        var provider = exchange.provider();
        var credential = exchange.grant().getCredential();
        // Both halves of the answer are scrubbed, not just the body: an upstream that reflects what
        // it was sent commonly does it in a header, and a stored entry keeps whatever is returned.
        var headers = SecretRedactor.scrubHeaders(HeaderPolicy.filterResponseHeaders(upstream.getHeaders()), secrets);
        byte[] body = Objects.requireNonNullElse(
                SecretRedactor.scrub(upstream.getBody(), upstream.getHeaders(), secrets), new byte[0]);
        int status = upstream.getStatusCode().value();

        // A write makes what was read about that resource questionable. RFC 9111 invalidates the
        // request URI; Janus also drops what lives under it, because a member changing is the
        // ordinary reason a collection listing is now wrong.
        if (!SAFE.contains(exchange.method()) && status < 400)
            cache.invalidateResource(
                    provider.getId(), credential.getId(), exchange.route().decodedPath());

        Long freshSeconds = null;
        if (key != null) {
            var storability = CachePolicy.evaluate(
                    upstream.getStatusCode(),
                    upstream.getHeaders(),
                    provider.getCacheTtlSeconds(),
                    properties.cache().staleIfErrorSeconds());
            if (storability.storable()) {
                cache.store(key, entry(status, headers, body, upstream.getHeaders(), exchange, storability));
                freshSeconds = storability.freshSeconds();
            }
        }
        var outcome = key == null ? CacheStatus.BYPASS : CacheStatus.MISS;
        cache.record(outcome);
        return new Delivery(status, headers, body, attempts, outcome, null, freshSeconds, null);
    }

    /** A 304 confirms what is stored; the stored body is returned and its freshness restarted. */
    private Delivery revalidated(
            String key,
            ResponseCache.Entry stored,
            ResponseEntity<byte[]> upstream,
            GatewayExchange exchange,
            int attempts,
            String... secrets) {
        var storability = CachePolicy.evaluate(
                HttpStatus.OK,
                upstream.getHeaders(),
                exchange.provider().getCacheTtlSeconds(),
                properties.cache().staleIfErrorSeconds());
        long now = System.currentTimeMillis();
        long fresh = storability.storable() ? storability.freshSeconds() : 0;
        long stale = storability.storable()
                ? storability.staleSeconds()
                : properties.cache().staleIfErrorSeconds();
        // A 304 carries updated metadata for the stored representation, not a new one.
        var headers = copy(stored.headers());
        SecretRedactor.scrubHeaders(HeaderPolicy.filterResponseHeaders(upstream.getHeaders()), secrets)
                .forEach(headers::put);
        var refreshed = new ResponseCache.Entry(
                stored.status(),
                HttpHeaders.readOnlyHttpHeaders(headers),
                stored.body(),
                // Either validator may legitimately be absent on both sides — an ETag without a
                // Last-Modified is the ordinary shape of a JSON API's response — so the two are
                // carried over independently rather than one being required to exist.
                updated(upstream.getHeaders().getETag(), stored.etag()),
                updated(upstream.getHeaders().getFirst(HttpHeaders.LAST_MODIFIED), stored.lastModified()),
                now,
                now + fresh * 1000,
                now + (fresh + stale) * 1000,
                stored.vary());
        if (key != null) cache.refresh(key, refreshed);
        cache.record(CacheStatus.REVALIDATED);
        return new Delivery(
                refreshed.status(),
                refreshed.headers(),
                refreshed.body(),
                attempts,
                CacheStatus.REVALIDATED,
                0L,
                fresh,
                null);
    }

    /** The upstream's value when it restated one, the stored one otherwise; null when neither exists. */
    private static String updated(String fromUpstream, String stored) {
        return fromUpstream != null ? fromUpstream : stored;
    }

    /** The last resort before an error: an expired answer is usually better than no answer. */
    private Optional<Delivery> stale(ResponseCache.Entry stored, String reason) {
        long now = System.currentTimeMillis();
        if (stored == null || !stored.servableStale(now)) return Optional.empty();
        cache.record(CacheStatus.STALE);
        // No freshness is announced for an answer that has none: this one is served because the
        // alternative was an error, and a caller must not go on to reuse it on that basis.
        return Optional.of(new Delivery(
                stored.status(),
                stored.headers(),
                stored.body(),
                0,
                CacheStatus.STALE,
                stored.ageSeconds(now),
                null,
                reason));
    }

    private ResponseCache.Entry entry(
            int status,
            HttpHeaders returned,
            byte[] body,
            HttpHeaders upstream,
            GatewayExchange exchange,
            CachePolicy.Storability storability) {
        long bornAt = System.currentTimeMillis() - CachePolicy.upstreamAgeSeconds(upstream) * 1000;
        var vary = new LinkedHashMap<String, String>();
        for (String name : CachePolicy.varyNames(upstream))
            vary.put(name, ResponseCache.varyValue(exchange.headers(), name));
        return new ResponseCache.Entry(
                status,
                HttpHeaders.readOnlyHttpHeaders(copy(returned)),
                body,
                upstream.getETag(),
                upstream.getFirst(HttpHeaders.LAST_MODIFIED),
                bornAt,
                bornAt + storability.freshSeconds() * 1000,
                bornAt + (storability.freshSeconds() + storability.staleSeconds()) * 1000,
                vary);
    }

    /** Stamps what Janus decided onto the response, so the caller can see it without asking. */
    private GatewayOutcome complete(Delivery delivery, RateLimiter.Decision client) {
        var headers = copy(delivery.headers());
        headers.set(CACHE_HEADER, delivery.cacheStatus().name());
        if (delivery.ageSeconds() != null) headers.set(HttpHeaders.AGE, Long.toString(delivery.ageSeconds()));
        announceFreshness(headers, delivery.freshSeconds());
        if (delivery.attempts() > 1) headers.set(ATTEMPTS_HEADER, Integer.toString(delivery.attempts()));
        if (client.measured()) {
            headers.set(LIMIT_HEADER, Integer.toString(client.limit()));
            headers.set(REMAINING_HEADER, Long.toString(client.remaining()));
            headers.set(RESET_HEADER, Long.toString(client.resetSeconds()));
        }
        var detail = new StringBuilder(delivery.cacheStatus().name());
        if (delivery.attempts() > 1)
            detail.append(", ").append(delivery.attempts()).append(" attempts");
        if (delivery.note() != null) detail.append(", ").append(delivery.note());
        return new GatewayOutcome(
                HttpStatusCode.valueOf(delivery.status()),
                headers,
                delivery.body(),
                delivery.cacheStatus(),
                detail.toString());
    }

    /**
     * Tells the caller how long its own copy may be reused, when the upstream did not say.
     *
     * <p>A provider given a default TTL is one Janus reuses answers from on a policy the upstream
     * never stated. Without this, the caller was told nothing — and a response with no
     * {@code Cache-Control} at all leaves the platform's default {@code no-store} in place, so the
     * only client that could benefit from that policy was Janus itself. Stating the remaining
     * lifetime makes the same reuse available one hop further out, which is where a chatty client
     * loop actually costs something.
     *
     * <p>Always {@code private}, and this is the part that matters when several accounts call the
     * same API. A stored answer here is addressed by the credential it was fetched with; one hop
     * further out, nothing knows that. A shared cache between Janus and the client would be keyed on
     * the URL alone and would hand one account's answer to another's request. {@code private} is what
     * says: reusable by the one caller who received it, by nobody in between.
     *
     * <p>An upstream that stated its own policy keeps it, whatever it says. Restating it here would
     * be Janus overruling the only party that knows what the resource is.
     */
    private static void announceFreshness(HttpHeaders headers, Long freshSeconds) {
        if (freshSeconds == null || freshSeconds <= 0) return;
        if (headers.containsHeader(HttpHeaders.CACHE_CONTROL) || headers.containsHeader(HttpHeaders.EXPIRES)) return;
        headers.set(HttpHeaders.CACHE_CONTROL, "private, max-age=" + freshSeconds);
    }

    /**
     * Presents the credential the way this API expects it.
     *
     * <p>Exhaustive over the enum and without a default, deliberately: adding a strategy should stop
     * the compiler here rather than silently send a request with no credential on it.
     */
    private void injectCredential(HttpHeaders headers, Credential credential, String secret) {
        switch (credential.getAuthType()) {
                // An obtained token is a bearer token; what differs is only where it came from.
            case BEARER, OAUTH2_CLIENT_CREDENTIALS -> headers.setBearerAuth(secret);
            case API_KEY_HEADER -> headers.set(credential.getHeaderName(), secret);
                // Already in the address; see withQueryParameter.
            case API_KEY_QUERY -> {}
                // The stored value is "username:password"; HttpHeaders#setBasicAuth(String) expects it pre-encoded.
            case BASIC -> headers.setBasicAuth(
                    Base64.getEncoder().encodeToString(secret.getBytes(StandardCharsets.UTF_8)));
                // Nothing to present. The request travels with the caller's forwarded headers alone.
            case NONE -> {}
        }
    }

    private boolean retryable(HttpMethod method, int attempts) {
        return IDEMPOTENT.contains(method) && attempts <= properties.retry().maxAttempts();
    }

    /** Exponential, capped, and jittered, so retries from many callers do not land together. */
    private long backoffMillis(int attempt) {
        long base = Math.min(
                properties.retry().initialBackoffMillis() * (1L << Math.min(attempt - 1, 16)),
                properties.retry().maxBackoffMillis());
        return base <= 1 ? 1 : base / 2 + ThreadLocalRandom.current().nextLong(base / 2 + 1);
    }

    private void pause(long millis) {
        try {
            Thread.sleep(Math.max(1, Math.min(millis, properties.retry().maxBackoffMillis())));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted between upstream attempts", ex);
        }
    }

    private HttpHeaders rateLimitHeaders(RateLimiter.Decision decision) {
        var headers = new HttpHeaders();
        if (decision.measured()) {
            headers.set(LIMIT_HEADER, Integer.toString(decision.limit()));
            headers.set(REMAINING_HEADER, Long.toString(decision.remaining()));
            headers.set(RESET_HEADER, Long.toString(decision.resetSeconds()));
        }
        return headers;
    }

    private static HttpHeaders copy(HttpHeaders source) {
        var headers = new HttpHeaders();
        headers.addAll(source);
        return headers;
    }
}
