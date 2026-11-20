package expo.modules.ps5translator

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Native OCR + offline EN→TR pipeline. Keeps processing when React Native
 * becomes paused after opening Chiaki. ML Kit downloads its model on first use.
 */
internal object BackgroundTranslator {
  private val executor = Executors.newSingleThreadExecutor()
  private val busy = AtomicBoolean(false)
  @Volatile var enabled = false
  @Volatile var status = "idle"
  @Volatile var lastEnglish = ""
  @Volatile var lastTurkish = ""
  @Volatile private var lastProcessed = 0L
  @Volatile private var lastDetected = 0L
  private var translator: Translator? = null
  private var ready = false
  private val cache = object : LinkedHashMap<String, String>(128, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 150
  }

  fun start(context: Context) {
    enabled = true
    status = if (ready) "running" else "downloading_model"
    executor.execute {
      try {
        if (translator == null) {
          translator = Translation.getClient(
            TranslatorOptions.Builder()
              .setSourceLanguage(TranslateLanguage.ENGLISH)
              .setTargetLanguage(TranslateLanguage.TURKISH)
              .build()
          )
        }
        Tasks.await(
          translator!!.downloadModelIfNeeded(DownloadConditions.Builder().build()),
          180, TimeUnit.SECONDS
        )
        ready = true
        status = "running"
      } catch (e: Exception) {
        status = "model_error: ${e.message}"
        Log.e("BackgroundTranslator", "Offline model unavailable", e)
      }
    }
  }

  fun stop(context: Context) {
    enabled = false
    status = "stopped"
    lastEnglish = ""
    lastTurkish = ""
    Handler(Looper.getMainLooper()).post { runCatching { TranslationOverlay.show(context, "") } }
  }

  fun process(context: Context, frame: String) {
    if (!enabled || !ready || !busy.compareAndSet(false, true)) return
    val now = android.os.SystemClock.elapsedRealtime()
    if (now - lastProcessed < 1600) {
      busy.set(false)
      return
    }
    lastProcessed = now
    executor.execute {
      try {
        val blocks = ScreenTextRecognizer.recognize(frame)
        val detected = blocks.mapNotNull { block ->
          val source = block.getString("text")?.trim()?.replace("\\n", " ") ?: ""
          if (source.length < 2 || !source.any(Char::isLetter)) null
          else source to block
        }.take(24)
        if (detected.isEmpty()) {
          if (android.os.SystemClock.elapsedRealtime() - lastDetected > 2500) {
            lastEnglish = ""
            lastTurkish = ""
            Handler(Looper.getMainLooper()).post {
              runCatching { TranslationOverlay.showBlocks(context, emptyList()) }
            }
          }
          return@execute
        }
        lastDetected = android.os.SystemClock.elapsedRealtime()
        val positioned = ArrayList<TranslationOverlay.Block>()
        for ((source, block) in detected) {
          if (!enabled) break
          val translated = cache[source] ?: Tasks.await(
            translator!!.translate(source), 12, TimeUnit.SECONDS
          ).also { cache[source] = it }
          if (translated.isBlank()) continue
          positioned.add(TranslationOverlay.Block(
            translated,
            block.getInt("left"), block.getInt("top"),
            block.getInt("right"), block.getInt("bottom"),
            block.getInt("frameWidth"), block.getInt("frameHeight")
          ))
        }
        if (!enabled) return@execute
        lastEnglish = detected.joinToString("\\n") { it.first }.take(600)
        lastTurkish = positioned.joinToString("\\n") { it.text }.take(600)
        Handler(Looper.getMainLooper()).post {
          runCatching { TranslationOverlay.showBlocks(context, positioned) }
            .onFailure { status = "overlay_error: ${it.message}" }
        }
      } catch (e: Exception) {
        status = "translation_error: ${e.message}"
        Log.e("BackgroundTranslator", "OCR/translation failed", e)
      } finally {
        busy.set(false)
      }
    }
  }
}
