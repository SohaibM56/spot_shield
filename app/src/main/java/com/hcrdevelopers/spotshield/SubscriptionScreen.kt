package com.hcrdevelopers.spotshield

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val AccentBlue = Color(0xFF079EFF)
private val AccentGreen = Color(0xFF30D158)

@Composable
fun SubscriptionScreen(
    state: SubscriptionState,
    onBack: () -> Unit,
    onSubscribe: () -> Unit,
    onManage: () -> Unit,
    onRetry: () -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .topSafePadding()
            .navigationBarsPadding()
    ) {
        ScreenTopBar("SPoT Shield Premium", onBack)

        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 30.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { Hero(state) }
            item { Benefits() }

            item {
                when {
                    state.isPremium -> ActivePlan(state, onManage)
                    state.isLoading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = AccentBlue)
                    }
                    state.canPurchase -> PurchaseCard(state, onSubscribe, onManage)
                    else -> Unavailable(state, onRetry)
                }
            }

            state.message?.let { message ->
                item {
                    Text(
                        message,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
private fun Hero(state: SubscriptionState) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(84.dp).background(AccentBlue.copy(alpha = 0.16f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text("⛨", color = AccentBlue, fontSize = 42.sp)
        }
        Spacer(Modifier.height(16.dp))
        Text(
            if (state.isPremium) "You're subscribed" else "Keep unwanted texts out",
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (state.isPremium) "Thanks for supporting SPoT Shield."
            else "Messages from people you know stay in your Inbox. Everything else is filtered.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 15.sp,
            lineHeight = 22.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun Benefits() {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(18.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        listOf(
            "Unknown numbers filtered automatically",
            "Saved contacts always reach your Inbox",
            "Trusted and blocked sender lists",
            "Mark as Fraud and Unspam in one tap"
        ).forEach { benefit ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("✓", color = AccentGreen, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(12.dp))
                Text(benefit, color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun PurchaseCard(state: SubscriptionState, onSubscribe: () -> Unit, onManage: () -> Unit) {
    val price = state.priceLabel ?: ""
    val period = state.periodLabel ?: "period"
    val trial = state.trialLabel

    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(18.dp))
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (trial != null) {
            Text(
                "$trial free".uppercase(),
                color = AccentGreen,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Then $price per $period",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
        } else {
            Text(
                "$price per $period",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = onSubscribe,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentBlue, contentColor = Color.White)
        ) {
            Text(
                if (trial != null) "Start $trial free trial" else "Subscribe",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
        }

        Spacer(Modifier.height(14.dp))

        Text(
            buildString {
                if (trial != null) append("Your $trial free trial starts today. ")
                append("After the trial your subscription renews automatically at $price per $period ")
                append("until you cancel. You can cancel any time in Google Play before the renewal date ")
                append("and you won't be charged. Payment is charged to your Google Play account.")
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(8.dp))

        Text(
            "Manage subscriptions",
            color = AccentBlue,
            fontSize = 13.sp,
            modifier = Modifier.clickable(onClick = onManage).padding(8.dp)
        )
    }
}

@Composable
private fun ActivePlan(state: SubscriptionState, onManage: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(18.dp))
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Premium is active",
            color = AccentGreen,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (state.autoRenewing) "Your subscription renews automatically. Cancel any time in Google Play."
            else "Auto-renewal is off. You keep access until the end of the current period.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        OutlinedButton(
            onClick = onManage,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentBlue)
        ) {
            Text("Manage subscription")
        }
    }
}

@Composable
private fun Unavailable(state: SubscriptionState, onRetry: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(18.dp))
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Subscription unavailable",
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            state.message ?: "We couldn't load the subscription from Google Play. Check your connection and try again.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(14.dp))
        OutlinedButton(
            onClick = onRetry,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentBlue)
        ) {
            Text("Try again")
        }
    }
}
