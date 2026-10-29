package com.hcrdevelopers.spotshield

import android.content.Context
import org.json.JSONArray

/**
 * Keywords live only on the device. The bundled defaults are copied into local storage
 * once, after which the stored list is the single source of truth: whatever the user adds,
 * edits or removes is exactly what is used, and it survives app restarts.
 * Nothing here touches the network.
 */
class SpamKeywordStorage(context: Context) {

    private val preferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    fun getKeywords(): List<String> = synchronized(lock) {
        seedIfNeeded()
        read() ?: SpamKeywords.defaultKeywords
    }

    fun saveKeywords(keywords: List<String>) = synchronized(lock) {
        write(keywords)
    }

    fun addKeyword(keyword: String): KeywordResult = synchronized(lock) {
        update { KeywordOps.add(it, keyword) }
    }

    fun editKeyword(original: String, updated: String): KeywordResult = synchronized(lock) {
        update { KeywordOps.edit(it, original, updated) }
    }

    fun deleteKeyword(keyword: String) = synchronized(lock) {
        write(KeywordOps.remove(getKeywords(), keyword))
    }

    fun restoreDefaults() = synchronized(lock) {
        write(KeywordOps.restoreDefaults(getKeywords()))
    }

    private fun update(change: (List<String>) -> KeywordResult): KeywordResult {
        val result = change(getKeywords())
        if (result is KeywordResult.Ok) write(result.keywords)
        return result
    }

    /** First run: store the defaults (or carry over the list from older app versions). */
    private fun seedIfNeeded() {
        if (preferences.getBoolean(KEY_SEEDED, false)) return

        val legacy = preferences.getStringSet(LEGACY_KEY, null).orEmpty()
        val initial = if (legacy.isEmpty()) {
            SpamKeywords.defaultKeywords
        } else {
            // Older versions saved an unordered set: defaults first, in their own order.
            val defaults = SpamKeywords.defaultKeywords.filter { default ->
                legacy.any { it.equals(default, ignoreCase = true) }
            }
            val custom = legacy
                .filter { saved -> defaults.none { it.equals(saved, ignoreCase = true) } }
                .sorted()
            defaults + custom
        }

        preferences.edit()
            .putString(KEY_KEYWORDS, toJson(initial))
            .putBoolean(KEY_SEEDED, true)
            .remove(LEGACY_KEY)
            .commit()
    }

    /** Returns null if the stored data is unreadable, so the defaults are used instead. */
    private fun read(): List<String>? {
        val json = preferences.getString(KEY_KEYWORDS, null) ?: return null

        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { array.getString(it) }
        } catch (_: Exception) {
            null
        }
    }

    // commit() writes to disk right away, so a change is never lost if the app is killed.
    private fun write(keywords: List<String>) {
        preferences.edit()
            .putString(KEY_KEYWORDS, toJson(keywords))
            .putBoolean(KEY_SEEDED, true)
            .commit()
    }

    private fun toJson(keywords: List<String>): String =
        JSONArray().apply { keywords.forEach { put(it) } }.toString()

    companion object {
        const val PREFS_NAME = "spot_shield"
        private const val KEY_KEYWORDS = "keywords_json"
        private const val KEY_SEEDED = "keywords_seeded"
        private const val LEGACY_KEY = "keywords"

        private val lock = Any()

        fun getKeywords(context: Context): List<String> {
            return SpamKeywordStorage(context).getKeywords()
        }
    }
}
