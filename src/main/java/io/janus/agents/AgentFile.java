package io.janus.agents;

import java.util.*;
import java.util.stream.Collectors;

/**
 * The file a coding agent reads before it writes a call: {@code JANUS.md}.
 *
 * <p>Claude Code, Codex and the rest are handed a repository and a task — "fetch the user's playlists
 * from Spotify" — and what they lack is the one thing that is not in the repository: that this project
 * reaches third-party APIs through a gateway, with two headers and no secret of its own. Left to guess,
 * an agent installs an SDK, invents an {@code Authorization} header, and asks the developer for
 * Spotify's client secret. That is the failure this file exists to prevent, so it leads with the rule,
 * names the APIs the service may already call, and states the prohibitions as prohibitions.
 *
 * <p>It is read on every task, which makes its length a running cost: every line is a rule an agent
 * would otherwise get wrong, in the shortest form that stays unambiguous.
 *
 * <p>Written here rather than in the console so there is one of it. The console offers it as a
 * download and an assistant connected over MCP fetches it with a tool, and the two must never
 * describe the gateway differently.
 *
 * <p>English whatever the console's language: it is read by a model prompted in English, and it lands
 * in a repository whose other instruction files are in English. The one value it never carries is the
 * key. This file belongs in the repository; the key does not.
 */
public final class AgentFile {
    public static final String FILE_NAME = "JANUS.md";

    private AgentFile() {}

    /**
     * One API the service may call.
     *
     * @param pathPrefix the path this service may call under, when its grant names one
     * @param methods the methods it may use, when its grant names any; empty is all of them
     * @param connected whether somebody has connected an account here, so calls must say whom they
     *     speak for
     * @param graphqlPath where its GraphQL endpoint is, when it is a GraphQL API
     */
    public record Api(
            String name,
            String slug,
            boolean normalizeJson,
            String pathPrefix,
            List<String> methods,
            boolean connected,
            String graphqlPath,
            List<String> graphqlOperations,
            List<String> graphqlRootFields) {}

    /**
     * @param origin where Janus answers
     * @param serviceName the calling service this file is written for
     * @param applicationId the id it presents
     * @param apis every API that service may already call
     */
    public record Target(String origin, String serviceName, String applicationId, List<Api> apis) {}

    public static final String PLACEHOLDER_SERVICE = "your-service";

    public static Target placeholder(String origin) {
        return new Target(origin, PLACEHOLDER_SERVICE, "00000000-0000-0000-0000-000000000000", List.of());
    }

    public static String render(Target target) {
        String origin = target.origin();
        String service =
                target.serviceName().equals(PLACEHOLDER_SERVICE) ? "this service" : "`" + target.serviceName() + "`";
        return """
                # Janus gateway

                This project calls third-party APIs through Janus, which holds each API's own secret and adds it on
                the way out. Never hold, request, or hardcode an API secret here.

                ## Environment

                    JANUS_URL=%1$s
                    JANUS_APPLICATION_ID=%2$s   # this service (%3$s); not a secret
                    JANUS_API_KEY=…   # secret; read from env or vault, never committed

                ## Calling

                Send the request you would have sent to the API, with its address replaced by
                `$JANUS_URL/gateway/<slug>` and two headers added:

                    X-Janus-Application-Id: $JANUS_APPLICATION_ID
                    X-Janus-Api-Key: $JANUS_API_KEY

                - The path after the slug is forwarded as is: `/gateway/spotify/v1/me` reaches the API at `/v1/me`.
                - Method, query, body and response are unchanged. No SDK: use the stock HTTP client.
                - Never send `Authorization` or cookies (Janus strips them), and never the API's own key.
                - Body limit 10 MiB. Janus waits 30 s upstream, so set the client timeout above 35 s.
                - One client per service, both headers set there and never at a call site. Do not retry POST or
                  PATCH; Janus does not either.

                ## Already handled — do not build it

                    response cache    Janus reuses upstream responses; `X-Janus-Cache` reports HIT, MISS, STALE…
                    retries, backoff  GET, HEAD, PUT, DELETE are retried; a failing API is paused for everyone
                    rate limiting     per-caller quota, answered as 429 with `Retry-After`
                    secret storage    the API's secret lives in the vault, never in this project
                    OAuth2 tokens     client-credentials tokens are fetched, cached and renewed by Janus
                    two identities    an API's own token or a connected person's; say which with X-Janus-Identity
                    audit trail       every call is recorded with its correlation id

                So: no cache layer, no retry or backoff wrapper, no circuit breaker, no token store, no entry in
                `.env` for the API's own credentials. Read the response headers instead of reimplementing any of it.

                ## APIs this service may call

                %4$s

                Unless a line above narrows it, any path and any method under a slug is forwarded, and what the API
                itself allows for the secret Janus presents is the only limit. A line that names a path or a set of
                methods is a ceiling this service was given: anything outside it is refused with 403 by Janus, before
                the API is called. An API at a slug not listed is not reachable at all.
                %5$s%6$s
                ## Errors

                `Content-Type: application/problem+json` means Janus refused and its `detail` says why; any other
                media type means the API itself answered.

                    400  dot segment, // or encoded separator in the path; a GraphQL document Janus cannot read
                         (graphql_invalid) or one deeper than the API accepts (graphql_too_complex)
                    401  headers missing, malformed, or wrong
                    403  not connected to that API, connection paused, or a path, method, GraphQL operation or root
                         field outside what it was given
                    404  no API at that slug, or its record is disabled
                    405  a method the gateway does not forward, or a GraphQL mutation sent as GET
                    413  body over the limit
                    429  a quota was reached, or too many open subscriptions (stream_limit); honour Retry-After
                    502  the API failed, or its address is no longer permitted

                Log `X-Janus-Correlation-Id`, present on every response, beside your own errors. Also returned:
                `X-Janus-Cache`, `X-Janus-Identity`, `X-Janus-RateLimit-Limit/-Remaining/-Reset`,
                `X-Janus-Upstream-Attempts`, and `Retry-After` on a 429.

                ## Two identities, where an API has both

                An API marked **app and account** above answers both for this service and for the person who
                connected their account. Nothing in a URL says which an endpoint wants, so **state it**: send

                    X-Janus-Identity: app        # the API's own data: a catalogue, a search, a public resource
                    X-Janus-Identity: account    # data belonging to the person who connected: their library, their profile

                on every call to those APIs, decided from what the endpoint returns rather than guessed at call time.
                It never reaches the API, and `X-Janus-Identity` on the response repeats what was used.

                Leaving it off is not an error: Janus presents the application, and on a refusal tries the account and
                remembers the answer. But it costs an extra round trip against the API's quota, it makes the first
                call to a new endpoint behave differently from every call after it, and it does not happen at all
                where a second attempt could repeat a write: a `POST` refused with 403 is returned as it came.
                Send the header.

                A grant may refuse the account identity, in which case `X-Janus-Identity: account` is answered 403 with
                code `identity_not_granted` and every call goes as the service. That is an operator's decision, not a
                fault to work around: ask them, do not retry as `app` and call it equivalent.

                ## If the API you need is not listed

                Stop and ask the operator to register it. Do not call the API directly, and never ask anyone for its
                key. In the Janus console at %1$s, two records are needed:

                1. **Connections → Register an API**: its name and base address, e.g. `https://api.spotify.com` —
                   the gateway slug is derived from the name — then how that API expects its secret (bearer, custom
                   header, query parameter, basic, OAuth2 client credentials, or nothing at all for an open API) and
                   its value, which goes to the vault and not into this repository.
                2. **Registry → Applications**: on %7$s, add the new API under
                   **Subscribed APIs**. Registering an API does not authorise any caller; without that subscription
                   the gateway answers 403.

                `JANUS_APPLICATION_ID` is on that service's page. `JANUS_API_KEY` appears **once**, on the screen
                that issues it: a lost key is rotated from the connection or from the service, and the previous one
                stops working immediately, tokens included.

                Then add the new slug to this file.
                """.formatted(
                        origin,
                        target.applicationId(),
                        target.serviceName(),
                        apiList(target.apis()),
                        conversionNote(target.apis()),
                        graphqlNote(target.apis()),
                        service);
    }

    /**
     * One line per API: the name, the gateway path its routes hang off, what it answers in, and the
     * two things a caller cannot infer — how much of it this service may reach, and whether it has to
     * say whom a call speaks for.
     */
    static String apiList(List<Api> apis) {
        if (apis.isEmpty()) return "None yet. Follow the next section before writing any call.";
        return apis.stream()
                .map(api -> {
                    var notes = new ArrayList<String>();
                    if (api.normalizeJson()) notes.add("**JSON**");
                    if (hasText(api.pathPrefix())) notes.add("only `" + api.pathPrefix() + "` and under");
                    if (!api.methods().isEmpty()) notes.add("only " + String.join(", ", api.methods()));
                    if (hasText(api.graphqlPath())) notes.add("**GraphQL** at `" + api.graphqlPath() + "`");
                    if (!api.graphqlOperations().isEmpty())
                        notes.add("only "
                                + api.graphqlOperations().stream()
                                        .map(operation -> operation.toLowerCase(Locale.ROOT))
                                        .collect(Collectors.joining(", ")));
                    if (!api.graphqlRootFields().isEmpty())
                        notes.add("root fields " + String.join(", ", api.graphqlRootFields()));
                    if (api.connected()) notes.add("app **and** account");
                    return "- **" + api.name() + "**: `/gateway/" + api.slug() + "/…`"
                            + (notes.isEmpty() ? "" : " (" + String.join(", ", notes) + ")");
                })
                .collect(Collectors.joining("\n"));
    }

    /**
     * Written only when something is actually converted. An agent handed a Plex task reads Plex's
     * documentation, sees XML, and reaches for a parser — it cannot know the gateway restated the
     * response, because the only place that is written down is here.
     */
    static String conversionNote(List<Api> apis) {
        if (apis.stream().noneMatch(Api::normalizeJson)) return "";
        return """

                APIs marked **JSON** reach you as JSON whatever their own documentation shows: Janus converts XML,
                form-encoded and NDJSON responses on the way back. Parse JSON, add no XML parser and no new
                dependency for it. `X-Janus-Transform` names the conversion that ran, or says why none did — and
                sending `Accept: application/xml` returns the untouched original if you ever need it.
                """;
    }

    /**
     * Written only when a listed API is a GraphQL one. An agent's reflex there is the vendor's SDK and
     * its own auth link, which is exactly what Janus replaces.
     */
    static String graphqlNote(List<Api> apis) {
        if (apis.stream().noneMatch(api -> hasText(api.graphqlPath()))) return "";
        return """

                APIs marked **GraphQL** take an ordinary GraphQL request at the path shown, e.g.
                `$JANUS_URL/gateway/<slug>/graphql`: POST JSON `{"query", "variables", "operationName"}`, or GET for
                a query. Use a plain GraphQL client pointed there with the two headers; no vendor SDK, no auth link.

                - Queries are cached, shared between identical calls and retried like a GET; mutations never are.
                - Subscriptions: send `Accept: text/event-stream`, or open a WebSocket (`graphql-transport-ws`) on the
                  same path. A browser passes its bearer token as the subprotocol `janus.bearer.<token>`.
                - A line saying "only query" refuses mutations with 403 `graphql_operation_not_granted`; "root fields"
                  refuses any other top-level field with `graphql_field_not_granted`.
                - A 200 can still carry GraphQL errors: `X-Janus-GraphQL-Errors` says how many.
                """;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
