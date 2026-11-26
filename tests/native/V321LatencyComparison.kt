import expo.modules.ps5translator.core.SubtitleTracker

/**
 * Replays the same two low-confidence OCR observations through both scan schedules.
 * The provider responses and WebSocket enqueue are synthetic. No device or API is used.
 */
fun main() {
  val firstCaptured = 1000L
  val recognitionMs = 38L
  val firstRead = firstCaptured + recognitionMs
  fun sample(translationMs: Long, v32: Boolean): Pair<Long, Long> {
    val tracker = SubtitleTracker(300, 2)
    val sentence = "I'll check the warehouse after work."
    check(!tracker.observe(sentence, firstRead))
    check(tracker.translationSource(firstRead, 1000) == sentence)
    // V3.2 sampled recently read text at min(180 ms, configured interval).
    val followUp = if (v32) 180L else tracker.nextScanInterval(firstRead)
    val secondRead = firstCaptured + maxOf(followUp, recognitionMs) + recognitionMs
    check(tracker.observe(sentence, secondRead))
    val completion = maxOf(secondRead, firstRead + translationMs) + 2
    return tracker.confirmationMs to (completion - firstCaptured)
  }
  for ((name, translationTime) in listOf("Local fixture" to 22L, "Cloud fixture" to 115L, "Slow Cloud fixture" to 260L)) {
    val before = sample(translationTime, true)
    val after = sample(translationTime, false)
    check(before.first == 180L && after.first == 50L && after.second <= before.second)
    if (translationTime < 180) check(after.second < before.second)
    println("$name (simulation): stability ${before.first} → ${after.first} ms, frame-to-queue ${before.second} → ${after.second} ms")
  }
}
