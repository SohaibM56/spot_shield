package com.hcrdevelopers.spotshield

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor

// Accent colors used across the screens.
internal val Black = Color(0xFF000000)
internal val DarkCard = Color(0xFF1C1C1E)
internal val DividerColor = Color(0xFF38383A)
internal val Blue = Color(0xFF079EFF)
internal val White = Color(0xFFFFFFFF)
internal val Gray = Color(0xFF929298)
internal val Red = Color(0xFFFF3B40)
internal val Green = Color(0xFF30D158)

// The text cursor must be white: the default (black) is invisible on the dark screens.
internal val CursorBrush = SolidColor(Color.White)

internal enum class ShieldTab(val label: String, val icon: String) {
    MESSAGES("Messages", "✉"),
    FILTERED("Fraud", "⊘"),
    SETTINGS("Settings", "⚙")
}

internal enum class SenderListType { TRUSTED, BLOCKED }

internal typealias SmsConversation = SmsConversationSummary

/** How many messages a chat loads at a time; older ones load as the user scrolls up. */
internal const val CHAT_PAGE_SIZE = 40

/** One-off things the screen has to react to: a toast, or navigation after an action finished. */
internal sealed interface UiEvent {
    data class Message(val text: String, val long: Boolean = false) : UiEvent

    /** A sender was moved to Fraud: close the chat and offer Undo. */
    data class MarkedAsFraud(val name: String, val sender: String) : UiEvent

    /** A Fraud message was moved to the Inbox: close its detail screen. */
    data object Unspammed : UiEvent
}
