-- Deleting registry metadata and destroying its OpenBao value cannot be one distributed
-- transaction. Remember the destruction durably beside the metadata deletion, then retry it until
-- OpenBao accepts it. A failed vault call must never turn an already-committed DELETE into HTTP 500.

CREATE TABLE pending_secret_deletions (
  id UUID PRIMARY KEY,
  secret_path VARCHAR(500) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_pending_secret_deletion_path UNIQUE (secret_path)
);

CREATE INDEX idx_pending_secret_deletions_created_at
  ON pending_secret_deletions(created_at);
