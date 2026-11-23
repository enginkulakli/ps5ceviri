package expo.modules.ps5translator.core

internal data class DetectedText(val text: String, val rect: FrameRect)

internal class OverlayTracker {
  internal data class Item(val id: Long, val text: String, val rect: FrameRect, val seenAt: Long)
  private var nextId = 0L
  private val items = LinkedHashMap<Long, Item>()

  fun observe(blocks: List<DetectedText>, now: Long, ttl: Long) {
    alive(now, ttl)
    val candidates = items.values.toMutableList()
    for (block in blocks.take(24)) {
      val exact = candidates.filter { similarOcr(it.text, block.text) && it.rect.distance(block.rect) < .19 }
        .minByOrNull { it.rect.distance(block.rect) }
      val replaced = exact ?: candidates.filter {
        it.rect.distance(block.rect) < .04 &&
          (it.rect.containsCenter(block.rect) || block.rect.containsCenter(it.rect))
      }.minByOrNull { it.rect.distance(block.rect) }
      if (replaced != null) candidates.remove(replaced)
      val id = replaced?.id ?: ++nextId
      items[id] = Item(id, exact?.text ?: block.text,
        replaced?.rect?.follow(block.rect) ?: block.rect, now)
    }
    val excess = items.size - 24
    if (excess > 0) items.values.sortedBy { it.seenAt }.take(excess).forEach { items.remove(it.id) }
  }

  fun alive(now: Long, ttl: Long): List<Item> {
    items.values.removeAll { now - it.seenAt >= ttl }
    return items.values.toList()
  }

  fun clear() = items.clear()
}
