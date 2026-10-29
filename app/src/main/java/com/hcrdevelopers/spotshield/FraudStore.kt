package com.hcrdevelopers.spotshield

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import java.io.File

/** Reads the list that older app versions kept as one JSON string in SharedPreferences. */
object LegacyFraudJson {

    fun parse(json: String): List<FilteredSmsMessage> {
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                FilteredSmsMessage(
                    id = item.getString("id"),
                    sender = item.getString("sender"),
                    body = item.getString("body"),
                    date = item.getLong("date"),
                    // Messages saved before read tracking existed count as read.
                    isRead = item.optBoolean("read", true),
                    matchedKeyword = item.optString("keyword", "").ifEmpty { null }
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}

/** One sender's Fraud conversation: numbers in any format count as one sender. */
data class FraudGroup(
    val key: String,
    /** The number as it appears on the latest message. */
    val sender: String,
    val latestBody: String,
    val latestDate: Long,
    val count: Int,
    val unread: Int,
    /** True when any of the sender's messages matched a keyword. */
    val flagged: Boolean
)

/**
 * Fraud messages in a small SQLite table, instead of one JSON string that had to be parsed and
 * rewritten in full for every message. Writes touch single rows, lists are paged, and old
 * messages are purged automatically.
 *
 * The file lives in no-backup storage, so message text never reaches cloud backups.
 */
class FraudStore(
    context: Context,
    dbName: String,
    private val legacyPrefsName: String?,
    private val now: () -> Long = System::currentTimeMillis
) : SQLiteOpenHelper(
    context.applicationContext,
    File(context.applicationContext.noBackupFilesDir, dbName).path,
    null,
    VERSION
) {

    private val appContext = context.applicationContext

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE (
                id TEXT PRIMARY KEY NOT NULL,
                sender TEXT NOT NULL,
                sender_key TEXT NOT NULL,
                body TEXT NOT NULL,
                date INTEGER NOT NULL,
                is_read INTEGER NOT NULL DEFAULT 0,
                keyword TEXT,
                added_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_fraud_date ON $TABLE(date DESC)")
        db.execSQL("CREATE INDEX idx_fraud_sender ON $TABLE(sender_key)")
        db.execSQL("CREATE INDEX idx_fraud_added ON $TABLE(added_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    /** Inserts in one transaction, ignoring ids already stored. Returns how many were new. */
    fun insertAll(messages: List<FilteredSmsMessage>): Int {
        if (messages.isEmpty()) return 0

        val db = writableDatabase
        var inserted = 0
        val addedAt = now()

        db.beginTransaction()
        try {
            messages.forEach { message ->
                val values = ContentValues().apply {
                    put("id", message.id)
                    put("sender", message.sender)
                    put("sender_key", PhoneNormalizer.key(message.sender))
                    put("body", message.body)
                    put("date", message.date)
                    put("is_read", if (message.isRead) 1 else 0)
                    put("keyword", message.matchedKeyword)
                    put("added_at", addedAt)
                }
                if (db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L) {
                    inserted++
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        if (inserted > 0) purge()
        return inserted
    }

    /** Newest [limit] messages, newest first. */
    fun page(limit: Int): List<FilteredSmsMessage> =
        query(selection = null, args = null, limit = limit)

    fun bySenderKey(senderKey: String): List<FilteredSmsMessage> =
        query(selection = "sender_key = ?", args = arrayOf(senderKey), limit = null)

    /**
     * One row per sender, newest conversation first. With a single MAX() in the query, SQLite
     * takes the bare columns (sender, body) from the row holding that newest date.
     */
    fun groups(): List<FraudGroup> {
        val sql = "SELECT sender_key, sender, body, MAX(date) AS latest, COUNT(*) AS total, " +
                "SUM(CASE WHEN is_read = 0 THEN 1 ELSE 0 END) AS unread, " +
                "SUM(CASE WHEN keyword IS NOT NULL AND keyword <> '' THEN 1 ELSE 0 END) AS flagged " +
                "FROM $TABLE GROUP BY sender_key ORDER BY latest DESC"

        return readableDatabase.rawQuery(sql, null).use {
            val groups = ArrayList<FraudGroup>(it.count)
            while (it.moveToNext()) {
                groups.add(
                    FraudGroup(
                        key = it.getString(0),
                        sender = it.getString(1),
                        latestBody = it.getString(2),
                        latestDate = it.getLong(3),
                        count = it.getInt(4),
                        unread = it.getInt(5),
                        flagged = it.getInt(6) > 0
                    )
                )
            }
            groups
        }
    }

    fun markSenderRead(senderKey: String) {
        val values = ContentValues().apply { put("is_read", 1) }
        writableDatabase.update(TABLE, values, "sender_key = ? AND is_read = 0", arrayOf(senderKey))
    }

    /** Deletes every message of one sender. Returns how many were removed. */
    fun deleteSender(senderKey: String): Int =
        writableDatabase.delete(TABLE, "sender_key = ?", arrayOf(senderKey))

    fun unreadCount(): Int = scalar("SELECT COUNT(*) FROM $TABLE WHERE is_read = 0")

    fun count(): Int = scalar("SELECT COUNT(*) FROM $TABLE")

    fun markRead(id: String) {
        val values = ContentValues().apply { put("is_read", 1) }
        writableDatabase.update(TABLE, values, "id = ? AND is_read = 0", arrayOf(id))
    }

    fun delete(ids: List<String>) {
        if (ids.isEmpty()) return

        val db = writableDatabase
        db.beginTransaction()
        try {
            ids.chunked(SQL_CHUNK).forEach { chunk ->
                db.delete(TABLE, "id IN (${chunk.joinToString(",") { "?" }})", chunk.toTypedArray())
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Drops messages that entered Fraud long ago, plus anything beyond the safety cap. */
    fun purge() {
        val db = writableDatabase
        db.delete(TABLE, "added_at < ?", arrayOf((now() - MAX_AGE_MS).toString()))

        if (count() > MAX_COUNT) {
            db.execSQL(
                "DELETE FROM $TABLE WHERE id NOT IN " +
                        "(SELECT id FROM $TABLE ORDER BY added_at DESC, date DESC LIMIT $MAX_COUNT)"
            )
        }
    }

    /**
     * One-time move of the old SharedPreferences list into the table. The old copy is removed
     * only after every message is confirmed to be stored, so a failed move loses nothing.
     */
    fun migrateLegacy() {
        val prefsName = legacyPrefsName ?: return
        val prefs = appContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val json = prefs.getString(LEGACY_KEY, null) ?: return

        val legacy = LegacyFraudJson.parse(json)
        if (legacy.isEmpty() && json.length > EMPTY_JSON_LENGTH) {
            // Unreadable data: keep it untouched instead of discarding it.
            return
        }

        insertAllWithoutPurge(legacy)

        val stored = legacy.map { it.id }.distinct().chunked(SQL_CHUNK).sumOf { chunk ->
            scalar(
                "SELECT COUNT(*) FROM $TABLE WHERE id IN (${chunk.joinToString(",") { "?" }})",
                chunk.toTypedArray()
            )
        }

        if (stored == legacy.map { it.id }.distinct().size) {
            prefs.edit().remove(LEGACY_KEY).commit()
            purge()
        }
    }

    private fun insertAllWithoutPurge(messages: List<FilteredSmsMessage>) {
        val db = writableDatabase
        val addedAt = now()

        db.beginTransaction()
        try {
            messages.forEach { message ->
                val values = ContentValues().apply {
                    put("id", message.id)
                    put("sender", message.sender)
                    put("sender_key", PhoneNormalizer.key(message.sender))
                    put("body", message.body)
                    put("date", message.date)
                    put("is_read", if (message.isRead) 1 else 0)
                    put("keyword", message.matchedKeyword)
                    put("added_at", addedAt)
                }
                db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_IGNORE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun query(selection: String?, args: Array<String>?, limit: Int?): List<FilteredSmsMessage> {
        val cursor: Cursor = readableDatabase.query(
            TABLE,
            arrayOf("id", "sender", "body", "date", "is_read", "keyword"),
            selection,
            args,
            null,
            null,
            "date DESC",
            limit?.toString()
        )

        return cursor.use {
            val idIndex = it.getColumnIndexOrThrow("id")
            val senderIndex = it.getColumnIndexOrThrow("sender")
            val bodyIndex = it.getColumnIndexOrThrow("body")
            val dateIndex = it.getColumnIndexOrThrow("date")
            val readIndex = it.getColumnIndexOrThrow("is_read")
            val keywordIndex = it.getColumnIndexOrThrow("keyword")

            val messages = ArrayList<FilteredSmsMessage>(it.count)
            while (it.moveToNext()) {
                messages.add(
                    FilteredSmsMessage(
                        id = it.getString(idIndex),
                        sender = it.getString(senderIndex),
                        body = it.getString(bodyIndex),
                        date = it.getLong(dateIndex),
                        isRead = it.getInt(readIndex) != 0,
                        matchedKeyword = it.getString(keywordIndex)
                    )
                )
            }
            messages
        }
    }

    private fun scalar(sql: String, args: Array<String>? = null): Int =
        readableDatabase.rawQuery(sql, args).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    companion object {
        private const val VERSION = 1
        private const val TABLE = "fraud_messages"
        private const val LEGACY_KEY = "filtered_messages"
        private const val EMPTY_JSON_LENGTH = 2
        private const val SQL_CHUNK = 400

        /** A message is kept for 30 days after it entered Fraud (not after it was sent). */
        const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000

        /** Safety net against runaway growth; far above what 30 days of spam produces. */
        const val MAX_COUNT = 5000
    }
}
