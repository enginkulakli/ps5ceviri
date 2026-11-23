package expo.modules.ps5translator

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import expo.modules.kotlin.Promise
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Android's document picker grants access only to the document chosen by the user. */
class ArchiveTransferActivity : Activity() {
  private val token get() = intent.getStringExtra("request")

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val transfer = pending.get()
    if (transfer == null || transfer.token != token) { finish(); return }
    if (savedInstanceState != null) return
    val chooser = Intent(if (transfer.enabledExport) Intent.ACTION_CREATE_DOCUMENT else Intent.ACTION_OPEN_DOCUMENT)
      .setType(if (transfer.enabledExport) "application/json" else "*/*")
      .addCategory(Intent.CATEGORY_OPENABLE)
    if (transfer.enabledExport) chooser.putExtra(Intent.EXTRA_TITLE, "PS5-Cevirmen-Arsiv-v1.json")
    try {
      @Suppress("DEPRECATION")
      startActivityForResult(chooser, 1)
    } catch (error: Exception) {
      reject(transfer, "Android dosya seçicisi açılamadı.")
    }
  }

  @Deprecated("Android document picker result")
  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    val transfer = pending.get()?.takeIf { it.token == token } ?: return
    if (requestCode != 1) return
    val uri = data?.data
    if (resultCode != RESULT_OK || uri == null) {
      if (pending.compareAndSet(transfer, null)) transfer.promise.resolve("{\"cancelled\":true}")
      finish()
      return
    }
    val app = applicationContext
    val db = TranslationArchive.get(app)
    val executor = if (transfer.enabledExport) db.reads else db.writes
    executor.execute {
      try {
        val result = if (transfer.enabledExport) {
          val stream = app.contentResolver.openOutputStream(uri) ?: error("Dosya açılamadı.")
          stream.use { db.exportArchive(transfer.game, it) }
          "{\"exported\":true}"
        } else {
          val stream = app.contentResolver.openInputStream(uri) ?: error("Dosya açılamadı.")
          stream.use { db.importArchive(it) }.toString()
        }
        if (pending.compareAndSet(transfer, null)) transfer.promise.resolve(result)
        runOnUiThread { finish() }
      } catch (error: Exception) {
        runOnUiThread { reject(transfer, if (transfer.enabledExport) "Yedek yazılamadı." else "Arşiv dosyası geçersiz veya okunamadı.") }
      }
    }
  }

  private fun reject(transfer: Transfer, message: String) {
    if (pending.compareAndSet(transfer, null)) transfer.promise.reject("ERR_ARCHIVE_FILE", message, null)
    finish()
  }

  private data class Transfer(val token: String, val enabledExport: Boolean, val game: String?, val promise: Promise)

  companion object {
    private val pending = AtomicReference<Transfer?>()

    internal fun open(context: Context, enabledExport: Boolean, game: String?, promise: Promise) {
      val transfer = Transfer(UUID.randomUUID().toString(), enabledExport, game, promise)
      check(pending.compareAndSet(null, transfer)) { "Bir arşiv dosyası işlemi zaten açık." }
      try {
        context.startActivity(Intent(context, ArchiveTransferActivity::class.java)
          .putExtra("request", transfer.token).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      } catch (error: Exception) {
        pending.compareAndSet(transfer, null)
        throw error
      }
    }
  }
}
