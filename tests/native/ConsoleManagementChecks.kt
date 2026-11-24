import expo.modules.ps5translator.ConsoleDiscovery
import expo.modules.ps5translator.matchingConsoleAddress
import expo.modules.ps5translator.normalizeConsoleIdentity
import expo.modules.ps5translator.parseConsoleSighting
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

private const val identity = "112233aabbcc"
private const val otherIdentity = "bbccddeeff11"
private const val available = "HTTP/1.1 200 OK\r\nhost-type:PS5\r\nhost-id:112233AABBCC\r\nhost-name:Salon PS5\r\n"

fun main() {
  val oldAddress = "192.168.1.13"
  val newAddress = "192.168.1.5"
  val console = checkNotNull(parseConsoleSighting(available, newAddress))
  check(console.host == newAddress && console.identity == identity && console.name == "Salon PS5" && !console.standby)
  check(normalizeConsoleIdentity("ffFFffFFffFF") == null)
  check(normalizeConsoleIdentity("000000000000") == null)
  check(normalizeConsoleIdentity("not-a-mac") == null)
  check(parseConsoleSighting(available.replace("host-type:PS5", "host-type:PS4"), oldAddress) == null)
  check(parseConsoleSighting(available, "127.0.0.1") == null)
  val standby = checkNotNull(parseConsoleSighting(available.replace("200 OK", "620 Server Standby"), oldAddress))
  check(standby.standby)
  check(matchingConsoleAddress(listOf(standby, console), identity, oldAddress) == newAddress)
  check(matchingConsoleAddress(listOf(console.copy(identity = otherIdentity)), identity, oldAddress) == null)
  check(matchingConsoleAddress(emptyList(), identity, oldAddress) == null)
  checkUdpDiscovery()
  println("PS5 announcement, identity matching and UDP request/reply fixtures passed.")
}

private fun checkUdpDiscovery() {
  val address = NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
    .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>().first()
  DatagramSocket(InetSocketAddress(address, 9302)).use { server ->
    server.soTimeout = 2000
    val responder = FutureTask {
      val packet = DatagramPacket(ByteArray(1024), 1024)
      server.receive(packet)
      val query = String(packet.data, packet.offset, packet.length, Charsets.US_ASCII)
      check(query.startsWith("SRCH * HTTP/1.1") && query.contains("00030010"))
      val reply = available.toByteArray(Charsets.US_ASCII)
      server.send(DatagramPacket(reply, reply.size, packet.address, packet.port))
    }
    Thread(responder, "synthetic-ddp-console").start()
    val results = ConsoleDiscovery.scan(600, address.hostAddress)
    responder.get(2, TimeUnit.SECONDS)
    check(results.single().identity == identity)
    check(results.single().host == address.hostAddress)
  }
}
