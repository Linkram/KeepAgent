// Launch an actual Electron application; this is not a browser viewport emulation.
const { _electron: electron, expect } = require('playwright/test');
const path = require('node:path');
const fs = require('node:fs');

async function main() {
  const task = JSON.parse(process.argv[2]);
  const artifacts = process.env.KEEPAGENT_ARTIFACT_DIR;
  const app = await electron.launch({ executablePath: require('electron'), args: [task.entry],
    env: { ...process.env, KEEPAGENT_HEADLESS: '1' }, timeout: 30000 });
  const errors = [];
  const checks = [];
  try {
    const page = await app.firstWindow();
    page.setDefaultTimeout(10000);
    page.on('pageerror', error => { if (errors.length < 30) errors.push(String(error).slice(0,1000)); });
    await page.waitForLoadState('domcontentloaded');
    for (const action of task.actions || []) {
      const target = page.locator(action.selector || 'body');
      if (action.action === 'click') await target.click();
      else if (action.action === 'fill') await target.fill(action.text);
      else if (action.action === 'assert_text') { await expect(target).toContainText(action.text); checks.push({passed:true, selector:action.selector}); }
      else if (action.action === 'assert_visible') { await expect(target).toBeVisible(); checks.push({passed:true, selector:action.selector}); }
      else throw new Error('Unsupported action: ' + action.action);
    }
    const result = { passed: errors.length === 0, target:'electron', title:await page.title(),
      text:(await page.locator('body').innerText()).slice(0,8000), checks, errors,
      electronVersion: await app.evaluate(({app}) => process.versions.electron) };
    const png = await app.evaluate(async ({BrowserWindow}) => {
      const image = await BrowserWindow.getAllWindows()[0].capturePage(undefined, {stayHidden:true, stayAwake:true});
      return image.toPNG().toString('base64');
    });
    if (!png) throw new Error('Desktop window returned an empty screenshot');
    fs.writeFileSync(path.join(artifacts,'page.png'), Buffer.from(png,'base64'));
    fs.writeFileSync(path.join(artifacts,'report.json'), JSON.stringify(result));
    process.stdout.write(JSON.stringify(result)+'\n');
    if (!result.passed) process.exitCode=1;
  } catch(error) {
    process.stdout.write(JSON.stringify({passed:false, target:'electron', error:String(error).slice(0,3000),checks,errors})+'\n');
    process.exitCode=1;
  } finally { await app.close(); }
}
main().catch(error => { console.error(String(error)); process.exitCode=1; });
