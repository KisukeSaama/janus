-- A key issued by someone else announces its own end nowhere. Unless the date is written down when
-- the secret is stored, nobody learns it expired until a call fails in production. This is the
-- column that makes the deadline knowable, and the register able to remember it for the operator.

ALTER TABLE credentials
  ADD COLUMN expires_at TIMESTAMPTZ,
  -- The gravest stage already announced for the current date. Announcements are made once per
  -- stage, not every day until someone acts, and claiming the stage here is what makes a sweep
  -- idempotent when it runs twice or on two instances at the same time.
  ADD COLUMN expiry_stage_notified VARCHAR(16)
    CHECK (expiry_stage_notified IN ('NOTICE','WARNING','EXPIRED'));

-- The sweep reads by deadline and nothing else; rows without one are not its business.
CREATE INDEX idx_credentials_expiring ON credentials(expires_at) WHERE expires_at IS NOT NULL;

CREATE TABLE notifications (
  id UUID PRIMARY KEY,
  stage VARCHAR(16) NOT NULL CHECK (stage IN ('NOTICE','WARNING','EXPIRED')),
  credential_id UUID NOT NULL REFERENCES credentials(id) ON DELETE CASCADE,
  -- The names are copied rather than joined: a notification states what was true when it was
  -- raised, and stays readable while the operator renames the records it points at.
  credential_name VARCHAR(120) NOT NULL,
  provider_name VARCHAR(120) NOT NULL,
  environment VARCHAR(8) NOT NULL CHECK (environment IN ('DEV','PROD')),
  expires_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  read_at TIMESTAMPTZ,
  -- Second belt against a repeated announcement, independent of the claim above.
  CONSTRAINT uq_notification_credential_stage UNIQUE(credential_id, stage)
);

CREATE INDEX idx_notifications_recent ON notifications(created_at DESC);
