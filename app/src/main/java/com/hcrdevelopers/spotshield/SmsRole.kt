package com.hcrdevelopers.spotshield

import android.app.role.RoleManager
import android.content.Context
import android.os.Build
import android.provider.Telephony

/** True when SPoT Shield is the phone's default SMS app (needed to write or delete messages). */
fun isDefaultSmsApp(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_SMS) == true
    } else {
        @Suppress("DEPRECATION")
        Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
    }
