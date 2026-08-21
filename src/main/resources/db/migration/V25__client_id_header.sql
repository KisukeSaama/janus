-- A client id is not a secret, and until now Janus treated it as if it were nobody's business.
--
-- An exchange stores client_id:client_secret, sends the pair to a token endpoint, and presents the
-- bearer token it gets back. Some APIs want the client id on the request as well: Twitch refuses
-- every Helix call that arrives without `Client-Id`, whichever token it carries. With nowhere to say
-- so, the only way through was for each calling service to send that header itself — which puts a
-- piece of the authentication contract back in the caller, the one thing this gateway exists to
-- prevent, and means an operator rotating the OAuth client has to chase every service that hard-coded
-- half of it.
--
-- So the name of that header is recorded per destination. Only the name: the value is the left half
-- of what OpenBao already holds, read at call time like everything else, so nothing about the client
-- moves into this database and a rotation is still one edit in one place.
--
-- Considered and left out: free-form constant headers. They would cover this and much else, and the
-- first thing somebody would put in one is an API key — in plaintext, in Postgres, which is the one
-- promise this schema makes.

ALTER TABLE providers ADD COLUMN client_id_header VARCHAR(100);
ALTER TABLE credentials ADD COLUMN client_id_header VARCHAR(100);

-- Only the strategy that has a client id may name a header for it. Anywhere else the column would
-- describe a value no row holds.
ALTER TABLE providers
  ADD CONSTRAINT ck_provider_client_id_header
    CHECK (client_id_header IS NULL OR auth_type = 'OAUTH2_CLIENT_CREDENTIALS');
ALTER TABLE credentials
  ADD CONSTRAINT ck_credential_client_id_header
    CHECK (client_id_header IS NULL OR auth_type = 'OAUTH2_CLIENT_CREDENTIALS');
