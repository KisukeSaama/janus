-- An API is one aggregate in the console. Its grants and credential metadata have no independent
-- lifetime, and V15 now records every stored OpenBao value durably before this root is removed.
-- Let PostgreSQL delete the aggregate in one statement instead of mixing bulk JPQL deletes with
-- managed Hibernate entities, which could leave the provider delete failing at transaction commit.

ALTER TABLE credentials DROP CONSTRAINT credentials_provider_id_fkey;
ALTER TABLE credentials
  ADD CONSTRAINT credentials_provider_id_fkey
  FOREIGN KEY (provider_id) REFERENCES providers(id) ON DELETE CASCADE;
