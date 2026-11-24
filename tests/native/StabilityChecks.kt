import expo.modules.ps5translator.core.DetectedText
import expo.modules.ps5translator.core.FrameRect
import expo.modules.ps5translator.core.OcrLine
import expo.modules.ps5translator.core.OverlayBox
import expo.modules.ps5translator.core.OverlayLuma
import expo.modules.ps5translator.core.OverlayTracker
import expo.modules.ps5translator.core.SmartOcr
import expo.modules.ps5translator.core.SubtitleCaption
import expo.modules.ps5translator.core.SubtitleTracker
import expo.modules.ps5translator.core.TranslationAttempts
import expo.modules.ps5translator.core.TvOverlayLayout
import expo.modules.ps5translator.core.overlap
import kotlin.math.abs

internal val menuFixture = listOf(
  OverlayBox(1, "Harita", FrameRect(.06, .20, .129, .024), 2000),
  OverlayBox(2, "Oyun", FrameRect(.06, .252, .116, .024), 2000),
  OverlayBox(3, "Ayarlar", FrameRect(.06, .304, .145, .024), 2000),
  OverlayBox(4, "Nişangâh boyutu", FrameRect(.06, .356, .312, .024), 2000),
  OverlayBox(5, "Işık yoğunluğu", FrameRect(.60, .356, .151, .024), 2000),
)

internal val phoneFixture = listOf(
  OverlayBox(6, "Kişiler", FrameRect(.465, .235, .088, .026), 2000),
  OverlayBox(7, "Mandy'nin kirası şimdi geldi. Bu işi başarabileceğini biliyordum.",
    FrameRect(.445, .329, .13, .067), 2000),
  OverlayBox(8, "Arka katta biraz para var. Üzerindeki giysileri değiştirip otele git.",
    FrameRect(.445, .658, .13, .057), 2000),
)

internal fun stabilityChecks() {
  val settings = SmartOcr.group(listOf(
    OcrLine("Uzaktan Oynama bağlandı.", FrameRect(.45, .04, .16, .02), 0),
    OcrLine("Persistent Center Dot", FrameRect(.08, .23, .125, .023), 1),
    OcrLine("Reticle Hit Markers", FrameRect(.08, .267, .125, .023), 2),
    OcrLine("Weapon Reticle Width", FrameRect(.65, .267, .155, .023), 3),
  ))
  check(settings.size == 3 && settings.none { it.text.contains("bağlandı") })
  check(settings.any { it.text == "Reticle Hit Markers" })
  val contacts = SmartOcr.group(listOf(
    OcrLine("New message from Brian.", FrameRect(.46, .22, .14, .026), 0),
    OcrLine("Reminder about the car.", FrameRect(.46, .25, .14, .026), 1),
    OcrLine("Reminder about the car.", FrameRect(.76, .25, .14, .026), 2),
  ))
  check(contacts.size == 3 && contacts.count { it.text == "Reminder about the car." } == 2)

  val layout = TvOverlayLayout.arrange(menuFixture)
  check(layout.size == menuFixture.size && TvOverlayLayout.arrange(menuFixture.reversed()) == layout)
  val phone = TvOverlayLayout.arrange(phoneFixture)
  check(phone.size == phoneFixture.size)
  for ((rendered, source) in listOf(layout to menuFixture, phone to phoneFixture)) {
    check(rendered.indices.all { i -> (i + 1 until rendered.size).none { j ->
      rendered[i].rect.overlap(rendered[j].rect) > 0.0
    } })
    for (box in rendered) {
      val origin = source.single { it.id == box.id }
      check(box.rect.w <= .22 && abs(box.rect.cx - origin.rect.cx) < .001 && abs(box.rect.y - origin.rect.y) <= .036)
    }
  }
  check(layout.first().rect.w <= .07)

  val before = ByteArray(320 * 180) { 40 }
  val after = before.copyOf()
  for (y in 42..141) for (x in 126..208) after[y * 320 + x] = 125
  val oldScene = OverlayLuma(before, 320, 180)
  val nextScene = OverlayLuma(after, 320, 180)
  check(!nextScene.sceneChanged(oldScene))
  val phoneRect = phoneFixture[1].rect
  check(nextScene.changedAt(oldScene, phoneRect))
  val tracking = OverlayTracker()
  val outside = DetectedText("Settings", FrameRect(.11, .34, .07, .02))
  val inside = DetectedText("New message", phoneRect)
  tracking.observe(listOf(outside, inside), 100, 2000)
  val outsideId = tracking.alive(100, 2000).first().id
  tracking.observe(listOf(outside), 600, 2000) { nextScene.changedAt(oldScene, it) }
  check(tracking.alive(600, 2000).single().id == outsideId)
  tracking.observe(listOf(outside, inside), 800, 2000)
  tracking.observe(listOf(outside), 950, 2000, sceneChanged = true)
  check(tracking.alive(950, 2000).single().id == outsideId)
  tracking.observe(emptyList(), 1100, 2000)
  check(tracking.alive(1100, 2000).size == 1)
  tracking.observe(emptyList(), 1600, 2000)
  check(tracking.alive(1600, 2000).isEmpty())
  tracking.observe(listOf(outside), 1800, 2000)
  val rotated = outside.copy(text = "Game")
  tracking.observe(listOf(rotated), 1900, 2000)
  check(tracking.alive(1900, 2000).single().text == "Game")

  val duplicate = outside.copy(rect = outside.rect.copy(y = .405))
  tracking.clear()
  tracking.observe(listOf(outside, duplicate), 2000, 2000)
  val initial = tracking.alive(2000, 2000).associateBy { it.rect.y }
  val jittered = duplicate.copy(rect = duplicate.rect.copy(x = .113))
  tracking.observe(listOf(jittered), 2500, 2000)
  val latest = tracking.alive(2500, 2000).single(tracking::seenInLatestFrame)
  check(latest.id == initial.getValue(.405).id && latest.rect == duplicate.rect)

  val tracker = SubtitleTracker(300, 2)
  val cache = mapOf("Follow the lantern east." to "Feneri takip et.", "Go!" to "Git!", "Wait here." to "Burada bekle.")
  check(tracker.observe("Follow the lantern east.", 100, reliable = true))
  var caption = SubtitleCaption.select(null, tracker.text, cache[tracker.text], tracker.seenAt, 110, 2000, 700)
  check(caption?.text == "Feneri takip et.")
  check(!tracker.observe("Follow the lantem east.", 230, reliable = true))
  check(tracker.observe("Go!", 280, reliable = true))
  caption = SubtitleCaption.select(caption, tracker.text, cache[tracker.text], tracker.seenAt, 300, 2000, 700)
  check(caption?.text == "Git!" && caption.shownAt == 300L)
  check(tracker.observe("Wait here.", 440, reliable = true))
  val latestCaption = SubtitleCaption.select(caption, tracker.text, cache[tracker.text], tracker.seenAt, 461, 2000, 700)
  check(latestCaption?.text == "Burada bekle.")
  check(!tracker.observe("Temporary noise", 2300))
  tracker.expire(2440, 2000)
  check(tracker.observe("Temporary noise", 2480))
  check(tracker.observe("Go to Gate A", 2600, reliable = true))
  check(tracker.observe("Go to Gate B", 2740, reliable = true))

  val attempts = TranslationAttempts()
  var aborted = 0
  val slow = attempts.start("obsolete Cloud result")
  slow.attach { aborted++ }
  val stillVisible = attempts.start("same text at two locations")
  repeat(100) { check("same text at two locations" in attempts) }
  attempts.releaseObsolete(setOf("same text at two locations", "new subtitle"), 1)
  check(aborted == 1 && slow.cancelled && attempts.room == 1)
  val fresh = attempts.start("new subtitle")
  attempts.finish("new subtitle", slow)
  check(attempts.current("new subtitle", fresh) && attempts.current("same text at two locations", stillVisible))
  fresh.canRelease = false
  stillVisible.canRelease = false
  attempts.releaseObsolete(emptySet(), 1)
  check(attempts.room == 0)
  attempts.clear()
  check(!attempts.busy)
}
