package expo.modules.ps5translator

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.util.Base64
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.TimeUnit

/**
 * Device-local OCR engine. Accepts a JPEG/PNG frame (base64), returns text blocks
 * with screen-space rectangles. The caller owns MediaProjection consent/capture.
 */
internal object ScreenTextRecognizer {
  private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

  fun recognize(base64Frame: String): ArrayList<android.os.Bundle> {
    val encoded = base64Frame.substringAfter("base64,", base64Frame)
    val bytes = Base64.decode(encoded, Base64.DEFAULT)
    require(bytes.size <= 12 * 1024 * 1024) { "Frame exceeds 12 MB" }
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
      ?: throw IllegalArgumentException("Invalid image frame")
    try {
      val image = InputImage.fromBitmap(bitmap, 0)
      val result = Tasks.await(recognizer.process(image), 15, TimeUnit.SECONDS)
      return ArrayList(result.textBlocks.map { block ->
        val rect = block.boundingBox ?: Rect()
        android.os.Bundle().apply {
          putString("text", block.text)
          putInt("left", rect.left)
          putInt("top", rect.top)
          putInt("right", rect.right)
          putInt("bottom", rect.bottom)
          putInt("frameWidth", bitmap.width)
          putInt("frameHeight", bitmap.height)
        }
      })
    } finally {
      bitmap.recycle()
    }
  }
}
