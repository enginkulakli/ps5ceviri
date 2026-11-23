package expo.modules.ps5translator.core

internal data class DetectedText(val text: String, val rect: FrameRect)

internal class OverlayTracker {
  internal data class Item(val id: Long, val text: String, val rect: FrameRect, val seenAt: Long)
  private var nextId = 0L
  private var latestObservation = 0L
  private val items = LinkedHashMap<Long, Item>()

  fun observe(blocks: List<DetectedText>, now: Long, ttl: Long, sceneChanged: Boolean = false) {
    alive(now, ttl)
    latestObservation = now
    val candidates = items.values.toMutableList()
    val incoming = blocks.sortedByDescending { it.text.length }.take(24)
    for (block in incoming) {
      val key = memoryText(block.text)
      val exact = candidates.filter { memoryText(it.text) == key && it.rect.distance(block.rect) < .24 }
        .minByOrNull { it.rect.distance(block.rect) }
      val replaced = exact ?: candidates.filter {
        it.rect.overlap(block.rect) > .48 || (it.rect.distance(block.rect) < .04 && it.rect.containsCenter(block.rect))
      }.minByOrNull { it.rect.distance(block.rect) }
      if (replaced != null) candidates.remove(replaced)
      val id = replaced?.id ?: ++nextId
      items[id] = Item(id, exact?.text ?: block.text, replaced?.rect?.follow(block.rect) ?: block.rect, now)
    }
    for (old in candidates) {
      if (sceneChanged || incoming.any { it.rect.overlap(old.rect) > .45 ||
          (memoryText(it.text) == memoryText(old.text) && it.rect.distance(old.rect) < .29) }) {
        items.remove(old.id)
      }
    }
    val excess = items.size - 24
    if (excess > 0) items.values.sortedBy { it.seenAt }.take(excess).forEach { items.remove(it.id) }
  }

  fun alive(now: Long, ttl: Long): List<Item> {
    items.values.removeAll { now - it.seenAt >= ttl }
    return items.values.toList()
  }

  fun seenInLatestFrame(item: Item) = item.seenAt == latestObservation
  fun clear() = items.clear()
}
