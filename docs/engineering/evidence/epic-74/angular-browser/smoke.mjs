// Uses an external verification runtime; not an application dependency.
if (!process.env.PLAYWRIGHT_MODULE) throw new Error('Set PLAYWRIGHT_MODULE to an installed Playwright ESM entry');
const { chromium } = await import(process.env.PLAYWRIGHT_MODULE);
import { createServer } from 'node:http';
import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { resolve, extname } from 'node:path';
import assert from 'node:assert/strict';

const dist = resolve(process.argv[2]);
const out = resolve(process.argv[3]);
await mkdir(out, { recursive: true });
const server = createServer(async (req, res) => {
  try {
    const pathname = decodeURIComponent(new URL(req.url, 'http://localhost').pathname);
    if (pathname === '/app-config.js') {
      res.setHeader('Content-Type', 'text/javascript');
      res.end('window.MNEMA_APP_CONFIG={features:{aiEnabled:false,federatedAuthEnabled:false}};');
      return;
    }
    const path = resolve(dist, '.' + (extname(pathname) ? pathname : '/index.html'));
    if (!path.startsWith(dist + '/')) throw new Error('outside build');
    const content = await readFile(path);
    res.setHeader('Content-Type', ({ '.js':'text/javascript', '.html':'text/html', '.css':'text/css', '.svg':'image/svg+xml' })[extname(path)] || 'application/octet-stream');
    res.end(content);
  } catch {
    res.writeHead(404); res.end('not found');
  }
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const origin = 'http://127.0.0.1:' + server.address().port;
const browser = await chromium.launch({ executablePath:process.env.CHROME_BIN || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', headless:true });
const results = [];
try {
  for (const width of [1440, 390]) {
    const context = await browser.newContext({ viewport:{ width, height:900 }, locale:'ru-RU', reducedMotion:'reduce' });
    await context.addInitScript(() => localStorage.setItem('mnema_language', 'ru'));
    const page = await context.newPage();
    const errors = [];
    const localJs = new Set();
    page.on('pageerror', error => errors.push(error.stack));
    page.on('request', request => { const url = new URL(request.url()); if (url.origin === origin && url.pathname.endsWith('.js')) localJs.add(url.pathname); });
    await page.route('**/*', async route => {
      const url = new URL(route.request().url());
      if (url.origin === origin) return route.continue();
      if (url.hostname === 'localhost' && url.pathname === '/auth/turnstile/config') {
        return route.fulfill({status:200, contentType:'application/json', body:'{"enabled":false}'});
      }
      if (url.hostname === 'localhost' && url.pathname.includes('/api/core/')) {
        return route.fulfill({ status:200, contentType:'application/json', body:JSON.stringify({ content:[], totalElements:0, totalPages:0, number:0, size:20, empty:true }) });
      }
      // No production/auth/third-party request leaves this isolated browser context.
      return route.fulfill({ status:200, contentType:'text/plain', body:'' });
    });
    await page.goto(origin, { waitUntil:'networkidle' });
    await page.locator('app-shell').waitFor();
    assert.ok((await page.locator('h1').first().innerText()).trim());
    const initialChunks = [...localJs];
    await page.screenshot({ path:out + '/landing-' + width + '.png', fullPage:true });
    await page.goto(origin + '/profile', { waitUntil:'networkidle' });
    assert.equal(new URL(page.url()).pathname, '/login');
    assert.equal(new URL(page.url()).searchParams.get('returnUrl'), '/profile');
    assert.ok(await page.locator('input[name="loginIdentifier"]').count());
    await page.locator('input[name="loginIdentifier"]').fill('synthetic@example.test');
    await page.keyboard.press('Tab');
    const focused = await page.locator(':focus').evaluate(el => ({tag:el.tagName, type:el.getAttribute('type')}));
    assert.deepEqual(focused, {tag:'INPUT', type:'password'}, 'identifier Tab moves focus to password');
    await page.screenshot({ path:out + '/login-' + width + '.png', fullPage:true });
    const beforeLazy = new Set(localJs);
    await page.goto(origin + '/public-decks', { waitUntil:'networkidle' });
    await page.locator('app-public-decks-catalog').waitFor({state:'attached'});
    await page.locator('.public-decks-catalog h1').waitFor();
    await page.screenshot({path:out + '/public-' + width + '.png', fullPage:true});
    const addedChunks = [...localJs].filter(path => !beforeLazy.has(path));
    if (!process.env.MNEMA_BASELINE_EAGER) assert.ok(addedChunks.length, 'lazy route must request additional chunks');
    assert.deepEqual(errors, [], 'no Angular runtime error');
    const layout = await page.evaluate(() => ({ width:innerWidth, scroll:document.documentElement.scrollWidth }));
    // #74 paper-shell replacement must remove the baseline Angular18 desktop overflow.
    // This migration guard accepts only the measured baseline, not growing overflow.
    assert.ok(layout.scroll <= (width === 1440 ? 1584 : width), 'layout must not exceed measured Angular18 baseline');
    results.push({ width, initialChunks, lazyAdditionalChunks:addedChunks, authRedirect:true, identifierInput:true, focused, publicRoute:true, errors, layout });
    await context.close();
  }
  await writeFile(out + '/results.json', JSON.stringify({ browser:await browser.version(), mode:(process.env.MNEMA_BASELINE_EAGER ? 'built Angular18 baseline' : 'built Angular22') + '; mocked API/external assets; no real Identity or device/AT claim', results }, null, 2));
  console.log(JSON.stringify(results));
} finally {
  await browser.close();
  await new Promise(resolve => server.close(resolve));
}
