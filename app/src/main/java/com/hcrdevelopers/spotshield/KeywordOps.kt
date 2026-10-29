package com.hcrdevelopers.spotshield

sealed interface KeywordResult {
    data class Ok(val keywords: List<String>) : KeywordResult

    /** [reason] is empty when the input was blank and nothing needs to be shown. */
    data class Rejected(val reason: String) : KeywordResult
}

/** Pure add / edit / remove rules for the keyword list. No Android, no storage. */
object KeywordOps {

    const val MAX_LENGTH = 60

    fun clean(raw: String): String = raw.trim().replace(Regex("\\s+"), " ")

    /** New keywords go to the top of the list so they are easy to find. */
    fun add(keywords: List<String>, raw: String): KeywordResult {
        val word = clean(raw)

        if (word.isEmpty()) return KeywordResult.Rejected("")
        if (word.length > MAX_LENGTH) return KeywordResult.Rejected(tooLong())
        if (keywords.any { it.equals(word, ignoreCase = true) }) {
            return KeywordResult.Rejected(duplicate(word))
        }

        return KeywordResult.Ok(listOf(word) + keywords)
    }

    /** Renames [original] in place, keeping its position. */
    fun edit(keywords: List<String>, original: String, raw: String): KeywordResult {
        val word = clean(raw)

        if (word.isEmpty()) return KeywordResult.Rejected("Enter a keyword.")
        if (word.length > MAX_LENGTH) return KeywordResult.Rejected(tooLong())
        if (word == original) return KeywordResult.Ok(keywords)

        val clashes = keywords.any {
            !it.equals(original, ignoreCase = true) && it.equals(word, ignoreCase = true)
        }
        if (clashes) return KeywordResult.Rejected(duplicate(word))

        return KeywordResult.Ok(
            keywords.map { if (it.equals(original, ignoreCase = true)) word else it }
        )
    }

    fun remove(keywords: List<String>, word: String): List<String> =
        keywords.filterNot { it.equals(word, ignoreCase = true) }

    /** Adds back any bundled default that is missing; never removes or changes the user's own. */
    fun restoreDefaults(keywords: List<String>): List<String> =
        keywords + SpamKeywords.defaultKeywords.filter { default ->
            keywords.none { it.equals(default, ignoreCase = true) }
        }

    private fun tooLong() = "Keyword is too long (max $MAX_LENGTH characters)."

    private fun duplicate(word: String) = "\"$word\" is already in your list."
}
