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
  let tamperNextCallback = false;
  const bearerTokens = [], idTokens = [], exchanges = [];
  const challenges = new Set();
  const loadedDocuments = new Set();
  const asyncWork = new Set();
  let asynchronousFailure = false;
  let actorAfterLogout = null, otherAfterLogout = null;
  diagnostics = () => ({ networkRequests, identityRequests, tokenExchanges, browserErrors, externalRequests,
    asynchronousFailure, eventFailed: tabs.some(tab => tab.eventFailed), actorAfterLogout, otherAfterLogout });
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
      if (networkRequests > 300 || identityRequests > 100) asynchronousFailure = true;
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
  const exists = (selector, tab = cdp) => tab.evaluate(`!!document.querySelector(${JSON.stringify(selector)})`);
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
    await tab.evaluate(`(() => {const e=document.querySelector(${JSON.stringify(selector)});e.focus();
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(e,${JSON.stringify(value)});
      e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));})()`);
  }
  async function submit(tab = cdp) { await tab.evaluate("document.querySelector('form button[type=submit]').click()"); }
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
  await cdp.evaluate(`document.querySelector(${JSON.stringify(config.logoutSelector)}).click()`);
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
  step = 'reload';
  const beforeReload = tokenExchanges, beforeProfile = profileReads;
  await cdp.call('Page.reload', { ignoreCache: true });
  await until(async () => (await authenticated()) && profileReads > beforeProfile, 'reload did not revalidate profile');
  require(tokenExchanges === beforeReload, 'reload unexpectedly reauthorized');
  require(await cdp.evaluate(`!Object.values(localStorage).some(v=>
    ${JSON.stringify(bearerTokens)}.some(token=>v.includes(token)))`), 'bearer persisted in localStorage');
  require(await cdp.evaluate(`![...Object.values(localStorage),...Object.values(sessionStorage)].some(v=>
    ${JSON.stringify([...idTokens, config.password])}.some(secret=>v.includes(secret)))`), 'ID token or password persisted');
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
  require(await authenticated(), 'first tab lost its independent profile');
  require(await cdp.evaluate(`(async()=>{
    const values=await Promise.all([
      fetch(${JSON.stringify(config.identity + '/userinfo')},{credentials:'omit',headers:{Authorization:${JSON.stringify('Bearer ' + firstBearer)}}}),
      fetch(${JSON.stringify(config.identity + '/userinfo')},{credentials:'omit',headers:{Authorization:${JSON.stringify('Bearer ' + secondBearer)}}}),
      fetch(${JSON.stringify(config.identity + '/api/accounts/session')},{credentials:'include'})
    ].map(async response=>{const result=await response;return result.ok?result.json():null;}));
    return typeof values[0]?.sub==='string' && typeof values[1]?.sub==='string'
      && values[0].sub!==values[1].sub && values[2]?.accountId===values[1].sub;
  })()`), 'two-account shared-cookie precondition not established');
  step = 'logout';
  logoutStatus = 0;
  await until(() => exists(config.logoutSelector), 'logout control absent');
  await cdp.evaluate(`document.querySelector(${JSON.stringify(config.logoutSelector)}).click()`);
  await until(async () => logoutStatus === 204 && !(await authenticated()), 'logout did not clear authentication');
  require(await cdp.evaluate(`![...Object.values(localStorage),...Object.values(sessionStorage)].some(v=>
    ${JSON.stringify(bearerTokens)}.some(token=>v.includes(token)))`), 'logout retained bearer in browser storage');
  actorAfterLogout = await cdp.evaluate(`fetch(${JSON.stringify(config.identity + '/userinfo')},
    {headers:{Authorization:${JSON.stringify('Bearer ' + firstBearer)}},credentials:'omit'}).then(r=>r.status)`);
  otherAfterLogout = await second.evaluate(`fetch(${JSON.stringify(config.identity + '/userinfo')},
    {headers:{Authorization:${JSON.stringify('Bearer ' + secondBearer)}},credentials:'omit'}).then(r=>r.status)`);
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
  require(!(await authenticated()) && await cdp.evaluate(`![${JSON.stringify(config.login)},${JSON.stringify(config.email)}]
    .some(value=>document.body.innerText.includes(value))`), 'screenshot not anonymous');
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
