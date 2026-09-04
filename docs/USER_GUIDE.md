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

## Test tab

For web projects: the Test tab serves your workspace over loopback and loads
a page in a built-in browser. Pick a viewport preset (phone / desktop),
open a URL path (`index.html`), and read the **console** — logs, warnings,
and errors — the same evidence you'd get in desktop devtools. This is what
the agent will drive itself in the next milestone.

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
