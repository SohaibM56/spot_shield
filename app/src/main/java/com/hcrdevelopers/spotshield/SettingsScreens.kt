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

// Play requires the policy to be reachable inside the app as well as on the store listing.
// TODO: replace with the real public URL before building the Play release.
internal const val PRIVACY_POLICY_URL = "https://example.com/spotshield/privacy-policy"

@Composable
internal fun SettingsScreen(
    notificationsEnabled: Boolean,
    defaultSmsStatus: Boolean,
    requestDefaultSmsApp: () -> Unit,
    openDefaultAppSettings: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onOpenGuide: () -> Unit,
    keywords: List<String>,
    keywordInput: String,
    onKeywordInputChange: (String) -> Unit,
    onAddKeyword: () -> Unit,
    activeDeleteKeyword: String?,
    onDeleteKeywordSelected: (String) -> Unit,
    onCancelDelete: () -> Unit,
    onDeleteKeyword: (String) -> Unit,
    contactsGranted: Boolean,
    onRequestContacts: () -> Unit,
    /** Null hides the Subscription row (Premium is switched off). */
    subscriptionSummary: String?,
    onOpenSubscription: () -> Unit,
    trustedCount: Int,
    blockedCount: Int,
    onOpenTrusted: () -> Unit,
    onOpenBlocked: () -> Unit,
    onEditKeyword: (String) -> Unit,
    onRestoreDefaults: () -> Unit
) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (subscriptionSummary != null) {
                item {
                    SettingsRow("Subscription", subscriptionSummary, onOpenSubscription)
                }
            }
            if (!contactsGranted) {
                item {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(18.dp))
                            .padding(16.dp)
                    ) {
                        Text("Contacts", color = MaterialTheme.colorScheme.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Allow contacts so saved numbers show their names and always stay in your Inbox.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 14.sp,
                            lineHeight = 20.sp
                        )
                        Spacer(Modifier.height(14.dp))
                        Button(
                            onClick = onRequestContacts,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Blue, contentColor = Color.White)
                        ) { Text("Allow Contacts", fontWeight = FontWeight.Bold) }
                    }
                }
            }
            item {
                SettingsRow("Trusted senders", "$trustedCount always delivered to Inbox", onOpenTrusted)
            }
            item {
                SettingsRow("Blocked senders", "$blockedCount always sent to Fraud", onOpenBlocked)
            }
            if (!notificationsEnabled) {
                item {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(18.dp)
                            )
                            .padding(16.dp)
                    ) {
                        Text(
                            "Notifications",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(Modifier.height(8.dp))

                        Text(
                            "Allow SPoT Shield to notify you when a legitimate SMS arrives. Filtered messages will not trigger notifications.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 14.sp,
                            lineHeight = 20.sp
                        )

                        Spacer(Modifier.height(14.dp))

                        Button(
                            onClick = onRequestNotificationPermission,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Blue,
                                contentColor = Color.White
                            )
                        ) {
                            Text("Enable Notifications", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            item {
                Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(18.dp)).padding(16.dp)) {
                    Text("SMS Filtering Setup", color = MaterialTheme.colorScheme.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(if (defaultSmsStatus) "SPoT Shield is set as your default SMS app." else "Set SPoT Shield as the default SMS app to manage incoming messages.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, lineHeight = 20.sp)
                    Spacer(Modifier.height(14.dp))
                    Button(onClick = { if (defaultSmsStatus) openDefaultAppSettings() else requestDefaultSmsApp() },
                        modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Blue, contentColor = Color.White)) {
                        Text(if (defaultSmsStatus) "Manage Default SMS App" else "Set as Default SMS App", fontWeight = FontWeight.Bold)
                    }
                }
            }
            item {
                SettingsRow("Setup guide", "Step-by-step help for filtering and notifications", onOpenGuide)
            }
            item {
                val context = LocalContext.current
                SettingsRow("Privacy policy", "How SPoT Shield handles your messages and contacts") {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_POLICY_URL)))
                    } catch (_: ActivityNotFoundException) {
                        Toast.makeText(context, PRIVACY_POLICY_URL, Toast.LENGTH_LONG).show()
                    }
                }
            }
            item {
                Column {
                    Text("Filter Keywords (${keywords.size})", color = MaterialTheme.colorScheme.onSurface, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text("Messages from unknown numbers that contain these keywords are marked High risk in Fraud. Keywords never change which messages reach your Inbox. Your list is stored only on this phone.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, lineHeight = 19.sp)
                    Text(
                        "Restore default keywords",
                        color = Blue,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable(role = Role.Button, onClick = onRestoreDefaults).padding(top = 10.dp, bottom = 2.dp)
                    )
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(
                        value = keywordInput, onValueChange = onKeywordInputChange, singleLine = true, cursorBrush = CursorBrush,
                        textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp), modifier = Modifier.weight(1f),
                        decorationBox = { inner ->
                            Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 16.dp)) {
                                if (keywordInput.isEmpty()) Text("Enter keyword (e.g. donation)", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                                inner()
                            }
                        }
                    )
                    Spacer(Modifier.width(10.dp))
                    Button(onClick = onAddKeyword, colors = ButtonDefaults.buttonColors(containerColor = Blue, contentColor = Color.White),
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp)) {
                        Text("+", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            if (keywords.isEmpty()) item { Text("No keywords added yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(keywords, key = { it.lowercase() }) { keyword ->
                val showDelete = activeDeleteKeyword == keyword
                Row(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                        .pointerInput(keyword, showDelete) {
                            var drag = 0f
                            detectHorizontalDragGestures(
                                onHorizontalDrag = { _, amount -> drag += amount },
                                onDragEnd = {
                                    when {
                                        drag < -40f -> onDeleteKeywordSelected(keyword)
                                        drag > 40f -> onCancelDelete()
                                    }
                                    drag = 0f
                                },
                                onDragCancel = { drag = 0f }
                            )
                        }.padding(horizontal = 14.dp, vertical = 15.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(keyword, color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp,
                        modifier = Modifier.weight(1f).clickable(onClickLabel = "Edit keyword") { onEditKeyword(keyword) })
                    if (showDelete) {
                        Text("Delete", color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.background(Red, RoundedCornerShape(20.dp))
                            .clickable { onDeleteKeyword(keyword) }.padding(horizontal = 13.dp, vertical = 8.dp))
                    } else Text("Tap to edit • swipe to delete", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
internal fun SetupGuideScreen(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).topSafePadding().navigationBarsPadding()) {
        ScreenTopBar("Setup Guide", onBack)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 30.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item { Text("Follow the steps below to configure SPoT Shield according to your preferences.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 17.sp, lineHeight = 27.sp) }
            item {
                GuideCard("STEP 1", "Enable SPoT Shield", "⚙", Blue, """
                    • Open Settings on your Android phone.

                    • Set SPoT Shield as the default SMS app when prompted.

                    • Follow the app's filtering setup instructions.
                """.trimIndent())
            }
            item {
                GuideCard("STEP 2", "Choose Your Notification Preferences", "♟", Color(0xFFFF922B), """
                    Manage SPoT Shield notifications from your Android settings.

                    Notification options vary by Android version and device manufacturer.
                """.trimIndent())
            }
            item {
                GuideCard("STEP 3", "Choose Your Preferred Configuration", "☷", Color(0xFFD946EF), """
                    Option A: Minimize unwanted notifications

                    • Review filtering settings.
                    • Choose notification preferences that suit you.

                    Option B: Receive important updates

                    • Keep the notifications you need enabled.
                """.trimIndent())
            }
            item {
                GuideCard("STEP 4", "Customize Your SPoT Shield Keywords", "⌕", Green, """
                    • Open the SPoT Shield app.

                    • Review your active filtering keywords.

                    • Add relevant keywords (e.g. fundraiser, donate, campaign).

                    • Remove keywords you no longer wish to use.

                    Note: Keywords only mark messages from unknown numbers as High risk in the Fraud tab. They never move a message out of your Inbox.
                """.trimIndent())
            }
            item {
                GuideCard("STEP 5", "Test Your Settings", "✓", Color(0xFF00C7D9), """
                    • Send a test message.

                    • Check whether SPoT Shield handles the message as expected.

                    • Confirm that legitimate messages remain accessible.
                """.trimIndent())
            }
            item {
                Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(22.dp)).padding(22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Need Help or Support?", color = MaterialTheme.colorScheme.onSurface, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(14.dp))
                    Text("If you need further assistance, please contact our support team:", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp, lineHeight = 23.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    Text("Josepheckersen@gmail.com", color = Blue, fontSize = 15.sp)
                }
            }
        }
    }
}

@Composable
internal fun GuideCard(step: String, title: String, icon: String, accent: Color, body: String) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(20.dp)).padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(icon, color = accent, fontSize = 30.sp)
            Spacer(Modifier.width(16.dp))
            Column {
                Text(step, color = accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(5.dp))
                Text(title, color = MaterialTheme.colorScheme.onSurface, fontSize = 19.sp, fontWeight = FontWeight.Bold, lineHeight = 25.sp)
            }
        }
        Spacer(Modifier.height(18.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(18.dp))
        Text(body, color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, lineHeight = 28.sp)
    }
}
