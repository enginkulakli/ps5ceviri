import expo.modules.ps5translator.core.DeviceDiscoveryProof

fun checksV32Discovery() {
  val key = checkNotNull(DeviceDiscoveryProof.keyFromCode("0011-2233-4455-6677-8899-AABB-CCDD-EEFF"))
  val other = checkNotNull(DeviceDiscoveryProof.keyFromCode("11112233445566778899aabbccddeeff"))
  check(DeviceDiscoveryProof.keyFromCode("0011") == null)
  val fields = listOf(
    "fedcba9876543210fedcba9876543210", "0123456789abcdef0123456789abcdef", "0".repeat(32), "f".repeat(32),
    "192.168.1.15", "8787",
  )
  val exchange = fields.joinToString("|")
  val proof = "83c0fa157bc807491f187971377e5a078080d6d04bf6380d2d2d98449ffdb41a"
  check(DeviceDiscoveryProof.sign(key, "server", exchange) == proof)
  check(DeviceDiscoveryProof.verify(key, "server", exchange, proof))
  check(!DeviceDiscoveryProof.verify(other, "server", exchange, proof))
  check(!DeviceDiscoveryProof.verify(key, "accepted", exchange, proof))
  check(!DeviceDiscoveryProof.verify(key, "server", exchange.replace("192.168.1.15", "192.168.1.19"), proof))
  check(!DeviceDiscoveryProof.verify(key, "server", exchange.replace(fields[2], "a".repeat(32)), proof))
  check(!DeviceDiscoveryProof.verify(key, "server", exchange.replace("8787", "9090"), proof))
  check(DeviceDiscoveryProof.validId(DeviceDiscoveryProof.randomId()))
}
