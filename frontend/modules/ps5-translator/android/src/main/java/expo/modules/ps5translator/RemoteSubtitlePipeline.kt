package expo.modules.ps5translator

import android.media.Image
import android.os.Handler
import android.os.SystemClock
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import expo.modules.ps5translator.core.DetectedText
import expo.modules.ps5translator.core.FrameRect
import expo.modules.ps5translator.core.GameGlossary
import expo.modules.ps5translator.core.OverlayBox
import expo.modules.ps5translator.core.OverlayMessage
import expo.modules.ps5translator.core.OverlayTracker
import expo.modules.ps5translator.core.SendStatus
import expo.modules.ps5translator.core.SubtitleTracker
import expo.modules.ps5translator.core.cleanOcr
import java.util.concurrent.Executor

internal data class RemotePlayOptions(val host: String, val keepWifiAwake: Boolean, val translation: TranslationOptions)

/** OCR results, cache and publication are serialized on the Remote Play worker. */
internal class RemoteSubtitlePipeline(
  private val handler: Handler,
  private var options: TranslationOptions,
  private val update: ((RemotePlayStatus) -> RemotePlayStatus) -> Unit,
) {
  private val callbackExecutor = Executor { handler.post(it) }
  private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
  private var provider = options.engine.provider()
  private var glossary = GameGlossary(options.glossary)
  private var tracker = SubtitleTracker(options.intervalMs, options.confirmations)
  private val overlay = OverlayTracker()
  private val hud = HudFilter()
  private val cache = object : LinkedHashMap<String, String>(128, .75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 180
  }
  private val inFlight = HashSet<String>()
  private val failed = HashMap<String, Long>()
  private var closed = false
  private var ocrBusy = false
  private var modelReady = false
  private var preparing = false
  private var scanEpoch = 0L
  private var translationEpoch = 0L
  private var lastScanAt = 0L
  private var videoWidth = 1280
  private var videoHeight = 720
  private var caption: Caption? = null
  private var previousOverlay = emptyList<OverlayBox>()
  private var overlayAt = 0L
  private val tick = Runnable { reconcile() }

  private data class Caption(val source: String, val text: String, val shownAt: Long, var until: Long, var sentAt: Long)

  fun prepare() {
    if (closed || modelReady || preparing) return
    if (!provider.available) {
      update { it.copy(ocr = "Bekliyor", translation = API_NOT_CONFIGURED) }
      return
    }
    preparing = true
    val active = provider
    update { it.copy(ocr = "Görüntü bekleniyor", translation = "ML Kit modeli hazırlanıyor") }
    active.prepare().addOnCompleteListener(callbackExecutor) { task ->
      if (closed || provider !== active) return@addOnCompleteListener
      preparing = false
      modelReady = task.isSuccessful
      update { it.copy(translation = if (modelReady) "ML Kit hazır" else "Model indirilemedi. Ayarlar → Dil paketleri bölümünü kontrol edin.") }
    }
  }

  fun configure(next: TranslationOptions) {
    if (next == options) return
    val engineChanged = next.engine != options.engine
    if (engineChanged || next.glossary != options.glossary) {
      translationEpoch++
      cache.clear()
      inFlight.clear()
      failed.clear()
      glossary = GameGlossary(next.glossary)
    }
    options = next
    resetStream()
    update { it.copy(english = "", turkish = "", ocr = "Görüntü bekleniyor") }
    if (engineChanged) {
      provider.close()
      provider = next.engine.provider()
      modelReady = false
      preparing = false
    }
    prepare()
  }

  private val fullScreen get() = options.mode == TranslationMode.FULLSCREEN
  private val scanInterval get() = if (fullScreen) maxOf(options.intervalMs, 500) else options.intervalMs

  fun needsFrame(now: Long) = !closed && modelReady && !ocrBusy && now - lastScanAt >= scanInterval

  fun process(image: Image) {
    val frame = if (fullScreen) SubtitleRegion(0.0, 0.0, 1.0, 1.0).crop(image)
      else options.region.crop(image, options.exclusions)
    lastScanAt = SystemClock.elapsedRealtime()
    ocrBusy = true
    val request = scanEpoch
    val input = InputImage.fromByteArray(frame.nv21, frame.width, frame.height, 0, InputImage.IMAGE_FORMAT_NV21)
    recognizer.process(input).addOnCompleteListener(callbackExecutor) { task ->
      ocrBusy = false
      if (closed || request != scanEpoch) return@addOnCompleteListener
      if (!task.isSuccessful) {
        update { it.copy(ocr = "OCR kareyi okuyamadı") }
        return@addOnCompleteListener
      }
      val now = SystemClock.elapsedRealtime()
      if (fullScreen) {
        videoWidth = frame.width
        videoHeight = frame.height
        val detected = screenText(task.result, frame.width, frame.height)
        overlay.observe(detected, now, screenTtl)
        update { it.copy(ocr = "Tam ekran · ${detected.size} blok", english = detected.joinToString(" · ") { block -> block.text }.take(420)) }
      } else {
        val text = subtitleText(task.result, frame.width, frame.height, now)
        val changed = tracker.observe(text, now)
        update { it.copy(ocr = if (text.isEmpty()) "Altyazı aranıyor" else "Altyazı okunuyor",
          english = if (changed) tracker.text else it.english) }
      }
      reconcile()
    }
  }

  private val minConfidence get() = (0.68 - 0.46 * options.sensitivity).toFloat()
  private val screenTtl get() = maxOf(options.ttlMs, scanInterval * 2 + 150)

  private fun screenText(result: Text, width: Int, height: Int): List<DetectedText> =
    result.textBlocks.mapNotNull { block ->
      val bounds = block.boundingBox ?: return@mapNotNull null
      val text = cleanOcr(block.text).take(420)
      if (text.count(Char::isLetter) < 2 || bounds.height() < 8 || block.lines.none {
          it.confidence <= 0 || it.confidence >= minConfidence
        }) return@mapNotNull null
      val x = bounds.left.coerceIn(0, width) / width.toDouble()
      val y = bounds.top.coerceIn(0, height) / height.toDouble()
      val w = bounds.right.coerceIn(0, width) / width.toDouble() - x
      val h = bounds.bottom.coerceIn(0, height) / height.toDouble() - y
      if (w <= 0 || h <= 0) null else DetectedText(text, FrameRect(x, y, w, h))
    }.sortedByDescending { it.rect.w * it.rect.h }.take(24)

  private fun subtitleText(result: Text, width: Int, height: Int, now: Long): String {
    val lines = result.textBlocks.flatMap { it.lines }.filter { line ->
      val bounds = line.boundingBox ?: return@filter false
      val text = cleanOcr(line.text)
      if (text.length !in 2..160 || text.count(Char::isLetter) < 2 ||
        (line.confidence > 0 && line.confidence < minConfidence) ||
        bounds.height() < maxOf(7, height / 30)) return@filter false
      val x = bounds.exactCenterX() / width
      if ((x < .17 && bounds.right < width * .24) || (x > .83 && bounds.left > width * .76)) return@filter false
      !hud.isStatic(text, x, bounds.exactCenterY() / height, now)
    }
    val anchor = lines.minByOrNull { line ->
      val bounds = line.boundingBox!!
      kotlin.math.abs(bounds.centerX() - width / 2) * 3 - minOf(bounds.width(), width / 2)
    } ?: return ""
    val anchorBounds = anchor.boundingBox!!
    return cleanOcr(lines.filter { line ->
      val b = line.boundingBox!!
      kotlin.math.abs(b.centerX() - anchorBounds.centerX()) <= width / 5 &&
        kotlin.math.abs(b.centerY() - anchorBounds.centerY()) <= maxOf(b.height(), anchorBounds.height()) * 2
    }.sortedBy { it.boundingBox!!.top }.take(3).joinToString("\n") { it.text }).take(420)
  }

  private fun reconcile() {
    handler.removeCallbacks(tick)
    if (closed || !modelReady) return
    val now = SystemClock.elapsedRealtime()
    if (fullScreen) {
      val active = overlay.alive(now, screenTtl)
      requestTranslations(active.filter(overlay::seenInLatestFrame).map { it.text }, now)
      val boxes = active.mapNotNull { item ->
        val text = cache[item.text] ?: return@mapNotNull null
        val wasVisible = previousOverlay.any { it.id == item.id && it.text == text }
        if (!wasVisible && !overlay.seenInLatestFrame(item)) return@mapNotNull null
        OverlayBox(item.id, text, item.rect, item.seenAt + screenTtl - now)
      }
      val changed = boxes.size != previousOverlay.size || boxes.zip(previousOverlay).any { (a, b) ->
        a.id != b.id || a.text != b.text || a.rect != b.rect
      }
      if (changed || (boxes.isNotEmpty() && now - overlayAt >= minOf(850, screenTtl / 2))) {
        val clients = ServerController.core.sendOverlay(OverlayMessage(videoWidth, videoHeight, boxes))
        if (clients > 0 && boxes.isNotEmpty()) update { state ->
          state.copy(turkish = boxes.joinToString(" · ") { it.text }.take(420))
        }
        previousOverlay = boxes
        overlayAt = now
      }
      active.minOfOrNull { it.seenAt + screenTtl }?.let { scheduleAt(it, now) }
      return
    }
    if (tracker.text.isNotEmpty() && now >= tracker.seenAt + options.ttlMs) tracker.clear()
    val source = tracker.text
    if (source.isNotEmpty()) requestTranslations(listOf(source), now)
    val translated = cache[source]
    var shown = caption
    val earliest = (shown?.shownAt ?: 0) + options.minimumMs
    if (translated != null && (shown == null || (shown.source != source && now >= earliest))) {
      shown = Caption(source, translated, now, maxOf(tracker.seenAt + options.ttlMs, now + options.minimumMs), 0)
      caption = shown
      publishCaption(shown, now)
    } else if (shown != null) {
      if (shown.source == source) shown.until = maxOf(tracker.seenAt + options.ttlMs, shown.shownAt + options.minimumMs)
      if (now >= shown.until) {
        ServerController.core.clearIfCurrent(shown.text)
        caption = null
        shown = null
      } else if (shown.source == source && now - shown.sentAt >= minOf(850, options.ttlMs / 2)) {
        publishCaption(shown, now)
      }
    }
    val next = listOfNotNull(shown?.until, (tracker.seenAt + options.ttlMs).takeIf { source.isNotEmpty() },
      earliest.takeIf { shown != null && translated != null && shown.source != source && it > now }).minOrNull()
    if (next != null) scheduleAt(next, now)
  }

  private fun publishCaption(shown: Caption, now: Long) {
    val server = ServerController.core
    val remaining = (shown.until - now).coerceAtLeast(1)
    if (!server.refresh(shown.text, remaining)) {
      val result = server.send(shown.text, force = true, ttlMs = remaining)
      if (result.status == SendStatus.SENT) update { it.copy(turkish = shown.text) }
    }
    shown.sentAt = now
  }

  private fun requestTranslations(sources: List<String>, now: Long) {
    failed.entries.removeAll { now >= it.value }
    val room = (if (fullScreen) 2 else 1) - inFlight.size
    if (room <= 0) return
    for (source in sources.distinct().filter { it !in cache && it !in inFlight && it !in failed }.take(room)) {
      inFlight.add(source)
      val epoch = translationEpoch
      update { it.copy(translation = "Çevriliyor") }
      provider.translate(TranslationRequest(source, glossary)).addOnCompleteListener(callbackExecutor) { task ->
        if (closed || epoch != translationEpoch) return@addOnCompleteListener
        inFlight.remove(source)
        val text = if (task.isSuccessful) task.result.trim().take(1200) else ""
        if (text.isBlank()) failed[source] = SystemClock.elapsedRealtime() + 3000
        else cache[source] = text
        update { it.copy(translation = if (inFlight.isNotEmpty()) "Çevriliyor" else if (text.isNotEmpty()) "ML Kit hazır" else "Çeviri tamamlanamadı") }
        // Publication reads only the currently tracked text and coordinates.
        reconcile()
      }
    }
  }

  private fun scheduleAt(time: Long, now: Long) { handler.postDelayed(tick, maxOf(1, time - now)) }

  fun resetStream() {
    scanEpoch++
    lastScanAt = 0L
    handler.removeCallbacks(tick)
    tracker = SubtitleTracker(options.intervalMs, options.confirmations)
    overlay.clear()
    hud.clear()
    caption?.let { ServerController.core.clearIfCurrent(it.text) }
    caption = null
    ServerController.core.sendOverlay(OverlayMessage(videoWidth, videoHeight, emptyList()))
    previousOverlay = emptyList()
  }

  fun close() {
    closed = true
    resetStream()
    recognizer.close()
    provider.close()
  }
}

private class HudFilter {
  private data class Seen(val x: Float, val y: Float, val start: Long, val last: Long)
  private val samples = LinkedHashMap<String, Seen>()
  fun isStatic(text: String, x: Float, y: Float, now: Long): Boolean {
    if (x in .29f.. .71f) return false
    samples.entries.removeAll { now - it.value.last > 4800 }
    val previous = samples[text]
    val stable = previous != null && kotlin.math.abs(previous.x - x) < .025 && kotlin.math.abs(previous.y - y) < .045
    val start = if (stable) previous!!.start else now
    samples[text] = Seen(x, y, start, now)
    if (samples.size > 64) samples.remove(samples.keys.first())
    return now - start >= 5800
  }
  fun clear() = samples.clear()
}
