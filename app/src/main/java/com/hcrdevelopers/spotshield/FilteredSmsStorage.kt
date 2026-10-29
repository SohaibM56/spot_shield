package com.hcrdevelopers.spotshield

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class FilteredSmsMessage(
    val id: String,
    val sender: String,
    val body: String,
    val date: Long,
    val isRead: Boolean = true,
    /** The user keyword found in the message; set only for messages that are routed to Fraud. */
    val matchedKeyword: String? = null
)

/**
 * The Fraud list. Backed by [FraudStore]; every call touches the database, so keep them off
 * the main thread. [changes] ticks after each write so screens know when to reload.
 */
object FilteredSmsStorage {

    /** SharedPreferences file used by older versions; its content is moved into the database. */
    const val PREFS_NAME = "spot_filtered_sms"

    private const val DB_NAME = "fraud.db"

    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes.asStateFlow()

    @Volatile private var store: FraudStore? = null

    private fun store(context: Context): FraudStore =
        store ?: synchronized(this) {
            store ?: FraudStore(context, DB_NAME, PREFS_NAME).also {
                it.migrateLegacy()
                store = it
            }
        }

    fun save(
        context: Context,
        sender: String,
        body: String,
        date: Long,
        isRead: Boolean = false
    ) {
        saveAll(context, listOf(IncomingFraud(sender, body, date, isRead)))
    }

    /** Stores several messages in one transaction. */
    fun saveAll(context: Context, messages: List<IncomingFraud>) {
        if (messages.isEmpty()) return

        val keywords = SpamKeywordStorage.getKeywords(context)
        val rows = messages.map {
            FilteredSmsMessage(
                id = "${it.date}_${it.sender}_${it.body.hashCode()}",
                sender = it.sender,
                body = it.body,
                date = it.date,
                isRead = it.isRead,
                matchedKeyword = SpamMatcher.findMatch(it.body, keywords)
            )
        }

        if (store(context).insertAll(rows) > 0) notifyChanged()
    }

    /** The newest [limit] messages, newest first. */
    fun getPage(context: Context, limit: Int): List<FilteredSmsMessage> =
        store(context).page(limit)

    fun getBySender(context: Context, sender: String): List<FilteredSmsMessage> =
        store(context).bySenderKey(PhoneNormalizer.key(sender))

    /** Messages of one conversation, by its key, newest first. */
    fun getByKey(context: Context, key: String): List<FilteredSmsMessage> =
        store(context).bySenderKey(key)

    /** The Fraud list as conversations: one row per sender. */
    fun getGroups(context: Context): List<FraudGroup> = store(context).groups()

    fun markSenderRead(context: Context, sender: String) {
        store(context).markSenderRead(PhoneNormalizer.key(sender))
        notifyChanged()
    }

    /** Deletes all Fraud messages of one sender. Returns how many were removed. */
    fun deleteSender(context: Context, sender: String): Int {
        val removed = store(context).deleteSender(PhoneNormalizer.key(sender))
        if (removed > 0) notifyChanged()
        return removed
    }

    fun unreadCount(context: Context): Int = store(context).unreadCount()

    fun delete(context: Context, id: String) = deleteAll(context, listOf(id))

    fun deleteAll(context: Context, ids: List<String>) {
        if (ids.isEmpty()) return
        store(context).delete(ids)
        notifyChanged()
    }

    fun markRead(context: Context, id: String) {
        store(context).markRead(id)
        notifyChanged()
    }

    /** Asks screens to reload, e.g. for a manual refresh. */
    fun invalidate() = notifyChanged()

    private fun notifyChanged() {
        _changes.value = _changes.value + 1
    }
}

data class IncomingFraud(
    val sender: String,
    val body: String,
    val date: Long,
    val isRead: Boolean = false
)
