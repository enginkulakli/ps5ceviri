package expo.modules.ps5translator.core

internal class SubtitleTracker(private val intervalMs: Long, private val confirmations: Int = 2) {
  private var count = 0
  private var candidate = ""
  private var candidateAt = 0L
  private var candidateStartedAt = 0L
  var confirmationMs = 0L
    private set
  private var candidateJitter = false
  var text = ""
    private set
  var seenAt = 0L
    private set

  fun observe(raw: String, now: Long, reliable: Boolean = false): Boolean {
    if (raw.isBlank()) return false
    val normalized = memoryText(raw)
    if (normalized == text) {
      seenAt = now
      candidate = ""
      count = 0
      return false
    }
    val continuing = normalized == candidate && now - candidateAt <= maxOf(1400, intervalMs * 2 + 200)
    count = if (continuing) count + 1 else 1
    if (!continuing) candidateStartedAt = now
    candidate = normalized
    candidateAt = now
    candidateJitter = text.length >= 12 && looksLikeOcrJitter(text, normalized, reliable) && count < 2
    if (candidateJitter) {
      seenAt = now
      return false
    }
    // Confirmation controls presentation. A plausible candidate may start translating now.
    if (count < confirmations && !(confirmations == 2 && reliable)) return false
    confirmationMs = now - candidateStartedAt
    text = normalized
    seenAt = now
    candidate = ""
    count = 0
    return true
  }

  fun translationSource(now: Long, ttl: Long): String {
    if (candidate.isNotEmpty() && !candidateJitter && now - candidateAt < ttl) return candidate
    return text.takeIf { now - seenAt < ttl }.orEmpty()
  }

  fun nextScanInterval(now: Long): Long {
    if (intervalMs > 300) return intervalMs
    // The usual two-read mode gets its second sample at the supported scan floor.
    // Stable text and deliberate three/four-read configurations keep their cadence.
    if (confirmations == 2 && count == 1 && !candidateJitter && now - candidateAt <= intervalMs) {
      return minOf(50, intervalMs)
    }
    return if (recentlyRead(now)) minOf(180, intervalMs) else intervalMs
  }

  fun readyForPublication(now: Long, ttl: Long) = text.isNotEmpty() && translationSource(now, ttl) == text

  fun recentlyRead(now: Long) =
    (text.isNotEmpty() && now - seenAt < 500) || (candidate.isNotEmpty() && now - candidateAt < 500)

  fun expire(now: Long, ttl: Long) {
    if (text.isNotEmpty() && now - seenAt >= ttl) {
      text = ""
      seenAt = 0
    }
  }

  fun clear() {
    text = ""
    seenAt = 0
    candidate = ""
    count = 0
  }
}

private fun looksLikeOcrJitter(current: String, incoming: String, reliable: Boolean): Boolean {
  if (current.replace("rn", "m") == incoming.replace("rn", "m")) return true
  if (reliable || current.length != incoming.length) return false
  val changed = current.indices.filter { current[it] != incoming[it] }.singleOrNull() ?: return false
  return current[changed].isLowerCase() && incoming[changed].isLowerCase() &&
    (current.getOrNull(changed - 1)?.isLowerCase() == true || current.getOrNull(changed + 1)?.isLowerCase() == true)
}

internal data class SubtitleCaption(val source: String, val text: String, val shownAt: Long,
                                    var until: Long, var sentAt: Long = 0) {
  companion object {
    fun select(previous: SubtitleCaption?, source: String, translated: String?, seenAt: Long,
               now: Long, ttl: Long, minimum: Long): SubtitleCaption? {
      if (translated != null && (previous?.source != source || previous.text != translated)) {
        return SubtitleCaption(source, translated, now, maxOf(seenAt + ttl, now + minimum))
      }
      if (previous?.source == source) previous.until = maxOf(seenAt + ttl, previous.shownAt + minimum)
      return previous?.takeIf { now < it.until }
    }
  }
}
