package expo.modules.ps5translator

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

  val core = SubtitleServerCore(onChange = { snap -> listeners.forEach { l -> runCatching { l(snap) } } })

  fun addListener(l: (ServerSnapshot) -> Unit) { listeners.add(l) }
  fun removeListener(l: (ServerSnapshot) -> Unit) { listeners.remove(l) }

  fun notifyChanged() {
    val snap = core.snapshot()
    listeners.forEach { l -> runCatching { l(snap) } }
  }
}
