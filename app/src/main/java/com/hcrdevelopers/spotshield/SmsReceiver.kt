package com.hcrdevelopers.spotshield

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class SmsReceiver : BroadcastReceiver() {

    private companion object {
        // One worker, so bursts of messages are handled in order; it exits after 30s idle.
        val executor = ThreadPoolExecutor(
            0, 1, 30L, TimeUnit.SECONDS, LinkedBlockingQueue()
        )
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d("SPoTShield", "SmsReceiver triggered. Action: ${intent.action}")

        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) {
            Log.w("SPoTShield", "Unexpected SMS action: ${intent.action}")
            return
        }

        val pendingResult = goAsync()

        executor.execute {
            try {
                val messages =
                    Telephony.Sms.Intents.getMessagesFromIntent(intent)

                Log.d("SPoTShield", "SMS parts received: ${messages.size}")

                if (messages.isEmpty()) return@execute

                val fullBody = messages.joinToString(separator = "") {
                    it.messageBody.orEmpty()
                }

                val firstMessage = messages.first()
                val sender = firstMessage.originatingAddress.orEmpty()

                Log.d("SPoTShield", "Message received; body length: ${fullBody.length}")

                if (sender.isBlank() || fullBody.isBlank()) {
                    Log.w("SPoTShield", "Missing sender or message body")
                    return@execute
                }

                val decision = MessageRouter.route(context, sender)
                Log.d("SPoTShield", "Routing: ${decision.destination} (${decision.reason})")

                if (decision.destination == Destination.FRAUD) {
                    FilteredSmsStorage.save(
                        context = context,
                        sender = sender,
                        body = fullBody,
                        date = firstMessage.timestampMillis
                    )

                    // No notification for filtered messages.
                    return@execute
                }

                val values = ContentValues().apply {
                    put(Telephony.Sms.ADDRESS, sender)
                    put(Telephony.Sms.BODY, fullBody)
                    put(Telephony.Sms.DATE, firstMessage.timestampMillis)
                    put(Telephony.Sms.READ, 0)
                    put(Telephony.Sms.SEEN, 0)
                    put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
                }

                val savedUri = context.contentResolver.insert(
                    Telephony.Sms.Inbox.CONTENT_URI,
                    values
                )

                if (savedUri != null) {
                    Log.d("SPoTShield", "SMS insert returned URI: $savedUri")

                    SmsNotificationHelper.showNotification(
                        context = context,
                        sender = sender,
                        senderName = ContactResolver.displayName(context, sender),
                        message = fullBody
                    )
                } else {
                    Log.e("SPoTShield", "SMS insert returned null")
                }

            } catch (exception: Exception) {
                Log.e("SPoTShield", "Error processing incoming SMS", exception)
            } finally {
                pendingResult.finish()
            }
        }
    }
}