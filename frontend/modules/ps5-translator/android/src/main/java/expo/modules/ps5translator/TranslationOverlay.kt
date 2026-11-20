package expo.modules.ps5translator

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/**
 * Non-interactive floating translation caption over other apps.
 * This is a first functional overlay; positioned multi-block replacement is a later refinement.
 */
internal object TranslationOverlay {
  private var view: TextView? = null
  private var manager: WindowManager? = null

  fun show(context: Context, text: String) {
    if (!Settings.canDrawOverlays(context)) throw IllegalStateException("Overlay permission required")
    val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    val old = view
    if (text.isBlank()) {
      if (old != null) runCatching { wm.removeView(old) }
      view = null
      return
    }
    if (old != null) {
      old.text = text
      return
    }
    val label = TextView(context).apply {
      this.text = text
      setTextColor(Color.WHITE)
      setBackgroundColor(Color.argb(215, 10, 10, 10))
      textSize = 19f
      gravity = Gravity.CENTER
      setPadding(20, 12, 20, 12)
      maxLines = 5
    }
    val params = WindowManager.LayoutParams(
      WindowManager.LayoutParams.MATCH_PARENT,
      WindowManager.LayoutParams.WRAP_CONTENT,
      if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
      else WindowManager.LayoutParams.TYPE_PHONE,
      WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_SECURE,
      PixelFormat.TRANSLUCENT
    )
    params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
    params.y = 110
    wm.addView(label, params)
    manager = wm
    view = label
  }
}
