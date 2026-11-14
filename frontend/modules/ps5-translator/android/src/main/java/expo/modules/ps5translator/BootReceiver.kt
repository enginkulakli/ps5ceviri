package expo.modules.ps5translator

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts the TV server after reboot / app update, only if the user enabled it and it was running. */
class BootReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val action = intent.action ?: return
    if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
    val prefs = ServerPrefs(context)
    if (prefs.startOnBoot && prefs.shouldRun) {
      try {
        SubtitleServerService.start(context)
      } catch (_: Throwable) {
        // Background start can be refused by the OS; the user can start it from the app.
      }
    }
  }
}
