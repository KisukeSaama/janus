-- Janus refuses a destination that resolves to a private address, because a proxy able to reach one
-- can be walked into the infrastructure standing behind it. That rule was deployment-wide, and the
-- only way past it was JANUS_ALLOW_PRIVATE_DESTINATIONS, which lifts it for every destination at
-- once — the SSRF guard, the OpenBao network, and the plain-HTTP refusal, all traded away to
-- register one service.
--
-- A self-hosted deployment has the opposite need: the API worth proxying is often on the local
-- network and nowhere else — a media server, a home automation hub, a NAS. Those deserve the same
-- treatment as any other destination, which is what this column gives them. It is stated per
-- destination, so admitting one LAN service leaves every other provider refusing exactly what it
-- refused before.
--
-- What it does not do is lift the whole check. Loopback, link-local, and the unspecified address
-- stay refused whatever a row says: from inside a container the first is Janus itself and the
-- OpenBao it reads credentials from, and the second is where a cloud host answers with instance
-- credentials to anyone who asks. Neither is ever the service this column was added for.
ALTER TABLE providers
  ADD COLUMN allow_private_destination BOOLEAN NOT NULL DEFAULT FALSE;
