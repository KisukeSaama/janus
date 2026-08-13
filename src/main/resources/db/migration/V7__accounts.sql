-- Janus held every third-party credential in the deployment behind a single console account. The
-- journal could therefore only ever name "admin", whoever had actually acted, and every record
-- belonged to everybody. This table is what makes an action attributable to a person, and what the
-- next migration makes a record attributable to an owner.
--
-- The bootstrap account is inserted here rather than written at startup because V8 makes ownership
-- NOT NULL: something has to exist already for the current records to be adopted by. Its identifier
-- is fixed so a redeployment finds the same account instead of creating a second one.

CREATE TABLE accounts (
  id UUID PRIMARY KEY,
  username VARCHAR(60) NOT NULL,
  display_name VARCHAR(120) NOT NULL,
  email VARCHAR(200) NOT NULL,
  password_hash VARCHAR(100) NOT NULL,
  role VARCHAR(16) NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  -- Not a policy, a fact: what the console shows about an account it can never show the password of.
  password_changed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_signed_in_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_account_username UNIQUE (username),
  CONSTRAINT uq_account_email UNIQUE (email),
  -- The ladder governs accounts only. Owning an API is never widened by a role: an administrator
  -- manages who may sign in, not what other people registered.
  CONSTRAINT ck_account_role CHECK (role IN ('SUPER_ADMIN','ADMIN','USER'))
);

-- '!' is not a BCrypt hash, so no password can ever match it. The startup reconciler replaces it
-- with the hash of janus.admin.password. Until it has run the account exists but cannot be used,
-- which is exactly what a row posted by a migration should be.
INSERT INTO accounts (id, username, display_name, email, password_hash, role)
VALUES ('00000000-0000-0000-0000-000000000001', 'admin', 'Administrator', 'admin@localhost', '!', 'SUPER_ADMIN');

-- The journal named its actor by login, which is the one thing an administrator can change. The
-- actor becomes the account's identifier, which is stable, and the readable name is copied beside it
-- under the same rule the notifications table already follows: an entry says what was true when it
-- was written. Rows written before this migration keep a login in actor_id; the stream is
-- append-only and an old entry stays readable exactly as it was recorded.
--
-- No foreign key, deliberately: audit_events references nothing, so that deleting a record can never
-- be blocked by, nor cascade into, the account of what happened.
ALTER TABLE audit_events
  ADD COLUMN actor_label VARCHAR(120),
  ADD COLUMN owner_id UUID;

CREATE INDEX idx_audit_owner_occurred_at ON audit_events(owner_id, occurred_at DESC);
