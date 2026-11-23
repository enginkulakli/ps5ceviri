package expo.modules.ps5translator.core

import java.util.Locale

internal class SubtitleTracker(private val intervalMs: Long, private val confirmations: Int = 2) {
  private var count = 0
  private var candidate = ""
  private var candidateAt = 0L
  var text = ""
    private set
  var seenAt = 0L
    private set

  fun observe(raw: String, now: Long): Boolean {
    if (raw.isBlank()) return false
    if (similarOcr(raw, text)) {
      seenAt = now
      candidate = ""
      count = 0
      return false
    }
    count = if (similarOcr(raw, candidate) && now - candidateAt <= maxOf(1400, intervalMs * 2 + 200)) count + 1 else 1
    candidate = raw
    candidateAt = now
    if (count < confirmations) return false
    text = raw
    seenAt = now
    candidate = ""
    count = 0
    return true
  }

  fun clear() {
    text = ""
    seenAt = 0
    candidate = ""
    count = 0
  }
}

internal fun similarOcr(first: String, second: String): Boolean {
  val a = first.lowercase(Locale.ROOT)
  val b = second.lowercase(Locale.ROOT)
  if (a == b) return true
  if (minOf(a.length, b.length) < 15 || kotlin.math.abs(a.length - b.length) > 1) return false
  // One OCR character can vary, but changing digits often represents real game text.
  if (a.filter(Char::isDigit) != b.filter(Char::isDigit)) return false
  val (shorter, longer) = if (a.length < b.length) a to b else b to a
  var difference = 0
  var i = 0
  var j = 0
  while (i < shorter.length && j < longer.length) {
    if (shorter[i] == longer[j]) { i++; j++; continue }
    if (++difference > 1) return false
    j++
    if (shorter.length == longer.length) i++
  }
  return difference + (longer.length - j) <= 1
}
