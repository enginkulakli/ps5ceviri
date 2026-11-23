package expo.modules.ps5translator

import android.os.Handler
import android.os.SystemClock
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import expo.modules.ps5translator.core.SendStatus
import expo.modules.ps5translator.core.SubtitleTracker
import java.util.concurrent.Executor

internal data class RemotePlayOptions(
  val host: String,
  val region: SubtitleRegion,
  val intervalMs: Long,
  val clearDelayMs: Long,
  val sensitivity: Double,
  val keepWifiAwake: Boolean,
)

/** All state and subtitle writes live on the service worker. ML Kit completes asynchronously. */
internal class RemoteSubtitlePipeline(
  private val handler: Handler,
  private val options: RemotePlayOptions,
  private val update: ((RemotePlayStatus) -> RemotePlayStatus) -> Unit,
) {
  private val callbackExecutor = Executor { handler.post(it) }
  private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
  private val translator = Translation.getClient(TranslatorOptions.Builder()
    .setSourceLanguage(TranslateLanguage.ENGLISH)
    .setTargetLanguage(TranslateLanguage.TURKISH)
    .build())
  private val tracker = SubtitleTracker(options.intervalMs)
  private val cache = object : LinkedHashMap<String, String>(128, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 150
  }
  private var closed = false
  private var ocrBusy = false
  private var modelReady = false
  private var translating = false
  private var generation = 0L
  private var attempted = -1L
  private var lastScanAt = 0L
  private var published = ""
  private var lastRefreshAt = 0L
  private val expiry = Runnable { expire() }

  fun prepare() {
    update { it.copy(ocr = "Görüntü bekleniyor", translation = "ML Kit modeli hazırlanıyor") }
    translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
      .addOnCompleteListener(callbackExecutor) { task ->
        if (closed) return@addOnCompleteListener
        modelReady = task.isSuccessful
        update { it.copy(translation = if (modelReady) "ML Kit hazır" else "Model indirilemedi. İnterneti kontrol edip çeviriyi yeniden başlatın.") }
        translateLatest()
      }
  }

  fun needsFrame(now: Long) = !closed && !ocrBusy && now - lastScanAt >= options.intervalMs

  fun process(frame: OcrFrame) {
    lastScanAt = SystemClock.elapsedRealtime()
    ocrBusy = true
    val scanGeneration = streamGeneration
    val image = InputImage.fromByteArray(frame.nv21, frame.width, frame.height, 0, InputImage.IMAGE_FORMAT_NV21)
    recognizer.process(image).addOnCompleteListener(callbackExecutor) { task ->
      ocrBusy = false
      if (closed || scanGeneration != streamGeneration) return@addOnCompleteListener
      if (!task.isSuccessful) {
        update { it.copy(ocr = "OCR kareyi okuyamadı") }
        return@addOnCompleteListener
      }
      val text = subtitleText(task.result, frame.width, frame.height)
      val now = SystemClock.elapsedRealtime()
      update { it.copy(ocr = if (text.isEmpty()) "Altyazı aranıyor" else "Altyazı okunuyor") }
      val previousSeen = tracker.seenAt
      if (tracker.observe(text, now)) {
        generation++
        update { it.copy(english = tracker.text) }
        translateLatest()
      } else if (tracker.seenAt != previousSeen && published.isNotEmpty() &&
        cache[tracker.text] == published && now - lastRefreshAt >= minOf(900L, options.clearDelayMs / 2)) {
        keepVisible(published, now)
      }
      if (tracker.seenAt != previousSeen) scheduleExpiry(now)
    }
  }

  private fun subtitleText(result: Text, width: Int, height: Int): String {
    val minConfidence = (0.68 - 0.46 * options.sensitivity).toFloat()
    val center = width / 2
    val lines = result.textBlocks.flatMap { it.lines }.filter { line ->
      val bounds = line.boundingBox ?: return@filter false
      val text = line.text.trim()
      text.length in 2..160 && text.count(Char::isLetter) >= 2 &&
        (line.confidence <= 0 || line.confidence >= minConfidence) &&
        bounds.height() >= maxOf(7, height / 30)
    }
    val anchor = lines.minByOrNull { line ->
      val bounds = line.boundingBox!!
      // Prefer dialogue near the horizontal center over HUD in the corners.
      val offset = kotlin.math.abs(bounds.centerX() - center)
      offset * 3 - minOf(bounds.width(), width / 2)
    } ?: return ""
    val anchorBounds = anchor.boundingBox!!
    return lines.filter { line ->
      val bounds = line.boundingBox!!
      kotlin.math.abs(bounds.centerX() - anchorBounds.centerX()) <= width / 5 &&
        kotlin.math.abs(bounds.centerY() - anchorBounds.centerY()) <= maxOf(anchorBounds.height(), bounds.height()) * 2
    }.sortedBy { it.boundingBox!!.top }.take(3)
      .joinToString(" ") { it.text.trim().replace(Regex("\\s+"), " ") }.take(420)
  }

  private fun translateLatest() {
    val source = tracker.text
    val request = generation
    if (closed || !modelReady || translating || source.isEmpty() || request == attempted) return
    attempted = request
    cache[source]?.let { publish(request, it); return }
    translating = true
    update { it.copy(translation = "Çevriliyor") }
    translator.translate(source).addOnCompleteListener(callbackExecutor) { task ->
      translating = false
      if (closed) return@addOnCompleteListener
      update { it.copy(translation = if (task.isSuccessful) "ML Kit hazır" else "Çeviri tamamlanamadı") }
      if (task.isSuccessful) {
        val text = task.result.trim()
        if (text.isNotEmpty()) {
          cache[source] = text
          publish(request, text)
        }
      }
      translateLatest()
    }
  }

  private fun publish(request: Long, text: String) {
    if (request != generation || tracker.text.isEmpty()) return
    val now = SystemClock.elapsedRealtime()
    val remaining = tracker.seenAt + options.clearDelayMs - now
    if (remaining <= 0) return
    val core = ServerController.core
    val result = if (text == published && core.refresh(text, remaining)) null
      else core.send(text, ttlMs = remaining)
    published = text
    lastRefreshAt = now
    if (result?.status == SendStatus.SENT) update { it.copy(turkish = text) }
  }

  private fun keepVisible(text: String, now: Long) {
    val core = ServerController.core
    if (!core.refresh(text, options.clearDelayMs)) {
      val result = core.send(text, ttlMs = options.clearDelayMs)
      if (result.status == SendStatus.SENT) update { it.copy(turkish = text) }
    }
    lastRefreshAt = now
  }

  private fun scheduleExpiry(now: Long) {
    handler.removeCallbacks(expiry)
    handler.postDelayed(expiry, maxOf(1, tracker.seenAt + options.clearDelayMs - now))
  }

  private fun expire() {
    if (tracker.text.isEmpty()) return
    val now = SystemClock.elapsedRealtime()
    if (now < tracker.seenAt + options.clearDelayMs) {
      scheduleExpiry(now)
      return
    }
    clearSubtitle()
  }

  private fun clearSubtitle() {
    handler.removeCallbacks(expiry)
    tracker.clear()
    generation++
    ServerController.core.clearIfCurrent(published)
    published = ""
  }

  private var streamGeneration = 0L

  fun resetStream() {
    streamGeneration++
    clearSubtitle()
  }

  fun close() {
    closed = true
    resetStream()
    recognizer.close()
    translator.close()
  }
}
