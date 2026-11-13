package io.openwaters.slackwater.android.map

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.openwaters.slackwater.android.BuildConfig
import io.openwaters.slackwater.android.R

private const val SPARKLE = "✦"
private const val SKY = "☾"

/**
 * List §8: the Premium features Settings controls, one row each, and the way to the Premium sheet. [purchased] is
 * null until the purchase record loads, so a supporter is never shown the sale first.
 */
@Composable
internal fun PremiumSettingsCard(showSky: Boolean, purchased: Boolean?, onSky: (Boolean) -> Unit, onSupport: () -> Unit) {
    val leaf = MaterialTheme.colorScheme.primary
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.semantics(mergeDescendants = true) { heading() }, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.premium_title), style = MaterialTheme.typography.titleSmall)
                // The title already says Premium, so the mark stays silent.
                Text(SPARKLE, Modifier.padding(start = 6.dp).clearAndSetSemantics { }, color = leaf)
            }
            Row(verticalAlignment = Alignment.Top) {
                Text(SKY, Modifier.width(24.dp).clearAndSetSemantics { }, color = leaf)
                Column {
                    Text(stringResource(R.string.premium_sky_controls), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .toggleable(value = showSky, role = Role.Switch, onValueChange = onSky),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.detail_show_sky), Modifier.weight(1f))
                        Switch(showSky, onCheckedChange = null)
                    }
                    Text(stringResource(R.string.settings_sky_footnote), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (BuildConfig.PREMIUM_ENABLED && purchased != null) {
                val label = stringResource(if (purchased) R.string.premium_purchased else R.string.schedule_support_title)
                val content: @Composable RowScope.() -> Unit = {
                    Text(SPARKLE, Modifier.padding(end = 8.dp).clearAndSetSemantics { })
                    Text(label)
                }
                val modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                // A supporter is thanked, not sold to again, and the sheet stays one tap away.
                if (purchased) TextButton(onSupport, modifier, colors = ButtonDefaults.textButtonColors(contentColor = leaf), content = content)
                else Button(onSupport, modifier, content = content)
            }
        }
    }
}
