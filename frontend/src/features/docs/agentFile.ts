/**
 * The file a coding agent reads before it writes a call.
 *
 * Claude Code, Codex and the rest are handed a repository and a task — "fetch the user's playlists
 * from Spotify" — and what they lack is the one thing that is not in the repository: that this
 * project reaches third-party APIs through a gateway, with two headers and no secret of its own.
 * Left to guess, an agent installs an SDK, invents an `Authorization` header, and asks the developer
 * for Spotify's client secret. That is the failure this file exists to prevent, so it leads with the
 * rule, names the APIs the service may already call, and states the prohibitions as prohibitions.
 *
 * It is read on every task, which makes its length a running cost: every line is a rule an agent
 * would otherwise get wrong, in the shortest form that stays unambiguous. Tables are indented blocks,
 * prose is clauses, and nothing is said twice.
 *
 * English whatever the console's language: it is read by a model prompted in English, and it lands in
 * a repository whose other instruction files are in English.
 *
 * The one value it never carries is the key. This file belongs in the repository; the key does not.
 */

export const AGENT_FILE_NAME = 'JANUS.md';

export type AgentApi = { name: string; slug: string };

export type AgentTarget = {
  /** Where Janus answers, taken from the address the console was opened at. */
  origin: string;
  /** The calling service this file is written for, and the id it presents. */
  serviceName: string;
  applicationId: string;
  /** Every API that service may already call, in full: no path within one is off limits. */
  apis: AgentApi[];
};

export const AGENT_PLACEHOLDER: AgentTarget = {
  origin: 'https://janus.example.com',
  serviceName: 'your-service',
  applicationId: '00000000-0000-0000-0000-000000000000',
  apis: [],
};

export function agentFile({ origin, serviceName, applicationId, apis }: AgentTarget): string {
  return `# Janus gateway

This project calls third-party APIs through Janus, which holds each API's own secret and adds it on
the way out. Never hold, request, or hardcode an API secret here.

## Environment

    JANUS_URL=${origin}
    JANUS_APPLICATION_ID=${applicationId}   # this service (${serviceName}); not a secret
    JANUS_API_KEY=…   # secret; read from env or vault, never committed

## Calling

Send the request you would have sent to the API, with its address replaced by
\`$JANUS_URL/gateway/<slug>\` and two headers added:

    X-Janus-Application-Id: $JANUS_APPLICATION_ID
    X-Janus-Api-Key: $JANUS_API_KEY

- The path after the slug is forwarded as is: \`/gateway/spotify/v1/me\` reaches the API at \`/v1/me\`.
- Method, query, body and response are unchanged. No SDK: use the stock HTTP client.
- Never send \`Authorization\` or cookies (Janus strips them), and never the API's own key.
- Body limit 10 MiB. Janus waits 30 s upstream, so set the client timeout above 35 s.
- One client per service, both headers set there and never at a call site. Do not retry POST or
  PATCH; Janus does not either.

## Already handled — do not build it

    response cache    Janus reuses upstream responses; \`X-Janus-Cache\` reports HIT, MISS, STALE…
    retries, backoff  GET, HEAD, PUT, DELETE are retried; a failing API is paused for everyone
    rate limiting     per-caller quota, answered as 429 with \`Retry-After\`
    secret storage    the API's secret lives in the vault, never in this project
    OAuth2 tokens     client-credentials tokens are fetched, cached and renewed by Janus
    audit trail       every call is recorded with its correlation id

So: no cache layer, no retry or backoff wrapper, no circuit breaker, no token store, no entry in
\`.env\` for the API's own credentials. Read the response headers instead of reimplementing any of it.

## APIs this service may call

${apiList(apis)}

Any path and any method under a slug above is forwarded. What the API itself allows for the secret
Janus presents is the only limit; an API at a slug not listed is not reachable at all.

## Errors

\`Content-Type: application/problem+json\` means Janus refused and its \`detail\` says why; any other
media type means the API itself answered.

    400  dot segment, // or encoded separator in the path
    401  headers missing, malformed, or wrong
    403  this service is not connected to that API, or the connection is paused
    404  no API at that slug, or its record is disabled
    405  a method the gateway does not forward
    413  body over the limit
    429  a quota was reached; honour Retry-After
    502  the API failed, or its address is no longer permitted

Log \`X-Janus-Correlation-Id\`, present on every response, beside your own errors. Also returned:
\`X-Janus-Cache\`, \`X-Janus-RateLimit-Limit/-Remaining/-Reset\`, \`X-Janus-Upstream-Attempts\`,
and \`Retry-After\` on a 429.

## If the API you need is not listed

Stop and ask the operator to register it. Do not call the API directly, and never ask anyone for its
key. In the Janus console at ${origin}:

1. **Connections → Connect**: the API's name and base address, e.g. \`https://api.spotify.com\`. The
   gateway slug is derived from the name.
2. **The secret and the caller**: how that API expects its secret (bearer, custom header, query
   parameter, basic, or OAuth2 client credentials) and its value — it goes to the vault, not into
   this repository — then pick ${serviceName === AGENT_PLACEHOLDER.serviceName ? 'this service' : `\`${serviceName}\``} as the caller.
3. The key is shown **once**, on the last screen: it becomes \`JANUS_API_KEY\`, and the id beside it
   \`JANUS_APPLICATION_ID\`.

Then add the new slug to this file. A lost key is rotated from **Connections → the connection →
rotate**, never recovered.
`;
}

/** One line per API: the name, and the gateway path every one of its routes hangs off. */
function apiList(apis: AgentApi[]): string {
  if (apis.length === 0) return 'None yet. Follow the next section before writing any call.';

  return apis.map(({ name, slug }) => `- ${name} — \`/gateway/${slug}/…\``).join('\n');
}
