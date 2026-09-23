"""KeepAgent companion v1. Explicitly grants command execution as the launching user."""
from __future__ import annotations

import argparse
import hmac
import json
import os
from pathlib import Path
import platform
import signal
import subprocess
import sys
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

MAX_BODY = 1_048_576
MAX_LOG = 8 * 1024 * 1024
TERMINAL = {"succeeded", "failed", "canceled", "timed_out", "interrupted"}


class Runner:
    def __init__(self, workspace: Path, state: Path, addons: Path | None = None,
                 adb: Path | None = None, android_serial: str | None = None):
        self.workspace = workspace.resolve(strict=True)
        if not self.workspace.is_dir():
            raise ValueError("Workspace must be a directory")
        self.state = state.resolve()
        self.state.mkdir(parents=True, exist_ok=True)
        self.lock = threading.RLock()
        self.jobs = {}
        self.processes = {}
        self.addons_path = addons.resolve(strict=True) if addons else None
        self.addons = json.loads(self.addons_path.read_text(encoding="utf-8")) if addons else {}
        if not isinstance(self.addons, dict):
            raise ValueError("Add-on config must map names to server definitions")
        self.adb = adb.resolve(strict=True) if adb else None
        self.android_serial = android_serial
        for path in self.state.glob("*/job.json"):
            try:
                job = json.loads(path.read_text(encoding="utf-8"))
                if job.get("workspace") != str(self.workspace):
                    continue
                if job["state"] not in TERMINAL:
                    job["state"] = "interrupted"
                    job["finished_at"] = time.time()
                self.jobs[job["id"]] = job
                self._save(job)
            except (OSError, ValueError, KeyError):
                continue

    def path(self, relative: str, *, exists=False) -> Path:
        if not isinstance(relative, str) or Path(relative).is_absolute():
            raise ValueError("Use a path relative to the paired workspace")
        path = (self.workspace / relative).resolve(strict=exists)
        if not path.is_relative_to(self.workspace):
            raise ValueError("Path escapes the paired workspace")
        return path

    def _save(self, job):
        folder = self.state / job["id"]
        folder.mkdir(exist_ok=True)
        temporary = folder / "job.tmp"
        temporary.write_text(json.dumps(job), encoding="utf-8")
        temporary.replace(folder / "job.json")

    def start(self, body):
        argv = body.get("argv")
        if not isinstance(argv, list) or not argv or len(argv) > 256 or any(
            not isinstance(a, str) or "\0" in a or len(a) > 65536 for a in argv
        ):
            raise ValueError("argv must be a nonempty string array (maximum 256 arguments)")
        cwd = self.path(body.get("cwd", "."), exists=True)
        if not cwd.is_dir():
            raise ValueError("cwd must be a directory")
        timeout = body.get("timeout_seconds", 120)
        if not isinstance(timeout, (int, float)) or not 1 <= timeout <= 3600:
            raise ValueError("timeout_seconds must be between 1 and 3600")
        key = body.get("request_id") or str(uuid.uuid4())
        if not isinstance(key, str) or len(key) > 128:
            raise ValueError("Invalid request_id")
        with self.lock:
            for job in self.jobs.values():
                if job["request_id"] == key:
                    if job["argv"] != argv or job["cwd"] != str(cwd) or job["timeout_seconds"] != timeout:
                        raise ValueError("request_id already belongs to a different command")
                    return dict(job)
            if sum(j["state"] not in TERMINAL for j in self.jobs.values()) >= 4:
                raise ValueError("Four jobs are already active; wait or cancel a job")
            job = dict(id=str(uuid.uuid4()), request_id=key, argv=argv, cwd=str(cwd),
                       workspace=str(self.workspace), timeout_seconds=timeout, state="queued",
                       created_at=time.time(), exit_code=None, output_bytes=0, output_truncated=False)
            self.jobs[job["id"]] = job
            self._save(job)
            threading.Thread(target=self._run, args=(job["id"],), daemon=True).start()
            return dict(job)

    def _kill(self, process):
        if process.poll() is not None:
            return
        if os.name == "nt":
            try:
                subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"],
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                               creationflags=subprocess.CREATE_NO_WINDOW, timeout=3)
            except (OSError, subprocess.TimeoutExpired):
                pass
            if process.poll() is None:
                process.kill()
        else:
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass

    def _run(self, job_id):
        process = None
        with self.lock:
            job = self.jobs[job_id]
            if job["state"] == "canceled":
                return
            try:
                process = subprocess.Popen(job["argv"], cwd=job["cwd"], stdin=subprocess.DEVNULL,
                    stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                    env=dict(os.environ, KEEPAGENT_ARTIFACT_DIR=str(self.state / job_id)),
                    creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0,
                    start_new_session=os.name != "nt")
                self.processes[job_id] = process
                job.update(state="running", started_at=time.time())
                self._save(job)
            except OSError as error:
                job.update(state="failed", error=str(error), finished_at=time.time())
                self._save(job)
                return

        def capture():
            with (self.state / job_id / "output.log").open("wb") as log, process.stdout:
                try:
                    while chunk := process.stdout.read(4096):
                        with self.lock:
                            remaining = max(0, MAX_LOG - job["output_bytes"])
                            kept = chunk[:remaining]
                            log.write(kept)
                            log.flush()
                            job["output_bytes"] += len(kept)
                            job["output_truncated"] |= len(kept) < len(chunk)
                except (OSError, ValueError):
                    pass

        reader = threading.Thread(target=capture, daemon=True)
        reader.start()
        try:
            code = process.wait(timeout=job["timeout_seconds"])
            with self.lock:
                if job["state"] != "canceled":
                    job["state"] = "succeeded" if code == 0 else "failed"
                job["exit_code"] = code
        except subprocess.TimeoutExpired:
            with self.lock:
                if job["state"] != "canceled":
                    job["state"] = "timed_out"
            self._kill(process)
            process.wait(timeout=15)
        finally:
            reader.join(timeout=2)
            with self.lock:
                job["finished_at"] = time.time()
                self.processes.pop(job_id, None)
                self._save(job)

    def get(self, job_id, offset=0):
        if offset < 0:
            raise ValueError("offset must be nonnegative")
        with self.lock:
            job = dict(self.jobs[job_id])
        path = self.state / job_id / "output.log"
        data = b""
        if path.is_file():
            with path.open("rb") as log:
                log.seek(offset)
                data = log.read(16_384)
        job.update(output=data.decode("utf-8", errors="replace"), next_offset=offset + len(data))
        job["artifacts"] = [name for name in ("page.png", "trace.zip", "report.json")
                            if (self.state / job_id / name).is_file()]
        return job

    def cancel(self, job_id):
        with self.lock:
            job = self.jobs[job_id]
            if job["state"] not in TERMINAL:
                job.update(state="canceled", finished_at=time.time())
                self._save(job)
            process = self.processes.get(job_id)
        if process:
            self._kill(process)
        return self.get(job_id)

    def browser(self, body):
        # Browser actions are an execution capability and use exactly the same job limits.
        actions = body.get("actions", [])
        if not isinstance(actions, list) or len(actions) > 30:
            raise ValueError("Use at most 30 browser actions per job")
        url = body.get("url", "")
        if not isinstance(url, str) or urlparse(url).scheme not in {"http", "https", "file"}:
            raise ValueError("Browser URL must use http, https or file")
        if url.startswith("file:"):
            from urllib.request import url2pathname
            path = Path(url2pathname(urlparse(url).path)).resolve(strict=True)
            if not path.is_relative_to(self.workspace):
                raise ValueError("File URL escapes the paired workspace")
        payload = json.dumps(dict(url=url, actions=actions, viewport=body.get("viewport", "mobile")))
        return self.start(dict(argv=[sys.executable, str(Path(__file__).with_name("browser_worker.py")), payload],
                               timeout_seconds=body.get("timeout_seconds", 120), request_id=body.get("request_id")))

    def electron(self, body):
        entry = self.path(body.get("entry", ""), exists=True)
        actions = body.get("actions", [])
        if not entry.is_file() or not isinstance(actions, list) or len(actions) > 30:
            raise ValueError("Provide an Electron entry file and at most 30 actions")
        payload = json.dumps(dict(entry=str(entry), actions=actions))
        return self.start(dict(argv=["node", str(Path(__file__).with_name("electron_worker.cjs")), payload],
                               timeout_seconds=body.get("timeout_seconds", 120), request_id=body.get("request_id")))

    def addon(self, body):
        if body.get("server") not in self.addons:
            raise ValueError("Choose a server configured by the companion owner")
        return self.start(dict(argv=[sys.executable, str(Path(__file__).with_name("mcp_worker.py")),
                                    str(self.addons_path), json.dumps(body)], timeout_seconds=120))

    def android(self, body):
        if not self.adb or not self.android_serial:
            raise ValueError("The companion owner has not configured an Android test target")
        apk = body.get("apk")
        if apk:
            apk = str(self.path(apk, exists=True))
            if not apk.lower().endswith(".apk"):
                raise ValueError("apk must name an APK inside the paired workspace")
        actions = body.get("actions", [])
        if not isinstance(actions, list) or len(actions) > 50:
            raise ValueError("Use at most 50 Android actions")
        package = body.get("package", "")
        if package and not all(part.replace("_", "").isalnum() for part in package.split(".")):
            raise ValueError("Invalid Android package")
        payload = json.dumps(dict(adb=str(self.adb), serial=self.android_serial, apk=apk,
            package=package, activity=body.get("activity"), actions=actions))
        return self.start(dict(argv=[sys.executable, str(Path(__file__).with_name("android_worker.py")), payload],
                               timeout_seconds=body.get("timeout_seconds", 240), request_id=body.get("request_id")))


def make_server(runner: Runner, token: str, address=("127.0.0.1", 8765)):
    if len(token) < 24:
        raise ValueError("Set a bearer token of at least 24 characters")

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass  # Do not leak authorization, code, or queries into access logs.

        def do_GET(self):
            self.dispatch()

        def do_POST(self):
            self.dispatch()

        def dispatch(self):
            if not hmac.compare_digest(self.headers.get("Authorization", ""), "Bearer " + token):
                return self.respond(401, {"error": "Unauthorized"})
            if self.headers.get("Origin"):
                return self.respond(403, {"error": "Browser-origin API requests are not allowed"})
            try:
                route = urlparse(self.path)
                body = {}
                if self.command == "POST":
                    length = int(self.headers.get("Content-Length", "0"))
                    if not 0 < length <= MAX_BODY:
                        raise ValueError("Request must contain at most 1 MiB of JSON")
                    body = json.loads(self.rfile.read(length))
                    if not isinstance(body, dict):
                        raise ValueError("Expected a JSON object")
                if route.path == "/v1/capabilities" and self.command == "GET":
                    capabilities = ["exec", "files", "jobs", "browser", "electron", "mcp"]
                    if runner.adb and runner.android_serial:
                        capabilities.append("android")
                    result = dict(protocol=1, os=platform.system(), architecture=platform.machine(),
                        workspace=str(runner.workspace), capabilities=capabilities,
                        isolation="Commands run as the companion user; cwd is not a sandbox.")
                elif route.path == "/v1/jobs" and self.command == "POST":
                    result = runner.start(body)
                elif route.path == "/v1/jobs" and self.command == "GET":
                    with runner.lock:
                        result = {"jobs": [dict(j) for j in list(runner.jobs.values())[-50:]][::-1]}
                elif route.path == "/v1/browser" and self.command == "POST":
                    result = runner.browser(body)
                elif route.path == "/v1/electron" and self.command == "POST":
                    result = runner.electron(body)
                elif route.path == "/v1/addons" and self.command == "GET":
                    result = {"servers": list(runner.addons)}
                elif route.path == "/v1/addons" and self.command == "POST":
                    result = runner.addon(body)
                elif route.path == "/v1/android" and self.command == "POST":
                    result = runner.android(body)
                elif route.path.startswith("/v1/jobs/"):
                    parts = route.path.split("/")
                    if len(parts) == 6 and parts[4] == "artifacts" and self.command == "GET":
                        job = runner.get(parts[3])
                        if parts[5] not in job["artifacts"]:
                            raise KeyError("artifact")
                        artifact = runner.state / parts[3] / parts[5]
                        if artifact.stat().st_size > MAX_LOG:
                            raise ValueError("Artifact exceeds the 8 MiB download limit")
                        import base64
                        result = dict(name=parts[5], base64=base64.b64encode(artifact.read_bytes()).decode())
                    elif len(parts) == 5 and parts[4] == "cancel" and self.command == "POST":
                        result = runner.cancel(parts[3])
                    elif len(parts) == 4 and self.command == "GET":
                        result = runner.get(parts[3], int(parse_qs(route.query).get("offset", [0])[0]))
                    else:
                        return self.respond(404, {"error": "Unknown job operation"})
                elif route.path == "/v1/files" and self.command == "POST":
                    path = runner.path(body.get("path", "."))
                    action = body.get("action", "read")
                    if action == "list":
                        result = {"entries": [{"name": p.name, "directory": p.is_dir()}
                            for p in sorted(path.iterdir())[:500]]}
                    elif action == "read":
                        offset = int(body.get("offset", 0))
                        if offset < 0:
                            raise ValueError("offset must be nonnegative")
                        with path.open("rb") as stream:
                            stream.seek(offset)
                            data = stream.read(16384)
                        result = dict(text=data.decode("utf-8", errors="replace"), next_offset=offset + len(data),
                                      size=path.stat().st_size)
                    elif action == "write":
                        content = body.get("content")
                        if not isinstance(content, str):
                            raise ValueError("content must be text")
                        path.parent.mkdir(parents=True, exist_ok=True)
                        temp = path.with_name(path.name + ".keepagent-" + uuid.uuid4().hex)
                        try:
                            temp.write_text(content, encoding="utf-8")
                            temp.replace(path)
                        finally:
                            temp.unlink(missing_ok=True)
                        result = {"written": body["path"]}
                    else:
                        raise ValueError("File action must be list, read or write")
                else:
                    return self.respond(404, {"error": "Unknown endpoint"})
                self.respond(200, result)
            except KeyError:
                self.respond(404, {"error": "Job or required field not found"})
            except (ValueError, OSError, TypeError) as error:
                self.respond(400, {"error": str(error)})

        def respond(self, status, body):
            data = json.dumps(body).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(data)

    return ThreadingHTTPServer(address, Handler)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--workspace", type=Path, required=True)
    parser.add_argument("--state", type=Path, required=True)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--addons", type=Path, help="Explicitly trusted MCP server config JSON")
    parser.add_argument("--adb", type=Path, help="ADB executable for an explicitly selected Android test target")
    parser.add_argument("--android-serial", help="Exact ADB serial; never defaults to an arbitrary connected device")
    args = parser.parse_args()
    token = os.environ.get("KEEPAGENT_RUNNER_TOKEN", "")
    if bool(args.adb) != bool(args.android_serial):
        parser.error("--adb and --android-serial must be supplied together")
    server = make_server(Runner(args.workspace, args.state, args.addons, args.adb, args.android_serial), token, (args.host, args.port))
    print(f"KeepAgent companion: {args.host}:{server.server_port}; workspace={args.workspace.resolve()}", flush=True)
    print("Paired clients can execute programs with this user's permissions. Use a private encrypted tunnel.", flush=True)
    try:
        server.serve_forever()
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
