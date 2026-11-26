import expo.modules.ps5translator.core.DetectedText
import expo.modules.ps5translator.core.FrameRect
import expo.modules.ps5translator.core.OverlayBox
import expo.modules.ps5translator.core.PhoneScreen
import expo.modules.ps5translator.core.PhoneSignature
import expo.modules.ps5translator.core.TvOverlayLayout
import expo.modules.ps5translator.core.memoryText
import expo.modules.ps5translator.core.pixelCrop

private fun picture(vararg rows: FrameRect, cursor: Boolean = false, variant: Int = 0, protectPhone: Boolean = false): PhoneSignature =
  PhoneSignature.sample(960, 540) { x, y ->
    val px = x / 960.0
    val py = y / 540.0
    val text = rows.any { px in it.x..(it.x + it.w) && py in it.y..(it.y + it.h) }
    when {
      text && ((x / 10 + y / 13) % 3 != 0) -> 209
      cursor && x in 500..510 && y in 260..271 -> 255
      variant != 0 && (!protectPhone || px < .63) -> if ((x / 10 + y / 13 + variant) % 2 == 0) 216 else 4
      else -> 46
    }
  }

fun main() {
  val phone = PhoneScreen()
  val top = FrameRect(.68, .15, .19, .045)
  val bottom = FrameRect(.68, .49, .19, .045)
  val first = DetectedText("The room is cold.", top)
  val second = DetectedText("The room is gold.", bottom)
  var full = 0
  var regional = 0
  val calls = mutableMapOf<String, Int>()
  val cache = mutableMapOf<String, String>()
  fun observe(scan: PhoneScreen.Scan, at: Long, vararg detections: DetectedText) {
    if (scan.full) full++ else regional += scan.regions.size
    phone.observe(scan, detections.toList(), at)
    phone.applyTranslations { key ->
      cache.getOrPut(key) {
        calls[key] = (calls[key] ?: 0) + 1
        "Türkçe $key"
      }
    }
  }

  val initial = picture(top)
  val firstScan = checkNotNull(phone.inspect(initial, 0))
  check(firstScan.full)
  observe(firstScan, 0, first)
  val original = phone.visible().single()
  for (time in PhoneScreen.PROBE_INTERVAL_MS..30_000 step PhoneScreen.PROBE_INTERVAL_MS) {
    val next = picture(top, cursor = time % 900 == 0L)
    check(phone.inspect(next, time) == null) { "Unexpected OCR at ${time}ms" }
  }
  check(full == 1 && regional == 0 && calls.values.sum() == 1)
  check(phone.visible().single().translationPair() == original.translationPair())
  println("Phone fixture · 30 s: $full initial OCR, 0 repeat OCR, 1 initial translation, 0 repeat translations")

  val added = picture(top, bottom)
  check(phone.inspect(added, 30_150) == null)
  val patch = checkNotNull(phone.inspect(added, 30_600))
  check(!patch.full && patch.covers(bottom) && !patch.covers(top)) { patch.regions }
  observe(patch, 30_600, second)
  check(calls.keys == setOf(memoryText(first.text), memoryText(second.text)))
  check(calls.values.sum() == 2 && phone.visible().size == 2)
  check(phone.visible().first().id == original.id)
  println("Phone fixture · localized change: 450 ms between probes, ${patch.regions.size} regional OCR, 1 new translation")

  val scrolled = top.copy(y = .32)
  val offscreen = picture(scrolled)
  check(phone.inspect(offscreen, 31_050) == null)
  val moved = checkNotNull(phone.inspect(offscreen, 31_500))
  check(moved.covers(top) && moved.covers(bottom) && moved.covers(scrolled))
  observe(moved, 31_500, first.copy(rect = scrolled))
  check(phone.visible().single().let { it.id == original.id && it.rect == scrolled && it.translated == original.translated })
  check(calls.values.sum() == 2)

  val replacement = PhoneScreen()
  val seed = picture(top)
  replacement.observe(checkNotNull(replacement.inspect(seed, 0)), listOf(first), 0)
  var fallback: PhoneScreen.Scan? = null
  for (i in 1..16) {
    val busy = if (i % 2 == 1) picture(variant = 1) else picture(variant = 2)
    fallback = replacement.inspect(busy, i * 450L)
    if (fallback != null) break
  }
  check(fallback?.full == true)
  // A moving gameplay background must not cause recurring full-frame OCR if
  // the tracked phone message itself still has the same appearance.
  val movingBackground = PhoneScreen()
  movingBackground.observe(checkNotNull(movingBackground.inspect(initial, 0)), listOf(first), 0)
  for (i in 1..22) {
    check(movingBackground.inspect(picture(top, variant = i % 2 + 1, protectPhone = true), i * 450L) == null)
  }
  check(movingBackground.visible().single().verifiedAt == 22 * 450L)
  phone.clear()
  check(phone.visible().isEmpty() && checkNotNull(phone.inspect(initial, 32_000)).full)

  val baseline = TvOverlayLayout.arrange(phoneFixture.map { it.copy(ttlMs = 0) })
  val layout = TvOverlayLayout.PhoneLayout()
  val placed = layout.arrange(phoneFixture.map { it.copy(ttlMs = 0) })
  check(placed == baseline) { "$baseline != $placed" }
  val extra = OverlayBox(54L, "Bu uzun ileti, birkaç satıra yayılsa bile önceki mesajların kutularını taşımamalıdır.", top, 0)
  val next = layout.arrange(phoneFixture.map { it.copy(ttlMs = 0) } + extra)
  check(next.filter { it.id != extra.id } == placed && next.size == placed.size + 1)
  println("Phone layout fixture · V3.2 and V3.2.2: ${placed.size}/${placed.size} equal wire rectangles; existing boxes stay pinned on insertion")

  val pixels = FrameRect(.157, .723, .7, .215).pixelCrop(1280, 720)
  check(listOf(pixels.left, pixels.top, pixels.width, pixels.height) == listOf(200, 520, 896, 154))
  val default = FrameRect(0.0, .72, 1.0, .28).pixelCrop(1280, 720)
  check(listOf(default.left, default.top, default.width, default.height) == listOf(0, 518, 1280, 200))
}

private fun PhoneScreen.Message.translationPair() = Triple(id, rect, translated)
