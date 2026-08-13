-- A record belonged to the deployment. It now belongs to somebody: this is what lets a person see
-- their own services, APIs, secrets and access rules and nobody else's, decided in the query rather
-- than in the page that renders it.
--
-- Only the two roots carry an owner. A credential is reached through its provider, which it can
-- never change (CredentialService refuses it, and secret_path is derived from it once), and a grant
-- is reached through its application. Repeating the owner on all four would be a denormalisation
-- that can drift, on exactly the rows where drifting means "the wrong person can read this secret".
--
-- The current records are adopted by the bootstrap account created in V7 — the only account that
-- exists at this point, and the one whose password the operator already holds. They can be handed
-- over from the console once the other accounts exist.

ALTER TABLE applications ADD COLUMN owner_id UUID REFERENCES accounts(id) ON DELETE RESTRICT;
ALTER TABLE providers    ADD COLUMN owner_id UUID REFERENCES accounts(id) ON DELETE RESTRICT;

UPDATE applications SET owner_id = '00000000-0000-0000-0000-000000000001' WHERE owner_id IS NULL;
UPDATE providers    SET owner_id = '00000000-0000-0000-0000-000000000001' WHERE owner_id IS NULL;

ALTER TABLE applications ALTER COLUMN owner_id SET NOT NULL;
ALTER TABLE providers    ALTER COLUMN owner_id SET NOT NULL;

-- RESTRICT, never CASCADE. Deleting a provider row from under Janus would leave its secret behind in
-- OpenBao: CredentialService destroys the secret after the transaction commits, and a database
-- cascade goes around that path entirely. An orphaned secret is enumerable by nothing. Removing a
-- person therefore means handing their records over first, which is a decision, not a side effect.

-- Uniqueness widens rather than narrows: two people each registering the Spotify API is the ordinary
-- case, not a conflict. Each holds their own client id, and the gateway resolves a slug within the
-- namespace of whoever's application is calling, so the public address keeps its shape.
ALTER TABLE applications DROP CONSTRAINT uq_application_name;
ALTER TABLE applications ADD CONSTRAINT uq_application_owner_name UNIQUE (owner_id, name);
ALTER TABLE providers    DROP CONSTRAINT uq_provider_slug;
ALTER TABLE providers    ADD CONSTRAINT uq_provider_owner_slug UNIQUE (owner_id, slug);

-- No extra index: both constraints create one with owner_id leading, which serves the console's
-- "my records" listing and the gateway's (owner, slug) lookup alike.
