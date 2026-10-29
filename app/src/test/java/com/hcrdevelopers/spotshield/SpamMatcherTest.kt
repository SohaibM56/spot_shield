package com.hcrdevelopers.spotshield

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpamMatcherTest {



    @Test

    fun avoidsPartialWordMatches() {

        assertFalse(

            SpamMatcher.isSpam(

                "The spacecraft is ready for launch."

            )

        )

        assertFalse(

            SpamMatcher.isSpam(

                "We have enough capacity for everyone."

            )

        )

    }

    @Test

    fun handlesEmptyMessagesAndKeywords() {

        assertFalse(

            SpamMatcher.isSpam("")

        )

        assertFalse(

            SpamMatcher.isSpam(

                message = "Please donate today.",

                keywords = emptyList()

            )

        )

    }


    @Test
    fun detectsFundraisingMessages() {
        val messages = listOf(
            "Please donate to our campaign today!",
            "We are fundraising to support our cause.",
            "Every dollar counts. Please contribute now.",
            "Can you chip in before our deadline?",
            "Help us reach our fundraising goal.",
            "Your donation will make a difference."
        )

        messages.forEach { message ->
            assertTrue(
                "Expected spam but was not detected: $message",
                SpamMatcher.isSpam(message)
            )
        }
    }

    @Test
    fun detectsPlatformNames() {
        val messages = listOf(
            "Donate today at GoFundMe.",
            "Support our campaign through ActBlue.",
            "Please contribute using WinRed."
        )

        messages.forEach { message ->
            assertTrue(
                "Expected spam but was not detected: $message",
                SpamMatcher.isSpam(message)
            )
        }
    }

    @Test
    fun matchingIsCaseInsensitive() {
        assertTrue(SpamMatcher.isSpam("PLEASE DONATE TODAY!"))
        assertTrue(SpamMatcher.isSpam("Every Dollar Counts"))
    }

    @Test
    fun avoidsSomeUnrelatedMessages() {
        val messages = listOf(
            "Your package will arrive tomorrow.",
            "Your appointment is confirmed for Monday.",
            "The weather will be sunny this afternoon."
        )

        messages.forEach { message ->
            assertFalse(
                "Unexpected spam match: $message",
                SpamMatcher.isSpam(message)
            )
        }
    }

    @Test
    fun supportsCustomKeywords() {
        val customKeywords = listOf("specialcampaign")

        assertTrue(
            SpamMatcher.isSpam(
                message = "Please review our specialcampaign.",
                keywords = customKeywords
            )
        )

        assertFalse(
            SpamMatcher.isSpam(
                message = "Your package is on its way.",
                keywords = customKeywords
            )
        )
    }

    @Test
    fun findMatchReturnsTheMatchingKeyword() {
        assertEquals("donate", SpamMatcher.findMatch("Please DONATE today", listOf("zakat", "donate")))
        assertEquals("every dollar counts", SpamMatcher.findMatch("Every dollar counts!", listOf(" every dollar counts ")))
        assertNull(SpamMatcher.findMatch("Hello, see you at lunch", listOf("donate")))
        assertNull(SpamMatcher.findMatch("", listOf("donate")))
    }
}