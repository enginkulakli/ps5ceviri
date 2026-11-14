package expo.modules.ps5translator.core

import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

data class ClientInfo(val address: String, val connectedAtMs: Long)

data class ServerSnapshot(
  val running: Boolean,
  val port: Int,
  val allowedIp: String?,
  val clients: List<ClientInfo>,
  val lastSentText: String?,
  val lastSentAtMs: Long,
  val lastSentClients: Int,
  val sentCount: Long,
  val rejectedCount: Long,
  val lastError: String?,
  val lastErrorAtMs: Long,
  val startedAtMs: Long,
)

enum class SendStatus { SENT, DUPLICATE, NO_CLIENTS, NOT_RUNNING }

data class SendResult(val status: SendStatus, val clients: Int)

/**
 * WebSocket server for the Tizen TV (the TV connects to ws://TABLET_IP:PORT).
 * Pure JVM code (Java-WebSocket, MIT) — no Android imports, unit-tested on a desktop JVM.
 *
 * Behaviour:
 *  - binds 0.0.0.0:PORT (reachable on the tablet's Wi-Fi IP), SO_REUSEADDR for quick restarts
 *  - optional TV IP allow-list, other peers are closed with 1008
 *  - a reconnecting TV replaces its stale connection from the same IP
 *  - the current subtitle is replayed to a TV that (re)connects
 *  - identical consecutive subtitles are not re-sent (unless forced)
 *  - dead connections are dropped by ping/pong (connectionLostTimeout)
 */
class SubtitleServerCore(
  private val onChange: (ServerSnapshot) -> Unit,
  private val clock: () -> Long = { System.currentTimeMillis() },
) {
  private val lock = Any()
  private var server: Inner? = null
  private var port = 8787
  @Volatile private var allowedIp: String? = null
  private val clients = LinkedHashMap<WebSocket, ClientInfo>()
  private var lastSentText: String? = null
  private var lastSentAtMs = 0L
  private var lastSentClients = 0
  private var sentCount = 0L
  private var rejectedCount = 0L
  private var lastError: String? = null
  private var lastErrorAtMs = 0L
  private var startedAtMs = 0L
  private var running = false

  fun snapshot(): ServerSnapshot = synchronized(lock) {
    ServerSnapshot(
      running, port, allowedIp, clients.values.toList(), lastSentText, lastSentAtMs, lastSentClients,
      sentCount, rejectedCount, lastError, lastErrorAtMs, startedAtMs,
    )
  }

  private fun emit() = onChange(snapshot())

  fun recordError(message: String) {
    synchronized(lock) {
      lastError = message
      lastErrorAtMs = clock()
    }
    emit()
  }

  fun setAllowedIp(ip: String?) {
    allowedIp = ip?.trim()?.takeIf { it.isNotEmpty() }
    emit()
  }

  /** Starts (or restarts) the server. Blocks up to [timeoutMs] for bind success; returns error text or null. */
  fun start(port: Int, allowedIp: String?, timeoutMs: Long = 4000): String? {
    stop()
    setAllowedIp(allowedIp)
    val latch = CountDownLatch(1)
    var bindError: String? = null
    val s = Inner(port, latch) { bindError = it }
    s.isReuseAddr = true
    s.connectionLostTimeout = 15
    synchronized(lock) {
      this.port = port
      server = s
    }
    try {
      s.start()
    } catch (t: Throwable) {
      bindError = t.message ?: t.javaClass.simpleName
      latch.countDown()
    }
    val ok = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
    val err = when {
      bindError != null -> "Port $port açılamadı: $bindError"
      !ok -> "Sunucu $timeoutMs ms içinde başlamadı"
      else -> null
    }
    if (err != null) {
      synchronized(lock) { server = null; running = false }
      try { s.stop(200) } catch (_: Throwable) {}
      recordError(err)
    }
    return err
  }

  fun stop() {
    val s = synchronized(lock) { server.also { server = null } } ?: return
    try {
      s.stop(1000, "Sunucu durduruldu")
    } catch (_: Throwable) {}
    synchronized(lock) {
      running = false
      clients.clear()
    }
    emit()
  }

  fun isRunning() = synchronized(lock) { running }

  fun clientCount() = synchronized(lock) { clients.size }

  fun send(text: String, force: Boolean = false): SendResult {
    val s: Inner
    val targets: List<WebSocket>
    synchronized(lock) {
      val srv = server
      if (srv == null || !running) return SendResult(SendStatus.NOT_RUNNING, 0)
      if (!force && text == lastSentText) return SendResult(SendStatus.DUPLICATE, clients.size)
      s = srv
      targets = clients.keys.filter { it.isOpen }
      // Record as current even without clients: a TV connecting later gets it replayed.
      lastSentText = text
      lastSentAtMs = clock()
    }
    if (targets.isEmpty()) {
      synchronized(lock) { lastSentClients = 0 }
      emit()
      return SendResult(SendStatus.NO_CLIENTS, 0)
    }
    val payload = SubtitleMessage.encode(text)
    var delivered = 0
    for (c in targets) {
      try {
        c.send(payload); delivered++
      } catch (t: Throwable) {
        recordError("Gönderim hatası (${addr(c)}): ${t.message}")
      }
    }
    synchronized(lock) {
      sentCount++
      lastSentClients = delivered
    }
    emit()
    return SendResult(if (delivered > 0) SendStatus.SENT else SendStatus.NO_CLIENTS, delivered)
  }

  private fun addr(c: WebSocket): String =
    try { c.remoteSocketAddress?.address?.hostAddress ?: "?" } catch (_: Throwable) { "?" }

  private inner class Inner(
    port: Int,
    private val latch: CountDownLatch,
    private val onBindError: (String) -> Unit,
  ) : WebSocketServer(InetSocketAddress(port)) {

    override fun onStart() {
      synchronized(lock) {
        running = true
        startedAtMs = clock()
        lastError = null
      }
      latch.countDown()
      emit()
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
      val ip = addr(conn)
      val allow = allowedIp
      if (allow != null && ip != allow) {
        synchronized(lock) { rejectedCount++ }
        recordError("İzin verilmeyen cihaz reddedildi: $ip")
        conn.close(1008, "not allowed")
        return
      }
      val stale: List<WebSocket>
      val replay: String?
      synchronized(lock) {
        stale = clients.keys.filter { addr(it) == ip && it != conn }
        stale.forEach { clients.remove(it) }
        clients[conn] = ClientInfo(ip, clock())
        replay = lastSentText
      }
      stale.forEach { try { it.close(1000, "replaced by new connection") } catch (_: Throwable) {} }
      if (replay != null) {
        try { conn.send(SubtitleMessage.encode(replay)) } catch (_: Throwable) {}
      }
      emit()
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {
      val removed = synchronized(lock) { clients.remove(conn) != null }
      if (removed) emit()
    }

    override fun onMessage(conn: WebSocket, message: String) {
      // The TV app only receives; incoming messages are ignored on purpose.
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
      if (conn == null) {
        // server-level error (e.g. port already in use)
        onBindError(ex.message ?: ex.javaClass.simpleName)
        synchronized(lock) { running = false }
        latch.countDown()
        recordError("Sunucu hatası: ${ex.message ?: ex.javaClass.simpleName}")
      } else {
        recordError("Bağlantı hatası (${addr(conn)}): ${ex.message ?: ex.javaClass.simpleName}")
      }
    }
  }
}
