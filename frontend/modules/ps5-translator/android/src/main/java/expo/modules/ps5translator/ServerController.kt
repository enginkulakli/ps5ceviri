package expo.modules.ps5translator

import android.content.Context
import expo.modules.ps5translator.core.ServerSnapshot
import expo.modules.ps5translator.core.SubtitleServerCore
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Process-wide owner of the WebSocket server. The foreground service controls its lifecycle;
 * the Expo module reads state and sends subtitles. Listeners are weakly coupled via add/remove.
 */
object ServerController {
  @Volatile var localIp: String? = null
  @Volatile var wifiConnected: Boolean = false

  private val listeners = CopyOnWriteArraySet<(ServerSnapshot) -> Unit>()

  @Volatile internal var discovery: TvDiscovery? = null
    private set
  @Volatile internal var discoveryError: String? = null
    private set

  val core = SubtitleServerCore(
    onChange = { snap -> listeners.forEach { l -> runCatching { l(snap) } } },
    authenticatedPeer = { ip -> discovery?.permits(ip) == true },
  )

  @Synchronized internal fun startDiscovery(context: Context, port: Int) {
    stopDiscovery()
    if (port == 8790) {
      discoveryError = "Otomatik TV keşfi için 8790 dışındaki bir altyazı portunu seçin. Manuel bağlantı kullanılabilir."
      return
    }
    runCatching { TvDiscovery(context.applicationContext, port).also { it.start() } }
      .onSuccess { discovery = it }
      .onFailure { discoveryError = "Otomatik TV keşfi açılamadı. Manuel bağlantı kullanılabilir." }
  }

  @Synchronized internal fun stopDiscovery() {
    val previous = discovery
    discovery = null
    discoveryError = null
    previous?.shutdown()
  }

  fun addListener(l: (ServerSnapshot) -> Unit) { listeners.add(l) }
  fun removeListener(l: (ServerSnapshot) -> Unit) { listeners.remove(l) }

  fun notifyChanged() {
    val snap = core.snapshot()
    listeners.forEach { l -> runCatching { l(snap) } }
  }
}
