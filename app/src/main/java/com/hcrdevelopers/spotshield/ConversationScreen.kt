package com.hcrdevelopers.spotshield

import android.Manifest
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.StrictMode
import android.provider.ContactsContract
import android.provider.Settings
import android.provider.Telephony
import android.telephony.SmsManager
import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.hcrdevelopers.spotshield.ui.theme.SPoTShieldTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal sealed interface ChatItem {
    val key: String

    data class DayHeader(val label: String, val day: Long) : ChatItem {
        override val key: String get() = "day_$day"
    }

    data class Bubble(val message: SmsInboxMessage) : ChatItem {
        override val key: String get() = "${message.id}_${message.type}"
    }
}

/** Interleaves day dividers ("Today", "Yesterday", …) with the messages, oldest first. */
internal fun buildChatItems(messages: List<SmsInboxMessage>): List<ChatItem> {
    val items = ArrayList<ChatItem>(messages.size + 8)
    var lastDay = Long.MIN_VALUE

    messages.forEach { message ->
        val day = dayStart(message.date)
        if (day != lastDay) {
            items.add(ChatItem.DayHeader(formatDayHeader(message.date), day))
            lastDay = day
        }
        items.add(ChatItem.Bubble(message))
    }
    return items
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MessageBubble(message: SmsInboxMessage, onLongPress: () -> Unit) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val time = remember(message.date) { formatTime(context, message.date) }

    val failed = message.type == Telephony.Sms.MESSAGE_TYPE_FAILED
    val sending = message.type == Telephony.Sms.MESSAGE_TYPE_OUTBOX
    val outgoing = failed || sending || message.type == Telephony.Sms.MESSAGE_TYPE_SENT
    val shape = RoundedCornerShape(
        topStart = 18.dp,
        topEnd = 18.dp,
        bottomStart = if (outgoing) 18.dp else 4.dp,
        bottomEnd = if (outgoing) 4.dp else 18.dp
    )

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start
    ) {
        Column(
            Modifier
                .widthIn(max = 300.dp)
                .clip(shape)
                .background(
                    when {
                        failed -> Color(0xFF8A2D30)
                        outgoing -> Color(0xFF075EAA)
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                )
                .combinedClickable(
                    onClick = {},
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongPress()
                    },
                    onLongClickLabel = "Delete message"
                )
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(
                message.body,
                color = if (outgoing) Color.White else MaterialTheme.colorScheme.onSurface,
                fontSize = 15.sp,
                lineHeight = 21.sp
            )

            Spacer(Modifier.height(4.dp))

            Text(
                when {
                    failed -> "Not sent • $time"
                    sending -> "Sending…"
                    else -> time
                },
                color = if (outgoing) Color(0xFFD0D7E2) else MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp
            )
        }
    }
}

@Composable
internal fun ConversationScreen(
    conversation: SmsConversation,
    messages: List<SmsInboxMessage>,
    hasMore: Boolean,
    onLoadOlder: () -> Unit,
    name: String,
    onBack: () -> Unit,
    onSendSms: (String, String) -> Unit,
    onDeleteMessage: (SmsInboxMessage) -> Unit,
    onMarkFraud: () -> Unit
) {
    // Saved, so a half-typed message survives rotating the phone.
    var messageText by rememberSaveable(conversation.key) { mutableStateOf("") }
    val context = LocalContext.current

    val chatItems = remember(messages) {
        buildChatItems(messages.sortedBy { it.date })
    }

    val listState = rememberLazyListState()

    // The newest message the list has already been scrolled to. It is saved with the screen, so after
    // a rotation the restored scroll position is kept instead of jumping back to the bottom.
    var lastNewestKey by rememberSaveable(conversation.key) { mutableStateOf<String?>(null) }

    // Keyed on the newest item, so loading older pages above it never yanks the list to the bottom.
    LaunchedEffect(conversation.key, chatItems.lastOrNull()?.key) {
        val newest = chatItems.lastOrNull()?.key ?: return@LaunchedEffect

        when (lastNewestKey) {
            null -> listState.scrollToItem(chatItems.lastIndex)         // the chat was just opened
            newest -> Unit                                              // rotated: position was restored
            else -> listState.animateScrollToItem(chatItems.lastIndex)  // a new message arrived
        }
        lastNewestKey = newest
    }

    val scrolledToStart = lastNewestKey != null

    // Near the top of what is loaded: fetch the next page of older messages.
    LaunchedEffect(listState, hasMore, scrolledToStart) {
        if (!hasMore || !scrolledToStart) return@LaunchedEffect

        snapshotFlow { listState.firstVisibleItemIndex }.collect { index ->
            if (index <= 3) onLoadOlder()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .topSafePadding()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BackButton(onBack)
            Spacer(Modifier.width(10.dp))
            Avatar(name, 40.dp)
            Spacer(Modifier.width(10.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    name,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    if (name != conversation.sender) conversation.sender
                    else "${conversation.messageCount} message(s)",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Text(
                "Mark as Fraud",
                color = Red,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(role = Role.Button, onClick = onMarkFraud)
                    .padding(horizontal = 10.dp, vertical = 12.dp)
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(chatItems, key = { it.key }) { item ->
                when (item) {
                    is ChatItem.DayHeader -> Box(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            item.label,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                        )
                    }

                    is ChatItem.Bubble -> MessageBubble(
                        message = item.message,
                        onLongPress = { onDeleteMessage(item.message) }
                    )
                }
            }
        }

        val canSend = messageText.isNotBlank()

        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            BasicTextField(
                value = messageText,
                onValueChange = { messageText = it },
                maxLines = 5,
                textStyle = TextStyle(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 15.sp
                ),
                cursorBrush = CursorBrush,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    keyboardType = KeyboardType.Text
                ),
                modifier = Modifier
                    .weight(1f)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(22.dp)
                    )
                    .padding(horizontal = 16.dp, vertical = 13.dp),
                decorationBox = { inner ->
                    Box {
                        if (messageText.isEmpty()) {
                            Text(
                                "Text message",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 15.sp
                            )
                        }
                        inner()
                    }
                }
            )

            Spacer(Modifier.width(8.dp))

            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(if (canSend) Blue else MaterialTheme.colorScheme.outlineVariant)
                    .semantics { contentDescription = "Send message" }
                    .clickable(enabled = canSend, role = Role.Button) {
                        val text = messageText.trim()
                        if (text.isEmpty()) return@clickable

                        if (
                            ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.SEND_SMS
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            Toast.makeText(
                                context,
                                "SMS permission is required.",
                                Toast.LENGTH_LONG
                            ).show()
                            return@clickable
                        }

                        onSendSms(conversation.sender, text)
                        messageText = ""
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "➤",
                    color = Color.White,
                    fontSize = 20.sp,
                    modifier = Modifier.clearAndSetSemantics { }
                )
            }
        }
    }
}

@Composable
internal fun EmptyConversationScreen(sender: String, onBack: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .topSafePadding()
            .navigationBarsPadding()
    ) {
        ScreenTopBar(sender, onBack)
        Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
            Text(
                "This conversation is no longer available.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
            )
        }
    }
}
