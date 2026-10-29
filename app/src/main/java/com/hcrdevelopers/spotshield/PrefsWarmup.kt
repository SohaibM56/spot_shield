package com.hcrdevelopers.spotshield

import android.content.Context

/**
 * The first read of a SharedPreferences file loads it from disk. Doing that while the first
 * screen is being drawn can freeze it, so it is started on a background thread right at launch.
 */
object PrefsWarmup {

    private val files = listOf(
        "spot_app",
        SpamKeywordStorage.PREFS_NAME,
        SenderListStorage.PREFS_NAME,
        EntitlementStorage.PREFS_NAME
    )

    fun start(context: Context) {
        val appContext = context.applicationContext
        Thread {
            files.forEach { name ->
                // Touching the contents makes Android finish loading the file.
                appContext.getSharedPreferences(name, Context.MODE_PRIVATE).all
            }
        }.start()
    }
}
