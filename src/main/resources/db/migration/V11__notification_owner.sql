-- An announcement was addressed to a list configured in the deployment. With accounts, the person who
-- has to go and rotate the key is the one who stored it: the owner is recorded on the announcement so
-- the console can show each person their own, and the mail can reach them rather than everybody.
--
-- Copied onto the row rather than joined, for the same reason the names already are: an announcement
-- says what was true when it was written. Handing a registry to somebody else does not rewrite who
-- was told about last month's expiry.

ALTER TABLE notifications ADD COLUMN owner_id UUID REFERENCES accounts(id) ON DELETE CASCADE;

UPDATE notifications n
   SET owner_id = p.owner_id
  FROM credentials c
  JOIN providers p ON p.id = c.provider_id
 WHERE c.id = n.credential_id;

-- No row can be left behind: notifications.credential_id already cascades from credentials, so every
-- announcement has a live credential, therefore a provider, therefore an owner since V8.
ALTER TABLE notifications ALTER COLUMN owner_id SET NOT NULL;

CREATE INDEX idx_notifications_owner_recent ON notifications(owner_id, created_at DESC);
