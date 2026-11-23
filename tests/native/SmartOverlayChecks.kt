import expo.modules.ps5translator.core.DetectedText
import expo.modules.ps5translator.core.FrameRect
import expo.modules.ps5translator.core.OcrLine
import expo.modules.ps5translator.core.OverlayBox
import expo.modules.ps5translator.core.OverlayTracker
import expo.modules.ps5translator.core.SmartOcr
import expo.modules.ps5translator.core.SubtitleTracker
import expo.modules.ps5translator.core.TvOverlayLayout
import expo.modules.ps5translator.core.bottom
import expo.modules.ps5translator.core.memoryText
import expo.modules.ps5translator.core.overlap
import expo.modules.ps5translator.core.right

internal fun smartOverlayChecks() {
  val menu = listOf(
    OcrLine("VI", FrameRect(.11, .06, .025, .035), 0),
    OcrLine("N map", FrameRect(.12, .22, .12, .033), 1),
    OcrLine("D Game", FrameRect(.12, .30, .11, .033), 2),
    OcrLine("44 Settings", FrameRect(.12, .39, .13, .036), 3),
    OcrLine("Scroll", FrameRect(.87, .92, .08, .032), 4),
  )
  val cleanMenu = SmartOcr.group(menu)
  check(cleanMenu.map { it.text } == listOf("map", "Game", "Settings"))
  val alternate = SmartOcr.group(menu.map { if (it.group == 1) it.copy(text = "map") else it })
  check(cleanMenu == alternate)
  val messages = listOf(
    OcrLine("Brian", FrameRect(.33, .19, .08, .028), 0),
    OcrLine("Mandy's rent just came in.", FrameRect(.27, .255, .16, .025), 1),
    OcrLine("Mandy's rent just came in.", FrameRect(.271, .254, .159, .025), 3),
    OcrLine("I knew you could pull it off.", FrameRect(.27, .284, .15, .025), 2),
    OcrLine("New messages", FrameRect(.30, .43, .12, .028), 4),
  )
  val grouped = SmartOcr.group(messages)
  check(grouped.size == 3 && grouped[1].text == "Mandy's rent just came in. I knew you could pull it off.")
  val tracker = OverlayTracker()
  tracker.observe(grouped, 100, 2000)
  val before = tracker.alive(100, 2000)
  tracker.observe(grouped.map { it.copy(rect = it.rect.copy(x = it.rect.x + .031)) }, 600, 2000)
  check(tracker.alive(600, 2000).map { it.id }.toSet() == before.map { it.id }.toSet())
  tracker.observe(cleanMenu, 1100, 2000, sceneChanged = true)
  check(tracker.alive(1100, 2000).map { it.text }.toSet() == cleanMenu.map { it.text }.toSet())
  tracker.clear()
  check(tracker.alive(1200, 2000).isEmpty())

  val boxes = listOf(
    OverlayBox(1, "Brian", grouped[0].rect, 2000),
    OverlayBox(2, "Mandy'nin kirası yeni geldi. Bunu gördün mü? Bu işi başarabileceğini biliyordum, seni ihtiyar kurt!", grouped[1].rect, 2000),
    OverlayBox(3, "Yeni Mesajlar", grouped[2].rect, 1800),
    OverlayBox(4, "Arka katta bir miktar para var. Üzerindeki giysileri değiştirip hemen otele git.", FrameRect(.27, .48, .15, .06), 1800),
    OverlayBox(5, "Crastenburg Oteli", FrameRect(.27, .565, .15, .029), 1700),
  )
  val arranged = TvOverlayLayout.arrange(boxes)
  check(arranged.size >= 3 && arranged.any { it.id == 2L } && arranged.any { it.id == 4L })
  check(arranged.all { it.rect.x >= 0 && it.rect.y >= 0 && it.rect.right <= 1 && it.rect.bottom <= 1 })
  check(arranged.indices.all { i -> (i + 1 until arranged.size).all { j -> arranged[i].rect.overlap(arranged[j].rect) == 0.0 } })
  check(TvOverlayLayout.arrange(boxes) == arranged)
  val subtitle = SubtitleTracker(200, 1)
  check(subtitle.observe("Go to Gate 1", 1))
  check(subtitle.observe("Go to Gate 2", 180))
  check(memoryText("They can open it") != memoryText("They can't open it"))
  check(memoryText("Polish") != memoryText("polish"))
  check(memoryText("Go  to\n the mine.") == memoryText("Go to the mine."))
}
