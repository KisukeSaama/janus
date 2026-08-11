-- Janus is deployed once per environment. A second, in-application DEV/PROD split therefore said
-- nothing the address bar did not already say, while making every record, index, and lookup carry a
-- discriminator with one value. This removes it.
--
-- The uniqueness rules narrow: what used to be unique per environment is now unique outright. That
-- can only fail on a database holding both environments at once, which is exactly the case an
-- operator has to arbitrate rather than have a migration decide silently. Each check below names
-- what collides and how to resolve it, and refuses to go further.

DO $$
DECLARE
  collisions TEXT;
BEGIN
  SELECT string_agg(DISTINCT name, ', ') INTO collisions
    FROM (SELECT name FROM applications GROUP BY name HAVING count(*) > 1) duplicates;
  IF collisions IS NOT NULL THEN
    RAISE EXCEPTION
      'Cannot drop the environment column: these application names exist in both environments (%). Rename or delete one side, then restart Janus.',
      collisions;
  END IF;

  SELECT string_agg(DISTINCT slug, ', ') INTO collisions
    FROM (SELECT slug FROM providers GROUP BY slug HAVING count(*) > 1) duplicates;
  IF collisions IS NOT NULL THEN
    RAISE EXCEPTION
      'Cannot drop the environment column: these provider slugs exist in both environments (%). Rename or delete one side, then restart Janus.',
      collisions;
  END IF;

  SELECT string_agg(DISTINCT pair, ', ') INTO collisions
    FROM (
      SELECT application_id || ' -> ' || provider_id AS pair
        FROM grants GROUP BY application_id, provider_id HAVING count(*) > 1
    ) duplicates;
  IF collisions IS NOT NULL THEN
    RAISE EXCEPTION
      'Cannot drop the environment column: these application/provider pairs are granted twice (%). Delete one grant of each pair, then restart Janus.',
      collisions;
  END IF;
END $$;

-- Dropping a column takes every constraint and index that depended on it, so the replacements are
-- declared afterwards rather than the originals being altered in place.
ALTER TABLE applications DROP COLUMN environment;
ALTER TABLE providers DROP COLUMN environment;
ALTER TABLE credentials DROP COLUMN environment;
ALTER TABLE grants DROP COLUMN environment;
ALTER TABLE notifications DROP COLUMN environment;

ALTER TABLE applications ADD CONSTRAINT uq_application_name UNIQUE (name);
ALTER TABLE providers ADD CONSTRAINT uq_provider_slug UNIQUE (slug);
ALTER TABLE grants ADD CONSTRAINT uq_grant_app_provider UNIQUE (application_id, provider_id);

-- The gateway's own lookup, minus the discriminator it no longer passes.
DROP INDEX IF EXISTS idx_grants_lookup;
CREATE INDEX idx_grants_lookup ON grants(application_id, provider_id, enabled);
