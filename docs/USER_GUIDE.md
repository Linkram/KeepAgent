# KeepAgent — User Guide

KeepAgent is an AI coding agent that lives on your phone. You chat with it,
it writes and edits files in workspaces on the device, shows you every tool
call before it runs (if you want), and you can test what it builds — all
without a computer.

## Quick start

1. Open **Connections** and create one: a base URL and a model name for any
   OpenAI-compatible API (a gateway, Ollama on your LAN, vLLM, …).
2. Tap the connection's **active** button so it becomes the active one.
3. Open **Chat** and send a message, e.g.
   `make a one-page site about lighthouses in index.html`.
4. When a tool wants to do something (usually a file write), an **approval
   card** appears: tap **allow** or **always this session**.

That's the whole loop: ask → approve → the agent works → you see the result.

## Run and test on a computer

The desktop runner is optional; KeepAgent's core phone workflow does not require it.
Use it only when a project needs a desktop-only runtime, native desktop application,
or a workload you deliberately want to offload. Install it from
[`../companion/README.md`](../companion/README.md), then open
**Tools** in the bottom navigation, enter its address and pairing token, then check the
connection. The agent gains tools for commands, files, headless Chromium, real Electron
apps, configured MCP servers and an explicitly configured Android emulator/device.

Commands and tests continue on the companion if the phone disconnects. Open **Tools**
and refresh jobs to inspect state, exit code, output and screenshots. A queued/running
job has not passed; success requires a zero exit or passing structured assertions.

The companion runs as your computer account. Keep it on loopback or behind an encrypted
private tunnel, and pair it only with the project directory you intend to expose.

## Mobile navigation

Phones use four thumb-reachable destinations: **Chat**, **Files**, **Test**, and
**Tools**. Tools contains models, add-ons, activity, help and execution targets. Wider
screens use a navigation rail. Android Back returns secondary tools to Tools and primary
destinations to Chat. Chat text uses the system reading font and larger body sizing.

## Chat

- **Streaming + thinking.** The reply streams token by token; models that
  "think" show a collapsible *Thought* block above the answer.
- **Tool lines.** Every tool call shows up as its own line
  (`write(noodleboats/style.css)` with a status). Tap a line for details.
- **Approval cards.** With approval mode *ask*, each sensitive tool call
  pauses for you. *Always this session* remembers the choice for that tool
  for the rest of the session.
- **Speed + context chips** (top bar): average tokens/second (green ≥ 30,
  yellow 14–29, red < 14) and how much of the model's context window the
  last turn used.

### Working with your own messages

Tap any **message you sent** to open its actions:

- **edit** — change the text and hit *send & continue*: the conversation
  rolls back to that message and the agent re-answers from there (your
  attachments for that message are kept).
- **branch** — start a new chat that contains everything up to and including
  that message, so you can explore a different direction without losing the
  original.
- **copy** / **delete** — the obvious ones.

### History

The **history icon** (top left, next to the speed) opens the chat sidebar:
all saved chats, most recent first. Tap a chat to open it; use the row
buttons to **rename** or **delete** it; **new** starts a fresh chat. The
**docs** button opens this guide and the developer docs.

### Chat settings

The small **settings** pill (bottom right, above the input bar) opens a
compact popup with the three per-chat switches:

- **Model** — pick from the active connection's model list (the configured
  model stays selectable even if the endpoint doesn't list it).
- **Approval** — `ask` (prompt every time), `auto-allow` (run without
  asking), `never-ask` (power-user posture).
- **Files** — the sandbox the file tools work in: `workspace` (only the
  active workspace), `app-files` (the app's whole storage), `read-only`.

Tap anywhere outside the popup to close it.

## Workspaces

A workspace is a folder the agent works in — its file tools can only touch
it (in *workspace* file-access mode).

- **Create / select / delete** workspaces from the **Workspaces** tab.
  Switching workspaces re-roots the file tools and the sandboxes
  immediately — no restart.
- The file **explorer** at the bottom of the tab lists the workspace tree;
  tap a file to read it, delete stray files, or open one in Chat context.
- **Share → KeepAgent** from other apps drops a file straight into the
  active workspace and opens a chat with it attached.
- To continue a public GitHub/GitLab-style project, enter a new project name,
  paste its HTTP(S) clone URL, and tap **Clone public Git repository**. The app
  keeps the full included Git history and opens the project after cloning.
- To move a local/private project without giving KeepAgent remote credentials,
  export it as a ZIP, enter a new project name, and tap **Import ZIP as new
  project**. Existing project names are never overwritten.
- **share zip** adds `.keepagent/handoff.json` to the exported source bundle. It
  carries bounded recent goal/decision context plus Git branch, commit and dirty
  hashes. Re-importing the bundle lets the agent resume from that neutral handoff;
  it does not claim unrecorded tests passed or copy provider credentials.

Saved model API keys and provider records are AES-GCM encrypted with a
non-exportable Android Keystore key. Older plaintext settings migrate when read.
If Android restores preferences onto a device without the original key, KeepAgent
clears the unusable credential and asks you to enter it again.

## Test tab

Test now opens a project overview. It discovers Python tests and HTML previews while
skipping dependency/build directories. Tap **Run Python tests** to execute pytest on
the phone, then expand the result or send it into the Chat draft. Runs continue across
tab changes; the last 20 results survive an app restart. A run interrupted by Android
is marked interrupted and must be started again explicitly. Node and Gradle
projects show missing-runtime explanations. **Open preview** enters the web viewer;
Back returns to project tests. Refresh rescans the workspace without running code.

For web projects: the Test tab serves your workspace over loopback and loads
a page in a built-in browser. Choose an HTML page, tap **Open**, inspect the
preview and console summary, then tap **Send evidence to agent** to attach a
real PNG capture and console output to Chat.

The top-right **Fit** menu contains phone, tablet, and desktop viewport sizes.
Reload, history navigation, external-browser, fullscreen, rotation, page-list
refresh, and save-capture actions are in the overflow menu so the normal test
flow stays focused. If the project has no HTML page, **Create a starter page**
creates and opens `index.html`.

## On-device runtimes

KeepAgent includes Python 3.13, pytest, HTTP requests, and QuickJS in the app.
The agent can run a workspace-relative Python script or pytest selection through
the approval-gated `run_python` tool without a paired computer or a second app.
Open **Tools → On this phone** to verify the packaged runtime versions. Output is
bounded and execution is confined to paths under the active workspace, although
executed code still has the app's own network and private-storage permissions.

Packages with Android-compatible wheels must be selected when KeepAgent is built;
ordinary desktop wheels and OS-specific dependencies do not automatically become
Android-compatible. Future large runtime packs will be optional and explicit rather
than silently downloading on every launch.

## Console

The **Console** tab is the live event stream: every tool call, provider
event, add-on registration, and system message, in order. Useful when
something looks off — the answer is usually in here.

## Add-ons

KeepAgent's tools and providers are add-ons:

- **Tier 1** runs in-process (the file tools, the OpenAI-compatible
  provider).
- **Tier 2** runs in a sandboxed JavaScript engine (`quickjs-ng`) in a
  separate `:js` process — a crashing add-on takes the sandbox, not the app.

The **Add-ons** tab shows each one's status and lets you enable/disable it
(disabling removes its tools from the agent). A running add-on's tools can
be invoked directly from the tab to test them without a chat.

## Markdown

Chat replies render real markdown: headings, **bold**, *italic*, `inline
code`, bullet and numbered lists, tables, blockquotes, and fenced code
blocks — each code block has a **copy** button. Long lines scroll
horizontally inside the block instead of breaking your thumbs.

## Approval & file access, in one breath

The approval gate and the file sandbox are two separate dials:

- **Approval mode** decides whether a tool call asks you first.
- **File access** decides *where* file tools can reach.

The default posture (ask + workspace) is safe for a small model: it can
only write into the current workspace, and only when you say so.

## When something breaks

1. Check **Console** — the last ~10 lines usually name the failure.
2. A `repaired malformed tool arguments` event means the model sent broken
   tool JSON and the harness fixed it — normal for small models.
3. A `stopped at the 12 tool-call limit` note at the end of a reply means
   the agent hit its per-turn budget; just send **continue**.
4. If a JS add-on looks dead, disable and re-enable it from **Add-ons**.
# Import an existing project (2026-09-04)

Open Workspaces, tap the current project name to open the project chooser, and
enter a new name. Paste a public HTTP(S) Git clone URL, or tap **Import ZIP as new
project** and select an archive through Android's file picker.

Include AGENTS.md, CLAUDE.md, PLAN.md, or HANDOFF.md to carry project guidance
across. KeepAgent reads bounded root-file excerpts on each turn. Included `.git`
files are copied, but desktop chats, installed dependencies, executable bits and
symlinks are not restored. See [the product spec](PRODUCT_SPEC.md) for limits.
