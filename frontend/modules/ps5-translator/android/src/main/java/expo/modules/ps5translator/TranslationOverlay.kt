package expo.modules.ps5translator

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

/** Touch-through, screen-positioned translated text blocks. */
internal object TranslationOverlay {
  internal data class Block(
    val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int,
    val frameWidth: Int, val frameHeight: Int
  )

  private var root: FrameLayout? = null
  private var manager: WindowManager? = null

  fun show(context: Context, text: String) {
    if (text.isBlank()) {
      showBlocks(context, emptyList())
      return
    }
    // Backwards-compatible manual caption path.
    val metrics = context.resources.displayMetrics
    showBlocks(context, listOf(Block(text, 20, (metrics.heightPixels * .78f).toInt(),
      metrics.widthPixels - 20, (metrics.heightPixels * .9f).toInt(),
      metrics.widthPixels, metrics.heightPixels)))
  }

  fun showBlocks(context: Context, blocks: List<Block>) {
    if (!Settings.canDrawOverlays(context)) throw IllegalStateException("Overlay permission required")
    val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    if (blocks.isEmpty()) {
      root?.let { runCatching { (manager ?: wm).removeView(it) } }
      root = null
      manager = null
      return
    }
    val layer = root ?: FrameLayout(context).also { container ->
      val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
          WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
          WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
          WindowManager.LayoutParams.FLAG_SECURE,
        PixelFormat.TRANSLUCENT
      )
      params.gravity = Gravity.TOP or Gravity.START
      wm.addView(container, params)
      manager = wm
      root = container
    }
    layer.removeAllViews()
    val metrics = context.resources.displayMetrics
    val screenW = metrics.widthPixels
    val screenH = metrics.heightPixels
    val density = metrics.density
    for (block in blocks.take(32)) {
      if (block.text.isBlank() || block.frameWidth <= 0 || block.frameHeight <= 0) continue
      val scaleX = screenW.toFloat() / block.frameWidth
      val scaleY = screenH.toFloat() / block.frameHeight
      val x = (block.left * scaleX).toInt().coerceIn(0, screenW - 1)
      val y = (block.top * scaleY).toInt().coerceIn(0, screenH - 1)
      val width = ((block.right - block.left) * scaleX).toInt()
        .coerceAtLeast((90 * density).toInt()).coerceAtMost(screenW - x)
      val height = ((block.bottom - block.top) * scaleY).toInt()
        .coerceAtLeast((32 * density).toInt()).coerceAtMost(screenH - y)
      val label = TextView(context).apply {
        text = block.text
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.argb(235, 12, 12, 12))
        textSize = 14f
        gravity = Gravity.CENTER
        setPadding((4 * density).toInt(), 0, (4 * density).toInt(), 0)
        maxLines = 5
      }
      layer.addView(label, FrameLayout.LayoutParams(width, height).apply {
        leftMargin = x
        topMargin = y
      })
    }
  }
}
