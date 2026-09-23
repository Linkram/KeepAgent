"""Run a synthetic Android smoke task through the same persistent job path as the app."""
from pathlib import Path
import argparse
import json
import time

from runner import Runner, TERMINAL


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--workspace", type=Path, required=True)
    parser.add_argument("--state", type=Path, required=True)
    parser.add_argument("--adb", type=Path, required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--package", required=True)
    parser.add_argument("--activity")
    parser.add_argument("--text", action="append", default=[])
    args = parser.parse_args()
    runner = Runner(args.workspace, args.state, adb=args.adb, android_serial=args.serial)
    actions = [{"action": "assert_text", "text": text} for text in args.text]
    actions.append({"action": "assert_package", "package": args.package})
    job = runner.android({"package": args.package, "activity": args.activity, "actions": actions})
    deadline = time.time() + 60
    while time.time() < deadline:
        result = runner.get(job["id"])
        if result["state"] in TERMINAL and job["id"] not in runner.processes:
            print(json.dumps(result, indent=2))
            raise SystemExit(0 if result["state"] == "succeeded" else 1)
        time.sleep(.1)
    runner.cancel(job["id"])
    raise SystemExit("Android smoke test timed out")


if __name__ == "__main__":
    main()
