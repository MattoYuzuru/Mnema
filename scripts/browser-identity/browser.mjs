// Node22 built-in WebSocket/CDP only. All credentials remain in this process's memory.
import { readFile, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { createHash } from 'node:crypto';

const config = JSON.parse(await readFile(process.argv[2], 'utf8'));
const results = [];
class SafeFailure extends Error {}
const require = (value, label) => { if (!value) throw new SafeFailure(label); };
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
class CDP {
  constructor(socket) {
    this.socket = socket; this.next = 0; this.pending = new Map(); this.listeners = new Map();
    socket.addEventListener('message', event => {
      const message = JSON.parse(event.data);
      if (message.id) {
        const request = this.pending.get(message.id);
        if (!request) return;
        this.pending.delete(message.id); clearTimeout(request.timeout);
        if (message.error) request.reject(new Error('CDP command failed'));
        else request.resolve(message.result);
      } else for (const listener of this.listeners.get(message.method) || []) listener(message.params);
    });
  }
  call(method, params = {}) {
    return new Promise((resolve, reject) => {
      const id = ++this.next;
      const timeout = setTimeout(() => { this.pending.delete(id); reject(new Error('CDP timeout')); }, 10000);
      this.pending.set(id, { resolve, reject, timeout });
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
  require(process.versions.node.split('.')[0] === '22', 'Node22 is required');
  cdp = await openTab();
  const allowed = new Set([config.frontend, config.identity]);
  let externalRequests = 0, tokenExchanges = 0, registrationStatus = 0, loginStatus = 0, logoutStatus = 0;
  let browserErrors = 0, callback = null, networkRequests = 0, identityRequests = 0, profileReads = 0;
  const authoringResponses = [];
  let tamperNextCallback = false;
  const bearerTokens = [], idTokens = [], exchanges = [];
  const challenges = new Set();
  const loadedDocuments = new Set();
  const asyncWork = new Set();
  let asynchronousFailure = false;
  let actorAfterLogout = null, otherAfterLogout = null;
  diagnostics = () => ({ networkRequests, identityRequests, tokenExchanges, browserErrors, externalRequests,
    asynchronousFailure, eventFailed: tabs.some(tab => tab.eventFailed), actorAfterLogout, otherAfterLogout,
    authoringResponses });
  const run = promise => {
    asyncWork.add(promise);
    promise.catch(() => { asynchronousFailure = true; }).finally(() => asyncWork.delete(promise));
  };
  function watchTab(tab) {
    const tokenRequests = new Set();
    tab.on('Runtime.exceptionThrown', () => { browserErrors++; });
    tab.on('Page.lifecycleEvent', event => {
      if (event.name === 'DOMContentLoaded') loadedDocuments.add(event.loaderId);
    });
    tab.on('Fetch.requestPaused', event => {
      const url = new URL(event.request.url);
      networkRequests++;
      if (url.origin === config.identity) identityRequests++;
      if (networkRequests > 500 || identityRequests > 150) asynchronousFailure = true;
      if (!allowed.has(url.origin) || asynchronousFailure) {
        externalRequests++;
        run(tab.call('Fetch.failRequest', { requestId: event.requestId, errorReason: 'BlockedByClient' }));
      } else if (tamperNextCallback && url.origin === config.frontend && url.pathname === '/auth/callback'
          && url.searchParams.has('code')) {
        tamperNextCallback = false;
        url.searchParams.set('state', 'fixture-wrong-live-state');
        // A real navigation redirect changes location.search; an invisible request URL rewrite would not.
        run(tab.call('Fetch.fulfillRequest', { requestId: event.requestId, responseCode: 302,
          responseHeaders: [{ name: 'Location', value: url.href }, { name: 'Cache-Control', value: 'no-store' }], body: '' }));
      } else run(tab.call('Fetch.continueRequest', { requestId: event.requestId }));
    });
    tab.on('Network.requestWillBeSent', event => {
      const url = new URL(event.request.url);
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
    tab.on('Network.loadingFinished', event => {
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
  async function until(predicate, label) {
    const deadline = Date.now() + 18000;
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
    && await exists('#detail-title'), 'real deck create did not open its canonical detail route');
  const deckPath = await sanitizedLocation();
  require(await cdp.callFunction(`function(expected) {
    const input = document.querySelector('#detail-title');
    return input instanceof HTMLTextAreaElement && input.value === expected
      && document.querySelector('h1')?.textContent?.trim() === expected;
  }`, [originalTitle]), 'created deck did not preserve exact title');
  record('real_own_deck_create_and_detail', { canonicalRoute: true, exactUnicode: true });
  await cdp.call('Page.reload', { ignoreCache: true });
  await until(() => exists('#detail-title'), 'deck detail did not survive browser reload');
  require(await cdp.callFunction(`function(expected) {
    const input = document.querySelector('#detail-title');
    return input instanceof HTMLTextAreaElement && input.value === expected;
  }`, [originalTitle]), 'reloaded deck lost persisted metadata');
  await fill('#detail-title', savedTitle);
  await submit();
  await until(() => cdp.callFunction(`function(expected) {
    return document.querySelector('h1')?.textContent?.trim() === expected
      && document.body.innerText.includes('Сервер подтвердил текущую версию колоды.');
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
  await until(() => exists('#detail-title', conflictTab), 'conflict tab did not load the deck');
  await navigate(deckPath);
  await until(() => exists('#detail-title'), 'primary conflict tab did not reload the deck');
  await fill('#detail-title', 'Изменение из первой вкладки');
  await submit();
  await until(() => cdp.callFunction(`function() {
    return document.body.innerText.includes('Сервер подтвердил текущую версию колоды.');
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
    return document.body.innerText.includes('Сервер подтвердил текущую версию колоды.');
  }`), 'explicit conflict reapply did not publish a fresh command');
  await navigate(deckPath);
  await until(() => cdp.callFunction(`function() {
    const input = document.querySelector('#detail-title');
    return input instanceof HTMLTextAreaElement && input.value === 'Изменение из второй вкладки';
  }`), 'conflict resolution did not persist the chosen local draft');
  record('real_own_deck_412_conflict_resolution', { exactLocalDraftPreserved: true, explicitReapply: true });

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
    await until(() => bodyIncludes('Черновик подтверждён сервером.', second), 'draft acknowledgement absent');
    const editorPath = await sanitizedLocation(second);
    await second.call('Page.reload', { ignoreCache: true });
    await until(async () => (await sanitizedLocation(second)) === editorPath
      && (await bodyIncludes(editedText, second))
      && (await bodyIncludes('Открыт последний серверный черновик', second)),
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

    step = 'authoring_publish_browse';
    require(await clickText('button', 'Опубликовать', second), 'publication unavailable');
    await until(async () => /^\/decks\/[0-9a-f-]{36}\/materials\/[0-9a-f-]{36}$/.test(
      await sanitizedLocation(second)) && await bodyIncludes(editedText, second),
    'publication did not reach Browse');
    const materialPath = await sanitizedLocation(second);
    require(await second.callFunction(`function() {
      return !globalThis.__mnemaXss && !document.querySelector('img[src="x"]');
    }`), 'published content executed as markup');
    await saveScreenshot('authoring-browse-1440.png', second);
    await navigate(deckPath + '/materials', second);
    await until(() => bodyIncludes('1 материалов', second), 'Browse list did not expose the published item');

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
      unsafeMarkupExecuted: false
    });

    step = 'study_browser_provision';
    const studyFixture = await second.callFunction(`async function(base, authorization, deckPath, materialPath) {
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
          objective: { operation: 'create', answerContract: { schemaVersion: 1,
            normalization: ['UNICODE_NFC', 'TRIM', 'CASE_FOLD'], accepted: ['memory'] } },
          exercise: { type: 'TYPED', schemaVersion: 1, enabled: true,
            prompt: { kind: 'CUSTOM_TEXT', text: 'Введите memory' },
            bindings: [{ bindingId: crypto.randomUUID(), role: 'ASSESSED',
              memberKey: itemBody.memberKey, itemRevisionId: itemBody.itemRevisionId,
              nodeIds: [node.id], display: { kind: 'NODE_TEXT' }, ordinal: 0 }],
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
    await until(async () => await exists('#typed-answer', second) || await exists('.completion', second)
      || await exists('.notice.error', second), 'keyboard did not change Study state');
    require(await exists('#typed-answer', second), 'keyboard Study start reached empty or error state');
    await until(() => second.callFunction(`function() {
      return document.activeElement?.id === 'typed-answer';
    }`), 'Study answer did not receive focus');
    require(!(await bodyIncludes('Эталон: memory', second)), 'Study leaked the reference before an accepted answer');
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
    await fill('#typed-answer', 'memory', second);
    require(await clickText('button', 'Проверить ответ', second), 'Study submit action absent');
    await until(() => exists('#feedback-title', second), 'Study feedback did not arrive from the real API');
    require(await second.callFunction(`function() {
      return document.activeElement?.id === 'feedback-title'
        && document.body.innerText.includes('Следующее повторение назначено сервером.');
    }`), 'Study feedback focus or canonical transition missing');
    await saveScreenshot('study-feedback-1440.png', second);
    record('real_https_study_browser', { scheduled: 'TYPED', keyboardStart: true,
      feedbackFocus: true, widths: [1440, 390, 320], deviceScaleFactor: 2,
      reducedMotion: true, noHorizontalOverflow: true, primaryActionMinimumPx: 44 });
  }
  require(await authenticated(), 'first tab lost its independent profile');
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
  // Only an empty login form is exported; account/profile/callback screens remain private.
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
  const evidence = { state: 'passed', scenarios: results, tokenExchanges, networkRequests, identityRequests,
    externalRequestsBlocked: externalRequests,
    browserRuntimeErrors: browserErrors, transport: 'real HTTPS, exact ephemeral SPKI allowlist, same-site cross-origin loopback',
    browser: (await cdp.call('Browser.getVersion')).product };
  await writeFile(join(config.output, 'browser.json'), JSON.stringify(evidence, null, 2));
} catch (error) {
  // No caught exception, URL, DOM, response body or stack may reveal a credential.
  await writeFile(join(config.output, 'browser.json'), JSON.stringify({ state: 'failed', step,
    reason: error instanceof SafeFailure ? error.message : 'driver_failure', completedScenarios: results.length,
    counts: diagnostics() }));
  process.exitCode = 1;
} finally {
  for (const tab of tabs) tab.close();
}
