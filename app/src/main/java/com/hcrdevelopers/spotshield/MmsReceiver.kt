package com.hcrdevelopers.spotshield

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class MmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // MMS handling will be implemented separately.
        Log.d("SPoTShield", "MMS delivery received")
    }
}