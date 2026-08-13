# Security

Janus exists to hold third-party credentials that client applications must never see. This document
states what it defends against, what it does not, and what the review of the first milestone found.

## Threat model

**Assets.** The external credentials in OpenBao, the application API keys, and the authorization
graph (which application may call which provider with which credential).

**Adversaries considered.**

1. *A client application*, holding a valid API key, trying to reach a provider it was not granted,
   another environment, or the credential itself.
2. *An unauthenticated caller* on the network, trying to guess an API key or the console password.
3. *A hostile or compromised upstream*, trying to echo the credential back to the caller, set a
   cookie in the caller's context, or redirect Janus somewhere it is not allowed to go.
4. *A caller trying to use Janus as an SSRF pivot* into the private network Janus itself sits in.

5. *A signed-in console account of any role*, trying to reach another account's registry, or to use a
   field it controls to reach a surface outside the console — a log line, a mail header, a metric.

**Explicitly out of scope for this milestone.** An adversary with shell access on the host or the
database, a super administrator acting against their own deployment, and side channels against the
JVM's memory.

## Controls

| Boundary | Control |
|---|---|
| Console authentication | Named accounts, session cookie for a browser and HTTP Basic for a script, BCrypt cost 12, throttled, audited. CSRF on, skipped only where an `Authorization` header makes forgery impossible. Production refuses to start on a weak or placeholder bootstrap password. |
| Registry separation | Every console query is scoped to the caller's own account, by a single class that fails closed. No role widens it: administration governs who may sign in, never whose records may be read. |
| Gateway authentication | 256-bit key, BCrypt hash at rest, verified once then cached for five minutes, invalidated on any change. Unknown identifiers pay the same hash comparison, so timing discloses nothing. A bearer token from `/oauth/token` is the same statement by another door — opaque, held in memory, revoked the moment what it stood for stops being true. |
| Authorization | A grant binds one application to one provider and one credential, and is decided before OpenBao is read. It admits the caller to the whole API rather than to a subset of its paths: the API's own authorization for the credential Janus presents is what limits reach, and a second copy of that answer here could only go stale. |
| Destination control | Callers name a provider slug, never a URL. HTTPS only, no user info, query, or fragment. Private, loopback, link-local, CGNAT, benchmark, multicast, reserved, and the IPv6 transition ranges are refused — at registration and again at connection time. Redirects are never followed. One exception exists for self-hosted deployments and is off unless configured: a single catalogue entry declared as living on the local network, which for that entry alone admits the site-local, CGNAT, and unique-local ranges and may be plain HTTP, since no authority issues a certificate for an address one network resolves. Loopback, link-local, and the unspecified address stay refused there too, so the exception reaches somebody's LAN and never Janus, OpenBao, or a cloud metadata service. |
| Request hygiene | Ambiguous URIs refused before routing. Encoded separators refused. Hop-by-hop and inbound-hop headers stripped outbound; authentication and session headers stripped inbound. The whole `X-Janus-` namespace is reserved in both directions, so neither side can speak in the gateway's voice. |
| Resource limits | Bounded request and response bodies, connect and response timeouts, bounded audit page size, per-client authentication throttling, and a per-address volume ceiling on every surface the backend answers on — gateway, console, and token exchange. |
| Observability | Every gateway decision and administrative mutation is audited with a correlation identifier, attributed to the actor, and never contains credential material. |

## What the review found

Every item below was fixed in this milestone. Severity is stated in terms of this threat model.

### Critical

- **The gateway could not work at all.** Entities exposed public fields while their associations were
  lazy, and the gateway read `grant.credential.enabled` directly off an uninitialised Hibernate proxy.
  That read returns `false` for every grant, so every authorized call was refused as "Credential is
  disabled". The same defect made administrative responses report null names. Entities are now
  encapsulated and the gateway's query fetches what it reads.
- **A BCrypt cost-12 verification ran on every proxied request.** Several hundred milliseconds of CPU
  per call, which is both a latency problem and a cheap denial of service. Verified keys are now
  cached, pinned to the stored hash so a rotated key can never be served from a stale entry.
- **Nothing limited credential guessing** on either the console or the gateway. Both are now
  throttled per client, and every rejection is audited.
- **A placeholder administrator password was accepted in production.** A production profile now
  refuses to start on a known placeholder, a value under 8 characters, one that does not span upper
  case, lower case and digits, or a password equal to the username.

### High

- **DNS rebinding.** The destination was validated when a provider was registered and never again, so
  a name that resolved to a public address at registration could resolve to `127.0.0.1` at call time.
  The address actually being connected to is now checked at connection time.
- **Upstream redirects were not disabled.** A 302 from an upstream would have walked the proxy
  straight out of its own allowlist. Redirects are now refused explicitly.
- **No limit on the inbound body and no outbound timeouts.** One caller could force the process to
  buffer an arbitrary payload, or hold a thread indefinitely against a slow upstream.
- **Authorization was decided on the raw path, and any `%` was rejected.** `/v1/%61dmin` would not
  match a rule written for `/v1/admin` but would still reach it upstream. Rejecting `%` outright also
  broke every legitimate encoded path. Authorization now runs on the decoded path, the request is
  forwarded with its original encoding, and encoded separators are refused so the two forms cannot
  disagree.
- **Layers disagreed about path normalisation.** `//` and `..` were read differently by Tomcat, Spring
  Security, and Spring MVC — the classic shape of a path-confusion bypass. Ambiguous URIs are now
  refused before any of them sees the request.
- **The private address list was incomplete**: carrier-grade NAT, IETF assignments, benchmarking,
  broadcast, IPv4-mapped IPv6, NAT64, and 6to4 all passed. All are now refused.
- **`Content-Length` was forwarded unchanged** after the body had been rewritten by secret redaction,
  producing a response whose framing contradicted its content.
- **The hop-by-hop and inbound-hop header lists were incomplete** (`te`, `trailer`, `expect`, `via`,
  `forwarded`, `x-forwarded-*`, `x-real-ip`), letting a caller shape what the upstream saw about the
  request's origin.
- **`OPTIONS` was answered by Spring MVC** with a route's allowed methods, without consulting the
  grant. It is now refused on the gateway, which no browser calls.
- **An unknown application identifier returned faster than a wrong key**, disclosing which identifiers
  exist. Both paths now cost the same comparison.
- **The pipeline wrote deployed secret files as `0644`** on the host. They are now created under a
  `077` umask and land as `0600`.

### Medium

- Malformed identifiers, unknown enum values, and unparsable JSON produced 500 responses; they are now
  precise 4xx problem responses, and unexpected failures are logged with a correlation identifier and
  answered generically.
- The gateway's error body was assembled by string concatenation; it is now serialised.
- An upstream exception message embeds the upstream response body — which, for a rejected credential,
  often quotes the credential. Only the status is now recorded.
- The console CSP omitted `base-uri`, `form-action`, and `object-src`, and an `add_header` inside an
  nginx `location` silently dropped every header inherited from the `server` block.
- **The pipeline ran no tests.** Images are built with `-DskipTests`, so nothing stood between a
  failing test and a deployment. A verify stage now runs both suites first.
- Deleting a credential destroyed the OpenBao secret before the metadata row was committed; a rollback
  left a live record pointing at a destroyed secret. The secret is now destroyed after commit.
- Updating a grant re-inserted its routes before the replaced ones were deleted, so re-saving an
  unchanged route hit the unique constraint.
- `frontend/` had no `.dockerignore`, so the host's `node_modules` was copied over the one `npm ci`
  had just installed — the image build failed on any host whose platform differed from the image.
- nginx resolved the backend once at startup: it refused to start before the backend existed and
  pinned the address it saw, so a recreated backend stayed unreachable.
- The console could not revoke or rotate anything. An operator facing a leaked key had no option but
  to delete the application and lose its audit trail. Editing, disabling, and key rotation are now
  in the console, and the audit log is paged and filtered server-side rather than truncated at 100
  rows.

## What the second review found

The milestone after the one above added named accounts with their own registries, the token
exchange, and the pipeline deployment behind Traefik. Every item below was found reviewing those and
is fixed.

### High

- **The token endpoint did not exist in production.** Neither reverse proxy routed `/oauth/`: it fell
  through to the SPA fallback and was served as a static file — `index.html` for a `GET`, a `405` for
  the `POST` the exchange actually is. Every deployment therefore had one documented way in that
  never reached the backend. Both configurations now proxy it explicitly, on the console's tighter
  allowance, and the development server proxies it too.
- **HSTS was absent from the deployment that actually runs.** It was declared in
  `deploy/nginx/prod.conf`, which serves the self-hosted Compose file, and nowhere in
  `frontend/nginx.conf`, which is the image the pipeline builds and Traefik fronts. The header moved
  into the shared `security-headers.conf` both include, so it cannot be declared for one path and
  forgotten for the other.
- **The token exchange was capped by nothing.** `ClientRateLimitFilter` metered `/gateway`, `/api`
  and `/actuator`; `/oauth` fell outside all three, and the failure throttle by construction never
  sees a caller whose credentials are correct. A valid key could therefore be exchanged in a loop,
  each turn costing a refresh-token row and an audit row. It is now metered on the console's
  allowance, and counted under `janus.ratelimit.rejected` with a `surface=oauth` tag.

### Medium

- **A hostile upstream could forge the headers Janus states its own decisions in.** `X-Janus-Cache`
  and `X-Janus-Correlation-Id` are always overwritten, but `X-Janus-RateLimit-Limit/-Remaining/-Reset`
  are written only when a quota exists — which is not the default — and `X-Janus-Upstream-Attempts`
  only after a retry. An upstream supplying those itself could tell a caller it had allowance it does
  not have. The whole prefix is now stripped in both directions.
- **Secret redaction covered the body and not the headers.** An API that reflects what it was sent
  into a debug header handed the caller the credential the gateway exists to withhold, and the
  response cache then kept it. Header values are now scrubbed on the same terms as the body, on the
  fresh response and on a revalidated one. Values under eight characters are left alone: a match on
  one proves nothing and rewriting it would corrupt more than it protected.
- **A record's name could inject SMTP headers.** Names are free text and travel into the subject
  line of the expiry mail, which is sent to the platform's own watcher addresses. A `\r\n` in one let
  whoever registered a secret append headers of their own to a message somebody else receives.
  Control characters are now refused on every name, and the subject is flattened to one line on the
  way out for the rows written before that rule existed.

### Low

- **The console's own CSP blocked the console's own script.** `script-src 'self'` carries no
  `unsafe-inline` and no nonce, so the inline theme-painting block in `index.html` never ran in
  production: the flash it exists to prevent happened on every load, and the violation it logged was
  one more thing to learn to ignore. It is a file now, which `'self'` covers.

## Residual risks

These are known, accepted for this milestone, and listed in the README's next steps.

- **Secrets are held in `String`** for the duration of one request and are subject to the JVM's memory
  lifetime; they cannot be zeroed.
- **Secret redaction is a safety net, not a boundary.** It catches an upstream that echoes the exact
  credential in a textual, unencoded body or response header. A transformed or compressed echo is not
  caught, and neither is a value under eight characters. The actual boundary is that the caller never
  receives the credential in the first place.
- **The administrator realm has no organization identity.** Accounts are named and attributed
  per person, and each holds its own registry, but they are local: OIDC or SAML, with the joiner and
  leaver flow that comes with it, is the next step. There is no second factor.
- **Issued access tokens, verified keys, throttle counters and rate-limit buckets are per instance.**
  With more than one replica each allowance is divided by the replica count, a failure throttle only
  counts what reached the same instance, and a revocation is enforced elsewhere within five minutes.
  Refresh tokens are in the database and do not have this property. A shared store is required before
  scaling out.
- **An administrative message can reach a 400 verbatim.** `IllegalStateException` is answered as a
  bad request with its own message, which for an infrastructure failure names the infrastructure —
  "OpenBao is unreachable". It reaches an already-authenticated console account only, and the
  alternative loses the operator the one sentence that says what to go and fix, so it stands.
- **OpenBao is reached over plain HTTP on an internal Docker network** and its token is long-lived.
  AppRole or workload identity with automatic renewal is the next step.
- **Dependency currency is not automated.** Both suites pin versions; scheduled dependency scanning
  should be added to the pipeline.

## Reporting

Report a suspected vulnerability to the platform team privately, with the correlation identifier of
any affected request. Do not open a public issue.
