package expo.modules.ps5translator.core

internal class SubtitleTracker(private val intervalMs: Long, private val confirmations: Int = 2) {
  private var count = 0
  private var candidate = ""
  private var candidateAt = 0L
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
    count = if (normalized == candidate && now - candidateAt <= maxOf(1400, intervalMs * 2 + 200)) count + 1 else 1
    candidate = normalized
    candidateAt = now
    candidateJitter = text.length >= 12 && looksLikeOcrJitter(text, normalized, reliable) && count < 2
    if (candidateJitter) {
      seenAt = now
      return false
    }
    // Confirmation controls presentation. A plausible candidate may start translating now.
    if (count < confirmations && !(confirmations == 2 && reliable)) return false
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
