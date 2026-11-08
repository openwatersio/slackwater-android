package io.openwaters.slackwater.android.map

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import io.openwaters.slackwater.android.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AboutSettingsLinksTest {
    @get:Rule val compose = createComposeRule()

    @Test fun opensSupportPrivacyAndroidSourceAndBundledCredits() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val opened = mutableListOf<String>()
        val handler = object : UriHandler {
            override fun openUri(uri: String) { opened.add(uri) }
        }
        compose.setContent {
            CompositionLocalProvider(LocalUriHandler provides handler) { Column { AboutSettingsLinks() } }
        }
        fun node(id: Int) = compose.onNodeWithText(context.getString(id))
        node(R.string.settings_help).performClick()
        node(R.string.about_title).performClick()
        node(R.string.about_privacy).performScrollTo().performClick()
        node(R.string.about_source).performScrollTo().performClick()
        node(R.string.about_attribution).performScrollTo().performClick()
        compose.onNodeWithText("DATA CREDITS").assertExists()
        compose.runOnIdle {
            assertEquals(listOf("https://slackwater.xyz/support/", "https://slackwater.xyz/privacy",
                "https://github.com/openwatersio/slackwater-android"), opened)
        }
    }
}
