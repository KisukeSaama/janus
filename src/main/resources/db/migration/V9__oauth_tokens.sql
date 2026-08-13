-- Janus asked every caller to present a long-lived key on every request. That works for a script and
-- badly for anything else: a browser page, a mobile app or an SDK wants the exchange every other API
-- performs — client id and client secret once, a short-lived bearer token afterwards. This is the
-- state that exchange needs.
--
-- Only the refresh token is stored. Access tokens live for minutes, in memory, and a restart costing
-- one extra exchange is cheaper than a row written per issued token. A refresh token is the opposite:
-- it is what a client holds for weeks, so losing them all on a restart would defeat the point.
--
-- The value is never stored, only its SHA-256: a leaked database must not hand anybody a working
-- token, exactly as for the API keys it sits beside.

CREATE TABLE application_refresh_tokens (
  id UUID PRIMARY KEY,
  application_id UUID NOT NULL REFERENCES applications(id) ON DELETE CASCADE,
  -- Hex SHA-256, so always 64 characters. VARCHAR rather than CHAR: the blank-padded type would
  -- compare a stored value against a presented one only after padding rules nobody wants to think
  -- about, and the entity maps a String.
  token_hash VARCHAR(64) NOT NULL,
  -- Rotation: using a refresh token issues a new one and retires this row. A retired token that is
  -- presented again means the value leaked, and the whole family is dropped rather than honoured.
  -- The chain is what makes "the whole family" a thing that can be found.
  family_id UUID NOT NULL,
  issued_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at TIMESTAMPTZ NOT NULL,
  used_at TIMESTAMPTZ,
  CONSTRAINT uq_refresh_token_hash UNIQUE (token_hash)
);

CREATE INDEX idx_refresh_token_family ON application_refresh_tokens(family_id);
CREATE INDEX idx_refresh_token_expiry ON application_refresh_tokens(expires_at);

-- An origin is something the caller declares and Janus checks, not something it sends. There is no
-- surrogate key: the pair is the identity of the row, and nothing ever points at an origin alone.
--
-- Empty means "any origin", which is the default and the plug-and-play case: a bearer token is what
-- authorises a call, and an origin header is declared by the browser rather than proven. Declaring
-- origins narrows that, for a deployment that wants one more thing to have to be true.
CREATE TABLE application_origins (
  application_id UUID NOT NULL REFERENCES applications(id) ON DELETE CASCADE,
  origin VARCHAR(255) NOT NULL,
  PRIMARY KEY (application_id, origin)
);
