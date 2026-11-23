package expo.modules.ps5translator

import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal object TranslationMetrics {
  private class Samples {
    val recent = LongArray(160)
    var count = 0L
    fun add(ms: Long) {
      recent[(count % recent.size).toInt()] = ms
      count++
    }
    fun summary(): JSONObject {
      val ordered = recent.take(minOf(count, recent.size.toLong()).toInt()).sorted()
      val last = recent[((count - 1) % recent.size).toInt()]
      return JSONObject().put("samples", count).put("lastMs", last)
        .put("p50Ms", ordered[(ordered.size - 1) * 50 / 100])
        .put("p95Ms", ordered[(ordered.size - 1) * 95 / 100])
    }
  }
  private val series = LinkedHashMap<String, Samples>()

  @Synchronized
  fun record(stage: String, started: Long) {
    series.getOrPut(stage, ::Samples).add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
  }

  @Synchronized
  fun snapshot() = JSONObject().apply { series.forEach { (stage, samples) -> put(stage, samples.summary()) } }
}
