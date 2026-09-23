package io.keepagent.app.ui.tabs

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.keepagent.app.Holder
import io.keepagent.app.test.ProjectChecks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun TestTab(onGotoChat: () -> Unit) {
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
        ProjectTestWorkspace(onGotoChat)
    }
}

@Composable
private fun ProjectTestWorkspace(onGotoChat: () -> Unit) {
    val app = Holder.app
    val root = app.workspaceManager.activeRoot().canonicalFile
    var preview by rememberSaveable(root.path) { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    var checks by remember(root.path) { mutableStateOf<List<ProjectChecks.Target>?>(null) }
    val runs by app.projectChecks.runs.collectAsState()
    LaunchedEffect(root.path, revision) {
        checks = withContext(Dispatchers.IO) { ProjectChecks.discover(root) }
    }
    BackHandler(preview) { preview = false }
    if (preview) {
        Column(Modifier.fillMaxSize()) {
            TextButton(onClick = { preview = false }) { Text("‹ Project tests") }
            Box(Modifier.weight(1f)) { WebPreviewTab(onGotoChat) }
        }
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("Test your project", style = MaterialTheme.typography.headlineSmall)
                Text(root.name, style = MaterialTheme.typography.bodyMedium)
            }
            TextButton(onClick = { revision++ }) { Text("Refresh") }
        }
        if (checks == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Looking for tests and previews…")
        } else if (checks!!.isEmpty()) {
            Text("No runnable entry points found yet. Add a Python test, Java main class, or an HTML interface.")
        }
        checks.orEmpty().forEach { target ->
            val run = runs.lastOrNull { it.workspace == root.path && it.target == target.path }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(target.title, style = MaterialTheme.typography.titleMedium)
                    Text(target.detail, style = MaterialTheme.typography.bodySmall)
                    if (target.kind == "web") {
                        OutlinedButton(onClick = { preview = true }, modifier = Modifier.fillMaxWidth()) { Text("Open preview") }
                    } else if (target.kind == "pytest" || target.kind == "java") {
                        Button(onClick = { app.projectChecks.start(root, target) },
                            enabled = runs.none { it.running }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (run?.running == true) "Running on phone…" else if (target.kind == "java") "Compile & run Java" else "Run Python tests")
                        }
                        if (run?.running == true) LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }
        }
        if (checks != null && checks!!.none { it.kind == "web" }) {
            OutlinedButton(onClick = { preview = true }, modifier = Modifier.fillMaxWidth()) { Text("Open app preview") }
        }
        val history = runs.filter { it.workspace == root.path }.takeLast(10).reversed()
        Text("Recent results", style = MaterialTheme.typography.titleMedium)
        if (history.isEmpty()) Text("Results will appear here after a run. Opening this screen never executes project code.")
        history.forEach { run ->
            var expanded by remember(run.id) { mutableStateOf(false) }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(run.title, style = MaterialTheme.typography.titleMedium)
                    Text(if (run.running) "Running on this phone" else if (run.ok) "Passed · on this phone" else "Failed · on this phone")
                    Text(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(run.id)), style = MaterialTheme.typography.bodySmall)
                    if (!run.running) {
                        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide output" else "View output") }
                        if (expanded) SelectionContainer { Text(run.output, style = MaterialTheme.typography.bodySmall) }
                        OutlinedButton(onClick = {
                            val evidence = "Test result for ${root.name}: ${run.title}\n${run.output}"
                            app.chatController.setDraft(listOf(app.chatController.draftText.value, evidence).filter { it.isNotBlank() }.joinToString("\n\n"))
                            onGotoChat()
                        }) { Text("Send result to agent") }
                    }
                }
            }
        }
    }
}
