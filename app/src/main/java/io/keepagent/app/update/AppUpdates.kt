package io.keepagent.app.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class AvailableUpdate(
    val versionCode: Long,
    val versionName: String,
    val downloadUrl: String,
    val size: Long,
    val sha256: String?,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Downloading(val percent: Int) : UpdateState
    data class Ready(val update: AvailableUpdate) : UpdateState
    data class Error(val message: String) : UpdateState
}

/** Checks the public stable release, downloads its APK, and hands installation to Android. */
class AppUpdates private constructor(private val context: Context) {
    private val prefs = context.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state
    private var readyFile: File? = null
    private var running = false

    suspend fun check(force: Boolean = false) {
        if (running) return
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong("last_check", 0) < CHECK_INTERVAL_MS) return
        running = true
        _state.value = UpdateState.Checking
        try {
            val update = withContext(Dispatchers.IO) { fetchLatest() }
            prefs.edit().putLong("last_check", now).apply()
            if (update == null) {
                _state.value = UpdateState.UpToDate
            } else {
                val apk = withContext(Dispatchers.IO) { downloadAndVerify(update) }
                readyFile = apk
                _state.value = UpdateState.Ready(update)
            }
        } catch (e: Exception) {
            _state.value = UpdateState.Error(e.message ?: "Update check failed")
        } finally {
            running = false
        }
    }

    fun install(): Boolean {
        val apk = readyFile?.takeIf { it.isFile } ?: return false
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            return false
        }
        val uri = FileProvider.getUriForFile(context, "io.keepagent.app.fileprovider", apk)
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        return true
    }

    private fun fetchLatest(): AvailableUpdate? {
        val connection = open("https://api.github.com/repos/Linkram/KeepAgent/releases/latest")
        val body = try {
            if (connection.responseCode != 200) error("GitHub returned HTTP ${connection.responseCode}")
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
        val release = JSONObject(body)
        val assets = release.getJSONArray("assets")
        val candidates = (0 until assets.length()).mapNotNull { index ->
            val asset = assets.getJSONObject(index)
            val match = APK_NAME.matchEntire(asset.optString("name")) ?: return@mapNotNull null
            val code = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
            val url = asset.optString("browser_download_url")
            if (!url.startsWith("https://github.com/Linkram/KeepAgent/releases/download/")) return@mapNotNull null
            val digest = asset.optString("digest").removePrefix("sha256:")
                .takeIf { it.matches(Regex("[a-fA-F0-9]{64}")) }
            AvailableUpdate(code, release.optString("tag_name"), url, asset.optLong("size"), digest)
        }
        val latest = candidates.maxByOrNull { it.versionCode }
            ?: error("Latest GitHub release has no keepagent-v<versionCode>.apk asset")
        val installed = if (Build.VERSION.SDK_INT >= 28) {
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionCode.toLong()
        }
        return latest.takeIf { it.versionCode > installed }
    }

    private fun downloadAndVerify(update: AvailableUpdate): File {
        require(update.size in 1..MAX_APK_BYTES) { "Release APK size is invalid" }
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val apk = File(dir, "keepagent-v${update.versionCode}.apk")
        if (apk.isFile && verify(apk, update)) return apk
        apk.delete()
        val part = File(dir, "keepagent-v${update.versionCode}.pending.apk")
        part.delete()
        val connection = open(update.downloadUrl)
        try {
            if (connection.responseCode != 200) error("APK download returned HTTP ${connection.responseCode}")
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            connection.inputStream.use { input ->
                part.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= update.size && total <= MAX_APK_BYTES) { "APK exceeds release size" }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                        _state.value = UpdateState.Downloading((total * 100 / update.size).toInt())
                    }
                }
            }
            require(total == update.size) { "Incomplete APK download" }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            if (update.sha256 != null) require(sha.equals(update.sha256, ignoreCase = true)) { "APK checksum mismatch" }
            require(verifyPackage(part, update.versionCode)) { "APK package, version, or signing certificate does not match" }
            require(part.renameTo(apk)) { "Could not save update APK" }
            return apk
        } finally {
            connection.disconnect()
            part.delete()
        }
    }

    private fun verify(file: File, update: AvailableUpdate): Boolean {
        if (file.length() != update.size || !verifyPackage(file, update.versionCode)) return false
        val expected = update.sha256 ?: return true
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }.equals(expected, ignoreCase = true)
    }

    @Suppress("DEPRECATION")
    private fun verifyPackage(file: File, versionCode: Long): Boolean {
        val flag = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val installed = context.packageManager.getPackageInfo(context.packageName, flag)
        val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, flag) ?: return false
        val actualCode = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        if (archive.packageName != context.packageName || actualCode != versionCode) return false
        val currentSigners = if (Build.VERSION.SDK_INT >= 28) installed.signingInfo?.apkContentsSigners else installed.signatures
        val updateSigners = if (Build.VERSION.SDK_INT >= 28) archive.signingInfo?.apkContentsSigners else archive.signatures
        return !currentSigners.isNullOrEmpty() && !updateSigners.isNullOrEmpty() &&
            currentSigners.map { it.toCharsString() }.toSet() == updateSigners.map { it.toCharsString() }.toSet()
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        setRequestProperty("Accept", "application/vnd.github+json")
        setRequestProperty("User-Agent", "KeepAgent-Android-Update")
    }

    companion object {
        private val APK_NAME = Regex("keepagent-v([0-9]+)\\.apk", RegexOption.IGNORE_CASE)
        private const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
        private const val MAX_APK_BYTES = 600L * 1024 * 1024
        @Volatile private var instance: AppUpdates? = null

        fun get(context: Context): AppUpdates = instance ?: synchronized(this) {
            instance ?: AppUpdates(context.applicationContext).also { instance = it }
        }
    }
}
