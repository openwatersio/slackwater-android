package io.openwaters.slackwater.android.premium

import android.content.Intent
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import io.openwaters.slackwater.android.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class PremiumLinkTest {
    @Test fun premiumRouteFollowsTheBuildOption() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("slackwater://premium")).setPackage(context.packageName)
        assertEquals(if (BuildConfig.PREMIUM_ENABLED) PremiumLinkActivity::class.java.name else null,
            intent.resolveActivity(context.packageManager)?.className)
    }
}
