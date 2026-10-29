package com.hcrdevelopers.spotshield

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyFraudJsonTest {

    @Test
    fun parsesAllFieldsOfTheOldFormat() {
        val json = """
            [{"id":"1_a_9","sender":"+15551234567","body":"Please donate","date":1000,"read":false,"keyword":"donate"}]
        """.trimIndent()

        val messages = LegacyFraudJson.parse(json)

        assertEquals(1, messages.size)
        with(messages.first()) {
            assertEquals("1_a_9", id)
            assertEquals("+15551234567", sender)
            assertEquals("Please donate", body)
            assertEquals(1000L, date)
            assertFalse(isRead)
            assertEquals("donate", matchedKeyword)
        }
    }

    @Test
    fun messagesSavedBeforeReadTrackingCountAsReadWithoutKeyword() {
        val json = """[{"id":"x","sender":"AMAZON","body":"hi","date":5}]"""

        val message = LegacyFraudJson.parse(json).single()

        assertTrue(message.isRead)
        assertNull(message.matchedKeyword)
    }

    @Test
    fun emptyKeywordStringBecomesNull() {
        val json = """[{"id":"x","sender":"a","body":"b","date":1,"read":true,"keyword":""}]"""

        assertNull(LegacyFraudJson.parse(json).single().matchedKeyword)
    }

    @Test
    fun brokenJsonGivesAnEmptyList() {
        assertTrue(LegacyFraudJson.parse("{not json").isEmpty())
        assertTrue(LegacyFraudJson.parse("[]").isEmpty())
    }
}
