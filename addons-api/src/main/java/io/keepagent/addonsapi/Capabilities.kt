package io.keepagent.addonsapi

/**
 * Capability ids an add-on may provide (spec §5.3). The capability registry
 * is the only coupling between host and add-ons: add-ons register, the host
 * consumes — never the reverse.
 */
object Capabilities {
    const val TOOL = "tool"
    const val LLM_PROVIDER = "llm.provider"
    const val UI_PANEL = "ui.panel"
    const val CHAT_RENDERER = "chat.renderer"
    const val WORKSPACE_SOURCE = "workspace.source"
    const val TEST_RUNNER = "test.runner" // target kinds: web, desktop, android, custom (spec §3.1)
    const val DEV_TOOLCHAIN = "dev.toolchain" // language/toolchain registration for the run tool (spec §8.1)
    const val CONSOLE_COMMAND = "console.command"
    const val CONNECTION = "connection"
    const val NOTIFICATION = "notification"
    const val SETTINGS_SCHEMA = "settings.schema"

    val ALL: Set<String> = setOf(
        TOOL,
        LLM_PROVIDER,
        UI_PANEL,
        CHAT_RENDERER,
        WORKSPACE_SOURCE,
        TEST_RUNNER,
        DEV_TOOLCHAIN,
        CONSOLE_COMMAND,
        CONNECTION,
        NOTIFICATION,
        SETTINGS_SCHEMA,
    )
}
