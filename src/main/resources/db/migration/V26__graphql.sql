-- A GraphQL API is one address and one method. Everything Janus decided from the path and the method
-- until now (whether an answer may be reused, whether a failure may be retried, what a grant admits)
-- reads the same for a query that lists a repository and a mutation that deletes it. What tells them
-- apart is inside the body, so the destination has to say where its GraphQL endpoint is before Janus
-- can read it there.
--
-- Null means the destination is not a GraphQL API, which is every existing row, so this migration
-- changes no behaviour anywhere.
ALTER TABLE providers
  ADD COLUMN graphql_path VARCHAR(200),
  ADD COLUMN graphql_max_depth INTEGER NOT NULL DEFAULT 0,
  ADD COLUMN graphql_max_aliases INTEGER NOT NULL DEFAULT 0;

-- A path, compared as one, under the same rules as a grant's prefix.
ALTER TABLE providers
  ADD CONSTRAINT ck_provider_graphql_path CHECK (
    graphql_path IS NULL
    OR (graphql_path LIKE '/%' AND graphql_path NOT LIKE '%..%' AND graphql_path NOT LIKE '%//%'
        AND graphql_path NOT LIKE '%?%' AND graphql_path NOT LIKE '%#%'));

-- Limits describe documents sent to an endpoint, so a row without one states none.
ALTER TABLE providers
  ADD CONSTRAINT ck_provider_graphql_limits CHECK (
    graphql_max_depth >= 0 AND graphql_max_aliases >= 0
    AND (graphql_path IS NOT NULL OR (graphql_max_depth = 0 AND graphql_max_aliases = 0)));

-- The grant's ceiling, restated in the terms a GraphQL API is actually used in: which kinds of
-- operation, and which root fields. Both empty by default, and empty means everything, exactly as the
-- path prefix and the methods already do.
ALTER TABLE grants
  ADD COLUMN graphql_operations VARCHAR(40),
  ADD COLUMN graphql_root_fields VARCHAR(1000);

ALTER TABLE grants
  ADD CONSTRAINT ck_grant_graphql_operations CHECK (
    graphql_operations IS NULL
    OR graphql_operations ~ '^(QUERY|MUTATION|SUBSCRIPTION)(,(QUERY|MUTATION|SUBSCRIPTION))*$');

ALTER TABLE grants
  ADD CONSTRAINT ck_grant_graphql_root_fields CHECK (
    graphql_root_fields IS NULL
    OR graphql_root_fields ~ '^[_A-Za-z][_0-9A-Za-z]*(,[_A-Za-z][_0-9A-Za-z]*)*$');
