package io.keepagent.core.host

import android.content.res.AssetManager
import io.keepagent.addonsapi.AddonManifest
import io.keepagent.addonsapi.validate
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Discovers add-ons on disk and validates their manifests (spec §5.4).
 * Bundled samples are seeded from app assets into [addonsDir] on first run,
 * so a user's addons directory is always a plain, writable directory.
 */
class AddonRepository(
    private val addonsDir: File,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    /** Copies `assets/addons/<name>/…` into [addonsDir] for anything not present yet. */
    fun seedFromAssets(assets: AssetManager) {
        val names = runCatching { assets.list("addons") ?: emptyArray() }.getOrDefault(emptyArray())
        for (name in names) {
            val target = File(addonsDir, name)
            if (target.exists()) continue
            target.mkdirs()
            copyAssetDir(assets, "addons/$name", target)
        }
    }

    private fun copyAssetDir(assets: AssetManager, assetDir: String, intoDir: File) {
        val children = assets.list(assetDir) ?: return
        for (child in children) {
            val childAssetPath = "$assetDir/$child"
            val grandchildren = assets.list(childAssetPath)
            if (grandchildren == null || grandchildren.isEmpty()) {
                runCatching {
                    File(intoDir, child).outputStream().use { out ->
                        assets.open(childAssetPath).use { it.copyTo(out) }
                    }
                }
            } else {
                val sub = File(intoDir, child)
                sub.mkdirs()
                copyAssetDir(assets, childAssetPath, sub)
            }
        }
    }

    fun discover(): List<AddonRecord> {
        return addonsDir.listFiles { f -> f.isDirectory }?.sortedBy { it.name }?.mapNotNull { dir ->
            val manifestFile = File(dir, "addon.json")
            if (!manifestFile.exists()) {
                AddonRecord(dir.name, null, listOf("addon.json missing"), AddonStatus.INVALID)
            } else {
                runCatching { json.decodeFromString(AddonManifest.serializer(), manifestFile.readText()) }
                    .fold(
                        onSuccess = { m ->
                            val result = m.validate()
                            AddonRecord(
                                dirName = dir.name,
                                manifest = m,
                                validationErrors = result.errors,
                                status = if (result.ok) AddonStatus.VALID else AddonStatus.INVALID,
                            )
                        },
                        onFailure = { e ->
                            AddonRecord(dir.name, null, listOf("addon.json: ${e.message}"), AddonStatus.INVALID)
                        },
                    )
            }
        } ?: emptyList()
    }
}
