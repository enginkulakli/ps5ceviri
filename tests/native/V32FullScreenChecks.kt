import expo.modules.ps5translator.core.DetectedText
import expo.modules.ps5translator.core.FrameRect
import expo.modules.ps5translator.core.OcrFrame
import expo.modules.ps5translator.core.OverlayBox
import expo.modules.ps5translator.core.TvOverlayLayout
import expo.modules.ps5translator.core.OcrLine
import expo.modules.ps5translator.core.OverlayTracker
import expo.modules.ps5translator.core.SmartOcr

fun checksV32FullScreen() {
  val source = OcrFrame(byteArrayOf(0, 40, 20, 60, 0, 0), 2, 2)
  val sampled = OcrFrame(ByteArray(1280 * 720 * 3 / 2) { 71 }, 1280, 720).enlargeForText()
  check(sampled.width == 1920 && sampled.height == 1080)
  check(sampled.nv21[1079 * 1920 + 1919] == 71.toByte())
  check(sampled.nv21[1080 * 1920] == 128.toByte())
  check(source.enlargeForText() === source)
  val subdued = OcrFrame(ByteArray(1280 * 720 * 3 / 2) { if ((it % 1280) < 640) 80 else 120 }, 1280, 720)
    .enlargeForText()
  check((subdued.nv21[0].toInt() and 255) < 80)
  check((subdued.nv21[1919].toInt() and 255) > 120)
  val adjacent = SmartOcr.group(listOf(
    OcrLine("Please come home.", FrameRect(.6, .20, .19, .022), 2),
    OcrLine("Call me right now.", FrameRect(.60, .232, .19, .022), 2),
  ))
  check(adjacent.size == 2)

  val detect = SmartOcr.group(listOf(
    OcrLine("Can you wait until", FrameRect(.62, .30, .19, .022), 1),
    OcrLine("I get home?", FrameRect(.71, .331, .10, .022), 1),
    OcrLine("No, call me now.", FrameRect(.53, .361, .16, .022), 2),
    OcrLine("You have five minutes.", FrameRect(.08, .34, .19, .022), 3),
    OcrLine("No, call me now.", FrameRect(.53, .360, .16, .022), 4),
  ))
  check(detect.map { it.text }.toSet() == setOf(
    "Can you wait until I get home?", "No, call me now.", "You have five minutes.",
  )) { detect }
  val sourceBox = detect.first()
  val tracker = OverlayTracker()
  tracker.observe(detect, 100, 1500)
  val id = tracker.alive(100, 1500).first().id
  val lower = sourceBox.rect.copy(y = .49)
  tracker.observe(listOf(sourceBox.copy(rect = lower)), 600, 1500)
  val visible = tracker.alive(600, 1500)
  check(visible.first().id == id && visible.first().rect == lower)
  check(visible.none { it.text == "No, call me now." })
  val short = DetectedText("Can you wait until", lower.copy(h = .022))
  tracker.observe(listOf(short), 900, 1500)
  check(tracker.alive(900, 1500).single().text == sourceBox.text)
  tracker.observe(listOf(short), 1200, 1500)
  check(tracker.alive(1200, 1500).single().text == short.text)
  tracker.observe(emptyList(), 1400, 1500, sceneChanged = true)
  check(tracker.alive(1400, 1500).isEmpty())

  val compact = (1L..8L).map { id -> OverlayBox(id, "Birbiriyle çakışan bağımsız mesaj $id", lower, 1000) }
  check(TvOverlayLayout.arrange(compact).size < compact.size)
  val forwarded = TvOverlayLayout.arrange(compact, retainUnplaced = true)
  check(forwarded.map { it.id } == compact.map { it.id })
  check(forwarded.all { it.rect.x + it.rect.w <= 1.0 && it.rect.y + it.rect.h <= 1.0 })
  val excluded = mutableListOf<String>()
  val admitted = SmartOcr.group(listOf(
    OcrLine("Please read this tiny message", lower, 1),
    OcrLine("Please read this tiny message", lower, 1),
    OcrLine("Back", FrameRect(.88, .91, .08, .02), 2),
  )) { _, reason -> excluded.add(reason) }
  check(admitted.size == 1 && excluded.toSet() == setOf("duplicate", "hud_or_noise"))
}
