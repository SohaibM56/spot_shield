package com.hcrdevelopers.spotshield

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageClassifierTest {

    private fun classify(
        allowlisted: Boolean = false,
        blocked: Boolean = false,
        isContact: Boolean? = false
    ) = MessageClassifier.classify(allowlisted, blocked) { isContact }

    @Test
    fun allowlistBeatsBlocklist() {
        val decision = classify(allowlisted = true, blocked = true, isContact = false)

        assertEquals(Destination.INBOX, decision.destination)
        assertEquals(Reason.ALLOWLISTED, decision.reason)
        assertTrue(decision.shouldNotify)
    }

    @Test
    fun blockedSenderGoesToFraudEvenIfSavedContact() {
        val decision = classify(blocked = true, isContact = true)

        assertEquals(Destination.FRAUD, decision.destination)
        assertEquals(Reason.BLOCKED, decision.reason)
        assertFalse(decision.shouldNotify)
    }

    @Test
    fun savedContactStaysInInbox() {
        val decision = classify(isContact = true)

        assertEquals(Destination.INBOX, decision.destination)
        assertEquals(Reason.SAVED_CONTACT, decision.reason)
        assertTrue(decision.shouldNotify)
    }

    @Test
    fun unknownNumberGoesToFraudWithoutNotification() {
        val decision = classify(isContact = false)

        assertEquals(Destination.FRAUD, decision.destination)
        assertEquals(Reason.UNKNOWN_NUMBER, decision.reason)
        assertFalse(decision.shouldNotify)
    }

    @Test
    fun unreadableContactsKeepMessagesInInbox() {
        val decision = classify(isContact = null)

        assertEquals(Destination.INBOX, decision.destination)
        assertEquals(Reason.CONTACTS_UNAVAILABLE, decision.reason)
    }

    @Test
    fun contactsAreNotQueriedWhenAListDecidesFirst() {
        var queried = false
        MessageClassifier.classify(allowlisted = true, blocked = false) {
            queried = true
            true
        }
        MessageClassifier.classify(allowlisted = false, blocked = true) {
            queried = true
            true
        }

        assertFalse(queried)
    }
}
