package expo.modules.ps5translator

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.concurrent.TimeUnit

internal data class ConsoleSighting(val host: String, val name: String, val identity: String?, val standby: Boolean)

private val hardwareId = Regex("[0-9a-f]{12}")

internal fun normalizeConsoleIdentity(value: String?): String? = value?.trim()?.lowercase(Locale.ROOT)?.takeIf {
  hardwareId.matches(it) && it != "000000000000" && it != "ffffffffffff"
}

internal fun parseConsoleSighting(message: String, address: String): ConsoleSighting? {
  if (!isConsoleIpv4(address)) return null
  val lines = message.lineSequence().toList()
  val status = lines.firstOrNull()?.split(' ', limit = 3)?.getOrNull(1)
  if (status != "200" && status != "620") return null
  fun header(name: String) = lines.firstOrNull { it.startsWith("$name:", ignoreCase = true) }
    ?.substringAfter(':')?.trim()
  if (!header("host-type").equals("PS5", ignoreCase = true)) return null
  return ConsoleSighting(address, header("host-name")?.take(100)?.ifBlank { "PS5" } ?: "PS5",
    normalizeConsoleIdentity(header("host-id")), status == "620")
}

internal fun matchingConsoleAddress(sightings: List<ConsoleSighting>, identity: String, previous: String): String? {
  val matching = sightings.filter { it.identity == identity }
  return (matching.firstOrNull { it.host == previous && !it.standby }
    ?: matching.firstOrNull { !it.standby }
    ?: matching.firstOrNull { it.host == previous }
    ?: matching.firstOrNull())?.host
}

internal object ConsoleDiscovery {
  private val request = "SRCH * HTTP/1.1\r\ndevice-discovery-protocol-version:00030010\r\n\r\n"
    .toByteArray(Charsets.US_ASCII)

  fun scan(timeoutMs: Int, target: String? = null): List<ConsoleSighting> {
    val found = linkedMapOf<Pair<String, String?>, ConsoleSighting>()
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceIn(500, 8000).toLong())
    val destinations = if (target == null) broadcastAddresses() else setOf(InetAddress.getByName(target))
    DatagramSocket().use { socket ->
      socket.broadcast = target == null
      for (address in destinations) {
        runCatching { socket.send(DatagramPacket(request, request.size, address, 9302)) }
      }
      val bytes = ByteArray(4096)
      while (System.nanoTime() < deadline) {
        val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
        socket.soTimeout = remaining.coerceIn(1, 300).toInt()
        val packet = DatagramPacket(bytes, bytes.size)
        try {
          socket.receive(packet)
        } catch (_: SocketTimeoutException) {
          continue
        }
        val address = packet.address.hostAddress ?: continue
        if (target != null && target != address) continue
        val message = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
        val console = parseConsoleSighting(message, address) ?: continue
        found[address to console.identity] = console
      }
    }
    return found.values.toList()
  }

  private fun broadcastAddresses(): Set<InetAddress> = buildSet {
    add(InetAddress.getByName("255.255.255.255"))
    NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { network ->
      if (network.isUp && !network.isLoopback) {
        network.interfaceAddresses.mapNotNullTo(this) { it.broadcast as? Inet4Address }
      }
    }
  }
}
