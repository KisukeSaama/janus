CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE applications (
  id UUID PRIMARY KEY,
  name VARCHAR(120) NOT NULL,
  description VARCHAR(500),
  environment VARCHAR(8) NOT NULL CHECK (environment IN ('DEV','PROD')),
  api_key_hash VARCHAR(100) NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_application_name_env UNIQUE(name, environment)
);

CREATE TABLE providers (
  id UUID PRIMARY KEY,
  name VARCHAR(120) NOT NULL,
  slug VARCHAR(80) NOT NULL,
  base_url VARCHAR(500) NOT NULL,
  environment VARCHAR(8) NOT NULL CHECK (environment IN ('DEV','PROD')),
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_provider_slug_env UNIQUE(slug, environment)
);

CREATE TABLE credentials (
  id UUID PRIMARY KEY,
  name VARCHAR(120) NOT NULL,
  provider_id UUID NOT NULL REFERENCES providers(id) ON DELETE RESTRICT,
  environment VARCHAR(8) NOT NULL CHECK (environment IN ('DEV','PROD')),
  auth_type VARCHAR(24) NOT NULL CHECK (auth_type IN ('BEARER','API_KEY_HEADER','BASIC')),
  header_name VARCHAR(100),
  secret_path VARCHAR(500) NOT NULL UNIQUE,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE grants (
  id UUID PRIMARY KEY,
  application_id UUID NOT NULL REFERENCES applications(id) ON DELETE CASCADE,
  provider_id UUID NOT NULL REFERENCES providers(id) ON DELETE CASCADE,
  credential_id UUID NOT NULL REFERENCES credentials(id) ON DELETE RESTRICT,
  environment VARCHAR(8) NOT NULL CHECK (environment IN ('DEV','PROD')),
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_grant_app_provider_env UNIQUE(application_id, provider_id, environment)
);

CREATE TABLE route_policies (
  id UUID PRIMARY KEY,
  grant_id UUID NOT NULL REFERENCES grants(id) ON DELETE CASCADE,
  http_method VARCHAR(12) NOT NULL,
  path_pattern VARCHAR(300) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_policy_grant_method_path UNIQUE(grant_id, http_method, path_pattern)
);

CREATE TABLE audit_events (
  id UUID PRIMARY KEY,
  occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  actor_type VARCHAR(24) NOT NULL,
  actor_id VARCHAR(120),
  action VARCHAR(80) NOT NULL,
  outcome VARCHAR(24) NOT NULL,
  provider_id UUID,
  request_method VARCHAR(12),
  request_path VARCHAR(500),
  status_code INTEGER,
  detail VARCHAR(500),
  correlation_id VARCHAR(80) NOT NULL
);
CREATE INDEX idx_audit_occurred_at ON audit_events(occurred_at DESC);
CREATE INDEX idx_grants_lookup ON grants(application_id, provider_id, environment, enabled);
