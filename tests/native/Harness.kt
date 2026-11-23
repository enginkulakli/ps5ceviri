// JVM test harness for SubtitleServerCore (not shipped in the app).
// Reads commands from stdin and prints JSON-ish state lines to stdout.
import expo.modules.ps5translator.core.SubtitleServerCore
import expo.modules.ps5translator.core.SubtitleTracker
import expo.modules.ps5translator.core.DetectedText
import expo.modules.ps5translator.core.FrameRect
import expo.modules.ps5translator.core.OverlayBox
import expo.modules.ps5translator.core.OverlayMessage
import expo.modules.ps5translator.core.OverlayTracker
import expo.modules.ps5translator.core.GameGlossary
import expo.modules.ps5translator.core.GlossaryEntry
import expo.modules.ps5translator.core.cleanOcr

fun main() {
  val tracker = SubtitleTracker(300)
  check(!tracker.observe("Follow the lantern east.", 100))
  check(!tracker.observe("", 200))
  check(tracker.observe("Follow the lantern east.", 400))
  check(!tracker.observe("Follow the lantem east.", 500))
  check(!tracker.observe("Follow the lanterh east.", 700))
  check(tracker.seenAt == 400L)
  check(!tracker.observe("Follow the lantern  east.", 800) && tracker.seenAt == 800L)
  check(!tracker.observe("Wait here.", 900))
  check(tracker.observe("Wait here.", 1200))
  check(!tracker.observe("", 1300) && tracker.text == "Wait here.")
  tracker.clear()
  check(tracker.text.isEmpty())
  val rect = FrameRect(.1, .2, .3, .1)
  val moved = rect.copy(x = .14)
  val overlays = OverlayTracker()
  overlays.observe(listOf(DetectedText("Castle", rect), DetectedText("Castle", rect.copy(y = .7))), 100, 1000)
  val originals = overlays.alive(100, 1000)
  check(originals.size == 2 && originals.map { it.id }.distinct().size == 2)
  overlays.observe(listOf(DetectedText("Castle", moved)), 500, 1000)
  val moving = overlays.alive(550, 1000).first()
  check(overlays.seenInLatestFrame(moving))
  check(!overlays.seenInLatestFrame(overlays.alive(550, 1000).last()))
  check(moving.id == originals.first().id && moving.rect.x in .11.. .13)
  check(overlays.alive(1100, 1000).size == 1)
  overlays.observe(listOf(DetectedText("Menu", moved)), 1200, 1000)
  check(overlays.alive(1200, 1000).single().let { it.id == moving.id && it.text == "Menu" })
  check(overlays.alive(2200, 1000).isEmpty())
  check(SubtitleTracker(150, 1).observe("Go!", 1))
  val strict = SubtitleTracker(300, 3)
  check(!strict.observe("Stay here.", 100) && !strict.observe("Stay here.", 300) && strict.observe("Stay here.", 500))
  check(cleanOcr("Re-\n turn  to the\n tower !") == "Return to the tower!")
  val glossary = GameGlossary(listOf(GlossaryEntry("mine", "maden"), GlossaryEntry("Old Mine", "Eski Maden")))
  check(glossary.split("Go to OLD MINE. Mines first.").filter { it.protected }.single().text == "Eski Maden")
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
      "OVERLAY" -> core.sendOverlay(OverlayMessage(1280, 720, listOf(
        OverlayBox(1, "Görev \"başladı\"", rect, 1000),
        OverlayBox(2, "İkinci satır", moved.copy(y = .7), 2500),
      ))).toString()
      "CLEAROVERLAY" -> core.sendOverlay(OverlayMessage(1280, 720, emptyList())).toString()
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
