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

import io.janus.credentials.AuthType;
import io.janus.credentials.Credential;
import io.janus.credentials.Identity;
import io.janus.credentials.RequestSigner;
import io.janus.credentials.UpstreamTokenProvider;
import io.janus.gateway.transform.ArrayPaths;
import io.janus.gateway.transform.JsonNormalizer;
import io.janus.openbao.OpenBaoClient;
import io.janus.shared.CorrelationIdFilter;
import io.janus.shared.ErrorCode;

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
    public static final String IDENTITY_HEADER = "X-Janus-Identity";
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

    /** Stateless, and a pure function of the request; see {@link RequestSigner}. */
    private static final RequestSigner SIGNER = new RequestSigner();

    private final WebClient web;
    private final WebClient privateWeb;
    private final OpenBaoClient bao;
    private final UpstreamTokenProvider tokens;
    private final ResponseCache cache;
    private final RateLimiter limiter;
    private final UpstreamCooldown cooldown;
    private final JsonNormalizer normalizer;
    private final IdentityMemory identities;
    private final GatewayTrafficProperties properties;
    private final long responseTimeoutMillis;

    /** Identical in-flight reads, so a cold entry under load costs one upstream call, not hundreds. */
    private final ConcurrentMap<String, Leader> inFlight = new ConcurrentHashMap<>();

    /**
     * A call somebody else is already making, and the request it was made for.
     *
     * <p>The headers are kept because {@code Vary} is not knowable until the answer arrives. The key
     * covers {@code Accept} and {@code Accept-Language}, which is what these requests are told apart
     * by beforehand; whether the upstream considers anything else part of this representation's
     * identity is something only its response says, and by then the followers are already waiting.
     */
    private record Leader(CompletableFuture<Delivery> result, HttpHeaders request) {}

    public GatewayTrafficService(
            WebClient gatewayWebClient,
            WebClient gatewayPrivateWebClient,
            OpenBaoClient bao,
            UpstreamTokenProvider tokens,
            ResponseCache cache,
            RateLimiter limiter,
            UpstreamCooldown cooldown,
            JsonNormalizer normalizer,
            IdentityMemory identities,
            GatewayTrafficProperties properties,
            @Value("${janus.gateway.response-timeout-seconds:30}") long responseTimeoutSeconds) {
        this.web = gatewayWebClient;
        this.privateWeb = gatewayPrivateWebClient;
        this.bao = bao;
        this.tokens = tokens;
        this.cache = cache;
        this.limiter = limiter;
        this.cooldown = cooldown;
        this.normalizer = normalizer;
        this.identities = identities;
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
            String note,
            Identity identity) {

        Delivery as(CacheStatus status) {
            return new Delivery(this.status, headers, body, attempts, status, ageSeconds, freshSeconds, note, identity);
        }

        /** The same answer, with something the journal should say about how it was reached. */
        Delivery noting(String note) {
            return new Delivery(status, headers, body, attempts, cacheStatus, ageSeconds, freshSeconds, note, identity);
        }
    }

    public GatewayOutcome forward(GatewayExchange exchange) {
        var provider = exchange.provider();
        var credential = exchange.grant().getCredential();
        var identity = chosen(exchange, credential);

        // 1. The caller's own allowance. Never waited out: this limit exists to be felt.
        var client = limiter.tryAcquire(
                "grant:" + exchange.grant().getId(),
                exchange.grant().getRateLimitPerMinute(),
                exchange.grant().getRateLimitBurst());
        if (!client.allowed())
            throw new Throttled(
                    ErrorCode.RATE_LIMIT_GRANT,
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
                        identity,
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
                        exchange,
                        new Delivery(
                                unchanged ? HttpStatus.NOT_MODIFIED.value() : stored.status(),
                                stored.headers(),
                                unchanged ? new byte[0] : stored.body(),
                                0,
                                CacheStatus.HIT,
                                stored.ageSeconds(now),
                                stored.lifetimeSeconds(),
                                null,
                                identity),
                        client);
            }
        }

        // Nothing fresh to answer a condition with, so the caller's exchange goes out as its own:
        // no stored validator mixed into it, and nothing kept from what comes back.
        if (conditionalCaller) return complete(exchange, call(exchange, identity, null, null), client);

        try {
            return complete(
                    exchange,
                    mayReuse ? coalesced(key, exchange, identity, stored) : call(exchange, identity, key, null),
                    client);
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
     * received.
     *
     * <p>A leader that has taken longer than a response is allowed to take is not going to answer
     * this request either, so it is stood down and the attempt is made again. Stood down rather than
     * merely abandoned: whoever notices first removes it, and the followers behind then coalesce on
     * the one that replaces it. Leaving it in place and calling directly would mean every request that
     * had been waiting on a stuck leader hitting the provider at the same moment, turning a stall into
     * a burst against exactly the upstream that was already struggling.
     */
    private Delivery coalesced(String key, GatewayExchange exchange, Identity identity, ResponseCache.Entry stored) {
        while (true) {
            var leader = inFlight.get(key);
            if (leader == null) {
                var mine = new Leader(new CompletableFuture<>(), exchange.headers());
                if (inFlight.putIfAbsent(key, mine) != null) continue;
                try {
                    var delivery = call(exchange, identity, key, stored);
                    mine.result().complete(delivery);
                    return delivery;
                } catch (RuntimeException ex) {
                    mine.result().completeExceptionally(ex);
                    throw ex;
                } finally {
                    inFlight.remove(key, mine);
                }
            }
            try {
                var delivery = leader.result().get(responseTimeoutMillis, TimeUnit.MILLISECONDS);
                // What came back may name headers this follower does not agree with the leader on.
                // The stored-response path checks that before serving one; this path had no way to,
                // because Vary only becomes known once the answer exists. Checked here instead, and
                // a follower that would be handed the wrong representation makes its own call.
                if (!sameRepresentation(delivery, leader.request(), exchange.headers()))
                    return call(exchange, identity, key, stored);
                cache.record(CacheStatus.COALESCED);
                return delivery.as(CacheStatus.COALESCED);
            } catch (TimeoutException ex) {
                inFlight.remove(key, leader);
                continue;
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

    /**
     * Whether the answer the leader was given is an answer to this request too.
     *
     * <p>{@code Vary: *} is never shared: the upstream is saying the representation depends on
     * something it will not name, so nothing can be concluded about a second request.
     */
    private static boolean sameRepresentation(Delivery delivery, HttpHeaders leader, HttpHeaders follower) {
        for (String name : CachePolicy.varyNames(delivery.headers())) {
            if (name.equals("*")) return false;
            if (!Objects.equals(ResponseCache.varyValue(leader, name), ResponseCache.varyValue(follower, name)))
                return false;
        }
        return true;
    }

    /**
     * One call, and the recovery from the one failure a gateway holding two identities can repair.
     *
     * <p>A refusal is not read as an answer about the endpoint until the obvious explanation has been
     * ruled out. An expired token is refused exactly like the wrong identity is, and the two are told
     * apart the only way they can be: by dropping the held token and asking again as the same
     * identity. Skipping that step means a token that merely aged out teaches Janus that an endpoint
     * belongs to somebody else, and it would go on sending it there.
     *
     * <p>Only then is the other identity tried, and only if it succeeds is anything learned. Two
     * refusals mean the endpoint refuses this credential, which is a fact about the credential rather
     * than about which of its identities to present — so the caller receives the first answer, the one
     * for the identity that was actually meant, and nothing is remembered.
     *
     * <p>None of it happens where sending the request twice could mean doing the thing twice; see
     * {@link #replayable}.
     */
    private Delivery call(GatewayExchange exchange, Identity identity, String key, ResponseCache.Entry stored) {
        var credential = exchange.grant().getCredential();
        var delivery = attempt(exchange, identity, key, stored);
        if (!refused(delivery.status()) || exchange.pinned() != null) return delivery;
        boolean replayable = replayable(exchange.method(), delivery.status());

        // A held token may simply have aged out. Costs one call, and only on a refusal.
        if (replayable && exchanges(credential, identity)) {
            tokens.invalidate(credential.getId(), identity);
            delivery = attempt(exchange, identity, key, null);
            if (!refused(delivery.status())) return delivery;
        }

        var other = identity == Identity.APP ? Identity.ACCOUNT : Identity.APP;
        if (!available(exchange, other)) return delivery;
        // Said in the journal rather than passed over quietly: this is the one refusal Janus could
        // have repaired and deliberately did not, and the caller's fix, pinning X-Janus-Identity, is
        // not one it can arrive at from the upstream's own 403.
        if (!replayable)
            return delivery.noting("identity not replayed, " + exchange.method() + " may have taken effect");

        // The store is addressed by identity, so the replay gets its own key — and no stored entry,
        // which belonged to the identity that was just refused.
        var replayed = attempt(exchange, other, replayKey(exchange, key, other), null);
        if (refused(replayed.status())) return delivery;

        identities.remember(
                credential.getId(), exchange.method().name(), exchange.route().decodedPath(), other);
        log.info(
                "{} {} answers to the {} identity; remembered [correlationId={}]",
                exchange.method(),
                RouteTemplate.of(exchange.route().decodedPath()),
                other.wire(),
                exchange.correlationId());
        return replayed;
    }

    /** Everything from "may we call this provider" to "what do we do when it will not answer". */
    private Delivery attempt(GatewayExchange exchange, Identity identity, String key, ResponseCache.Entry stored) {
        var provider = exchange.provider();
        var credential = exchange.grant().getCredential();
        String cooldownKey = UpstreamCooldown.key(provider.getId(), credential.getId());

        var paused = cooldown.remaining(cooldownKey);
        if (paused.isPresent())
            return stale(stored, identity, "provider in cooldown")
                    .orElseThrow(() -> new Throttled(
                            ErrorCode.PROVIDER_COOLDOWN,
                            "Provider asked for a pause and Janus is honouring it",
                            paused.get(),
                            new HttpHeaders()));

        var ceiling = limiter.acquire(
                "provider:" + provider.getId(),
                provider.getRateLimitPerMinute(),
                provider.getRateLimitBurst(),
                properties.throttle().maxWaitMillis());
        if (!ceiling.allowed())
            return stale(stored, identity, "provider allowance exhausted")
                    .orElseThrow(() -> new Throttled(
                            ErrorCode.RATE_LIMIT_PROVIDER,
                            "Provider rate limit reached",
                            ceiling.retryAfterSeconds(),
                            new HttpHeaders()));

        // Authorisation is complete and the answer cannot come from anywhere else; only now does
        // credential material exist in this process — and for an open API called as itself, never:
        // nothing was stored for it, so nothing is fetched, and the call goes out as anonymous as it
        // was meant to be.
        //
        // Which stored value is read follows from whom the call speaks for. The account identity
        // exchanges with the connection's OAuth client, which is usually the very same value the
        // application stores and occasionally one of its own.
        boolean asAccount = identity == Identity.ACCOUNT;
        String secretPath = asAccount ? credential.connectionSecretPath() : credential.getSecretPath();
        boolean stores = asAccount || !credential.getAuthType().anonymous();
        String secret = stores ? bao.read(secretPath) : null;
        // What actually travels. For most strategies it is the stored value; for an exchange it is the
        // bearer token Janus obtained with it, held until close to its expiry.
        //
        // Fails closed: a failed exchange throws rather than sending the request without credentials,
        // which is the one outcome that would look to the upstream like an anonymous call.
        String presented = exchanges(credential, identity) ? tokens.tokenFor(credential, identity, secret) : secret;
        boolean revalidating = stored != null && stored.revalidatable();

        int attempts = 0;
        RuntimeException transportFailure = null;
        ResponseEntity<byte[]> upstream = null;

        while (true) {
            attempts++;
            try {
                upstream = send(exchange, credential, identity, presented, revalidating ? stored : null);
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
                return revalidated(key, stored, upstream, exchange, identity, attempts, presented, secret);
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
            var rescued = stale(stored, identity, "upstream unreachable");
            if (rescued.isEmpty()) throw transportFailure;
            log.warn("Serving a stale response after an upstream failure [correlationId={}]", exchange.correlationId());
            return rescued.get();
        }
        if (TRANSIENT.contains(upstream.getStatusCode().value())) {
            var rescued = stale(
                    stored,
                    identity,
                    "upstream returned " + upstream.getStatusCode().value());
            if (rescued.isPresent()) return rescued.get();
        }
        // Both values are scrubbed: what was sent, and what it was obtained with. An upstream that
        // echoes either one back must not have it stored or returned.
        return deliver(upstream, exchange, identity, key, attempts, presented, secret);
    }

    /**
     * Which address rule this call connects under. The check runs inside the connection pool, where
     * the provider is no longer in scope, so it is chosen here — before the request is built — rather
     * than consulted at connection time.
     */
    private WebClient client(GatewayExchange exchange) {
        return exchange.provider().isAllowPrivateDestination() ? privateWeb : web;
    }

    private ResponseEntity<byte[]> send(
            GatewayExchange exchange,
            Credential credential,
            Identity identity,
            String secret,
            ResponseCache.Entry validator) {
        // Speaking for a person means presenting a bearer token, whatever the application itself
        // presents. A destination whose key travels in the query string does not put somebody's
        // access token there too.
        var type = identity == Identity.ACCOUNT ? AuthType.BEARER : credential.getAuthType();
        var address = exchange.route().toTargetUri(exchange.provider().getBaseUrl());
        if (type.inQuery()) address = withQueryParameter(address, credential.getQueryParameter(), secret);

        // A signature covers the request as it will actually be sent, so it is computed once the
        // address and the body are both settled and nothing is added to either afterwards. It may
        // move the address itself: some APIs want the timestamp and the signature appended to it.
        var signed = type.signs()
                ? SIGNER.sign(credential.signatureSettings(), secret, exchange.method(), address, exchange.body())
                : null;
        var target = signed == null ? address : signed.uri();

        var spec = client(exchange).method(exchange.method()).uri(target).headers(headers -> {
            headers.addAll(exchange.headers());
            injectCredential(headers, type, credential, secret);
            if (signed != null) signed.headers().forEach(headers::set);
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
            ResponseEntity<byte[]> upstream,
            GatewayExchange exchange,
            Identity identity,
            String key,
            int attempts,
            String... secrets) {
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
        return new Delivery(status, headers, body, attempts, outcome, null, freshSeconds, null, identity);
    }

    /** A 304 confirms what is stored; the stored body is returned and its freshness restarted. */
    private Delivery revalidated(
            String key,
            ResponseCache.Entry stored,
            ResponseEntity<byte[]> upstream,
            GatewayExchange exchange,
            Identity identity,
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
                null,
                identity);
    }

    /** The upstream's value when it restated one, the stored one otherwise; null when neither exists. */
    private static String updated(String fromUpstream, String stored) {
        return fromUpstream != null ? fromUpstream : stored;
    }

    /** The last resort before an error: an expired answer is usually better than no answer. */
    private Optional<Delivery> stale(ResponseCache.Entry stored, Identity identity, String reason) {
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
                reason,
                identity));
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
    private GatewayOutcome complete(GatewayExchange exchange, Delivery delivery, RateLimiter.Decision client) {
        var headers = copy(delivery.headers());
        var body = normalized(exchange, headers, delivery.body());
        headers.set(CACHE_HEADER, delivery.cacheStatus().name());
        headers.set(IDENTITY_HEADER, delivery.identity().wire());
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
        String transform = headers.getFirst(JsonNormalizer.TRANSFORM_HEADER);
        if (transform != null) detail.append(", ").append(transform);
        return new GatewayOutcome(
                HttpStatusCode.valueOf(delivery.status()), headers, body, delivery.cacheStatus(), detail.toString());
    }

    /**
     * Restates the answer as JSON where the destination asks for it, whichever mechanism produced it.
     *
     * <p>Deliberately here, at the very end, and not in {@link #deliver}: what the store holds is the
     * representation the upstream sent, addressed by the credential that fetched it. Converting
     * before storing would mean a stored entry could not answer a caller that wanted the original,
     * and Janus's own conditional revalidation would be sending the upstream a validator for a
     * document it never issued. Storing the original costs one conversion per served response, and
     * buys a store that stays truthful about what it holds.
     */
    private byte[] normalized(GatewayExchange exchange, HttpHeaders headers, byte[] body) {
        if (!exchange.provider().isNormalizeJson() || !normalizer.isEnabled()) return body;

        // Stated whether or not this particular response was converted. The answer now depends on
        // the caller's Accept, and a cache in front of Janus that does not know it would hand one
        // caller's XML to another caller's request for JSON.
        if (!headers.getOrEmpty(HttpHeaders.VARY).contains(HttpHeaders.ACCEPT))
            headers.add(HttpHeaders.VARY, HttpHeaders.ACCEPT);

        var outcome = normalizer.normalize(
                body,
                headers,
                exchange.headers(),
                ArrayPaths.parse(exchange.provider().getJsonArrayPaths()));
        if (outcome.note() != null) headers.set(JsonNormalizer.TRANSFORM_HEADER, outcome.note());
        if (!outcome.converted()) return body;

        headers.setContentType(MediaType.APPLICATION_JSON);
        // The upstream issued both for the document it sent, not for this one. Suffixing them so a
        // caller could still revalidate would mean unpicking the suffix on the way back in, at every
        // point that reads a condition — and getting that wrong means revalidating against the wrong
        // representation. Dropping them costs the last hop its 304s and nothing else: the store keeps
        // the original validators and goes on using them against the upstream, which is where a
        // conditional request actually saves a body.
        headers.remove(HttpHeaders.ETAG);
        headers.remove(HttpHeaders.LAST_MODIFIED);
        return outcome.body();
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
    private void injectCredential(HttpHeaders headers, AuthType type, Credential credential, String secret) {
        switch (type) {
            // An obtained token is a bearer token; what differs is only where it came from — a
            // client secret of the application's, or a person's consent.
            case BEARER, OAUTH2_CLIENT_CREDENTIALS -> headers.setBearerAuth(secret);
            case API_KEY_HEADER -> headers.set(credential.getHeaderName(), secret);
            // The secret half signed the request and does not travel. The key half identifies who
            // signed it, which is what lets the upstream pick the secret to verify against.
            case HMAC_SIGNATURE -> {
                int separator = secret.indexOf(':');
                if (credential.getHeaderName() != null && separator >= 0)
                    headers.set(credential.getHeaderName(), secret.substring(0, separator));
            }
            // Already in the address; see withQueryParameter.
            case API_KEY_QUERY -> {}
            // The stored value is "username:password"; HttpHeaders#setBasicAuth(String) expects it pre-encoded.
            case BASIC ->
                headers.setBasicAuth(Base64.getEncoder().encodeToString(secret.getBytes(StandardCharsets.UTF_8)));
            // Nothing to present. The request travels with the caller's forwarded headers alone.
            case NONE -> {}
        }
    }

    /**
     * Whom this call speaks for.
     *
     * <p>A caller that named an identity gets it, whatever else is known. Otherwise what was learned
     * about this endpoint decides, and where nothing was learned the application goes first — not out
     * of caution, but because its answers are the ones that can be shared. A catalogue fetched with
     * one person's token is a catalogue stored for that person alone, and the store is most of what a
     * gateway is for.
     *
     * <p>The exception is a destination that stores nothing for the application and offers a
     * connection: an anonymous call there is not a lesser attempt, it is a meaningless one.
     */
    private Identity chosen(GatewayExchange exchange, Credential credential) {
        if (exchange.pinned() != null) {
            // Refused rather than quietly downgraded to the application. A caller that named an
            // identity did so because the other one answers differently, and silently sending the
            // wrong one would return somebody else's data or a confusing 401 from the API.
            if (exchange.pinned() == Identity.ACCOUNT && !credential.connected())
                throw new GatewayController.Denied(
                        HttpStatus.FORBIDDEN,
                        ErrorCode.CONNECTION_NOT_AUTHORISED,
                        "This call asked to speak for the connected account, and no account is connected");
            return exchange.pinned();
        }
        // A grant that does not admit the account identity is never answered with it, whatever was
        // learned about the endpoint. What the memory holds is which identity an endpoint answers
        // to, which is a fact about the destination; whether this application may present it is a
        // decision about the application, and the decision outranks the fact.
        if (!available(exchange, Identity.ACCOUNT)) return Identity.APP;
        var learned = identities.recall(
                credential.getId(), exchange.method().name(), exchange.route().decodedPath());
        if (learned.isPresent()) return learned.get();
        return credential.getAuthType().anonymous() ? Identity.ACCOUNT : Identity.APP;
    }

    /** Whether this call can present that identity at all: the credential holds it, the grant admits it. */
    private static boolean available(GatewayExchange exchange, Identity identity) {
        if (identity != Identity.ACCOUNT) return true;
        return exchange.grant().getCredential().connected()
                && exchange.grant().getScope().admitsAccountIdentity();
    }

    /** Whether presenting that identity means holding a token that can quietly age out. */
    private static boolean exchanges(Credential credential, Identity identity) {
        return identity == Identity.ACCOUNT || credential.getAuthType().exchanged();
    }

    /** Statuses that mean "not you", and which the other identity might therefore answer. */
    private static boolean refused(int status) {
        return status == HttpStatus.UNAUTHORIZED.value() || status == HttpStatus.FORBIDDEN.value();
    }

    /**
     * Whether a refused request may be sent a second time, refreshed or as the other identity.
     *
     * <p>The question is not whether the replay would help. It is whether the first attempt could
     * already have done something, because a replay that repeats it charges a card twice or posts a
     * message twice, and no amount of convenience is worth that.
     *
     * <p>A {@code 401} settles it whatever the method: RFC 9110 §15.5.2 is a refusal to accept the
     * request at all, pronounced before it was acted on. A {@code 403} does not. It means understood
     * and refused, and an API is perfectly entitled to accept a write, act on part of it, and then
     * refuse on a rule of its own: a quota, a read-only key, a resource belonging to somebody else.
     * So a {@code 403} is only replayed where a second identical request is harmless anyway: the
     * methods HTTP already defines as idempotent, which are the same ones this class retries.
     *
     * <p>What that costs is one case: the first {@code POST} to an endpoint that wanted the connected
     * account is refused rather than repaired, and nothing is learned from it. The caller pins
     * {@code X-Janus-Identity: account} and it works from then on. A {@code GET} on the same endpoint
     * teaches nothing here either, because a route is remembered per method.
     */
    private static boolean replayable(HttpMethod method, int status) {
        return status == HttpStatus.UNAUTHORIZED.value() || IDEMPOTENT.contains(method);
    }

    /** The same request's key under the identity it is about to be replayed as. */
    private static String replayKey(GatewayExchange exchange, String key, Identity identity) {
        if (key == null) return null;
        return CachePolicy.key(
                exchange.provider().getId(),
                exchange.grant().getCredential().getId(),
                identity,
                exchange.method().name(),
                exchange.route(),
                exchange.headers());
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
