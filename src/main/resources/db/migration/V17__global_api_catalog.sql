-- APIs are deployment-wide catalogue entries. Accounts no longer duplicate the same destination;
-- they activate one catalogue entry by provisioning their own credential row for it.

-- Preserve every existing slug while making it globally addressable. In the unlikely case that
-- several owners used the same slug, the oldest keeps it and the others receive a stable suffix.
WITH ranked AS (
  SELECT id, slug,
         row_number() OVER (PARTITION BY slug ORDER BY created_at, id) AS position
    FROM providers
)
UPDATE providers p
   SET slug = left(p.slug, 70) || '-' || left(replace(p.id::text, '-', ''), 8)
  FROM ranked r
 WHERE p.id = r.id AND r.position > 1;

ALTER TABLE providers DROP CONSTRAINT uq_provider_owner_slug;
ALTER TABLE providers ADD CONSTRAINT uq_provider_slug UNIQUE (slug);

-- Authentication is part of the API contract and is therefore chosen once by an administrator.
-- Existing installations are seeded from the first credential registered for each API.
ALTER TABLE providers
  ADD COLUMN auth_type VARCHAR(32),
  ADD COLUMN header_name VARCHAR(100),
  ADD COLUMN query_parameter VARCHAR(100),
  ADD COLUMN token_url VARCHAR(500),
  ADD COLUMN token_scopes VARCHAR(500),
  ADD COLUMN token_client_auth VARCHAR(16);

WITH selected AS (
  SELECT DISTINCT ON (provider_id)
         provider_id, auth_type, header_name, query_parameter, token_url, token_scopes, token_client_auth
    FROM credentials
   ORDER BY provider_id, created_at, id
)
UPDATE providers p
   SET auth_type = c.auth_type,
       header_name = c.header_name,
       query_parameter = c.query_parameter,
       token_url = c.token_url,
       token_scopes = c.token_scopes,
       token_client_auth = c.token_client_auth
  FROM selected c
 WHERE c.provider_id = p.id;
UPDATE providers SET auth_type = 'NONE' WHERE auth_type IS NULL;

ALTER TABLE providers ALTER COLUMN auth_type SET NOT NULL;
ALTER TABLE providers ADD CONSTRAINT ck_provider_auth_type CHECK (auth_type IN (
  'NONE', 'BEARER', 'API_KEY_HEADER', 'API_KEY_QUERY', 'BASIC', 'OAUTH2_CLIENT_CREDENTIALS'
));
ALTER TABLE providers ADD CONSTRAINT ck_provider_token_client_auth
  CHECK (token_client_auth IS NULL OR token_client_auth IN ('BASIC', 'POST'));
ALTER TABLE providers ADD CONSTRAINT ck_provider_token_url
  CHECK ((auth_type = 'OAUTH2_CLIENT_CREDENTIALS') = (token_url IS NOT NULL));
ALTER TABLE providers ADD CONSTRAINT ck_provider_query_parameter
  CHECK ((auth_type = 'API_KEY_QUERY') = (query_parameter IS NOT NULL));
ALTER TABLE providers ADD CONSTRAINT ck_provider_header_name
  CHECK ((auth_type = 'API_KEY_HEADER') = (header_name IS NOT NULL));

-- The activation and its OpenBao reference belong to the signed-in account. Ownership used to be
-- inferred through the API, so copy it before removing that obsolete relationship.
ALTER TABLE credentials ADD COLUMN owner_id UUID REFERENCES accounts(id) ON DELETE RESTRICT;
ALTER TABLE credentials ADD COLUMN requires_reprovision BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE credentials c SET owner_id = p.owner_id FROM providers p WHERE p.id = c.provider_id;
ALTER TABLE credentials ALTER COLUMN owner_id SET NOT NULL;
CREATE INDEX idx_credentials_owner_name ON credentials(owner_id, name);
ALTER TABLE credentials
  ADD CONSTRAINT uq_credential_owner_provider UNIQUE (owner_id, provider_id);

ALTER TABLE providers DROP CONSTRAINT providers_owner_id_fkey;
ALTER TABLE providers DROP COLUMN owner_id;

-- A duplicate here is ambiguous (grants may intentionally point at different secret values), so
-- migration fails visibly instead of silently discarding or rewiring a secret-bearing row.
