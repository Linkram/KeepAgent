package io.keepagent.core.host

import io.keepagent.addonsapi.AddonManifest

enum class AddonStatus {
    DISCOVERED,
    VALID,
    INVALID,
    ENABLED,
    INITIALIZED,
    FAILED,
    UNAVAILABLE,
}

/** A discovered add-on and its current state (spec §5.4). */
data class AddonRecord(
    val dirName: String,
    val manifest: AddonManifest?,
    val validationErrors: List<String>,
    var status: AddonStatus,
    var statusDetail: String? = null,
    val tools: MutableList<String> = mutableListOf(),
) {
    val id: String get() = manifest?.id ?: dirName
}
