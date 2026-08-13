-- Traffic policy: what Janus caches on behalf of a provider, and how fast anyone may reach it.
-- Zero always means "no ceiling of this kind"; caching defaults to obeying the upstream's own
-- directives, which is the only behaviour that cannot surprise a caller that was not asked.

ALTER TABLE providers
  ADD COLUMN cache_enabled BOOLEAN NOT NULL DEFAULT TRUE,
  ADD COLUMN cache_ttl_seconds INTEGER NOT NULL DEFAULT 0,
  ADD COLUMN rate_limit_per_minute INTEGER NOT NULL DEFAULT 0,
  ADD COLUMN rate_limit_burst INTEGER NOT NULL DEFAULT 0;

ALTER TABLE providers
  ADD CONSTRAINT ck_provider_cache_ttl CHECK (cache_ttl_seconds BETWEEN 0 AND 86400),
  ADD CONSTRAINT ck_provider_rate_limit CHECK (rate_limit_per_minute BETWEEN 0 AND 1000000),
  ADD CONSTRAINT ck_provider_rate_burst CHECK (rate_limit_burst BETWEEN 0 AND 100000);

ALTER TABLE grants
  ADD COLUMN rate_limit_per_minute INTEGER NOT NULL DEFAULT 0,
  ADD COLUMN rate_limit_burst INTEGER NOT NULL DEFAULT 0;

ALTER TABLE grants
  ADD CONSTRAINT ck_grant_rate_limit CHECK (rate_limit_per_minute BETWEEN 0 AND 1000000),
  ADD CONSTRAINT ck_grant_rate_burst CHECK (rate_limit_burst BETWEEN 0 AND 100000);
