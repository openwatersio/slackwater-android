package io.openwaters.slackwater.android.detail

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DetailPostureTest {
    @get:Rule val compose = createComposeRule()
    private var wide by mutableStateOf(false)
    private var loaded by mutableStateOf(true)
    private lateinit var scope: CoroutineScope
    private lateinit var scroll: ScrollState
    private lateinit var page: DetailPageScroll
    private var density = 1f

    private fun mount() {
        compose.setContent {
            scope = rememberCoroutineScope()
            scroll = rememberScrollState()
            page = rememberDetailPageScroll(scroll)
            density = LocalDensity.current.density
            Column(Modifier.width(if (wide) 300.dp else 220.dp).height(240.dp)
                .testTag("postureViewport").then(page.viewportModifier)
                .onGloballyPositioned { page.measureViewport(it, scroll) }.verticalScroll(scroll)) {
                Spacer(Modifier.height(if (wide) 420.dp else 360.dp))
                Box(page.scheduleModifier(scroll).fillMaxWidth().height(if (loaded) 360.dp else 0.dp))
                Spacer(Modifier.height(400.dp))
            }
        }
    }

    private fun offset(dp: Int) {
        compose.runOnIdle { scope.launch { scroll.scrollTo((dp * density).toInt()) } }
        compose.waitForIdle()
    }

    @Test fun foldWithScheduleInUpperHalfWaitsForRowsThenRestoresToScheduleStart() {
        mount()
        offset(300)
        compose.runOnIdle { wide = true; loaded = false }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(true, page.destination); loaded = true }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(420.0, scroll.value / density.toDouble(), 1.0) }
    }

    @Test fun bottomGlimpseReturnsToChartAndUserScrollCancelsPendingScheduleRestore() {
        mount()
        offset(160)
        compose.runOnIdle { wide = true }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, scroll.value) }
        offset(370)
        compose.runOnIdle { wide = false; loaded = false }
        compose.waitForIdle()
        compose.onNodeWithTag("postureViewport").performTouchInput { swipeUp() }
        compose.runOnIdle { assertNull(page.destination); loaded = true }
        compose.waitForIdle()
        compose.runOnIdle { assertNotEquals(360.0, scroll.value / density.toDouble(), 1.0) }
    }
}
