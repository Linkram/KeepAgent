# Research notes — 2026-09-04

Primary sources checked while implementing project import and guidance. These
inform engineering choices; they do not establish model performance gains.

| Source | Finding and application |
|---|---|
| [Anthropic: effective context engineering](https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents) (2025-09-29) | Select relevant context, retrieve detail when needed, and preserve working state through compaction/notes. Applied bounded project excerpts and source pointers; durable cross-turn checkpoints remain required. |
| [Qwen: function calling](https://qwen.readthedocs.io/en/latest/framework/function_call.html) | Function-call handling and replay depend on the serving/template path. Evaluate the deployed endpoint and reasoning/tool protocol; parameter count alone is insufficient. No Qwen-version-specific claim is made for the owner's model. |
| [Ollama: context length](https://docs.ollama.com/context-length) | Allocated context and memory configuration matter; larger context costs memory. Keep deployed limits explicit and verify serving configuration instead of trusting model maximums. |
| [Android: Storage Access Framework](https://developer.android.com/guide/topics/providers/document-provider) | User-selected documents are available through the system picker. Applied OpenDocument for ZIP import without asking for broad storage access. |
| [Compose: accessibility defaults](https://developer.android.com/develop/ui/compose/accessibility/api-defaults) | Explicit 48 dp minimum sizes avoid overlapping expanded touch regions. Applied to import action; whole-app accessibility remains to be audited. |
| [Playwright: Electron](https://playwright.dev/docs/api/class-electron) | Experimental automation launches a real Electron process and inspects windows/main process. Supports an Electron runner design, not a claim that WebView tests cover native desktop applications. |
| [Android: adaptive navigation](https://developer.android.com/develop/ui/compose/layouts/adaptive/build-adaptive-navigation) (updated 2026-08-04) | Compact windows use a bottom navigation bar and expanded windows a rail. Applied four primary destinations with a tools hub for secondary surfaces. |
| [Playwright Python: installation](https://playwright.dev/python/docs/intro) | Browser binaries are installed separately from the library. Companion setup pins the library and documents explicit Chromium installation. |
| [Electron: automated testing](https://www.electronjs.org/docs/latest/tutorial/automated-testing) | Electron recommends WebDriver or Playwright approaches for application automation. The companion labels this separately from browser testing and validates a real fixture process. |
| [Official MCP Python SDK](https://github.com/modelcontextprotocol/python-sdk) | The current v2 SDK negotiates 2026-07-28 and earlier revisions and supports stdio and Streamable HTTP. The companion delegates protocol handling to the pinned SDK and keeps server configuration owner-controlled. |
| [Anthropic: advanced tool use](https://www.anthropic.com/engineering/advanced-tool-use) | Tool discovery can reduce context consumption when many tools exist. Applied capability gating and per-server MCP discovery; KeepAgent still needs measured model-specific comparisons. |
| [Android: layout and navigation patterns](https://developer.android.com/design/ui/mobile/guides/layout-and-content/layout-and-nav-patterns) | Give a screen one highest-importance action, keep secondary actions near their content, and move infrequent actions to overflow. Applied to the Test tab's Open → preview → Send evidence flow. |
| [Android: accessibility](https://developer.android.com/design/ui/mobile/guides/foundations/accessibility) | Interactive targets should be at least 48 dp and controls need meaningful descriptions. Applied to the Test tab's visible actions and icon buttons. |
| [Android: menu design](https://developer.android.com/guide/practices/ui_guidelines/menu_design.html) | Overflow menus are appropriate for less-frequent actions that should remain reachable. Applied to reload, navigation, fullscreen, rotation, refresh, and capture saving. |
| [Chaquopy 17 Android configuration](https://chaquo.com/chaquopy/doc/current/android.html) | Python and compatible native wheels are packaged at build time; arbitrary runtime pip installation is not a dependable Android distribution model. Applied Python 3.13, pytest and requests as pinned, bundled dependencies. |
| [Android: on-demand feature delivery](https://developer.android.com/guide/playcore/feature-delivery/on-demand) | Large modules can be requested on demand from Play, but installs may fail and this path is tied to app-bundle distribution. Core runtimes therefore remain install-time/offline; future large packs need explicit progress and recovery. |
| [Android WebView API](https://developer.android.com/reference/android/webkit/WebView.html#evaluateJavascript(java.lang.String,%20android.webkit.ValueCallback%3Cjava.lang.String%3E)) | WebView can evaluate JavaScript in the displayed page on the UI thread. This supports a standalone browser automation bridge; it is not a Chromium/desktop-process claim. |
| [Termux RUN_COMMAND](https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent) | Cross-app command execution requires the separate Termux app, its signature permission and explicit external-app opt-in. It may be a future optional integration, not KeepAgent's core standalone path. |

Review these sources again before implementing their planned workstreams. Pin actual
runtime versions in test evidence. Do not choose sampling parameters, quantization,
context limits, or server tool parsers based on unverified model-name assumptions.

Research gaps before release: direct mobile user studies, physical-device accessibility
tests, endpoint-specific 27B evaluation, native OS automation selection, protocol
compatibility and authenticated companion pairing design.
