package io.openwaters.slackwater.android.alerts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import io.openwaters.slackwater.android.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.openwaters.slackwater.android.core.planning.AlertRule
import io.openwaters.slackwater.android.core.station.StationIdentity

data class AlertRow(val rule: AlertRule, val station: StationIdentity, val status: String)

@Composable
fun AlertsScreen(rows: List<AlertRow>, onEdit: (AlertEditorDraft) -> Unit, onDismiss: () -> Unit) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.alert_screen_back)) }
        Text(stringResource(R.string.alert_screen_title), style = MaterialTheme.typography.headlineLarge)
        if (rows.isEmpty()) Text(stringResource(R.string.alert_screen_empty), Modifier.padding(vertical = 16.dp))
        LazyColumn {
            items(rows, key = { it.rule.id.toString() }) { row ->
                val rule = row.rule
                ListItem(
                    headlineContent = { Text("${row.station.name} · ${rule.trigger.label()}") },
                    supportingContent = {
                        Column {
                            Text(rule.once?.let { row.station.timeLabel(it) } ?: stringResource(R.string.alert_every, rule.trigger.label()))
                            Text(row.status)
                        }
                    },
                    modifier = Modifier.clickable { onEdit(AlertEditorDraft(rule, row.station, rule.once, existing = true)) },
                )
                HorizontalDivider()
            }
        }
    }
}
