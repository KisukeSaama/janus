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

export type AgentApi = {
  name: string;
  slug: string;
  /** Whether this API's responses reach the caller as JSON whatever it answers in. */
  normalizeJson: boolean;
  /** The path this service may call under, when its grant names one. */
  pathPrefix?: string;
  /** The methods it may use, when its grant names any. Empty is all of them. */
  methods: string[];
  /** Whether somebody has connected an account here, so calls must say whom they speak for. */
  connected: boolean;
};

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
    two identities    an API's own token or a connected person's; say which with X-Janus-Identity
    audit trail       every call is recorded with its correlation id

So: no cache layer, no retry or backoff wrapper, no circuit breaker, no token store, no entry in
\`.env\` for the API's own credentials. Read the response headers instead of reimplementing any of it.

## APIs this service may call

${apiList(apis)}

Unless a line above narrows it, any path and any method under a slug is forwarded, and what the API
itself allows for the secret Janus presents is the only limit. A line that names a path or a set of
methods is a ceiling this service was given: anything outside it is refused with 403 by Janus, before
the API is called. An API at a slug not listed is not reachable at all.
${conversionNote(apis)}

## Errors

\`Content-Type: application/problem+json\` means Janus refused and its \`detail\` says why; any other
media type means the API itself answered.

    400  dot segment, // or encoded separator in the path
    401  headers missing, malformed, or wrong
    403  not connected to that API, connection paused, or a path or method outside what it was given
    404  no API at that slug, or its record is disabled
    405  a method the gateway does not forward
    413  body over the limit
    429  a quota was reached; honour Retry-After
    502  the API failed, or its address is no longer permitted

Log \`X-Janus-Correlation-Id\`, present on every response, beside your own errors. Also returned:
\`X-Janus-Cache\`, \`X-Janus-Identity\`, \`X-Janus-RateLimit-Limit/-Remaining/-Reset\`,
\`X-Janus-Upstream-Attempts\`, and \`Retry-After\` on a 429.

## Two identities, where an API has both

An API marked **app and account** above answers both for this service and for the person who
connected their account. Nothing in a URL says which an endpoint wants, so **state it**: send

    X-Janus-Identity: app        # the API's own data: a catalogue, a search, a public resource
    X-Janus-Identity: account    # data belonging to the person who connected: their library, their profile

on every call to those APIs, decided from what the endpoint returns rather than guessed at call time.
It never reaches the API, and \`X-Janus-Identity\` on the response repeats what was used.

Leaving it off is not an error: Janus presents the application, and on a refusal tries the account and
remembers the answer. But it costs an extra round trip against the API's quota, it makes the first
call to a new endpoint behave differently from every call after it, and it does not happen at all
where a second attempt could repeat a write: a \`POST\` refused with 403 is returned as it came.
Send the header.

## If the API you need is not listed

Stop and ask the operator to register it. Do not call the API directly, and never ask anyone for its
key. In the Janus console at ${origin}, two records are needed:

1. **Connections → Register an API**: its name and base address, e.g. \`https://api.spotify.com\` —
   the gateway slug is derived from the name — then how that API expects its secret (bearer, custom
   header, query parameter, basic, OAuth2 client credentials, or nothing at all for an open API) and
   its value, which goes to the vault and not into this repository.
2. **Registry → Applications**: on ${serviceName === AGENT_PLACEHOLDER.serviceName ? 'this service' : `\`${serviceName}\``}, add the new API under
   **Subscribed APIs**. Registering an API does not authorise any caller; without that subscription
   the gateway answers 403.

\`JANUS_APPLICATION_ID\` is on that service's page. \`JANUS_API_KEY\` appears **once**, on the screen
that issues it: a lost key is rotated from the connection or from the service, and the previous one
stops working immediately, tokens included.

Then add the new slug to this file.
`;
}

/**
 * One line per API: the name, the gateway path its routes hang off, what it answers in, and the two
 * things a caller cannot infer. Those two are how much of it this service may reach, and whether it
 * has to say whom a call speaks for.
 */
function apiList(apis: AgentApi[]): string {
  if (apis.length === 0) return 'None yet. Follow the next section before writing any call.';

  return apis
    .map((api) => {
      const notes = [
        api.normalizeJson ? '**JSON**' : '',
        api.pathPrefix ? `only \`${api.pathPrefix}\` and under` : '',
        api.methods.length > 0 ? `only ${api.methods.join(', ')}` : '',
        api.connected ? 'app **and** account' : '',
      ].filter(Boolean);
      return `- **${api.name}**: \`/gateway/${api.slug}/…\`${notes.length > 0 ? ` (${notes.join(', ')})` : ''}`;
    })
    .join('\n');
}

/**
 * Written only when something is actually converted, and worth its lines when it is.
 *
 * An agent handed a Plex task reads Plex's documentation, sees XML, and reaches for a parser and a
 * dependency — the same reflex that makes it invent an `Authorization` header. It cannot know the
 * gateway restated the response, because the only place that is written down is here.
 */
function conversionNote(apis: AgentApi[]): string {
  if (!apis.some((api) => api.normalizeJson)) return '';

  return `
APIs marked **JSON** reach you as JSON whatever their own documentation shows: Janus converts XML,
form-encoded and NDJSON responses on the way back. Parse JSON, add no XML parser and no new
dependency for it. \`X-Janus-Transform\` names the conversion that ran, or says why none did — and
sending \`Accept: application/xml\` returns the untouched original if you ever need it.
`;
}
