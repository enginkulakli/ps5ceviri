package expo.modules.ps5translator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * User-consented screen capture. Android 14 requires a fresh consent for every session.
 * No image is transmitted to the TV or backend by this service.
 */
class ScreenCaptureService : Service() {
  private var projection: MediaProjection? = null
  private var display: VirtualDisplay? = null
  private var reader: ImageReader? = null
  private val thread = HandlerThread("screen-capture")
  private lateinit var handler: Handler

  override fun onCreate() {
    super.onCreate()
    thread.start()
    handler = Handler(thread.looper)
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_STOP) {
      stopSelf()
      return START_NOT_STICKY
    }
    val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
    val data = if (Build.VERSION.SDK_INT >= 33)
      intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
    else intent?.getParcelableExtra(EXTRA_RESULT_DATA)
    if (resultCode != android.app.Activity.RESULT_OK || data == null) {
      stopSelf()
      return START_NOT_STICKY
    }
    val channel = "ps5_screen_capture"
    val nm = getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(NotificationChannel(channel, "Ekran çevirisi", NotificationManager.IMPORTANCE_LOW))
    val notification = Notification.Builder(this, channel)
      .setContentTitle("Ekran metni okunuyor")
      .setContentText("Durdurmak için uygulamaya dönün")
      .setSmallIcon(android.R.drawable.ic_menu_view)
      .build()
    if (Build.VERSION.SDK_INT >= 29)
      startForeground(8072, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    else startForeground(8072, notification)

    try {
      val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
      projection = manager.getMediaProjection(resultCode, data)
      val metrics = resources.displayMetrics
      val width = metrics.widthPixels
      val height = metrics.heightPixels
      val density = metrics.densityDpi
      val callback = object : MediaProjection.Callback() {
        override fun onStop() { stopSelf() }
      }
      projection?.registerCallback(callback, handler)
      reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
      display = projection?.createVirtualDisplay(
        "PS5 screen OCR", width, height, density,
        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader?.surface, null, handler
      )
      reader?.setOnImageAvailableListener({ source ->
        val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
        try {
          val now = android.os.SystemClock.elapsedRealtime()
          if (now - lastCapture < 850L) return@setOnImageAvailableListener
          lastCapture = now
          val plane = image.planes[0]
          val pixelStride = plane.pixelStride
          val rowStride = plane.rowStride
          val paddedWidth = width + (rowStride - pixelStride * width) / pixelStride
          val bitmap = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
          try {
            bitmap.copyPixelsFromBuffer(plane.buffer)
            val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)
            try {
              val stream = ByteArrayOutputStream()
              cropped.compress(Bitmap.CompressFormat.JPEG, 65, stream)
              // Frame stays on-device; consumers can request the most recent frame.
              ScreenCaptureFrames.latest = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
            } finally { cropped.recycle() }
          } finally { bitmap.recycle() }
        } catch (_: Exception) {
          // Screen rotation or temporary surface invalidation: next frame may recover.
        } finally { image.close() }
      }, handler)
    } catch (_: Exception) { stopSelf() }
    return START_NOT_STICKY
  }

  private var lastCapture = 0L
  override fun onDestroy() {
    reader?.setOnImageAvailableListener(null, null)
    display?.release()
    reader?.close()
    projection?.stop()
    ScreenCaptureFrames.latest = null
    thread.quitSafely()
    super.onDestroy()
  }
  override fun onBind(intent: Intent?): IBinder? = null

  companion object {
    const val ACTION_STOP = "ps5.capture.STOP"
    const val EXTRA_RESULT_CODE = "resultCode"
    const val EXTRA_RESULT_DATA = "resultData"
  }
}

internal object ScreenCaptureFrames {
  @Volatile var latest: String? = null
}
