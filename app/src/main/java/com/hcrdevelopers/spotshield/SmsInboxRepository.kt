package com.hcrdevelopers.spotshield

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.os.Build
import android.os.Bundle
import android.provider.Telephony
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SmsInboxMessage(
    val id: Long,
    val sender: String,
    val body: String,
    val date: Long,
    val isRead: Boolean,
    val type: Int = Telephony.Sms.MESSAGE_TYPE_INBOX
)

/**
 * One row of the conversation list. It holds only the latest message, so the list never has
 * to read every message in the phone; a chat loads its own messages when it is opened.
 */
data class SmsConversationSummary(
    /** Normalized number, so "+92 300…" and "0300…" share one conversation. */
    val key: String,
    /** Number as it appears on the latest message. */
    val sender: String,
    /** Every raw address that maps to [key]; used to find this conversation's messages. */
    val addresses: List<String>,
    val latestMessage: SmsInboxMessage,
    val unreadCount: Int,
    val messageCount: Int
)

object SmsInboxRepository {

    private const val UNKNOWN_SENDER = "Unknown sender"
    private const val BODY_CHUNK = 400

    // Received and sent SMS, plus messages that are sending or failed.
    private val CHAT_TYPES = listOf(
        Telephony.Sms.MESSAGE_TYPE_INBOX,
        Telephony.Sms.MESSAGE_TYPE_SENT,
        Telephony.Sms.MESSAGE_TYPE_OUTBOX,
        Telephony.Sms.MESSAGE_TYPE_FAILED
    )
    private val CHAT_TYPES_CLAUSE = "${Telephony.Sms.TYPE} IN (${CHAT_TYPES.joinToString(",")})"

    private class Builder(
        val sender: String,
        val latestId: Long,
        val latestDate: Long,
        val latestType: Int,
        val latestRead: Boolean
    ) {
        val addresses = LinkedHashSet<String>()
        var unread = 0
        var count = 0
    }

    /**
     * Builds the conversation list from a light query (no message bodies), then fetches the body
     * of each conversation's latest message only. Newest conversation first.
     */
    suspend fun getConversations(
        context: Context
    ): List<SmsConversationSummary> = withContext(Dispatchers.IO) {
        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.DATE,
            Telephony.Sms.READ,
            Telephony.Sms.TYPE
        )

        // The query is newest first, so the first row seen for a key is its latest message.
        val builders = LinkedHashMap<String, Builder>()
        val keyCache = HashMap<String, String>()

        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            projection,
            CHAT_TYPES_CLAUSE,
            null,
            "${Telephony.Sms.DATE} DESC"
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
            val addressIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val dateIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val readIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.READ)
            val typeIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE)

            while (cursor.moveToNext()) {
                val address = cursor.getString(addressIndex) ?: UNKNOWN_SENDER
                val type = cursor.getInt(typeIndex)
                val read = cursor.getInt(readIndex) != 0
                val key = keyCache.getOrPut(address) { PhoneNormalizer.key(address) }

                val builder = builders.getOrPut(key) {
                    Builder(
                        sender = address,
                        latestId = cursor.getLong(idIndex),
                        latestDate = cursor.getLong(dateIndex),
                        latestType = type,
                        latestRead = read
                    )
                }

                builder.addresses.add(address)
                builder.count++
                if (type == Telephony.Sms.MESSAGE_TYPE_INBOX && !read) builder.unread++
            }
        }

        val bodies = loadBodies(context.contentResolver, builders.values.map { it.latestId })

        builders.map { (key, builder) ->
            SmsConversationSummary(
                key = key,
                sender = builder.sender,
                addresses = builder.addresses.toList(),
                latestMessage = SmsInboxMessage(
                    id = builder.latestId,
                    sender = builder.sender,
                    body = bodies[builder.latestId].orEmpty(),
                    date = builder.latestDate,
                    isRead = builder.latestRead,
                    type = builder.latestType
                ),
                unreadCount = builder.unread,
                messageCount = builder.count
            )
        }
    }

    /** Newest [limit] messages of one conversation, newest first. */
    suspend fun getMessages(
        context: Context,
        addresses: List<String>,
        limit: Int
    ): List<SmsInboxMessage> = withContext(Dispatchers.IO) {
        val (addressClause, args) = addressSelection(addresses)
        val selection = "($addressClause) AND $CHAT_TYPES_CLAUSE"

        queryMessages(
            context.contentResolver,
            selection,
            args,
            limit
        )
    }

    /** Every received message of one conversation, used when moving a sender to Fraud. */
    suspend fun getReceivedMessages(
        context: Context,
        addresses: List<String>
    ): List<SmsInboxMessage> = withContext(Dispatchers.IO) {
        val (addressClause, args) = addressSelection(addresses)
        val selection = "($addressClause) AND ${Telephony.Sms.TYPE} = ${Telephony.Sms.MESSAGE_TYPE_INBOX}"

        queryMessages(context.contentResolver, selection, args, limit = null)
    }

    suspend fun deleteMessage(
        context: Context,
        messageId: Long
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val deletedRows = context.contentResolver.delete(
                Telephony.Sms.CONTENT_URI,
                "${Telephony.Sms._ID} = ?",
                arrayOf(messageId.toString())
            )

            deletedRows > 0
        } catch (_: Exception) {
            false
        }
    }

    /** Deletes the given messages one by one and returns the ids that were actually removed. */
    suspend fun deleteMessages(
        context: Context,
        messageIds: List<Long>
    ): List<Long> = withContext(Dispatchers.IO) {
        messageIds.filter { id ->
            try {
                context.contentResolver.delete(
                    Telephony.Sms.CONTENT_URI,
                    "${Telephony.Sms._ID} = ?",
                    arrayOf(id.toString())
                ) > 0
            } catch (_: Exception) {
                false
            }
        }
    }

    /** Deletes every message of a conversation. Returns the number of deleted rows. */
    suspend fun deleteConversation(
        context: Context,
        addresses: List<String>
    ): Int = withContext(Dispatchers.IO) {
        val (addressClause, args) = addressSelection(addresses)

        try {
            context.contentResolver.delete(Telephony.Sms.CONTENT_URI, addressClause, args)
        } catch (_: Exception) {
            0
        }
    }

    /** Marks every unread message of a conversation as read and seen. Returns updated rows. */
    suspend fun markConversationRead(
        context: Context,
        addresses: List<String>
    ): Int = withContext(Dispatchers.IO) {
        val (addressClause, args) = addressSelection(addresses)
        val values = ContentValues().apply {
            put(Telephony.Sms.READ, 1)
            put(Telephony.Sms.SEEN, 1)
        }

        try {
            context.contentResolver.update(
                Telephony.Sms.CONTENT_URI,
                values,
                "($addressClause) AND ${Telephony.Sms.READ} = 0",
                args
            )
        } catch (_: Exception) {
            0
        }
    }

    /**
     * Stores a message that is about to be sent, so it shows in the chat right away.
     * Android only does this for apps that are not the default SMS app, so we must.
     * Returns the new row id, or null if Android refused.
     */
    suspend fun insertOutgoing(
        context: Context,
        address: String,
        body: String,
        date: Long
    ): Long? = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, date)
            put(Telephony.Sms.READ, 1)
            put(Telephony.Sms.SEEN, 1)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_OUTBOX)
        }

        try {
            context.contentResolver.insert(Telephony.Sms.Outbox.CONTENT_URI, values)
                ?.let { android.content.ContentUris.parseId(it) }
        } catch (_: Exception) {
            null
        }
    }

    /** Moves an outgoing message to [newType] (sent or failed). [onlyIfType] makes it conditional. */
    fun updateType(
        context: Context,
        messageId: Long,
        newType: Int,
        onlyIfType: Int? = null
    ): Int {
        val values = ContentValues().apply { put(Telephony.Sms.TYPE, newType) }
        val condition = buildString {
            append("${Telephony.Sms._ID} = ?")
            if (onlyIfType != null) append(" AND ${Telephony.Sms.TYPE} = $onlyIfType")
        }

        return try {
            context.contentResolver.update(
                Telephony.Sms.CONTENT_URI,
                values,
                condition,
                arrayOf(messageId.toString())
            )
        } catch (_: Exception) {
            0
        }
    }

    /** Inserts a received message into the Inbox. Returns false if Android refuses. */
    suspend fun insertInbox(
        context: Context,
        sender: String,
        body: String,
        date: Long,
        isRead: Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, sender)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, date)
            put(Telephony.Sms.READ, if (isRead) 1 else 0)
            put(Telephony.Sms.SEEN, if (isRead) 1 else 0)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
        }

        try {
            context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values) != null
        } catch (_: Exception) {
            false
        }
    }

    private fun queryMessages(
        resolver: ContentResolver,
        selection: String,
        args: Array<String>,
        limit: Int?
    ): List<SmsInboxMessage> {
        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.READ,
            Telephony.Sms.TYPE
        )
        val sortOrder = "${Telephony.Sms.DATE} DESC"

        val cursor: Cursor? = when {
            limit == null -> resolver.query(Telephony.Sms.CONTENT_URI, projection, selection, args, sortOrder)

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> {
                val queryArgs = Bundle().apply {
                    putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                    putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
                    putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sortOrder)
                    putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
                }
                resolver.query(Telephony.Sms.CONTENT_URI, projection, queryArgs, null)
            }

            // Android 7: the telephony provider accepts LIMIT inside the sort order.
            else -> resolver.query(
                Telephony.Sms.CONTENT_URI,
                projection,
                selection,
                args,
                "$sortOrder LIMIT $limit"
            )
        }

        val messages = ArrayList<SmsInboxMessage>()
        cursor?.use {
            val idIndex = it.getColumnIndexOrThrow(Telephony.Sms._ID)
            val addressIndex = it.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyIndex = it.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateIndex = it.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val readIndex = it.getColumnIndexOrThrow(Telephony.Sms.READ)
            val typeIndex = it.getColumnIndexOrThrow(Telephony.Sms.TYPE)

            while (it.moveToNext()) {
                messages.add(
                    SmsInboxMessage(
                        id = it.getLong(idIndex),
                        sender = it.getString(addressIndex) ?: UNKNOWN_SENDER,
                        body = it.getString(bodyIndex).orEmpty(),
                        date = it.getLong(dateIndex),
                        isRead = it.getInt(readIndex) != 0,
                        type = it.getInt(typeIndex)
                    )
                )
            }
        }
        return messages
    }

    private fun loadBodies(resolver: ContentResolver, ids: List<Long>): Map<Long, String> {
        val bodies = HashMap<Long, String>(ids.size)

        ids.chunked(BODY_CHUNK).forEach { chunk ->
            resolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms._ID, Telephony.Sms.BODY),
                "${Telephony.Sms._ID} IN (${chunk.joinToString(",")})",
                null,
                null
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
                val bodyIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)

                while (cursor.moveToNext()) {
                    bodies[cursor.getLong(idIndex)] = cursor.getString(bodyIndex).orEmpty()
                }
            }
        }
        return bodies
    }

    /** `address IN (?, ?)` for a conversation's raw addresses; a missing address is matched too. */
    private fun addressSelection(addresses: List<String>): Pair<String, Array<String>> {
        val real = addresses.filter { it != UNKNOWN_SENDER }
        val clauses = ArrayList<String>()

        if (real.isNotEmpty()) {
            clauses.add("${Telephony.Sms.ADDRESS} IN (${real.joinToString(",") { "?" }})")
        }
        if (real.size != addresses.size) {
            clauses.add("${Telephony.Sms.ADDRESS} IS NULL")
        }
        if (clauses.isEmpty()) {
            // Matches nothing, so an empty list can never touch other conversations.
            return "0" to emptyArray()
        }

        return clauses.joinToString(" OR ") to real.toTypedArray()
    }
}
