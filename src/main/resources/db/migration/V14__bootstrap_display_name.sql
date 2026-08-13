-- The bootstrap row was inserted under the display name 'Administrator', which is not a name: it is
-- the English label of a role, and of the wrong one, since the account is a SUPER_ADMIN. A French
-- console therefore printed "Administrator" over "Super administrateur" and left the reader to work
-- out which of the two was true. It could not be translated either, because a display name is data
-- an administrator types, not a string the console ships.
--
-- The display name is the one field here that holds a person, and a shared bootstrap account holds
-- nobody. So it carries its login until somebody signs their own name to it, and the console has one
-- token to print instead of three that all say "admin".
--
-- Guarded on the placeholder: a deployment where anyone has already set a real name keeps it.
UPDATE accounts
SET display_name = username,
    updated_at = now()
WHERE id = '00000000-0000-0000-0000-000000000001'
  AND display_name = 'Administrator';
