package io.keepagent.app.update

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

/** Starts the daily check on launch and offers Android's installer once download is verified. */
@Composable
fun AppUpdatePrompt() {
    val context = LocalContext.current
    val updates = remember { AppUpdates.get(context) }
    val state by updates.state.collectAsState()
    var dismissedVersion by remember { mutableStateOf<Long?>(null) }
    var permissionMessage by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { updates.check() }

    val ready = state as? UpdateState.Ready
    if (ready != null && ready.update.versionCode != dismissedVersion) {
        AlertDialog(
            onDismissRequest = { dismissedVersion = ready.update.versionCode },
            title = { Text("KeepAgent update ready") },
            text = {
                Text(
                    if (permissionMessage) "Allow KeepAgent to install apps in Android settings, then tap Install update again."
                    else "${ready.update.versionName} has been downloaded and verified. Android will ask you to confirm installation.",
                )
            },
            confirmButton = {
                Button(onClick = { permissionMessage = !updates.install() }) { Text("Install update") }
            },
            dismissButton = {
                TextButton(onClick = { dismissedVersion = ready.update.versionCode }) { Text("Later") }
            },
        )
    }
}

/** Manual retry and update status for the settings screen. */
@Composable
fun AppUpdateControl() {
    val context = LocalContext.current
    val updates = remember { AppUpdates.get(context) }
    val state by updates.state.collectAsState()
    val scope = rememberCoroutineScope()
    OutlinedButton(onClick = { scope.launch { updates.check(force = true) } }, enabled = state !is UpdateState.Checking && state !is UpdateState.Downloading) {
        Text("Check for app updates")
    }
    when (val current = state) {
        is UpdateState.Checking -> Text("Checking GitHub releases…")
        is UpdateState.Downloading -> {
            Text("Downloading update · ${current.percent}%")
            LinearProgressIndicator(progress = { current.percent / 100f })
        }
        is UpdateState.Ready -> {
            Text("${current.update.versionName} is ready to install")
            Button(onClick = { updates.install() }) { Text("Install update") }
        }
        is UpdateState.UpToDate -> Text("App is up to date")
        is UpdateState.Error -> Text(current.message)
        else -> Unit
    }
}
