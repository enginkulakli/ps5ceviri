package expo.modules.ps5translator

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import androidx.annotation.Keep
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Keep
internal object RemotePlayNative {
  init { System.loadLibrary("ps5-remote-play") }

  external fun register(host: String, account: ByteArray, pin: Int): ByteArray?
  external fun start(host: String, keys: ByteArray, receiver: Receiver): Long
  external fun stop(handle: Long)

  @Keep
  interface Receiver {
    fun onVideo(data: ByteBuffer): Boolean
    fun onEvent(code: Int, message: String?)
  }
}

internal fun isConsoleIpv4(host: String): Boolean {
  val parts = host.split('.')
  return parts.size == 4 && parts.all {
    it.isNotEmpty() && it.length <= 3 && it.all { char -> char in '0'..'9' } &&
      (it.length == 1 || it[0] != '0') && it.toInt() <= 255
  } && parts[0].toInt() in 1..223 && parts[0] != "127"
}

internal object ConsoleCredentials {
  private const val ALIAS = "ps5-remote-play-pairing-v1"
  private const val IV_BYTES = 12
  private const val KEY_BYTES = 32

  @Synchronized
  fun register(context: Context, host: String, accountId: String, pin: String) {
    require(isConsoleIpv4(host)) { "Geçerli bir PS5 IPv4 adresi girin." }
    require(pin.matches(Regex("[0-9]{8}"))) { "PIN 8 haneli olmalıdır." }
    val account = runCatching { Base64.decode(accountId.trim(), Base64.DEFAULT) }.getOrNull()
    require(account != null && account.size == 8) { "PSN Account ID, Base64 biçiminde 8 bayt olmalıdır." }
    val keys = try {
      RemotePlayNative.register(host, account, pin.toInt())
        ?: error("PS5 cihaz kaydını reddetti veya yanıt vermedi. Remote Play ayarını, hesabı ve eşleştirme PIN'ini kontrol edin.")
    } finally {
      account.fill(0)
    }
    try {
      check(keys.size == KEY_BYTES)
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
      cipher.updateAAD(host.toByteArray(Charsets.US_ASCII))
      val ciphertext = cipher.doFinal(keys)
      check(cipher.iv.size == IV_BYTES)
      val file = file(context, host)
      val output = file.startWrite()
      try {
        output.write(cipher.iv)
        output.write(ciphertext)
        file.finishWrite(output)
      } catch (e: Exception) {
        file.failWrite(output)
        throw e
      }
    } finally {
      keys.fill(0)
    }
  }

  @Synchronized
  fun read(context: Context, host: String): ByteArray? {
    val file = file(context, host)
    if (!file.baseFile.exists()) return null
    val stored = file.readFully()
    check(stored.size == IV_BYTES + KEY_BYTES + 16) { "PS5 kaydı okunamadı. Cihazı yeniden eşleştirin." }
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(128, stored, 0, IV_BYTES))
    cipher.updateAAD(host.toByteArray(Charsets.US_ASCII))
    return cipher.doFinal(stored, IV_BYTES, stored.size - IV_BYTES)
  }

  @Synchronized
  fun remove(context: Context, host: String) { file(context, host).delete() }

  private fun file(context: Context, host: String): AtomicFile {
    require(isConsoleIpv4(host)) { "Geçersiz PS5 IPv4 adresi." }
    return AtomicFile(File(context.noBackupFilesDir, "remote-play-$host"))
  }

  private fun key(create: Boolean): SecretKey {
    val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
    check(create) { "PS5 kaydının anahtarı bulunamadı. Cihazı yeniden eşleştirin." }
    return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
      init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        .build())
      generateKey()
    }
  }
}
