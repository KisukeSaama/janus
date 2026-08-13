-- Janus could present a stored secret six ways, and every one of them assumed the same thing: that a
-- secret is a value that travels, and that an administrator typing it into a form is enough to make a
-- destination work. Two common shapes do not fit that assumption.
--
--   * An authorisation a person gives at the provider's own site, which yields a refresh token
--     belonging to them rather than to the application. This is what separates somebody's Spotify
--     playlists from the Spotify catalogue, and no amount of configuration substitutes for it.
--   * A secret that signs the request instead of accompanying it. Exchanges work this way — Binance,
--     Coinbase, Kraken — and a developer who meets one meets nothing else that resembles it.
--
-- Both are settings rather than exceptions: recorded per API, in columns, beside the ones already
-- here, with the constraints below stating for each which strategy it belongs to.
--
-- Considered and deliberately left out: an assertion signed with a private key (RFC 7523), and a key
-- split across two headers. Both belong to enterprise service accounts rather than to public APIs,
-- and each one added to this list is one more thing between a reader and the strategy they need.

ALTER TABLE providers DROP CONSTRAINT ck_provider_auth_type;
ALTER TABLE providers ADD CONSTRAINT ck_provider_auth_type CHECK (auth_type IN (
  'NONE', 'BEARER', 'API_KEY_HEADER', 'API_KEY_QUERY', 'BASIC',
  'OAUTH2_CLIENT_CREDENTIALS', 'OAUTH2_AUTHORIZATION_CODE', 'HMAC_SIGNATURE'
));

ALTER TABLE credentials DROP CONSTRAINT ck_credential_auth_type;
ALTER TABLE credentials ADD CONSTRAINT ck_credential_auth_type CHECK (auth_type IN (
  'NONE', 'BEARER', 'API_KEY_HEADER', 'API_KEY_QUERY', 'BASIC',
  'OAUTH2_CLIENT_CREDENTIALS', 'OAUTH2_AUTHORIZATION_CODE', 'HMAC_SIGNATURE'
));

-- The same settings on both tables: the API states the contract, and each account's credential holds
-- a copy of it, so an outbound request can be built without reading two rows.
ALTER TABLE providers
  ADD COLUMN authorization_url VARCHAR(500),
  ADD COLUMN signature_algorithm VARCHAR(16),
  ADD COLUMN signature_template VARCHAR(500),
  ADD COLUMN signature_encoding VARCHAR(16),
  ADD COLUMN signature_header VARCHAR(100),
  ADD COLUMN signature_parameter VARCHAR(100),
  ADD COLUMN timestamp_header VARCHAR(100),
  ADD COLUMN timestamp_parameter VARCHAR(100);

ALTER TABLE credentials
  ADD COLUMN authorization_url VARCHAR(500),
  ADD COLUMN signature_algorithm VARCHAR(16),
  ADD COLUMN signature_template VARCHAR(500),
  ADD COLUMN signature_encoding VARCHAR(16),
  ADD COLUMN signature_header VARCHAR(100),
  ADD COLUMN signature_parameter VARCHAR(100),
  ADD COLUMN timestamp_header VARCHAR(100),
  ADD COLUMN timestamp_parameter VARCHAR(100),
  -- Consent, and whom the provider says gave it. Null is not "unknown": it is "nobody has agreed
  -- yet", which is a state the console acts on rather than reports as a failure.
  ADD COLUMN authorized_at TIMESTAMPTZ,
  ADD COLUMN authorized_subject VARCHAR(255);

-- A header name used to belong to one strategy. Two want one now, for different reasons: a key
-- travels in it, or it identifies who signed a request that the secret itself never travelled with.
ALTER TABLE providers DROP CONSTRAINT ck_provider_header_name;
ALTER TABLE providers ADD CONSTRAINT ck_provider_header_name CHECK (
  CASE auth_type
    WHEN 'API_KEY_HEADER' THEN header_name IS NOT NULL
    WHEN 'HMAC_SIGNATURE' THEN TRUE
    ELSE header_name IS NULL
  END);
ALTER TABLE credentials DROP CONSTRAINT ck_credential_header_name;
ALTER TABLE credentials ADD CONSTRAINT ck_credential_header_name CHECK (
  CASE auth_type
    WHEN 'API_KEY_HEADER' THEN header_name IS NOT NULL
    WHEN 'HMAC_SIGNATURE' THEN TRUE
    ELSE header_name IS NULL
  END);

-- Two strategies exchange something at a token endpoint now, not one.
ALTER TABLE providers DROP CONSTRAINT ck_provider_token_url;
ALTER TABLE providers ADD CONSTRAINT ck_provider_token_url CHECK (
  (auth_type IN ('OAUTH2_CLIENT_CREDENTIALS', 'OAUTH2_AUTHORIZATION_CODE')) = (token_url IS NOT NULL));
ALTER TABLE credentials DROP CONSTRAINT ck_credential_token_url;
ALTER TABLE credentials ADD CONSTRAINT ck_credential_token_url CHECK (
  (auth_type IN ('OAUTH2_CLIENT_CREDENTIALS', 'OAUTH2_AUTHORIZATION_CODE')) = (token_url IS NOT NULL));

ALTER TABLE providers
  ADD CONSTRAINT ck_provider_authorization_url
    CHECK ((auth_type = 'OAUTH2_AUTHORIZATION_CODE') = (authorization_url IS NOT NULL)),
  ADD CONSTRAINT ck_provider_signature
    CHECK ((auth_type = 'HMAC_SIGNATURE')
           = (signature_algorithm IS NOT NULL AND signature_template IS NOT NULL
              AND signature_encoding IS NOT NULL)),
  -- Exactly one destination for the signature: both would send it twice, neither would send it at all.
  ADD CONSTRAINT ck_provider_signature_destination
    CHECK (auth_type <> 'HMAC_SIGNATURE'
           OR ((signature_header IS NOT NULL) <> (signature_parameter IS NOT NULL))),
  ADD CONSTRAINT ck_provider_timestamp_destination
    CHECK (timestamp_header IS NULL OR timestamp_parameter IS NULL);

ALTER TABLE credentials
  ADD CONSTRAINT ck_credential_authorization_url
    CHECK ((auth_type = 'OAUTH2_AUTHORIZATION_CODE') = (authorization_url IS NOT NULL)),
  ADD CONSTRAINT ck_credential_signature
    CHECK ((auth_type = 'HMAC_SIGNATURE')
           = (signature_algorithm IS NOT NULL AND signature_template IS NOT NULL
              AND signature_encoding IS NOT NULL)),
  ADD CONSTRAINT ck_credential_signature_destination
    CHECK (auth_type <> 'HMAC_SIGNATURE'
           OR ((signature_header IS NOT NULL) <> (signature_parameter IS NOT NULL))),
  ADD CONSTRAINT ck_credential_timestamp_destination
    CHECK (timestamp_header IS NULL OR timestamp_parameter IS NULL),
  -- Consent only exists for the strategy that asks for it. A recorded authorisation on any other row
  -- would be a claim nothing produced and nothing would ever clear.
  ADD CONSTRAINT ck_credential_authorized
    CHECK (auth_type = 'OAUTH2_AUTHORIZATION_CODE' OR authorized_at IS NULL);

-- Where an authorisation in progress is remembered, between sending somebody to the provider and
-- their coming back. Short-lived by construction: a row is claimed once, by the callback quoting its
-- state, and swept afterwards whether or not anyone returned.
--
-- The verifier is the PKCE secret (RFC 7636). It is held here rather than in OpenBao because it is
-- worthless a few minutes after it is written, and worthless to anyone who did not also intercept the
-- authorisation code — and because a row that must be deleted on use belongs where deletion is
-- transactional.
CREATE TABLE oauth_authorization_states (
  state VARCHAR(64) PRIMARY KEY,
  credential_id UUID NOT NULL REFERENCES credentials(id) ON DELETE CASCADE,
  -- Who started it. The callback must be answered by the same person, or one account's consent could
  -- be attached to another account's credential.
  account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
  code_verifier VARCHAR(128) NOT NULL,
  -- Recorded rather than rebuilt: RFC 6749 §4.1.3 requires the redirect sent to the token endpoint to
  -- be identical to the one that started the flow, and a deployment's public URL can change between
  -- the two requests.
  redirect_uri VARCHAR(500) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_oauth_states_expiry ON oauth_authorization_states(expires_at);
