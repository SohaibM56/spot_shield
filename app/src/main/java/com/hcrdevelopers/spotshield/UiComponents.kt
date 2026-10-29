package com.hcrdevelopers.spotshield

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Calendar
import java.util.Date

/**
 * Keeps content below the status bar and any camera cutout/notch, on every phone shape.
 * Use instead of statusBarsPadding() on the top-most container of a screen.
 */
@Composable
fun Modifier.topSafePadding(): Modifier =
    windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Top))

/** Round back button with a 48dp touch target. */
@Composable
fun BackButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .semantics { contentDescription = "Back" }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "‹",
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 32.sp,
            modifier = Modifier.clearAndSetSemantics { }
        )
    }
}

/** Standard header for every pushed screen: back button and a centered title. */
@Composable
fun ScreenTopBar(title: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BackButton(onBack)
        Text(
            title,
            Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.size(48.dp))
    }
}

private val AvatarColors = listOf(
    Color(0xFF2868B5),
    Color(0xFF2E8B6E),
    Color(0xFF7A55B8),
    Color(0xFFB5683A),
    Color(0xFFA14A72),
    Color(0xFF3C7FA8)
)

/** Initial-letter avatar; numbers without a name show "#". The color is stable per name. */
@Composable
fun Avatar(name: String, size: Dp, modifier: Modifier = Modifier) {
    val initial = name.trim().firstOrNull { it.isLetter() }?.uppercase() ?: "#"
    val base = AvatarColors[(name.hashCode() and Int.MAX_VALUE) % AvatarColors.size]
    val brush = remember(base) { Brush.linearGradient(listOf(base, base.copy(alpha = 0.7f))) }

    Box(
        modifier
            .size(size)
            .background(brush, CircleShape)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center
    ) {
        Text(
            initial,
            color = Color.White,
            fontSize = (size.value * 0.4f).sp,
            fontWeight = FontWeight.Bold
        )
    }
}

private const val DAY_MS = 24L * 60 * 60 * 1000

fun dayStart(millis: Long): Long =
    Calendar.getInstance().apply {
        timeInMillis = millis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

private fun daysAgo(millis: Long): Int =
    Math.round((dayStart(System.currentTimeMillis()) - dayStart(millis)) / DAY_MS.toDouble()).toInt()

private fun isThisYear(millis: Long): Boolean {
    val now = Calendar.getInstance()
    val other = Calendar.getInstance().apply { timeInMillis = millis }
    return now.get(Calendar.YEAR) == other.get(Calendar.YEAR)
}

/** Time of day in the user's 12/24-hour setting. */
fun formatTime(context: Context, millis: Long): String =
    timeFormatFor(context).format(Date(millis))

// Building a date formatter is slow, so reuse it until the 12/24-hour setting changes.
private var cachedTimeFormat: java.text.DateFormat? = null
private var cachedIs24Hour = false

private fun timeFormatFor(context: Context): java.text.DateFormat {
    val is24Hour = DateFormat.is24HourFormat(context)
    val cached = cachedTimeFormat
    if (cached != null && cachedIs24Hour == is24Hour) return cached

    return DateFormat.getTimeFormat(context).also {
        cachedTimeFormat = it
        cachedIs24Hour = is24Hour
    }
}

/** Conversation list stamp: time today, then Yesterday, weekday, date. */
fun formatListTime(context: Context, millis: Long): String {
    val days = daysAgo(millis)
    return when {
        days <= 0 -> formatTime(context, millis)
        days == 1 -> "Yesterday"
        days < 7 -> DateFormat.format("EEE", millis).toString()
        isThisYear(millis) -> DateFormat.format("MMM d", millis).toString()
        else -> DateFormat.format("MMM d, yyyy", millis).toString()
    }
}

/** Full date and time, e.g. "Oct 26, 2026 • 9:41 AM". */
fun formatDateTime(context: Context, millis: Long): String =
    "${DateFormat.getMediumDateFormat(context).format(Date(millis))} • ${formatTime(context, millis)}"

/** Label for a day divider inside a conversation. */
fun formatDayHeader(millis: Long): String {
    val days = daysAgo(millis)
    return when {
        days <= 0 -> "Today"
        days == 1 -> "Yesterday"
        isThisYear(millis) -> DateFormat.format("EEEE, MMM d", millis).toString()
        else -> DateFormat.format("MMM d, yyyy", millis).toString()
    }
}
