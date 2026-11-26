package expo.modules.ps5translator

import android.content.Context
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
import expo.modules.ps5translator.core.OverlayLuma
import expo.modules.ps5translator.core.OverlayTracker
import expo.modules.ps5translator.core.PhoneScreen
import expo.modules.ps5translator.core.pixelCrop
import expo.modules.ps5translator.core.SendStatus
import expo.modules.ps5translator.core.SubtitleTracker
import expo.modules.ps5translator.core.SubtitleCaption
import expo.modules.ps5translator.core.TranslationAttempt
import expo.modules.ps5translator.core.TranslationAttempts
import expo.modules.ps5translator.core.cleanOcr
import expo.modules.ps5translator.core.memoryText
import expo.modules.ps5translator.core.OcrLine
import expo.modules.ps5translator.core.SmartOcr
import expo.modules.ps5translator.core.TvOverlayLayout
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import expo.modules.ps5translator.core.OcrFrame

internal data class RemotePlayOptions(val host: String, val keepWifiAwake: Boolean, val translation: TranslationOptions)

/** OCR results, cache and publication are serialized on the Remote Play worker. */
internal class RemoteSubtitlePipeline(
  private val context: Context,
  private val handler: Handler,
  private var options: TranslationOptions,
  private val update: ((RemotePlayStatus) -> RemotePlayStatus) -> Unit,
) {
  private val callbackExecutor = Executor { handler.post(it) }
  private val ocrWorker = Executors.newSingleThreadExecutor { work -> Thread(work, "remote-play-ocr") }
  private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
  private val archive = TranslationArchive.get(context)
  private var archiveRevision = archive.revision.get()
  private var provider = options.engine.provider(context, options.cloudLimits)
  private var glossary = GameGlossary(options.glossary)
  private var tracker = SubtitleTracker(options.intervalMs, options.confirmations)
  private val overlay = OverlayTracker()
  private val phone = PhoneScreen()
  private val phoneLayout = TvOverlayLayout.PhoneLayout()
  private val hud = HudFilter()
  private val cache = object : LinkedHashMap<String, String>(128, .75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 180
  }
  private val inFlight = TranslationAttempts()
  private val failed = HashMap<String, Long>()
  private val detectedAt = HashMap<String, Long>()
  private var glossaryVersion = version()
  private var lastScene: OverlayLuma? = null
  private var lastLayoutInputs = emptyList<OverlayBox>()
  private var lastLayout = emptyList<OverlayBox>()
  private var tvOverlayExpires = 0L
  @Volatile private var closed = false
  private var ocrBusy = false
  private var modelReady = false
  private var preparing = false
  @Volatile private var scanEpoch = 0L
  private var translationEpoch = 0L
  private var lastScanAt = 0L
  private var videoWidth = 1280
  private var videoHeight = 720
  private var caption: SubtitleCaption? = null
  private var previousOverlay = emptyList<OverlayBox>()
  private val tick = Runnable { reconcile() }

  private data class SubtitleRead(val text: String, val reliable: Boolean)

  private fun version(): String {
    val bytes = java.security.MessageDigest.getInstance("SHA-256").digest(
      options.glossary.joinToString("\u0000") { it.source + "\u0001" + it.target }.toByteArray(Charsets.UTF_8),
    )
    return "v3-" + bytes.joinToString("") { "%02x".format(it) }.take(24)
  }

  fun prepare() {
    if (closed || modelReady || preparing) return
    if (!provider.available) {
      update { it.copy(ocr = "Bekliyor", translation = API_NOT_CONFIGURED) }
      return
    }
    preparing = true
    val active = provider
    update { it.copy(ocr = "Görüntü bekleniyor", translation = if (options.engine == EngineId.OFFLINE) "ML Kit modeli hazırlanıyor" else "Google Cloud hazır") }
    active.prepare().addOnCompleteListener(callbackExecutor) { task ->
      if (closed || provider !== active) return@addOnCompleteListener
      preparing = false
      modelReady = task.isSuccessful
      update { it.copy(translation = if (modelReady) engineReady() else task.exception?.message?.take(160) ?: "Dil paketi hazırlanamadı.") }
      if (modelReady) { failed.clear(); reconcile() }
    }
  }

  private fun engineReady() = if (options.engine == EngineId.OFFLINE) "ML Kit hazır" else "Google Cloud hazır"

  fun refreshCloud() {
    if (options.engine == EngineId.GOOGLE_CLOUD) configure(options, changedKey = true)
  }

  fun configure(next: TranslationOptions, changedKey: Boolean = false) {
    if (next == options && !changedKey) return
    // A subtitle-only edit cannot invalidate a running spatial mode.
    if (!changedKey && options.mode != TranslationMode.SUBTITLE && next.mode == options.mode &&
      next.copy(region = options.region, exclusions = options.exclusions) == options) {
      options = next
      return
    }
    val engineChanged = changedKey || next.engine != options.engine || next.cloudLimits != options.cloudLimits
    if (engineChanged || next.glossary != options.glossary || next.archive != options.archive) {
      translationEpoch++
      cache.clear()
      inFlight.clear()
      failed.clear()
      glossary = GameGlossary(next.glossary)
    }
    options = next
    glossaryVersion = version()
    resetStream()
    update { it.copy(english = "", turkish = "", ocr = "Görüntü bekleniyor") }
    if (engineChanged) {
      provider.close()
      provider = next.engine.provider(context, next.cloudLimits)
      modelReady = false
      preparing = false
    }
    prepare()
  }

  private val fullScreen get() = options.mode == TranslationMode.FULLSCREEN
  private val phoneMode get() = options.mode == TranslationMode.PHONE
  private val scanInterval get() = if (fullScreen) maxOf(options.intervalMs, 500) else options.intervalMs

  fun needsFrame(now: Long): Boolean {
    val interval = when (options.mode) {
      TranslationMode.PHONE -> PhoneScreen.PROBE_INTERVAL_MS
      TranslationMode.FULLSCREEN -> scanInterval
      TranslationMode.SUBTITLE -> tracker.nextScanInterval(now)
    }
    return !closed && !ocrBusy && now - lastScanAt >= interval
  }

  fun process(image: Image) {
    val frameStarted = System.nanoTime()
    if (phoneMode) { processPhone(image, frameStarted); return }
    val screen = fullScreen
    val frame = if (screen) SubtitleRegion(0.0, 0.0, 1.0, 1.0).crop(image)
      else options.region.crop(image, options.exclusions)
    lastScanAt = SystemClock.elapsedRealtime()
    ocrBusy = true
    val request = scanEpoch
    ocrWorker.execute {
      if (closed || request != scanEpoch) {
        handler.post { ocrBusy = false }
        return@execute
      }
      try {
        val recognition = if (screen) frame.enlargeForText() else frame
        val input = InputImage.fromByteArray(recognition.nv21, recognition.width, recognition.height, 0, InputImage.IMAGE_FORMAT_NV21)
        val ocrStarted = System.nanoTime()
        TranslationMetrics.record("framePreparation", frameStarted)
        recognizer.process(input).addOnCompleteListener({ work -> work.run() }) { task ->
          val completedAt = System.nanoTime()
          handler.post {
            ocrBusy = false
            if (closed || request != scanEpoch) return@post
            if (task.isSuccessful) {
              TranslationMetrics.recordDuration("ocr", completedAt - ocrStarted)
              TranslationMetrics.record("ocrDelivery", completedAt)
              onRecognition(task.result, frame, recognition, frameStarted)
            } else update { it.copy(ocr = "OCR kareyi okuyamadı") }
          }
        }
      } catch (_: Exception) {
        handler.post {
          ocrBusy = false
          if (!closed && request == scanEpoch) update { it.copy(ocr = "OCR kareyi işleyemedi") }
        }
      }
    }
  }

  private fun processPhone(image: Image, frameStarted: Long) {
    val now = SystemClock.elapsedRealtime()
    lastScanAt = now
    val signature = image.phoneSignature()
    val scan = phone.inspect(signature, now)
    TranslationMetrics.record("phoneProbe", frameStarted)
    if (scan == null) return
    val regions = if (scan.full) listOf(FrameRect(0.0, 0.0, 1.0, 1.0)) else scan.regions
    val bounds = image.cropRect
    val captures = regions.map { region ->
      val crop = region.pixelCrop(bounds.width(), bounds.height())
      val frame = SubtitleRegion(region.x, region.y, region.w, region.h).crop(image)
      FrameRect(crop.left.toDouble() / bounds.width(), crop.top.toDouble() / bounds.height(),
        frame.width.toDouble() / bounds.width(), frame.height.toDouble() / bounds.height()) to frame
    }
    val request = scanEpoch
    val results = mutableListOf<DetectedText>()
    ocrBusy = true
    fun finish(success: Boolean) {
      ocrBusy = false
      if (closed || request != scanEpoch) return
      if (!success) {
        update { it.copy(ocr = "Telefon modu metni okuyamadı") }
        return
      }
      videoWidth = signature.width
      videoHeight = signature.height
      phone.observe(scan, results, SystemClock.elapsedRealtime())
      val ready = phone.visible()
      rememberDetections(ready.map { it.source }, frameStarted)
      update { it.copy(ocr = "Telefon modu · ${ready.size} blok", english = ready.joinToString(" · ") { msg -> msg.source }.take(420)) }
      reconcile()
    }
    fun recognize(index: Int) {
      if (index == captures.size) { finish(true); return }
      val (region, frame) = captures[index]
      ocrWorker.execute {
        if (closed || request != scanEpoch) {
          handler.post { finish(false) }
          return@execute
        }
        val started = System.nanoTime()
        try {
          val input = frame.enlargeForText()
          recognizer.process(InputImage.fromByteArray(input.nv21, input.width, input.height, 0, InputImage.IMAGE_FORMAT_NV21))
            .addOnCompleteListener(callbackExecutor) { task ->
              if (closed || request != scanEpoch || !task.isSuccessful) { finish(false); return@addOnCompleteListener }
              TranslationMetrics.record(if (scan.full) "phoneFullOcr" else "phoneRegionOcr", started)
              results += screenText(task.result, input.width, input.height, null, region)
              recognize(index + 1)
            }
        } catch (_: Exception) {
          handler.post { finish(false) }
        }
      }
    }
    recognize(0)
  }

  private fun onRecognition(result: Text, frame: OcrFrame, recognition: OcrFrame, frameStarted: Long) {
    val comparison = System.nanoTime()
    val now = SystemClock.elapsedRealtime()
    if (fullScreen) {
      videoWidth = frame.width
      videoHeight = frame.height
      val diagnostic = ScreenOcrDiagnostics.begin(frame.width, frame.height, recognition.width, recognition.height)
      val detected = screenText(result, recognition.width, recognition.height, diagnostic)
      val luminance = OverlayLuma(frame.nv21, frame.width, frame.height)
      val previous = lastScene
      overlay.observe(detected, now, screenTtl, previous != null && luminance.sceneChanged(previous)) { rect ->
        previous != null && luminance.changedAt(previous, rect)
      }
      lastScene = luminance
      diagnostic?.let {
        val grouped = JSONArray()
        for ((index, block) in detected.withIndex()) {
          grouped.put(ScreenOcrDiagnostics.text(block.text, block.rect)
            .put("state", if (index >= 24) "track_limit" else "candidate"))
        }
        val tracked = JSONArray()
        overlay.alive(now, screenTtl).forEach { item ->
          tracked.put(ScreenOcrDiagnostics.text(item.text, item.rect).put("id", item.id)
            .put("seenInLatestFrame", overlay.seenInLatestFrame(item))
            .put("translationReady", item.normalized in cache).put("translationPending", item.normalized in inFlight))
        }
        it.put("grouped", grouped).put("tracked", tracked)
        ScreenOcrDiagnostics.record(it)
      }
      rememberDetections(detected.map { it.text }, frameStarted)
      update { it.copy(ocr = "Tam ekran · ${detected.size} blok", english = detected.joinToString(" · ") { block -> block.text }.take(420)) }
    } else {
      val reading = subtitleText(result, frame.width, frame.height, now)
      val changed = tracker.observe(reading?.text.orEmpty(), now, reliable = reading?.reliable == true)
      if (changed) TranslationMetrics.recordDuration("stability", TimeUnit.MILLISECONDS.toNanos(tracker.confirmationMs))
      rememberDetections(listOf(tracker.text, tracker.translationSource(now, options.ttlMs)).filter(String::isNotEmpty), frameStarted)
      update { it.copy(ocr = if (reading == null) "Altyazı aranıyor" else "Altyazı okunuyor",
        english = if (changed) tracker.text else it.english) }
    }
    TranslationMetrics.record("comparison", comparison)
    reconcile()
  }

  private val minConfidence get() = (0.68 - 0.46 * options.sensitivity).toFloat()
  private val screenTtl get() = maxOf(options.ttlMs, scanInterval * 2 + 150)

  private fun screenText(result: Text, width: Int, height: Int, diagnostic: JSONObject?,
                         region: FrameRect? = null): List<DetectedText> {
    val raw = if (diagnostic != null) JSONArray() else null
    val rejected = if (diagnostic != null) JSONArray() else null
    fun normalized(bounds: android.graphics.Rect?): FrameRect? {
      if (bounds == null) return null
      val x = bounds.left.coerceIn(0, width) / width.toDouble()
      val y = bounds.top.coerceIn(0, height) / height.toDouble()
      val w = bounds.right.coerceIn(0, width) / width.toDouble() - x
      val h = bounds.bottom.coerceIn(0, height) / height.toDouble() - y
      if (w <= 0 || h <= 0) return null
      return if (region == null) FrameRect(x, y, w, h)
      else FrameRect(region.x + x * region.w, region.y + y * region.h, w * region.w, h * region.h)
    }
    val candidates = result.textBlocks.flatMapIndexed { group, block ->
      val lines = if (diagnostic != null) JSONArray() else null
      raw?.put(ScreenOcrDiagnostics.text(block.text, normalized(block.boundingBox)).put("lines", lines))
      block.lines.mapNotNull { line ->
        val bounds = line.boundingBox
        val rect = normalized(bounds)
        val confidence = line.confidence
        val reason = when {
          bounds == null || rect == null -> "invalid_bounds"
          bounds.height() < 4 -> "below_minimum_height"
          // Low-confidence short fragments are often icons. Keep plausible words
          // so dark or tiny chat text returned by ML Kit can still reach the TV.
          confidence > 0 && confidence < minConfidence &&
            (confidence < .15f || line.text.count(Char::isLetter) < 3) -> "low_confidence_fragment"
          else -> null
        }
        lines?.put(ScreenOcrDiagnostics.text(line.text, rect)
          .put("confidence", if (confidence.isFinite()) confidence.toDouble() else JSONObject.NULL)
          .put("state", reason ?: "candidate"))
        if (reason != null || rect == null) null else OcrLine(line.text, rect, group)
      }
    }
    val grouped = SmartOcr.group(candidates, rejected?.let { rows ->
      { line, reason -> rows.put(ScreenOcrDiagnostics.text(line.text, line.rect).put("state", reason)); Unit }
    })
    diagnostic?.put("raw", raw)?.put("rejected", rejected)
    return grouped
  }

  private fun rememberDetections(sources: List<String>, start: Long) {
    val keys = sources.mapTo(HashSet(), ::memoryText)
    detectedAt.keys.retainAll(keys)
    for (key in keys) detectedAt.putIfAbsent(key, start)
  }

  private fun subtitleText(result: Text, width: Int, height: Int, now: Long): SubtitleRead? {
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
    } ?: return null
    val anchorBounds = anchor.boundingBox!!
    val included = lines.filter { line ->
      val b = line.boundingBox!!
      kotlin.math.abs(b.centerX() - anchorBounds.centerX()) <= width / 5 &&
        kotlin.math.abs(b.centerY() - anchorBounds.centerY()) <= maxOf(b.height(), anchorBounds.height()) * 2
    }.sortedBy { it.boundingBox!!.top }.take(3)
    val source = cleanOcr(included.joinToString("\n") { it.text }).take(420)
    val reliable = anchorBounds.exactCenterX() / width in .28f.. .72f &&
      included.all { it.confidence >= .80f && it.boundingBox!!.height() >= maxOf(8, height / 24) }
    return SubtitleRead(source, reliable)
  }

  private fun reconcile() {
    handler.removeCallbacks(tick)
    if (closed) return
    val revision = archive.revision.get()
    if (archiveRevision != revision) {
      archiveRevision = revision
      translationEpoch++
      cache.clear()
      if (phoneMode) phone.clearTranslations()
      failed.clear()
      inFlight.clear()
    }
    val now = SystemClock.elapsedRealtime()
    if (phoneMode) { reconcilePhone(now); return }
    if (fullScreen) {
      val active = overlay.alive(now, screenTtl)
      requestTranslations(active.filter(overlay::seenInLatestFrame).map { it.text to it.normalized }, now)
      val live = active.mapNotNull { item ->
        val text = cache[item.normalized] ?: return@mapNotNull null
        val wasVisible = previousOverlay.any { it.id == item.id && it.text == text }
        if (!wasVisible && !overlay.seenInLatestFrame(item)) return@mapNotNull null
        OverlayBox(item.id, text, item.rect, item.seenAt + screenTtl - now)
      }
      val inputs = live.map { it.copy(ttlMs = 0) }.sortedBy { it.id }
      if (inputs != lastLayoutInputs) {
        lastLayoutInputs = inputs
        lastLayout = TvOverlayLayout.arrange(inputs, retainUnplaced = true)
      }
      val boxes = lastLayout.map { placed -> placed.copy(ttlMs = live.first { it.id == placed.id }.ttlMs) }
      val changed = boxes.map { it.copy(ttlMs = 0) } != previousOverlay.map { it.copy(ttlMs = 0) }
      if (changed || (boxes.isNotEmpty() && now >= tvOverlayExpires - maxOf(300, scanInterval + 100))) {
        val sentAt = System.nanoTime()
        val clients = ServerController.core.sendOverlay(OverlayMessage(videoWidth, videoHeight, boxes))
        if (ScreenOcrDiagnostics.enabled) ScreenOcrDiagnostics.published(boxes, clients)
        TranslationMetrics.record("websocket", sentAt)
        if (clients > 0 && boxes.isNotEmpty()) {
          val fresh = boxes.filter { box -> previousOverlay.none { it.id == box.id && it.text == box.text } }.map { it.id }.toSet()
          active.filter { it.id in fresh }.forEach {
            detectedAt[it.normalized]?.let { start -> TranslationMetrics.record("ocrToWebSocket", start) }
          }
          update { state -> state.copy(turkish = boxes.joinToString(" · ") { it.text }.take(420)) }
        }
        previousOverlay = boxes
        tvOverlayExpires = now + (boxes.minOfOrNull { it.ttlMs } ?: 0)
      }
      active.minOfOrNull { it.seenAt + screenTtl }?.let { scheduleAt(it, now) }
      return
    }
    tracker.expire(now, options.ttlMs)
    val source = tracker.text
    val requested = tracker.translationSource(now, options.ttlMs)
    if (requested.isNotEmpty()) requestTranslations(listOf(requested to requested), now)
    val translated = if (tracker.readyForPublication(now, options.ttlMs)) cache[source] else null
    val previous = caption
    val shown = SubtitleCaption.select(previous, source, translated, tracker.seenAt,
      now, options.ttlMs, options.minimumMs)
    caption = shown
    if (shown == null) {
      previous?.let { ServerController.core.clearIfCurrent(it.text) }
    } else if (shown !== previous || (shown.source == source &&
        now - shown.sentAt >= minOf(850, options.ttlMs / 2))) {
      publishCaption(shown, now)
    }
    listOfNotNull(shown?.until, (tracker.seenAt + options.ttlMs).takeIf { source.isNotEmpty() })
      .minOrNull()?.let { scheduleAt(it, now) }
  }

  private fun reconcilePhone(now: Long) {
    phone.applyTranslations(cache::get)
    val visible = phone.visible()
    requestTranslations(visible.filter { it.translated == null }.map { it.source to it.key }, now)
    val inputs = visible.mapNotNull { item -> item.translated?.let { OverlayBox(item.id, it, item.rect, 0) } }
    val changed = inputs != lastLayoutInputs
    if (changed) {
      lastLayoutInputs = inputs
      lastLayout = phoneLayout.arrange(inputs)
    }
    if (changed || (lastLayout.isNotEmpty() && now >= tvOverlayExpires)) {
      val started = System.nanoTime()
      val boxes = lastLayout.map { it.copy(ttlMs = PhoneScreen.LEASE_MS) }
      val clients = ServerController.core.sendOverlay(OverlayMessage(videoWidth, videoHeight, boxes))
      TranslationMetrics.record("websocket", started)
      if (changed) {
        if (clients > 0) visible.filter { item ->
          item.translated != null && previousOverlay.none { it.id == item.id && it.text == item.translated }
        }.forEach { item -> detectedAt[item.key]?.let { TranslationMetrics.record("phoneToWebSocket", it) } }
        update { state -> state.copy(turkish = boxes.joinToString(" · ") { it.text }.take(420)) }
        previousOverlay = boxes
      }
      tvOverlayExpires = now + PhoneScreen.RENEW_AFTER_MS
    }
    if (lastLayout.isNotEmpty()) scheduleAt(tvOverlayExpires, now)
  }

  private fun publishCaption(shown: SubtitleCaption, now: Long) {
    val server = ServerController.core
    val remaining = (shown.until - now).coerceAtLeast(1)
    val started = System.nanoTime()
    if (!server.refresh(shown.text, remaining)) {
      val result = server.send(shown.text, force = true, ttlMs = remaining)
      if (result.status == SendStatus.SENT) {
        detectedAt[shown.source]?.let {
          TranslationMetrics.record("ocrToWebSocket", it)
          TranslationMetrics.record(if (options.engine == EngineId.GOOGLE_CLOUD) "cloudSubtitleTotal" else "localSubtitleTotal", it)
        }
        update { it.copy(turkish = shown.text) }
      }
    }
    TranslationMetrics.record("websocket", started)
    shown.sentAt = now
  }

  private fun requestTranslations(sources: List<Pair<String, String>>, now: Long) {
    failed.entries.removeAll { now >= it.value }
    if (sources.isEmpty()) return
    val unique = sources.distinctBy { it.second }
    val started = System.nanoTime()
    val misses = unique.filter { (_, normalized) -> normalized !in cache }
    TranslationMetrics.record("ram", started)
    val pending = misses.filter { (_, normalized) -> normalized !in inFlight && normalized !in failed }
    if (pending.size > inFlight.room) {
      inFlight.releaseObsolete(sources.mapTo(HashSet()) { it.second }, minOf(2, pending.size))
    }
    for ((source, normalized) in pending.take(inFlight.room)) {
      val key = ArchiveKey(options.archive.gameId, source, normalized, options.engine.key, glossaryVersion)
      val policy = options.archive
      val epoch = translationEpoch
      val attempt = inFlight.start(normalized)
      var lookupSettled = false
      fun continueWith(archived: String?) {
        if (lookupSettled || closed || epoch != translationEpoch || !inFlight.current(normalized, attempt)) return
        lookupSettled = true
        if (archived != null) {
          inFlight.finish(normalized, attempt)
          cache[normalized] = archived
          reconcile()
          return
        }
        val stillVisible = when (options.mode) {
          TranslationMode.PHONE -> phone.visible().any { it.key == normalized && it.translated == null }
          TranslationMode.FULLSCREEN -> overlay.alive(SystemClock.elapsedRealtime(), screenTtl)
            .any { overlay.seenInLatestFrame(it) && it.normalized == normalized }
          TranslationMode.SUBTITLE -> tracker.translationSource(SystemClock.elapsedRealtime(), options.ttlMs) == normalized
        }
        if (!modelReady || !stillVisible) {
          inFlight.finish(normalized, attempt)
          if (!modelReady) failed[normalized] = SystemClock.elapsedRealtime() + 1000
          return
        }
        translateLive(key, epoch, attempt)
      }
      val deadline = Runnable { continueWith(null) }
      // Bypass a slow disk lookup only for the free local engine. A cloud fallback
      // requires a definite archive miss so an existing entry cannot incur a request.
      if (options.engine == EngineId.OFFLINE) handler.postDelayed(deadline, 28)
      archive.lookups.execute {
        if (attempt.cancelled) return@execute
        val lookupStarted = System.nanoTime()
        val result = runCatching { archive.lookup(key, policy) }
        TranslationMetrics.record("sqlite", lookupStarted)
        handler.post {
          handler.removeCallbacks(deadline)
          if (result.isFailure && key.engine == "google_cloud") {
            if (closed || epoch != translationEpoch || lookupSettled || !inFlight.current(normalized, attempt)) return@post
            lookupSettled = true
            inFlight.finish(normalized, attempt)
            failed[normalized] = SystemClock.elapsedRealtime() + 3000
            update { it.copy(translation = "Yerel arşiv okunamadı. Google Cloud isteği gönderilmedi.") }
          } else continueWith(result.getOrNull())
        }
      }
    }
  }

  private fun translateLive(key: ArchiveKey, epoch: Long, attempt: TranslationAttempt) {
    val started = System.nanoTime()
    val isCloud = options.engine == EngineId.GOOGLE_CLOUD
    attempt.canRelease = isCloud
    val revision = archiveRevision
    update { it.copy(translation = "Çevriliyor") }
    provider.translate(TranslationRequest(key.source, glossary, attempt = attempt)).addOnCompleteListener(callbackExecutor) { task ->
      TranslationMetrics.record(if (isCloud) "googlePipeline" else "mlkit", started)
      if (closed || epoch != translationEpoch || !inFlight.current(key.normalized, attempt)) return@addOnCompleteListener
      inFlight.finish(key.normalized, attempt)
      val text = if (task.isSuccessful) task.result.trim().take(1200) else ""
      if (text.isBlank()) failed[key.normalized] = SystemClock.elapsedRealtime() + if (isCloud) 30000 else 3000
      else cache[key.normalized] = text
      update { it.copy(translation = if (inFlight.busy) "Çevriliyor" else if (text.isNotEmpty()) engineReady()
        else if (options.engine == EngineId.GOOGLE_CLOUD) task.exception?.message?.take(180) ?: "Google Cloud çevirisi tamamlanamadı."
        else "Çeviri tamamlanamadı") }
      reconcile()
      if (text.isNotEmpty()) archive.writes.execute {
        if (archive.revision.get() == revision) runCatching { archive.store(key, text) }
      }
    }
  }

  private fun scheduleAt(time: Long, now: Long) { handler.postDelayed(tick, maxOf(1, time - now)) }

  fun resetStream() {
    scanEpoch++
    lastScanAt = 0L
    handler.removeCallbacks(tick)
    inFlight.clear()
    tracker = SubtitleTracker(options.intervalMs, options.confirmations)
    overlay.clear()
    phone.clear()
    phoneLayout.clear()
    hud.clear()
    caption?.let { ServerController.core.clearIfCurrent(it.text) }
    caption = null
    ServerController.core.sendOverlay(OverlayMessage(videoWidth, videoHeight, emptyList()))
    previousOverlay = emptyList()
    lastLayoutInputs = emptyList()
    lastLayout = emptyList()
    lastScene = null
    detectedAt.clear()
    tvOverlayExpires = 0
  }

  fun close() {
    closed = true
    resetStream()
    ocrWorker.execute { recognizer.close() }
    ocrWorker.shutdown()
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
