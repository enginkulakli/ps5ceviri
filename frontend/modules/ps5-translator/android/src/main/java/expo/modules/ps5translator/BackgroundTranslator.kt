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
        val english = blocks.mapNotNull { it.getString("text")?.trim() }
          .filter { it.length >= 3 && it.any(Char::isLetter) }
          .joinToString("\n")
          .take(600)
        if (english.isBlank()) {
          if (android.os.SystemClock.elapsedRealtime() - lastDetected > 4000) {
            lastEnglish = ""
            lastTurkish = ""
            Handler(Looper.getMainLooper()).post {
              runCatching { TranslationOverlay.show(context, "") }
            }
          }
          return@execute
        }
        lastDetected = android.os.SystemClock.elapsedRealtime()
        if (english == lastEnglish) return@execute
        lastEnglish = english
        val turkish = Tasks.await(translator!!.translate(english), 30, TimeUnit.SECONDS)
        if (!enabled) return@execute
        lastTurkish = turkish
        Handler(Looper.getMainLooper()).post {
          runCatching { TranslationOverlay.show(context, turkish) }
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
