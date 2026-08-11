# Janus

Janus is a self-hosted credential boundary and controlled API proxy. Client applications authenticate to Janus, but only Janus can retrieve the external credential from OpenBao and inject it into an authorized upstream request.

```text
Application ──API key──> Janus gateway ──────grant──────> OpenBao
                               │                                  │
                               └──── fixed registered provider <──┘
```

The project is a Java 21 / Spring Boot 4 modular monolith with a React administration console. Each module is layered controller → service → repository: the HTTP class decides nothing, the service owns the transaction and the audit record, and the entities enforce their own invariants. PostgreSQL contains identity, provider, grant, and audit metadata. It never contains plaintext external secrets.

Requests run on virtual threads. A proxied call spends nearly all of its life waiting on someone else's API, and that wait now costs a stack rather than a platform thread, so concurrency is bounded by the database pool instead of by the servlet container.

## Security boundaries

- `/api/admin/**` authenticates a console account, by session cookie for a browser or HTTP Basic for a script, and is independent from gateway authentication. Everything not explicitly permitted is denied. CSRF protection is on, and skipped only for requests carrying an `Authorization` header — a browser never attaches one on its own, so there is nothing there to forge.
- `/gateway/{providerSlug}/**` requires `X-Janus-Application-Id` and `X-Janus-Api-Key`, or a bearer token obtained from the exchange below.
- `/oauth/token` exchanges an application's own identifier and key for a short-lived opaque bearer token, and `/oauth/revoke` drops one. Tokens are held per instance and keyed by digest, so revocation — disabling a service, rotating its key, handing it to another account — takes effect at once rather than when a signed token would have expired. Refresh tokens rotate, and a value presented twice revokes its whole family. The surface is unauthenticated by construction (no token exists yet when it is called), so it authenticates the client itself, throttles failures, and is capped on volume like the console.
- Application keys are generated with 256 bits of randomness, displayed once, and persisted only as BCrypt hashes. Verified keys are cached in memory for five minutes so a proxied request does not pay a BCrypt verification each time; administrative changes and key rotation invalidate that cache immediately.
- An unknown application identifier still costs a hash comparison, so response timing does not disclose which identifiers exist.
- The administrator password is cached the same way and for the same reason. HTTP Basic is stateless, so every console request would otherwise repeat a comparison the hash was tuned to make expensive — roughly a tenth of a second each, several per screen, and a multiplier on anything sent in bulk. Only verified passwords are remembered, keyed on a digest of the value together with the hash it was checked against; the password comes from the environment, so changing it restarts the process and empties the cache.
- Repeated authentication failures from one client are throttled on both surfaces, and every rejection is audited.
- Every client is also capped on volume, whatever the outcome: 300 console requests a minute per address, 1800 gateway requests, and the token exchange on the console's tighter allowance, refused with 429 and a `Retry-After` before authentication is attempted. nginx enforces a rate and a connection ceiling in front of that, and the two are deliberately redundant — any route that reaches the backend without passing the reverse proxy still meets a limit. Refusals here are counted as `janus.ratelimit.rejected`, never audited: a row per rejected call would turn a flood into a second flood against the database.
- Who a client *is*, for all of the above, is decided by Tomcat's `RemoteIpValve`: it walks `X-Forwarded-For` from the far end and keeps the first address that is not a hop listed in `JANUS_TRUSTED_PROXIES`. A caller cannot pick its own throttling identity by sending the header itself, which would otherwise buy it unlimited password and API-key guesses. Two things follow. **A reverse proxy on a public address must be named in `JANUS_TRUSTED_PROXIES`** — the default lists loopback and the private ranges, where a containerised nginx or Traefik lives. And **the backend's own port must not be reachable except through that proxy**, because a caller connecting from a trusted range is believed when it names its predecessor. Both Compose files enforce this: the backend publishes no port and sits on an `internal` network. The development file deliberately does not, alongside its exposed database and OpenBao.
- Credential values are accepted only on credential create/update and written to a server-derived OpenBao KV v2 path. API responses expose only an `openbao://` reference.
- One deployment holds one environment. Janus runs as a separate instance per environment, each with its own database and OpenBao, so no record carries an environment discriminator and no request can be pointed at the wrong side by getting a field wrong.
- Upstream URLs come only from enabled Providers. URLs with user info, queries, fragments, non-HTTPS schemes, or private/local DNS results are rejected. The address a request is actually about to connect to is checked again at connection time, which closes the DNS rebinding window that a registration-time check leaves open. Upstream redirects are never followed.
- A grant admits one application to one provider, not to a subset of its surface: once granted, every path and method under that slug is forwarded. What the API itself permits for the credential Janus presents is the limit, and an allowlist here would only be a second, staler copy of that answer. Authorization is decided before OpenBao is read, and the request is forwarded with its original encoding. Encoded path separators are rejected, and any request URI containing an empty or dot segment is refused before routing, so no two layers can disagree about which path was requested.
- Client authorization, cookies, hop-by-hop headers, inbound-hop headers, and Janus authentication headers are not forwarded. Authentication response headers and cookies are not returned. Text/JSON responses are scrubbed if they echo the credential.
- Request and response bodies are size-bounded, and outbound calls have connect and response timeouts.
- Stored responses are addressed by the credential they were fetched with, never by the calling application, and authorization is decided before the store is consulted. An application whose grant was revoked cannot be served from an entry it once caused. Nothing marked `private`, nothing carrying a cookie, and nothing with `Vary: *` is stored at all.
- Gateway decisions and administrative mutations produce audit events with correlation IDs and no credential material. Every response carries `X-Janus-Correlation-Id`, and the same identifier appears in the application logs.

## Repository layout

```text
src/main/java/io/janus/
  applications/   machine identities and one-time API keys
  providers/      fixed destinations and SSRF validation
  credentials/    OpenBao-backed credential metadata
  grants/         application/provider bindings and their per-caller quotas
  gateway/        authorization, path handling, and the outbound proxy
  audit/          immutable operational event stream
  security/       separate admin and gateway filter chains, key cache, throttling, per-client rate limit
  openbao/        minimal KV v2 integration
  shared/         correlation IDs, request URI guard, API errors
frontend/         React, TypeScript, Vite, TailwindCSS admin console
deploy/           Traefik-facing Compose file and OpenBao production configuration
```

## Development

Requirements: Docker with Compose. Copy `.env.example` to `.env` and replace every placeholder with a long random value. The `.env` file is ignored by Git.

```powershell
Copy-Item .env.example .env
docker compose -f compose.dev.yml up --build
```

Development ports are intentionally exposed for debugging:

- Admin UI: `http://localhost:5173`
- Backend: `http://localhost:8080`
- PostgreSQL: `localhost:5432`
- OpenBao: `http://localhost:8200`

The OpenBao container runs only in dev mode in `compose.dev.yml`. Its data is ephemeral. PostgreSQL uses a named development volume.

For local backend work without Docker, run PostgreSQL and OpenBao from the development Compose file, then use the Maven wrapper with a Java 21 toolchain:

```powershell
./mvnw spring-boot:run
```

## Configuration

Everything below has a working default for development; the ones without a safe default are marked.

| Variable | Default | Purpose |
|---|---|---|
| `JANUS_DATABASE_URL` / `_USERNAME` / `_PASSWORD` | local PostgreSQL | datastore connection |
| `JANUS_ADMIN_PASSWORD` | placeholder | password for the `kisuke` bootstrap super-administrator. **A production profile refuses to start on a placeholder, a value under 8 characters, one missing an upper-case letter, a lower-case letter or a digit, or a password equal to the username.** Read once, on the first start; afterwards the account is managed from the console. |
| `JANUS_ADMIN_EMAIL` | `admin@localhost` | where the bootstrap account's own expiry notices go. Applied while that account still carries the address the migration wrote; once a real one is set, here or from the console, that one stands. |
| `JANUS_CORS_ORIGINS` | `http://localhost:5173` | comma-separated console origins. `*` is refused. |
| `OPENBAO_ADDR` / `OPENBAO_TOKEN` / `OPENBAO_KV_MOUNT` | local dev server | KV v2 integration |
| `JANUS_ALLOW_PRIVATE_DESTINATIONS` | `false` | **Disables SSRF address filtering.** Only for pointing a development provider at a private host. |
| `JANUS_MAX_REQUEST_BYTES` / `JANUS_MAX_RESPONSE_BYTES` | 10 MiB | proxied body limits |
| `JANUS_CONNECT_TIMEOUT_MILLIS` / `JANUS_RESPONSE_TIMEOUT_SECONDS` | 5000 / 30 | outbound timeouts |
| `JANUS_MAX_AUTH_FAILURES` / `JANUS_AUTH_FAILURE_WINDOW_SECONDS` / `JANUS_AUTH_BLOCK_SECONDS` | 10 / 300 / 900 | authentication throttling |
| `JANUS_TRUSTED_PROXIES` | loopback and RFC 1918 | regular expression for the hops Janus sits behind. **Everything throttled or rate-limited is keyed on the address this decides.** Too narrow and the proxy becomes the throttled client, so one caller locks everybody out; too wide and a caller's own `X-Forwarded-For` is believed. |
| `JANUS_RATE_LIMIT_ADMIN_PER_MINUTE` / `_ADMIN_BURST` | 300 / 60 | per-address ceiling on `/api/**`, before authentication. Zero disables it. |
| `JANUS_RATE_LIMIT_GATEWAY_PER_MINUTE` / `_GATEWAY_BURST` | 1800 / 300 | per-address ceiling on `/gateway/**`, before authentication. Zero disables it. |
| `JANUS_CACHE_ENABLED` | `true` | master switch for response reuse; `false` overrides every provider |
| `JANUS_CACHE_MAX_ENTRIES` / `JANUS_CACHE_MAX_ENTRY_BYTES` / `JANUS_CACHE_MAX_TOTAL_BYTES` | 1000 / 1 MiB / 64 MiB | store bounds, evicted least recently used first |
| `JANUS_CACHE_STALE_IF_ERROR_SECONDS` | 300 | how long a stale response may answer while an upstream is failing |
| `JANUS_THROTTLE_MAX_WAIT_MILLIS` | 2000 | how long a request may wait for a provider allowance before 429 |
| `JANUS_THROTTLE_MAX_COOLDOWN_SECONDS` | 300 | ceiling on a pause taken from an upstream `Retry-After` |
| `JANUS_RETRY_MAX_ATTEMPTS` / `JANUS_RETRY_INITIAL_BACKOFF_MILLIS` / `JANUS_RETRY_MAX_BACKOFF_MILLIS` | 2 / 200 / 2000 | retries after the first attempt, for idempotent methods only |
| `JANUS_EXPIRY_CHECK_CRON` / `JANUS_EXPIRY_CHECK_ZONE` | `0 15 7 * * *` / `UTC` | when the daily expiry sweep runs, and the zone that schedule is read in |
| `JANUS_EXPIRY_NOTICE_DAYS` / `JANUS_EXPIRY_WARNING_DAYS` | 30 / 7 | how far ahead each of the two announcements is made |
| `JANUS_EXPIRY_EMAIL_ENABLED` / `_RECIPIENTS` / `_FROM` / `_SUBJECT_PREFIX` | `false` / empty / `janus@localhost` / `[Janus]` | outbound notice. Needs `SPRING_MAIL_HOST`; without a relay no sender is built and nothing is sent. |

## Key expiry

A key issued by someone else announces its own end nowhere. Storing a secret in Janus therefore records the date it stops working, and Janus is what remembers it.

Once a day it sweeps the recorded deadlines and says what has become true since yesterday, in three stages: a quiet **notice** thirty days ahead, an insistent **warning** seven days ahead, then **expired**. Each stage is announced once, not every morning until someone acts — the stage is claimed in the credential's own row before the announcement is written, so a restart, a second run, or a second instance on the same schedule finds nothing left to claim.

A deadline moved further out is a different promise: it rearms every stage, and withdraws the announcement it has made false. A key carries exactly one line at a time, the stage it is at now.

Announcements are read in the console at `GET /api/admin/notifications`, which carries the stage and the deadline rather than a countdown, so a page left open overnight cannot claim seven days when there are six.

**An announcement belongs to somebody.** A secret has an owner, and the owner is who has to go and rotate the key, so that is who the feed shows it to and who the mail reaches — one message per person, about their own secrets and nobody else's. Telling everybody about everybody's deadlines is how a notice becomes something people filter. `janus.notifications.email.recipients` is kept as a copy for whoever watches the deployment as a whole; they are the only ones who see more than their own. Each message is sent separately, so a relay that refuses one address does not cost the others theirs — and mail is only attempted once the announcements are durably recorded: a refused relay costs the mail, never the record.

## Minimal API flow

Create all records through the console or the administration API. The create-application response is the only response that contains its API key.

The console carries the caller's side of this at `/documentation`: what a client service sends, what it may call, what each refusal means, and the same request in cURL, PHP, JavaScript, Python, and Java. Its examples are built from the connections this deployment actually holds, so hand a developer that address rather than a copy of these snippets.

```bash
# Register an application
curl -u admin:$JANUS_ADMIN_PASSWORD -H 'Content-Type: application/json' \
  -d '{"name":"orders","description":"Orders service","enabled":true}' \
  http://localhost:8080/api/admin/applications

# Call any path on that API, once a provider, credential and grant exist.
curl -H 'X-Janus-Application-Id: <application-uuid>' \
  -H 'X-Janus-Api-Key: <one-time-key>' \
  http://localhost:8080/gateway/<provider-slug>/<any-path>
```

## Authenticating a client

There are two ways in, and they are the same credential. An application's identifier is its
`client_id` and its API key is its `client_secret`; nothing extra is created or stored.

**The static key**, above: two headers on every request. One line of `curl`, nothing to implement,
and the right choice for a cron job or a script.

**The token exchange**, for everything else — a browser page, a mobile app, anything using an SDK.
It is ordinary OAuth 2.0 `client_credentials` (RFC 6749), so a client library needs to be told the
URL and nothing more.

```bash
# Exchange the key for a short-lived bearer token
curl -X POST http://localhost:8080/oauth/token \
  -d grant_type=client_credentials \
  -d client_id=<application-uuid> \
  -d client_secret=<one-time-key>
# → {"access_token":"jnt_…","token_type":"Bearer","expires_in":900,"refresh_token":"jnr_…"}

# Call the gateway with it
curl -H 'Authorization: Bearer jnt_…' \
  http://localhost:8080/gateway/<provider-slug>/<allowed-path>

# Come back without the secret when it expires
curl -X POST http://localhost:8080/oauth/token \
  -d grant_type=refresh_token -d refresh_token=jnr_…

# Hand a token back (RFC 7009); answers 200 whether or not it existed
curl -X POST http://localhost:8080/oauth/revoke -d token=jnt_…
```

Client credentials may also be presented as HTTP Basic, per RFC 6749 §2.3.1.

Access tokens are opaque, held in memory, and honoured for `janus.oauth.access-token-ttl`. Opaque
rather than signed so that revocation is immediate: disabling an application, rotating its key or
handing it to somebody else stops its tokens on the next call rather than whenever they expire.

Refresh tokens are stored as a SHA-256, live for `janus.oauth.refresh-token-ttl`, and **rotate**:
using one retires it and issues a successor. A value presented twice is evidence that it leaked, so
the second attempt is refused and the whole chain is dropped.

**Calling from a browser.** The gateway and the token endpoint answer CORS preflights from any
origin, with credentials off — so a page on another origin cannot make a browser attach the console
session cookie to a gateway call, and the gateway chain reads no session at all. A service may
narrow this by declaring its origins, after which a token presented from anywhere else is refused.
Declaring none is the default and means any: the bearer token is what authorises the call.

Credential strategies, for what Janus presents to the upstream API:

- `NONE`: nothing is stored and nothing is presented — the open APIs. The record still exists,
  because the grant, the allowances, the cache and the journal all hang off it
- `BEARER`: stored value becomes `Authorization: Bearer …`
- `API_KEY_HEADER`: stored value is put in the configured header
- `API_KEY_QUERY`: stored value is appended as the configured query parameter
- `BASIC`: stored value must be `username:password` and is Base64 encoded at request time
- `OAUTH2_CLIENT_CREDENTIALS`: stored value must be `client_id:client_secret`, and is **exchanged**
  rather than sent

The last one is why a client service does not implement a token clock. Janus posts the client
credentials to the recorded token endpoint, holds the bearer token it gets back, and renews it a
minute before the provider says it expires. The token is held in memory, per credential, and shared
by every application that credential authorises. A refused exchange is remembered for thirty seconds,
so a wrong client secret does not call the provider once per proxied request. If no token can be
obtained the request is **not** sent: the caller gets `502` and the journal names the token endpoint,
never its response body.

```bash
# Spotify, end to end
curl -u admin:$JANUS_ADMIN_PASSWORD -H 'Content-Type: application/json' \
  -d '{"name":"Spotify","slug":"spotify","baseUrl":"https://api.spotify.com","enabled":true}' \
  http://localhost:8080/api/admin/providers

curl -u admin:$JANUS_ADMIN_PASSWORD -H 'Content-Type: application/json' -d '{
    "name":"spotify-app", "providerId":"<provider-uuid>",
    "authType":"OAUTH2_CLIENT_CREDENTIALS",
    "tokenUrl":"https://accounts.spotify.com/api/token",
    "secret":"<client-id>:<client-secret>",
    "expiresAt":null
  }' http://localhost:8080/api/admin/credentials

# then, from the client service, with a grant on this provider
curl -H 'Authorization: Bearer jnt_…' \
  'http://localhost:8080/gateway/spotify/v1/search?q=miles+davis&type=artist'
```

`expiresAt` on such a credential dates the **client secret**, not the tokens it produces. Access
tokens last minutes, are never persisted, and are renewed without anyone being told; the expiry
announcements are about the thing a human has to go and rotate.

## Traffic handling

A client application sends an ordinary HTTP request. Everything that usually surrounds calling a third-party API — caching, respecting the provider's rate limit, backing off, surviving a blip — is done by Janus and configured per provider, so no client has to implement it and no two clients implement it differently.

Every request is answered with headers stating what was done, so the behaviour is observable rather than magical.

| Header | Meaning |
|---|---|
| `X-Janus-Cache` | `HIT`, `MISS`, `REVALIDATED`, `STALE`, `COALESCED`, or `BYPASS` |
| `Age` | seconds since the served response was fetched |
| `X-Janus-RateLimit-Limit` / `-Remaining` / `-Reset` | the calling application's own allowance, when one is set |
| `X-Janus-Upstream-Attempts` | present when Janus retried |
| `Retry-After` | always present on a 429 |

**Reuse.** Enabled per provider, on by default. Janus obeys the upstream's `Cache-Control`, `Expires`, `ETag`, and `Vary`; a provider that states nothing is only cached if you give it a default freshness. `GET` and `HEAD` only. A stale entry with a validator is revalidated conditionally, so an unchanged resource costs a 304 rather than a body. A successful write invalidates that resource and everything under it. A caller can opt out per request with `Cache-Control: no-cache` or `no-store`. A served hit reads no credential: the secret never leaves OpenBao.

**Rate limiting**, in three independent layers:

- *Per client address*, protecting Janus itself. Applied before authentication, so it also covers callers with no valid key at all, and in force whether or not any policy has been configured. This is the only one an unauthenticated flood ever reaches.
- *Per provider*, all callers combined, protecting the upstream's quota. A call that would exceed it waits briefly (up to `JANUS_THROTTLE_MAX_WAIT_MILLIS`) rather than failing, and is answered 429 with `Retry-After` only if the allowance is still unavailable.
- *Per grant*, protecting one application's share. This one is never waited out: exceeding it is answered 429 immediately, because a quota that cannot be felt is not a quota.

All three are continuously refilled token buckets, so an allowance cannot be spent twice across a window boundary. A burst of 0 allows a tenth of the per-minute allowance at once. The per-address buckets are kept apart from the policy buckets, so a flood from many sources cannot evict the ceilings that protect the upstreams.

**Failure absorption.** Idempotent requests (`GET`, `HEAD`, `PUT`, `DELETE`) are retried on 429, 502, 503, 504, and transport failures, with jittered exponential backoff. `POST` and `PATCH` are never retried. If a provider answers 429 or 503 with a `Retry-After` longer than a retry could absorb, Janus stops calling it entirely for that period rather than letting every caller discover the ban in turn. While a provider is failing or paused, a stale stored response answers instead of an error, within its `stale-if-error` window. Identical concurrent reads are collapsed into a single upstream call.

Policy is set on the provider (reuse, default freshness, outbound limit, burst) and on the grant (the application's own limit and burst). `GET /api/admin/gateway/traffic` reports what is held, how often it spared a call, and which providers are paused; `DELETE /api/admin/providers/{id}/cache` and `DELETE /api/admin/gateway/cache` drop stored responses when data changed upstream without Janus having changed it.

Revoking access does not require deleting a record. Clearing **Active** on an application, credential, or grant stops gateway calls immediately while preserving the audit trail, and rotating an application key invalidates the previous one at once.

## Observability

Three surfaces, for three different questions.

- `GET /actuator/health` is open, for orchestrator probes, and says nothing beyond up or down.
- `GET /actuator/metrics` requires administrator authentication and carries `janus.gateway.requests`, a timer tagged by provider slug, outcome, cache result, and status. The request path is deliberately never a tag: one time series per URL is how a metrics backend is brought down, and per-path detail is what the audit log is for. The slug is only tagged once a provider was actually resolved, so an unknown destination cannot mint series either.
- The audit stream answers what happened to one request. Every response carries `X-Janus-Correlation-Id`, the same identifier appears in the application logs, and the console can filter the log by outcome.

Gateway audit events are written off the request path by a bounded single-threaded writer. Saturation runs the write on the calling thread rather than dropping it, and shutdown drains the queue, so a proxied call does not pay for an insert before it can answer and no event is lost to a normal stop.

## CI/CD deployment

GitLab runs the backend and frontend test suites, then builds backend and web images in its registry. A push to `develop` builds the `develop` images and exposes a manual DEV deployment; a `v*` tag builds immutable release images and automatically deploys PROD. Traefik is the only public reverse proxy and terminates HTTPS.

- DEV: `https://janus-d.kisukesaama.com`
- PROD: `https://janus.kisukesaama.com`
- Manual DEV deployment/shutdown: `deploy_dev`, `stop_dev`
- Manual PROD shutdown: `stop_prod` (available only in a release-tag pipeline)

Configure these GitLab CI/CD variables (names only; never commit their values):

- `JANUS_POSTGRES_PASSWORD`
- `JANUS_ADMIN_PASSWORD` (at least 8 characters with an upper-case letter, a lower-case letter and a digit, and not a placeholder; the deployment job rejects anything weaker before it reaches the server)
- `JANUS_OPENBAO_TOKEN`
- `JANUS_OPENBAO_SEAL_KEY` (environment-scoped; 32 random bytes in base64, from `openssl rand -base64 32`)
- `JANUS_ADMIN_EMAIL` (optional, defaults to `admin@localhost`; set it, or the first account's expiry notices go nowhere)

Use environment-scoped values for `dev` and `production`, protect the production values, and protect the `v*` tag pattern so those values are available to release pipelines. The runner must have the `devops` tag and Docker socket access; the server must already provide the external `traefik` network. Deployment files live in `/home/kisuke/deploy-janus/{dev,prod}` and persistent data in `/home/kisuke/janus/{dev,prod}`. Because the runner exposes the host Docker socket but does not mount `/home/kisuke`, the pipeline stages those files onto the host through a short-lived container. Secret files are written under a `077` umask, then land on the host as `644` inside a root-owned `700` directory. Compose uses file bind mounts for these secrets, so this lets the non-root application user read them without making them reachable through the host filesystem.

DEV and PROD both keep OpenBao file storage persistent and need no manual step: `deploy/openbao/bootstrap.sh` runs as part of every deployment and reconciles the vault with the environment-scoped variables. On a first pipeline it initializes OpenBao, enables the `secret` KV v2 mount, writes the `janus` policy, and creates the application token with the id held in `JANUS_OPENBAO_TOKEN`. On later runs it verifies and renews. The policy it writes is:

   ```hcl
   path "secret/data/janus/*" {
     capabilities = ["create", "read", "update"]
   }
   path "secret/metadata/janus/*" {
     capabilities = ["read", "delete"]
   }
   ```

File storage starts sealed on every restart, and a sealed OpenBao answers 503 to everything while its health check still passes — a deployment that looks green until the first credential write fails. Both environments therefore auto-unseal through OpenBao's `static` seal, using their own environment-scoped `JANUS_OPENBAO_SEAL_KEY`. That key decrypts the vault and lives on the same host as the data it opens, so anyone with the server has both; it is a CI variable so a stolen data directory is useless on its own and a rebuilt server can be handed it again. Treat it like the storage itself: losing it means losing every stored secret. `bao operator rekey` is not enough to recover from that — only the recovery share written to `${DATA_DIR}/{dev,prod}/openbao-bootstrap/init.json` is, alongside the root token, which is why those directories are `700` and worth backing up.

Rotating the seal key means setting `previous_key` alongside `current_key` in `deploy/openbao/openbao.hcl`, deploying once so the storage is re-encrypted, then dropping the old pair. A vault that was already initialized under the default Shamir seal before this change will refuse to start against a `static` seal; migrating it needs `bao operator unseal -migrate`, and an OpenBao that has never held a secret is faster to wipe and let the pipeline rebuild.

Static seal auto-unseal requires OpenBao 2.4.0 or newer; the images are pinned to 2.6.

Create a production release with:

```bash
git tag v1.2.3
git push origin v1.2.3
```

### Self-hosted alternative

`compose.prod.yml` is a second, independent path for deployments without Traefik: nginx terminates TLS itself from `deploy/certs/tls.crt` and `deploy/certs/tls.key`, and images are built locally rather than pulled. Secrets are read from `./.secrets/{postgres_password,openbao_token,admin_password}`. Both files must be provided before it will start; neither is in source control. The GitLab pipeline does not use this file.

## Verification

```bash
./mvnw verify
cd frontend && npm ci && npm run lint && npm run build
```

The backend schema is managed only by Flyway (`src/main/resources/db/migration`). Hibernate runs in validation mode and will not mutate production schemas.

## MVP limitations and next production steps

This milestone intentionally remains a modular monolith. Before broad production use, add organization-specific identity (OIDC/SAML) for administrators, OpenBao AppRole or workload identity with automatic token renewal, and structured log export with SIEM retention.

Six mechanisms are deliberately per-instance and must move to a shared store before running more than one replica: the verified-key cache, the administrator password cache, the authentication throttle, the response store, the policy rate-limit buckets, and the per-client rate-limit buckets. With several replicas, throttling counts only the failures that reached the same instance, and a key rotation performed on one instance is enforced on the others once their cache entry expires, within five minutes. A response store warms per replica, which costs latency and nothing else, but a rate limit is effectively multiplied by the number of replicas: divide the configured allowances accordingly, or move the buckets to Redis, before scaling out.

Also still open: outbound DNS/IP pinning appropriate to your network, and integration tests against disposable PostgreSQL/OpenBao containers.
