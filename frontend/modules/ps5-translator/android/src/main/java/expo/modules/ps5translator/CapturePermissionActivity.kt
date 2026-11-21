package expo.modules.ps5translator

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.core.content.ContextCompat

/**
 * Transparent consent activity; never caches or reuses MediaProjection tokens.
 */
class CapturePermissionActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    if (savedInstanceState == null) {
      val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
      @Suppress("DEPRECATION")
      startActivityForResult(manager.createScreenCaptureIntent(), 4411)
    }
  }

  @Deprecated("Android activity result callback for system screen capture permission")
  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    if (requestCode == 4411 && resultCode == RESULT_OK && data != null) {
      val service = Intent(this, ScreenCaptureService::class.java)
        .putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
        .putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
      ContextCompat.startForegroundService(this, service)
    }
    finish()
  }
}
