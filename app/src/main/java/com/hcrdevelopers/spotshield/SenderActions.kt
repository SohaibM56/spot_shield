package com.hcrdevelopers.spotshield

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class RestoreResult(val restored: Int, val failed: Int)

data class FraudMoveResult(val found: Int, val moved: Int)

/** Moves a sender's messages between Inbox and Fraud and keeps the allow/block lists in sync. */
object SenderActions {

    /**
     * Blocks [sender], and moves their received messages from the Inbox to Fraud.
     * [FraudMoveResult.moved] is 0 when Android refused to delete them, e.g. when
     * SPoT Shield is not the default SMS app.
     */
    suspend fun markAsFraud(
        context: Context,
        sender: String,
        addresses: List<String>
    ): FraudMoveResult = withContext(Dispatchers.IO) {
        SenderListStorage.addBlocked(context, sender)
        SenderListStorage.removeAllowed(context, sender)

        val received = SmsInboxRepository.getReceivedMessages(context, addresses)
        val deletedIds = SmsInboxRepository.deleteMessages(context, received.map { it.id }).toSet()

        val moved = received.filter { it.id in deletedIds }
        FilteredSmsStorage.saveAll(
            context,
            moved.map { IncomingFraud(it.sender, it.body, it.date, it.isRead) }
        )

        FraudMoveResult(found = received.size, moved = moved.size)
    }

    /**
     * Moves every Fraud message from [sender] back to the Inbox and unblocks them.
     * With [allowlist] the sender is also trusted (Unspam); without it this is a plain undo.
     */
    suspend fun restoreSender(
        context: Context,
        sender: String,
        allowlist: Boolean
    ): RestoreResult = withContext(Dispatchers.IO) {
        val messages = FilteredSmsStorage.getBySender(context, sender)

        val restoredIds = ArrayList<String>()
        messages.forEach { message ->
            val inserted = SmsInboxRepository.insertInbox(
                context = context,
                sender = message.sender,
                body = message.body,
                date = message.date,
                isRead = message.isRead
            )

            if (inserted) restoredIds.add(message.id)
        }
        FilteredSmsStorage.deleteAll(context, restoredIds)
        val restored = restoredIds.size

        SenderListStorage.removeBlocked(context, sender)
        if (allowlist) SenderListStorage.addAllowed(context, sender)

        RestoreResult(restored = restored, failed = messages.size - restored)
    }
}
