package expo.modules.ps5translator.core

import kotlin.math.abs
import kotlin.math.ceil

internal data class OcrLine(val text: String, val rect: FrameRect, val group: Int)

private val controls = Regex("(?:scroll|set waypoint|back|select|return|navigate)(?:\\s+[a-z0-9])?", RegexOption.IGNORE_CASE)
private val badge = Regex("(?:remote play (?:connection|connected|in progress)(?:[ .…].*)?|uzaktan oynama bağlant[ıi].*)", RegexOption.IGNORE_CASE)
private val menu = Regex("(?:[ndopil0-9|()■□◯]{1,3}\\s+)?(map|game|store|help|photo studio|settings)(?:\\s+[o0|])?", RegexOption.IGNORE_CASE)
private val roman = Regex("[IVXLCDM]{2,5}")

/** Stable normalization preserves words, punctuation and all numbers outside known menu icons. */
internal fun memoryText(value: String) = cleanOcr(value)

internal object SmartOcr {
  fun group(lines: List<OcrLine>): List<DetectedText> {
    val normalized = lines.take(160).mapNotNull(::cleanLine).sortedBy { it.rect.y }
    val distinct = mutableListOf<OcrLine>()
    for (line in normalized) {
      if (distinct.none { it.text == line.text && it.rect.overlap(line.rect) > .55 }) {
        distinct.add(line)
      }
    }
    val paragraphs = mutableListOf<Pair<DetectedText, Int>>()
    for (line in distinct) {
      val best = paragraphs.indices.filter { i ->
        val (previous, group) = paragraphs[i]
        val gap = line.rect.y - previous.rect.bottom
        val shared = (minOf(line.rect.right, previous.rect.right) - maxOf(line.rect.x, previous.rect.x)).coerceAtLeast(0.0)
        val related = group == line.group ||
          (previous.text.count { it == ' ' } >= 3 && line.text.count { it == ' ' } >= 2 &&
            abs(previous.rect.x - line.rect.x) <= .025)
        related && gap in -.008..(line.rect.h * .62) && shared >= minOf(line.rect.w, previous.rect.w) * .58 &&
          previous.text.length + line.text.length <= 420
      }.minByOrNull { abs(paragraphs[it].first.rect.bottom - line.rect.y) }
      if (best == null) paragraphs.add(DetectedText(line.text, line.rect) to line.group)
      else {
        val previous = paragraphs[best].first
        paragraphs[best] = DetectedText(cleanOcr(previous.text + "\n" + line.text), previous.rect.union(line.rect)) to line.group
      }
    }
    return paragraphs.map { it.first }.take(48)
  }

  private fun cleanLine(line: OcrLine): OcrLine? {
    var text = cleanOcr(line.text).take(420)
    val rect = line.rect
    if (rect.cy < .18 && (badge.matches(text) || (roman.matches(text) && rect.w <= .08))) return null
    if (rect.cy > .81 && rect.cx > .70 && controls.matches(text)) return null
    if (rect.cx < .48 && rect.h <= .075) menu.matchEntire(text)?.let { text = it.groupValues[1] }
    if (text.count(Char::isLetter) < 2) return null
    return line.copy(text = text)
  }
}

internal val FrameRect.right get() = x + w
internal val FrameRect.bottom get() = y + h
internal fun FrameRect.union(other: FrameRect): FrameRect {
  val left = minOf(x, other.x)
  val top = minOf(y, other.y)
  return FrameRect(left, top, maxOf(right, other.right) - left, maxOf(bottom, other.bottom) - top)
}

internal fun FrameRect.overlap(other: FrameRect): Double {
  val width = (minOf(right, other.right) - maxOf(x, other.x)).coerceAtLeast(0.0)
  val height = (minOf(bottom, other.bottom) - maxOf(y, other.y)).coerceAtLeast(0.0)
  return (width * height) / minOf(w * h, other.w * other.h)
}

internal object TvOverlayLayout {
  private const val GAP = .008
  // TV CSS sizes are logical pixels. Reserve the largest V2 font at a conservative 720p viewport.
  private const val FONT = 42.0
  private const val WIDTH = 1280.0
  private const val HEIGHT = 720.0
  private val wordBoundaries = Regex("\\s+")

  fun arrange(boxes: List<OverlayBox>): List<OverlayBox> {
    val placed = mutableListOf<OverlayBox>()
    val source = boxes.sortedWith(compareByDescending<OverlayBox> {
      it.text.length.coerceAtMost(135) + if (it.rect.cx in .18.. .82) 28 else 0
    }.thenBy { it.id })
    for (box in source) {
      bestPosition(box, placed)?.let { placed.add(box.copy(rect = it)) }
    }
    return placed.sortedBy { it.id }
  }

  private fun bestPosition(box: OverlayBox, placed: List<OverlayBox>): FrameRect? {
    val minimumWidth = Math.max(5, Math.min(24, box.text.length)) * FONT * .55 + FONT
    val preferred = maxOf(box.rect.w, minimumWidth / WIDTH, if (box.text.length > 45) .22 else .0)
    val maxWidth = maxOf(preferred, if (box.text.length > 80) .46 else .32).coerceAtMost(.94)
    val widths = listOf(preferred, preferred * 1.35, maxWidth).map { it.coerceAtMost(maxWidth) }.distinct()
    var best: FrameRect? = null
    var bestScore = Double.POSITIVE_INFINITY
    for (width in widths) {
      val height = estimateHeight(box.text, width)
      if (height > .68) continue
      val originX = (box.rect.cx - width / 2).coerceIn(GAP, 1.0 - width - GAP)
      val originY = box.rect.y.coerceIn(GAP, 1.0 - height - GAP)
      val xs = listOf(originX) + placed.flatMap { listOf(it.rect.x - width - GAP, it.rect.right + GAP) }
      val ys = listOf(originY) + placed.flatMap { listOf(it.rect.y - height - GAP, it.rect.bottom + GAP) }
      for (x in xs) for (y in ys) {
        if (x < GAP - 1e-9 || y < GAP - 1e-9 || x + width > 1 - GAP + 1e-9 || y + height > 1 - GAP + 1e-9) continue
        val displacement = abs(x + width / 2 - box.rect.cx) + abs(y - box.rect.y) * 1.25
        if (displacement > .31) continue
        val score = displacement + height * .30 + maxOf(0.0, width - preferred) * .09
        if (score >= bestScore) continue
        val candidate = FrameRect(x, y, width, height)
        if (placed.any { overlaps(candidate, it.rect) }) continue
        bestScore = score
        best = candidate
      }
    }
    return best
  }

  private fun overlaps(a: FrameRect, b: FrameRect) =
    a.x < b.right + GAP - 1e-9 && a.right + GAP > b.x + 1e-9 &&
      a.y < b.bottom + GAP - 1e-9 && a.bottom + GAP > b.y + 1e-9

  private fun estimateHeight(text: String, width: Double): Double {
    val capacity = (width * WIDTH - FONT * .6) / (FONT * .59)
    var lines = 1
    var used = 0.0
    for (word in text.split(wordBoundaries)) {
      if (word.length > capacity) {
        if (used > 0) lines++
        val wrapped = ceil(word.length / capacity).toInt()
        lines += wrapped - 1
        used = word.length - (wrapped - 1) * capacity
      } else if (used + word.length + 1 > capacity) {
        lines++
        used = word.length.toDouble()
      } else used += word.length + 1
    }
    return ((lines * 1.2 + .32) * FONT + 3) / HEIGHT
  }
}
