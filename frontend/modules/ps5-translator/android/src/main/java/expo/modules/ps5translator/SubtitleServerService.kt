package expo.modules.ps5translator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import expo.modules.ps5translator.core.ServerSnapshot

/**
 * Foreground service (type connectedDevice) that keeps the TV WebSocket server alive while the
 * app is in the background or the screen is off. Shows a persistent notification with a Stop action.
 * Network work runs on its own HandlerThread, never on the main thread.
 */
class SubtitleServerService : Service() {
  companion object {
    const val ACTION_START = "expo.modules.ps5translator.START"
    const val ACTION_STOP = "expo.modules.ps5translator.STOP"
    private const val CHANNEL_ID = "tv_subtitle_server"
    private const val NOTIF_ID = 8787

    fun start(context: Context) {
      val i = Intent(context, SubtitleServerService::class.java).setAction(ACTION_START)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i) else context.startService(i)
    }

    fun stop(context: Context) {
      val i = Intent(context, SubtitleServerService::class.java).setAction(ACTION_STOP)
      context.startService(i)
    }
  }

  private lateinit var worker: HandlerThread
  private lateinit var handler: Handler
  private var wifiLock: WifiManager.WifiLock? = null
  private var wakeLock: PowerManager.WakeLock? = null
  private var networkCallback: ConnectivityManager.NetworkCallback? = null
  private val snapshotListener: (ServerSnapshot) -> Unit = { snap -> updateNotification(snap) }
  private var lastNotifKey = ""

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    worker = HandlerThread("tv-ws-service").also { it.start() }
    handler = Handler(worker.looper)
    createChannel()
    ServerController.addListener(snapshotListener)
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    // Must call startForeground quickly after startForegroundService, also for STOP.
    goForeground(buildNotification(ServerController.core.snapshot()))
    val prefs = ServerPrefs(this)
    when (intent?.action) {
      ACTION_STOP -> {
        prefs.shouldRun = false
        handler.post {
          ServerController.core.stop()
          stopSelfSafely()
        }
        return START_NOT_STICKY
      }
      else -> {
        // ACTION_START, or null intent when the system restarts a sticky service
        if (intent == null && !prefs.shouldRun) {
          stopSelfSafely(); return START_NOT_STICKY
        }
        prefs.shouldRun = true
        acquireLocks()
        registerNetworkCallback()
        handler.post {
          ServerController.localIp = NetworkUtils.localIpv4(this)
          ServerController.wifiConnected = NetworkUtils.isOnWifi(this)
          if (!ServerController.wifiConnected) ServerController.core.recordError("Wi-Fi bağlı değil. TV aynı Wi-Fi ağında olmalı.")
          val err = ServerController.core.start(prefs.port, prefs.allowedIp)
          if (err != null) prefs.shouldRun = false
          ServerController.notifyChanged()
        }
      }
    }
    return START_STICKY
  }

  private fun stopSelfSafely() {
    releaseLocks()
    unregisterNetworkCallback()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
    stopSelf()
  }

  override fun onDestroy() {
    ServerController.removeListener(snapshotListener)
    ServerController.core.stop()
    releaseLocks()
    unregisterNetworkCallback()
    worker.quitSafely()
    super.onDestroy()
  }

  private fun goForeground(n: Notification) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
    } else {
      startForeground(NOTIF_ID, n)
    }
  }

  // ---------- locks ----------
  private fun acquireLocks() {
    if (wifiLock == null) {
      val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
      val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else @Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF
      wifiLock = wm.createWifiLock(mode, "ps5translator:tv").apply { setReferenceCounted(false); acquire() }
    }
    if (wakeLock == null) {
      val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
      wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ps5translator:tv").apply { setReferenceCounted(false); acquire() }
    }
  }

  private fun releaseLocks() {
    wifiLock?.let { if (it.isHeld) it.release() }
    wifiLock = null
    wakeLock?.let { if (it.isHeld) it.release() }
    wakeLock = null
  }

  // ---------- network changes ----------
  private fun registerNetworkCallback() {
    if (networkCallback != null) return
    val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val cb = object : ConnectivityManager.NetworkCallback() {
      override fun onAvailable(network: Network) = refreshNetwork(null)
      override fun onLost(network: Network) = refreshNetwork("Wi-Fi bağlantısı koptu. Yeniden bağlanınca TV otomatik bağlanabilir.")
      override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = refreshNetwork(null)
    }
    try {
      cm.registerDefaultNetworkCallback(cb, handler)
      networkCallback = cb
    } catch (_: Throwable) {}
  }

  private fun refreshNetwork(lostMessage: String?) {
    handler.postDelayed({
      val oldIp = ServerController.localIp
      ServerController.localIp = NetworkUtils.localIpv4(this)
      ServerController.wifiConnected = NetworkUtils.isOnWifi(this)
      if (lostMessage != null && !ServerController.wifiConnected) ServerController.core.recordError(lostMessage)
      else if (oldIp != null && ServerController.localIp != null && oldIp != ServerController.localIp)
        ServerController.core.recordError("Tablet IP adresi değişti: $oldIp → ${ServerController.localIp}. TV uygulamasında yeni adresi kullanın.")
      ServerController.notifyChanged()
    }, 300)
  }

  private fun unregisterNetworkCallback() {
    val cb = networkCallback ?: return
    val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    try { cm.unregisterNetworkCallback(cb) } catch (_: Throwable) {}
    networkCallback = null
  }

  // ---------- notification ----------
  private fun createChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val nm = getSystemService(NotificationManager::class.java)
      val ch = NotificationChannel(CHANNEL_ID, "TV altyazı sunucusu", NotificationManager.IMPORTANCE_LOW)
      ch.description = "TV'ye altyazı gönderen sunucu çalışırken gösterilir"
      ch.setShowBadge(false)
      nm.createNotificationChannel(ch)
    }
  }

  private fun buildNotification(snap: ServerSnapshot): Notification {
    val ip = ServerController.localIp ?: "IP yok"
    val title = if (snap.running) "TV sunucusu çalışıyor" else "TV sunucusu başlatılıyor"
    val text = if (snap.running) "ws://$ip:${snap.port} · ${snap.clients.size} TV bağlı" else "ws://$ip:${snap.port}"
    val launch = packageManager.getLaunchIntentForPackage(packageName)
    val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
    val contentPi = launch?.let { PendingIntent.getActivity(this, 0, it, piFlags) }
    val stopPi = PendingIntent.getService(this, 1, Intent(this, SubtitleServerService::class.java).setAction(ACTION_STOP), piFlags)
    val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_ID) else @Suppress("DEPRECATION") Notification.Builder(this)
    b.setContentTitle(title)
      .setContentText(text)
      .setSmallIcon(android.R.drawable.stat_sys_upload)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .addAction(Notification.Action.Builder(null, "Durdur", stopPi).build())
    contentPi?.let { b.setContentIntent(it) }
    return b.build()
  }

  private fun updateNotification(snap: ServerSnapshot) {
    val key = "${snap.running}|${snap.clients.size}|${ServerController.localIp}|${snap.port}"
    if (key == lastNotifKey) return
    lastNotifKey = key
    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    try { nm.notify(NOTIF_ID, buildNotification(snap)) } catch (_: Throwable) {}
  }
}
