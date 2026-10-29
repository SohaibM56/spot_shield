package com.hcrdevelopers.spotshield

import android.app.Activity
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SubscriptionState(
    val isLoading: Boolean = true,
    val isPremium: Boolean = false,
    val autoRenewing: Boolean = false,
    /** True when a purchasable offer was found in Google Play. */
    val canPurchase: Boolean = false,
    /** Set when the user is eligible for the free trial, e.g. "1 month". */
    val trialLabel: String? = null,
    /** Recurring price, e.g. "$2.99". */
    val priceLabel: String? = null,
    /** Recurring period, e.g. "month". */
    val periodLabel: String? = null,
    val message: String? = null
)

class BillingManager(context: Context) : PurchasesUpdatedListener {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    var state by mutableStateOf(SubscriptionState())
        private set

    init {
        // The last known status is read off the main thread; Google Play then confirms it.
        scope.launch {
            val cached = withContext(Dispatchers.IO) { EntitlementStorage.isActive(appContext) }
            if (cached && !state.isPremium) state = state.copy(isPremium = true)
        }
    }

    // True while a connection attempt is in flight, so onCreate and onResume can't both start one.
    @Volatile private var connecting = false

    // When Google Play is missing or refuses, don't retry on every screen resume.
    @Volatile private var lastFailureAt = 0L

    private var productDetails: ProductDetails? = null
    private var offerToken: String? = null

    private val client: BillingClient = BillingClient.newBuilder(appContext)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
        )
        .build()

    fun start() {
        if (!ENABLED) return
        if (client.isReady) {
            refresh()
            return
        }
        if (connecting) return
        if (System.currentTimeMillis() - lastFailureAt < RETRY_AFTER_FAILURE_MS) return
        connecting = true

        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                connecting = false
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    scope.launch { sync() }
                } else {
                    lastFailureAt = System.currentTimeMillis()
                    state = state.copy(
                        isLoading = false,
                        message = "Google Play Billing is unavailable on this device."
                    )
                }
            }

            override fun onBillingServiceDisconnected() {
                // Reconnects on the next refresh().
                connecting = false
            }
        })
    }

    /** Re-checks products and purchases, e.g. when the app returns to the foreground. */
    fun refresh() {
        if (!client.isReady) {
            start()
            return
        }
        scope.launch { sync() }
    }

    fun end() {
        client.endConnection()
        scope.cancel()
    }

    fun launchPurchase(activity: Activity) {
        val details = productDetails
        val token = offerToken

        if (details == null || token == null) {
            state = state.copy(message = "Subscription is not available right now. Please try again.")
            refresh()
            return
        }

        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
                        .setOfferToken(token)
                        .build()
                )
            )
            .build()

        val result = client.launchBillingFlow(activity, flowParams)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            state = state.copy(message = "Unable to start the purchase. Please try again.")
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK ->
                scope.launch { handlePurchases(purchases.orEmpty(), authoritative = false) }

            BillingClient.BillingResponseCode.USER_CANCELED ->
                state = state.copy(message = null)

            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED ->
                refresh()

            else ->
                state = state.copy(message = "Purchase failed. You have not been charged.")
        }
    }

    private suspend fun sync() {
        loadProduct()
        loadPurchases()
        state = state.copy(isLoading = false)
    }

    private suspend fun loadProduct() {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PRODUCT_ID)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build()
                )
            )
            .build()

        val result = client.queryProductDetails(params)
        val details = result.productDetailsList?.firstOrNull()

        if (result.billingResult.responseCode != BillingClient.BillingResponseCode.OK || details == null) {
            productDetails = null
            offerToken = null
            state = state.copy(canPurchase = false, trialLabel = null)
            return
        }

        // Google Play only returns the free-trial offer to users who are still eligible,
        // so picking the offer with a free phase doubles as the eligibility check.
        val offers = details.subscriptionOfferDetails.orEmpty()
        val chosen = offers.firstOrNull { offer ->
            offer.pricingPhases.pricingPhaseList.any { it.priceAmountMicros == 0L }
        } ?: offers.firstOrNull { it.offerId == null } ?: offers.firstOrNull()

        if (chosen == null) {
            productDetails = null
            offerToken = null
            state = state.copy(canPurchase = false, trialLabel = null)
            return
        }

        val phases = chosen.pricingPhases.pricingPhaseList
        val trial = phases.firstOrNull { it.priceAmountMicros == 0L }
        val recurring = phases.lastOrNull()

        productDetails = details
        offerToken = chosen.offerToken
        state = state.copy(
            canPurchase = true,
            trialLabel = trial?.let { describePeriod(it.billingPeriod) },
            priceLabel = recurring?.formattedPrice,
            periodLabel = recurring?.let { describePeriod(it.billingPeriod, singular = true) }
        )
    }

    private suspend fun loadPurchases() {
        val result = client.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        )

        // On a failed query keep the last known status instead of revoking access.
        if (result.billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
            handlePurchases(result.purchasesList, authoritative = true)
        }
    }

    private suspend fun handlePurchases(purchases: List<Purchase>, authoritative: Boolean) {
        val active = purchases.firstOrNull {
            it.purchaseState == Purchase.PurchaseState.PURCHASED && PRODUCT_ID in it.products
        }

        if (active != null) {
            if (!active.isAcknowledged) {
                client.acknowledgePurchase(
                    AcknowledgePurchaseParams.newBuilder()
                        .setPurchaseToken(active.purchaseToken)
                        .build()
                )
            }

            EntitlementStorage.set(appContext, true)
            state = state.copy(isPremium = true, autoRenewing = active.isAutoRenewing, message = null)
            return
        }

        val pending = purchases.any { it.purchaseState == Purchase.PurchaseState.PENDING }

        if (authoritative) {
            EntitlementStorage.set(appContext, false)
            state = state.copy(
                isPremium = false,
                autoRenewing = false,
                message = if (pending) "Your payment is pending. Access starts once it completes." else null
            )
        }
    }

    companion object {
        /**
         * Premium is off for the first Play release: nothing is locked behind it yet, and Play
         * rejects subscriptions that unlock nothing. When true, billing connects and Settings
         * shows the Subscription row again.
         */
        const val ENABLED = false

        /** Subscription product ID created in Google Play Console. */
        const val PRODUCT_ID = "spot_shield_premium"

        private const val RETRY_AFTER_FAILURE_MS = 60_000L

        fun manageSubscriptionUrl(packageName: String) =
            "https://play.google.com/store/account/subscriptions?sku=$PRODUCT_ID&package=$packageName"

        /** Converts an ISO-8601 period such as "P1M" to "1 month" (or "month" when [singular]). */
        fun describePeriod(iso: String, singular: Boolean = false): String {
            val match = Regex("P(?:(\\d+)Y)?(?:(\\d+)M)?(?:(\\d+)W)?(?:(\\d+)D)?").matchEntire(iso)
                ?: return iso

            val (years, months, weeks, days) = match.destructured
            val parts = listOf(years to "year", months to "month", weeks to "week", days to "day")
            val (count, unit) = parts.firstOrNull { it.first.isNotEmpty() } ?: return iso
            val number = count.toInt()

            return when {
                singular && number == 1 -> unit
                number == 1 -> "1 $unit"
                else -> "$number ${unit}s"
            }
        }
    }
}
