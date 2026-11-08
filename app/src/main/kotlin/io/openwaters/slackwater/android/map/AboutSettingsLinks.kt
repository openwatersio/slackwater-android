package io.openwaters.slackwater.android.map

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.PackageInfoCompat
import io.openwaters.slackwater.android.R

@Composable
internal fun AboutSettingsLinks() {
    var about by rememberSaveable { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    var unavailableUrl by rememberSaveable { mutableStateOf<String?>(null) }
    fun open(url: String) {
        try {
            uriHandler.openUri(url)
        } catch (_: IllegalArgumentException) {
            unavailableUrl = url
        }
    }

    TextButton(onClick = { open("https://slackwater.xyz/support/") }) { Text(stringResource(R.string.settings_help)) }
    TextButton(onClick = { about = true }) {
        Column {
            Text(stringResource(R.string.about_title))
            Text(stringResource(R.string.about_summary), style = MaterialTheme.typography.bodySmall)
        }
    }
    if (about) AboutDialog(::open) { about = false }
    unavailableUrl?.let { url ->
        AlertDialog(onDismissRequest = { unavailableUrl = null }, text = { Text(url) }, confirmButton = {
            TextButton(onClick = { unavailableUrl = null }) { Text(stringResource(R.string.close)) }
        })
    }
}

@Composable
private fun AboutDialog(open: (String) -> Unit, dismiss: () -> Unit) {
    val context = LocalContext.current
    val version = remember(context) {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName} (${PackageInfoCompat.getLongVersionCode(info)})"
    }
    var credits by rememberSaveable { mutableStateOf(false) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(R.string.about_title)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.about_predictions), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.about_predictions_text))
            Text(stringResource(R.string.about_chs_predictions))
            Text(stringResource(R.string.about_navigation), style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = { credits = true }) { Text(stringResource(R.string.about_attribution)) }
            TextButton(onClick = { open("https://slackwater.xyz/privacy") }) { Text(stringResource(R.string.about_privacy)) }
            Text(stringResource(R.string.about_license), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.about_license_text))
            TextButton(onClick = { open("https://github.com/openwatersio/slackwater-android") }) {
                Text(stringResource(R.string.about_source))
            }
            Text(stringResource(R.string.about_version), style = MaterialTheme.typography.titleSmall)
            Text(version)
        }
    }, confirmButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.close)) } })
    if (credits) DataCreditsDialog { credits = false }
}

@Composable
internal fun DataCreditsDialog(dismiss: () -> Unit) {
    val context = LocalContext.current
    val notices = remember(context) {
        context.resources.openRawResource(R.raw.third_party_notices).bufferedReader().use {
            it.readText().trim().split(Regex("\\n{2,}"))
        }
    }
    AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(R.string.data_credits)) }, text = {
        LazyColumn(Modifier.heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(notices) { paragraph -> Text(paragraph) }
        }
    }, confirmButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.close)) } })
}
