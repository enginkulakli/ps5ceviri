package expo.modules.ps5translator

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import expo.modules.kotlin.exception.CodedException
import expo.modules.kotlin.exception.Exceptions
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.ps5translator.core.SendStatus
import expo.modules.ps5translator.core.ServerSnapshot

private const val STATE_EVENT = "onServerState"

class ServerStartException(message: String) : CodedException("ERR_TV_SERVER_START", message, null)

class Ps5TranslatorModule : Module() {
  private val context: Context
    get() = appContext.reactContext ?: throw Exceptions.ReactContextLost()
  private val mainHandler = Handler(Looper.getMainLooper())

  private val listener: (ServerSnapshot) -> Unit = { snap ->
    // sendEvent must run on the main thread
    mainHandler.post { runCatching { sendEvent(STATE_EVENT, toBundle(snap)) } }
  }

  override fun definition() = ModuleDefinition {
    Name("Ps5Translator")

    Events(STATE_EVENT)

    OnCreate { ServerController.addListener(listener) }
    OnDestroy {
      ServerController.removeListener(listener)
      mainHandler.removeCallbacksAndMessages(null)
    }

    AsyncFunction("getServerState") {
      ServerController.localIp = NetworkUtils.localIpv4(context)
      ServerController.wifiConnected = NetworkUtils.isOnWifi(context)
      toBundle(ServerController.core.snapshot())
    }

    AsyncFunction("startScreenCapture") {
      val intent = Intent(context, CapturePermissionActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      context.startActivity(intent)
    }

    AsyncFunction("stopScreenCapture") {
      context.stopService(Intent(context, ScreenCaptureService::class.java))
    }

    AsyncFunction("recognizeLatestScreen") {
      val frame = ScreenCaptureFrames.latest
      if (frame.isNullOrEmpty()) ArrayList<Bundle>()
      else ScreenTextRecognizer.recognize(frame)
    }

    // OCR is local to the device; returns all detected blocks and bounding boxes.
    // Capture permission and frame acquisition are handled separately.
    AsyncFunction("recognizeScreenFrame") { base64Frame: String ->
      ScreenTextRecognizer.recognize(base64Frame)
    }

    AsyncFunction("getLocalIpAddress") {
      NetworkUtils.localIpv4(context)
    }

    // Starts the foreground service and waits (off the UI thread) until the port is open or failed.
    AsyncFunction("startTvServer") { port: Int, allowedTvIp: String? ->
      require(port in 1024..65535) { "Port 1024–65535 aralığında olmalı" }
      val prefs = ServerPrefs(context)
      prefs.port = port
      prefs.allowedIp = allowedTvIp?.trim()?.takeIf { it.isNotEmpty() }
      ServerController.core.setAllowedIp(prefs.allowedIp)
      val before = ServerController.core.snapshot().lastErrorAtMs
      SubtitleServerService.start(context)
      val deadline = System.currentTimeMillis() + 6000
      while (System.currentTimeMillis() < deadline) {
        val s = ServerController.core.snapshot()
        if (s.running && s.port == port) return@AsyncFunction toBundle(s)
        if (s.lastErrorAtMs > before && s.lastError?.startsWith("Port") == true) throw ServerStartException(s.lastError)
        Thread.sleep(100)
      }
      throw ServerStartException("Sunucu 6 saniye içinde başlamadı")
    }

    AsyncFunction("stopTvServer") {
      SubtitleServerService.stop(context)
      val deadline = System.currentTimeMillis() + 3000
      while (System.currentTimeMillis() < deadline && ServerController.core.isRunning()) Thread.sleep(50)
      toBundle(ServerController.core.snapshot())
    }

    AsyncFunction("setAllowedTvIp") { ip: String? ->
      val v = ip?.trim()?.takeIf { it.isNotEmpty() }
      ServerPrefs(context).allowedIp = v
      ServerController.core.setAllowedIp(v)
    }

    AsyncFunction("getTvClientCount") {
      ServerController.core.clientCount()
    }

    // Sends {"text":"..."} to every connected TV. Identical consecutive text is skipped unless force=true.
    AsyncFunction("sendSubtitle") { text: String, force: Boolean? ->
      val r = ServerController.core.send(text, force == true)
      Bundle().apply {
        putString(
          "status",
          when (r.status) {
            SendStatus.SENT -> "sent"
            SendStatus.DUPLICATE -> "duplicate"
            SendStatus.NO_CLIENTS -> "no_clients"
            SendStatus.NOT_RUNNING -> "not_running"
          },
        )
        putInt("clients", r.clients)
      }
    }

    AsyncFunction("setStartOnBoot") { enabled: Boolean ->
      ServerPrefs(context).startOnBoot = enabled
    }

    AsyncFunction("isIgnoringBatteryOptimizations") {
      val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
      pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    // Opens the system dialog asking to exempt the app from battery optimization.
    AsyncFunction("requestIgnoreBatteryOptimizations") {
      @SuppressLint("BatteryLife")
      val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        .setData(Uri.parse("package:${context.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      try {
        context.startActivity(intent)
      } catch (_: Throwable) {
        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      }
    }
  }

  private fun toBundle(s: ServerSnapshot): Bundle = Bundle().apply {
    putBoolean("running", s.running)
    putInt("port", s.port)
    putString("allowedIp", s.allowedIp)
    putString("localIp", ServerController.localIp)
    putBoolean("wifiConnected", ServerController.wifiConnected)
    putInt("clientCount", s.clients.size)
    putStringArrayList("clientIps", ArrayList(s.clients.map { it.address }))
    putString("lastSentText", s.lastSentText)
    putDouble("lastSentAtMs", s.lastSentAtMs.toDouble())
    putInt("lastSentClients", s.lastSentClients)
    putDouble("sentCount", s.sentCount.toDouble())
    putDouble("rejectedCount", s.rejectedCount.toDouble())
    putString("lastError", s.lastError)
    putDouble("lastErrorAtMs", s.lastErrorAtMs.toDouble())
    putDouble("startedAtMs", s.startedAtMs.toDouble())
  }
}
