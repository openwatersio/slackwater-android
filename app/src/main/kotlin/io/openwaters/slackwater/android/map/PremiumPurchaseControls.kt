package io.openwaters.slackwater.android.map

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.openwaters.slackwater.android.R
import io.openwaters.slackwater.android.core.premium.PremiumProducts
import io.openwaters.slackwater.android.premium.PremiumPlan
import io.openwaters.slackwater.android.premium.PremiumStore
import io.openwaters.slackwater.android.premium.StoreAnswer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** The Premium sheet's store controls: the two plans, the purchase button, restore and the privacy policy. */
@Composable
internal fun PremiumPurchaseControls(store: PremiumStore = PremiumStore.get(LocalContext.current)) {
    val activity = LocalActivity.current
    val links = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val plans by store.plans.collectAsState()
    val failures by store.failures.collectAsState()
    var selected by rememberSaveable { mutableStateOf(PremiumProducts.YEARLY) }
    // A failure from before this sheet opened, or before the latest attempt, is not this attempt's.
    var failuresBefore by remember { mutableIntStateOf(store.failures.value) }
    var couldNotOpen by remember { mutableStateOf(false) }
    var restoreUnanswered by remember { mutableStateOf(false) }
    var privacyUnopened by rememberSaveable { mutableStateOf(false) }
    // A fault inside the store must cost the buyer a retry, not the app. Cancellation is the sheet closing.
    suspend fun <T> guarded(fallback: T, ask: suspend () -> T): T = try {
        ask()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        android.util.Log.w("Slackwater", "Premium store call failed", error)
        fallback
    }
    LaunchedEffect(store) { guarded(Unit) { store.loadPlans() } }

    val plan = plans?.firstOrNull { it.id == selected }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.selectableGroup()) {
            // A subscription states its term beside its price.
            PlanRow(R.string.premium_plan_yearly, R.string.premium_plan_yearly_detail, PremiumProducts.YEARLY, plans, selected) { selected = it }
            PlanRow(R.string.premium_plan_lifetime, R.string.premium_plan_lifetime_detail, PremiumProducts.LIFETIME, plans, selected) { selected = it }
        }
        val planMissing = plans != null && plan == null
        // A restore the store could not answer must not look like one that found nothing.
        if (planMissing || restoreUnanswered) Text(stringResource(R.string.premium_unavailable), style = MaterialTheme.typography.bodySmall)
        if (planMissing) TextButton(onClick = { scope.launch { guarded(Unit) { store.loadPlans() } } }) { Text(stringResource(R.string.table_sheet_retry)) }
        if (couldNotOpen || failures > failuresBefore) {
            Text(stringResource(R.string.premium_purchase_failed), style = MaterialTheme.typography.bodySmall)
        }
        Button(
            onClick = {
                failuresBefore = failures
                couldNotOpen = plan == null || activity == null || !try {
                    store.buy(activity, plan.id)
                } catch (error: RuntimeException) {
                    android.util.Log.w("Slackwater", "Could not open the purchase screen", error)
                    false
                }
            },
            enabled = plan != null && activity != null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.premium_get), style = MaterialTheme.typography.titleMedium)
                if (plan != null) {
                    val price = if (plan.id == PremiumProducts.YEARLY) stringResource(R.string.premium_price_per_year, plan.price) else plan.price
                    Text(price, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            TextButton(onClick = {
                restoreUnanswered = false
                scope.launch { restoreUnanswered = guarded(StoreAnswer.UNKNOWN) { store.reconcile() } == StoreAnswer.UNKNOWN }
            }) { Text(stringResource(R.string.premium_restore)) }
            TextButton(onClick = {
                // A device with no browser throws here; the address is shown to copy by hand.
                privacyUnopened = try { links.openUri(PRIVACY_URL); false } catch (_: IllegalArgumentException) { true }
            }) { Text(stringResource(R.string.about_privacy)) }
        }
        if (privacyUnopened) Text(PRIVACY_URL, style = MaterialTheme.typography.bodySmall)
    }
}

internal const val PRIVACY_URL = "https://slackwater.xyz/privacy"

@Composable
private fun PlanRow(name: Int, detail: Int, id: String, plans: List<PremiumPlan>?, selected: String, select: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .selectable(selected = selected == id, role = Role.RadioButton) { select(id) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = selected == id, onClick = null)
        Column(Modifier.weight(1f)) {
            Text(stringResource(name), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(detail), style = MaterialTheme.typography.bodySmall)
        }
        val price = plans?.firstOrNull { it.id == id }?.price
        when {
            price != null -> Text(price, style = MaterialTheme.typography.titleMedium)
            plans == null -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
}
