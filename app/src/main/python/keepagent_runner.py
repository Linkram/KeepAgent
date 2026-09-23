"""Bounded entry point for KeepAgent's bundled on-device Python runtime."""

from contextlib import redirect_stderr, redirect_stdout
from io import StringIO
import json
import os
import runpy
import sys
import traceback


MAX_OUTPUT = 64 * 1024


class BoundedOutput(StringIO):
    def __init__(self):
        super().__init__()
        self.omitted = 0

    def write(self, text):
        remaining = max(0, MAX_OUTPUT - self.tell())
        super().write(text[:remaining])
        self.omitted += max(0, len(text) - remaining)
        return len(text)

    def getvalue(self):
        text = super().getvalue()
        return text + ("\n… {} characters omitted".format(self.omitted) if self.omitted else "")


def info():
    import pytest
    import requests
    return json.dumps({
        "python": sys.version.split()[0],
        "pytest": pytest.__version__,
        "requests": requests.__version__,
    })


def _trim(value):
    if len(value) <= MAX_OUTPUT:
        return value
    omitted = len(value) - MAX_OUTPUT
    return value[:MAX_OUTPUT] + "\n… output truncated; {} characters omitted".format(omitted)


def run(workspace, path, mode, arguments_json):
    """Run one workspace script or pytest invocation and return compact JSON."""
    output, errors = BoundedOutput(), BoundedOutput()
    old_argv = list(sys.argv)
    old_path = list(sys.path)
    old_cwd = os.getcwd()
    args = json.loads(arguments_json or "[]")
    exit_code = 0
    try:
        os.chdir(workspace)
        sys.path.insert(0, workspace)
        with redirect_stdout(output), redirect_stderr(errors):
            if mode == "pytest":
                import pytest
                exit_code = int(pytest.main(([path] if path else []) + args))
            else:
                sys.argv = [path] + args
                runpy.run_path(path, run_name="__main__")
    except SystemExit as exc:
        exit_code = 0 if exc.code is None else (int(exc.code) if isinstance(exc.code, int) else 1)
        if exc.code not in (None, 0):
            print(str(exc.code), file=errors)
    except BaseException:
        exit_code = 1
        traceback.print_exc(file=errors)
    finally:
        sys.argv = old_argv
        sys.path[:] = old_path
        os.chdir(old_cwd)
    return json.dumps({
        "exit_code": exit_code,
        "stdout": _trim(output.getvalue()),
        "stderr": _trim(errors.getvalue()),
    })
