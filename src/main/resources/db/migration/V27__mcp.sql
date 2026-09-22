-- An AI assistant configuring Janus over the Model Context Protocol. It acts as the person who let it
-- in, with that person's role, and it is let in the way the protocol prescribes: OAuth 2.1 with PKCE,
-- a client that registers itself (RFC 7591), and a consent given in the console by somebody signed in.
--
-- Three tables, for the three things that outlive a request. A client is a program that said who it
-- is; an authorisation is a consent in progress; a connection is a consent given, and the tokens that
-- carry it. As everywhere else in Janus, no token is stored: only its SHA-256.

-- Registered by the client itself, with nobody signed in, so nothing here is trusted: the name is what
-- it calls itself, and the redirect addresses are the only places a code will ever be sent.
CREATE TABLE mcp_clients (
  id UUID PRIMARY KEY,
  client_name VARCHAR(120) NOT NULL,
  -- One address per line. Never parsed as anything but a list to compare against.
  redirect_uris VARCHAR(2600) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_mcp_client_created ON mcp_clients(created_at);

-- Between the client sending a browser to Janus and the code being redeemed. The request id is what
-- the console is handed; the code, once somebody agrees, is what the client is handed. Both are read
-- once.
CREATE TABLE mcp_authorizations (
  id VARCHAR(64) PRIMARY KEY,
  client_id UUID NOT NULL REFERENCES mcp_clients(id) ON DELETE CASCADE,
  redirect_uri VARCHAR(500) NOT NULL,
  -- S256 of the client's verifier (RFC 7636); the plain method is not offered.
  code_challenge VARCHAR(128) NOT NULL,
  state VARCHAR(500),
  -- Who agreed, and the code they agreed to. Both null until somebody does.
  account_id UUID REFERENCES accounts(id) ON DELETE CASCADE,
  code_hash VARCHAR(64),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT uq_mcp_authorization_code UNIQUE (code_hash)
);

CREATE INDEX idx_mcp_authorization_expiry ON mcp_authorizations(expires_at);

-- A consent given. One row per client a person let in, holding the current access token and refresh
-- token; a refresh rotates both in place. The refresh token it replaced is kept, so that value being
-- presented again — which only happens when it leaked — ends the connection rather than being refused
-- quietly.
CREATE TABLE mcp_connections (
  id UUID PRIMARY KEY,
  client_id UUID NOT NULL REFERENCES mcp_clients(id) ON DELETE CASCADE,
  account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
  access_token_hash VARCHAR(64) NOT NULL,
  access_expires_at TIMESTAMPTZ NOT NULL,
  refresh_token_hash VARCHAR(64) NOT NULL,
  previous_refresh_hash VARCHAR(64),
  refresh_expires_at TIMESTAMPTZ NOT NULL,
  -- When the person agreed. Compared with the account's password change: a consent given before it is
  -- one given with a password that is no longer theirs to vouch for.
  authorized_at TIMESTAMPTZ NOT NULL,
  last_used_at TIMESTAMPTZ,
  CONSTRAINT uq_mcp_access_token UNIQUE (access_token_hash),
  CONSTRAINT uq_mcp_refresh_token UNIQUE (refresh_token_hash)
);

CREATE INDEX idx_mcp_connection_account ON mcp_connections(account_id);
CREATE INDEX idx_mcp_connection_previous ON mcp_connections(previous_refresh_hash);
CREATE INDEX idx_mcp_connection_expiry ON mcp_connections(refresh_expires_at);
