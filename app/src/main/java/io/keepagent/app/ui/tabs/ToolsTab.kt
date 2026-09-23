package io.keepagent.app.ui.tabs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import io.keepagent.app.Holder
import io.keepagent.app.phoneui.PhoneUiAccessCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import io.keepagent.app.ui.theme.TextPrimary
import io.keepagent.app.ui.theme.TextSecondary

@Composable
fun ToolsTab(onNavigate: (Int) -> Unit, onDocs: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val notificationPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { }
    val connection = Holder.app.runnerConnection
    val scope = rememberCoroutineScope()
    var endpoint by rememberSaveable { mutableStateOf(connection.endpoint) }
    var token by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var jobs by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var selectedJob by remember { mutableStateOf<JsonObject?>(null) }
    var screenshot by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var localRuntimeStatus by remember { mutableStateOf("Bundled · works offline") }

    fun request(action: suspend () -> Unit) {
        scope.launch {
            busy = true
            try { action() }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { status = e.message ?: "Connection failed. Check the companion and retry." }
            finally { busy = false }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Text("Your development tools", style=MaterialTheme.typography.headlineSmall, color=TextPrimary)
        Text("Manage local capabilities and inspect the agent’s work.", style=MaterialTheme.typography.bodyMedium, color=TextSecondary)
        OutlinedButton(onClick = {
            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            } else {
                context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName))
            }
        }) { Text("Background notifications") }
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick={ onNavigate(5) }, modifier=Modifier.weight(1f)) { Text("Models") }
            OutlinedButton(onClick={ onNavigate(4) }, modifier=Modifier.weight(1f)) { Text("Add-ons") }
        }
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick={ onNavigate(3) }, modifier=Modifier.weight(1f)) { Text("Activity log") }
            OutlinedButton(onClick=onDocs, modifier=Modifier.weight(1f)) { Text("Help") }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Text("On this phone", style=MaterialTheme.typography.titleLarge)
                Text("Python 3.13, pytest, Java compilation, HTTP requests, and QuickJS are included. They run locally without pairing. Larger phone-native toolchains belong here as installable packs.")
                Text(localRuntimeStatus, style=MaterialTheme.typography.bodySmall, color=TextSecondary)
                OutlinedButton(
                    enabled=!busy,
                    modifier=Modifier.fillMaxWidth(),
                    onClick={ request {
                        val raw = withContext(Dispatchers.IO) { Holder.app.addonManager.invokeTool("local_runtime_info", "{}") }
                        val payload = Json.parseToJsonElement(raw).jsonObject
                        localRuntimeStatus = payload["text"]?.jsonPrimitive?.contentOrNull
                            ?: payload["error"]?.jsonPrimitive?.contentOrNull
                            ?: "Runtime check failed"
                    } },
                ) { Text("Verify local runtimes") }
            }
        }
        PhoneUiAccessCard()
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text("Desktop runner add-on", style=MaterialTheme.typography.titleLarge)
                Text("Optional. Connect a computer only for desktop operating systems, .exe apps, or workloads you choose to offload. Phone apps, local runtimes, app previews, and on-device add-ons must work without pairing.")
                OutlinedTextField(value=endpoint, onValueChange={endpoint=it}, label={Text("Companion address")},
                    placeholder={Text("http://100.x.x.x:8765")}, singleLine=true, modifier=Modifier.fillMaxWidth())
                OutlinedTextField(value=token, onValueChange={token=it}, label={Text("Pairing token")},
                    supportingText={Text("Stored encrypted. Leave blank to keep the token for this address.")},
                    visualTransformation=PasswordVisualTransformation(), singleLine=true, modifier=Modifier.fillMaxWidth())
                Text("Use an encrypted private tunnel (or USB port reverse) from this phone. Commands run with the companion user’s permissions.", style=MaterialTheme.typography.bodySmall)
                Button(enabled=!busy, modifier=Modifier.fillMaxWidth(), onClick={ request {
                    withContext(Dispatchers.IO) { connection.save(endpoint, token) }
                    token = ""
                    val info = withContext(Dispatchers.IO) { Json.parseToJsonElement(connection.request("/v1/capabilities")).jsonObject }
                    status = "Connected to ${info["os"]?.jsonPrimitive?.content}\nProject: ${info["workspace"]?.jsonPrimitive?.content}"
                } }) { Text("Save and check connection") }
                if (connection.configured()) {
                    OutlinedButton(enabled=!busy, onClick={ request {
                        val result = withContext(Dispatchers.IO) { Json.parseToJsonElement(connection.request("/v1/jobs")).jsonObject }
                        jobs = result["jobs"]?.jsonArray?.map { it.jsonObject }.orEmpty()
                        status = if (jobs.isEmpty()) "No jobs yet. Ask the agent to run a test in the paired project." else "Jobs refreshed"
                    } }, modifier=Modifier.fillMaxWidth()) { Text("Refresh jobs") }
                    TextButton(enabled=!busy, onClick={ connection.disconnect(); endpoint=""; jobs=emptyList(); selectedJob=null; status="Disconnected. Existing remote jobs continue until finished or canceled on the companion." }) { Text("Disconnect") }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (status.isNotBlank()) SelectionContainer { Text(status, style=MaterialTheme.typography.bodyMedium) }
            }
        }
        jobs.forEach { job ->
            val id = job.getValue("id").jsonPrimitive.content
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(job["argv"]?.jsonArray?.joinToString(" ") { it.jsonPrimitive.content }.orEmpty(), style=MaterialTheme.typography.titleSmall, maxLines=3)
                    Text("${job["state"]?.jsonPrimitive?.content} · exit ${job["exit_code"]}")
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        TextButton(enabled=!busy, onClick={ request {
                            selectedJob = withContext(Dispatchers.IO) { Json.parseToJsonElement(connection.request("/v1/jobs/$id")).jsonObject }
                        } }) { Text("View output") }
                        if (job["state"]?.jsonPrimitive?.content in listOf("queued", "running")) {
                            TextButton(enabled=!busy, onClick={ request {
                                selectedJob = withContext(Dispatchers.IO) { Json.parseToJsonElement(connection.request("/v1/jobs/$id/cancel", "{}")).jsonObject }
                                jobs = jobs.map { if (it["id"] == job["id"]) selectedJob!! else it }
                            } }) { Text("Stop job") }
                        }
                    }
                }
            }
        }
        selectedJob?.let { job ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text("${job["state"]?.jsonPrimitive?.content} · Output", style=MaterialTheme.typography.titleMedium)
                    SelectionContainer { Text(job["output"]?.jsonPrimitive?.content.orEmpty().ifEmpty { "No output yet" }) }
                    if (job["artifacts"]?.jsonArray?.any { it.jsonPrimitive.content == "page.png" } == true) {
                        TextButton(enabled=!busy, onClick={ request {
                            val id=job.getValue("id").jsonPrimitive.content
                            screenshot=withContext(Dispatchers.IO) {
                                val artifact=Json.parseToJsonElement(connection.request("/v1/jobs/$id/artifacts/page.png")).jsonObject
                                val bytes=android.util.Base64.decode(artifact.getValue("base64").jsonPrimitive.content, android.util.Base64.DEFAULT)
                                android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size)
                            }
                        } }) { Text("View test screenshot") }
                    }
                    screenshot?.let { Image(it.asImageBitmap(), contentDescription="Captured test application", modifier=Modifier.fillMaxWidth()) }
                    TextButton(enabled=!busy, onClick={ request {
                        val id=job.getValue("id").jsonPrimitive.content
                        val offset=job["next_offset"]?.jsonPrimitive?.longOrNull ?: 0
                        selectedJob=withContext(Dispatchers.IO) { Json.parseToJsonElement(connection.request("/v1/jobs/$id?offset=$offset")).jsonObject }
                    } }) { Text("Read next output page") }
                }
            }
        }
    }
}
