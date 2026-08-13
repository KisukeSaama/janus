-- The console's activity view filters by outcome and reads newest first. Without a composite index
-- that query scans the whole event stream and sorts it, which is fine on an empty deployment and
-- steadily less so on the table that grows with every proxied call.
CREATE INDEX idx_audit_outcome_occurred_at ON audit_events(outcome, occurred_at DESC);
