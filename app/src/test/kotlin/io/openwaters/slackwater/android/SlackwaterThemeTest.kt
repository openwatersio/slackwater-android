package io.openwaters.slackwater.android

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SlackwaterThemeTest {
    @Test fun theLaunchWindowDrawsTheSameSurfaceAsCompose() {
        val themes = File("src/main/res/values/themes.xml").readText()
        val hex = requireNotNull(Regex("""<color name="water_surface">#([0-9A-Fa-f]{6})</color>""").find(themes)).groupValues[1]
        assertEquals(WaterColors.surface.toArgb(), (0xFF000000 or hex.toLong(16)).toInt())
    }

    // A role left at Material's default draws neutral gray, as bottom sheets did with surfaceContainerLow unset.
    @Test fun everySurfaceAndOutlineRoleComesFromTheWaterPalette() {
        val material = darkColorScheme()
        val roles = mapOf<String, (ColorScheme) -> Color>(
            "surface" to { it.surface }, "background" to { it.background }, "surfaceDim" to { it.surfaceDim },
            "surfaceBright" to { it.surfaceBright }, "surfaceVariant" to { it.surfaceVariant },
            "surfaceContainerLowest" to { it.surfaceContainerLowest }, "surfaceContainerLow" to { it.surfaceContainerLow },
            "surfaceContainer" to { it.surfaceContainer }, "surfaceContainerHigh" to { it.surfaceContainerHigh },
            "surfaceContainerHighest" to { it.surfaceContainerHighest },
            "inverseSurface" to { it.inverseSurface }, "inverseOnSurface" to { it.inverseOnSurface },
            "outline" to { it.outline }, "outlineVariant" to { it.outlineVariant },
        )
        val defaults = roles.filter { (_, role) -> role(WaterColors) == role(material) }.keys
        assertTrue("Left at Material's default: $defaults", defaults.isEmpty())
    }

    @Test fun theContainersStepUpFromTheSurface() {
        val ramp = with(WaterColors) {
            listOf(surfaceContainerLowest, surface, surfaceContainerLow, surfaceContainer, surfaceVariant,
                surfaceContainerHigh, surfaceContainerHighest, surfaceBright)
        }
        assertTrue("Each step is brighter than the last", ramp.zipWithNext().all { (a, b) -> a.luminance() < b.luminance() })
        // Material's dark tokens make the dim surface the surface itself.
        assertEquals(WaterColors.surface, WaterColors.surfaceDim)
    }
}
