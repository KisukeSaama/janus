-- A destination somebody has connected their account to answers as two different callers: the
-- application, whose answers are the same for everybody, and the person, whose answers are theirs.
-- Which one a proxied call speaks as is decided by a request header, and until now any application
-- holding a grant could write it. Reaching somebody's own data therefore took no more authority
-- than reaching the shared catalogue, which is a consequence of a grant naming a destination rather
-- than a decision anybody made.
--
-- This column is that decision. It is not a new restriction: TRUE is what every existing grant has
-- always done, which is why the default is TRUE and why this migration changes no behaviour. What
-- it adds is the ability to say no — for the dashboard that only needs the catalogue, the service
-- that only writes, the integration nobody wants speaking for a person.
ALTER TABLE grants
  ADD COLUMN allow_account_identity BOOLEAN NOT NULL DEFAULT TRUE;
