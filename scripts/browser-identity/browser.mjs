// Node 24 built-in WebSocket/CDP only. All credentials remain in this process's memory.
import { readFile, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { createHash } from 'node:crypto';
import { runHub } from './hub.mjs';
import { runMechanics } from './mechanics.mjs';
import { runNotifications } from './notifications.mjs';
import { runCodeBlock } from './code-block.mjs';
import { runUsage } from './usage.mjs';
import { runWorkshop } from './workshop.mjs';
import { runAssessment } from './assessment.mjs';

const config = JSON.parse(await readFile(process.argv[2], 'utf8'));
const defaultCdpTimeout = Number.isInteger(config.cdpTimeoutMs) && config.cdpTimeoutMs >= 1000 && config.cdpTimeoutMs <= 120_000 ? config.cdpTimeoutMs : 10_000;
const results = [];
class SafeFailure extends Error {}
const require = (value, label) => { if (!value) throw new SafeFailure(label); };
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
/**
 * A plain CDP command fails after `config.cdpTimeoutMs` (10 s unless `MNEMA_HARNESS_CDP_TIMEOUT_MS` in the runner's environment says
 * otherwise): it is answered by the browser at once, so a silence is a finding. A call that waits for the page itself (navigation,
 * a screenshot, evaluating a script or a promise in it) may wait `SLOW_CDP_TIMEOUT_MS`: on a loaded machine (several browser harnesses
 * can share one) a page is held for ten seconds and more without being broken.
 */
const SLOW_CDP_TIMEOUT_MS = 30_000;
const SLOW_CDP_METHODS = new Set(['Page.navigate', 'Page.reload', 'Page.captureScreenshot', 'Runtime.evaluate', 'Runtime.callFunctionOn', 'Runtime.releaseObject']);

class CDP {
  constructor(socket) {
    this.socket = socket; this.next = 0; this.pending = new Map(); this.listeners = new Map();
    socket.addEventListener('message', event => {
      const message = JSON.parse(event.data);
      if (message.id) {
        const request = this.pending.get(message.id);
        if (!request) return;
        this.pending.delete(message.id); clearTimeout(request.timeout);
        // Method and CDP error text only (no params): enough to classify a failure without leaking request data.
        if (message.error) request.reject(new Error(`CDP ${request.method} failed: ${String(message.error.message).slice(0, 120)}`));
        else request.resolve(message.result);
      } else for (const listener of this.listeners.get(message.method) || []) listener(message.params);
    });
  }
  call(method, params = {}) {
    return new Promise((resolve, reject) => {
      const id = ++this.next;
      const timeout = setTimeout(() => { this.pending.delete(id); reject(new Error(`CDP timeout: ${method}`)); }, SLOW_CDP_METHODS.has(method) ? Math.max(SLOW_CDP_TIMEOUT_MS, defaultCdpTimeout) : defaultCdpTimeout);
      this.pending.set(id, { resolve, reject, timeout, method });
      this.socket.send(JSON.stringify({ id, method, params }));
    });
  }
  on(method, listener) {
    if (!this.listeners.has(method)) this.listeners.set(method, []);
    this.listeners.get(method).push(params => {
      try { listener(params); } catch { this.eventFailed = true; }
    });
  }
  async evaluate(expression) {
    const result = await this.call('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true });
    require(!result.exceptionDetails, 'browser evaluation failed');
    return result.result.value;
  }
  async callFunction(functionDeclaration, values = []) {
    const global = await this.call('Runtime.evaluate', { expression: 'globalThis' });
    const objectId = global.result?.objectId;
    require(objectId, 'browser execution context unavailable');
    try {
      const result = await this.call('Runtime.callFunctionOn', {
        functionDeclaration,
        objectId,
        arguments: values.map(value => ({ value })),
        awaitPromise: true,
        returnByValue: true,
      });
      require(!result.exceptionDetails, 'browser function failed');
      return result.result.value;
    } finally {
      await this.call('Runtime.releaseObject', { objectId }).catch(() => {});
    }
  }
  close() {
    for (const pending of this.pending.values()) { clearTimeout(pending.timeout); pending.reject(new Error('CDP closed')); }
    this.pending.clear(); this.socket.close();
  }
}

const record = (scenario, details = {}) => results.push({ scenario, ...details });
let cdp;
const tabs = [];
let step = 'startup';
let mechanicsFailures = [];
let mechanicsFindings = [];
let diagnostics = () => ({});
async function openTab() {
  const response = await fetch(`http://127.0.0.1:${config.debugPort}/json/new?about:blank`,
    { method: 'PUT', signal: AbortSignal.timeout(10000) });
  require(response.ok, 'Chrome target unavailable');
  const target = await response.json();
  const socket = new WebSocket(target.webSocketDebuggerUrl);
  await new Promise((resolve, reject) => {
    socket.addEventListener('open', resolve, { once: true });
    socket.addEventListener('error', () => reject(new Error('CDP connection failed')), { once: true });
  });
  const tab = new CDP(socket);
  tabs.push(tab);
  return tab;
}
try {
  require(process.versions.node.split('.')[0] === '24', 'Node 24 is required');
  cdp = await openTab();
  const allowed = new Set([config.frontend, config.identity, ...(config.media ? [config.mediaOrigin] : [])]);
  let externalRequests = 0, tokenExchanges = 0, registrationStatus = 0, loginStatus = 0, logoutStatus = 0;
  let browserErrors = 0, callback = null, networkRequests = 0, identityRequests = 0, profileReads = 0;
  let primaryDeckListRequests = 0;
  const authoringResponses = [];
  /** Requests the page has sent and not seen finish (method, path without query, age): what a stalled page is waiting for. */
  const inflight = new Map();
  /** Requests paused for interception and not yet continued by this driver. */
  const paused = new Map();
  const inflightNow = () => [...paused.values()].map(entry => `PAUSED ${entry.what} ${Math.round((Date.now() - entry.at) / 100) / 10}s`)
    .concat([...inflight.values()].filter(entry => Date.now() - entry.at > 2000).map(entry => `${entry.what} ${Math.round((Date.now() - entry.at) / 100) / 10}s`));
  const mediaResponses = [], mediaFailures = [], mediaApiResponses = [];
  const mediaRequestIds = new Set();
  let browseMediaState = null;
  let tamperNextCallback = false;
  const bearerTokens = [], idTokens = [], exchanges = [];
  const challenges = new Set();
  const loadedDocuments = new Set();
  const asyncWork = new Set();
  let asynchronousFailure = false, firstAsynchronousFailure = null;
  let actorAfterLogout = null, otherAfterLogout = null;
  diagnostics = () => ({ networkRequests, identityRequests, tokenExchanges, browserErrors, externalRequests,
    asynchronousFailure, firstAsynchronousFailure, cancelledInterceptions, eventFailed: tabs.some(tab => tab.eventFailed), actorAfterLogout, otherAfterLogout,
    authoringResponses, mediaResponses, mediaFailures, mediaApiResponses, browseMediaState });
  let cancelledInterceptions = 0;
  /** A paused request that the browser cancelled during navigation can no longer be continued; nothing else is benign. */
  const interception = promise => promise.catch(error => {
    if (/^CDP Fetch\.(continueRequest|failRequest|fulfillRequest) failed: Invalid InterceptionId/.test(error?.message ?? '')) {
      cancelledInterceptions++;
      return;
    }
    throw error;
  });
  const run = promise => {
    asyncWork.add(promise);
    promise.catch(error => { asynchronousFailure = true; firstAsynchronousFailure ??= error?.message ?? 'unknown'; })
      .finally(() => asyncWork.delete(promise));
  };
  function watchTab(tab) {
    const tokenRequests = new Set();
    tab.on('Runtime.exceptionThrown', () => { browserErrors++; });
    // A page that opens a JavaScript dialog blocks every later CDP call; that is a product finding, named as such.
    tab.on('Page.javascriptDialogOpening', event => {
      asynchronousFailure = true; firstAsynchronousFailure ??= 'unexpected JavaScript dialog: ' + String(event.type).slice(0, 20);
      run(tab.call('Page.handleJavaScriptDialog', { accept: false }));
    });
    tab.on('Page.lifecycleEvent', event => {
      if (event.name === 'DOMContentLoaded') loadedDocuments.add(event.loaderId);
    });
    tab.on('Fetch.requestPaused', event => {
      const url = new URL(event.request.url);
      paused.set(event.requestId, { what: `${event.request.method} ${url.pathname}`.slice(0, 90), at: Date.now() });
      const release = promise => promise.finally(() => paused.delete(event.requestId));
      networkRequests++;
      if (url.origin === config.identity) identityRequests++;
      // Authoring exercises several full navigations and their local assets; keep a finite request budget.
      // The Workshop scenarios (`--generation`) poll the real events endpoint and load the app several times; the exercise
      // generation scenario (#291) adds a batch review, an editor round trip and a Study session on top of them (each full page load
      // also asks Identity, so its budget grows with `--generation` too).
      if (networkRequests > (config.mechanics ? 3000 : config.media ? 1250 : config.authoring ? 1000 : 500) + (config.generation ? 3500 : 0) + (config.assessment ? 1000 : 0)
          || identityRequests > 150 + (config.generation ? 100 : 0) + (config.assessment ? 50 : 0)) asynchronousFailure = true;
      if (!allowed.has(url.origin) || asynchronousFailure) {
        externalRequests++;
        run(interception(release(tab.call('Fetch.failRequest', { requestId: event.requestId, errorReason: 'BlockedByClient' }))));
      } else if (tamperNextCallback && url.origin === config.frontend && url.pathname === '/auth/callback'
          && url.searchParams.has('code')) {
        tamperNextCallback = false;
        url.searchParams.set('state', 'fixture-wrong-live-state');
        // A real navigation redirect changes location.search; an invisible request URL rewrite would not.
        run(interception(release(tab.call('Fetch.fulfillRequest', { requestId: event.requestId, responseCode: 302,
          responseHeaders: [{ name: 'Location', value: url.href }, { name: 'Cache-Control', value: 'no-store' }], body: '' }))));
      } else run(interception(release(tab.call('Fetch.continueRequest', { requestId: event.requestId }))));
    });
    tab.on('Network.requestWillBeSent', event => {
      const url = new URL(event.request.url);
      inflight.set(event.requestId, { what: `${event.request.method} ${url.pathname}`.slice(0, 90), at: Date.now() });
      if (tab === cdp && url.origin === config.frontend && url.pathname === '/api/decks'
          && event.request.method === 'GET') primaryDeckListRequests++;
      if (config.media && url.origin === config.mediaOrigin) mediaRequestIds.add(event.requestId);
      if (url.origin === config.identity && url.pathname === '/oauth2/authorize') {
        require(url.searchParams.get('redirect_uri') === config.frontend + '/auth/callback', 'exact callback mismatch');
        require(url.searchParams.get('code_challenge_method') === 'S256', 'PKCE S256 absent');
        challenges.add(url.searchParams.get('code_challenge'));
      }
      if (url.origin === config.frontend && url.pathname === '/auth/callback' && url.searchParams.has('code')
          && url.searchParams.get('code') !== 'fixture-invalid') callback = event.request.url;
      if (url.origin === config.identity && url.pathname === '/oauth2/token' && event.request.method === 'POST') {
        const body = new URLSearchParams(event.request.postData || '');
        exchanges.push(body);
        tokenRequests.add(event.requestId);
      }
    });
    tab.on('Network.responseReceived', event => {
      const url = new URL(event.response.url);
      if (config.media && url.origin === config.mediaOrigin) {
        mediaResponses.push({ status: event.response.status });
      }
      if (config.media && url.origin === config.frontend && url.pathname.startsWith('/api/media-assets')) {
        const action = url.pathname.endsWith('/upload-policy') ? 'policy'
          : url.pathname.endsWith('/upload-intents') ? 'intent'
            : url.pathname.endsWith('/upload/finalize') ? 'finalize'
              : url.pathname.endsWith('/upload') ? 'status' : 'playback';
        mediaApiResponses.push({ action, status: event.response.status });
      }
      if (config.authoring && url.origin === config.frontend && url.pathname.startsWith('/api/')) {
        const resource = url.pathname.startsWith('/api/capture-notes') ? 'capture'
          : url.pathname.startsWith('/api/editing-drafts') ? 'draft'
            : url.pathname.startsWith('/api/decks') ? 'deck' : 'other';
        authoringResponses.push({ resource, status: event.response.status });
      }
      if (url.origin !== config.identity) return;
      if (url.pathname === '/api/accounts/register') registrationStatus = event.response.status;
      if (url.pathname === '/api/accounts/login') loginStatus = event.response.status;
      if (url.pathname === '/api/accounts/logout') logoutStatus = event.response.status;
      if (url.pathname === '/api/accounts/me' && event.response.status === 200) profileReads++;
    });
    tab.on('Network.loadingFailed', event => {
      inflight.delete(event.requestId);
      if (mediaRequestIds.has(event.requestId)) mediaFailures.push(event.errorText);
    });
    tab.on('Network.loadingFinished', event => {
      inflight.delete(event.requestId);
      if (!tokenRequests.delete(event.requestId)) return;
      run(tab.call('Network.getResponseBody', { requestId: event.requestId }).then(result => {
        const body = JSON.parse(result.base64Encoded ? Buffer.from(result.body, 'base64').toString() : result.body);
        require(typeof body.access_token === 'string' && !body.refresh_token, 'invalid public-client grant');
        if (typeof body.id_token === 'string') idTokens.push(body.id_token);
        bearerTokens.push(body.access_token); tokenExchanges++;
      }));
    });
  }
  async function initialize(tab) {
    watchTab(tab);
    await tab.call('Page.enable');
    await tab.call('Page.setLifecycleEventsEnabled', { enabled: true });
    await tab.call('Runtime.enable');
    await tab.call('Network.enable', { maxTotalBufferSize: 2_097_152, maxResourceBufferSize: 1_048_576 });
    await tab.call('Network.setBypassServiceWorker', { bypass: true });
    await tab.call('Fetch.enable', { patterns: [{ urlPattern: '*', requestStage: 'Request' }] });
    await tab.call('Emulation.setDeviceMetricsOverride', { width: 1280, height: 900, deviceScaleFactor: 1, mobile: false });
  }
  await initialize(cdp);
  async function until(predicate, label, timeoutMs = 18000) {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      require(!asynchronousFailure && !tabs.some(tab => tab.eventFailed), 'CDP event processing failed');
      try { if (await predicate()) return; } catch { /* A navigation can replace the execution context. */ }
      await sleep(100);
    }
    throw new SafeFailure(label);
  }
  const exists = (selector, tab = cdp) => tab.callFunction(
    'function(selector) { return Boolean(document.querySelector(selector)); }', [selector]);
  const authenticated = (tab = cdp) => exists(config.readySelector, tab);
  async function navigateUrl(url, tab = cdp) {
    const navigation = await tab.call('Page.navigate', { url });
    require(!navigation.errorText && navigation.loaderId, 'new document navigation failed');
    // Page.navigate acknowledges acceptance, not DOM replacement. Never match an old page's alert.
    await until(() => Promise.resolve(loadedDocuments.has(navigation.loaderId)), 'new document did not load');
  }
  async function navigate(path, tab = cdp) {
    await navigateUrl(config.frontend + path, tab);
  }
  async function fill(selector, value, tab = cdp) {
    await until(() => exists(selector, tab), 'form field absent');
    require(await tab.callFunction(`function(selector, value) {
      const element = document.querySelector(selector);
      if (!(element instanceof HTMLInputElement) && !(element instanceof HTMLTextAreaElement)) return false;
      element.focus();
      const prototype = element instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
      Object.getOwnPropertyDescriptor(prototype, 'value').set.call(element, value);
      element.dispatchEvent(new Event('input', { bubbles: true }));
      element.dispatchEvent(new Event('change', { bubbles: true }));
      return true;
    }`, [selector, value]), 'form field is not an input');
  }
  const click = (selector, tab = cdp) => tab.callFunction(`function(selector) {
    const element = document.querySelector(selector);
    if (!(element instanceof HTMLElement)) return false;
    element.click();
    return true;
  }`, [selector]);
  const clickText = (selector, label, tab = cdp) => tab.callFunction(`function(selector, label) {
    const element = [...document.querySelectorAll(selector)]
      .find(candidate => candidate.textContent.trim() === label);
    if (!(element instanceof HTMLElement) || element.matches(':disabled')) return false;
    element.click();
    return true;
  }`, [selector, label]);
  const bodyIncludes = (text, tab = cdp) => tab.callFunction(
    'function(text) { return document.body.innerText.includes(text); }', [text]);
  /** The Deck page is a hub: its metadata form lives behind «Изменить». Opens it with a real activation and waits for the title field. */
  async function openDeckEditor(tab = cdp) {
    const control = `function() {
      return [...document.querySelectorAll('nav.hub-actions button')].find(button => button.textContent.trim() === 'Изменить');
    }`;
    await until(() => tab.callFunction(`function() { return Boolean((${control})()); }`), 'deck hub did not load «Изменить»');
    if (!(await exists('#detail-title', tab))) {
      require(await tab.callFunction(`function() { const button = (${control})(); button.click(); return true; }`),
        '«Изменить» control absent from the deck hub');
    }
    await until(() => exists('#detail-title', tab), 'deck metadata form did not open behind «Изменить»');
  }
  async function submit(tab = cdp) { await tab.evaluate("document.querySelector('form button[type=submit]').click()"); }
  async function pressKey(key, code, virtualKeyCode, modifiers = 0, tab = cdp) {
    const event = { key, code, windowsVirtualKeyCode: virtualKeyCode, nativeVirtualKeyCode: virtualKeyCode, modifiers };
    await tab.call('Input.dispatchKeyEvent', { type: 'rawKeyDown', ...event });
    await tab.call('Input.dispatchKeyEvent', { type: 'keyUp', ...event });
  }
  async function saveScreenshot(name, tab = cdp) {
    const capture = await tab.call('Page.captureScreenshot', { format: 'png', fromSurface: true });
    await writeFile(join(config.output, name), Buffer.from(capture.data, 'base64'));
  }
  async function saveFullScreenshot(name, tab = cdp) {
    const layout = await tab.call('Page.getLayoutMetrics');
    const { width, height } = layout.cssContentSize;
    const capture = await tab.call('Page.captureScreenshot', { format: 'png', fromSurface: true,
      captureBeyondViewport: true, clip: { x: 0, y: 0, width, height, scale: 1 } });
    await writeFile(join(config.output, name), Buffer.from(capture.data, 'base64'));
  }
  const sanitizedLocation = (tab = cdp) => tab.evaluate('location.pathname');
  step = 'wrong_callback';
  await navigate('/auth/callback?code=fixture-invalid&state=fixture-invalid');
  await until(() => exists(config.errorSelector), 'wrong callback not rejected');
  require(!(await authenticated()) && exchanges.length === 0, 'wrong callback attempted exchange');
  record('wrong_callback_rejected');
  step = 'register';
  await navigate('/register');
  await fill('#email', config.email);
  await fill('#username', config.login); await fill('#password', config.password);
  if (await exists('#login-name')) await fill('#login-name', config.login);
  await submit();
  await until(async () => registrationStatus === 201 && tokenExchanges > 0 && (await sanitizedLocation()) === '/decks',
    'registration automatic login PKCE did not succeed');
  await navigate('/login');
  await until(authenticated, 'registered profile absent');
  record('real_browser_registration_auto_login_pkce', { status: 201 });
  await until(() => exists(config.logoutSelector), 'logout control absent');
  require(await click(config.logoutSelector), 'logout control is not clickable');
  await until(async () => logoutStatus === 204 && !(await authenticated()), 'registration session logout failed');
  step = 'login';
  const beforeLogin = tokenExchanges;
  loginStatus = 0; logoutStatus = 0;
  await navigate('/login');
  await fill('#login-name', config.login); await fill('#password', config.password); await submit();
  await until(async () => tokenExchanges > beforeLogin && (await sanitizedLocation()) === '/decks',
    'login PKCE callback did not authenticate');
  await navigate('/login');
  await until(authenticated, 'login profile absent');
  require(loginStatus === 200 && callback !== null, 'login protocol evidence absent');
  const cookies = (await cdp.call('Network.getCookies', { urls: [config.identity] })).cookies;
  require(cookies.some(cookie => cookie.secure && cookie.httpOnly && cookie.sameSite === 'Lax'), 'session cookie flags absent');
  require(await cdp.evaluate('isSecureContext && location.protocol === "https:"'), 'not a secure browser context');
  require(!(await sanitizedLocation()).startsWith('/auth/callback'), 'callback not cleaned');
  const firstBearer = bearerTokens.at(-1), originalCallback = callback;
  record('login_pkce_callback', { loginStatus, secureContext: true, secureHttpOnlyLaxCookie: true });
  step = 'native_profile_edit';
  await navigate('/profile');
  await until(() => exists('#profile-bio'), 'native account profile did not load');
  await fill('#display-name', 'Mnema browser fixture');
  await fill('#profile-bio', 'Профиль нового Identity API');
  require(await click('form button[type=submit]'), 'profile save action absent');
  await until(() => exists('.success'), 'bearer profile update did not complete');
  await cdp.call('Page.reload', { ignoreCache: true });
  await until(async () => await cdp.callFunction(`function() {
    return document.querySelector('#profile-bio')?.value === 'Профиль нового Identity API';
  }`), 'native profile edit did not persist across reload');
  record('native_profile_edit', { persisted: true });
  if (config.media) {
    step = 'native_profile_avatar';
    require(await cdp.callFunction(`async function() {
    const input = document.querySelector('#avatar-file');
    if (!(input instanceof HTMLInputElement)) return false;
    const canvas = document.createElement('canvas');
    canvas.width = 8; canvas.height = 8;
    const context = canvas.getContext('2d');
    if (!context) return false;
    context.fillStyle = '#433489';
    context.fillRect(0, 0, 8, 8);
    const blob = await new Promise(resolve => canvas.toBlob(resolve, 'image/png'));
    if (!blob) return false;
    const files = new DataTransfer();
    files.items.add(new File([blob], 'fixture-avatar.png', { type: 'image/png' }));
    input.files = files.files;
    input.dispatchEvent(new Event('change', { bubbles: true }));
    return true;
    }`), 'browser could not select a PNG avatar');
    await until(() => cdp.callFunction(`function() {
    const image = document.querySelector('.avatar-preview img');
    return image instanceof HTMLImageElement && image.complete && image.naturalWidth === 8;
    }`), 'native avatar upload did not render');
    await cdp.call('Page.reload', { ignoreCache: true });
    await until(() => cdp.callFunction(`function() {
    const image = document.querySelector('.avatar-preview img');
    return image instanceof HTMLImageElement && image.complete && image.naturalWidth === 8;
    }`), 'native avatar did not survive reload');
    record('native_profile_avatar', { persisted: true });
  }
  await saveScreenshot('native-profile-desktop.png');
  for (const width of [390, 320]) {
    await cdp.call('Emulation.setDeviceMetricsOverride', {
      width, height: 900, deviceScaleFactor: width === 320 ? 2 : 1, mobile: false });
    require(await cdp.callFunction(`function() {
      return document.documentElement.scrollWidth <= window.innerWidth;
    }`), 'profile has horizontal overflow on a narrow viewport');
    await saveScreenshot(`native-profile-${width}.png`);
  }
  await cdp.call('Emulation.setDeviceMetricsOverride', {
    width: 1280, height: 900, deviceScaleFactor: 1, mobile: false });
  record('native_profile_responsive', { widths: [390, 320] });
  step = 'own_deck_authoring';
  const originalTitle = 'Русский материал — 漢字';
  const savedTitle = 'Русский материал — версия 2';
  await navigate('/decks');
  await until(() => exists('#own-decks-title'), 'own deck library did not load');
  require(await cdp.callFunction(`function() {
    return document.body.innerText.includes('Первая страница пока чиста');
  }`), 'fresh account did not show the own-deck empty state');
  await navigate('/decks/new');
  await fill('#create-title', originalTitle);
  await fill('#create-description', 'Длинная строка на русском\nשורה בעברית');
  await submit();
  await until(async () => /^\/decks\/[0-9a-f-]{36}$/.test(await sanitizedLocation())
    && await exists('#deck-detail-title'), 'real deck create did not open its canonical hub route');
  const deckPath = await sanitizedLocation();
  require(await cdp.callFunction(`function() { return !document.querySelector('#detail-title'); }`),
    'the metadata form must stay closed until «Изменить» is used');
  await openDeckEditor();
  require(await cdp.callFunction(`function(expected) {
    const input = document.querySelector('#detail-title');
    return input instanceof HTMLTextAreaElement && input.value === expected
      && document.querySelector('h1')?.textContent?.trim() === expected;
  }`, [originalTitle]), 'created deck did not preserve exact title');
  record('real_own_deck_create_and_detail', { canonicalRoute: true, exactUnicode: true });
  await cdp.call('Page.reload', { ignoreCache: true });
  await openDeckEditor();
  require(await cdp.callFunction(`function(expected) {
    const input = document.querySelector('#detail-title');
    return input instanceof HTMLTextAreaElement && input.value === expected;
  }`, [originalTitle]), 'reloaded deck lost persisted metadata');
  await fill('#detail-title', savedTitle);
  await submit();
  await until(() => cdp.callFunction(`function(expected) {
    return document.querySelector('h1')?.textContent?.trim() === expected;
  }`, [savedTitle]), 'real deck metadata save was not acknowledged');
  record('real_own_deck_save_reload', { persistedAfterReload: true });

  step = 'own_deck_conflict';
  const conflictTab = await openTab();
  await initialize(conflictTab);
  const beforeConflictLogin = tokenExchanges;
  await navigate('/login', conflictTab);
  await fill('#login-name', config.login, conflictTab);
  await fill('#password', config.password, conflictTab);
  await submit(conflictTab);
  await until(async () => tokenExchanges > beforeConflictLogin
    && (await sanitizedLocation(conflictTab)) === '/decks', 'same-account conflict tab did not authenticate');
  await navigate(deckPath, conflictTab);
  await openDeckEditor(conflictTab);
  await navigate(deckPath);
  await openDeckEditor();
  await fill('#detail-title', 'Изменение из первой вкладки');
  await submit();
  await until(() => cdp.callFunction(`function() {
    return document.querySelector('h1')?.textContent?.trim() === 'Изменение из первой вкладки';
  }`), 'first concurrent save was not acknowledged');
  await fill('#detail-title', 'Изменение из второй вкладки', conflictTab);
  await submit(conflictTab);
  await until(() => exists('#conflict-title', conflictTab), 'stale concurrent save did not expose a 412 choice');
  require(await conflictTab.callFunction(`function() {
    const input = document.querySelector('#detail-title');
    return input instanceof HTMLTextAreaElement && input.readOnly
      && input.value === 'Изменение из второй вкладки';
  }`), 'conflict did not preserve and lock the exact local draft');
  require(await click('.notice.conflict .button.primary', conflictTab), 'conflict reapply action absent');
  await until(() => conflictTab.callFunction(`function() {
    return document.querySelector('h1')?.textContent?.trim() === 'Изменение из второй вкладки';
  }`), 'explicit conflict reapply did not publish a fresh command');
  await navigate(deckPath);
  await openDeckEditor();
  await until(() => cdp.callFunction(`function() {
    const input = document.querySelector('#detail-title');
    return input instanceof HTMLTextAreaElement && input.value === 'Изменение из второй вкладки';
  }`), 'conflict resolution did not persist the chosen local draft');
  record('real_own_deck_412_conflict_resolution', { exactLocalDraftPreserved: true, explicitReapply: true });

  step = 'own_deck_cross_tab_refresh';
  await navigate('/decks');
  await until(() => exists('#own-decks-title'), 'primary library absent before cross-tab update');
  const remoteDeckTitle = 'Новая колода из второй вкладки';
  await navigate('/decks/new', conflictTab);
  await fill('#create-title', remoteDeckTitle, conflictTab);
  await submit(conflictTab);
  await until(async () => /^\/decks\/[0-9a-f-]{36}$/.test(await sanitizedLocation(conflictTab)),
    'second tab did not create a deck');
  const visibilityBeforeFocus = await cdp.evaluate('document.visibilityState');
  require(visibilityBeforeFocus === 'hidden', 'primary tab did not become hidden');
  const hiddenDeckRequests = primaryDeckListRequests;
  if (!config.media) {
    await sleep(47_000);
    require(primaryDeckListRequests === hiddenDeckRequests, 'hidden library performed periodic deck reads');
  }
  const requestsBeforeFocus = networkRequests;
  await cdp.call('Page.bringToFront');
  await until(() => bodyIncludes(remoteDeckTitle), 'primary library did not refresh after focus');
  require((await sanitizedLocation()) === '/decks', 'cross-tab refresh navigated the primary tab');
  record('real_own_deck_cross_tab_refresh', {
    visibilityBeforeFocus, requestsAfterFocus: networkRequests - requestsBeforeFocus,
    hiddenSecondsWithoutDeckReads: config.media ? 0 : 47,
    primaryRoutePreserved: true, manualReload: false
  });

  if (!config.media) {
    step = 'own_deck_visible_polling';
    const timerDeckTitle = 'Новая колода по таймеру';
    const createByOtherTab = await conflictTab.callFunction(`async function(base, token, title) {
      const response = await fetch(base + '/api/decks', {
        method: 'POST', credentials: 'omit',
        headers: { Authorization: 'Bearer ' + token, 'Content-Type': 'application/json' },
        body: JSON.stringify({ commandId: crypto.randomUUID(), metadata: { title, description: '' } })
      });
      return response.status;
    }`, [config.frontend, firstBearer, timerDeckTitle]);
    require(createByOtherTab === 201, 'second tab did not publish the timer deck');
    const visibleDeckRequests = primaryDeckListRequests;
    const pollStarted = Date.now();
    await until(() => bodyIncludes(timerDeckTitle), 'visible library did not update on its bounded timer', 53_000);
    require(primaryDeckListRequests > visibleDeckRequests, 'visible update had no list request');
    record('real_own_deck_visible_polling', {
      elapsedMs: Date.now() - pollStarted, listRequests: primaryDeckListRequests - visibleDeckRequests,
      manualReload: false
    });
  }
  await navigate(deckPath);
  await openDeckEditor();

  step = 'own_deck_responsive_evidence';
  require(await cdp.callFunction(`function() {
    const input = document.querySelector('#detail-title');
    if (!(input instanceof HTMLTextAreaElement)) return false;
    input.focus();
    return document.activeElement === input;
  }`), 'title field did not accept keyboard focus');
  await pressKey('Tab', 'Tab', 9);
  require(await cdp.callFunction(`function() {
    return document.activeElement?.id === 'detail-description';
  }`), 'metadata fields were not in predictable keyboard order');
  require(await cdp.callFunction(`function() {
    document.body.tabIndex = -1;
    document.body.focus();
    return document.activeElement === document.body;
  }`), 'could not establish the keyboard traversal origin');
  await pressKey('Tab', 'Tab', 9);
  require(await cdp.callFunction(`function() {
    const active = document.activeElement;
    return active instanceof HTMLAnchorElement && active.classList.contains('skip-link')
      && active.getBoundingClientRect().top >= 0;
  }`), 'first keyboard stop was not the visible skip link');
  await pressKey('Enter', 'Enter', 13);
  await until(() => cdp.callFunction(`function() {
    return document.activeElement?.id === 'main-content';
  }`), 'skip link did not move keyboard focus to main content');
  require((await sanitizedLocation()) === deckPath && await exists('#detail-title'),
    'skip link changed the current route or removed its content');
  await cdp.callFunction(`function() { document.body.removeAttribute('tabindex'); }`);
  await cdp.call('Emulation.setEmulatedMedia',
    { features: [{ name: 'prefers-reduced-motion', value: 'reduce' }] });
  require(await cdp.callFunction(`function() {
    const input = document.querySelector('#detail-title');
    return matchMedia('(prefers-reduced-motion: reduce)').matches
      && input instanceof HTMLElement && parseFloat(getComputedStyle(input).transitionDuration) <= 0.001;
  }`), 'reduced-motion preference did not suppress authored transitions');
  await cdp.call('Emulation.setDeviceMetricsOverride',
    { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false });
  await saveScreenshot('own-deck-1440.png');
  await cdp.call('Emulation.setDeviceMetricsOverride',
    { width: 390, height: 844, deviceScaleFactor: 1, mobile: true });
  require(await cdp.callFunction(`function() {
    const action = document.querySelector('.button.primary');
    return document.documentElement.scrollWidth <= document.documentElement.clientWidth
      && action instanceof HTMLElement && action.getBoundingClientRect().height >= 44;
  }`), '390px layout overflowed or exposed an undersized primary action');
  await saveScreenshot('own-deck-390.png');
  await cdp.call('Emulation.setDeviceMetricsOverride',
    { width: 320, height: 900, deviceScaleFactor: 2, mobile: false });
  require(await cdp.callFunction(`function() {
    return window.innerWidth === 320 && window.devicePixelRatio === 2
      && document.documentElement.clientWidth === 320
      && document.documentElement.scrollWidth <= 320;
  }`), '200% rasterized 320px layout did not reflow without horizontal overflow');
  await saveScreenshot('own-deck-320-at-200-percent.png');
  await cdp.call('Emulation.setDeviceMetricsOverride',
    { width: 1280, height: 900, deviceScaleFactor: 1, mobile: false });
  await cdp.call('Emulation.setEmulatedMedia', { features: [] });
  record('own_deck_responsive_and_zoom', { widths: [1440, 390], cssWidth: 320, deviceScaleFactor: 2,
    noHorizontalOverflow: true, primaryActionMinimumPx: 44, keyboardOrder: true, reducedMotion: true });

  await navigate('/login');
  await until(authenticated, 'profile absent after own-deck authoring');
  step = 'reload';
  const beforeReload = tokenExchanges, beforeProfile = profileReads;
  await cdp.call('Page.reload', { ignoreCache: true });
  await until(async () => (await authenticated()) && profileReads > beforeProfile, 'reload did not revalidate profile');
  require(tokenExchanges === beforeReload, 'reload unexpectedly reauthorized');
  require(await cdp.callFunction(`function(secrets) {
    return !Object.values(localStorage).some(value => secrets.some(secret => value.includes(secret)));
  }`, [bearerTokens]), 'bearer persisted in localStorage');
  require(await cdp.callFunction(`function(secrets) {
    return ![...Object.values(localStorage), ...Object.values(sessionStorage)]
      .some(value => secrets.some(secret => value.includes(secret)));
  }`, [[...idTokens, config.password]]), 'ID token or password persisted');
  record('authenticated_reload_profile_revalidated', { noLocalStorageBearer: true });
  step = 'two_account_session_divergence';
  // Tabs share Identity cookies but not sessionStorage: the current cookie becomes account B,
  // while account A's tab still holds its own independently validated bearer/profile.
  const second = await openTab();
  await initialize(second);
  await second.call('Page.bringToFront');
  const beforeSecond = tokenExchanges;
  await navigate('/register', second);
  await fill('#email', 'second_' + config.email, second);
  await fill('#username', config.login + '_second', second);
  await fill('#password', config.password, second);
  if (await exists('#login-name', second)) await fill('#login-name', config.login + '_second', second);
  await submit(second);
  await until(async () => tokenExchanges > beforeSecond && (await sanitizedLocation(second)) === '/decks',
    'second account login did not complete');
  const secondBearer = bearerTokens.at(-1);
  require(secondBearer !== firstBearer, 'second account did not receive distinct access');
  await navigate('/login', second);
  await until(() => authenticated(second), 'second account profile absent');
  if (config.authoring) {
    step = 'authoring_create_deck';
    const authoringStarted = Date.now();
    const requestsBeforeAuthoring = networkRequests;
    const deckTitle = 'Северный архив';
    let uploadedAudioAssetId = null;
    let freeResponseEditPath = null;
    const capturedText = 'سلام · 日本語 · <img src=x onerror="globalThis.__mnemaXss=true">';
    const editedText = 'Долговечный материал — עברית, русский и 日本語';
    await navigate('/decks/new', second);
    await fill('#create-title', deckTitle, second);
    await fill('#create-description', 'HTTPS browser authoring fixture', second);
    await submit(second);
    await until(async () => /^\/decks\/[0-9a-f-]{36}$/.test(await sanitizedLocation(second)),
      'deck creation did not reach detail');
    const deckPath = await sanitizedLocation(second);

    step = 'authoring_capture';
    await navigate(deckPath + '/capture', second);
    await fill('#capture-text', capturedText, second);
    await submit(second);
    await until(() => bodyIncludes(capturedText, second), 'captured note absent');
    require(await second.callFunction(`function() {
      return !globalThis.__mnemaXss && !document.querySelector('img[src="x"]');
    }`), 'captured text executed as markup');
    require(await clickText('button', 'Превратить в материал', second), 'capture conversion unavailable');
    await until(async () => /^\/decks\/[0-9a-f-]{36}\/materials\/[0-9a-f-]{36}\/edit$/.test(
      await sanitizedLocation(second)), 'conversion did not open editor');

    step = 'authoring_acknowledged_draft';
    await until(() => exists('.ProseMirror[contenteditable="true"]', second), 'native editor absent');
    require(await second.callFunction(`function() {
      const editor = document.querySelector('.ProseMirror[contenteditable="true"]');
      if (!(editor instanceof HTMLElement)) return false;
      editor.focus();
      const selection = getSelection();
      const range = document.createRange();
      range.selectNodeContents(editor);
      selection.removeAllRanges();
      selection.addRange(range);
      return true;
    }`), 'native editor could not be selected');
    await second.call('Input.insertText', { text: editedText });
    await until(() => bodyIncludes('Все изменения сохранены', second), 'draft acknowledgement absent');
    const editorPath = await sanitizedLocation(second);
    await second.call('Page.reload', { ignoreCache: true });
    await until(async () => (await sanitizedLocation(second)) === editorPath
      && (await bodyIncludes(editedText, second))
      && (await bodyIncludes('Все изменения сохранены', second)),
    'acknowledged draft did not restore after reload');
    await second.call('Emulation.setDeviceMetricsOverride',
      { width: 390, height: 844, deviceScaleFactor: 1, mobile: true });
    require(await second.callFunction(`function() {
      const primary = [...document.querySelectorAll('button')]
        .find(button => button.textContent.trim() === 'Опубликовать');
      return document.documentElement.scrollWidth <= document.documentElement.clientWidth
        && primary instanceof HTMLElement && primary.getBoundingClientRect().height >= 44;
    }`), '390px editor overflowed or exposed an undersized publication action');
    await saveScreenshot('authoring-editor-390.png', second);
    await second.call('Emulation.setDeviceMetricsOverride',
      { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false });

    step = 'authoring_rich_diagram_and_youtube';
    require(await clickText('.editor-toolbar button', 'Медиа', second), 'rich content controls absent');
    require(await clickText('.rich-kind-picker button', 'Схема', second), 'Mermaid editor action absent');
    await fill('.rich-fields input[type="text"]', 'Схема API', second);
    await fill('.rich-fields textarea[rows="3"]', 'Клиент обращается к API', second);
    await fill('.rich-fields textarea[rows="6"]', 'flowchart LR\nA[Клиент] --> B[API]', second);
    require(await click('.rich-apply', second), 'Mermaid insertion action absent');
    require(await clickText('.editor-toolbar button', 'Медиа', second), 'YouTube controls absent');
    require(await clickText('.rich-kind-picker button', 'YouTube', second), 'YouTube editor action absent');
    await fill('.rich-fields input[type="url"]', 'https://www.youtube.com/watch?v=dQw4w9WgXcQ', second);
    await fill('.rich-fields input[type="text"]', 'Видео о сервисе', second);
    await fill('.rich-fields textarea[rows="3"]', 'Текстовое пояснение к видео', second);
    require(await click('.rich-apply', second), 'YouTube insertion action absent');
    await sleep(1200); // Let the acknowledged draft persist both rich nodes before publish.

    if (config.media) {
      step = 'browser_media_upload';
      require(await clickText('.editor-toolbar button', 'Медиа', second), 'media controls absent');
      require(await clickText('.rich-kind-picker button', 'Изображение', second), 'image controls absent');
      await until(() => exists('app-native-media-upload .media-drop', second), 'media upload surface absent');
      require(await second.callFunction(`async function() {
        const canvas = document.createElement('canvas');
        canvas.width = 96; canvas.height = 72;
        const drawing = canvas.getContext('2d');
        drawing.fillStyle = '#eee8dc'; drawing.fillRect(0, 0, 96, 72);
        drawing.fillStyle = '#281378'; drawing.fillRect(12, 18, 72, 36);
        const blob = await new Promise(resolve => canvas.toBlob(resolve, 'image/png'));
        if (!blob) return false;
        const transfer = new DataTransfer();
        transfer.items.add(new File([blob], 'browser-diagram.png', { type: 'image/png' }));
        const drop = document.querySelector('app-native-media-upload .media-drop');
        if (!(drop instanceof HTMLElement)) return false;
        drop.dispatchEvent(new DragEvent('drop', { bubbles: true, cancelable: true, dataTransfer: transfer }));
        return true;
      }`), 'browser could not drop a PNG into the upload surface');
      await until(() => second.callFunction(`function() {
        return [...document.querySelectorAll('.media-row')].some(row =>
          row.textContent.includes('browser-diagram.png')
          && [...row.querySelectorAll('button')].some(button => button.textContent.trim() === 'Выбрать для материала'));
      }`), 'media intent did not become insertable');
      await until(() => second.callFunction(`function() {
        const row = [...document.querySelectorAll('.media-row')]
          .find(item => item.textContent.includes('browser-diagram.png'));
        return row?.querySelector('.media-status')?.textContent?.includes('Готов к просмотру');
      }`), 'uploaded PNG did not become READY in the editor', 30_000);
      require(await clickText('.media-row button', 'Выбрать для материала', second), 'media selection action absent');
      require(await second.callFunction(`function() {
        return document.querySelector('.rich-fields .editor-note')?.textContent?.includes('Файл выбран');
      }`), 'media selection did not reach the editor');
      await fill('.rich-fields input[type="text"]', 'Загруженная схема API', second);
      require(await click('.rich-apply', second), 'image node insertion action absent');
      await until(() => exists('.ProseMirror [data-native-kind="image"]', second),
        'uploaded image did not enter the editor document');
      for (const clip of [
        { kind: 'audio', name: 'browser-audio.mp3', mime: 'audio/mpeg', title: 'Звуковое объяснение' },
        { kind: 'video', name: 'browser-video.mp4', mime: 'video/mp4', title: 'Видеопример' }
      ]) {
        require(await clickText('.editor-toolbar button', 'Медиа', second), `${clip.kind} controls absent`);
        require(await clickText('.rich-kind-picker button', clip.kind === 'audio' ? 'Аудио' : 'Видео', second),
          `${clip.kind} kind absent`);
        require(await second.callFunction(`function(encoded, name, mime) {
          const bytes = Uint8Array.from(atob(encoded), value => value.charCodeAt(0));
          const transfer = new DataTransfer();
          transfer.items.add(new File([bytes], name, { type: mime }));
          const drop = document.querySelector('app-native-media-upload .media-drop');
          if (!(drop instanceof HTMLElement)) return false;
          drop.dispatchEvent(new DragEvent('drop', { bubbles: true, cancelable: true, dataTransfer: transfer }));
          return true;
        }`, [config.mediaClips[clip.kind], clip.name, clip.mime]), 'browser media drop failed');
        await until(() => second.callFunction(`function(name) {
          const row = [...document.querySelectorAll('.media-row')]
            .find(item => item.textContent.includes(name));
          return row?.querySelector('.media-status')?.textContent?.includes('Готов к просмотру');
        }`, [clip.name]), `${clip.kind} did not become READY in the editor`, 30_000);
        require(await second.callFunction(`function(name) {
          const row = [...document.querySelectorAll('.media-row')]
            .find(item => item.textContent.includes(name));
          const insert = [...(row?.querySelectorAll('button') ?? [])]
            .find(button => button.textContent.trim() === 'Выбрать для материала');
          if (!(insert instanceof HTMLButtonElement)) return false;
          insert.click(); return true;
        }`, [clip.name]), `${clip.kind} insert action absent`);
        if (clip.kind === 'audio') {
          uploadedAudioAssetId = await second.callFunction(`function(name) {
            return [...document.querySelectorAll('.media-row')]
              .find(row => row.textContent.includes(name))?.getAttribute('data-asset-id') ?? null;
          }`, [clip.name]);
          require(/^[0-9a-f-]{36}$/i.test(uploadedAudioAssetId ?? ''), 'audio asset was not selected');
        }
        await fill('.rich-fields input[type="text"]', clip.title, second);
        await fill('.rich-fields textarea[rows="3"]', 'Текстовая версия записи', second);
        require(await click('.rich-apply', second), `${clip.kind} node insertion action absent`);
        await until(() => exists(`.ProseMirror [data-native-kind="${clip.kind}"]`, second),
          `${clip.kind} did not enter the editor document`);
      }
      await sleep(1200);
    }

    step = 'authoring_publish_browse';
    require(await clickText('button', 'Опубликовать', second), 'publication unavailable');
    await until(async () => /^\/decks\/[0-9a-f-]{36}\/materials\/[0-9a-f-]{36}$/.test(
      await sanitizedLocation(second)) && await bodyIncludes(editedText, second),
    'publication did not reach Browse');
    const materialPath = await sanitizedLocation(second);
    require(await second.callFunction(`function() {
      return !globalThis.__mnemaXss && !document.querySelector('img[src="x"]');
    }`), 'published content executed as markup');
    await until(() => second.callFunction(`function() {
      const image = document.querySelector('app-native-mermaid img');
      return image instanceof HTMLImageElement && image.complete && image.naturalWidth > 0;
    }`), 'published Mermaid diagram did not render');
    require(await second.callFunction(`function() {
      const video = document.querySelector('app-native-youtube');
      return video instanceof HTMLElement && !video.querySelector('iframe')
        && video.querySelector('a[href="https://www.youtube.com/watch?v=dQw4w9WgXcQ"]') !== null
        && video.querySelector('button')?.textContent?.trim() === 'Показать видео';
    }`), 'YouTube consent or external fallback was absent');
    if (config.media) {
      await until(async () => {
        browseMediaState = await second.callFunction(`function() {
        const picture = document.querySelector('app-native-media-image img');
        const renderer = document.querySelector('app-native-document-renderer');
        return { visible: document.visibilityState, surface: Boolean(document.querySelector('app-native-media-surface')),
          renderer: Boolean(renderer), imageNode: Boolean(document.querySelector('app-native-media-image')),
          pending: document.querySelector('.native-media-pending')?.textContent?.trim() ?? null,
          loaded: picture instanceof HTMLImageElement && picture.complete && picture.naturalWidth > 0 };
        }`);
        return browseMediaState.loaded;
      }, 'published PNG was not processed and shown without reload', 30_000);
      await until(() => second.callFunction(`function() {
        const audio = document.querySelector('app-native-media-player audio');
        const video = document.querySelector('app-native-media-player video');
        return audio instanceof HTMLAudioElement && audio.readyState >= 1
          && video instanceof HTMLVideoElement && video.readyState >= 1;
      }`), 'processed audio/video did not reach the shared browser players', 30_000);
      await saveFullScreenshot('authoring-browse-media-1440.png', second);
    }
    await saveScreenshot('authoring-browse-1440.png', second);
    // The material list lives in the deck hub; the separate list route no longer exists.
    await navigate(deckPath, second);
    await until(() => second.callFunction(`function() {
      return document.querySelectorAll('app-selectable-material-list li.item-row').length === 1
        && document.querySelector('#materials-heading')?.textContent.replace(/\\s+/g, ' ').trim() === 'Материалы · 1';
    }`), 'deck hub list did not expose the published item');

    const persisted = await second.callFunction(`async function(base, authorization, expectedText) {
      const response = await fetch(base + '/api/capture-notes?limit=20', {
        credentials: 'omit', headers: { Authorization: authorization }
      });
      if (!response.ok) return null;
      const body = await response.json();
      const note = body.items?.find(candidate => candidate.text === expectedText);
      return note ? { source: note.source, converted: Boolean(note.conversion),
        createdAt: note.createdAt, updatedAt: note.updatedAt } : null;
    }`, [config.frontend, 'Bearer ' + secondBearer, capturedText]);
    require(persisted?.source === 'manual' && persisted.converted
      && typeof persisted.createdAt === 'string' && typeof persisted.updatedAt === 'string',
    'Capture source/conversion was not preserved');
    record('real_https_authoring_capture_draft_publish_reload_browse', {
      requests: networkRequests - requestsBeforeAuthoring,
      durationMs: Date.now() - authoringStarted,
      captureSourcePreserved: true,
      acknowledgedDraftRestored: true,
      unsafeMarkupExecuted: false, mermaidRendered: true, youtubeConsentBeforeEmbed: true,
      browserPngProcessed: Boolean(config.media), browserAudioVideoProcessed: Boolean(config.media)
    });

    step = 'study_browser_provision';
    let studyFixture = { ready: false };
    if (config.media) {
      require(uploadedAudioAssetId !== null, 'audio-prompt exercise has no uploaded audio');
      await navigate(materialPath + '/exercises/new', second);
      await until(() => exists('input[name="mechanic"][value="FREE_RESPONSE"]', second),
        'exercise authoring page did not load');
      require(await click('input[name="mechanic"][value="FREE_RESPONSE"]', second),
        'free-response mechanic could not be selected');
      await until(() => exists('#free-response-prompt-text-0', second), 'free-response prompt slot absent');
      await fill('#free-response-prompt-text-0', 'Прослушайте запись и напишите ответ', second);
      require(await second.callFunction(`function() {
        const slot = document.querySelector('#free-response-prompt-text-0')?.closest('app-exercise-slot-editor');
        const add = [...(slot?.querySelectorAll('button[data-add]') ?? [])]
          .find(button => button.textContent.trim() === 'Добавить аудио');
        if (!(add instanceof HTMLButtonElement)) return false;
        add.click(); return true;
      }`), 'prompt media picker could not be opened');
      await until(() => exists('app-exercise-slot-editor app-native-media-upload .media-drop', second),
        'prompt media picker did not render');
      require(await second.callFunction(`function(encoded) {
        const bytes = Uint8Array.from(atob(encoded), value => value.charCodeAt(0));
        const transfer = new DataTransfer();
        transfer.items.add(new File([bytes], 'exercise-audio.mp3', { type: 'audio/mpeg' }));
        const drop = document.querySelector('app-exercise-slot-editor app-native-media-upload .media-drop');
        if (!(drop instanceof HTMLElement)) return false;
        drop.dispatchEvent(new DragEvent('drop', { bubbles: true, cancelable: true, dataTransfer: transfer }));
        return true;
      }`, [config.mediaClips.audio]), 'exercise audio upload absent');
      await until(() => second.callFunction(`function() {
        const row = [...document.querySelectorAll('app-exercise-slot-editor .media-row')]
          .find(item => item.textContent.includes('exercise-audio.mp3'));
        return row?.querySelector('.media-status')?.textContent?.includes('Готов к просмотру');
      }`), 'exercise audio did not become ready', 30_000);
      require(await second.callFunction(`function() {
        const row = [...document.querySelectorAll('app-exercise-slot-editor .media-row')]
          .find(item => item.textContent.includes('exercise-audio.mp3'));
        const choice = [...(row?.querySelectorAll('button') ?? [])]
          .find(button => button.textContent.trim() === 'Добавить в упражнение');
        if (!(choice instanceof HTMLButtonElement)) return false;
        choice.click(); return true;
      }`), 'exercise audio could not be attached to the prompt');
      await until(() => exists('#free-response-prompt-title-1', second), 'attached audio block absent');
      await fill('#free-response-prompt-title-1', 'Звуковое объяснение', second);
      require(await clickText('button', 'Продолжить', second), 'continue to the answers step absent');
      await until(() => exists('#step-answers app-text-answer-editor input[type="text"]', second), 'answers step did not open');
      require(await second.callFunction(`function() {
        const input = document.querySelector('app-text-answer-editor input[type="text"]');
        if (!(input instanceof HTMLInputElement)) return false;
        input.focus(); return document.activeElement === input;
      }`), 'accepted answer field absent');
      await second.call('Input.insertText', { text: editedText });
      require(await clickText('button', 'Продолжить', second), 'continue to the save step absent');
      await until(() => exists('#step-finish', second), 'save step did not open');
      require(await clickText('button', 'Создать упражнение', second),
        'exercise save action absent');
      await until(async () => /^\/decks\/[0-9a-f-]{36}\/exercises\/[0-9a-f-]{36}\/edit/.test(
        await sanitizedLocation(second)), 'audio-prompt exercise was not saved through authoring UI');
      freeResponseEditPath = await sanitizedLocation(second);
      studyFixture = { ready: true };
    } else studyFixture = await second.callFunction(`async function(base, authorization, deckPath, materialPath) {
      const headers = { Authorization: authorization };
      const deck = await fetch(base + '/api' + deckPath, { credentials: 'omit', headers });
      const memberKey = materialPath.split('/').at(-1);
      const item = await fetch(base + '/api' + deckPath + '/items/' + memberKey,
        { credentials: 'omit', headers });
      if (!deck.ok || !item.ok) return { ready: false };
      const deckBody = await deck.json(), itemBody = await item.json();
      const node = itemBody.document?.root?.content?.find(candidate => candidate.type === 'paragraph');
      if (!node?.id || !itemBody.memberKey || !itemBody.itemRevisionId || !deckBody.revisionId)
        return { ready: false };
      const exercise = await fetch(base + '/api' + deckPath + '/exercises', {
        method: 'POST', credentials: 'omit', headers: { ...headers, 'Content-Type': 'application/json',
          'If-Match': deck.headers.get('etag') },
        body: JSON.stringify({
          commandId: crypto.randomUUID(), expectedDeckRevisionId: deckBody.revisionId,
          objective: { operation: 'create', title: 'Слово memory' },
          exercise: { type: 'FREE_RESPONSE', schemaVersion: 2, enabled: true,
            subject: { memberKey: itemBody.memberKey, itemRevisionId: itemBody.itemRevisionId },
            content: { prompt: [{ kind: 'TEXT', text: 'Введите английское слово «память»' }],
              reference: [{ kind: 'MATERIAL', memberKey: itemBody.memberKey,
                itemRevisionId: itemBody.itemRevisionId, nodeId: node.id }], responseInput: 'TEXT' },
            answerKey: { kind: 'TEXT', accepted: ['memory'],
              normalization: ['UNICODE_NFC', 'TRIM', 'CASE_FOLD'], matchingMode: 'STRICT' },
            evaluatorPolicy: { id: 'deterministic-text', version: '1' } }
        })
      });
      return { ready: exercise.status === 201 };
    }`, [config.frontend, 'Bearer ' + secondBearer, deckPath, materialPath]);
    require(studyFixture?.ready, 'real browser Study fixture publication failed');

    step = 'study_browser_ui';
    await navigate(deckPath + '/study', second);
    await until(() => exists('.session-setup', second), 'Study preset setup did not load');
    require(await second.callFunction(`function() {
      const button = document.querySelector('.session-setup [data-answer-control]');
      if (!(button instanceof HTMLButtonElement)) return false;
      button.focus();
      return document.activeElement === button;
    }`), 'Study preset cannot receive keyboard focus');
    await pressKey(' ', 'Space', 32, 0, second);
    await until(async () => await exists('#study-0-answer', second) || await exists('.completion', second)
      || await exists('.notice.error', second), 'keyboard did not change Study state');
    require(await exists('#study-0-answer', second), 'keyboard Study start reached empty or error state');
    if (config.media) {
      await until(() => second.callFunction(`function() {
        const player = document.querySelector('.study-card app-learner-media app-native-media-player audio');
        return player instanceof HTMLAudioElement && player.readyState >= 1;
      }`), 'listening Study did not load its audio in the shared player');
      require(await bodyIncludes('Прослушайте запись и напишите ответ', second),
        'Study did not present the authored listening instruction');
    }
    await until(() => second.callFunction(`function() {
      return document.activeElement?.id === 'study-0-answer';
    }`), 'Study answer did not receive focus');
    require(!(await bodyIncludes(config.media ? editedText : 'memory', second)),
      'Study leaked the reference before an accepted answer');
    await second.call('Emulation.setEmulatedMedia',
      { features: [{ name: 'prefers-reduced-motion', value: 'reduce' }] });
    for (const [width, height, dpr, name] of [
      [1440, 900, 1, 'study-1440.png'], [390, 844, 1, 'study-390.png'],
      [320, 900, 2, 'study-320-at-200-percent.png']]) {
      await second.call('Emulation.setDeviceMetricsOverride',
        { width, height, deviceScaleFactor: dpr, mobile: width === 390 });
      require(await second.callFunction(`function() {
        const action = document.querySelector('.study-card .button.primary');
        return matchMedia('(prefers-reduced-motion: reduce)').matches
          && document.documentElement.scrollWidth <= document.documentElement.clientWidth
          && action instanceof HTMLElement && action.getBoundingClientRect().height >= 44;
      }`), 'Study layout or touch target failed under responsive reduced-motion emulation');
      await saveScreenshot(name, second);
    }
    await second.call('Emulation.setDeviceMetricsOverride',
      { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false });
    await fill('#study-0-answer', config.media ? editedText : 'memory', second);
    require(await clickText('button', 'Проверить ответ', second), 'Study submit action absent');
    await until(() => exists('#feedback-title', second), 'Study feedback did not arrive from the real API');
    require(await second.callFunction(`function() {
      return document.activeElement?.id === 'feedback-title'
        && (document.querySelector('.progress-note')?.textContent ?? '').trim().length > 0
        && document.querySelector('.feedback-card.correct') !== null;
    }`), 'Study feedback focus or canonical transition missing');
    await saveScreenshot('study-feedback-1440.png', second);
    record('real_https_study_browser', { scheduled: 'FREE_RESPONSE', audioPrompt: Boolean(config.media),
      authoredAudioLoaded: Boolean(config.media), keyboardStart: true,
      feedbackFocus: true, widths: [1440, 390, 320], deviceScaleFactor: 2,
      reducedMotion: true, noHorizontalOverflow: true, primaryActionMinimumPx: 44 });
    if (config.media) {
      // Close the base flow's one-exercise session so later scenarios start fresh sessions.
      step = 'study_browser_complete';
      require(await clickText('.feedback-card button.primary', 'Продолжить', second), 'Study continue action absent');
      await until(() => exists('.completion', second), 'base Study session did not complete');
      // Notification center against a REAL producer (the media worker rejecting a corrupt upload).
      await runNotifications({
        tab: second, config, record, SafeFailure, until, exists, sanitizedLocation, navigate, saveScreenshot,
        clickText, setStep: value => { step = value; }, deckPath, bearer: secondBearer, answerText: editedText });
    }
    if (config.mechanics) {
      step = 'mechanics_prepare';
      const outcome = await runMechanics({
        tab: second, config, record, SafeFailure, until, exists, bodyIncludes, sanitizedLocation, navigate, click,
        saveScreenshot, saveFullScreenshot, run, mediaTrace: () => ({ api: mediaApiResponses.slice(-10), store: mediaResponses.slice(-10), failures: mediaFailures.slice(-5) }), setStep: value => { step = value; },
        deckPath, materialPath, freeResponseEditPath, materialText: editedText });
      mechanicsFailures = outcome.failures;
      mechanicsFindings = outcome.findings;
      // The Deck hub (#285) is checked on the deck the mechanics baseline just filled with exercises.
      step = 'hub_prepare';
      const hub = await runHub({
        tab: second, config, record, SafeFailure, until, exists, bodyIncludes, sanitizedLocation, navigate,
        saveScreenshot, saveFullScreenshot, clickText, setStep: value => { step = value; }, deckPath, bearer: secondBearer });
      mechanicsFailures = [...mechanicsFailures, ...hub.failures];
    }
    // Native code block (#303): real editor input, publication, Browse, scrolling, round trip. Runs last in its own material.
    await runCodeBlock({
      tab: second, config, record, SafeFailure, until, exists, sanitizedLocation, navigate, saveScreenshot,
      clickText, setStep: value => { step = value; }, deckPath, bearer: secondBearer });
    // The profile's «ИИ-бюджет» block (#281) against the real GET /api/usage.
    step = 'usage_prepare';
    await runUsage({
      tab: second, config, record, SafeFailure, until, exists, navigate, saveScreenshot, setStep: value => { step = value; },
      bearer: secondBearer });
    // The generation composer and the Workshop (#289) against the real Learning API with the Stub text provider.
    if (config.generation) {
      step = 'workshop_prepare';
      await runWorkshop({
        tab: second, config, record, SafeFailure, until, exists, navigate, saveScreenshot, clickText, setStep: value => { step = value; },
        deckPath, bearer: secondBearer, inflight: inflightNow, audioAssetId: config.media ? uploadedAudioAssetId : null });
    }
    // The semantic assessment of explanations (#292): the rubric editor and the learner's side, with the Stub grader.
    if (config.assessment) {
      step = 'assessment_prepare';
      await runAssessment({
        tab: second, config, record, SafeFailure, until, exists, navigate, saveScreenshot, saveFullScreenshot, clickText,
        setStep: value => { step = value; }, deckPath, bearer: secondBearer });
    }
  }
  step = 'first_tab_profile';
  // The first tab stayed in the background through the authoring, notification, mechanics and hub flows; bring it back
  // before reading it, as the second tab's focus changes in those flows may have hidden it.
  await cdp.call('Page.bringToFront');
  await until(authenticated, 'first tab lost its independent profile', 30_000);
  step = 'two_account_precondition';
  require(await cdp.callFunction(`async function(firstUrl, firstAuthorization, secondUrl, secondAuthorization, sessionUrl) {
    const values=await Promise.all([
      fetch(firstUrl, { credentials: 'omit', headers: { Authorization: firstAuthorization } }),
      fetch(secondUrl, { credentials: 'omit', headers: { Authorization: secondAuthorization } }),
      fetch(sessionUrl, { credentials: 'include' })
    ].map(async response=>{const result=await response;return result.ok?result.json():null;}));
    return typeof values[0]?.sub==='string' && typeof values[1]?.sub==='string'
      && values[0].sub!==values[1].sub && values[2]?.accountId===values[1].sub;
  }`, [config.identity + '/userinfo', 'Bearer ' + firstBearer,
    config.identity + '/userinfo', 'Bearer ' + secondBearer,
    config.identity + '/api/accounts/session']), 'two-account shared-cookie precondition not established');
  step = 'logout';
  logoutStatus = 0;
  await until(() => exists(config.logoutSelector), 'logout control absent');
  require(await click(config.logoutSelector), 'logout control is not clickable');
  await until(async () => logoutStatus === 204 && !(await authenticated()), 'logout did not clear authentication');
  require(await cdp.callFunction(`function(secrets) {
    return ![...Object.values(localStorage), ...Object.values(sessionStorage)]
      .some(value => secrets.some(secret => value.includes(secret)));
  }`, [bearerTokens]), 'logout retained bearer in browser storage');
  actorAfterLogout = await cdp.callFunction(`function(url, authorization) {
    return fetch(url, { headers: { Authorization: authorization }, credentials: 'omit' }).then(response => response.status);
  }`, [config.identity + '/userinfo', 'Bearer ' + firstBearer]);
  otherAfterLogout = await second.callFunction(`function(url, authorization) {
    return fetch(url, { headers: { Authorization: authorization }, credentials: 'omit' }).then(response => response.status);
  }`, [config.identity + '/userinfo', 'Bearer ' + secondBearer]);
  require(actorAfterLogout === 401 && otherAfterLogout === 200,
    'logout actor followed shared cookie instead of active bearer');
  record('logout_revokes_prior_bearer', { logoutStatus: 204, oldBearerStatus: 401 });
  record('two_account_shared_cookie_logout_isolation', { loggedOutAccountBearerStatus: actorAfterLogout,
    otherAccountBearerStatus: otherAfterLogout });
  step = 'wrong_live_state';
  const beforeWrongState = exchanges.length;
  tamperNextCallback = true;
  await navigate('/login');
  await fill('#login-name', config.login); await fill('#password', config.password); await submit();
  await until(() => exists(config.errorSelector), 'wrong state with pending PKCE transaction not rejected');
  require(!tamperNextCallback && !(await authenticated()) && exchanges.length === beforeWrongState,
    'wrong state with pending PKCE transaction attempted exchange');
  record('wrong_live_pkce_state_rejected');
  step = 'replayed_callback';
  const beforeReplay = exchanges.length;
  await navigateUrl(originalCallback);
  await until(() => exists(config.errorSelector), 'replayed callback not rejected');
  require(!(await authenticated()) && exchanges.length === beforeReplay, 'replayed callback attempted exchange');
  record('replayed_callback_rejected');
  // Only synthetic profile views and an empty login form are exported; callback state remains private.
  await navigate('/login'); await until(() => exists('#login-name'), 'final login route absent');
  await fill('#login-name', ''); await fill('#password', '');
  require(!(await authenticated()) && await cdp.callFunction(`function(values) {
    return !values.some(value => document.body.innerText.includes(value));
  }`, [[config.login, config.email]]), 'screenshot not anonymous');
  await Promise.all([...asyncWork]);
  for (const body of exchanges) {
    const verifier = body.get('code_verifier');
    require(verifier && verifier.length >= 43 && verifier.length <= 128, 'invalid PKCE verifier');
    const challenge = createHash('sha256').update(verifier).digest('base64url');
    require(challenges.has(challenge) && body.get('redirect_uri') === config.frontend + '/auth/callback', 'PKCE exchange mismatch');
  }
  require(browserErrors === 0 && !asynchronousFailure && !tabs.some(tab => tab.eventFailed), 'browser runtime errors');
  const screenshot = await cdp.call('Page.captureScreenshot', { format: 'png' });
  await writeFile(join(config.output, 'login.png'), Buffer.from(screenshot.data, 'base64'));
  const evidence = { state: mechanicsFailures.length === 0 ? 'passed' : 'failed',
    ...(config.mechanics ? { mechanicsFailures, mechanicsFindings, syntheticMicrophone: true, realDeviceMicrophone: false } : {}),
    scenarios: results, tokenExchanges, networkRequests, identityRequests, cancelledInterceptions,
    externalRequestsBlocked: externalRequests,
    browserRuntimeErrors: browserErrors, transport: 'real HTTPS, exact ephemeral SPKI allowlist, same-site cross-origin loopback',
    browser: (await cdp.call('Browser.getVersion')).product };
  await writeFile(join(config.output, 'browser.json'), JSON.stringify(evidence, null, 2));
  if (mechanicsFailures.length > 0) process.exitCode = 1;
} catch (error) {
  // No caught exception, URL, DOM, response body or stack may reveal a credential.
  await writeFile(join(config.output, 'browser.json'), JSON.stringify({ state: 'failed', step,
    reason: error instanceof SafeFailure ? error.message : 'driver_failure', completedScenarios: results.length,
    // Local diagnosis only (--keep-on-failure): CDP method names and timeouts, never page data or credentials.
    ...(config.diagnosticsDir && !(error instanceof SafeFailure) ? { driverDetail: String(error?.message ?? error).slice(0, 160) } : {}),
    ...(config.mechanics ? { scenarios: results } : {}), counts: diagnostics() }));
  process.exitCode = 1;
} finally {
  for (const tab of tabs) tab.close();
}
