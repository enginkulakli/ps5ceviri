package expo.modules.ps5translator

import expo.modules.ps5translator.core.FrameRect
import expo.modules.ps5translator.core.GlossaryEntry
import org.json.JSONObject

internal enum class TranslationMode { SUBTITLE, FULLSCREEN }

internal data class TranslationOptions(
  val mode: TranslationMode,
  val engine: EngineId,
  val region: SubtitleRegion,
  val exclusions: List<FrameRect>,
  val intervalMs: Long,
  val ttlMs: Long,
  val minimumMs: Long,
  val sensitivity: Double,
  val confirmations: Int,
  val glossary: List<GlossaryEntry>,
  val archive: ArchivePolicy,
  val cloudLimits: CloudLimits,
) {
  companion object {
    fun parse(json: String): TranslationOptions {
      require(json.length <= 32000) { "Çeviri ayarları çok uzun." }
      val doc = JSONObject(json)
      val area = doc.optJSONObject("region")?.rect() ?: FrameRect(0.0, .72, 1.0, .28)
      val exclusions = doc.optJSONArray("exclusions")
      val glossary = doc.optJSONArray("glossary")
      return TranslationOptions(
        if (doc.optString("mode") == "fullscreen") TranslationMode.FULLSCREEN else TranslationMode.SUBTITLE,
        EngineId.entries.find { it.key == doc.optString("engine") } ?: EngineId.OFFLINE,
        SubtitleRegion(area.x, area.y, area.w, area.h),
        (0 until minOf(exclusions?.length() ?: 0, 8)).mapNotNull { exclusions?.optJSONObject(it)?.rect() },
        doc.optLong("intervalMs", 300).coerceIn(150, 2000),
        doc.optLong("ttlMs", 2000).coerceIn(500, 10000),
        doc.optLong("minimumMs", 700).coerceIn(0, 3000),
        doc.optDouble("sensitivity", .5).takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: .5,
        doc.optInt("confirmations", 2).coerceIn(1, 4),
        (0 until minOf(glossary?.length() ?: 0, 80)).mapNotNull { index ->
          val term = glossary?.optJSONObject(index) ?: return@mapNotNull null
          val source = term.optString("source").trim().take(80)
          val target = term.optString("target").trim().take(120)
          if (source.isBlank() || target.isBlank()) null else GlossaryEntry(source, target)
        },
        ArchivePolicy(doc.optString("gameId", GENERAL_GAME).takeIf { it.matches(Regex("[a-zA-Z0-9_-]{1,80}")) } ?: GENERAL_GAME,
          doc.optBoolean("generalArchive", true), doc.optBoolean("shareEngines", false)),
        CloudLimits(doc.optInt("dailyCharacters", 10000).coerceIn(0, 10000000),
          doc.optInt("monthlyCharacters", 100000).coerceIn(0, 100000000)),
      )
    }

    private fun JSONObject.rect(): FrameRect? {
      val x = optDouble("x")
      val y = optDouble("y")
      val w = optDouble("w")
      val h = optDouble("h")
      if (x !in 0.0..0.95 || y !in 0.0..0.95 || w < .05 || h < .05 ||
        !w.isFinite() || !h.isFinite() || x + w > 1.00001 || y + h > 1.00001) return null
      return FrameRect(x, y, minOf(w, 1 - x), minOf(h, 1 - y))
    }
  }
}
