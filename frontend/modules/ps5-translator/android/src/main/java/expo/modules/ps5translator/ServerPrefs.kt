package expo.modules.ps5translator

import android.content.Context

/** Small persisted config so the service can restore itself after process death / reboot. */
class ServerPrefs(context: Context) {
  private val sp = context.applicationContext.getSharedPreferences("ps5translator_server", Context.MODE_PRIVATE)

  var port: Int
    get() = sp.getInt("port", 8787)
    set(v) = sp.edit().putInt("port", v).apply()

  var allowedIp: String?
    get() = sp.getString("allowed_ip", null)
    set(v) = sp.edit().putString("allowed_ip", v).apply()

  /** True while the user wants the server running (cleared by an explicit stop). */
  var shouldRun: Boolean
    get() = sp.getBoolean("should_run", false)
    set(v) = sp.edit().putBoolean("should_run", v).apply()

  var startOnBoot: Boolean
    get() = sp.getBoolean("start_on_boot", false)
    set(v) = sp.edit().putBoolean("start_on_boot", v).apply()
}
