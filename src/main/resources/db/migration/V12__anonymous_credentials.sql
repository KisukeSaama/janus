-- Every strategy so far answered "how is the key presented". Some APIs never ask for one — PokéAPI,
-- an open data endpoint, an internal service that trusts its own network — and until now those could
-- not be registered at all, because a grant needs a credential and a credential needed a secret.
--
-- That is the wrong reason to keep an API outside the gateway. The key is only one of the things
-- Janus does for a destination; the route allowlist, the two allowances, the shared cache and the
-- journal are the others, and none of them depend on there being a secret. NONE is the strategy that
-- presents nothing, and the row exists so everything else still has something to hang off.

ALTER TABLE credentials DROP CONSTRAINT ck_credential_auth_type;
ALTER TABLE credentials ADD CONSTRAINT ck_credential_auth_type CHECK (auth_type IN (
  'NONE', 'BEARER', 'API_KEY_HEADER', 'API_KEY_QUERY', 'BASIC', 'OAUTH2_CLIENT_CREDENTIALS'
));

-- Nothing is stored for one of these, so nothing about it can stop working on a date. Stated here as
-- well as in the entity, so the expiry sweep can never find a deadline it would have to announce for
-- a secret that does not exist.
ALTER TABLE credentials ADD CONSTRAINT ck_credential_anonymous_expiry
  CHECK (auth_type <> 'NONE' OR expires_at IS NULL);
