package io.keepagent.app.ui.tabs

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.PixelCopy
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Test tab (M1.2, F-006 groundwork): renders a workspace HTML file in a
 * local browser. Capture the page and the screenshot rides the next chat
 * message as an image attachment.
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
    var notice by remember { mutableStateOf<String?>(null) }
    var capturing by remember { mutableStateOf(false) }
    var webViewRef = remember { mutableStateOf<WebView?>(null) }

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
                f.length() > 2_000_000 -> notice = "file too large for the browser: $p"
                else -> {
                    loadedPath = f.absolutePath
                    notice = null
                    withContext(Dispatchers.Main) {
                        webViewRef.value?.loadUrl(Uri.fromFile(f).toString())
                    }
                }
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
                    val bytes = ByteArray(bmp.allocationByteCount)
                    bmp.copyPixelsToBuffer(ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()))
                    app.chatController.attachImage(
                        ImagePart("image/png", Base64.encodeToString(bytes, Base64.NO_WRAP)),
                    )
                    notice = "screenshot attached — opening Chat"
                    onGotoChat()
                } else {
                    notice = "capture failed (code $result)"
                }
            },
            Handler(Looper.getMainLooper()),
        )
    }

    LaunchedEffect(Unit) { refreshFiles() }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text("Test", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            Text(
                text = "Renders a workspace HTML file in a local browser. Have the agent build or fix the page in Chat, load it here, then send a screenshot back to the agent.",
                fontSize = 11.sp,
                color = TextSecondary,
            )
            Row(
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
            }
            if (htmlFiles.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    htmlFiles.take(20).forEach { f ->
                        val abs = fs.root.resolve(f).absolutePath
                        Text(
                            text = f,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = if (loadedPath == abs) UserBubble else TextSecondary,
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

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webViewRef.value = this
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(8.dp))
                    .border(width = 1.dp, color = BevelLight, shape = RoundedCornerShape(8.dp))
                    .background(Color.White),
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
            OutlinedButton(onClick = { refreshFiles() }) {
                Text("refresh files")
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
