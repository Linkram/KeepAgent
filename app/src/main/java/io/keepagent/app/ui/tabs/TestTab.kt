package io.keepagent.app.ui.tabs

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.PixelCopy
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.keepagent.addonsapi.llm.ImagePart
import io.keepagent.app.Holder
import io.keepagent.app.ui.theme.AmberStatus
import io.keepagent.app.ui.theme.BevelLight
import io.keepagent.app.ui.theme.TextPrimary
import io.keepagent.app.ui.theme.TextSecondary
import io.keepagent.app.ui.theme.TileStone
import io.keepagent.app.ui.theme.UserBubble
import io.keepagent.app.test.TestServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Test tab (M1.2, F-006 groundwork): renders a workspace HTML file in a
 * local browser served over loopback HTTP (WebView blocks `file://` into
 * internal storage). Capture the page and the screenshot rides the next
 * chat message as an image attachment.
 */
@Composable
fun TestTab(onGotoChat: () -> Unit) {
    val app = Holder.app
    val fs = app.fileService
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var htmlFiles by remember { mutableStateOf<List<String>>(emptyList()) }
    var path by remember { mutableStateOf("index.html") }
    var loadedPath by remember { mutableStateOf<String?>(null) }
    var loadedRel by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var capturing by remember { mutableStateOf(false) }
    var fullscreen by remember { mutableStateOf(false) }
    var landscape by remember { mutableStateOf(false) }
    var webViewRef = remember { mutableStateOf<WebView?>(null) }
    var lastShot = remember { mutableStateOf<Bitmap?>(null) }
    var consoleLines by remember { mutableStateOf<List<String>>(emptyList()) }
    var consoleOpen by remember { mutableStateOf(false) }

    // Back/forward + viewport presets + open-in-browser (M1.4h).
    var viewport by remember { mutableStateOf("full") } // full | phone | tablet | desktop
    var canBack by remember { mutableStateOf(false) }
    var canFwd by remember { mutableStateOf(false) }

    fun setLandscape(on: Boolean) {
        landscape = on
        (context as? Activity)?.requestedOrientation =
            if (on) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    // Leaving the tab always restores the device's default orientation.
    DisposableEffect(Unit) {
        onDispose {
            (context as? Activity)?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    fun refreshFiles() {
        scope.launch {
            val r = withContext(Dispatchers.IO) { fs.glob("**/*.html") }
            if (r.ok) htmlFiles = r.text.split("\n").filter { it.isNotBlank() }
        }
    }

    fun load(p: String) {
        scope.launch {
            val f = withContext(Dispatchers.IO) { fs.resolve(p) }
            when {
                f == null -> notice = "outside workspace (file access = workspace)"
                !f.exists() || f.isDirectory -> notice = "no such file: $p"
                f.length() > 20_000_000 -> notice = "file too large for the browser: $p"
                else -> {
                    // WebView refuses file:// into the app's internal storage
                    // (ERR_ACCESS_DENIED), so the workspace is served over
                    // loopback HTTP instead — relative assets and fetch()
                    // behave like a real host.
                    val srv = withContext(Dispatchers.IO) { TestServer.ensure(fs.root) }
                    val rel = f.relativeTo(fs.root.absoluteFile.normalize())
                        .path.replace(File.separatorChar, '/')
                    val url = "http://127.0.0.1:${srv.port}/" +
                        rel.split("/").joinToString("/") {
                            URLEncoder.encode(it, "UTF-8").replace("+", "%20")
                        }
                    loadedPath = url
                    loadedRel = rel
                    notice = null
                    withContext(Dispatchers.Main) {
                        webViewRef.value?.loadUrl(url)
                    }
                }
            }
        }
    }

    /** Writes the captured console tail to reports/ and attaches it to Chat (main thread, small file). */
    fun attachConsoleToChat() {
        if (consoleLines.isEmpty()) return
        runCatching {
            val dir = File(fs.root, "reports")
            dir.mkdirs()
            val f = File(dir, "console-${System.currentTimeMillis()}.txt")
            f.writeText((loadedRel?.let { "page: $it\n" } ?: "") + consoleLines.joinToString("\n"))
            app.chatController.attachFile(
                io.keepagent.app.ChatController.AttachedFile("reports/${f.name}", false, ""),
            )
        }
    }

    fun savePng() {
        val bmp = lastShot.value ?: run { notice = "capture a screenshot first"; return }
        scope.launch(Dispatchers.IO) {
            val res = runCatching {
                val dir = File(fs.root, "reports")
                dir.mkdirs()
                val f = File(dir, "screenshot-${System.currentTimeMillis()}.png")
                f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                f
            }
            withContext(Dispatchers.Main) {
                notice = res.fold({ "saved ${it.name}" }, { "save failed: ${it.message}" })
            }
        }
    }

    /** Captures the WebView's on-screen region via PixelCopy and attaches it to Chat. */
    fun capture() {
        val wv = webViewRef.value ?: return
        if (wv.width == 0 || wv.height == 0) {
            notice = "nothing to capture yet — load a page first"
            return
        }
        val activity = context as? Activity ?: return
        val loc = IntArray(2)
        wv.getLocationOnScreen(loc)
        val rect = Rect(loc[0], loc[1], loc[0] + wv.width, loc[1] + wv.height)
        val bmp = Bitmap.createBitmap(wv.width, wv.height, Bitmap.Config.ARGB_8888)
        capturing = true
        notice = null
        PixelCopy.request(
            activity.window,
            rect,
            bmp,
            PixelCopy.OnPixelCopyFinishedListener { result ->
                capturing = false
                if (result == PixelCopy.SUCCESS) {
                    lastShot.value = bmp
                    val bytes = ByteArray(bmp.allocationByteCount)
                    bmp.copyPixelsToBuffer(ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()))
                    app.chatController.attachImage(
                        ImagePart("image/png", Base64.encodeToString(bytes, Base64.NO_WRAP)),
                    )
                    attachConsoleToChat()
                    notice = "screenshot attached — opening Chat"
                    onGotoChat()
                } else {
                    notice = "capture failed (code $result)"
                }
            },
            Handler(Looper.getMainLooper()),
        )
    }

    LaunchedEffect(Unit) {
        val r = withContext(Dispatchers.IO) { fs.glob("**/*.html") }
        if (r.ok) htmlFiles = r.text.split("\n").filter { it.isNotBlank() }
        if ("index.html" in htmlFiles && loadedPath == null) load("index.html")
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (!fullscreen) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Test", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                Spacer(modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { fullscreen = true }) {
                    Text("fullscreen", fontSize = 11.sp)
                }
                OutlinedButton(onClick = { setLandscape(!landscape) }) {
                    Text(if (landscape) "portrait" else "landscape", fontSize = 11.sp)
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextField(
                    value = path,
                    onValueChange = { path = it },
                    placeholder = { Text("index.html", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = TileStone,
                        unfocusedContainerColor = TileStone,
                        focusedIndicatorColor = BevelLight,
                        unfocusedIndicatorColor = BevelLight,
                    ),
                )
                OutlinedButton(onClick = { load(path) }) {
                    Text("Load")
                }
                OutlinedButton(onClick = { webViewRef.value?.reload() }) {
                    Text("reload", fontSize = 11.sp)
                }
                OutlinedButton(
                    onClick = { webViewRef.value?.goBack() },
                    enabled = canBack,
                ) {
                    Text("◂ back", fontSize = 11.sp)
                }
                OutlinedButton(
                    onClick = { webViewRef.value?.goForward() },
                    enabled = canFwd,
                ) {
                    Text("fwd ▸", fontSize = 11.sp)
                }
                OutlinedButton(onClick = {
                    val u = loadedPath
                    if (u != null) {
                        runCatching {
                            context.startActivity(
                                Intent.createChooser(
                                    Intent(Intent.ACTION_VIEW, android.net.Uri.parse(u)),
                                    "open in browser",
                                ),
                            )
                        }
                    }
                }, enabled = loadedPath != null) {
                    Text("browser", fontSize = 11.sp)
                }
            }
            // Viewport presets (M1.4h): constrain the page to a common size.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "viewport:",
                    fontSize = 10.sp,
                    color = TextSecondary,
                )
                listOf("full", "phone 390×844", "tablet 768×1024", "desktop 1280×800").forEach { label ->
                    val id = label.substringBefore(' ')
                    Text(
                        text = label.substringAfter(' ', label),
                        fontSize = 10.sp,
                        color = if (viewport == id) TextPrimary else TextSecondary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                if (viewport == id)
                                    io.keepagent.app.ui.theme.TileStoneSelected
                                else Color.Transparent,
                            )
                            .clickable { viewport = id }
                            .padding(horizontal = 7.dp, vertical = 3.dp),
                    )
                }
            }
            if (htmlFiles.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    htmlFiles.take(20).forEach { f ->
                        Text(
                            text = f,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = if (loadedRel == f) UserBubble else TextSecondary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(TileStone)
                                .clickable {
                                    path = f
                                    load(f)
                                }
                                .padding(horizontal = 6.dp, vertical = 3.dp),
                        )
                    }
                }
            }
            notice?.let {
                Text(
                    text = it,
                    fontSize = 10.sp,
                    color = AmberStatus,
                    maxLines = 2,
                )
            }
        }

        // Keyed so the WebView keeps its identity when the chrome above and
        // below it disappears in fullscreen mode.
        key("webview") {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(
                        horizontal = if (fullscreen) 0.dp else 10.dp,
                        vertical = if (fullscreen) 0.dp else 4.dp,
                    )
                    .then(
                        if (viewport != "full")
                            Modifier.verticalScroll(rememberScrollState())
                                .horizontalScroll(rememberScrollState())
                        else Modifier,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webViewRef.value = this
                        // Refresh back/forward button state as pages load (M1.4h).
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String) {
                                Handler(Looper.getMainLooper()).post {
                                    canBack = view.canGoBack()
                                    canFwd = view.canGoForward()
                                }
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onConsoleMessage(cm: ConsoleMessage): Boolean {
                                val line = "${cm.messageLevel().toString().take(1)}: ${cm.message()} (${cm.sourceId()}:${cm.lineNumber()})"
                                Handler(Looper.getMainLooper()).post {
                                    consoleLines = (consoleLines + line).takeLast(200)
                                }
                                return true
                            }
                        }
                    }
                },
                modifier = when (viewport) {
                    "phone" -> Modifier
                        .width(390.dp).height(844.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .border(width = 1.dp, color = BevelLight, shape = RoundedCornerShape(8.dp))
                        .background(Color.White)
                    "tablet" -> Modifier
                        .width(768.dp).height(1024.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .border(width = 1.dp, color = BevelLight, shape = RoundedCornerShape(8.dp))
                        .background(Color.White)
                    "desktop" -> Modifier
                        .width(1280.dp).height(800.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .border(width = 1.dp, color = BevelLight, shape = RoundedCornerShape(8.dp))
                        .background(Color.White)
                    else -> Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(8.dp))
                        .border(width = 1.dp, color = BevelLight, shape = RoundedCornerShape(8.dp))
                        .background(Color.White)
                },
            )
                if (loadedPath == null) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "no page loaded — enter a file path (e.g. index.html) and tap Load",
                            fontSize = 12.sp,
                            color = TextSecondary,
                        )
                    }
                }
                if (fullscreen) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        OutlinedButton(onClick = { setLandscape(!landscape) }) {
                            Text(if (landscape) "portrait" else "landscape", fontSize = 11.sp)
                        }
                        OutlinedButton(onClick = { fullscreen = false }) {
                            Text("exit fullscreen", fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        if (!fullscreen) {
            // Console tail from the page's JS — errors highlighted.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 2.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { consoleOpen = !consoleOpen }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (consoleOpen) "▾" else "▸",
                    fontSize = 10.sp,
                    color = TextSecondary,
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "console · ${consoleLines.size} lines",
                    fontSize = 10.sp,
                    color = if (consoleLines.any { it.startsWith("E") }) AmberStatus else TextSecondary,
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "clear",
                    fontSize = 9.sp,
                    color = TextSecondary,
                    modifier = Modifier.clickable { consoleLines = emptyList() },
                )
            }
            if (consoleOpen && consoleLines.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 110.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 14.dp, vertical = 2.dp),
                ) {
                    consoleLines.takeLast(50).forEach { l ->
                        Text(
                            text = l,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            color = if (l.startsWith("E")) AmberStatus else TextSecondary,
                            maxLines = 3,
                        )
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = { capture() },
                enabled = loadedPath != null && !capturing,
            ) {
                Text(if (capturing) "capturing…" else "Send screenshot to agent")
            }
            OutlinedButton(onClick = { savePng() }) {
                Text("save png", fontSize = 11.sp)
            }
            OutlinedButton(onClick = { refreshFiles() }) {
                Text("refresh files", fontSize = 11.sp)
            }
            loadedPath?.let {
                Text(
                    text = "loaded: $it",
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            }
        }
    }
}
