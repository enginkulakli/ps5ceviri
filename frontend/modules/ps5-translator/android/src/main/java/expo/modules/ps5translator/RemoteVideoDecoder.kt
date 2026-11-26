package expo.modules.ps5translator

import android.graphics.ImageFormat
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.SystemClock
import java.nio.ByteBuffer
import expo.modules.ps5translator.core.FrameRect
import expo.modules.ps5translator.core.OcrFrame
import expo.modules.ps5translator.core.PhoneSignature
import expo.modules.ps5translator.core.pixelCrop

/** The transport thread feeds compressed samples. The service worker drains decoded frames. */
internal class RemoteVideoDecoder {
  private val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
    try {
      configure(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1280, 720).apply {
        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 * 1024 * 1024)
      }, null, null, 0)
      start()
    } catch (error: Exception) {
      release()
      throw error
    }
  }
  private val outputInfo = MediaCodec.BufferInfo()
  private var parameterSets: ByteArray? = null
  private var lastPresentationUs = 0L

  /** The input ByteBuffer is valid only during this call. */
  fun queue(sample: ByteBuffer): Boolean {
    if (isCodecConfig(sample)) {
      if (sample.remaining() > 64 * 1024) return false
      parameterSets = ByteArray(sample.remaining()).also(sample::get)
      return true
    }
    parameterSets?.let { pending ->
      if (!queueInput(ByteBuffer.wrap(pending), MediaCodec.BUFFER_FLAG_CODEC_CONFIG)) return false
      parameterSets = null
    }
    return queueInput(sample, 0)
  }

  private fun isCodecConfig(sample: ByteBuffer): Boolean {
    var offset = sample.position()
    var hasParameterSet = false
    while (offset + 3 < sample.limit()) {
      if (sample.get(offset).toInt() == 0 && sample.get(offset + 1).toInt() == 0 &&
        sample.get(offset + 2).toInt() == 1) {
        val type = sample.get(offset + 3).toInt() and 0x1f
        if (type in 1..5) return false // This access unit also contains a picture.
        if (type == 7 || type == 8) hasParameterSet = true
        offset += 4
      } else offset++
    }
    return hasParameterSet
  }

  private fun queueInput(sample: ByteBuffer, flags: Int): Boolean {
    val index = codec.dequeueInputBuffer(0)
    if (index < 0) return false
    val size = sample.remaining()
    val input = codec.getInputBuffer(index)
    if (input == null || size > input.remaining()) {
      codec.queueInputBuffer(index, 0, 0, lastPresentationUs, 0)
      return false
    }
    input.put(sample)
    lastPresentationUs = maxOf(lastPresentationUs + 1, SystemClock.elapsedRealtimeNanos() / 1000)
    codec.queueInputBuffer(index, 0, size, lastPresentationUs, flags)
    return true
  }

  fun drain(needImage: () -> Boolean, onFrame: (Image?) -> Unit) {
    repeat(12) {
      val index = codec.dequeueOutputBuffer(outputInfo, 0)
      if (index == MediaCodec.INFO_TRY_AGAIN_LATER) return
      if (index < 0) return@repeat
      try {
        if (outputInfo.size > 0 && outputInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
          val image = if (needImage()) {
            checkNotNull(codec.getOutputImage(index)) { "H.264 çözücüsü okunabilir YUV karesi vermedi." }
          } else null
          image.use { onFrame(it) }
        }
      } finally {
        codec.releaseOutputBuffer(index, false)
      }
    }
  }

  /** Called after the transport has stopped invoking queue(). */
  fun close() = codec.release()
}

internal data class SubtitleRegion(val x: Double, val y: Double, val width: Double, val height: Double) {
  fun crop(image: Image, exclusions: List<FrameRect> = emptyList()): OcrFrame {
    check(image.format == ImageFormat.YUV_420_888) { "Video decoder did not return YUV_420_888" }
    val bounds = image.cropRect
    val crop = FrameRect(x, y, width, height).pixelCrop(bounds.width(), bounds.height())
    val left = bounds.left + crop.left
    val top = bounds.top + crop.top
    val w = crop.width
    val h = crop.height
    val plane = image.planes[0]
    val buffer = plane.buffer
    val base = buffer.position()
    val bytes = ByteArray(w * h * 3 / 2)
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride
    for (row in 0 until h) {
      val start = base + (top + row) * rowStride + left * pixelStride
      if (pixelStride == 1) {
        buffer.position(start)
        buffer.get(bytes, row * w, w)
      } else {
        for (column in 0 until w) bytes[row * w + column] = buffer.get(start + column * pixelStride)
      }
    }
    for (excluded in exclusions) {
      val fromX = (bounds.left + (excluded.x * bounds.width()).toInt() - left).coerceIn(0, w)
      val toX = (bounds.left + ((excluded.x + excluded.w) * bounds.width()).toInt() - left).coerceIn(0, w)
      val fromY = (bounds.top + (excluded.y * bounds.height()).toInt() - top).coerceIn(0, h)
      val toY = (bounds.top + ((excluded.y + excluded.h) * bounds.height()).toInt() - top).coerceIn(0, h)
      for (row in fromY until toY) bytes.fill(16, row * w + fromX, row * w + toX)
    }
    bytes.fill(128.toByte(), w * h)
    return OcrFrame(bytes, w, h)
  }
}

internal fun Image.phoneSignature(): PhoneSignature {
  val bounds = cropRect
  val plane = planes[0]
  val bytes = plane.buffer
  val origin = bytes.position() + bounds.top * plane.rowStride + bounds.left * plane.pixelStride
  return PhoneSignature.sample(bounds.width(), bounds.height()) { x, y ->
    bytes.get(origin + y * plane.rowStride + x * plane.pixelStride).toInt() and 255
  }
}
