package expo.modules.ps5translator.core

/**
 * Encodes the exact message format expected by the existing Tizen TV app:
 *   {"text":"Türkçe altyazı"}   and   {"text":""}
 * Pure Kotlin (no Android / org.json) so it can be unit-tested on the JVM.
 */
object SubtitleMessage {
  fun encode(text: String, ttlMs: Long? = null): String {
    val sb = StringBuilder(text.length + 12)
    sb.append("{\"text\":\"")
    for (ch in text) {
      when (ch) {
        '"' -> sb.append("\\\"")
        '\\' -> sb.append("\\\\")
        '\n' -> sb.append("\\n")
        '\r' -> sb.append("\\r")
        '\t' -> sb.append("\\t")
        '\b' -> sb.append("\\b")
        '\u000C' -> sb.append("\\f")
        else -> if (ch < ' ') sb.append(String.format("\\u%04x", ch.code)) else sb.append(ch)
      }
    }
    sb.append('"')
    if (ttlMs != null) sb.append(",\"ttlMs\":").append(ttlMs)
    sb.append('}')
    return sb.toString()
  }
}
