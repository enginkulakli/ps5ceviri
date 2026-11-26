import expo.modules.ps5translator.core.SubtitleCaption
import expo.modules.ps5translator.core.SubtitleTracker

internal fun checksV32Subtitles() {
  val immediate = SubtitleTracker(50, 1)
  check(immediate.observe("Go now!", 100))
  check(immediate.translationSource(100, 700) == "Go now!")
  check(immediate.observe("Go back!", 150))
  check(immediate.translationSource(150, 700) == "Go back!")
  val results = mapOf("Go now!" to "Şimdi git!", "Go back!" to "Geri dön!")
  val active = immediate.text
  val shown = SubtitleCaption.select(null, active, results[active], immediate.seenAt, 180, 700, 200)
  check(shown?.text == "Geri dön!")

  val stable = SubtitleTracker(50, 2)
  check(!stable.observe("Follow the lantern east.", 100))
  check(stable.translationSource(100, 700) == "Follow the lantern east.")
  check(stable.text.isEmpty())
  check(stable.observe("Follow the lantern east.", 150))
  check(!stable.observe("Follow the lantem east.", 200))
  check(stable.seenAt == 200L && stable.translationSource(200, 700) == "Follow the lantern east.")
  check(stable.observe("The light is still on.", 250, reliable = true))
  check(stable.observe("The night is still on.", 300, reliable = true))
  check(stable.translationSource(300, 700) == "The night is still on.")
  check(stable.translationSource(1000, 700).isEmpty())
}
