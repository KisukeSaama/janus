-- A login is typed, never displayed as typed. The console has always folded what an administrator
-- submits to lower case, but the lookup behind a sign-in compared exactly what was typed, so
-- "Kisuke" and "kisuke" were two different answers to the same question.
--
-- The comparison is now made on the normalised form. That turns "usernames are stored lowercased"
-- from a habit of one request record into an invariant the sign-in depends on: a row holding
-- anything else -- written by hand, restored from an older export, or inserted by a migration --
-- would be an account nobody can sign in to, and it would look like a wrong password. So the
-- existing rows are folded, and the constraint keeps them that way.

-- Two rows differing only by case cannot both keep their login, and the unique index would say so
-- as a constraint violation naming neither. Said here first, before anything is changed, in terms
-- somebody can act on.
DO $$
DECLARE clashing TEXT;
BEGIN
  SELECT string_agg(login, ', ') INTO clashing
    FROM (SELECT lower(username) AS login FROM accounts GROUP BY lower(username) HAVING count(*) > 1) AS pairs;
  IF clashing IS NOT NULL THEN
    RAISE EXCEPTION 'These logins exist more than once when case is ignored: %. Rename or remove all but one of each before starting Janus again.', clashing;
  END IF;
END $$;

UPDATE accounts SET username = lower(username) WHERE username <> lower(username);

ALTER TABLE accounts
  ADD CONSTRAINT ck_account_username_lowercase CHECK (username = lower(username));
