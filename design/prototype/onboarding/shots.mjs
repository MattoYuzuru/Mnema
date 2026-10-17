// Screenshot matrix for the OB-001 prototype: Node 24 built-in WebSocket + CDP, no packages.
// Usage: node design/prototype/serve.mjs & node design/prototype/onboarding/shots.mjs [outDir]
// CHROME=<path to chrome or chrome-headless-shell> overrides the default Playwright cache binary.
import { spawn } from 'node:child_process';
import { mkdtemp, mkdir, writeFile, rm } from 'node:fs/promises';
import { homedir, tmpdir } from 'node:os';
import { join, resolve } from 'node:path';

const out = resolve(process.argv[2] ?? 'design/prototype/onboarding/screenshots');
const base = 'http://127.0.0.1:4173/onboarding/index.html?shot';
const chrome = process.env.CHROME ?? join(homedir(), 'Library/Caches/ms-playwright/chromium_headless_shell-1243/chrome-headless-shell-mac-arm64/chrome-headless-shell');
const WIDTHS = { 320: 720, 768: 1024, 1280: 860 };
const ALL = ['welcome', 'survey-1', 'survey-2', 'survey-3', 'survey-4', 'branch', 'skip', 'tour-sample-1', 'tour-sample-2', 'tour-sample-3', 'tour-sample-4',
  'tour-community-1', 'tour-community-2', 'tour-community-3', 'tour-community-4', 'community-stuck', 'tour-own-1', 'tour-own-2', 'tour-own-3', 'tour-own-4',
  'pause', 'finish-states', 'finished', 'ai-final', 'congrats', 'path', 'path-toast', 'hint', 'rhythm', 'reward'];
const KEY = ['welcome', 'survey-2', 'survey-4', 'branch', 'tour-sample-2', 'tour-community-3', 'pause', 'finish-states', 'ai-final', 'congrats', 'path', 'hint', 'rhythm', 'reward'];
const runs = [
  ...ALL.map(hash => ({ hash, width: 1280 })),
  ...KEY.flatMap(hash => [768, 320].map(width => ({ hash, width }))),
  ...['tour-sample-3', 'tour-community-3'].flatMap(hash => [1280, 320].map(width => ({ hash, width, armed: true }))),
  ...['welcome', 'survey-3', 'survey-4', 'tour-community-3', 'finish-states', 'path', 'rhythm', 'congrats'].flatMap(hash => [1280, 320].map(width => ({ hash, width, forced: true }))),
  ...['welcome', 'congrats', 'finish-states'].map(hash => ({ hash, width: 1280, reduced: true })),
  { hash: 'branch', width: 1280, query: '&catalog=0', suffix: 'no-catalog' },
];

const profile = await mkdtemp(join(tmpdir(), 'mnema-ob001-'));
const proc = spawn(chrome, ['--headless', '--remote-debugging-port=0', `--user-data-dir=${profile}`, '--hide-scrollbars', 'about:blank'], { stdio: ['ignore', 'ignore', 'pipe'] });
const wsUrl = await new Promise((ok, fail) => {
  let buffer = '';
  proc.stderr.on('data', chunk => { buffer += chunk; const m = buffer.match(/DevTools listening on (ws:\/\/\S+)/); if (m) ok(m[1]); });
  setTimeout(() => fail(new Error('Chrome did not start')), 15000);
});
const ws = new WebSocket(wsUrl);
await new Promise(ok => ws.addEventListener('open', ok, { once: true }));
let seq = 0; const pending = new Map();
ws.addEventListener('message', ({ data }) => { const msg = JSON.parse(data); if (msg.id && pending.has(msg.id)) { const { ok, fail } = pending.get(msg.id); pending.delete(msg.id); msg.error ? fail(new Error(msg.error.message)) : ok(msg.result); } });
const send = (method, params = {}, sessionId) => new Promise((ok, fail) => { const id = ++seq; pending.set(id, { ok, fail }); ws.send(JSON.stringify({ id, method, params, sessionId })); });
const { targetId } = await send('Target.createTarget', { url: 'about:blank' });
const { sessionId } = await send('Target.attachToTarget', { targetId, flatten: true });
const page = (method, params) => send(method, params, sessionId);
const evaluate = async expression => (await page('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true })).result.value;
const sleep = ms => new Promise(ok => setTimeout(ok, ms));

await mkdir(out, { recursive: true });
const report = [];
for (const run of runs) {
  const height = WIDTHS[run.width];
  await page('Emulation.setDeviceMetricsOverride', { width: run.width, height, deviceScaleFactor: 1, mobile: run.width < 768 });
  await page('Emulation.setEmulatedMedia', { features: [
    { name: 'prefers-reduced-motion', value: run.reduced ? 'reduce' : 'no-preference' },
    { name: 'forced-colors', value: run.forced ? 'active' : 'none' },
  ] });
  await page('Page.navigate', { url: 'about:blank' });
  await page('Page.navigate', { url: `${base}${run.query ?? ''}#${run.hash}` });
  await sleep(500);
  if (run.armed) await evaluate(`document.querySelector('dialog .hold-finish').click()`);
  await sleep(run.reduced ? 100 : 400);
  const facts = await evaluate(`({ overflow: document.documentElement.scrollWidth > innerWidth, running: document.getAnimations().filter(a => a.playState === 'running').length, focus: document.activeElement?.id || document.activeElement?.tagName })`);
  const name = `${run.hash}${run.suffix ? '-' + run.suffix : ''}${run.armed ? '-armed' : ''}${run.forced ? '-forced' : ''}${run.reduced ? '-reduced' : ''}-${run.width}.png`;
  const { data } = await page('Page.captureScreenshot', { format: 'png' });
  await writeFile(join(out, name), Buffer.from(data, 'base64'));
  report.push({ name, ...facts });
}
await writeFile(join(out, 'shots.json'), JSON.stringify(report, null, 1) + '\n');
console.log(`${report.length} screenshots → ${out}`);
const issues = report.filter(r => r.overflow || (r.name.includes('-reduced') && r.running));
console.log(issues.length ? `Issues: ${JSON.stringify(issues)}` : 'No horizontal overflow; no running animations under reduced motion.');
ws.close(); proc.kill(); await rm(profile, { recursive: true, force: true });
