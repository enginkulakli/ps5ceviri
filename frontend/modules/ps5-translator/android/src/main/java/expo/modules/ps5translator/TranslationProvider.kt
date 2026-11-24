package expo.modules.ps5translator

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import expo.modules.ps5translator.core.GameGlossary
import expo.modules.ps5translator.core.TranslationAttempt

internal const val API_NOT_CONFIGURED = "API yapılandırılmadı"

internal enum class EngineId(val key: String) {
  OFFLINE("offline"), GOOGLE_CLOUD("google_cloud"), AI("ai");

  fun provider(context: Context, limits: CloudLimits): TranslationProvider = when (this) {
    OFFLINE -> MlKitProvider()
    GOOGLE_CLOUD -> GoogleCloudProvider(context, limits)
    AI -> AiProvider
  }
}

internal data class TranslationRequest(
  val text: String,
  val glossary: GameGlossary,
  val context: GameContext? = null,
  val attempt: TranslationAttempt? = null,
)

internal data class GameContext(val title: String, val recentDialogue: List<String>)
internal data class UsageLimits(val maxRequests: Int, val maxCostMicros: Long)

internal interface TranslationProvider : AutoCloseable {
  val available: Boolean
  fun prepare(): Task<Void>
  fun translate(request: TranslationRequest): Task<String>
  override fun close()
}

/** Future remote adapters must enforce these limits before sending a request. */
private abstract class UnconfiguredProvider : TranslationProvider {
  final override val available = false
  protected open val usageLimits: UsageLimits? = null
  final override fun prepare(): Task<Void> = Tasks.forException(IllegalStateException(API_NOT_CONFIGURED))
  final override fun translate(request: TranslationRequest): Task<String> =
    Tasks.forException(IllegalStateException(API_NOT_CONFIGURED))
  final override fun close() = Unit
}

private object AiProvider : UnconfiguredProvider()

private class MlKitProvider : TranslationProvider {
  override val available = true
  private val client = Translation.getClient(TranslatorOptions.Builder()
    .setSourceLanguage(TranslateLanguage.ENGLISH)
    .setTargetLanguage(TranslateLanguage.TURKISH)
    .build())

  override fun prepare(): Task<Void> = client.downloadModelIfNeeded(DownloadConditions.Builder().build())

  override fun translate(request: TranslationRequest): Task<String> {
    val parts = request.glossary.split(request.text)
    if (parts.size == 1 && !parts[0].protected) return client.translate(request.text)
    val tasks = parts.map { part ->
      if (part.protected || part.text.none(Char::isLetter)) Tasks.forResult(part.text)
      else client.translate(part.text.trim()).continueWith { result ->
        part.text.takeWhile(Char::isWhitespace) + result.result.trim() +
          part.text.takeLastWhile(Char::isWhitespace)
      }
    }
    return Tasks.whenAllSuccess<String>(tasks).continueWith { result -> result.result.joinToString("") }
  }

  override fun close() = client.close()
}

internal object LanguageModels {
  private val manager get() = RemoteModelManager.getInstance()
  private fun model(language: String): TranslateRemoteModel {
    require(language in listOf("en", "tr", "ja", "de", "fr", "es")) { "Dil paketi desteklenmiyor." }
    return TranslateRemoteModel.Builder(language).build()
  }
  fun downloaded() = manager.getDownloadedModels(TranslateRemoteModel::class.java)
  fun download(language: String) = manager.download(model(language), DownloadConditions.Builder().build())
  fun remove(language: String) = manager.deleteDownloadedModel(model(language))
}
