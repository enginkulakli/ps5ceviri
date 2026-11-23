package expo.modules.ps5translator

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.JsonWriter
import expo.modules.ps5translator.core.memoryText
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

internal const val GENERAL_GAME = "general"

internal data class ArchivePolicy(val gameId: String = GENERAL_GAME, val general: Boolean = true, val shareEngines: Boolean = false)

internal data class ArchiveKey(val gameId: String, val source: String, val normalized: String, val engine: String, val version: String)

internal class TranslationArchive private constructor(context: Context) : SQLiteOpenHelper(context, NAME, null, 1) {
  private val app = context.applicationContext
  val lookups = Executors.newSingleThreadExecutor()
  val reads = Executors.newSingleThreadExecutor()
  val writes = Executors.newSingleThreadExecutor()
  val revision = AtomicLong()

  init { setWriteAheadLoggingEnabled(true) }

  override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }

  override fun onCreate(db: SQLiteDatabase) {
    db.execSQL("CREATE TABLE games(id TEXT PRIMARY KEY, name TEXT NOT NULL)")
    db.execSQL("INSERT INTO games VALUES ('general','Genel arşiv')")
    db.execSQL("""
      CREATE TABLE records(
        id INTEGER PRIMARY KEY, game TEXT NOT NULL REFERENCES games(id), lang TEXT NOT NULL,
        source TEXT NOT NULL, normalized TEXT NOT NULL, translated TEXT NOT NULL, engine TEXT NOT NULL,
        created INTEGER NOT NULL, version TEXT NOT NULL, context TEXT, curated INTEGER NOT NULL DEFAULT 0,
        UNIQUE(game, lang, normalized, engine, version))
    """.trimIndent())
    db.execSQL("CREATE INDEX records_game_recent ON records(game, id DESC)")
    db.execSQL("CREATE INDEX records_lookup ON records(game, lang, normalized, curated DESC, id DESC)")
    db.execSQL("CREATE TABLE usage(period TEXT PRIMARY KEY, chars INTEGER NOT NULL, requests INTEGER NOT NULL)")
  }

  override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    error("Desteklenmeyen arşiv şeması.")
  }

  fun lookup(key: ArchiveKey, policy: ArchivePolicy): String? {
    val games = if (policy.general && key.gameId != GENERAL_GAME) listOf(key.gameId, GENERAL_GAME) else listOf(key.gameId)
    for (game in games) {
      val filters = if (policy.shareEngines) "" else " AND (engine=? OR engine='package')"
      val args = mutableListOf(game, key.normalized, key.version).apply { if (!policy.shareEngines) add(key.engine) }
      readableDatabase.rawQuery(
        "SELECT translated FROM records WHERE game=? AND lang='en' AND normalized=? AND (curated=1 OR version=?)" +
          filters + " ORDER BY curated DESC, engine=? DESC, id DESC LIMIT 1", (args + key.engine).toTypedArray(),
      ).use { if (it.moveToFirst()) return it.getString(0) }
    }
    return null
  }

  fun store(key: ArchiveKey, translated: String, engine: String = key.engine) {
    val db = writableDatabase
    db.insertWithOnConflict("records", null, recordValues(key.gameId, "en", key.source, translated,
      engine, System.currentTimeMillis(), key.version, null, false), SQLiteDatabase.CONFLICT_IGNORE)
  }

  fun games(): JSONArray {
    val result = JSONArray()
    readableDatabase.rawQuery(
      "SELECT g.id,g.name,(SELECT COUNT(*) FROM records r WHERE r.game=g.id) FROM games g ORDER BY g.id!='general', g.name COLLATE NOCASE",
      null,
    ).use { rows ->
      while (rows.moveToNext()) result.put(JSONObject().put("id", rows.getString(0)).put("name", rows.getString(1)).put("count", rows.getLong(2)))
    }
    return result
  }

  fun createGame(name: String): String {
    val title = name.trim()
    require(title.isNotEmpty() && title.length <= 100) { "Oyun adı 1–100 karakter olmalı." }
    val id = UUID.randomUUID().toString()
    writableDatabase.insertOrThrow("games", null, ContentValues().apply { put("id", id); put("name", title) })
    return id
  }

  fun page(game: String, search: String, before: Long): JSONArray {
    val needle = search.take(120).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    val result = JSONArray()
    readableDatabase.rawQuery(
      "SELECT id,game,lang,source,translated,engine,created,version,context,curated FROM records " +
        "WHERE game=? AND id<? AND (source LIKE ? ESCAPE '\\' OR translated LIKE ? ESCAPE '\\') ORDER BY id DESC LIMIT 50",
      arrayOf(game, if (before > 0) before.toString() else Long.MAX_VALUE.toString(), "%$needle%", "%$needle%"),
    ).use { rows -> while (rows.moveToNext()) result.put(rows.record()) }
    return result
  }

  fun byteSize(): Long {
    val file = app.getDatabasePath(NAME)
    return file.length() + File(file.path + "-wal").length()
  }

  fun edit(id: Long, translation: String) {
    require(translation.isNotBlank() && translation.length <= 1200) { "Çeviri 1–1200 karakter olmalı." }
    val count = writableDatabase.update("records", ContentValues().apply {
      put("translated", translation.trim()); put("curated", 1)
    }, "id=?", arrayOf(id.toString()))
    check(count == 1) { "Arşiv kaydı bulunamadı." }
    revision.incrementAndGet()
  }

  fun delete(id: Long) {
    writableDatabase.delete("records", "id=?", arrayOf(id.toString()))
    revision.incrementAndGet()
  }

  /** Archive and usage writes share a transactional SQLite clock. UTC periods never depend on device time zone. */
  fun reserveCloudUsage(characters: Int, daily: Int, monthly: Int) {
    val (day, month) = periods()
    writableDatabase.transaction {
      val current = usage(day, month)
      check(current[0] + characters <= daily && current[2] + characters <= monthly) {
        "Yerel Google Cloud karakter sınırına ulaşıldı."
      }
      for (period in listOf(day, month)) {
        execSQL("INSERT OR IGNORE INTO usage VALUES(?,0,0)", arrayOf(period))
        execSQL("UPDATE usage SET chars=chars+?,requests=requests+1 WHERE period=?", arrayOf<Any>(characters, period))
      }
      execSQL("DELETE FROM usage WHERE length(period)=10 AND period<?", arrayOf(month + "-01"))
    }
  }

  fun currentUsage(): JSONObject {
    val (day, month) = periods()
    val values = readableDatabase.usage(day, month)
    return JSONObject().put("dailyCharacters", values[0]).put("dailyRequests", values[1])
      .put("monthlyCharacters", values[2]).put("monthlyRequests", values[3])
  }

  private fun periods(): Pair<String, String> {
    val day = Instant.now().atOffset(ZoneOffset.UTC).toLocalDate().toString()
    return day to day.take(7)
  }

  private fun SQLiteDatabase.usage(day: String, month: String): LongArray {
    val result = LongArray(4)
    rawQuery("SELECT period,chars,requests FROM usage WHERE period IN (?,?)", arrayOf(day, month)).use { rows ->
      while (rows.moveToNext()) {
        val i = if (rows.getString(0) == day) 0 else 2
        result[i] = rows.getLong(1)
        result[i + 1] = rows.getLong(2)
      }
    }
    return result
  }

  fun exportArchive(game: String?, output: OutputStream) {
    JsonWriter(output.writer(Charsets.UTF_8)).use { json ->
      json.beginObject().name("format").value(FORMAT).name("version").value(1)
      val clause = if (game == null) "" else " WHERE id=?"
      val args = game?.let { arrayOf(it) }
      json.name("games").beginArray()
      readableDatabase.rawQuery("SELECT id,name FROM games" + clause, args).use { rows ->
        while (rows.moveToNext()) {
          json.beginObject().name("id").value(rows.getString(0)).name("name").value(rows.getString(1)).endObject()
        }
      }
      json.endArray().name("records").beginArray()
      readableDatabase.rawQuery("SELECT id,game,lang,source,translated,engine,created,version,context,curated FROM records" +
        if (game == null) " ORDER BY id" else " WHERE game=? ORDER BY id", args).use { rows ->
        while (rows.moveToNext()) {
          val record = rows.record()
          json.beginObject()
          val keys = record.keys()
          while (keys.hasNext()) {
            val key = keys.next()
            if (key == "id") continue
            json.name(key)
            when (val value = record.get(key)) {
              is Boolean -> json.value(value)
              is Number -> json.value(value)
              JSONObject.NULL -> json.nullValue()
              else -> json.value(value.toString())
            }
          }
          json.endObject()
        }
      }
      json.endArray().endObject()
    }
  }

  fun importArchive(input: InputStream): JSONObject {
    // Limit input before JSON parsing. Import merges whole packages atomically.
    val bytes = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
      val read = input.read(buffer)
      if (read < 0) break
      require(bytes.size() + read <= 24 * 1024 * 1024) { "Arşiv dosyası 24 MB sınırını aşıyor." }
      bytes.write(buffer, 0, read)
    }
    val json = JSONObject(bytes.toString("UTF-8"))
    require(json.getString("format") == FORMAT && json.getInt("version") == 1) { "Arşiv biçimi desteklenmiyor." }
    val games = json.getJSONArray("games")
    val records = json.getJSONArray("records")
    require(games.length() <= 10000 && records.length() <= 100000) { "Arşiv paketi çok fazla kayıt içeriyor." }
    val gameIds = HashSet<String>()
    var added = 0
    writableDatabase.transaction {
      for (i in 0 until games.length()) {
        val item = games.getJSONObject(i)
        val id = item.getString("id")
        val name = item.getString("name").trim()
        require(id.matches(Regex("[a-zA-Z0-9_-]{1,80}")) && name.isNotBlank() && name.length <= 100 && gameIds.add(id))
        insertWithOnConflict("games", null, ContentValues().apply { put("id", id); put("name", name) }, SQLiteDatabase.CONFLICT_IGNORE)
      }
      for (i in 0 until records.length()) {
        val item = records.getJSONObject(i)
        val game = item.getString("game")
        val source = item.getString("source").trim()
        val result = item.getString("translated").trim()
        val lang = item.getString("lang")
        val engine = item.getString("engine")
        val version = item.getString("version")
        val context = if (item.isNull("context")) null else item.getString("context").take(1000)
        require(game in gameIds && lang == "en" && source.length in 1..1200 && result.length in 1..1200 &&
          engine in setOf("offline", "google_cloud", "ai", "package") && version.length in 1..100)
        val row = recordValues(game, lang, source, result, engine, item.getLong("created"),
          version, context, item.optBoolean("curated") || engine == "package")
        if (insertWithOnConflict("records", null, row, SQLiteDatabase.CONFLICT_IGNORE) != -1L) added++
      }
    }
    revision.incrementAndGet()
    return JSONObject().put("added", added).put("skipped", records.length() - added)
  }

  private fun Cursor.record() = JSONObject().put("id", getLong(0)).put("game", getString(1))
    .put("lang", getString(2)).put("source", getString(3)).put("translated", getString(4)).put("engine", getString(5))
    .put("created", getLong(6)).put("version", getString(7)).put("context", getString(8) ?: JSONObject.NULL)
    .put("curated", getInt(9) != 0)

  private fun recordValues(game: String, lang: String, source: String, translation: String, engine: String,
                           created: Long, version: String, context: String?, curated: Boolean) = ContentValues().apply {
    put("game", game); put("lang", lang); put("source", source); put("normalized", memoryText(source))
    put("translated", translation); put("engine", engine); put("created", created); put("version", version)
    put("context", context); put("curated", if (curated) 1 else 0)
  }

  private inline fun SQLiteDatabase.transaction(action: SQLiteDatabase.() -> Unit) {
    beginTransactionNonExclusive()
    try { action(); setTransactionSuccessful() } finally { endTransaction() }
  }

  companion object {
    private const val NAME = "translation-archive.db"
    private const val FORMAT = "ps5ceviri-interchange"
    @Volatile private var instance: TranslationArchive? = null
    fun get(context: Context): TranslationArchive = instance ?: synchronized(this) {
      instance ?: TranslationArchive(context.applicationContext).also { instance = it }
    }
  }
}
