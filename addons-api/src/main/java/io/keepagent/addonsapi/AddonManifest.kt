package io.keepagent.addonsapi

import kotlinx.serialization.Serializable

/**
 * The manifest every add-on ships (spec §5.2). The host grants an add-on
 * nothing beyond what a *valid* manifest declares.
 */
@Serializable
data class AddonManifest(
    val id: String,
    val name: String,
    val version: String,
    val apiVersion: Int,
    val tier: Int,
    val entry: String,
    val permissions: List<String> = emptyList(),
    val provides: List<String> = emptyList(),
    val depends: List<AddonDependency> = emptyList(),
    val minHost: String = "0.1.0",
    val sigil: String? = null,
)

@Serializable
data class AddonDependency(val id: String, val version: String)

/** Result of manifest validation; an add-on only loads when [errors] is empty. */
data class ValidationResult(val errors: List<String>) {
    val ok: Boolean get() = errors.isEmpty()
}

/**
 * Validates a manifest against the host's supported API versions and the
 * known permission/capability registries (spec §5.4: "validate" is a hard
 * gate — invalid manifests never initialize).
 */
fun AddonManifest.validate(
    supportedApiVersions: Set<Int> = setOf(1),
    knownPermissions: Set<String> = Permissions.ALL,
    knownCapabilities: Set<String> = Capabilities.ALL,
): ValidationResult {
    val errors = mutableListOf<String>()
    if (!id.matches(Regex("^[a-z0-9]+(\\.[a-z0-9-]+)+$"))) {
        errors += "id must be a reverse-domain name, e.g. io.example.my-addon"
    }
    if (name.isBlank()) errors += "name must not be blank"
    if (!version.matches(Regex("^\\d+\\.\\d+\\.\\d+(-[0-9A-Za-z.-]+)?$"))) {
        errors += "version must be semver (MAJOR.MINOR.PATCH)"
    }
    if (apiVersion !in supportedApiVersions) {
        errors += "apiVersion $apiVersion not supported (supported: ${supportedApiVersions.sorted().joinToString()})"
    }
    if (tier !in 1..3) errors += "tier must be 1, 2, or 3"
    if (entry.isBlank()) errors += "entry must not be blank"
    if (tier == 2 && !entry.endsWith(".js")) {
        errors += "Tier-2 entry must be a .js file (bundle your TypeScript before shipping)"
    }
    permissions.forEach { p ->
        if (p !in knownPermissions) {
            errors += "unknown permission \"$p\" (known: ${knownPermissions.sorted().joinToString()})"
        }
    }
    provides.forEach { c ->
        if (c !in knownCapabilities) {
            errors += "unknown capability \"$c\" (known: ${knownCapabilities.sorted().joinToString()})"
        }
    }
    return ValidationResult(errors)
}
