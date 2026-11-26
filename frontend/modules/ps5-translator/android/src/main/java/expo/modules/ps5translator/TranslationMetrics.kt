package expo.modules.ps5translator

import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal object TranslationMetrics {
  private class Samples {
    val recent = LongArray(160)
    var count = 0L
    fun add(micros: Long) {
      recent[(count % recent.size).toInt()] = micros
      count++
    }
    fun summary(): JSONObject {
      val ordered = recent.take(minOf(count, recent.size.toLong()).toInt()).sorted()
      val last = recent[((count - 1) % recent.size).toInt()]
      return JSONObject().put("samples", count).put("lastMs", last / 1000.0)
        .put("p50Ms", ordered[(ordered.size - 1) * 50 / 100] / 1000.0)
        .put("p95Ms", ordered[(ordered.size - 1) * 95 / 100] / 1000.0)
    }
  }
  private val series = LinkedHashMap<String, Samples>()

  fun record(stage: String, started: Long) = recordDuration(stage, System.nanoTime() - started)

  @Synchronized
  fun recordDuration(stage: String, nanos: Long) {
    series.getOrPut(stage, ::Samples).add(TimeUnit.NANOSECONDS.toMicros(nanos))
  }

  @Synchronized
  fun snapshot() = JSONObject().apply { series.forEach { (stage, samples) -> put(stage, samples.summary()) } }
}
