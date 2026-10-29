package com.hcrdevelopers.spotshield

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Holds the data behind the screens and does all loading and changing off the main thread.
 * It survives rotation, so nothing is reloaded just because the screen turned.
 * The composables only draw state and forward taps.
 */
@OptIn(FlowPreview::class)
internal class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val context: Context = application
    private val keywordStorage = SpamKeywordStorage(application)

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------------ one-off events

    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events: Flow<UiEvent> = _events.receiveAsFlow()

    private fun message(text: String, long: Boolean = false) {
        _events.trySend(UiEvent.Message(text, long))
    }

    // ------------------------------------------------------------------ conversations

    private val _conversations = MutableStateFlow<List<SmsConversation>>(emptyList())
    val conversations: StateFlow<List<SmsConversation>> = _conversations.asStateFlow()

    private val _isLoadingInbox = MutableStateFlow(false)
    val isLoadingInbox: StateFlow<Boolean> = _isLoadingInbox.asStateFlow()

    private val _inboxError = MutableStateFlow<String?>(null)
    val inboxError: StateFlow<String?> = _inboxError.asStateFlow()

    private var conversationsJob: Job? = null

    /** Reloads the conversation list. [showSpinner] is for the manual refresh button. */
    fun refreshConversations(showSpinner: Boolean = false) {
        conversationsJob?.cancel()
        conversationsJob = viewModelScope.launch { loadConversations(showSpinner) }
    }

    private suspend fun loadConversations(showSpinner: Boolean) {
        if (!hasPermission(Manifest.permission.READ_SMS)) {
            if (showSpinner) {
                _inboxError.value = "SMS permission is not granted. Allow SMS access in Android settings."
            }
            return
        }

        if (showSpinner || _conversations.value.isEmpty()) _isLoadingInbox.value = true
        if (showSpinner) _inboxError.value = null

        try {
            _conversations.value = SmsInboxRepository.getConversations(context)
            _inboxError.value = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _inboxError.value = e.message ?: "Unable to load messages."
        } finally {
            _isLoadingInbox.value = false
        }
    }

    // ------------------------------------------------------------------ chat (one conversation, paged)

    private val openChatKey = MutableStateFlow<String?>(null)
    private val chatLimit = MutableStateFlow(CHAT_PAGE_SIZE)

    private val _chatMessages = MutableStateFlow<List<SmsInboxMessage>>(emptyList())
    val chatMessages: StateFlow<List<SmsInboxMessage>> = _chatMessages.asStateFlow()

    /** True while there may be older messages above the ones loaded. */
    val chatHasMore: StateFlow<Boolean> = combine(_chatMessages, chatLimit) { messages, limit ->
        messages.size >= limit
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Opens a chat by its conversation key, or closes it with null. */
    fun openChat(key: String?) {
        if (openChatKey.value == key) return
        chatLimit.value = CHAT_PAGE_SIZE
        _chatMessages.value = emptyList()
        openChatKey.value = key
    }

    fun loadOlderChat() {
        chatLimit.update { it + CHAT_PAGE_SIZE }
    }

    fun markConversationRead(conversation: SmsConversation) {
        viewModelScope.launch {
            val updated = SmsInboxRepository.markConversationRead(context, conversation.addresses)
            if (updated > 0) {
                _conversations.update { list ->
                    list.map { if (it.key == conversation.key) it.copy(unreadCount = 0) else it }
                }
            }
        }
    }

    // ------------------------------------------------------------------ Fraud list: one conversation per sender

    private val _fraudGroups = MutableStateFlow<List<FraudGroup>>(emptyList())
    val fraudGroups: StateFlow<List<FraudGroup>> = _fraudGroups.asStateFlow()

    /** True once the Fraud list has been read at least once (an empty list before that means "loading"). */
    private val _fraudLoaded = MutableStateFlow(false)
    val fraudLoaded: StateFlow<Boolean> = _fraudLoaded.asStateFlow()

    private val _fraudUnread = MutableStateFlow(0)
    val fraudUnread: StateFlow<Int> = _fraudUnread.asStateFlow()

    private val openFraudKey = MutableStateFlow<String?>(null)

    private val _fraudThread = MutableStateFlow<List<FilteredSmsMessage>>(emptyList())

    /** Messages of the open Fraud conversation, newest first. */
    val fraudThread: StateFlow<List<FilteredSmsMessage>> = _fraudThread.asStateFlow()

    /** Opens a Fraud conversation by its sender key, or closes it with null. */
    fun openFraudThread(key: String?) {
        if (openFraudKey.value == key) return
        _fraudThread.value = emptyList()
        openFraudKey.value = key
    }

    fun refreshFraud() = FilteredSmsStorage.invalidate()

    fun markFraudSenderRead(sender: String) {
        viewModelScope.launch(Dispatchers.IO) { FilteredSmsStorage.markSenderRead(context, sender) }
    }

    fun deleteFraudMessage(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            FilteredSmsStorage.delete(context, id)
            message("Fraud message deleted.")
        }
    }

    fun deleteFraudSender(sender: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val removed = FilteredSmsStorage.deleteSender(context, sender)
            message(if (removed == 1) "Fraud message deleted." else "$removed fraud messages deleted.")
        }
    }

    // ------------------------------------------------------------------ contacts, trusted and blocked senders

    private val _contactNames = MutableStateFlow<Map<String, String>>(emptyMap())
    val contactNames: StateFlow<Map<String, String>> = _contactNames.asStateFlow()

    // False when Android refused SMS access, e.g. "restricted settings" for apps installed outside Google Play.
    private val _smsPermissionGranted = MutableStateFlow(hasPermission(Manifest.permission.READ_SMS))
    val smsPermissionGranted: StateFlow<Boolean> = _smsPermissionGranted.asStateFlow()

    private val _contactsGranted = MutableStateFlow(hasPermission(Manifest.permission.READ_CONTACTS))
    val contactsGranted: StateFlow<Boolean> = _contactsGranted.asStateFlow()

    private val _allowedSenders = MutableStateFlow<List<SenderEntry>>(emptyList())
    val allowedSenders: StateFlow<List<SenderEntry>> = _allowedSenders.asStateFlow()

    private val _blockedSenders = MutableStateFlow<List<SenderEntry>>(emptyList())
    val blockedSenders: StateFlow<List<SenderEntry>> = _blockedSenders.asStateFlow()

    private var contactsJob: Job? = null

    fun refreshContacts() {
        contactsJob?.cancel()
        contactsJob = viewModelScope.launch {
            val granted = hasPermission(Manifest.permission.READ_CONTACTS)
            _contactsGranted.value = granted
            _contactNames.value = if (granted) {
                withContext(Dispatchers.IO) {
                    ContactResolver.loadContacts(context, force = true)
                } ?: emptyMap()
            } else {
                emptyMap()
            }
        }
    }

    fun refreshSenderLists() {
        viewModelScope.launch(Dispatchers.IO) {
            _allowedSenders.value = SenderListStorage.getAllowed(context)
            _blockedSenders.value = SenderListStorage.getBlocked(context)
        }
    }

    fun removeTrusted(number: String) {
        viewModelScope.launch(Dispatchers.IO) {
            SenderListStorage.removeAllowed(context, number)
            refreshSenderLists()
        }
    }

    private fun nameFor(sender: String): String =
        _contactNames.value[PhoneNormalizer.key(sender)] ?: sender

    // ------------------------------------------------------------------ keywords

    private val _keywords = MutableStateFlow<List<String>>(emptyList())
    val keywords: StateFlow<List<String>> = _keywords.asStateFlow()

    fun setKeywords(updated: List<String>) {
        _keywords.value = updated
        viewModelScope.launch(Dispatchers.IO) { keywordStorage.saveKeywords(updated) }
    }

    // ------------------------------------------------------------------ actions that move or change messages

    /** Blocks the sender and moves their messages to Fraud; the screen then offers Undo. */
    fun markAsFraud(conversation: SmsConversation) {
        viewModelScope.launch {
            val name = nameFor(conversation.sender)
            val result = SenderActions.markAsFraud(context, conversation.sender, conversation.addresses)
            reloadAfterMove()

            if (result.found > 0 && result.moved == 0) {
                message(
                    "$name is blocked, but existing messages could not be moved. " +
                            "Make sure SPoT Shield is the default SMS app.",
                    long = true
                )
            }
            _events.send(UiEvent.MarkedAsFraud(name, conversation.sender))
        }
    }

    fun undoMarkAsFraud(sender: String) {
        viewModelScope.launch {
            SenderActions.restoreSender(context, sender, allowlist = false)
            reloadAfterMove()
        }
    }

    /** Moves a sender's Fraud messages back to the Inbox and trusts them from now on. */
    fun unspam(sender: String) {
        if (!isDefaultSmsApp(context)) {
            message("Set SPoT Shield as the default SMS app to move messages to your Inbox.", long = true)
            return
        }

        viewModelScope.launch {
            val name = nameFor(sender)
            val result = SenderActions.restoreSender(context, sender, allowlist = true)
            reloadAfterMove()

            _events.send(UiEvent.Unspammed)
            message(
                if (result.failed == 0) {
                    "$name moved to Inbox. Future messages will always reach your Inbox."
                } else {
                    "Some messages from $name could not be moved to the Inbox."
                },
                long = true
            )
        }
    }

    fun deleteMessage(id: Long) {
        viewModelScope.launch {
            val deleted = SmsInboxRepository.deleteMessage(context, id)
            loadConversations(showSpinner = false)
            message(
                if (deleted) "SMS deleted."
                else "Could not delete SMS. Make sure SPoT Shield is the default SMS app.",
                long = true
            )
        }
    }

    fun deleteConversation(addresses: List<String>) {
        viewModelScope.launch {
            val count = SmsInboxRepository.deleteConversation(context, addresses)
            loadConversations(showSpinner = false)
            message(
                if (count > 0) "Conversation deleted."
                else "Could not delete conversation. Make sure SPoT Shield is the default SMS app.",
                long = true
            )
        }
    }

    fun sendSms(recipient: String, body: String) {
        if (recipient.isBlank() || body.isBlank()) return

        if (!isDefaultSmsApp(context)) {
            message("Please set SPoT Shield as the default SMS app first.", long = true)
            return
        }
        if (!hasPermission(Manifest.permission.SEND_SMS)) {
            message("SMS sending permission is not granted.", long = true)
            return
        }

        viewModelScope.launch {
            // The message goes into the SMS database first, so the chat shows it as "Sending…".
            val messageId = SmsInboxRepository.insertOutgoing(
                context,
                recipient,
                body,
                System.currentTimeMillis()
            )

            try {
                withContext(Dispatchers.IO) {
                    val manager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        context.getSystemService(SmsManager::class.java)
                            ?: throw IllegalStateException("SMS service is unavailable.")
                    } else {
                        @Suppress("DEPRECATION")
                        SmsManager.getDefault()
                    }
                    val sentIntent = messageId?.let { SmsSentReceiver.pendingIntent(context, it) }
                    val parts = manager.divideMessage(body)
                    if (parts.size > 1) {
                        manager.sendMultipartTextMessage(
                            recipient,
                            null,
                            parts,
                            ArrayList(parts.map { sentIntent }),
                            null
                        )
                    } else {
                        manager.sendTextMessage(recipient, null, body, sentIntent, null)
                    }
                }
                loadConversations(showSpinner = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                messageId?.let {
                    SmsInboxRepository.updateType(context, it, Telephony.Sms.MESSAGE_TYPE_FAILED)
                }
                loadConversations(showSpinner = false)
                message("Unable to send SMS: ${e.message ?: "Unknown error"}", long = true)
            }
        }
    }

    private suspend fun reloadAfterMove() {
        FilteredSmsStorage.invalidate()
        refreshSenderLists()
        loadConversations(showSpinner = false)
    }

    // ------------------------------------------------------------------ signals from the screen

    private val smsChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val contactsChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** The system SMS database changed (called for every write, so it is debounced). */
    fun onSmsDatabaseChanged() {
        smsChanged.tryEmit(Unit)
    }

    /** A contact was added or edited (a sync can fire many of these, so it is debounced). */
    fun onContactsChanged() {
        contactsChanged.tryEmit(Unit)
    }

    /** Permissions were granted or revoked. */
    fun onPermissionsChanged() {
        _smsPermissionGranted.value = hasPermission(Manifest.permission.READ_SMS)
        refreshContacts()
        refreshConversations()
    }

    fun onResume() {
        _smsPermissionGranted.value = hasPermission(Manifest.permission.READ_SMS)
        FilteredSmsStorage.invalidate()
        refreshSenderLists()
        // Contacts are only re-read when access was granted or revoked in system settings.
        if (hasPermission(Manifest.permission.READ_CONTACTS) != _contactsGranted.value) refreshContacts()
        refreshConversations()
    }

    // ------------------------------------------------------------------ wiring

    init {
        viewModelScope.launch(Dispatchers.IO) { _keywords.value = keywordStorage.getKeywords() }
        refreshContacts()
        refreshSenderLists()
        refreshConversations()

        viewModelScope.launch { smsChanged.debounce(300).collect { refreshConversations() } }
        viewModelScope.launch { contactsChanged.debounce(500).collect { refreshContacts() } }

        // The open chat reloads when its page size grows or the conversation list changes.
        viewModelScope.launch {
            combine(openChatKey, chatLimit, _conversations) { key, limit, list -> Triple(key, limit, list) }
                .collectLatest { (key, limit, list) ->
                    val addresses = key?.let { open -> list.firstOrNull { it.key == open }?.addresses }
                        ?: return@collectLatest

                    try {
                        _chatMessages.value = SmsInboxRepository.getMessages(context, addresses, limit)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                    }
                }
        }

        // The Fraud list and the open conversation reload after any change to the Fraud database.
        viewModelScope.launch {
            combine(FilteredSmsStorage.changes, openFraudKey) { _, key -> key }.collectLatest { key ->
                try {
                    val (groups, unread, thread) = withContext(Dispatchers.IO) {
                        Triple(
                            FilteredSmsStorage.getGroups(context),
                            FilteredSmsStorage.unreadCount(context),
                            key?.let { FilteredSmsStorage.getByKey(context, it) }.orEmpty()
                        )
                    }
                    _fraudGroups.value = groups
                    _fraudUnread.value = unread
                    _fraudThread.value = thread
                    _fraudLoaded.value = true
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }
        }
    }
}
