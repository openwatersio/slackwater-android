package io.openwaters.slackwater.android

import android.graphics.Color.TRANSPARENT
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

internal val WaterColors = darkColorScheme(
    primary = Color(0xffa4d77d), onPrimary = Color(0xff143511),
    secondary = Color(0xff8ec6d6), tertiary = Color(0xfff0c860),
    surface = Color(0xff0c1b24), background = Color(0xff0c1b24),
    onSurface = Color(0xfff3f5ef), onSurfaceVariant = Color(0xffc2cfcf),
    // Surface and outline roles step along one water ramp, so no sheet, divider or chip falls back to Material's gray.
    surfaceContainerLowest = Color(0xff07131a), surfaceDim = Color(0xff0c1b24), outlineVariant = Color(0xff354a51),
    surfaceContainerLow = Color(0xff12232c), surfaceContainer = Color(0xff182b34), surfaceVariant = Color(0xff20323b),
    surfaceContainerHigh = Color(0xff21353e), surfaceContainerHighest = Color(0xff293e46), surfaceBright = Color(0xff31474e),
    inverseSurface = Color(0xfff3f5ef), inverseOnSurface = Color(0xff0c1b24),
    outline = Color(0xff789086), primaryContainer = Color(0xff29472e),
    secondaryContainer = Color(0xff23444f), onSecondaryContainer = Color(0xffd6f2f0),
)

/** The app is always dark, as iOS is: every spec ink is chosen against a dark fill. */
@Composable
internal fun SlackwaterTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = WaterColors, content = content)

/** Light system bar icons whatever the device's own dark setting, since the app never draws light. */
internal fun ComponentActivity.enableWaterEdgeToEdge() =
    enableEdgeToEdge(SystemBarStyle.dark(TRANSPARENT), SystemBarStyle.dark(TRANSPARENT))
