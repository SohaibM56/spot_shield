package com.hcrdevelopers.spotshield

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object SmsNotificationHelper {

    private const val CHANNEL_ID = "spot_shield_sms"
    private const val CHANNEL_NAME = "Incoming Messages"

    const val EXTRA_SENDER = "extra_sender"

    fun showNotification(
        context: Context,
        sender: String,
        senderName: String,
        message: String
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permissionGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (!permissionGranted) {
                Log.d(
                    "SPoTShield",
                    "Notification permission has not been granted."
                )
                return
            }
        }

        createNotificationChannel(context)

        val notificationIntent = Intent(
            context,
            MainActivity::class.java
        ).apply {
            putExtra(EXTRA_SENDER, sender)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            PhoneNormalizer.key(sender).hashCode(),
            notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(
            context,
            CHANNEL_ID
        )
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(senderName)
            .setContentText(message)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(message)
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        try {
            NotificationManagerCompat
                .from(context)
                .notify(
                    System.currentTimeMillis().toInt(),
                    notification
                )
        } catch (exception: SecurityException) {
            Log.e(
                "SPoTShield",
                "Unable to display SMS notification.",
                exception
            )
        }
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description =
                    "Notifications for legitimate incoming SMS messages"
            }

            val manager = context.getSystemService(
                NotificationManager::class.java
            )

            manager?.createNotificationChannel(channel)
        }
    }
}