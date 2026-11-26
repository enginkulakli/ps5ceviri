package expo.modules.ps5translator

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import expo.modules.ps5translator.core.DeviceDiscoveryProof
import org.java_websocket.WebSocket
import org.java_websocket.drafts.Draft_6455
import org.java_websocket.framing.CloseFrame
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** A separate discovery socket. Legacy subtitle clients use their existing port and messages. */
internal class TvDiscovery(context: Context, private val subtitlePort: Int) :
  WebSocketServer(InetSocketAddress(8790), 1, listOf(Draft_6455(emptyList(), emptyList(), 1024))) {
  private val store = TvDiscoveryStore(context)
  private val identity = store.identity
  private val sessions = ConcurrentHashMap<WebSocket, Exchange>()
  private data class VerifiedPeer(val address: String, val confirmedAt: Long)
  private val pairedAddresses = ConcurrentHashMap<String, VerifiedPeer>()
  @Volatile private var error: String? = null
  @Volatile private var started = false
  @Volatile private var stopped = false

  private class Exchange(val opened: Long, val address: String, val target: String) {
    var id = ""
    var sequence = ""
    var key: ByteArray? = null
    var enrollment = false
    var finished = false
    val live get() = !finished && SystemClock.elapsedRealtime() - opened < 90_000
  }

  init { connectionLostTimeout = 20; isReuseAddr = true }

  override fun onStart() { started = true }

  override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
    val remote = conn.remoteSocketAddress?.address
    val local = conn.localSocketAddress?.address
    if (stopped || remote !is Inet4Address || local !is Inet4Address ||
      !(remote.isSiteLocalAddress || remote.isLinkLocalAddress) || handshake.resourceDescriptor != "/ps5-discovery") {
      conn.close(CloseFrame.POLICY_VALIDATION)
      return
    }
    sessions.entries.filter { !it.value.live }.forEach { it.key.close(CloseFrame.NORMAL) }
    if (sessions.size >= 16) {
      conn.close(CloseFrame.POLICY_VALIDATION)
      return
    }
    sessions[conn] = Exchange(SystemClock.elapsedRealtime(), remote.hostAddress.orEmpty(), local.hostAddress.orEmpty())
  }

  override fun onMessage(conn: WebSocket, message: String) {
    val exchange = sessions[conn] ?: return conn.close(CloseFrame.POLICY_VALIDATION)
    try {
      synchronized(exchange) {
        require(exchange.live && message.length <= 1024)
        val packet = JSONObject(message)
        when (packet.optString("type")) {
          "discover" -> {
            require(exchange.sequence.isEmpty() && packet.optInt("version") == 1)
            val tv = packet.optString("tvId")
            val nonce = packet.optString("nonce")
            require(DeviceDiscoveryProof.validId(tv) && DeviceDiscoveryProof.validId(nonce))
            exchange.id = tv
            exchange.sequence = listOf(identity, tv, nonce, DeviceDiscoveryProof.randomId(), exchange.target, subtitlePort).joinToString("|")
            exchange.enrollment = packet.optBoolean("pair")
            if (exchange.enrollment) {
              conn.send(JSONObject().put("type", "pending").toString())
            } else {
              val key = store.read(tv) ?: return conn.close(CloseFrame.POLICY_VALIDATION)
              challenge(conn, exchange, key)
            }
          }
          "authenticate" -> {
            val key = requireNotNull(exchange.key)
            require(DeviceDiscoveryProof.verify(key, "tv", exchange.sequence, packet.optString("proof")))
            if (exchange.enrollment) store.save(exchange.id, key)
            pairedAddresses[exchange.id] = VerifiedPeer(exchange.address, SystemClock.elapsedRealtime())
            conn.send(JSONObject().put("type", "accepted")
              .put("proof", DeviceDiscoveryProof.sign(key, "accepted", exchange.sequence)).toString())
            exchange.finished = true
            key.fill(0)
            exchange.key = null
          }
          else -> error("Unknown discovery message")
        }
      }
    } catch (_: Exception) {
      conn.close(CloseFrame.POLICY_VALIDATION)
    }
  }

  private fun challenge(conn: WebSocket, exchange: Exchange, key: ByteArray) {
    exchange.key = key
    val fields = exchange.sequence.split('|')
    conn.send(JSONObject().put("type", "challenge").put("serverId", identity).put("nonce", fields[3])
      .put("host", exchange.target).put("port", subtitlePort)
      .put("proof", DeviceDiscoveryProof.sign(key, "server", exchange.sequence)).toString())
  }

  fun approve(id: String, code: String) {
    val candidate = sessions.entries.firstOrNull { (conn, exchange) ->
      conn.isOpen && synchronized(exchange) { exchange.live && exchange.enrollment && exchange.id == id && exchange.key == null }
    } ?: throw IllegalStateException("Bu TV’nin eşleştirme isteği sona erdi. TV’de yeniden arayın.")
    val key = DeviceDiscoveryProof.keyFromCode(code) ?: throw IllegalArgumentException("TV’deki 32 karakterli eşleştirme kodunu girin.")
    synchronized(candidate.value) {
      if (!candidate.key.isOpen || !candidate.value.live || candidate.value.key != null) {
        key.fill(0)
        throw IllegalStateException("Bu TV’nin eşleştirme isteği sona erdi.")
      }
      challenge(candidate.key, candidate.value, key)
    }
  }

  fun permits(address: String) = pairedAddresses.values.any {
    it.address == address && SystemClock.elapsedRealtime() - it.confirmedAt < 20_000
  }

  fun snapshot() = Bundle().apply {
    putBoolean("available", started && !stopped)
    putString("error", error)
    val waiting = sessions.values.mapNotNull { exchange ->
      synchronized(exchange) {
        exchange.takeIf { it.live && it.enrollment && it.id.isNotEmpty() && it.key == null }?.let {
          Bundle().apply { putString("id", it.id); putString("ip", it.address) }
        }
      }
    }.distinctBy { it.getString("id") }
    putParcelableArrayList("waiting", ArrayList(waiting))
    val paired = store.pairedIds().map { id ->
      Bundle().apply { putString("id", id); putString("ip", pairedAddresses[id]?.address) }
    }
    putParcelableArrayList("paired", ArrayList(paired))
  }

  override fun onMessage(conn: WebSocket, buffer: ByteBuffer) { conn.close(CloseFrame.REFUSE) }

  override fun onError(conn: WebSocket?, ex: Exception) {
    if (conn == null) {
      started = false
      error = "Otomatik TV keşfi başlatılamadı. Manuel IP ve port ile bağlanabilirsiniz."
    }
  }

  override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
    sessions.remove(conn)?.let { exchange ->
      synchronized(exchange) { exchange.key?.fill(0); exchange.key = null; exchange.finished = true }
    }
  }

  fun shutdown() {
    stopped = true
    runCatching { stop(400) }
  }
}

private class TvDiscoveryStore(context: Context) {
  private val prefs = context.getSharedPreferences("ps5_tv_discovery", Context.MODE_PRIVATE)
  val identity = prefs.getString("identity", null) ?: DeviceDiscoveryProof.randomId().also {
    check(prefs.edit().putString("identity", it).commit())
  }

  fun pairedIds() = prefs.all.keys.filter(DeviceDiscoveryProof::validId)

  fun read(id: String): ByteArray? = runCatching {
    val encoded = prefs.getString(id, null) ?: return null
    val payload = Base64.decode(encoded, Base64.NO_WRAP)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(128, payload.copyOfRange(0, 12)))
    cipher.updateAAD(id.toByteArray(Charsets.UTF_8))
    cipher.doFinal(payload, 12, payload.size - 12)
  }.getOrNull()

  @Synchronized fun save(id: String, key: ByteArray) {
    check(id in pairedIds() || pairedIds().size < 16)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
    cipher.updateAAD(id.toByteArray(Charsets.UTF_8))
    val encrypted = Base64.encodeToString(cipher.iv + cipher.doFinal(key), Base64.NO_WRAP)
    check(prefs.edit().putString(id, encrypted).commit())
  }

  @Synchronized private fun encryptionKey(): SecretKey {
    val alias = "ps5_tv_discovery_v1"
    val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    return keys.getKey(alias, null) as? SecretKey ?: KeyGenerator.getInstance(
      KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore",
    ).apply {
      init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
    }.generateKey()
  }
}
