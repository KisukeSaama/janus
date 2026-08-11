ui = false
disable_mlock = true
api_addr = "http://openbao:8200"
cluster_addr = "http://openbao:8201"
storage "file" { path = "/openbao/file" }
listener "tcp" {
  address = "0.0.0.0:8200"
  cluster_address = "0.0.0.0:8201"
  tls_disable = 1
}

# File storage starts sealed on every restart, and a sealed OpenBao answers 503 to every
# request while still passing its own health check: the deploy looks green and the first
# credential write is what discovers otherwise. This unseals it unattended.
#
# The key sits on the same host as the ciphertext it opens, so whoever holds the host
# holds both. That is the honest cost of a deploy nobody has to finish by hand. It is
# also why the key is a CI variable rather than something generated here: a stolen data
# directory is worthless without it, and a rebuilt server can be given it again.
seal "static" {
  current_key_id = "janus-1"
  current_key    = "file:///run/secrets/openbao_seal_key"
}
