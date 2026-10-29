package com.hcrdevelopers.spotshield

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeywordOpsTest {

    private fun ok(result: KeywordResult) = (result as KeywordResult.Ok).keywords
    private fun reason(result: KeywordResult) = (result as KeywordResult.Rejected).reason

    @Test
    fun addPutsNewKeywordFirstAndCleansWhitespace() {
        val result = ok(KeywordOps.add(listOf("donate"), "  super   pac  "))

        assertEquals(listOf("super pac", "donate"), result)
    }

    @Test
    fun addRejectsBlankSilently() {
        assertEquals("", reason(KeywordOps.add(listOf("donate"), "   ")))
    }

    @Test
    fun addRejectsDuplicatesIgnoringCase() {
        assertTrue(reason(KeywordOps.add(listOf("Donate"), "donate")).contains("already"))
    }

    @Test
    fun addRejectsTooLongKeywords() {
        val tooLong = "a".repeat(KeywordOps.MAX_LENGTH + 1)

        assertTrue(reason(KeywordOps.add(emptyList(), tooLong)).contains("too long"))
    }

    @Test
    fun editKeepsPositionAndAppliesNewText() {
        val result = ok(KeywordOps.edit(listOf("a", "donate", "b"), "donate", " Donations "))

        assertEquals(listOf("a", "Donations", "b"), result)
    }

    @Test
    fun editCanOnlyChangeCaseOfTheSameKeyword() {
        val result = ok(KeywordOps.edit(listOf("donate"), "donate", "DONATE"))

        assertEquals(listOf("DONATE"), result)
    }

    @Test
    fun editRejectsNameOfAnotherKeywordAndBlank() {
        val list = listOf("donate", "zakat")

        assertTrue(reason(KeywordOps.edit(list, "donate", "ZAKAT")).contains("already"))
        assertEquals("Enter a keyword.", reason(KeywordOps.edit(list, "donate", "  ")))
    }

    @Test
    fun removeIgnoresCase() {
        assertEquals(listOf("zakat"), KeywordOps.remove(listOf("Donate", "zakat"), "donate"))
    }

    @Test
    fun deletingEverythingStaysEmpty() {
        assertEquals(emptyList<String>(), KeywordOps.remove(listOf("donate"), "donate"))
    }

    @Test
    fun restoreDefaultsAddsMissingOnesAndKeepsCustomKeywords() {
        val defaults = SpamKeywords.defaultKeywords
        val result = KeywordOps.restoreDefaults(listOf("my custom word", defaults.first()))

        assertEquals("my custom word", result.first())
        assertTrue(result.containsAll(defaults))
        assertEquals(defaults.size + 1, result.size)
    }
}
