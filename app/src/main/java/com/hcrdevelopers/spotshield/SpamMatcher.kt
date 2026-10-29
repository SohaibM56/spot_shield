package com.hcrdevelopers.spotshield

object SpamMatcher {

    // Compiling a regex is far costlier than running it, and the same keywords are
    // checked against every message, so each pattern is built once.
    private const val MAX_CACHED_PATTERNS = 1000
    private val patterns = java.util.concurrent.ConcurrentHashMap<String, Regex>()

    private fun patternFor(keyword: String): Regex {
        if (patterns.size > MAX_CACHED_PATTERNS) patterns.clear()

        return patterns.getOrPut(keyword) {
            Regex(
                pattern = """(?<![\p{L}\p{N}])${Regex.escape(keyword)}(?![\p{L}\p{N}])""",
                option = RegexOption.IGNORE_CASE
            )
        }
    }

    /**
     * Returns true if the message matches one or more spam keywords.
     */
    fun isSpam(
        message: String,
        keywords: List<String> = SpamKeywords.defaultKeywords
    ): Boolean {
        return findMatch(message, keywords) != null
    }

    /**
     * Returns the first keyword found in the message, or null when none match.
     */
    fun findMatch(
        message: String,
        keywords: List<String> = SpamKeywords.defaultKeywords
    ): String? {
        if (message.isBlank() || keywords.isEmpty()) {
            return null
        }

        return keywords.firstOrNull { keyword ->
            matchesKeyword(message, keyword)
        }?.trim()
    }

    /**
     * Checks a keyword without considering uppercase/lowercase differences.
     * Single-word keywords use word boundaries to reduce false positives.
     */
    private fun matchesKeyword(
        message: String,
        keyword: String
    ): Boolean {
        val cleanKeyword = keyword.trim()

        if (cleanKeyword.isEmpty()) {
            return false
        }

        // Phrases and domains are matched as text.
        if (cleanKeyword.contains(" ") || cleanKeyword.contains(".")) {
            return message.contains(cleanKeyword, ignoreCase = true)
        }

        // Match standalone words instead of accidental partial matches.
        val pattern = patternFor(cleanKeyword)

        return pattern.containsMatchIn(message)
    }
}