package com.hcrdevelopers.spotshield

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

/** Hears from Android whether an outgoing SMS was really sent, and marks it Sent or Failed. */
class SmsSentReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val messageId = intent.getLongExtra(EXTRA_MESSAGE_ID, -1L)
        if (messageId < 0) return

        // Read the result first: after goAsync() the receiver's own resultCode always reads 0.
        val result = resultCode
        val pending = goAsync()

        Thread {
            try {
                if (result == Activity.RESULT_OK) {
                    // Only an unsent message becomes Sent; a failed part of the same message stays Failed.
                    SmsInboxRepository.updateType(
                        context,
                        messageId,
                        Telephony.Sms.MESSAGE_TYPE_SENT,
                        onlyIfType = Telephony.Sms.MESSAGE_TYPE_OUTBOX
                    )
                } else {
                    Log.w("SPoTShield", "SMS send failed, result code $result")
                    SmsInboxRepository.updateType(context, messageId, Telephony.Sms.MESSAGE_TYPE_FAILED)
                }
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        private const val EXTRA_MESSAGE_ID = "message_id"

        fun pendingIntent(context: Context, messageId: Long): PendingIntent {
            val intent = Intent(context, SmsSentReceiver::class.java)
                .putExtra(EXTRA_MESSAGE_ID, messageId)

            return PendingIntent.getBroadcast(
                context,
                messageId.toInt(),
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }
    }
}
