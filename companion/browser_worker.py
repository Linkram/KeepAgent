"""One isolated, bounded headless Chromium session. Requires Playwright + Chromium."""
import json
import sys
import os
from pathlib import Path


def main():
    try:
        from playwright.sync_api import sync_playwright
    except ImportError:
        raise SystemExit("Browser dependency missing. Run: python -m pip install playwright; python -m playwright install chromium")
    task = json.loads(sys.argv[1])
    console = []
    errors = []
    checks = []
    artifacts = Path(os.environ["KEEPAGENT_ARTIFACT_DIR"])
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(headless=True)
        mobile = task.get("viewport") != "desktop"
        page = browser.new_page(viewport={"width": 390 if mobile else 1440, "height": 844 if mobile else 900})
        page.set_default_timeout(10000)
        page.context.tracing.start(screenshots=True, snapshots=True, sources=False)
        page.on("console", lambda message: console.append(message.text[:1000]) if len(console) < 30 else None)
        page.on("pageerror", lambda error: errors.append(str(error)[:1000]) if len(errors) < 30 else None)
        try:
            page.goto(task["url"], wait_until="domcontentloaded", timeout=30000)
            for action in task["actions"]:
                kind = action.get("action")
                selector = action.get("selector", "body")
                if kind == "click":
                    page.locator(selector).click()
                elif kind == "fill":
                    page.locator(selector).fill(action["text"])
                elif kind == "assert_text":
                    from playwright.sync_api import expect
                    expect(page.locator(selector)).to_contain_text(action["text"])
                    checks.append({"selector": selector, "passed": True})
                elif kind == "assert_visible":
                    from playwright.sync_api import expect
                    expect(page.locator(selector)).to_be_visible()
                    checks.append({"selector": selector, "passed": True})
                else:
                    raise ValueError("Actions: click, fill, assert_text, assert_visible")
            print(json.dumps(dict(passed=not errors, url=page.url, title=page.title(), checks=checks,
                                  text=page.locator("body").inner_text()[:8000], console=console, errors=errors)))
            if errors:
                raise SystemExit(1)
        except Exception as error:
            print(json.dumps(dict(passed=False, error=str(error)[:3000], checks=checks, console=console, errors=errors)))
            raise SystemExit(1)
        finally:
            try:
                page.screenshot(path=str(artifacts / "page.png"), full_page=False)
                page.context.tracing.stop(path=str(artifacts / "trace.zip"))
            except Exception as error:
                print(json.dumps({"artifact_error": str(error)[:500]}))
            browser.close()


if __name__ == "__main__":
    main()
