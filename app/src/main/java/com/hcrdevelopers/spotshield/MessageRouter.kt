package com.hcrdevelopers.spotshield

import android.content.Context

enum class Destination { INBOX, FRAUD }

enum class Reason {
    ALLOWLISTED,
    BLOCKED,
    SAVED_CONTACT,
    CONTACTS_UNAVAILABLE,
    UNKNOWN_NUMBER
}

data class RoutingDecision(val destination: Destination, val reason: Reason) {
    /** Normal notifications are only shown for messages that reach the Inbox. */
    val shouldNotify: Boolean get() = destination == Destination.INBOX
}

object MessageClassifier {

    /**
     * Priority order:
     * 1. Allowlisted sender  -> Inbox
     * 2. Blocked sender      -> Fraud
     * 3. Saved contact       -> Inbox
     * 4. Unknown number      -> Fraud
     *
     * [isContact] returns null when contacts can't be read; in that case the message
     * stays in the Inbox rather than hiding mail from people the user may know.
     */
    fun classify(
        allowlisted: Boolean,
        blocked: Boolean,
        isContact: () -> Boolean?
    ): RoutingDecision {
        if (allowlisted) return RoutingDecision(Destination.INBOX, Reason.ALLOWLISTED)
        if (blocked) return RoutingDecision(Destination.FRAUD, Reason.BLOCKED)

        return when (isContact()) {
            true -> RoutingDecision(Destination.INBOX, Reason.SAVED_CONTACT)
            null -> RoutingDecision(Destination.INBOX, Reason.CONTACTS_UNAVAILABLE)
            false -> RoutingDecision(Destination.FRAUD, Reason.UNKNOWN_NUMBER)
        }
    }
}

object MessageRouter {

    fun route(context: Context, sender: String): RoutingDecision =
        MessageClassifier.classify(
            allowlisted = SenderListStorage.isAllowlisted(context, sender),
            blocked = SenderListStorage.isBlocked(context, sender),
            isContact = { ContactResolver.isContact(context, sender) }
        )
}
