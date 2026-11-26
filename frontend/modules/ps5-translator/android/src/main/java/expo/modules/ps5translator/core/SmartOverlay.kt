package expo.modules.ps5translator.core

import kotlin.math.abs
import kotlin.math.ceil

internal data class OcrLine(val text: String, val rect: FrameRect, val group: Int)

private val controls = Regex("(?:scroll|set waypoint|back|select|return|navigate)(?:\\s+[a-z0-9])?", RegexOption.IGNORE_CASE)
private val badge = Regex("(?:remote play (?:connection|connected|in progress)(?:[ .…].*)?|uzaktan oynama ba(?:ğ|g)lan(?:d[ıi]|t[ıi]).*)", RegexOption.IGNORE_CASE)
private val menu = Regex("(?:[ndopil0-9|()■□◯]{1,3}\\s+)?(map|game|store|help|photo studio|settings)(?:\\s+[o0|])?", RegexOption.IGNORE_CASE)
private val roman = Regex("[IVXLCDM]{2,5}")

/** Stable normalization preserves words, punctuation and all numbers outside known menu icons. */
internal fun memoryText(value: String) = cleanOcr(value)

internal object SmartOcr {
  private data class Paragraph(val block: DetectedText, val last: OcrLine)

  fun group(lines: List<OcrLine>, rejected: ((OcrLine, String) -> Unit)? = null): List<DetectedText> {
    val normalized = lines.take(160).mapNotNull { line ->
      cleanLine(line).also { if (it == null) rejected?.invoke(line, "hud_or_noise") }
    }.sortedBy { it.rect.y }
    if (rejected != null) lines.drop(160).forEach { rejected(it, "line_limit") }
    val groupSizes = normalized.groupingBy { it.group }.eachCount()
    val distinct = mutableListOf<OcrLine>()
    for (line in normalized) {
      val duplicate = distinct.indexOfFirst { it.text == line.text && it.rect.overlap(line.rect) > .55 }
      if (duplicate < 0) distinct.add(line)
      else if (groupSizes.getValue(line.group) > groupSizes.getValue(distinct[duplicate].group)) {
        rejected?.invoke(distinct[duplicate], "duplicate")
        distinct[duplicate] = line
      } else rejected?.invoke(line, "duplicate")
    }
    val paragraphs = mutableListOf<Paragraph>()
    for (line in distinct.sortedBy { it.rect.y }) {
      val best = paragraphs.indices.filter { i ->
        val (block, previous) = paragraphs[i]
        val gap = line.rect.y - previous.rect.bottom
        val lineHeight = maxOf(previous.rect.h, line.rect.h)
        val shared = minOf(line.rect.right, previous.rect.right) - maxOf(line.rect.x, previous.rect.x)
        val edgeDistance = minOf(abs(previous.rect.x - line.rect.x), abs(previous.rect.right - line.rect.right))
        val aligned = edgeDistance <= minOf(.018, lineHeight * .75) &&
          shared >= minOf(line.rect.w, previous.rect.w) * .70 &&
          minOf(previous.rect.h, line.rect.h) >= lineHeight * .60
        val sameBlock = previous.group == line.group &&
          (previous.text.count { it == ' ' } >= 2 || line.text.first().isLowerCase())
        val continuation = previous.text.count { it == ' ' } >= 2 &&
          previous.text.last() !in ".!?:…" && (line.text.first().isLowerCase() || previous.text.endsWith('-'))
        val sentenceBoundary = previous.text.last() in ".!?:…" && line.text.first().isUpperCase()
        val wrapGap = if (sameBlock && !sentenceBoundary) .52 else .32
        aligned && gap in -.004..(lineHeight * wrapGap) &&
          (sameBlock || continuation) && block.text.length + line.text.length <= 420
      }.minByOrNull { abs(paragraphs[it].last.rect.bottom - line.rect.y) }
      if (best == null) paragraphs.add(Paragraph(DetectedText(line.text, line.rect), line))
      else {
        val previous = paragraphs[best].block
        val joined = cleanOcr(previous.text + "\n" + line.text)
        paragraphs[best] = Paragraph(DetectedText(joined, previous.rect.union(line.rect)), line)
      }
    }
    if (rejected != null) paragraphs.drop(48).forEach { rejected(it.last, "group_limit") }
    return paragraphs.map { it.block }.take(48)
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
  private const val GAP = .003
  private const val MAX_NUDGE = .036
  private const val MAX_WIRE_WIDTH = .22
  private const val VIEW_WIDTH = 1920.0
  private const val VIEW_HEIGHT = 1080.0
  private const val DEFAULT_FONT = 34 * .72
  private val fontSizes = listOf(26, 34, 46, 58).map { it * .72 }
  private val wordBoundaries = Regex("\\s+")

  private data class Placement(val box: OverlayBox, val painted: List<FrameRect>)

  fun arrange(boxes: List<OverlayBox>, retainUnplaced: Boolean = false): List<OverlayBox> {
    val placed = mutableListOf<Placement>()
    val fallback = mutableListOf<OverlayBox>()
    // Keep message content when its original area cannot also fit nearby labels.
    val source = boxes.sortedWith(compareByDescending<OverlayBox> { it.text.length > 60 }
      .thenBy { it.rect.y }.thenBy { it.rect.x }.thenBy { it.id })
    for (box in source) {
      val placement = place(box, placed)
      if (placement != null) placed.add(placement)
      else if (retainUnplaced) fallback.add(place(box, emptyList())?.box ?: anchor(box))
    }
    return (placed.map { it.box } + fallback).sortedBy { it.id }
  }

  /** Preserve V3.2's source-centred reservations, and lock already placed messages. */
  class PhoneLayout {
    private data class Entry(val input: OverlayBox, val placement: Placement?)
    private val pinned = mutableMapOf<Long, Entry>()

    fun arrange(boxes: List<OverlayBox>): List<OverlayBox> {
      val inputs = boxes.associateBy { it.id }
      pinned.entries.removeAll { (id, entry) -> inputs[id] != entry.input }
      val placed = pinned.values.mapNotNull { it.placement }.toMutableList()
      val fresh = boxes.filter { it.id !in pinned }
        .sortedWith(compareByDescending<OverlayBox> { it.text.length > 60 }.thenBy { it.rect.y }.thenBy { it.rect.x }.thenBy { it.id })
      for (box in fresh) {
        val placement = place(box, placed)
        if (placement != null) placed.add(placement)
        pinned[box.id] = Entry(box, placement)
      }
      return boxes.map { box ->
        // Forward the original anchor when there is no V3.2 slot. The installed
        // TV renderer can wrap or compact without controlling phone identity.
        pinned.getValue(box.id).placement?.box ?: anchor(box)
      }.sortedBy { it.id }
    }

    fun clear() = pinned.clear()
  }

  private fun anchor(box: OverlayBox): OverlayBox {
    val w = box.rect.w.coerceIn(.025, MAX_WIRE_WIDTH)
    val h = box.rect.h.coerceIn(.01, .32)
    return box.copy(rect = FrameRect(
      (box.rect.cx - w / 2).coerceIn(0.0, 1 - w),
      box.rect.y.coerceIn(0.0, 1 - h), w, h,
    ))
  }

  private fun place(box: OverlayBox, placed: List<Placement>): Placement? {
    val glyphs = box.text.length.coerceAtMost(24)
    val naturalWidth = (glyphs * .55 + .6) * DEFAULT_FONT / VIEW_WIDTH
    val maxWidth = minOf(MAX_WIRE_WIDTH, naturalWidth + if (box.text.length > 24) .04 else .015)
    val width = maxOf(.016, naturalWidth, minOf(box.rect.w, maxWidth))
    val x = (box.rect.cx - width / 2).coerceIn(GAP, 1 - width - GAP)
    val center = x + width / 2
    val words = box.text.split(wordBoundaries)
    val paintedWidths = fontSizes.map { font ->
      maxOf(width, (glyphs.coerceAtLeast(5) * .55 + 1) * font / VIEW_WIDTH)
    }
    val heights = fontSizes.zip(paintedWidths).map { (font, paintedWidth) -> height(words, paintedWidth, font) }
    if (heights.last() > .32) return null
    val idealY = box.rect.y.coerceIn(GAP, 1 - heights.first() - GAP)
    val candidates = mutableSetOf(idealY)
    for (other in placed) {
      for (i in heights.indices) {
        candidates.add(other.painted[i].y - heights[i] - GAP)
        candidates.add(other.painted[i].bottom + GAP)
      }
    }
    for (y in candidates.sortedBy { abs(it - idealY) }) {
      if (abs(y - box.rect.y) > MAX_NUDGE || y !in GAP..(1 - GAP)) continue
      val painted = paintedWidths.indices.map { i ->
        val w = paintedWidths[i]
        val h = heights[i]
        FrameRect((center - w / 2).coerceIn(0.0, 1 - w), y.coerceAtMost(1 - h), w, h)
      }
      if (placed.any { other -> painted.indices.any { overlaps(painted[it], other.painted[it]) } }) continue
      // The client applies its own minimum width and wraps text. Its reservation is
      // intentionally kept out of the transmitted rectangle and backplate width.
      return Placement(box.copy(rect = FrameRect(x, y, width, minOf(heights[1], 1 - y))), painted)
    }
    return null
  }

  private fun overlaps(a: FrameRect, b: FrameRect) =
    a.x < b.right + GAP - 1e-8 && a.right + GAP > b.x + 1e-8 &&
      a.y < b.bottom + GAP - 1e-8 && a.bottom + GAP > b.y + 1e-8

  private fun height(words: List<String>, width: Double, font: Double): Double {
    val capacity = (width * VIEW_WIDTH - font * .6) / (font * .57)
    var lines = 1
    var used = 0.0
    for (word in words) {
      if (word.length > capacity) {
        if (used > 0) lines++
        val wrapped = ceil(word.length / capacity).toInt()
        lines += wrapped - 1
        used = word.length - (wrapped - 1) * capacity
      } else if (used > 0 && used + word.length + 1 > capacity) {
        lines++
        used = word.length.toDouble()
      } else used += word.length + if (used > 0) 1 else 0
    }
    return ((lines * 1.2 + .32) * font + 2) / VIEW_HEIGHT
  }
}
