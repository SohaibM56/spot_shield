package com.hcrdevelopers.spotshield

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PhoneNormalizerTest {

    @Test
    fun matchesDifferentFormatsOfTheSameNumber() {
        val expected = PhoneNormalizer.key("0300 1234567")

        assertEquals(expected, PhoneNormalizer.key("+92 300 1234567"))
        assertEquals(expected, PhoneNormalizer.key("+92-300-1234567"))
        assertEquals(expected, PhoneNormalizer.key("00923001234567"))
        assertEquals(expected, PhoneNormalizer.key("(0300) 123-4567"))
    }

    @Test
    fun matchesUsNumbersWithAndWithoutCountryCode() {
        assertEquals(
            PhoneNormalizer.key("(555) 123-4567"),
            PhoneNormalizer.key("+1 555 123 4567")
        )
    }

    @Test
    fun differentNumbersDoNotMatch() {
        assertNotEquals(
            PhoneNormalizer.key("+1 555 123 4567"),
            PhoneNormalizer.key("+1 555 123 4568")
        )
    }

    @Test
    fun shortCodesKeepTheirDigits() {
        assertEquals("32665", PhoneNormalizer.key("32665"))
    }

    @Test
    fun alphanumericSendersAreComparedByNameIgnoringCase() {
        assertEquals(PhoneNormalizer.key("AMAZON"), PhoneNormalizer.key(" amazon "))
        assertNotEquals(PhoneNormalizer.key("AMAZON"), PhoneNormalizer.key("AMAZON2"))
    }

    @Test
    fun blankInputGivesEmptyKey() {
        assertEquals("", PhoneNormalizer.key("   "))
    }
}
