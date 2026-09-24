package io.keepagent.app.ui.tabs

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.keepagent.app.Holder
import io.keepagent.app.test.ProjectChecks
import io.keepagent.app.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun TestTab(onGotoChat: () -> Unit, onGotoProjects: () -> Unit) {
    val app = Holder.app
    val root = app.workspaceManager.activeRoot().canonicalFile
    var section by rememberSaveable(root.path) { mutableStateOf("checks") }
    var revision by remember { mutableIntStateOf(0) }
    var targets by remember(root.path) { mutableStateOf<List<ProjectChecks.Target>?>(null) }
    val runs by app.projectChecks.runs.collectAsState()
    val history = runs.filter { it.workspace == root.path }.takeLast(20).reversed()

    LaunchedEffect(root.path, revision) {
        targets = withContext(Dispatchers.IO) { ProjectChecks.discover(root) }
    }
    BackHandler(section == "preview") { section = "checks" }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("Test", style = MaterialTheme.typography.headlineSmall)
                    Text("Working in ${root.name}", color = TextSecondary)
                }
                TextButton(onClick = onGotoProjects) { Text("Change project") }
            }
            Text("Run project checks, preview a page, and share results with the agent.",
                style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("checks" to "Checks", "preview" to "Preview", "results" to "Results").forEach { (id, label) ->
                    FilterChip(selected = section == id, onClick = { section = id }, label = { Text(label) })
                }
            }
        }
        when (section) {
            "preview" -> Box(Modifier.weight(1f)) { WebPreviewTab(onGotoChat) }
            "results" -> Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Recent results", style = MaterialTheme.typography.titleLarge)
                if (history.isEmpty()) Text("No runs yet. Choose a check to see results here.", color = TextSecondary)
                history.forEach { run ->
                    var expanded by remember(run.id) { mutableStateOf(false) }
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(run.title, style = MaterialTheme.typography.titleMedium)
                            Text(if (run.running) "Running" else if (run.ok) "Passed" else "Failed",
                                color = if (run.ok || run.running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                            Text(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(run.id)),
                                style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                            if (run.running) LinearProgressIndicator(Modifier.fillMaxWidth())
                            else {
                                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide output" else "View output") }
                                if (expanded) SelectionContainer { Text(run.output, style = MaterialTheme.typography.bodySmall) }
                                OutlinedButton(onClick = {
                                    val evidence = "Test result for ${root.name}: ${run.title}\n${run.output}"
                                    app.chatController.setDraft(listOf(app.chatController.draftText.value, evidence)
                                        .filter { it.isNotBlank() }.joinToString("\n\n"))
                                    onGotoChat()
                                }) { Text("Share with agent") }
                            }
                        }
                    }
                }
            }
            else -> Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Available checks", style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = { revision++ }) { Text("Refresh") }
                }
                if (targets == null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Finding checks…", color = TextSecondary)
                } else {
                    val runnable = targets.orEmpty().filter { it.kind == "pytest" || it.kind == "java" }
                    if (runnable.isEmpty()) {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("No automated checks found", style = MaterialTheme.typography.titleMedium)
                                Text("Add a Python test or Java main class to this project. You can still preview HTML pages.",
                                    color = TextSecondary)
                                OutlinedButton(onClick = { section = "preview" }) { Text("Open preview") }
                            }
                        }
                    }
                    runnable.forEach { target ->
                        val run = history.firstOrNull { it.target == target.path }
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(target.title, style = MaterialTheme.typography.titleMedium)
                                Text(target.detail, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                                if (run != null) Text(if (run.running) "Running now" else if (run.ok) "Last run passed" else "Last run failed",
                                    style = MaterialTheme.typography.bodySmall)
                                Button(onClick = { app.projectChecks.start(root, target); section = "results" },
                                    enabled = runs.none { it.running }, modifier = Modifier.fillMaxWidth()) {
                                    Text(if (target.kind == "java") "Run Java" else "Run Python tests")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
