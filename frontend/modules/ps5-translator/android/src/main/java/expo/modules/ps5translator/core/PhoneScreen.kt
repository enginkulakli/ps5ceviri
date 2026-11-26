package expo.modules.ps5translator.core

import kotlin.math.abs

/** A downsampled view of luma and local edges, independent of OCR and translation. */
internal class PhoneSignature private constructor(
  val width: Int, val height: Int, private val samples: IntArray,
) {
  fun compatible(other: PhoneSignature) = width == other.width && height == other.height

  fun changes(other: PhoneSignature): Set<Int> {
    if (!compatible(other)) return (0 until TILE_COLS * TILE_ROWS).toSet()
    val counts = IntArray(TILE_COLS * TILE_ROWS)
    val edges = IntArray(counts.size)
    for (row in 1 until ROWS) for (col in 1 until COLS) {
      val i = row * COLS + col
      val a = samples[i]
      val b = other.samples[i]
      if (abs(a - b) < 16) continue
      val tile = row / TILE_H * TILE_COLS + col / TILE_W
      counts[tile]++
      val edgeChange = abs((a - samples[i - 1]) - (b - other.samples[i - 1])) +
        abs((a - samples[i - COLS]) - (b - other.samples[i - COLS]))
      if (edgeChange >= 26) edges[tile]++
    }
    return counts.indices.filterTo(linkedSetOf()) { counts[it] >= 5 && edges[it] >= 3 }
  }

  fun patch(current: PhoneSignature, regions: List<FrameRect>): PhoneSignature {
    if (!compatible(current)) return current
    val updated = samples.copyOf()
    for (index in updated.indices) {
      val x = (index % COLS + .5) / COLS
      val y = (index / COLS + .5) / ROWS
      if (regions.any { x in it.x..it.right && y in it.y..it.bottom }) updated[index] = current.samples[index]
    }
    return PhoneSignature(width, height, updated)
  }

  fun regionKey(rect: FrameRect): Long {
    val left = (rect.x * COLS).toInt().coerceIn(0, COLS - 1)
    val right = (rect.right * COLS).toInt().coerceIn(left, COLS - 1)
    val top = (rect.y * ROWS).toInt().coerceIn(0, ROWS - 1)
    val bottom = (rect.bottom * ROWS).toInt().coerceIn(top, ROWS - 1)
    var hash = 1L
    for (y in top..bottom) for (x in left..right) hash = hash * 31 + samples[y * COLS + x] / 16
    return hash
  }

  companion object {
    private const val TILE_COLS = 12
    private const val TILE_ROWS = 9
    private const val TILE_W = 8
    private const val TILE_H = 6
    private const val COLS = TILE_COLS * TILE_W
    private const val ROWS = TILE_ROWS * TILE_H

    fun sample(width: Int, height: Int, luma: (Int, Int) -> Int): PhoneSignature {
      val grid = IntArray(COLS * ROWS) { i ->
        val x = i % COLS
        val y = i / COLS
        val a = luma((x * 4 + 1) * width / (COLS * 4), (y * 4 + 1) * height / (ROWS * 4))
        val b = luma((x * 4 + 3) * width / (COLS * 4), (y * 4 + 3) * height / (ROWS * 4))
        (a + b) / 2
      }
      return PhoneSignature(width, height, grid)
    }

    fun tileBounds(index: Int) = FrameRect(
      (index % TILE_COLS).toDouble() / TILE_COLS, (index / TILE_COLS).toDouble() / TILE_ROWS,
      1.0 / TILE_COLS, 1.0 / TILE_ROWS,
    )
  }
}

/** Phone-only scheduling and message identity. The foreground service owns this state. */
internal class PhoneScreen {
  data class Scan(val image: PhoneSignature, val regions: List<FrameRect>) {
    val full get() = regions.isEmpty()
    fun covers(rect: FrameRect) = full || regions.any { area ->
      rect.cx in area.x..area.right && rect.cy in area.y..area.bottom
    }
  }

  data class Message(
    val id: Long, val source: String, val key: String, val rect: FrameRect, val imageKey: Long,
    val verifiedAt: Long, val translated: String? = null,
  )

  private data class Match(val index: Int, val message: Message, val distance: Double)
  private var reference: PhoneSignature? = null
  private var previousProbe: PhoneSignature? = null
  private var lastFullAt = Long.MIN_VALUE
  private var unreliableSince: Long? = null
  private var nextId = 0L
  private var messages = emptyList<Message>()

  fun visible() = messages

  fun inspect(current: PhoneSignature, now: Long): Scan? {
    val probe = previousProbe
    previousProbe = current
    val before = reference
    if (before == null || !current.compatible(before)) return Scan(current, emptyList())
    val dirty = current.changes(before)
    if (dirty.isEmpty()) {
      unreliableSince = null
      messages = messages.map { it.copy(verifiedAt = now) }
      return null
    }
    // Require a settled change across two inexpensive probes. Motion outside a
    // changed conversation does not veto a settled message region.
    val moving = if (probe == null) dirty else current.changes(probe)
    val settled = dirty - moving
    var movedMessages = 0
    messages = messages.map { message ->
      if (current.regionKey(message.rect) == message.imageKey) message.copy(verifiedAt = now)
      else { movedMessages++; message }
    }
    val unstable = dirty.size >= 34 && moving.size >= 34 && (messages.isEmpty() || movedMessages > 0)
    unreliableSince = if (unstable) unreliableSince ?: now else null
    val fullAllowed = lastFullAt == Long.MIN_VALUE || now - lastFullAt >= FULL_SCAN_SPACING_MS
    if (fullAllowed && unreliableSince?.let { now - it >= FULL_SCAN_SPACING_MS } == true) {
      return Scan(current, emptyList())
    }
    if (settled.isEmpty()) return null
    val regions = mutableListOf<FrameRect>()
    for (tile in settled) {
      var region = PhoneSignature.tileBounds(tile).expand(.047, .019)
      for (message in messages) {
        if (region.intersects(message.rect)) region = region.union(message.rect.expand(.016, .012))
      }
      while (true) {
        val other = regions.firstOrNull(region::intersects) ?: break
        region = region.union(other)
        regions.remove(other)
      }
      regions.add(region)
    }
    val full = settled.size >= 34 || regions.size > 3 || regions.sumOf { it.w * it.h } > .46
    return if (!full) Scan(current, regions)
    else if (fullAllowed) Scan(current, emptyList())
    else null
  }

  fun observe(scan: Scan, detections: List<DetectedText>, now: Long) {
    val (unaffected, reconsider) = messages.partition { !scan.covers(it.rect) }
    val blocks = detections.distinctBy { memoryText(it.text) to it.rect }.take(MAX_MESSAGES)
    val remaining = reconsider.toMutableSet()
    val matched = mutableMapOf<Int, Message>()
    blocks.flatMapIndexed { index, block ->
      val key = memoryText(block.text)
      reconsider.mapNotNull { old ->
        val distance = old.rect.distance(block.rect)
        val sameLane = abs(old.rect.cx - block.rect.cx) <= maxOf(.045, minOf(old.rect.w, block.rect.w) * .30)
        val samePixels = old.imageKey == scan.image.regionKey(old.rect)
        val visualMisread = samePixels && distance < .025 && old.key.replace("rn", "m") == key.replace("rn", "m")
        if ((old.key == key && (distance < .14 || sameLane)) || visualMisread) {
          Match(index, old, distance + if (old.key == key) 0.0 else .2)
        } else null
      }
    }.sortedBy { it.distance }.forEach { match ->
      if (match.index !in matched && remaining.remove(match.message)) matched[match.index] = match.message
    }

    val next = unaffected.toMutableList()
    for ((index, block) in blocks.withIndex()) {
      if (next.size == MAX_MESSAGES) break
      val old = matched[index]
      val rect = old?.rect?.follow(block.rect) ?: block.rect
      val imageKey = scan.image.regionKey(rect)
      next.add(old?.copy(rect = rect, imageKey = imageKey, verifiedAt = now)
        ?: Message(++nextId, block.text, memoryText(block.text), rect, imageKey, now))
    }
    // OCR can miss an unchanged message sharing a crop with a new message.
    for (old in remaining) {
      if (next.size == MAX_MESSAGES) break
      if (scan.image.regionKey(old.rect) == old.imageKey && blocks.none { it.rect.overlap(old.rect) > .55 }) {
        next.add(old.copy(verifiedAt = now))
      }
    }
    messages = next.sortedBy { it.id }
    reference = if (scan.full) scan.image else reference?.patch(scan.image, scan.regions) ?: scan.image
    if (scan.full) {
      lastFullAt = now
      unreliableSince = null
    }
  }

  fun applyTranslations(current: (String) -> String?) {
    messages = messages.map { item ->
      if (item.translated == null) item.copy(translated = current(item.key)) else item
    }
  }

  fun clearTranslations() {
    messages = messages.map { it.copy(translated = null) }
  }

  fun clear() {
    messages = emptyList()
    reference = null
    previousProbe = null
    lastFullAt = Long.MIN_VALUE
    unreliableSince = null
  }

  companion object {
    const val PROBE_INTERVAL_MS = 450L
    const val FULL_SCAN_SPACING_MS = 3500L
    // Tizen v2/v3 clamps its own expiry to 15 seconds.
    const val LEASE_MS = 14_000L
    const val RENEW_AFTER_MS = 10_500L
    private const val MAX_MESSAGES = 24
  }
}

private fun FrameRect.expand(xPad: Double, yPad: Double): FrameRect {
  val left = (x - xPad).coerceAtLeast(0.0)
  val top = (y - yPad).coerceAtLeast(0.0)
  return FrameRect(left, top, (right + xPad).coerceAtMost(1.0) - left, (bottom + yPad).coerceAtMost(1.0) - top)
}

private fun FrameRect.intersects(other: FrameRect) = x < other.right && right > other.x &&
  y < other.bottom && bottom > other.y
