package expo.modules.ps5translator

import expo.modules.ps5translator.core.FrameRect
import expo.modules.ps5translator.core.OverlayBox
import org.json.JSONArray
import org.json.JSONObject

/** Opt-in, process-local snapshots. Never writes recognized text to disk or logcat. */
internal object ScreenOcrDiagnostics {
  @Volatile var enabled = false
    private set
  private val frames = ArrayDeque<String>(3)
  private var published = "{}"

  @Synchronized fun configure(enabled: Boolean) {
    this.enabled = enabled
    if (!enabled) {
      frames.clear()
      published = "{}"
    }
  }

  fun begin(w: Int, h: Int, ocrW: Int, ocrH: Int): JSONObject? = if (!enabled) null else JSONObject()
    .put("at", System.currentTimeMillis()).put("width", w).put("height", h)
    .put("ocrWidth", ocrW).put("ocrHeight", ocrH).put("exclusionsApplied", false)

  fun text(value: String, bounds: FrameRect?) = JSONObject().put("text", value).put("bounds", bounds?.let {
    JSONObject().put("x", it.x).put("y", it.y).put("w", it.w).put("h", it.h)
  } ?: JSONObject.NULL)

  @Synchronized fun record(frame: JSONObject) {
    if (!enabled) return
    if (frames.size == 3) frames.removeFirst()
    frames.addLast(frame.toString())
  }

  @Synchronized fun published(boxes: List<OverlayBox>, clientCount: Int) {
    if (!enabled) return
    published = JSONObject().put("at", System.currentTimeMillis()).put("clients", clientCount)
      .put("boxes", JSONArray().also { rows -> boxes.forEach { box ->
        rows.put(text(box.text, box.rect).put("id", box.id))
      } }).toString()
  }

  @Synchronized fun snapshot(): String = JSONObject()
    .put("enabled", enabled).put("sent", JSONObject(published))
    .put("frames", JSONArray().also { rows -> frames.forEach { rows.put(JSONObject(it)) } }).toString(2)
}
