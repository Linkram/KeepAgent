import json
from pathlib import Path
import sys
import tempfile
import threading
import time
import unittest
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from runner import Runner, make_server, TERMINAL


class RunnerTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="keepagent-runner-test-")
        self.root = Path(self.temp.name)
        self.workspace = self.root / "project"
        self.workspace.mkdir()
        self.runner = Runner(self.workspace, self.root / "state")
        self.server = make_server(self.runner, "test-token-" + "x" * 32, ("127.0.0.1", 0))
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        for job in list(self.runner.jobs.values()):
            if job["state"] not in TERMINAL:
                self.runner.cancel(job["id"])
        deadline = time.time() + 5
        while self.runner.processes and time.time() < deadline:
            time.sleep(.02)
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
        self.temp.cleanup()

    def request(self, path, body=None, token=True, origin=False):
        headers = {"Authorization": "Bearer test-token-" + "x" * 32} if token else {}
        if origin:
            headers["Origin"] = "https://untrusted.example"
        data = json.dumps(body).encode() if body is not None else None
        request = Request(f"http://127.0.0.1:{self.server.server_port}{path}", data=data, headers=headers)
        with urlopen(request, timeout=5) as response:
            return json.load(response)

    def wait(self, job):
        deadline = time.time() + 40
        while time.time() < deadline:
            result = self.runner.get(job["id"])
            if result["state"] in TERMINAL and job["id"] not in self.runner.processes:
                return result
            time.sleep(.02)
        self.fail("Job did not settle")

    def test_authentication_and_browser_origin_rejected(self):
        for kwargs, expected in [({"token": False}, 401), ({"origin": True}, 403)]:
            with self.assertRaises(HTTPError) as error:
                self.request("/v1/capabilities", **kwargs)
            self.assertEqual(expected, error.exception.code)
        self.assertEqual(1, self.request("/v1/capabilities")["protocol"])

    def test_command_cwd_output_exit_and_reconnect(self):
        body = {"argv": [sys.executable, "-c", "import pathlib; print(pathlib.Path.cwd().name); print('pass')"], "request_id": "once"}
        job = self.request("/v1/jobs", body)
        self.assertEqual(job["id"], self.request("/v1/jobs", body)["id"])
        result = self.wait(job)
        self.assertEqual("succeeded", result["state"])
        self.assertEqual(0, result["exit_code"])
        self.assertIn("project", result["output"])
        self.assertIn("pass", result["output"])
        restored = Runner(self.workspace, self.root / "state")
        self.assertEqual("succeeded", restored.get(job["id"])["state"])
        with self.assertRaises(HTTPError):
            self.request("/v1/jobs", dict(body, argv=["different"]))

    def test_failure_timeout_and_cancellation(self):
        failed = self.wait(self.runner.start({"argv": [sys.executable, "-c", "raise SystemExit(7)"]}))
        self.assertEqual(("failed", 7), (failed["state"], failed["exit_code"]))
        timed = self.wait(self.runner.start({"argv": [sys.executable, "-c", "import time; time.sleep(30)"], "timeout_seconds": 1}))
        self.assertEqual("timed_out", timed["state"])
        job = self.runner.start({"argv": [sys.executable, "-c", "import time; time.sleep(30)"]})
        self.request(f"/v1/jobs/{job['id']}/cancel", {})
        self.assertEqual("canceled", self.wait(job)["state"])

    def test_files_and_escape_rejection(self):
        self.request("/v1/files", {"action": "write", "path": "src/file.txt", "content": "hello"})
        self.assertEqual("hello", self.request("/v1/files", {"action": "read", "path": "src/file.txt"})["text"])
        for path in ["../outside", str(self.root / "outside")]:
            with self.assertRaises(HTTPError):
                self.request("/v1/files", {"action": "write", "path": path, "content": "bad"})
        self.assertFalse((self.root / "outside").exists())
        with self.assertRaises(HTTPError):
            self.request("/v1/jobs", {"argv": [sys.executable], "cwd": ".."})

    def test_output_paging_and_invalid_jobs(self):
        result = self.wait(self.runner.start({"argv": [sys.executable, "-c", "print('x'*20000)"]}))
        self.assertEqual(16384, len(result["output"]))
        tail = self.request(f"/v1/jobs/{result['id']}?offset={result['next_offset']}")
        self.assertGreater(len(tail["output"]), 3000)
        with self.assertRaises(HTTPError):
            self.request("/v1/jobs", {"argv": [], "timeout_seconds": 0})

    def test_headless_browser_assertions(self):
        import importlib.util
        if not importlib.util.find_spec("playwright"):
            self.skipTest("Playwright not installed")
        page = self.workspace / "index.html"
        page.write_text('<button onclick="this.textContent=\'Passed\'">Run</button>', encoding="utf-8")
        job = self.runner.browser({"url": page.as_uri(), "actions": [
            {"action": "click", "selector": "button"},
            {"action": "assert_text", "selector": "button", "text": "Passed"}]})
        result = self.wait(job)
        self.assertEqual("succeeded", result["state"], result["output"])
        self.assertIn('"passed": true', result["output"])
        failed = self.wait(self.runner.browser({"url": page.as_uri(), "actions": [
            {"action": "assert_text", "selector": "button", "text": "wrong"}]}))
        self.assertEqual("failed", failed["state"])

    def test_real_electron_app(self):
        import shutil
        if not (Path(__file__).parent / "node_modules" / "electron").exists():
            self.skipTest("Optional Electron dependencies not installed")
        shutil.copytree(Path(__file__).parent / "fixtures" / "electron", self.workspace / "desktop")
        result = self.wait(self.runner.electron({"entry": "desktop/main.cjs", "actions": [
            {"action": "click", "selector": "button"},
            {"action": "assert_text", "selector": "output", "text": "Desktop test passed"}]}))
        self.assertEqual("succeeded", result["state"], result["output"])
        self.assertIn('"target":"electron"', result["output"])
        self.assertIn("page.png", result["artifacts"])

    def test_mcp_discovery_and_invocation(self):
        import importlib.util
        if not importlib.util.find_spec("mcp"):
            self.skipTest("Optional MCP dependency not installed")
        config = self.root / "addons.json"
        config.write_text(json.dumps({"fixture": {"command": sys.executable,
            "args": [str(Path(__file__).parent.resolve() / "fixtures" / "mcp_server.py")]}}), encoding="utf-8")
        self.runner.addons_path = config
        self.runner.addons = json.loads(config.read_text())
        discovery = self.wait(self.runner.addon({"server": "fixture"}))
        self.assertEqual("succeeded", discovery["state"], discovery["output"])
        self.assertIn('"name":"add"', discovery["output"])
        result = self.wait(self.runner.addon({"server": "fixture", "tool": "add", "arguments": {"a": 2, "b": 3}}))
        self.assertEqual("succeeded", result["state"], result["output"])
        self.assertIn("5", result["output"])


if __name__ == "__main__":
    unittest.main()
