ui = false
disable_mlock = true
api_addr = "http://openbao:8200"
cluster_addr = "http://openbao:8201"
storage "file" { path = "/openbao/data" }
listener "tcp" {
  address = "0.0.0.0:8200"
  cluster_address = "0.0.0.0:8201"
  tls_disable = 1
}
