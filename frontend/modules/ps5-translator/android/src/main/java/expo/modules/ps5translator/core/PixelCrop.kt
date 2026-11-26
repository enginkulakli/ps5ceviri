package expo.modules.ps5translator.core

internal data class PixelCrop(val left: Int, val top: Int, val width: Int, val height: Int)

/** Coordinates relative to the visible decoder frame, before its crop offset. */
internal fun FrameRect.pixelCrop(frameWidth: Int, frameHeight: Int): PixelCrop {
  val left = (frameWidth * x).toInt().coerceIn(0, frameWidth - 2)
  val top = (frameHeight * y).toInt().coerceIn(0, frameHeight - 2)
  val width = minOf((frameWidth * w).toInt().coerceAtLeast(2), frameWidth - left) and -2
  val height = minOf((frameHeight * h).toInt().coerceAtLeast(2), frameHeight - top) and -2
  return PixelCrop(left, top, width, height)
}
