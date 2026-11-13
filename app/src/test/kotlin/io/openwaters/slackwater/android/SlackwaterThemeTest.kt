package io.openwaters.slackwater.android

import androidx.compose.ui.graphics.toArgb
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class SlackwaterThemeTest {
    @Test fun theLaunchWindowDrawsTheSameSurfaceAsCompose() {
        val themes = File("src/main/res/values/themes.xml").readText()
        val hex = requireNotNull(Regex("""<color name="water_surface">#([0-9A-Fa-f]{6})</color>""").find(themes)).groupValues[1]
        assertEquals(WaterColors.surface.toArgb(), (0xFF000000 or hex.toLong(16)).toInt())
    }
}
