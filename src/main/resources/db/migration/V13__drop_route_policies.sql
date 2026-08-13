-- A grant admits a caller to a destination; it no longer restates which of that destination's paths
-- the caller may reach. The API being called already answers that, for the credential presented, and
-- an allowlist here was a second copy of that answer that could only go stale. Registering an API is
-- now enough to call all of it.
DROP TABLE IF EXISTS route_policies;
