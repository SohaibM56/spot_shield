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
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
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

class MainActivity : ComponentActivity() {
    private lateinit var billing: BillingManager

    var notificationSender by mutableStateOf<String?>(null)
        private set

    fun clearNotificationSender() {
        notificationSender = null
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)

        // Black status and navigation bars with white icons (time, battery, signal) on every phone.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK)
        )

        // Debug builds only: log slow work on the main thread so it can be found and fixed.
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .detectNetwork()
                    .penaltyLog()
                    .build()
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectLeakedClosableObjects()
                    .detectLeakedSqlLiteObjects()
                    .detectActivityLeaks()
                    .penaltyLog()
                    .build()
            )
        }

        PrefsWarmup.start(this)

        billing = BillingManager(this)
        billing.start()

        notificationSender = intent.getStringExtra(SmsNotificationHelper.EXTRA_SENDER)

        setContent {
            // Keep SPoT Shield in dark mode regardless of the device theme.
            SPoTShieldTheme(darkTheme = true) {
                SPoTShieldApp(
                    billing = billing,
                    launchPurchase = { billing.launchPurchase(this) },
                    openManageSubscription = { openManageSubscription() },
                    isDefaultSmsApp = { isDefaultSmsApp() },
                    requestDefaultSmsApp = { requestDefaultSmsApp() },
                    openDefaultAppSettings = { openDefaultAppSettings() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        billing.refresh()
    }

    override fun onDestroy() {
        billing.end()
        super.onDestroy()
    }

    private fun openManageSubscription() {
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(BillingManager.manageSubscriptionUrl(packageName)))
            )
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Open Google Play > Payments & subscriptions to manage it.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        notificationSender = intent.getStringExtra(SmsNotificationHelper.EXTRA_SENDER)
    }

    private fun isDefaultSmsApp(): Boolean = com.hcrdevelopers.spotshield.isDefaultSmsApp(this)

    private fun requestDefaultSmsApp() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_SMS)) {
                defaultSmsRoleLauncher.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS))
            }
        } else {
            @Suppress("DEPRECATION")
            val changeIntent = Intent("android.provider.Telephony.ACTION_CHANGE_DEFAULT")
                .putExtra("package", packageName)
            defaultSmsRoleLauncher.launch(changeIntent)
        }
    }

    private fun openDefaultAppSettings() {
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Open Settings > Apps > Default apps to change your SMS app.", Toast.LENGTH_LONG).show()
        }
    }

    private val defaultSmsRoleLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            recreate()
        }
}

@Composable
private fun SPoTShieldApp(
    billing: BillingManager,
    launchPurchase: () -> Unit,
    openManageSubscription: () -> Unit,
    isDefaultSmsApp: () -> Boolean,
    requestDefaultSmsApp: () -> Unit,
    openDefaultAppSettings: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val notificationPermissionLauncher =
        androidx.activity.compose.rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            Toast.makeText(
                context,
                if (granted) "Notifications enabled!" else "Notification permission denied.",
                if (granted) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
            ).show()
        }

    val vm: MainViewModel = viewModel()
    val snackbarHostState = remember { SnackbarHostState() }
    val subscription = billing.state

    // Data comes from the ViewModel; it is loaded off the main thread and survives rotation.
    val conversations by vm.conversations.collectAsStateWithLifecycle()
    val isLoadingInbox by vm.isLoadingInbox.collectAsStateWithLifecycle()
    val inboxError by vm.inboxError.collectAsStateWithLifecycle()
    val chatMessages by vm.chatMessages.collectAsStateWithLifecycle()
    val chatHasMore by vm.chatHasMore.collectAsStateWithLifecycle()
    val fraudGroups by vm.fraudGroups.collectAsStateWithLifecycle()
    val fraudThread by vm.fraudThread.collectAsStateWithLifecycle()
    val fraudLoaded by vm.fraudLoaded.collectAsStateWithLifecycle()
    val fraudUnread by vm.fraudUnread.collectAsStateWithLifecycle()
    val contactNames by vm.contactNames.collectAsStateWithLifecycle()
    val contactsGranted by vm.contactsGranted.collectAsStateWithLifecycle()
    val smsGranted by vm.smsPermissionGranted.collectAsStateWithLifecycle()
    val allowedSenders by vm.allowedSenders.collectAsStateWithLifecycle()
    val blockedSenders by vm.blockedSenders.collectAsStateWithLifecycle()
    val keywords by vm.keywords.collectAsStateWithLifecycle()

    fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    var showContactsRationale by remember { mutableStateOf(false) }
    var showSubscription by rememberSaveable { mutableStateOf(false) }
    var openSenderList by rememberSaveable { mutableStateOf<SenderListType?>(null) }

    fun maybeAskForContacts() {
        scope.launch {
            val alreadyAsked = withContext(Dispatchers.IO) {
                context.getSharedPreferences("spot_app", Context.MODE_PRIVATE)
                    .getBoolean("contacts_asked", false)
            }
            if (!hasPermission(Manifest.permission.READ_CONTACTS) && !alreadyAsked) {
                showContactsRationale = true
            }
        }
    }

    fun markContactsAsked() {
        scope.launch(Dispatchers.IO) {
            context.getSharedPreferences("spot_app", Context.MODE_PRIVATE)
                .edit().putBoolean("contacts_asked", true).apply()
        }
    }

    val startupPermissionsLauncher =
        androidx.activity.compose.rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) {
            vm.onPermissionsChanged()
            maybeAskForContacts()
        }

    val contactsPermissionLauncher =
        androidx.activity.compose.rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            vm.onPermissionsChanged()
            if (!granted) {
                Toast.makeText(
                    context,
                    "Contacts permission denied. Until it is granted, messages stay in your Inbox.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    var keywordInput by rememberSaveable { mutableStateOf("") }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedTab by rememberSaveable { mutableStateOf(ShieldTab.MESSAGES) }
    var showGuide by rememberSaveable { mutableStateOf(false) }
    var selectedSender by rememberSaveable { mutableStateOf<String?>(null) }
    // Only the sender key is saved; the conversation is looked up in the loaded list, so it survives rotation.
    var selectedFraudKey by rememberSaveable { mutableStateOf<String?>(null) }
    var fraudSenderToDelete by remember { mutableStateOf<String?>(null) }
    var messageToDelete by remember { mutableStateOf<SmsInboxMessage?>(null) }
    var conversationToDelete by remember { mutableStateOf<SmsConversation?>(null) }
    var filteredMessageToDelete by remember { mutableStateOf<FilteredSmsMessage?>(null) }
    val activity = context as? MainActivity

    LaunchedEffect(activity?.notificationSender) {
        val sender = activity?.notificationSender
        if (!sender.isNullOrBlank()) {
            selectedTab = ShieldTab.MESSAGES
            showGuide = false
            searchQuery = ""
            selectedSender = PhoneNormalizer.key(sender)
            activity.clearNotificationSender()
        }
    }

    var defaultSmsStatus by remember { mutableStateOf(isDefaultSmsApp()) }

    fun notificationsAllowed() =
        androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled() &&
                (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                        hasPermission(Manifest.permission.POST_NOTIFICATIONS))

    var notificationsEnabled by remember { mutableStateOf(notificationsAllowed()) }

    var activeDeleteKeyword by remember { mutableStateOf<String?>(null) }
    var keywordToEdit by remember { mutableStateOf<String?>(null) }

    // Results of actions that finished in the ViewModel: toasts, and screens to close.
    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                is UiEvent.Message -> Toast.makeText(
                    context,
                    event.text,
                    if (event.long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
                ).show()

                is UiEvent.MarkedAsFraud -> {
                    selectedSender = null
                    // Own coroutine, so a visible snackbar never delays the next event.
                    scope.launch {
                        val result = snackbarHostState.showSnackbar(
                            message = "${event.name} marked as fraud",
                            actionLabel = "Undo",
                            withDismissAction = true,
                            duration = SnackbarDuration.Long
                        )
                        if (result == SnackbarResult.ActionPerformed) vm.undoMarkAsFraud(event.sender)
                    }
                }

                UiEvent.Unspammed -> selectedFraudKey = null
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                defaultSmsStatus = isDefaultSmsApp()
                notificationsEnabled = notificationsAllowed()
                vm.onResume()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        val needed = buildList {
            add(Manifest.permission.READ_SMS)
            add(Manifest.permission.SEND_SMS)
            add(Manifest.permission.RECEIVE_SMS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.filterNot { hasPermission(it) }

        if (needed.isEmpty()) {
            maybeAskForContacts()
        } else {
            startupPermissionsLauncher.launch(needed.toTypedArray())
        }
    }

    // Live updates while the app is on screen: new SMS and edited contacts.
    // Nothing stays registered in the background, so incoming messages never wake the UI.
    DisposableEffect(lifecycleOwner) {
        val resolver = context.contentResolver
        val handler = Handler(Looper.getMainLooper())
        val smsObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                vm.onSmsDatabaseChanged()
            }
        }
        val contactsObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                vm.onContactsChanged()
            }
        }

        var registered = false

        fun register() {
            if (registered) return
            registered = true
            resolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, smsObserver)
            try {
                resolver.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, contactsObserver)
            } catch (_: SecurityException) {
            }
        }

        fun unregister() {
            if (!registered) return
            registered = false
            resolver.unregisterContentObserver(smsObserver)
            resolver.unregisterContentObserver(contactsObserver)
        }

        val lifecycleObserver = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> register()
                Lifecycle.Event.ON_STOP -> {
                    unregister()
                    // Free the contact copy while hidden; it is read again when needed.
                    ContactResolver.clearCache()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) register()

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            unregister()
        }
    }

    // Tell the ViewModel what the screen is showing, so it loads only that.
    LaunchedEffect(selectedFraudKey) { vm.openFraudThread(selectedFraudKey) }
    LaunchedEffect(selectedSender) { vm.openChat(selectedSender) }

    // Where the user can undo Android's "restricted settings" block for apps installed outside Google Play.
    fun openAppInfo() {
        try {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
            )
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "Open Settings > Apps > SPoT Shield.", Toast.LENGTH_LONG).show()
        }
    }

    fun updateKeywords(updated: List<String>) {
        vm.setKeywords(updated)
        if (activeDeleteKeyword !in updated) activeDeleteKeyword = null
    }

    fun loadInbox() = vm.refreshConversations(showSpinner = true)

    fun refreshFiltered() = vm.refreshFraud()

    // Saved contact name, or the number itself when it isn't saved.
    fun nameFor(sender: String): String =
        contactNames[PhoneNormalizer.key(sender)] ?: sender

    val visibleConversations = remember(conversations, searchQuery, contactNames) {
        conversations.filter {
            nameFor(it.sender).contains(searchQuery, true) ||
                    it.sender.contains(searchQuery, true) ||
                    it.latestMessage.body.contains(searchQuery, true)
        }
    }
    val visibleFraud = remember(fraudGroups, searchQuery, contactNames) {
        fraudGroups.filter {
            nameFor(it.sender).contains(searchQuery, true) ||
                    it.sender.contains(searchQuery, true) ||
                    it.latestBody.contains(searchQuery, true)
        }
    }

    val screen = when {
        showGuide -> "guide"
        showSubscription -> "subscription"
        openSenderList != null -> "senders"
        selectedFraudKey != null -> "fraud_thread"
        selectedSender != null -> "conversation"
        else -> "home"
    }

    BackHandler(enabled = screen != "home") {
        when (screen) {
            "guide" -> showGuide = false
            "subscription" -> showSubscription = false
            "senders" -> openSenderList = null
            "fraud_thread" -> selectedFraudKey = null
            "conversation" -> selectedSender = null
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
      Box(Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = screen,
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
            label = "screen"
        ) { target ->
        // During the exit animation the state may already be cleared, so every branch is null-safe.
        when (target) {
            "guide" -> SetupGuideScreen { showGuide = false }

            "subscription" -> SubscriptionScreen(
                state = subscription,
                onBack = { showSubscription = false },
                onSubscribe = launchPurchase,
                onManage = openManageSubscription,
                onRetry = { billing.refresh() }
            )

            "senders" -> when (openSenderList) {
                SenderListType.TRUSTED -> SendersScreen(
                    title = "Trusted senders",
                    description = "Messages from these numbers always go to your Inbox with normal notifications.",
                    emptyText = "No trusted senders yet. Tap Unspam on a Fraud message to trust its sender.",
                    entries = allowedSenders,
                    nameFor = { nameFor(it) },
                    actionLabel = "Remove",
                    actionColor = Red,
                    onAction = { entry -> vm.removeTrusted(entry.number) },
                    onBack = { openSenderList = null }
                )

                SenderListType.BLOCKED -> SendersScreen(
                    title = "Blocked senders",
                    description = "Messages from these numbers go to Fraud without notifications.",
                    emptyText = "No blocked senders. Use Mark as Fraud inside a conversation to block a sender.",
                    entries = blockedSenders,
                    nameFor = { nameFor(it) },
                    actionLabel = "Restore",
                    onAction = { entry -> vm.unspam(entry.number) },
                    onBack = { openSenderList = null }
                )

                null -> Unit
            }

            "fraud_thread" -> selectedFraudKey?.let { key ->
                val group = fraudGroups.firstOrNull { it.key == key }

                // The sender's last message was deleted or moved to the Inbox: nothing is left to show.
                LaunchedEffect(group == null, fraudLoaded) {
                    if (group == null && fraudLoaded) selectedFraudKey = null
                }

                group?.let { g ->
                    FraudThreadScreen(
                        name = nameFor(g.sender),
                        sender = g.sender,
                        messages = fraudThread,
                        onBack = { selectedFraudKey = null },
                        onUnspam = { vm.unspam(g.sender) },
                        onDeleteMessage = { filteredMessageToDelete = it },
                        onDeleteAll = { fraudSenderToDelete = g.sender }
                    )
                }
            }

            "conversation" -> {
                val conversation = conversations.firstOrNull { it.key == selectedSender }

                if (conversation != null) {
                    // Opening a conversation marks its received messages as read.
                    LaunchedEffect(conversation.key, conversation.unreadCount) {
                        if (conversation.unreadCount > 0) vm.markConversationRead(conversation)
                    }

                    ConversationScreen(
                        conversation = conversation,
                        messages = chatMessages,
                        hasMore = chatHasMore,
                        onLoadOlder = { vm.loadOlderChat() },
                        name = nameFor(conversation.sender),
                        onBack = { selectedSender = null },
                        onSendSms = { recipient, body -> vm.sendSms(recipient, body) },
                        onDeleteMessage = { message -> messageToDelete = message },
                        onMarkFraud = { vm.markAsFraud(conversation) }
                    )
                } else if (selectedSender != null) {
                    EmptyConversationScreen(nameFor(selectedSender.orEmpty())) {
                        selectedSender = null
                    }
                } else {
                    Unit
                }
            }

            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .topSafePadding()
            ) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = 16.dp,
                            end = 16.dp,
                            top = 12.dp,
                            bottom = 12.dp
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "SPoT Shield",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 27.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            text = when (selectedTab) {
                                ShieldTab.MESSAGES -> "Your messages"
                                ShieldTab.FILTERED -> "Fraud messages"
                                ShieldTab.SETTINGS -> "App preferences"
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 14.sp
                        )
                    }

                    if (selectedTab == ShieldTab.MESSAGES && isLoadingInbox) {
                        Box(Modifier.size(54.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                color = Blue,
                                strokeWidth = 2.5.dp,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    } else Text(
                        text = "↻",
                        color = Blue,
                        fontSize = 30.sp,
                        modifier = Modifier
                            .semantics { contentDescription = "Refresh" }
                            .clickable(role = Role.Button) {
                                when (selectedTab) {
                                    ShieldTab.MESSAGES -> loadInbox()
                                    ShieldTab.FILTERED -> refreshFiltered()
                                    ShieldTab.SETTINGS -> {
                                        defaultSmsStatus = isDefaultSmsApp()
                                    }
                                }
                            }
                            .padding(8.dp)
                    )
                }

                // Search field
                if (selectedTab != ShieldTab.SETTINGS) {
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        singleLine = true,
                        cursorBrush = CursorBrush,
                        textStyle = TextStyle(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 16.sp
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        decorationBox = { inner ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        MaterialTheme.colorScheme.surfaceVariant,
                                        RoundedCornerShape(14.dp)
                                    )
                                    .padding(
                                        horizontal = 14.dp,
                                        vertical = 15.dp
                                    ),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "⌕",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 22.sp
                                )

                                Spacer(Modifier.width(10.dp))

                                Box(Modifier.weight(1f)) {
                                    if (searchQuery.isEmpty()) {
                                        Text(
                                            text = "Search messages",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontSize = 16.sp
                                        )
                                    }

                                    inner()
                                }

                                if (searchQuery.isNotEmpty()) {
                                    Text(
                                        text = "×",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 22.sp,
                                        modifier = Modifier
                                            .clickable(onClickLabel = "Clear search") {
                                                searchQuery = ""
                                            }
                                            .padding(horizontal = 6.dp)
                                    )
                                }
                            }
                        }
                    )

                    Spacer(Modifier.height(12.dp))

                    if (!smsGranted) {
                        InfoBanner(
                            title = "SMS access is blocked",
                            text = "SPoT Shield can't read or filter messages yet. If Android says the app was denied access, " +
                                    "open App info, tap the ⋮ menu (top right) and choose \"Allow restricted settings\". " +
                                    "Then allow SMS and set SPoT Shield as the default SMS app.",
                            actionLabel = "Open App info",
                            onAction = { openAppInfo() }
                        )
                    }

                    if (!contactsGranted) {
                        InfoBanner(
                            title = "Contacts access needed",
                            text = "Allow contacts so saved numbers show their names and are never filtered. Until then, messages stay in your Inbox.",
                            actionLabel = "Allow",
                            onAction = { showContactsRationale = true }
                        )
                    }
                }

                // Main content
                Box(Modifier.weight(1f)) {
                Crossfade(targetState = selectedTab, animationSpec = tween(180), label = "tab") { tab ->
                when (tab) {
                    ShieldTab.MESSAGES -> when {
                        isLoadingInbox && conversations.isEmpty() -> {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(color = Blue)
                            }
                        }

                        inboxError != null && conversations.isEmpty() -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = inboxError.orEmpty(),
                                    color = Red,
                                    textAlign = TextAlign.Center
                                )

                                Spacer(Modifier.height(16.dp))

                                Button(
                                    onClick = { loadInbox() },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Blue,
                                        contentColor = Color.White
                                    )
                                ) {
                                    Text("Try Again")
                                }
                            }
                        }

                        visibleConversations.isEmpty() -> {
                            EmptyState(
                                Modifier.fillMaxSize(),
                                "✉",
                                if (searchQuery.isBlank()) {
                                    "No messages yet"
                                } else {
                                    "No matching messages"
                                },
                                if (searchQuery.isBlank()) {
                                    "Your SMS conversations will appear here."
                                } else {
                                    "Try another sender or search term."
                                },
                                "Refresh Inbox",
                                { loadInbox() }
                            )
                        }

                        else -> {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(bottom = 12.dp)
                            ) {
                                items(
                                    visibleConversations,
                                    key = { it.key }
                                ) { convo ->
                                    Box(Modifier.animateItem()) {
                                        ConversationRow(
                                            conversation = convo,
                                            name = nameFor(convo.sender),
                                            onClick = {
                                                selectedSender = convo.key
                                            },
                                            onDelete = {
                                                conversationToDelete = convo
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    ShieldTab.FILTERED -> {
                        if (visibleFraud.isEmpty()) {
                            EmptyState(
                                Modifier.fillMaxSize(),
                                "⊘",
                                if (searchQuery.isBlank()) {
                                    "No fraud messages"
                                } else {
                                    "No matching messages"
                                },
                                if (searchQuery.isBlank()) {
                                    "Messages from unknown numbers and blocked senders appear here."
                                } else {
                                    "Try another sender or search term."
                                },
                                "Refresh Fraud",
                                { refreshFiltered() }
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(bottom = 12.dp)
                            ) {
                                items(
                                    visibleFraud,
                                    key = { it.key }
                                ) { group ->
                                    Box(Modifier.animateItem()) {
                                        FraudGroupRow(group, nameFor(group.sender)) {
                                            vm.markFraudSenderRead(group.sender)
                                            selectedFraudKey = group.key
                                        }
                                    }
                                }
                            }
                        }
                    }

                    ShieldTab.SETTINGS -> {
                        Box(
                            modifier = Modifier.fillMaxSize()
                        ) {
                            SettingsScreen(
                                notificationsEnabled,
                                defaultSmsStatus,
                                requestDefaultSmsApp,
                                openDefaultAppSettings,
                                {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        notificationPermissionLauncher.launch(
                                            Manifest.permission.POST_NOTIFICATIONS
                                        )
                                    } else {
                                        Toast.makeText(
                                            context,
                                            "Manage notifications in your Android settings.",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                },
                                { showGuide = true },
                                keywords,
                                keywordInput,
                                { keywordInput = it },
                                {
                                    when (val result = KeywordOps.add(keywords, keywordInput)) {
                                        is KeywordResult.Ok -> {
                                            updateKeywords(result.keywords)
                                            keywordInput = ""
                                        }

                                        is KeywordResult.Rejected -> if (result.reason.isNotEmpty()) {
                                            Toast.makeText(context, result.reason, Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                activeDeleteKeyword,
                                { activeDeleteKeyword = it },
                                { activeDeleteKeyword = null },
                                { word -> updateKeywords(KeywordOps.remove(keywords, word)) },
                                contactsGranted,
                                { showContactsRationale = true },
                                if (!BillingManager.ENABLED) null else when {
                                    subscription.isPremium -> "Premium active"
                                    subscription.isLoading -> "Checking subscription…"
                                    subscription.trialLabel != null -> "${subscription.trialLabel} free trial available"
                                    else -> "Not subscribed"
                                },
                                { showSubscription = true },
                                allowedSenders.size,
                                blockedSenders.size,
                                { openSenderList = SenderListType.TRUSTED },
                                { openSenderList = SenderListType.BLOCKED },
                                { keywordToEdit = it },
                                {
                                    updateKeywords(KeywordOps.restoreDefaults(keywords))
                                    Toast.makeText(context, "Default keywords restored.", Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                    }
                }
                }
                }

                // Bottom navigation
                BottomNavigationBar(
                    selectedTab,
                    badges = mapOf(
                        ShieldTab.MESSAGES to conversations.sumOf { it.unreadCount },
                        ShieldTab.FILTERED to fraudUnread
                    )
                ) { tab ->
                    selectedTab = tab
                    searchQuery = ""

                    if (tab == ShieldTab.MESSAGES) {
                        loadInbox()
                    }

                    if (tab == ShieldTab.FILTERED) {
                        refreshFiltered()
                    }
                }
            }
        }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 72.dp)
        )
      }
    }
    // The dialogs belong inside SPoTShieldApp, after the main Surface.
    keywordToEdit?.let { original ->
        var text by remember(original) { mutableStateOf(original) }
        var error by remember(original) { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { keywordToEdit = null },
            title = { Text("Edit keyword") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        error = null
                    },
                    singleLine = true,
                    isError = error != null,
                    supportingText = { error?.let { Text(it) } },
                    colors = OutlinedTextFieldDefaults.colors(cursorColor = Color.White),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    when (val result = KeywordOps.edit(keywords, original, text)) {
                        is KeywordResult.Ok -> {
                            updateKeywords(result.keywords)
                            keywordToEdit = null
                        }

                        is KeywordResult.Rejected -> error = result.reason.ifEmpty { "Enter a keyword." }
                    }
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { keywordToEdit = null }) { Text("Cancel") }
            }
        )
    }

    if (showContactsRationale) {
        AlertDialog(
            onDismissRequest = {
                markContactsAsked()
                showContactsRationale = false
            },
            title = { Text("Allow access to contacts?") },
            text = {
                Text(
                    "SPoT Shield uses your contacts to keep messages from people you know in your Inbox " +
                            "and to show their names. Messages from unknown numbers are moved to Fraud. " +
                            "Your contacts never leave your phone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    markContactsAsked()
                    showContactsRationale = false
                    contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                }) { Text("Allow") }
            },
            dismissButton = {
                TextButton(onClick = {
                    markContactsAsked()
                    showContactsRationale = false
                }) { Text("Not now") }
            }
        )
    }

    filteredMessageToDelete?.let { message ->
        AlertDialog(
            onDismissRequest = { filteredMessageToDelete = null },
            title = { Text("Delete fraud message?") },
            text = { Text("This removes the message from your Fraud list.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteFraudMessage(message.id)
                    filteredMessageToDelete = null
                }) { Text("Delete", color = Red) }
            },
            dismissButton = {
                TextButton(onClick = { filteredMessageToDelete = null }) { Text("Cancel") }
            }
        )
    }

    fraudSenderToDelete?.let { sender ->
        AlertDialog(
            onDismissRequest = { fraudSenderToDelete = null },
            title = { Text("Delete all fraud messages?") },
            text = { Text("This removes every fraud message from ${nameFor(sender)}.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteFraudSender(sender)
                    fraudSenderToDelete = null
                }) { Text("Delete", color = Red) }
            },
            dismissButton = {
                TextButton(onClick = { fraudSenderToDelete = null }) { Text("Cancel") }
            }
        )
    }

    messageToDelete?.let { message ->
        AlertDialog(
            onDismissRequest = { messageToDelete = null },
            title = { Text("Delete SMS?") },
            text = { Text("This permanently deletes this SMS if Android allows it.") },
            confirmButton = {
                TextButton(onClick = {
                    messageToDelete = null
                    vm.deleteMessage(message.id)
                }) { Text("Delete", color = Red) }
            },
            dismissButton = {
                TextButton(onClick = { messageToDelete = null }) { Text("Cancel") }
            }
        )
    }

    conversationToDelete?.let { conversation ->
        AlertDialog(
            onDismissRequest = { conversationToDelete = null },
            title = { Text("Delete conversation?") },
            text = {
                Text("This permanently deletes all SMS messages for ${nameFor(conversation.sender)}, if Android allows it.")
            },
            confirmButton = {
                TextButton(onClick = {
                    conversationToDelete = null
                    if (selectedSender == conversation.key) selectedSender = null
                    vm.deleteConversation(conversation.addresses)
                }) { Text("Delete", color = Red) }
            },
            dismissButton = {
                TextButton(onClick = { conversationToDelete = null }) { Text("Cancel") }
            }
        )
    }
}
