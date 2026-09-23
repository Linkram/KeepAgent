# KeepAgent companion

The companion executes project commands and tests on the operating system where
the project actually runs. Pairing is explicit: it exposes one selected workspace,
requires a bearer token, binds to loopback by default, and never selects an Android
device unless an exact ADB serial is configured.

## Setup

```powershell
cd companion
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
$env:PLAYWRIGHT_BROWSERS_PATH = "$PWD\.browsers"
.\.venv\Scripts\python.exe -m playwright install chromium
npm install
$env:KEEPAGENT_RUNNER_TOKEN = "generate-a-long-random-secret-here"
.\.venv\Scripts\python.exe runner.py --workspace C:\path\to\project --state .runner-state
```

In the Android app, open **Tools**, enter the companion origin and the same token,
then tap **Save and check connection**. `127.0.0.1` on the phone is the phone, not the
computer. During USB development, run `adb reverse tcp:8765 tcp:8765`; the phone can
then use `http://127.0.0.1:8765`. For normal use, bind the companion to the computer's
private-overlay address and use that encrypted tunnel address. Plain LAN HTTP exposes
the token and project data to that network.

The paired client can execute programs as the account running the companion. Use a
dedicated account or OS sandbox when the project or model is untrusted.

## Optional targets

`npm install` enables actual Electron window testing. Playwright enables headless
Chromium. Add an exact emulator/device explicitly:

```powershell
.\.venv\Scripts\python.exe runner.py ... `
  --adb C:\Android\Sdk\platform-tools\adb.exe --android-serial emulator-5556
```

The Android target can install an APK from the paired workspace, launch it, tap/type,
assert visible UI text/foreground package, and retain its screenshot and UI XML. Do
not point it at a personal device if automated app installation/input is unwanted.

MCP servers are owner-configured in JSON and are never accepted from agent input:

```json
{
  "project-tools": {"command": "python", "args": ["tools/server.py"], "cwd": "C:/project"},
  "hosted-tools": {"url": "https://trusted.example/mcp"}
}
```

Pass `--addons addons.json`. Keep secrets in the server environment rather than JSON.
The app first lists configured server names, then discovers only the selected server's
tool schemas. MCP calls run as persistent jobs with the same timeout and output limits.

## Verification

```powershell
$env:PLAYWRIGHT_BROWSERS_PATH = "$PWD\.browsers"
.\.venv\Scripts\python.exe -m unittest -v test_runner
```

The suite checks auth, origin rejection, paths, idempotency, reconnect state, exits,
timeouts, cancellation, paged output, headless browser assertions, a real Electron
fixture, and MCP discovery/calling. `smoke_android.py` tests an explicitly named device.
