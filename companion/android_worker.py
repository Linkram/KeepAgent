"""Drive one explicitly configured Android emulator/device through ADB."""
from __future__ import annotations

import json
import os
from pathlib import Path
import subprocess
import sys
import xml.etree.ElementTree as ET


def main():
    task = json.loads(sys.argv[1])
    adb, serial = task["adb"], task["serial"]
    artifacts = Path(os.environ["KEEPAGENT_ARTIFACT_DIR"])

    def run(*args, binary=False, timeout=30):
        result = subprocess.run([adb, "-s", serial, *args], capture_output=True, timeout=timeout)
        if result.returncode:
            raise RuntimeError((result.stderr or result.stdout).decode(errors="replace")[:2000])
        return result.stdout if binary else result.stdout.decode(errors="replace")

    package = task.get("package", "")
    apk = task.get("apk")
    if apk:
        run("install", "-r", apk, timeout=180)
    if package:
        activity = task.get("activity")
        if activity:
            run("shell", "am", "start", "-W", "-n", f"{package}/{activity}")
        else:
            run("shell", "monkey", "-p", package, "-c", "android.intent.category.LAUNCHER", "1")

    checks, actions = [], task.get("actions", [])
    for action in actions:
        kind = action.get("action")
        if kind == "tap":
            run("shell", "input", "tap", str(int(action["x"])), str(int(action["y"])))
        elif kind == "text":
            text = action["text"]
            if not isinstance(text, str) or any(c in text for c in "\n\r\0"):
                raise ValueError("Android text input must be a single line")
            # input text receives one argv item through adb; percent encoding handles spaces.
            run("shell", "input", "text", text.replace("%", "%25").replace(" ", "%s"))
        elif kind == "key":
            run("shell", "input", "keyevent", str(action["key"]))
        elif kind in {"assert_text", "assert_package"}:
            continue
        else:
            raise ValueError("Actions: tap, text, key, assert_text, assert_package")

    run("shell", "uiautomator", "dump", "/sdcard/keepagent-runner.xml")
    xml_bytes = run("exec-out", "cat", "/sdcard/keepagent-runner.xml", binary=True)
    (artifacts / "ui.xml").write_bytes(xml_bytes)
    visible = []
    for node in ET.fromstring(xml_bytes).iter("node"):
        text = node.attrib.get("text") or node.attrib.get("content-desc")
        if text:
            visible.append(text)
    foreground = run("shell", "dumpsys", "window", "windows")
    for action in actions:
        if action["action"] == "assert_text":
            expected = action["text"]
            passed = any(expected in text for text in visible)
            checks.append({"kind": "text", "expected": expected, "passed": passed})
            if not passed:
                raise AssertionError(f"Text not found: {expected}")
        elif action["action"] == "assert_package":
            expected = action.get("package", package)
            passed = expected in foreground
            checks.append({"kind": "package", "expected": expected, "passed": passed})
            if not passed:
                raise AssertionError(f"Package is not foreground: {expected}")
    png = run("exec-out", "screencap", "-p", binary=True)
    (artifacts / "page.png").write_bytes(png)
    result = {"passed": True, "target": "android", "serial": serial,
              "package": package, "checks": checks, "visible_text": visible[:200]}
    (artifacts / "report.json").write_text(json.dumps(result), encoding="utf-8")
    print(json.dumps(result))


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(json.dumps({"passed": False, "target": "android", "error": str(error)[:3000]}))
        raise SystemExit(1)
