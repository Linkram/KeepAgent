package io.keepagent.app.phoneui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.keepagent.app.Holder
import io.keepagent.core.settings.SettingsStore

@Composable
fun PhoneUiAccessCard() {
    val context = LocalContext.current
    val settings = Holder.app.settingsStore
    val connected by PhoneUiService.connected.collectAsState()
    var mode by remember { mutableStateOf(settings.getString(SettingsStore.NS_GENERAL, "phoneUiAccess") ?: "off") }
    var confirmFull by remember { mutableStateOf(false) }
    var notificationDenied by remember { mutableStateOf(false) }

    fun save(value: String) {
        settings.setString(SettingsStore.NS_GENERAL, "phoneUiAccess", value)
        mode = value
        if (value == "off") PhoneUiService.stop()
    }

    fun openAccessibilitySettings() {
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            save("ask")
            if (!connected) openAccessibilitySettings()
        } else notificationDenied = true
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Other phone apps", style = MaterialTheme.typography.titleLarge)
            Text("Let the agent read visible controls and interact with any foreground app, such as a chat or browser. Screen text it reads may be sent to your selected AI model.")
            Text("Access: ${when (mode) { "full" -> "Full access"; "ask" -> "Ask each action"; else -> "Off" }} · Android service: ${if (connected) "on" else "off"}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    notificationDenied = false
                    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                            context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        save("ask")
                        if (!connected) openAccessibilitySettings()
                    }
                }) { Text("Ask each action") }
                OutlinedButton(onClick = { confirmFull = true }) { Text("Full access") }
            }
            if (mode != "off") {
                if (!connected) {
                    Button(onClick = ::openAccessibilitySettings) { Text("Enable in Android settings") }
                }
                TextButton(onClick = { save("off") }) { Text("Turn off app control") }
            }
            if (notificationDenied) Text("Allow KeepAgent notifications to approve actions while another app is open, or choose Full access.")
            Text("Android requires you to enable KeepAgent phone app control in Accessibility settings. You can revoke it there at any time.", style = MaterialTheme.typography.bodySmall)
        }
    }

    if (confirmFull) {
        AlertDialog(
            onDismissRequest = { confirmFull = false },
            title = { Text("Allow full phone app access?") },
            text = { Text("The agent can read and control visible content in other apps without asking for each action while this is on. Android's Accessibility permission is still required. You can turn this off in Tools or Android settings.") },
            confirmButton = {
                Button(onClick = {
                    confirmFull = false
                    save("full")
                    if (!connected) openAccessibilitySettings()
                }) { Text("Allow full access") }
            },
            dismissButton = { TextButton(onClick = { confirmFull = false }) { Text("Cancel") } },
        )
    }
}
