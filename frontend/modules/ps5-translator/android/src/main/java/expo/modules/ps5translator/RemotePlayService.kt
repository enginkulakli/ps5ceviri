package expo.modules.ps5translator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import java.nio.ByteBuffer

internal data class RemotePlayStatus(
  val active: Boolean = false,
  val host: String? = null,
  val console: String = "Bekliyor",
  val session: String = "Kapalı",
  val video: String = "Bekliyor",
  val ocr: String = "Bekliyor",
  val translation: String = "Bekliyor",
  val english: String = "",
  val turkish: String = "",
  val error: String? = null,
)

class RemotePlayService : Service() {
  private lateinit var worker: HandlerThread
  private lateinit var handler: Handler
  private var options: RemotePlayOptions? = null
  private var pipeline: RemoteSubtitlePipeline? = null
  private var decoder: RemoteVideoDecoder? = null
  private var nativeHandle = 0L
  private var attemptNumber = 0
  private var streamToken = 0L
  private var streamingSince = 0L
  private var deadline = 0L
  private var startRequestId = 0
  private var wakeLock: PowerManager.WakeLock? = null
  private var wifiLock: WifiManager.WifiLock? = null
  private val reconnect = Runnable { connect() }
  private val drain = Runnable { drainVideo() }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    worker = HandlerThread("ps5-remote-play").apply { start() }
    handler = Handler(worker.looper)
    instance = this
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_START -> {
        showNotification()
        handler.post {
          startRequestId = startId
          begin(intent)
        }
      }
      ACTION_STOP -> handler.post {
        stopWork()
        stopSelfResult(startId)
      }
      else -> stopSelfResult(startId)
    }
    return START_NOT_STICKY
  }

  private fun begin(intent: Intent) {
    val host = intent.getStringExtra("host") ?: return
    if (options?.host == host) return
    stopWork()
    val config = RemotePlayOptions(host, intent.getBooleanExtra("wifi", true),
      TranslationOptions.parse(intent.getStringExtra("translation") ?: "{}"))
    status = RemotePlayStatus(active = true, host = host, console = "PS5'e bağlanılıyor")
    options = config
    attemptNumber = 0
    try {
      acquireLocks(config.keepWifiAwake)
      pipeline = RemoteSubtitlePipeline(handler, config.translation, ::update).also { it.prepare() }
      connect()
    } catch (e: Exception) {
      finish(e.message ?: "Çeviri başlatılamadı")
    }
  }

  private fun connect() {
    val config = options ?: return
    status = status.copy(console = "PS5'e bağlanılıyor", session = "Oturum açılıyor", video = "Kare bekleniyor", error = null)
    val keys = try {
      ConsoleCredentials.read(applicationContext, config.host)
        ?: return finish("Seçilen PS5 için önce eşleştirme yapın.")
    } catch (_: Exception) {
      return finish("PS5 eşleştirme anahtarları okunamadı. Konsolu yeniden eşleştirin.")
    }
    val token = ++streamToken
    val videoDecoder = try {
      RemoteVideoDecoder()
    } catch (e: Exception) {
      keys.fill(0)
      return finish("H.264 donanım veya yazılım çözücüsü açılamadı: ${e.message}")
    }
    decoder = videoDecoder
    try {
      nativeHandle = RemotePlayNative.start(config.host, keys, object : RemotePlayNative.Receiver {
        override fun onVideo(data: ByteBuffer): Boolean = try {
          videoDecoder.queue(data)
        } catch (e: Exception) {
          handler.post { if (token == streamToken) finish("Video çözücü hatası: ${e.message}") }
          false
        }

        override fun onEvent(code: Int, message: String?) {
          handler.post {
            if (token != streamToken) return@post
            when (code) {
              0 -> update { it.copy(console = "PS5 bağlı", session = "Remote Play açık") }
              1 -> finish("PS5 oturum için kullanıcı giriş kodu istiyor. PS5'te bu kullanıcıyla giriş yapın ve yeniden deneyin.")
              2 -> retry(message ?: "Remote Play bağlantısı kesildi")
              3 -> finish(message ?: "PS5 oturumu kapattı")
            }
          }
        }
      })
      check(nativeHandle != 0L) { "Remote Play oturumu başlatılamadı" }
      deadline = SystemClock.elapsedRealtime() + 15000
      handler.post(drain)
    } catch (e: LinkageError) {
      finish("Remote Play yerel kütüphanesi yüklenemedi: ${e.message}")
    } catch (e: Exception) {
      retry(e.message ?: "Remote Play bağlantısı kurulamadı")
    } finally {
      keys.fill(0)
    }
  }

  private fun drainVideo() {
    val decoder = decoder ?: return
    try {
      decoder.drain({ pipeline?.needsFrame(SystemClock.elapsedRealtime()) == true }) { image ->
        val now = SystemClock.elapsedRealtime()
        if (streamingSince == 0L) {
          streamingSince = now
          update { it.copy(video = "H.264 720p akışı alınıyor") }
        }
        if (now - streamingSince >= 15000) attemptNumber = 0
        deadline = now + 8000
        if (image != null) pipeline?.process(image)
      }
      if (SystemClock.elapsedRealtime() >= deadline) {
        retry(if (streamingSince == 0L) "PS5 görüntüsü alınamadı" else "PS5 görüntü akışı durdu")
      } else handler.postDelayed(drain, 16)
    } catch (e: Exception) {
      finish("Video karesi işlenemedi: ${e.message}")
    }
  }

  private fun retry(reason: String) {
    pipeline?.resetStream()
    closeStream()
    if (attemptNumber >= 5) return finish(reason)
    val delay = 1000L shl attemptNumber++
    update { it.copy(console = "Bağlantı kesildi", session = "${delay / 1000} sn sonra yeniden bağlanıyor",
      video = "Bekliyor", ocr = "Görüntü bekleniyor", error = reason) }
    handler.postDelayed(reconnect, delay)
  }

  private fun closeStream() {
    streamToken++
    handler.removeCallbacks(drain)
    if (nativeHandle != 0L) {
      RemotePlayNative.stop(nativeHandle)
      nativeHandle = 0L
    }
    decoder?.close()
    decoder = null
    streamingSince = 0
  }

  private fun finish(error: String) {
    stopWork(error)
    stopSelfResult(startRequestId)
  }

  private fun stopWork(error: String? = null) {
    handler.removeCallbacks(reconnect)
    pipeline?.close()
    pipeline = null
    closeStream()
    options = null
    wifiLock?.let { if (it.isHeld) it.release() }
    wifiLock = null
    wakeLock?.let { if (it.isHeld) it.release() }
    wakeLock = null
    update { RemotePlayStatus(english = it.english, turkish = it.turkish, error = error) }
  }

  private fun update(transform: (RemotePlayStatus) -> RemotePlayStatus) {
    status = transform(status)
  }

  private fun acquireLocks(keepWifiAwake: Boolean) {
    wakeLock = getSystemService(PowerManager::class.java)
      .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ps5translator:remoteplay").apply { acquire() }
    if (keepWifiAwake) {
      val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        else @Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF
      wifiLock = applicationContext.getSystemService(WifiManager::class.java)
        .createWifiLock(mode, "ps5translator:remoteplay").apply { acquire() }
    }
  }

  private fun showNotification() {
    val channel = "ps5_remote_play"
    val builder = if (Build.VERSION.SDK_INT >= 26) {
      getSystemService(NotificationManager::class.java).createNotificationChannel(
        NotificationChannel(channel, "PS5 arka plan çevirisi", NotificationManager.IMPORTANCE_LOW))
      Notification.Builder(this, channel)
    } else @Suppress("DEPRECATION") Notification.Builder(this)
    val stop = PendingIntent.getService(this, 0, Intent(this, RemotePlayService::class.java).setAction(ACTION_STOP),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    val notification = builder.setContentTitle("PS5 altyazı çevirisi")
      .setContentText("Remote Play görüntüsü arka planda işleniyor")
      .setSmallIcon(android.R.drawable.ic_menu_view).setOngoing(true)
      .addAction(Notification.Action.Builder(null, "Durdur", stop).build()).build()
    if (Build.VERSION.SDK_INT >= 29) startForeground(8073, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
    else startForeground(8073, notification)
  }

  override fun onDestroy() {
    if (instance === this) instance = null
    handler.post {
      if (options != null) stopWork()
      worker.quitSafely()
    }
    super.onDestroy()
  }

  companion object {
    private const val ACTION_START = "ps5.remoteplay.START"
    private const val ACTION_STOP = "ps5.remoteplay.STOP"

    @Volatile private var instance: RemotePlayService? = null

    @Volatile internal var status = RemotePlayStatus()
      private set

    internal fun start(context: Context, host: String, keepWifiAwake: Boolean, rawOptions: String) {
      TranslationOptions.parse(rawOptions)
      val intent = Intent(context, RemotePlayService::class.java).setAction(ACTION_START)
        .putExtra("host", host).putExtra("wifi", keepWifiAwake).putExtra("translation", rawOptions)
      if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
    }

    internal fun configure(options: TranslationOptions) {
      val service = instance ?: return
      service.handler.post { service.pipeline?.configure(options) }
    }

    internal fun refreshModels() {
      val service = instance ?: return
      service.handler.post { service.pipeline?.prepare() }
    }

    internal fun stop(context: Context) {
      context.startService(Intent(context, RemotePlayService::class.java).setAction(ACTION_STOP))
    }
  }
}
