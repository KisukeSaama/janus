-- A grant admits one service to one destination, and until now to the whole of it. That was the
-- right default and it stays the default: the API being called already decides what the credential
-- Janus presents may do, and restating a copy of that answer here is what V13 removed.
--
-- What it does not cover is the credential that cannot be narrowed at the source. A self-hosted
-- deployment is full of them: a media server's token is its administrator, a home automation hub
-- issues one key for the whole house, a NAS has no notion of scopes at all. The API cannot say "this
-- service may only read", so nobody can, and a dashboard that got compromised holds everything the
-- key does.
--
-- These two columns are that ceiling and nothing more. They are not an access model: one prefix, an
-- optional set of methods, both empty by default, and no wildcard, no ordering and no precedence to
-- reason about. Empty means exactly what every existing row already does, which is why this migration
-- changes no behaviour anywhere.
ALTER TABLE grants
  ADD COLUMN path_prefix VARCHAR(512),
  ADD COLUMN allowed_methods VARCHAR(128);

-- A prefix is a path and is compared as one: absolute, and refused rather than guessed at if it
-- carries a query, a fragment, or a traversal segment. The application-side check is the one that
-- normalises; this is the floor under a row written by anything else.
ALTER TABLE grants
  ADD CONSTRAINT ck_grant_path_prefix CHECK (
    path_prefix IS NULL
    OR (path_prefix LIKE '/%' AND path_prefix NOT LIKE '%..%' AND path_prefix NOT LIKE '%//%'
        AND path_prefix NOT LIKE '%?%' AND path_prefix NOT LIKE '%#%'));
