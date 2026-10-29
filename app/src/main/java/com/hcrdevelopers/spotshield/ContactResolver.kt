package com.hcrdevelopers.spotshield

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Reads saved contacts and exposes them as a map of normalized number -> display name.
 * All lookups are blocking; call from a background thread.
 */
object ContactResolver {

    // Short, so a contact saved a moment ago is recognised when their message arrives.
    private const val CACHE_MS = 5_000L

    @Volatile private var cache: Map<String, String> = emptyMap()
    @Volatile private var cachedAt = 0L

    /** Drops the in-memory copy of the contacts; it is read again on next use. */
    fun clearCache() {
        cache = emptyMap()
        cachedAt = 0L
    }

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
                PackageManager.PERMISSION_GRANTED

    /** Returns null when contacts cannot be read (permission missing). */
    fun loadContacts(context: Context, force: Boolean = false): Map<String, String>? {
        if (!hasPermission(context)) return null

        val now = System.currentTimeMillis()
        if (!force && now - cachedAt < CACHE_MS) return cache

        val contacts = HashMap<String, String>()
        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                ),
                null,
                null,
                null
            )?.use { cursor ->
                val numberIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val nameIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)

                while (cursor.moveToNext()) {
                    val number = cursor.getString(numberIndex).orEmpty()
                    val name = cursor.getString(nameIndex).orEmpty()
                    val key = PhoneNormalizer.key(number)
                    if (key.isNotEmpty() && name.isNotBlank()) {
                        contacts.putIfAbsent(key, name)
                    }
                }
            }
        } catch (exception: SecurityException) {
            Log.w("SPoTShield", "Contacts permission revoked", exception)
            return null
        }

        cache = contacts
        cachedAt = now
        return contacts
    }

    fun isContact(context: Context, sender: String): Boolean? =
        loadContacts(context)?.containsKey(PhoneNormalizer.key(sender))

    /** Contact name when saved, otherwise the number itself. */
    fun displayName(context: Context, sender: String): String =
        loadContacts(context)?.get(PhoneNormalizer.key(sender)) ?: sender
}
