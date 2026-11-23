package expo.modules.ps5translator.core

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
    val normalized = memoryText(raw)
    if (normalized == text) {
      seenAt = now
      candidate = ""
      count = 0
      return false
    }
    count = if (normalized == candidate && now - candidateAt <= maxOf(1400, intervalMs * 2 + 200)) count + 1 else 1
    candidate = normalized
    candidateAt = now
    if (count < confirmations) return false
    text = normalized
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
