package expo.modules.ps5translator.core

internal data class FrameRect(val x: Double, val y: Double, val w: Double, val h: Double) {
  val cx get() = x + w / 2
  val cy get() = y + h / 2

  fun containsCenter(other: FrameRect) =
    other.cx in x..(x + w) && other.cy in y..(y + h)

  fun distance(other: FrameRect) = kotlin.math.hypot(cx - other.cx, cy - other.cy)

  fun follow(other: FrameRect): FrameRect {
    if (distance(other) < .006 && kotlin.math.abs(w - other.w) < .006 &&
      kotlin.math.abs(h - other.h) < .006) return this
    return FrameRect((x + other.x) / 2, (y + other.y) / 2, (w + other.w) / 2, (h + other.h) / 2)
  }
}

internal data class OverlayBox(val id: Long, val text: String, val rect: FrameRect, val ttlMs: Long)

internal data class OverlayMessage(val frameWidth: Int, val frameHeight: Int, val blocks: List<OverlayBox>) {
  fun encode(): String = buildString {
    append("{\"type\":\"overlay\",\"frameWidth\":").append(frameWidth)
    append(",\"frameHeight\":").append(frameHeight).append(",\"blocks\":[")
    blocks.forEachIndexed { index, block ->
      if (index > 0) append(',')
      append("{\"id\":\"").append(block.id).append("\",\"text\":")
      SubtitleMessage.appendQuoted(this, block.text)
      append(",\"x\":").append(block.rect.x).append(",\"y\":").append(block.rect.y)
      append(",\"w\":").append(block.rect.w).append(",\"h\":").append(block.rect.h)
      append(",\"ttlMs\":").append(block.ttlMs).append('}')
    }
    append("]}")
  }
}
