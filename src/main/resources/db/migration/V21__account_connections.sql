-- An API is one API. Janus was making people register it twice.
--
-- Spotify publishes a single API and issues a single client id and secret. That one client obtains
-- two different tokens: one that speaks for the application, and one that speaks for a person who
-- agreed at Spotify's own site. Until now those were two values of auth_type, and auth_type belongs
-- to the provider — so the only way to have both was two providers, two gateway paths, and a
-- distinction the provider's own documentation never mentions. Twitch and Discord have the same
-- shape, and every deployment met the same surprise.
--
-- So the account identity stops being a strategy and becomes a block of settings a destination may
-- carry in addition to whatever its application identity is. The two are orthogonal now, which is
-- what lets a Discord bot token and a Discord OAuth connection live under one entry — impossible
-- while both had to be the single value of one column.
--
-- OAUTH2_AUTHORIZATION_CODE therefore stops being offered, and the rows that hold it are converted
-- below rather than left as a second way to say the same thing.

ALTER TABLE providers
  ADD COLUMN connection_authorization_url VARCHAR(500),
  ADD COLUMN connection_token_url VARCHAR(500),
  ADD COLUMN connection_scopes VARCHAR(500),
  ADD COLUMN connection_client_auth VARCHAR(16);

-- The same settings on both tables, for the reason V18 gave: the API states the contract, each
-- account's credential holds a copy, and an outbound request is built without reading two rows.
ALTER TABLE credentials
  ADD COLUMN connection_authorization_url VARCHAR(500),
  ADD COLUMN connection_token_url VARCHAR(500),
  ADD COLUMN connection_scopes VARCHAR(500),
  ADD COLUMN connection_client_auth VARCHAR(16),
  -- Whether an OAuth client of the connection's own has been supplied. Only meaningful when the
  -- connection does not share the application's stored secret; see Credential#connectionSecretPath.
  ADD COLUMN connection_provisioned BOOLEAN NOT NULL DEFAULT FALSE;

-- Converted before the constraints below are added, or the rows this migration exists for would be
-- the ones that fail it.
--
-- auth_type becomes NONE rather than being dropped: these destinations have no application identity
-- at all, which is exactly what NONE says. The stored client_id:client_secret does not move — with
-- NONE consuming no secret of its own, the connection reads the one already at secret_path, and a
-- migration that had to rewrite OpenBao could not be a migration.
UPDATE providers SET
  connection_authorization_url = authorization_url,
  connection_token_url = token_url,
  connection_scopes = token_scopes,
  connection_client_auth = COALESCE(token_client_auth, 'BASIC'),
  auth_type = 'NONE',
  authorization_url = NULL,
  token_url = NULL,
  token_scopes = NULL,
  token_client_auth = NULL
WHERE auth_type = 'OAUTH2_AUTHORIZATION_CODE';

UPDATE credentials SET
  connection_authorization_url = authorization_url,
  connection_token_url = token_url,
  connection_scopes = token_scopes,
  connection_client_auth = COALESCE(token_client_auth, 'BASIC'),
  connection_provisioned = TRUE,
  auth_type = 'NONE',
  authorization_url = NULL,
  token_url = NULL,
  token_scopes = NULL,
  token_client_auth = NULL
WHERE auth_type = 'OAUTH2_AUTHORIZATION_CODE';

ALTER TABLE providers DROP CONSTRAINT ck_provider_auth_type;
ALTER TABLE providers ADD CONSTRAINT ck_provider_auth_type CHECK (auth_type IN (
  'NONE', 'BEARER', 'API_KEY_HEADER', 'API_KEY_QUERY', 'BASIC',
  'OAUTH2_CLIENT_CREDENTIALS', 'HMAC_SIGNATURE'
));

ALTER TABLE credentials DROP CONSTRAINT ck_credential_auth_type;
ALTER TABLE credentials ADD CONSTRAINT ck_credential_auth_type CHECK (auth_type IN (
  'NONE', 'BEARER', 'API_KEY_HEADER', 'API_KEY_QUERY', 'BASIC',
  'OAUTH2_CLIENT_CREDENTIALS', 'HMAC_SIGNATURE'
));

-- Both URLs of a connection exist together or not at all: an authorisation page with nowhere to
-- exchange the code for a token describes half a flow, and half a flow fails at the moment somebody
-- has already agreed. The client authentication travels with them, defaulted rather than asked for.
ALTER TABLE providers
  ADD CONSTRAINT ck_provider_connection CHECK (
    (connection_authorization_url IS NULL) = (connection_token_url IS NULL)
    AND (connection_client_auth IS NULL) = (connection_token_url IS NULL)),
  ADD CONSTRAINT ck_provider_connection_client_auth
    CHECK (connection_client_auth IS NULL OR connection_client_auth IN ('BASIC', 'POST'));

ALTER TABLE credentials
  ADD CONSTRAINT ck_credential_connection CHECK (
    (connection_authorization_url IS NULL) = (connection_token_url IS NULL)
    AND (connection_client_auth IS NULL) = (connection_token_url IS NULL)),
  ADD CONSTRAINT ck_credential_connection_client_auth
    CHECK (connection_client_auth IS NULL OR connection_client_auth IN ('BASIC', 'POST'));

-- The token endpoint constraints named a strategy that no longer exists, and a connection's endpoint
-- lives in its own column now, so what remains is the one exchange the application itself performs.
ALTER TABLE providers DROP CONSTRAINT ck_provider_token_url;
ALTER TABLE providers ADD CONSTRAINT ck_provider_token_url CHECK (
  (auth_type = 'OAUTH2_CLIENT_CREDENTIALS') = (token_url IS NOT NULL));
ALTER TABLE credentials DROP CONSTRAINT ck_credential_token_url;
ALTER TABLE credentials ADD CONSTRAINT ck_credential_token_url CHECK (
  (auth_type = 'OAUTH2_CLIENT_CREDENTIALS') = (token_url IS NOT NULL));

-- Dropped rather than constrained to null. Its one reader was the strategy this migration removes,
-- and a column kept "in case" is a second place a connection's authorisation page could be written —
-- which is the exact ambiguity this change exists to end. Postgres removes the check constraints
-- that depended on it along with it.
ALTER TABLE providers DROP COLUMN authorization_url;
ALTER TABLE credentials DROP COLUMN authorization_url;

-- Consent belonged to a strategy; it belongs to a connection now. The rule it enforces is unchanged:
-- a recorded authorisation on a row that has no connection would be a claim nothing produced and
-- nothing would ever clear.
ALTER TABLE credentials DROP CONSTRAINT ck_credential_authorized;
ALTER TABLE credentials ADD CONSTRAINT ck_credential_authorized
  CHECK (connection_token_url IS NOT NULL OR (authorized_at IS NULL AND NOT connection_provisioned));
