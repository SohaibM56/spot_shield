package com.hcrdevelopers.spotshield

/**
 * Turns any phone number format into a stable comparison key so that
 * "+92 300 1234567", "0300-1234567" and "923001234567" all match.
 */
object PhoneNormalizer {

    private const val MATCH_DIGITS = 10

    fun key(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""

        // Alphanumeric sender IDs (e.g. "AMAZON") are compared by name.
        if (trimmed.any { it.isLetter() }) return "alpha:" + trimmed.lowercase()

        val digits = trimmed.filter { it.isDigit() }
        if (digits.isEmpty()) return trimmed

        // Keep the national part only, so country codes and trunk prefixes don't matter.
        return if (digits.length > MATCH_DIGITS) digits.takeLast(MATCH_DIGITS) else digits
    }
}
