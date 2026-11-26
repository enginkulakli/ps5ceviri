package expo.modules.ps5translator

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.os.Bundle
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Samples a real visible decoder frame on demand, then encodes it away from the video thread. */
internal object RemoteRegionPreview {
  private val request = AtomicReference<CompletableFuture<Bundle?>?>()
  private val encoder = Executors.newSingleThreadExecutor { work -> Thread(work, "ps5-region-preview") }

  fun wantsFrame() = request.get() != null

  fun capture(): Bundle? {
    val future = CompletableFuture<Bundle?>()
    if (!request.compareAndSet(null, future)) return null
    return try {
      future.get(2, TimeUnit.SECONDS)
    } catch (_: Exception) {
      null
    } finally {
      request.compareAndSet(future, null)
      future.cancel(false)
    }
  }

  fun accept(image: Image) {
    val future = request.getAndSet(null) ?: return
    try {
      val source = image.cropRect
      val sourceWidth = source.width()
      val sourceHeight = source.height()
      val scale = minOf(1.0, 640.0 / maxOf(sourceWidth, sourceHeight))
      val width = (sourceWidth * scale).toInt() and -2
      val height = (sourceHeight * scale).toInt() and -2
      if (image.format != ImageFormat.YUV_420_888 || width < 2 || height < 2) {
        future.complete(null)
        return
      }
      val output = ByteArray(width * height * 3 / 2)
      val (y, u, v) = image.planes
      val yBuffer = y.buffer
      val uBuffer = u.buffer
      val vBuffer = v.buffer
      fun sample(plane: Image.Plane, bytes: java.nio.ByteBuffer, col: Int, row: Int) =
        bytes.get(bytes.position() + row * plane.rowStride + col * plane.pixelStride)
      val columns = IntArray(width) { source.left + it * sourceWidth / width }
      for (row in 0 until height) {
        val inputY = source.top + row * sourceHeight / height
        for (col in columns.indices) output[row * width + col] = sample(y, yBuffer, columns[col], inputY)
        if (row % 2 != 0) continue
        for (col in columns.indices step 2) {
          val index = width * height + (row / 2) * width + col
          output[index] = sample(v, vBuffer, columns[col] / 2, inputY / 2)
          output[index + 1] = sample(u, uBuffer, columns[col] / 2, inputY / 2)
        }
      }
      encoder.execute {
        val result = runCatching {
          ByteArrayOutputStream().use { bytes ->
            check(YuvImage(output, ImageFormat.NV21, width, height, null)
              .compressToJpeg(Rect(0, 0, width, height), 78, bytes))
            Bundle().apply {
              putString("data", Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP))
              putInt("width", sourceWidth)
              putInt("height", sourceHeight)
            }
          }
        }.getOrNull()
        future.complete(result)
      }
    } catch (_: Exception) {
      future.complete(null)
    }
  }
}
