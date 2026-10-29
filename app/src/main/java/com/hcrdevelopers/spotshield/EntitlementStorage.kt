package com.hcrdevelopers.spotshield

import android.content.Context

/**
 * Local copy of the last verified subscription status, so code that cannot talk to
 * Google Play (e.g. the SMS receiver) can still check it once premium gating is enabled.
 */
object EntitlementStorage {

    const val PREFS_NAME = "spot_entitlement"
    private const val KEY_ACTIVE = "active"
    private const val KEY_VERIFIED_AT = "verified_at"

    // How long a cached "active" status stays valid if Google Play can't be reached.
    private const val OFFLINE_GRACE_MS = 7L * 24 * 60 * 60 * 1000

    fun isActive(context: Context): Boolean {
        val prefs = prefs(context)
        if (!prefs.getBoolean(KEY_ACTIVE, false)) return false

        val verifiedAt = prefs.getLong(KEY_VERIFIED_AT, 0L)
        return System.currentTimeMillis() - verifiedAt < OFFLINE_GRACE_MS
    }

    fun set(context: Context, active: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_ACTIVE, active)
            .putLong(KEY_VERIFIED_AT, System.currentTimeMillis())
            .apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
