-- Janus could present a stored secret three ways: as a bearer token, in a named header, or as Basic.
-- That covers a good share of APIs and none of the two that a developer actually meets first — a key
-- in the query string, and an exchange of client credentials for a short-lived token. Spotify is the
-- second kind: client id and secret go to accounts.spotify.com, and api.spotify.com wants the bearer
-- token that comes back.
--
-- Doing that exchange in Janus is the whole point of Janus. Otherwise every client service
-- reimplements a clock and a token cache, which is what a credential boundary exists to prevent.
--
-- The existing type constraint was written inline and is therefore named by PostgreSQL rather than by
-- us, and the column is one character too short for the new value. Both are rebuilt.

ALTER TABLE credentials DROP CONSTRAINT credentials_auth_type_check;
ALTER TABLE credentials ALTER COLUMN auth_type TYPE VARCHAR(32);
ALTER TABLE credentials ADD CONSTRAINT ck_credential_auth_type CHECK (auth_type IN (
  'BEARER', 'API_KEY_HEADER', 'API_KEY_QUERY', 'BASIC', 'OAUTH2_CLIENT_CREDENTIALS'
));

ALTER TABLE credentials
  -- The query parameter a key is presented as, for the APIs that read one: ?apikey=…, ?key=…, ?token=…
  ADD COLUMN query_parameter VARCHAR(100),
  -- The token endpoint, which is almost never the API's own host: accounts.spotify.com issues what
  -- api.spotify.com expects. Validated as a destination like any other URL Janus will call.
  ADD COLUMN token_url VARCHAR(500),
  -- Space separated, as RFC 6749 says. Empty means the client's default scopes.
  ADD COLUMN token_scopes VARCHAR(500),
  -- How the client presents itself at the token endpoint. RFC 6749 §2.3.1 requires Basic and permits
  -- the form body; providers disagree about which they accept, so this is data rather than a guess.
  ADD COLUMN token_client_auth VARCHAR(16);

ALTER TABLE credentials
  ADD CONSTRAINT ck_credential_token_client_auth
    CHECK (token_client_auth IS NULL OR token_client_auth IN ('BASIC', 'POST')),
  -- Each of these belongs to exactly one strategy and means nothing for the others. Stated in the
  -- database as well as in the entity, so it holds for rows written by anything else.
  ADD CONSTRAINT ck_credential_token_url
    CHECK ((auth_type = 'OAUTH2_CLIENT_CREDENTIALS') = (token_url IS NOT NULL)),
  ADD CONSTRAINT ck_credential_query_parameter
    CHECK ((auth_type = 'API_KEY_QUERY') = (query_parameter IS NOT NULL)),
  ADD CONSTRAINT ck_credential_header_name
    CHECK ((auth_type = 'API_KEY_HEADER') = (header_name IS NOT NULL));
