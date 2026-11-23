// JVM test harness for SubtitleServerCore (not shipped in the app).
// Reads commands from stdin and prints JSON-ish state lines to stdout.
import expo.modules.ps5translator.core.SubtitleServerCore
import expo.modules.ps5translator.core.SubtitleTracker

fun main() {
  val tracker = SubtitleTracker(300)
  check(!tracker.observe("Follow the lantern east.", 100))
  check(!tracker.observe("", 200))
  check(tracker.observe("Follow the lantern east.", 400))
  check(!tracker.observe("Follow the lantem east.", 500))
  check(!tracker.observe("Follow the lanterh east.", 700))
  check(tracker.seenAt == 700L)
  check(!tracker.observe("Wait here.", 900))
  check(tracker.observe("Wait here.", 1200))
  check(!tracker.observe("", 1300) && tracker.text == "Wait here.")
  tracker.clear()
  check(tracker.text.isEmpty())
  val core = SubtitleServerCore(onChange = { })
  println("READY")
  System.out.flush()
  while (true) {
    val line = readLine() ?: break
    val cmd = line.substringBefore(' ')
    val arg = line.substringAfter(' ', "").replace("<NL>", "\n")
    val out = when (cmd) {
      "START" -> {
        val parts = arg.split(' ')
        val port = parts[0].toInt()
        val allow = parts.getOrNull(1)?.takeIf { it != "-" }
        "START " + (core.start(port, allow) ?: "OK")
      }
      "STOP" -> { core.stop(); "STOPPED" }
      "SEND" -> core.send(arg).let { "SEND ${it.status} ${it.clients}" }
      "TTL" -> core.send(arg.substringAfter(' '), ttlMs = arg.substringBefore(' ').toLong())
        .let { "SEND ${it.status} ${it.clients}" }
      "REFRESH" -> core.refresh(arg.substringAfter(' '), arg.substringBefore(' ').toLong()).toString()
      "SENDEMPTY" -> core.send("").let { "SEND ${it.status} ${it.clients}" }
      "FORCE" -> core.send(arg, force = true).let { "SEND ${it.status} ${it.clients}" }
      "ALLOW" -> { core.setAllowedIp(arg.takeIf { it != "-" }); "ALLOWED" }
      "STATE" -> core.snapshot().let { "STATE running=${it.running} clients=${it.clients.size} sent=${it.sentCount} rejected=${it.rejectedCount} last=${it.lastSentText} err=${it.lastError}" }
      "QUIT" -> { core.stop(); println("BYE"); System.out.flush(); return }
      else -> "UNKNOWN"
    }
    println(out)
    System.out.flush()
  }
}
