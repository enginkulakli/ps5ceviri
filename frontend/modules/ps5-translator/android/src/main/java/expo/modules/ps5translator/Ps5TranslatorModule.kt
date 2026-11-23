package expo.modules.ps5translator

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import android.os.SystemClock
import java.net.SocketTimeoutException
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import expo.modules.kotlin.Promise
import expo.modules.kotlin.exception.CodedException
import expo.modules.kotlin.exception.Exceptions
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.kotlin.records.Field
import expo.modules.kotlin.records.Record
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.concurrent.TimeUnit
import expo.modules.ps5translator.core.SendStatus
import expo.modules.ps5translator.core.ServerSnapshot

private const val STATE_EVENT = "onServerState"

class ServerStartException(message: String) : CodedException("ERR_TV_SERVER_START", message, null)

class RemotePlayArguments : Record {
  @Field var settingsJson: String = "{}"
  @Field var keepWifiAwake: Boolean = true
}

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

    AsyncFunction("hasOverlayPermission") {
      Settings.canDrawOverlays(context)
    }

    AsyncFunction("requestOverlayPermission") {
      context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
        .setData(Uri.parse("package:${context.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    AsyncFunction("showTranslationOverlay") { text: String ->
      mainHandler.post { TranslationOverlay.show(context, text) }
    }

    AsyncFunction("startScreenCapture") {
      val intent = Intent(context, CapturePermissionActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      context.startActivity(intent)
    }

    AsyncFunction("stopScreenCapture") {
      context.stopService(Intent(context, ScreenCaptureService::class.java))
    }

    AsyncFunction("startBackgroundTranslation") {
      BackgroundTranslator.start(context.applicationContext)
    }

    AsyncFunction("stopBackgroundTranslation") {
      BackgroundTranslator.stop(context.applicationContext)
    }

    AsyncFunction("getBackgroundTranslationStatus") {
      Bundle().apply {
        putBoolean("enabled", BackgroundTranslator.enabled)
        putString("status", BackgroundTranslator.status)
        putString("english", BackgroundTranslator.lastEnglish)
        putString("turkish", BackgroundTranslator.lastTurkish)
      }
    }

    AsyncFunction("getScreenCaptureStatus") {
      Bundle().apply {
        putBoolean("hasFrame", ScreenCaptureFrames.latest != null)
        putString("error", ScreenCaptureFrames.lastError)
      }
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

    AsyncFunction("registerConsole") { host: String, account: String, pin: String ->
      ConsoleCredentials.register(context, host.trim(), account, pin.trim())
    }

    AsyncFunction("isConsoleRegistered") { host: String ->
      val keys = runCatching { ConsoleCredentials.read(context, host.trim()) }.getOrNull()
      keys?.fill(0)
      keys != null
    }

    AsyncFunction("forgetConsole") { host: String ->
      require(RemotePlayService.status.host != host) { "PS5 kaydını silmeden önce çeviriyi durdurun." }
      ConsoleCredentials.remove(context, host.trim())
    }

    AsyncFunction("startSession") { host: String, args: RemotePlayArguments ->
      require(isConsoleIpv4(host)) { "Geçerli bir PS5 IPv4 adresi girin." }
      val keys = ConsoleCredentials.read(context, host)
        ?: error("Seçilen PS5 için önce eşleştirme yapın.")
      keys.fill(0)
      RemotePlayService.start(context, host, args.keepWifiAwake, args.settingsJson)
    }

    AsyncFunction("updateTranslation") { json: String -> RemotePlayService.configure(TranslationOptions.parse(json)) }

    AsyncFunction("getCloudStatus") { promise: Promise ->
      val app = context.applicationContext
      val db = TranslationArchive.get(app)
      db.reads.execute {
        try {
          val configured = GoogleKeyStore.read(app) != null
          promise.resolve(db.currentUsage().put("configured", configured)
            .put("status", if (configured) GoogleKeyStore.status else API_NOT_CONFIGURED).toString())
        } catch (_: Exception) {
          promise.reject("ERR_CLOUD_STATUS", "Google Cloud ayarları okunamadı.", null)
        }
      }
    }

    AsyncFunction("saveCloudKey") { key: String ->
      GoogleKeyStore.save(context.applicationContext, key)
      RemotePlayService.refreshCloud()
    }

    AsyncFunction("revealCloudKey") {
      GoogleKeyStore.read(context.applicationContext)
    }

    AsyncFunction("deleteCloudKey") {
      GoogleKeyStore.remove(context.applicationContext)
      RemotePlayService.refreshCloud()
    }

    AsyncFunction("testCloudConnection") { daily: Int, monthly: Int, promise: Promise ->
      val provider = GoogleCloudProvider(context.applicationContext, CloudLimits(
        daily.coerceIn(0, 10000000), monthly.coerceIn(0, 100000000)))
      provider.test().addOnCompleteListener { task ->
        provider.close()
        if (task.isSuccessful) promise.resolve("Google Cloud bağlı")
        else promise.reject("ERR_CLOUD_TEST", task.exception?.message ?: "Google Cloud isteği tamamlanamadı.", null)
      }
    }

    AsyncFunction("getTranslationMetrics") { TranslationMetrics.snapshot().toString() }

    AsyncFunction("getArchiveOverview") { promise: Promise ->
      val db = TranslationArchive.get(context)
      archiveTask(db, false, promise) {
        org.json.JSONObject().put("games", db.games()).put("bytes", db.byteSize()).toString()
      }
    }

    AsyncFunction("createArchiveGame") { name: String, promise: Promise ->
      val db = TranslationArchive.get(context)
      archiveTask(db, true, promise) { db.createGame(name) }
    }

    AsyncFunction("getArchivePage") { game: String, search: String, before: Double, promise: Promise ->
      val db = TranslationArchive.get(context)
      archiveTask(db, false, promise) { db.page(game, search, before.toLong()).toString() }
    }

    AsyncFunction("editArchiveRecord") { id: Double, translated: String, promise: Promise ->
      val db = TranslationArchive.get(context)
      archiveTask(db, true, promise) { db.edit(id.toLong(), translated); null }
    }

    AsyncFunction("deleteArchiveRecord") { id: Double, promise: Promise ->
      val db = TranslationArchive.get(context)
      archiveTask(db, true, promise) { db.delete(id.toLong()); null }
    }

    AsyncFunction("exportTranslationArchive") { game: String?, promise: Promise ->
      ArchiveTransferActivity.open(context, true, game, promise)
    }

    AsyncFunction("importTranslationArchive") { promise: Promise ->
      ArchiveTransferActivity.open(context, false, null, promise)
    }

    AsyncFunction("getLanguageModels") { promise: Promise ->
      LanguageModels.downloaded().addOnCompleteListener { task ->
        if (task.isSuccessful) promise.resolve(task.result.map { it.language }.sorted())
        else promise.reject("ERR_ML_MODEL", "Dil paketleri okunamadı.", task.exception)
      }
    }

    AsyncFunction("downloadLanguageModel") { language: String, promise: Promise ->
      LanguageModels.download(language).addOnCompleteListener { task ->
        if (task.isSuccessful) {
          RemotePlayService.refreshModels()
          promise.resolve()
        } else promise.reject("ERR_ML_MODEL", "Dil paketi indirilemedi. İnternet bağlantısını kontrol edin.", task.exception)
      }
    }

    AsyncFunction("removeLanguageModel") { language: String, promise: Promise ->
      check(!RemotePlayService.status.active && !BackgroundTranslator.enabled) { "Dil paketini kaldırmadan önce çeviriyi durdurun." }
      LanguageModels.remove(language).addOnCompleteListener { task ->
        if (task.isSuccessful) promise.resolve()
        else promise.reject("ERR_ML_MODEL", "Dil paketi kaldırılamadı.", task.exception)
      }
    }

    AsyncFunction("translateOffline") { text: String, source: String, target: String ->
      val from = TranslateLanguage.fromLanguageTag(source) ?: error("Kaynak dil desteklenmiyor.")
      val to = TranslateLanguage.fromLanguageTag(target) ?: error("Hedef dil desteklenmiyor.")
      val translator = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(from).setTargetLanguage(to).build())
      try {
        Tasks.await(translator.downloadModelIfNeeded(DownloadConditions.Builder().build()), 180, TimeUnit.SECONDS)
        Tasks.await(translator.translate(text), 12, TimeUnit.SECONDS)
      } finally { translator.close() }
    }

    AsyncFunction("stopSession") { RemotePlayService.stop(context) }

    AsyncFunction("getRemotePlayStatus") {
      val state = RemotePlayService.status
      Bundle().apply {
        putBoolean("active", state.active)
        putInt("overlayClients", ServerController.core.overlayClientCount())
        putString("host", state.host)
        putString("console", state.console)
        putString("session", state.session)
        putString("video", state.video)
        putString("ocr", state.ocr)
        putString("translation", state.translation)
        putString("english", state.english)
        putString("turkish", state.turkish)
        putString("error", state.error)
      }
    }

    AsyncFunction("discoverConsoles") { timeoutMs: Int ->
      val found = LinkedHashMap<String, Bundle>()
      val deadline = SystemClock.elapsedRealtime() + timeoutMs.coerceIn(500, 8000)
      val request = "SRCH * HTTP/1.1\r\ndevice-discovery-protocol-version:00030010\r\n\r\n"
        .toByteArray(Charsets.US_ASCII)
      DatagramSocket().use { socket ->
        socket.broadcast = true
        socket.soTimeout = 300
        val destinations = buildSet {
          add(InetAddress.getByName("255.255.255.255"))
          NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { network ->
            if (network.isUp && !network.isLoopback) {
              network.interfaceAddresses.mapNotNullTo(this) { it.broadcast }
            }
          }
        }
        for (destination in destinations) {
          runCatching { socket.send(DatagramPacket(request, request.size, destination, 9302)) }
        }
        val bytes = ByteArray(4096)
        while (SystemClock.elapsedRealtime() < deadline) {
          val packet = DatagramPacket(bytes, bytes.size)
          try {
            socket.receive(packet)
            val message = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
            fun header(name: String): String? = message.lineSequence().firstOrNull {
              it.startsWith("$name:", ignoreCase = true)
            }?.substringAfter(':')?.trim()
            if (!header("host-type").equals("PS5", ignoreCase = true)) continue
            val host = packet.address.hostAddress ?: continue
            if (!isConsoleIpv4(host)) continue
            val state = if (message.lineSequence().first().contains(" 620")) "standby" else "ready"
            found[host] = Bundle().apply {
              putString("name", header("host-name") ?: "PS5")
              putString("host", host)
              putString("state", state)
            }
          } catch (_: SocketTimeoutException) { /* Continue collecting LAN responses. */ }
        }
      }
      ArrayList(found.values)
    }

    // Temporary real-video path: open a separately installed Chiaki client.
    // The user pairs the PS5 in Chiaki. MediaProjection then captures its screen.
    // This is NOT an embedded Remote Play session or a headless stream.
    AsyncFunction("openExternalRemotePlay") {
      val packages = listOf("com.streetpea.chiaki4deck", "com.metallic.chiaki", "com.chiaki")
      val pm = context.packageManager
      val launch = packages.firstNotNullOfOrNull { packageName ->
        pm.getLaunchIntentForPackage(packageName)
      } ?: throw IllegalStateException(
        "Chiaki Android uygulaması bulunamadı. Önce uyumlu Chiaki istemcisini kurup PS5 eşleştirmesini orada yapın."
      )
      launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      context.startActivity(launch)
      true
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

  private fun archiveTask(db: TranslationArchive, write: Boolean, promise: Promise, action: () -> Any?) {
    val executor = if (write) db.writes else db.reads
    executor.execute {
      try { promise.resolve(action()) }
      catch (e: Exception) {
        val message = if (e is IllegalArgumentException || e is IllegalStateException) e.message
          else "Arşiv işlemi tamamlanamadı."
        promise.reject("ERR_ARCHIVE", message, null)
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
