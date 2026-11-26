package expo.modules.ps5translator.core

internal data class OcrFrame(val nv21: ByteArray, val width: Int, val height: Int) {
  /** Scale only the luma used by OCR, leaving coordinates normalized to the original frame. */
  fun enlargeForText(): OcrFrame {
    val scale = minOf(1.5, 1920.0 / maxOf(width, height))
    if (scale <= 1) return this
    val w = (width * scale).toInt() and -2
    val h = (height * scale).toInt() and -2
    if (w <= width || h <= height) return this
    val output = ByteArray(w * h * 3 / 2)
    val xPositions = IntArray(w) { it * (width - 1) * 256 / (w - 1) }
    for (row in 0 until h) {
      val yPosition = row * (height - 1) * 256 / (h - 1)
      val upper = (yPosition / 256) * width
      val lower = minOf(upper + width, (height - 1) * width)
      val yWeight = yPosition % 256
      for (col in 0 until w) {
        val x = xPositions[col] / 256
        val next = minOf(x + 1, width - 1)
        val weight = xPositions[col] % 256
        val top = (nv21[upper + x].toInt() and 255) * (256 - weight) + (nv21[upper + next].toInt() and 255) * weight
        val bottom = (nv21[lower + x].toInt() and 255) * (256 - weight) + (nv21[lower + next].toInt() and 255) * weight
        output[row * w + col] = ((top * (256 - yWeight) + bottom * yWeight + 32768) / 65536).toByte()
      }
    }
    output.fill(128.toByte(), w * h)
    return OcrFrame(output, w, h)
  }
}
