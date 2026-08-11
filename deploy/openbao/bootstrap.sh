#!/bin/sh
# Brings a file-backed OpenBao from empty to usable: initialised, KV mounted, and holding
# the token the application was deployed with. Every step checks before it acts, so
# running this against a vault that is already correct changes nothing.
set -eu

export BAO_ADDR="${BAO_ADDR:-http://openbao:8200}"

STATE=/bootstrap/init.json
janus_token=$(cat /run/secrets/openbao_token)
[ -n "$janus_token" ] || { echo "The openbao_token secret is empty." >&2; exit 1; }

# `bao status` exits 2 while sealed or uninitialised — both are states this script exists
# to leave, so only a connection failure is worth waiting on.
attempt=0
until bao status >/dev/null 2>&1 || [ $? -eq 2 ]; do
  attempt=$((attempt + 1))
  if [ "$attempt" -ge 60 ]; then
    echo "OpenBao never answered at ${BAO_ADDR}." >&2
    exit 1
  fi
  sleep 2
done

if ! bao status -format=json 2>/dev/null | grep -Eq '"initialized"[[:space:]]*:[[:space:]]*true'; then
  echo "Initialising OpenBao."
  # Static seal unlocks the vault on its own; the recovery shares in $STATE are the only
  # way back in if that key is ever lost, which is why the file is kept rather than read
  # once and dropped.
  (umask 077; bao operator init -recovery-shares=1 -recovery-threshold=1 -format=json > "${STATE}.partial")
  mv "${STATE}.partial" "$STATE"
fi

if [ ! -s "$STATE" ]; then
  cat >&2 <<'MSG'
OpenBao is initialised but its bootstrap file is missing, so the root token is unknown
and the application token cannot be reconciled. Restore /bootstrap/init.json from a
backup, or wipe the OpenBao data directory to start over.
MSG
  exit 1
fi

BAO_TOKEN=$(sed -n 's/.*"root_token"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$STATE")
export BAO_TOKEN
[ -n "$BAO_TOKEN" ] || { echo "No root token in the bootstrap file." >&2; exit 1; }

# Only -dev servers mount a KV engine for free.
if ! bao secrets list -format=json | grep -q '"secret/"'; then
  echo "Enabling the secret/ KV v2 mount."
  bao secrets enable -path=secret -version=2 kv
fi

bao policy write janus - >/dev/null <<'POLICY'
path "secret/data/janus/*" {
  capabilities = ["create", "read", "update"]
}
path "secret/metadata/janus/*" {
  capabilities = ["read", "delete"]
}
POLICY

# The token id is pinned to the deployed secret instead of generated, so the vault follows
# the CI variable rather than the other way round: rotating the variable and redeploying
# is enough. Periodic tokens never expire on their own as long as something renews them,
# and this runs on every deploy.
if BAO_TOKEN="$janus_token" bao token lookup >/dev/null 2>&1; then
  BAO_TOKEN="$janus_token" bao token renew >/dev/null
else
  echo "Creating the application token."
  bao token create -id="$janus_token" -policy=janus -orphan -period=8760h >/dev/null
fi

echo "OpenBao is ready."
