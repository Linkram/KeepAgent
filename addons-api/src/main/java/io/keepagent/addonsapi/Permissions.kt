package io.keepagent.addonsapi

/**
 * Permission names an add-on may declare in its manifest (spec §5.2, §10).
 * Unknown names fail validation; the host is the only thing that can grant
 * more than declared.
 */
object Permissions {
    const val LOG = "log"
    const val SETTINGS_READ = "settings:read"
    const val SETTINGS_WRITE = "settings:write"
    const val WORKSPACE_READ = "workspace:read"
    const val WORKSPACE_WRITE = "workspace:write"
    const val NETWORK = "network"
    const val DEVICE_SCREENSHOT = "device:screenshot"
    // Test-target permissions (spec §3.1, §10): each maps to a system flow.
    const val DEVICE_UIAUTOMATION = "device:uiautomation" // AccessibilityService + intent launch
    const val PACKAGE_INSTALL = "package:install" // via the system installer UI, never silent
    const val PROCESS_SPAWN = "process:spawn" // subprocess execution under the exec approval class

    val ALL: Set<String> = setOf(
        LOG,
        SETTINGS_READ,
        SETTINGS_WRITE,
        WORKSPACE_READ,
        WORKSPACE_WRITE,
        NETWORK,
        DEVICE_SCREENSHOT,
        DEVICE_UIAUTOMATION,
        PACKAGE_INSTALL,
        PROCESS_SPAWN,
    )
}
