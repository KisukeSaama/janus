# Janus

Janus is a self-hosted credential boundary and controlled API proxy. Client applications authenticate to Janus, but only Janus can retrieve the external credential from OpenBao and inject it into an authorized upstream request.

```text
Application ──API key──> Janus gateway ──grant + route policy──> OpenBao
                               │                                  │
                               └──── fixed registered provider <──┘
```

The project is a Java 21 / Spring Boot modular monolith with a React administration console. PostgreSQL contains identity, provider, grant, route-policy, and audit metadata. It never contains plaintext external secrets.

## Security boundaries

- `/api/admin/**` uses administrator HTTP Basic authentication and is independent from gateway authentication.
- `/gateway/{providerSlug}/**` requires `X-Janus-Application-Id` and `X-Janus-Api-Key`.
- Application keys are generated with 256 bits of randomness, displayed once, and persisted only as BCrypt hashes.
- Credential values are accepted only on credential create/update and written to a server-derived OpenBao KV v2 path. API responses expose only an `openbao://` reference.
- Applications, providers, credentials, and grants carry a mandatory `DEV` or `PROD` environment. A grant cannot cross environments.
- Upstream URLs come only from enabled Providers. URLs with user info, queries, fragments, non-HTTPS schemes, or private/local DNS results are rejected by default and revalidated before each request.
- Each grant has an explicit HTTP method and Ant-style path allowlist. Authorization happens before OpenBao is read.
- Client authorization, cookies, hop-by-hop headers, and Janus authentication headers are not forwarded. Authentication response headers and cookies are not returned. Text/JSON responses are also scrubbed if they echo the exact secret.
- Gateway decisions and administrative mutations produce audit events with correlation IDs and no credential material.

## Repository layout

```text
src/main/java/io/janus/
  applications/   machine identities and one-time API keys
  providers/      fixed destinations and SSRF validation
  credentials/    OpenBao-backed credential metadata
  grants/         application/provider bindings and route policies
  gateway/        authorization and outbound proxy
  audit/          immutable operational event stream
  security/       separate admin and gateway filter chains
  openbao/        minimal KV v2 integration
  shared/         cross-module configuration and API errors
frontend/         React, TypeScript, Vite, TailwindCSS admin console
deploy/           Nginx and OpenBao production configuration
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

For local backend work without Docker, run PostgreSQL and OpenBao from the development Compose file, then use Java 21 and Maven:

```powershell
mvn spring-boot:run
```

## Minimal API flow

Create all records through the console or the administration API. The create-application response is the only response that contains its API key.

```bash
# Create a DEV application
curl -u admin:$JANUS_ADMIN_PASSWORD -H 'Content-Type: application/json' \
  -d '{"name":"orders-dev","description":"Orders service","environment":"DEV","enabled":true}' \
  http://localhost:8080/api/admin/applications

# Invoke an authorized provider route after creating a matching provider,
# credential, grant, and route policy.
curl -H 'X-Janus-Application-Id: <application-uuid>' \
  -H 'X-Janus-Api-Key: <one-time-key>' \
  http://localhost:8080/gateway/<provider-slug>/<allowed-path>
```

Credential strategies:

- `BEARER`: stored value becomes `Authorization: Bearer …`
- `API_KEY_HEADER`: stored value is put in the configured header
- `BASIC`: stored value must be `username:password` and is Base64 encoded at request time

Route patterns use Spring's Ant syntax, such as `GET /v1/customers/*` or `POST /v1/jobs/**`. Avoid `/**` unless full provider access is intentional.

## Production deployment

Production Compose publishes only Nginx on ports 80/443. PostgreSQL and OpenBao live on an internal Docker network with persistent named volumes. The backend additionally joins an egress-only bridge so it can call public providers without publishing a port.

1. Create untracked secret files. Do not add a newline unless it is part of the value.

   ```text
   .secrets/postgres_password
   .secrets/admin_password
   .secrets/openbao_token
   ```

2. Place a certificate and private key at `deploy/certs/tls.crt` and `deploy/certs/tls.key`. This directory is excluded from both Git and Docker build contexts.
3. Set `JANUS_PUBLIC_HOST` in the deployment environment.
4. Start OpenBao and PostgreSQL first, initialize and unseal OpenBao, enable a KV v2 mount named `secret`, and create a narrowly scoped token for Janus.

   ```hcl
   path "secret/data/janus/*" {
     capabilities = ["create", "read", "update"]
   }
   path "secret/metadata/janus/*" {
     capabilities = ["read", "delete"]
   }
   ```

5. Store that token in `.secrets/openbao_token`, then start the full stack.

```bash
docker compose -f compose.prod.yml up -d --build
```

OpenBao's file storage is deliberately not auto-unsealed by this repository. For a serious deployment, integrate your platform's supported seal mechanism and token rotation process; do not place unseal keys in Compose or source control. Terminate TLS at the included Nginx proxy or replace it with your existing ingress while keeping the internal services unpublished.

## Verification

```bash
mvn test
cd frontend && npm ci && npm run build
```

The backend schema is managed only by Flyway (`src/main/resources/db/migration`). Hibernate runs in validation mode and will not mutate production schemas.

## MVP limitations and next production steps

This milestone intentionally remains a modular monolith. Before broad production use, add organization-specific identity (OIDC/SAML) for administrators, OpenBao AppRole or workload identity with automatic token renewal, structured log export/SIEM retention, outbound DNS/IP pinning appropriate to your network, rate limits per application, request-size quotas, and integration tests against disposable PostgreSQL/OpenBao containers.
