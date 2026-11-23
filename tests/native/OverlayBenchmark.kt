import expo.modules.ps5translator.core.BaselineOverlayTracker
import expo.modules.ps5translator.core.DetectedText
import expo.modules.ps5translator.core.FrameRect
import expo.modules.ps5translator.core.OcrLine
import expo.modules.ps5translator.core.OverlayBox
import expo.modules.ps5translator.core.OverlayTracker
import expo.modules.ps5translator.core.SmartOcr
import expo.modules.ps5translator.core.TvOverlayLayout
import java.util.Locale

private val menu = listOf(
  OcrLine("VI", FrameRect(.11, .06, .025, .035), 0),
  OcrLine("N map", FrameRect(.12, .22, .12, .033), 1),
  OcrLine("D Game", FrameRect(.12, .30, .11, .033), 2),
  OcrLine("44 Settings", FrameRect(.12, .39, .13, .036), 3),
  OcrLine("Scroll", FrameRect(.87, .92, .08, .032), 4),
)
private val phone = listOf(
  OcrLine("Brian", FrameRect(.33, .19, .08, .028), 0),
  OcrLine("Mandy's rent just came in.", FrameRect(.27, .255, .16, .025), 1),
  OcrLine("Mandy's rent just came in.", FrameRect(.271, .254, .159, .025), 3),
  OcrLine("I knew you could pull it off.", FrameRect(.27, .284, .15, .025), 2),
  OcrLine("New messages", FrameRect(.30, .43, .12, .028), 4),
)
private val translations = mapOf(
  "Mandy's rent just came in." to "Mandy'nin kirası az önce geldi.",
  "I knew you could pull it off." to "Bunu başarabileceğini biliyordum.",
  "Mandy's rent just came in. I knew you could pull it off." to
    "Mandy'nin kirası az önce geldi. Bunu başarabileceğini biliyordum.",
  "New messages" to "Yeni mesajlar",
)
private val menuVariants = listOf(menu, menu.map { if (it.group == 1) it.copy(text = "map") else it })

fun main() {
  compare("static menu", List(1500) { menuVariants[it % 2] })
  compare("phone screen", List(1500) { phone })
}

private fun compare(label: String, frames: List<List<OcrLine>>) {
  val v2 = BaselineOverlayTracker()
  val v3 = OverlayTracker()
  val seenV2 = mutableSetOf<String>()
  val seenV3 = mutableSetOf<String>()
  var layoutInputs = emptyList<OverlayBox>()
  var layout = emptyList<OverlayBox>()
  val t2 = ArrayList<Long>()
  val t3 = ArrayList<Long>()
  var countV2 = 0
  for ((index, lines) in frames.withIndex()) {
    val now = (index + 1) * 500L
    var start = System.nanoTime()
    val v2Blocks = lines.map { DetectedText(it.text, it.rect) }.sortedByDescending { it.rect.w * it.rect.h }
    v2.observe(v2Blocks, now, 2000)
    val old = v2.alive(now, 2000)
    countV2 = old.size
    seenV2.addAll(old.filter(v2::seenInLatestFrame).map { it.text })
    if (index >= 300) t2.add(System.nanoTime() - start)

    start = System.nanoTime()
    v3.observe(SmartOcr.group(lines), now, 2000)
    val active = v3.alive(now, 2000)
    seenV3.addAll(active.filter(v3::seenInLatestFrame).map { it.normalized })
    val inputs = active.map { OverlayBox(it.id, translations[it.text] ?: it.text, it.rect, 0) }
    if (inputs != layoutInputs) {
      layoutInputs = inputs
      layout = TvOverlayLayout.arrange(inputs)
    }
    if (index >= 300) t3.add(System.nanoTime() - start)
  }
  check(seenV3.size < seenV2.size && layout.size < countV2)
  println("$label — V2 JVM p50/p95 ${percentiles(t2)}, ${seenV2.size} distinct source candidates, $countV2 boxes; " +
    "V3 JVM p50/p95 ${percentiles(t3)}, ${seenV3.size} distinct source candidates, ${layout.size} boxes")
}

private fun percentiles(samples: List<Long>): String {
  val ordered = samples.sorted()
  return String.format(Locale.ROOT, "%.3f/%.3f ms", ordered[ordered.size / 2] / 1e6, ordered[ordered.size * 95 / 100] / 1e6)
}
