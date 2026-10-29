package com.hcrdevelopers.spotshield

import android.content.Context
import org.json.JSONObject

data class SenderEntry(
    val key: String,
    val number: String,
    val addedAt: Long
)

/**
 * Persistent allowlist (trusted) and blocklist (fraud) of senders.
 * Entries are keyed by [PhoneNormalizer.key] so number formats don't matter.
 */
object SenderListStorage {

    const val PREFS_NAME = "spot_sender_lists"
    private const val KEY_ALLOWED = "allowed"
    private const val KEY_BLOCKED = "blocked"

    fun isAllowlisted(context: Context, sender: String): Boolean =
        contains(context, KEY_ALLOWED, sender)

    fun isBlocked(context: Context, sender: String): Boolean =
        contains(context, KEY_BLOCKED, sender)

    fun addAllowed(context: Context, sender: String) = add(context, KEY_ALLOWED, sender)
    fun addBlocked(context: Context, sender: String) = add(context, KEY_BLOCKED, sender)
    fun removeAllowed(context: Context, sender: String) = remove(context, KEY_ALLOWED, sender)
    fun removeBlocked(context: Context, sender: String) = remove(context, KEY_BLOCKED, sender)

    fun getAllowed(context: Context): List<SenderEntry> = read(context, KEY_ALLOWED)
    fun getBlocked(context: Context): List<SenderEntry> = read(context, KEY_BLOCKED)

    @Synchronized
    private fun contains(context: Context, listKey: String, sender: String): Boolean {
        val key = PhoneNormalizer.key(sender)
        if (key.isEmpty()) return false
        return load(context, listKey).has(key)
    }

    @Synchronized
    private fun add(context: Context, listKey: String, sender: String) {
        val key = PhoneNormalizer.key(sender)
        if (key.isEmpty()) return

        val json = load(context, listKey)
        if (json.has(key)) return

        json.put(
            key,
            JSONObject().apply {
                put("number", sender.trim())
                put("addedAt", System.currentTimeMillis())
            }
        )
        save(context, listKey, json)
    }

    @Synchronized
    private fun remove(context: Context, listKey: String, sender: String) {
        val key = PhoneNormalizer.key(sender)
        val json = load(context, listKey)
        if (json.remove(key) != null) {
            save(context, listKey, json)
        }
    }

    @Synchronized
    private fun read(context: Context, listKey: String): List<SenderEntry> {
        val json = load(context, listKey)
        return json.keys().asSequence().mapNotNull { key ->
            val item = json.optJSONObject(key) ?: return@mapNotNull null
            SenderEntry(
                key = key,
                number = item.optString("number", key),
                addedAt = item.optLong("addedAt")
            )
        }.sortedByDescending { it.addedAt }.toList()
    }

    private fun load(context: Context, listKey: String): JSONObject {
        val raw = prefs(context).getString(listKey, null) ?: return JSONObject()
        return try {
            JSONObject(raw)
        } catch (_: Exception) {
            JSONObject()
        }
    }

    // commit() so a list change survives the process being killed right after.
    private fun save(context: Context, listKey: String, json: JSONObject) {
        prefs(context).edit().putString(listKey, json.toString()).commit()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
