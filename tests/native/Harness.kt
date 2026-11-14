// JVM test harness for SubtitleServerCore (not shipped in the app).
// Reads commands from stdin and prints JSON-ish state lines to stdout.
import expo.modules.ps5translator.core.SubtitleServerCore

fun main() {
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
