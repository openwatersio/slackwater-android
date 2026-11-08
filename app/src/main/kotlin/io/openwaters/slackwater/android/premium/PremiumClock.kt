package io.openwaters.slackwater.android.premium

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.openwaters.slackwater.android.core.planning.ReminderEntitlement
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.delay

@Composable
internal fun premiumClock(vararg inputs: Any?, nextBoundary: (Instant) -> Instant?): Instant {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var refresh by remember { mutableIntStateOf(0) }
    DisposableEffect(context, lifecycle) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { refresh++ }
        }
        context.registerReceiver(receiver, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_LOCALE_CHANGED)
        })
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh++
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            context.unregisterReceiver(receiver)
        }
    }

    val now = remember(*inputs, refresh) { Instant.now() }
    val next = nextBoundary(now)
    LaunchedEffect(next, now) {
        if (next != null) {
            delay(Duration.between(Instant.now(), next).toMillis().coerceAtLeast(1))
            refresh++
        }
    }
    return now
}

@Composable
internal fun entitlementClock(access: ReminderEntitlement?, subscriptionEnds: List<Instant> = emptyList()): Instant {
    val trial = access as? ReminderEntitlement.FreeDay
    return premiumClock(access, subscriptionEnds) { now ->
        (subscriptionEnds + listOfNotNull(trial?.started, trial?.ends)).filter { it > now }.minOrNull()
    }
}
