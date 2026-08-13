-- An operator cannot maintain what the console cannot show. Creation time answers "when was this
-- application registered", not "how old is the key it is presenting", and the two diverge the first
-- time a key is rotated. Recording rotation separately is what makes key age reportable.

ALTER TABLE applications
  ADD COLUMN api_key_rotated_at TIMESTAMPTZ;

UPDATE applications SET api_key_rotated_at = created_at WHERE api_key_rotated_at IS NULL;

ALTER TABLE applications
  ALTER COLUMN api_key_rotated_at SET NOT NULL,
  ALTER COLUMN api_key_rotated_at SET DEFAULT now();
