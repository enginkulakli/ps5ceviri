package expo.modules.ps5translator

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URL
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.HttpsURLConnection

internal data class CloudLimits(val daily: Int = 10000, val monthly: Int = 100000)

internal object GoogleKeyStore {
  private const val ALIAS = "ps5ceviri-cloud-key-v1"
  @Volatile var status = "Bağlantı test edilmedi"
    private set

  @Synchronized
  fun save(context: Context, input: String) {
    val secret = input.trim()
    require(secret.matches(Regex("[A-Za-z0-9_-]{20,180}"))) { "Google Cloud API anahtarı biçimi geçersiz." }
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, key(true))
    val bytes = secret.toByteArray(Charsets.UTF_8)
    val file = file(context)
    val out = file.startWrite()
    try {
      out.write(cipher.iv)
      out.write(cipher.doFinal(bytes))
      file.finishWrite(out)
      status = "Bağlantı test edilmedi"
    } catch (error: Exception) {
      file.failWrite(out)
      throw error
    } finally { bytes.fill(0) }
  }

  @Synchronized
  fun read(context: Context): String? {
    val file = file(context)
    if (!file.baseFile.exists()) return null
    val data = file.readFully()
    check(data.size in 29..512)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, data, 0, 12))
    val bytes = cipher.doFinal(data, 12, data.size - 12)
    return try { String(bytes, Charsets.UTF_8) } finally { bytes.fill(0) }
  }

  @Synchronized
  fun remove(context: Context) {
    file(context).delete()
    val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    if (store.containsAlias(ALIAS)) store.deleteEntry(ALIAS)
    status = "Bağlantı test edilmedi"
  }

  fun connection(ok: Boolean, message: String = "") {
    status = if (ok) "Google Cloud bağlı" else message
  }

  private fun file(context: Context) = AtomicFile(File(context.noBackupFilesDir, "google-cloud-credential"))

  private fun key(create: Boolean): SecretKey {
    val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
    check(create) { "Google Cloud anahtarını yeniden kaydedin." }
    return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
      init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
        .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
      generateKey()
    }
  }
}

internal class GoogleCloudProvider(context: Context, private val limits: CloudLimits) : TranslationProvider {
  private val app = context.applicationContext
  @Volatile private var closed = false
  private val connections = ConcurrentHashMap.newKeySet<HttpsURLConnection>()

  override val available get() = runCatching { GoogleKeyStore.read(app) != null }.getOrDefault(false)

  override fun prepare(): Task<Void> = if (available) Tasks.forResult(null)
    else Tasks.forException(IllegalStateException(API_NOT_CONFIGURED))

  override fun translate(request: TranslationRequest): Task<String> = Tasks.call(executor) {
    val parts = request.glossary.split(request.text)
    val queries = parts.filter { !it.protected && it.text.any(Char::isLetter) }.map { it.text.trim() }.distinct()
    if (queries.isEmpty()) return@call parts.joinToString("") { it.text }
    val translations = send(queries)
    val bySource = queries.zip(translations).toMap()
    parts.joinToString("") { part ->
      if (part.protected || part.text.none(Char::isLetter)) part.text
      else part.text.takeWhile(Char::isWhitespace) + bySource.getValue(part.text.trim()) +
        part.text.takeLastWhile(Char::isWhitespace)
    }
  }

  fun test(): Task<String> = Tasks.call(executor) { send(listOf("Hello")).single() }

  private fun send(queries: List<String>): List<String> {
    check(!closed) { "Çeviri iptal edildi." }
    val key = try { GoogleKeyStore.read(app) } catch (_: Exception) {
      error("Google Cloud anahtarı okunamadı. Anahtarı yeniden kaydedin.")
    } ?: error(API_NOT_CONFIGURED)
    val payload = JSONObject().put("q", JSONArray(queries)).put("source", "en")
      .put("target", "tr").put("format", "text").toString().toByteArray(Charsets.UTF_8)
    val conn = URL(ENDPOINT).openConnection() as HttpsURLConnection
    connections.add(conn)
    try {
      check(!closed) { "Çeviri iptal edildi." }
      TranslationArchive.get(app).reserveCloudUsage(queries.sumOf { it.codePointCount(0, it.length) }, limits.daily, limits.monthly)
      conn.instanceFollowRedirects = false
      conn.connectTimeout = 3500
      conn.readTimeout = 5000
      conn.requestMethod = "POST"
      conn.doOutput = true
      conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
      conn.setRequestProperty("x-goog-api-key", key)
      conn.setFixedLengthStreamingMode(payload.size)
      conn.outputStream.use { it.write(payload) }
      val code = conn.responseCode
      if (code !in 200..299) error(cloudHttpError(code))
      val parsed = conn.inputStream.use { input ->
        val bytes = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
          val n = input.read(buffer)
          if (n < 0) break
          check(bytes.size() + n <= 1024 * 1024) { "Google Cloud yanıtı çok büyük." }
          bytes.write(buffer, 0, n)
        }
        JSONObject(bytes.toString("UTF-8")).getJSONObject("data").getJSONArray("translations")
      }
      check(parsed.length() == queries.size) { "Google Cloud eksik yanıt verdi." }
      val result = queries.indices.map { i -> decodeCloudText(parsed.getJSONObject(i).getString("translatedText")) }
      GoogleKeyStore.connection(true)
      return result
    } catch (e: Exception) {
      val message = when (e) {
        is IOException -> "Google Cloud bağlantısı kesildi veya zaman aşımına uğradı."
        is org.json.JSONException -> "Google Cloud yanıtı okunamadı."
        is IllegalStateException -> e.message ?: "Google Cloud isteği tamamlanamadı."
        else -> "Google Cloud isteği tamamlanamadı."
      }
      if (!closed) GoogleKeyStore.connection(false, message)
      throw IllegalStateException(message)
    } finally {
      connections.remove(conn)
      conn.disconnect()
    }
  }

  override fun close() {
    closed = true
    connections.forEach { it.disconnect() }
  }

  companion object {
    private const val ENDPOINT = "https://translation.googleapis.com/language/translate/v2"
    private val executor = Executors.newFixedThreadPool(2)
  }
}

internal fun cloudHttpError(code: Int): String = when (code) {
  400, 401 -> "Google Cloud anahtarı veya istek geçersiz."
  403 -> "Google Cloud erişimi reddedildi. API etkinliğini, anahtar kısıtlarını ve faturalandırmayı kontrol edin."
  429 -> "Google Cloud kota sınırına ulaşıldı."
  in 500..599 -> "Google Cloud geçici olarak yanıt veremiyor."
  else -> "Google Cloud isteği tamamlanamadı (HTTP $code)."
}

private val entities = Regex("&(?:amp|lt|gt|quot|apos|#\\d{1,7}|#x[0-9a-fA-F]{1,6});")
private fun decodeCloudText(text: String) = entities.replace(text) { match ->
  when (val value = match.value) {
    "&amp;" -> "&"
    "&lt;" -> "<"
    "&gt;" -> ">"
    "&quot;" -> "\""
    "&apos;" -> "'"
    else -> {
      val point = if (value.startsWith("&#x")) value.substring(3, value.length - 1).toIntOrNull(16)
        else value.substring(2, value.length - 1).toIntOrNull()
      if (point != null && Character.isValidCodePoint(point)) String(Character.toChars(point)) else value
    }
  }
}
