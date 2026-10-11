// Real-browser check of community decks (Share/17, #439, epic #419): publication -> guest -> copy -> study -> by invitation.
// Runs with `--community` against the real Identity and Learning jars (the Learning of this run has the three community kill
// switches on, see run.py), on the two accounts of the base flow, once on a desktop viewport and once on a 390 px mobile
// viewport, with real CDP keyboard events wherever the interaction is natural. Node 24 built-ins only.
//
// Who is who: the owner is the first account (the first tab), the member is the second account (the second tab), the guest is
// a third tab in a NEW browser context: no cookies, no storage, no session. The fixture is made through the real UI (the deck and
// its materials in the real editor, the public profile consent in /profile, the access change in the hub) except one exercise,
// which is created through the authenticated API exactly like the Study fixture of the base flow.
//
// A broken stage is a finding for the product, never something to work around: it is recorded as one failed `community_<pass>_<stage>`
// result with the UI's own reason and a screenshot, the stages that depend on it are recorded as skipped, and the run fails.
import { writeFile } from 'node:fs/promises';
import { join } from 'node:path';

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const KEYS = { Tab: ['Tab', 'Tab', 9], Enter: ['Enter', 'Enter', 13], Space: [' ', 'Space', 32], ArrowLeft: ['ArrowLeft', 'ArrowLeft', 37],
  ArrowRight: ['ArrowRight', 'ArrowRight', 39], ArrowDown: ['ArrowDown', 'ArrowDown', 40], Escape: ['Escape', 'Escape', 27] };
/** What is wrong with a topic directory body, as the page's strict parser would see it: keys per level, empty or over-long names, duplicate ids, ordinals. */
function topicShape(body) {
  const level = entries => ({ count: Array.isArray(entries) ? entries.length : null,
    keys: [...new Set((Array.isArray(entries) ? entries : []).map(entry => Object.keys(entry ?? {}).sort().join(',')))],
    duplicates: Array.isArray(entries) ? entries.length - new Set(entries.map(entry => entry?.topicId)).size : null,
    badNames: (Array.isArray(entries) ? entries : []).filter(entry => ['topicId', 'nameRu', 'nameEn'].some(key => typeof entry?.[key] !== 'string' || entry[key].length === 0 || entry[key].length > 200)).length,
    badOrdinals: (Array.isArray(entries) ? entries : []).filter(entry => !Number.isInteger(entry?.ordinal) || entry.ordinal < 0).length });
  const top = body?.topics;
  return { top: level(top), topKeys: Object.keys(body ?? {}), children: level((Array.isArray(top) ? top : []).flatMap(entry => entry.children ?? [])) };
}
/** Paths of the leaves that differ between two JSON values (timestamps are ignored: a read time is not a change of the data). */
function diffPaths(before, after, path = '') {
  const instant = value => typeof value === 'string' && /^\d{4}-\d{2}-\d{2}T/u.test(value);
  if (before !== null && after !== null && typeof before === 'object' && typeof after === 'object') {
    return [...new Set([...Object.keys(before), ...Object.keys(after)])].flatMap(key => diffPaths(before[key], after[key], path + '/' + key));
  }
  return before === after || (instant(before) && instant(after)) ? [] : [`${path || '/'} (${JSON.stringify(before)} -> ${JSON.stringify(after)})`];
}
const DECK_PATH = /^\/decks\/[0-9a-f-]{36}$/;

/** The words of the product contract (docs/product/community-decks.md, «Тексты для пользователя» and the sections cited by each use). */
const WORDS = {
  consentLabel: 'Показывать мой профиль другим',
  consentStart: 'Когда профиль публичный, ваш логин видят все, кто открывает ваши публичные колоды',
  levels: ['Приватная', 'По ссылке', 'По приглашению', 'Публичная'],
  signInToCopy: 'Войдите, чтобы добавить колоду к себе',
  add: 'Добавить к себе',
  openCopy: 'Открыть мою копию',
  preparing: 'Готовим колоду…',
  notFound: 'Колода не найдена',
  notFoundBody: 'Такой колоды нет, или автор её скрыл.',
  inviteOnly: 'Колода доступна по приглашению',
  inviteOnlyBody: 'Автор открыл эту колоду только приглашённым. Попросите доступ, и автор увидит ваш запрос.',
  askAccess: 'Запросить доступ',
  requestSent: 'Запрос отправлен. Если автор согласится, колода появится по этой же ссылке.',
  sourceClosed: 'Автор закрыл исходную колоду. Ваша копия остаётся с вами, но обновлений больше не будет.',
  rotates: 'Ссылка изменится, и старые ссылки перестанут работать.'
};

const PASSES = [
  { name: 'desktop', size: { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false },
    title: 'Испанский в дороге', description: 'Слова и фразы для первой поездки: кафе, транспорт, отель.',
    materials: ['Кафе: una mesa, por favor — столик, пожалуйста', 'Транспорт: la estación — вокзал, станция', 'Отель: la llave — ключ от номера'],
    prompt: 'Введите по-испански слово «обувь»', answer: 'zapato' },
  { name: 'mobile', size: { width: 390, height: 844, deviceScaleFactor: 1, mobile: true },
    title: 'Португальский в дороге', description: 'Слова и фразы для первой поездки: рынок, пляж, гостиница.',
    materials: ['Рынок: o preço — цена, стоимость', 'Пляж: a toalha — полотенце для пляжа', 'Гостиница: a chave — ключ от комнаты'],
    prompt: 'Введите по-португальски слово «обувь»', answer: 'sapato' }
];

/** One browser tab seen as a person: page helpers, real keyboard, the real API with the person's own bearer (none for the guest). */
function makeActor(ctx, label, tab, bearer) {
  const { config, SafeFailure } = ctx;
  const need = (value, what) => { if (!value) throw new SafeFailure(`${label}: ${what}`); };
  const page = (body, ...args) => tab.callFunction(`async function(...args) { ${body} }`, args);
  const frames = () => page('return new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve(true))));').catch(() => false);
  const front = () => tab.call('Page.bringToFront');
  const press = async (name, modifiers = 0) => {
    const [key, code, windowsVirtualKeyCode] = KEYS[name];
    const event = { key, code, windowsVirtualKeyCode, modifiers };
    await front();
    const down = name === 'Enter' ? { type: 'keyDown', text: '\r', unmodifiedText: '\r', ...event }
      : name === 'Space' ? { type: 'keyDown', text: ' ', unmodifiedText: ' ', ...event } : { type: 'rawKeyDown', ...event };
    await tab.call('Input.dispatchKeyEvent', down);
    await tab.call('Input.dispatchKeyEvent', { type: 'keyUp', ...event });
    await frames();
  };
  const metrics = size => tab.call('Emulation.setDeviceMetricsOverride', size);
  /** Every API request this tab sent: path with codes and ids masked, whether the PAGE set an Authorization header, and the status that came back. */
  const wire = [];
  tab.on('Network.requestWillBeSent', event => {
    const url = new URL(event.request.url);
    if (url.origin !== config.frontend || !url.pathname.startsWith('/api/')) return;
    wire.push({ id: event.requestId, method: event.request.method, status: null,
      path: url.pathname.replace(/\/public\/decks\/[1-9A-HJ-NP-Za-km-z]{10}/u, '/public/decks/{code}').replace(/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}/giu, '{id}'),
      authorization: Object.keys(event.request.headers).some(name => name.toLowerCase() === 'authorization') });
  });
  tab.on('Network.responseReceived', event => {
    const entry = wire.find(candidate => candidate.id === event.requestId);
    if (entry) entry.status = event.response.status;
  });
  /** The newest request of this tab to `path` (masked, as in {@link wire}). */
  const lastRequest = (path, method = 'GET') => [...wire].reverse().find(entry => entry.path === path && entry.method === method) ?? null;
  /** The visible text of the page. */
  const body = () => page('return document.body.innerText;');
  const has = text => page('return document.body.innerText.includes(args[0]);', text);
  const read = (selector, index = 0) => page('return document.querySelectorAll(args[0])[args[1]]?.innerText?.replace(/\\s+/g, " ").trim() ?? null;', selector, index);
  const count = selector => page('return document.querySelectorAll(args[0]).length;', selector);
  const location = () => page('return location.pathname;');
  const search = () => page('return location.search;');
  /** Moves the focus to the first element matching `selector` (and, if given, whose trimmed text is `text`). */
  const focus = async (selector, text = null) => {
    const done = await page(`const e = [...document.querySelectorAll(args[0])].find(x => args[1] === null || x.textContent.trim() === args[1]);
      if (!(e instanceof HTMLElement)) return false;
      e.scrollIntoView({ block: 'center', behavior: 'instant' }); e.focus(); return document.activeElement === e;`, selector, text);
    need(done, `cannot focus ${selector}${text === null ? '' : ' «' + text + '»'}`);
  };
  /** A real Enter on the element (a keyboard user's activation). */
  const enter = async (selector, text = null) => { await focus(selector, text); await press('Enter'); };
  /**
   * Presses real Tab from the top of the document until the focused element satisfies `predicate` (page-side source of a function of
   * the element). Returns how many presses it took and whether the browser considered the focus keyboard-visible.
   */
  const tabTo = async (predicate, what, max = 80) => {
    await front();
    await page(`document.activeElement?.blur?.(); window.scrollTo(0, 0); document.body.tabIndex = -1; document.body.focus(); document.body.removeAttribute('tabindex'); return true;`);
    for (let presses = 1; presses <= max; presses++) {
      await press('Tab');
      const state = await page(`const e = document.activeElement;
        return e && e !== document.body && (${predicate})(e) ? { visible: e.matches(':focus-visible') } : null;`);
      if (state !== null) return { presses, focusVisible: state.visible };
    }
    throw new SafeFailure(`${label}: Tab did not reach ${what} in ${max} presses`);
  };
  const overflow = () => page('return document.documentElement.scrollWidth > document.documentElement.clientWidth;');
  const api = (method, path, payload, headers = {}) => page(`const init = { method: args[0], credentials: 'omit', cache: 'no-store', headers: { ...args[3] } };
      if (args[4] !== null) init.headers.Authorization = args[4];
      if (args[2] !== null) { init.body = JSON.stringify(args[2]); init.headers['Content-Type'] = 'application/json'; }
      const response = await fetch(args[5] + args[1], init);
      const text = await response.text();
      return { status: response.status, etag: response.headers.get('etag'), text, body: text && (response.headers.get('content-type') ?? '').includes('json') ? JSON.parse(text) : null };`,
  method, path, payload ?? null, headers, bearer === null ? null : 'Bearer ' + bearer, config.frontend);
  return { label, tab, bearer, wire, lastRequest, need, page, frames, front, press, metrics, body, has, read, count, location, search, focus, enter, tabTo, overflow, api };
}

export async function runCommunity(ctx) {
  const { config, record, SafeFailure, until, exists, navigate, fill, submit, clickText, saveScreenshot, saveFullScreenshot, setStep,
    ownerTab, ownerBearer, memberTab, memberBearer, openGuestTab } = ctx;
  const owner = makeActor(ctx, 'owner', ownerTab, ownerBearer);
  const member = makeActor(ctx, 'member', memberTab, memberBearer);
  const failures = [];
  const gaps = [];
  const started = Date.now();

  const shoot = async (actor, name, { full = false } = {}) => {
    try { await (full ? saveFullScreenshot : saveScreenshot)(name, actor.tab); } catch { /* the verdict is the assertion, not the picture */ }
  };

  // ---- the guest: a new browser context, no cookies, no storage ------------------------------------------------------------------
  const guestTab = await openGuestTab();
  const guest = makeActor(ctx, 'guest', guestTab.tab, null);

  const stage = async (pass, name, depends, body) => {
    const key = `community_${pass.name}_${name}`;
    setStep(key);
    const broken = depends.find(dependency => pass.failed.has(dependency));
    if (broken !== undefined) {
      record(key, { state: 'skipped', because: `community_${pass.name}_${broken} failed` });
      pass.failed.add(name);
      return null;
    }
    const begun = Date.now();
    try {
      const detail = (await body()) ?? {};
      record(key, { state: 'passed', durationMs: Date.now() - begun, ...detail });
      return detail;
    } catch (error) {
      const reason = error instanceof SafeFailure ? error.message : 'driver: ' + String(error?.message ?? error).slice(0, 160);
      pass.failed.add(name);
      failures.push({ stage: key, reason });
      record(key, { state: 'failed', reason, durationMs: Date.now() - begun });
      for (const actor of [owner, member, guest]) await shoot(actor, `failure-community-${pass.name}-${name}-${actor.label}.png`);
      await writeFile(join(config.output, `failure-${key}.txt`), reason + '\n').catch(() => {});
      return null;
    }
  };

  // ---- 0. the public profile consent of the owner (once) -------------------------------------------------------------------------
  let ownerLogin = null;
  const consent = await (async () => {
    setStep('community_consent');
    const begun = Date.now();
    try {
      await owner.metrics(PASSES[0].size);
      await navigate('/profile', owner.tab);
      await until(() => exists('#profile-username', owner.tab), 'profile form absent');
      ownerLogin = await owner.page('return document.querySelector("#profile-username").value;');
      owner.need(/^[A-Za-z0-9_.-]{3,50}$/u.test(ownerLogin), 'the fixture account has no profile login (the precondition of a public profile)');
      await until(() => exists('app-public-profile-settings input[role="switch"]', owner.tab), 'public profile switch absent');
      const before = await owner.page(`const root = document.querySelector('app-public-profile-settings');
        const box = label => [...root.querySelectorAll('label')].find(node => node.textContent.trim() === label)?.querySelector('input');
        return { switchLabel: root.querySelector('label.is-switch')?.textContent.trim(), switchOn: root.querySelector('input[role="switch"]').checked,
          switchDisabled: root.querySelector('input[role="switch"]').disabled,
          fields: ['Имя', 'Фото', 'О себе'].map(label => ({ label, checked: box(label)?.checked, disabled: box(label)?.disabled })),
          text: root.querySelector('#public-profile-consent-text')?.textContent.trim() };`);
      owner.need(before.switchLabel === WORDS.consentLabel, `the switch reads «${before.switchLabel}»`);
      owner.need(before.text?.startsWith(WORDS.consentStart), 'the consent text is not the approved one');
      owner.need(!before.switchOn && !before.switchDisabled, 'the consent must start off and be switchable once the login exists');
      owner.need(before.fields.every(field => field.checked === false && field.disabled === true), 'the per-field boxes must start unticked and disabled: ' + JSON.stringify(before.fields));
      await owner.focus('app-public-profile-settings input[role="switch"]');
      await owner.press('Space');
      await until(() => owner.page(`return document.querySelector('app-public-profile-settings input[role="switch"]').checked
        && document.querySelector('app-public-profile-settings .preview app-author-chip .login')?.textContent.trim() === '@' + args[0];`, ownerLogin),
      'switching the profile on by keyboard was not saved and previewed');
      const identity = await owner.page(`const response = await fetch(args[0] + '/api/accounts/me/public-profile', { credentials: 'omit', headers: { Authorization: args[1] } });
        return { status: response.status, body: await response.json() };`, config.identity, 'Bearer ' + ownerBearer);
      owner.need(identity.status === 200 && identity.body.enabled === true && identity.body.publishReady === true
        && identity.body.showDisplayName === false && identity.body.showAvatar === false && identity.body.showBio === false,
      'Identity does not report a ready public profile with every field off: ' + JSON.stringify(identity.body));
      record('community_consent', { state: 'passed', durationMs: Date.now() - begun, switchByKeyboard: true, fieldsStartOffAndDisabled: true,
        approvedTextShown: true, identityPublishReady: true });
      return true;
    } catch (error) {
      const reason = error instanceof SafeFailure ? error.message : 'driver: ' + String(error?.message ?? error).slice(0, 160);
      failures.push({ stage: 'community_consent', reason });
      record('community_consent', { state: 'failed', reason });
      await shoot(owner, 'failure-community-consent-owner.png');
      return false;
    }
  })();

  for (const spec of PASSES) {
    const pass = { ...spec, failed: new Set() };
    if (!consent) { record(`community_${pass.name}`, { state: 'skipped', because: 'community_consent failed' }); continue; }
    const world = { deckPath: null, deckId: null, titles: [], code: null, link: null, newCode: null, copyPath: null, ownerInsights: null };
    const publicationState = async () => {
      const answer = await owner.api('GET', `/api/decks/${world.deckId}/publication`, null, {}, ownerBearer);
      owner.need(answer.status === 200, 'publication state answered ' + answer.status);
      return answer.body;
    };
    for (const actor of [owner, member, guest]) await actor.metrics(pass.size);

    // ---- 1. owner: deck, materials, a public deck through the hub ---------------------------------------------------------------
    await stage(pass, 'publish', [], async () => {
      await owner.front();
      await owner.metrics(PASSES[0].size);          // authoring in the editor is not what is measured here: write at desktop width
      await navigate('/decks/new', owner.tab);
      await fill('#create-title', pass.title, owner.tab);
      await fill('#create-description', pass.description, owner.tab);
      await submit(owner.tab);
      await until(async () => DECK_PATH.test((await owner.location()).split('?')[0]) && await exists('#deck-detail-title', owner.tab), 'deck creation did not reach its hub');
      world.deckPath = (await owner.location()).split('?')[0];
      world.deckId = world.deckPath.split('/').at(-1);
      let firstKey = null;
      for (const text of pass.materials) {
        await navigate(world.deckPath + '/materials/new', owner.tab);
        await until(() => exists('.ProseMirror[contenteditable="true"]', owner.tab), 'new-material editor absent');
        owner.need(await owner.page(`const editor = document.querySelector('.ProseMirror[contenteditable="true"]');
          if (!(editor instanceof HTMLElement)) return false;
          editor.focus(); const selection = getSelection(); const range = document.createRange();
          range.selectNodeContents(editor); selection.removeAllRanges(); selection.addRange(range); return true;`), 'editor cannot take focus');
        await owner.tab.call('Input.insertText', { text });
        await until(() => owner.has('Все изменения сохранены'), 'draft acknowledgement absent');
        owner.need(await clickText('button', 'Опубликовать', owner.tab), 'material publication unavailable');
        await until(async () => /^\/decks\/[0-9a-f-]{36}\/materials\/[0-9a-f-]{36}$/.test(await owner.location()) && await owner.has(text), 'material did not reach Browse');
        firstKey ??= (await owner.location()).split('/').at(-1);
      }
      // One exercise through the authenticated API, like the Study fixture of the base flow; its answer must never reach a reader.
      const deck = await owner.api('GET', `/api/decks/${world.deckId}`, null, {}, ownerBearer);
      const item = await owner.api('GET', `/api/decks/${world.deckId}/items/${firstKey}`, null, {}, ownerBearer);
      owner.need(deck.status === 200 && item.status === 200, 'deck or material read failed');
      const paragraph = item.body.document?.root?.content?.find(candidate => candidate.type === 'paragraph');
      owner.need(paragraph?.id, 'the first material has no paragraph to quote');
      const exercise = await owner.api('POST', `/api/decks/${world.deckId}/exercises`, {
        commandId: crypto.randomUUID(), expectedDeckRevisionId: deck.body.revisionId,
        objective: { operation: 'create', title: 'Слово: обувь' },
        exercise: { type: 'FREE_RESPONSE', schemaVersion: 2, enabled: true,
          subject: { memberKey: item.body.memberKey, itemRevisionId: item.body.itemRevisionId },
          content: { prompt: [{ kind: 'TEXT', text: pass.prompt }],
            reference: [{ kind: 'MATERIAL', memberKey: item.body.memberKey, itemRevisionId: item.body.itemRevisionId, nodeId: paragraph.id }], responseInput: 'TEXT' },
          answerKey: { kind: 'TEXT', accepted: [pass.answer], normalization: ['UNICODE_NFC', 'TRIM', 'CASE_FOLD'], matchingMode: 'STRICT' },
          evaluatorPolicy: { id: 'deterministic-text', version: '1' } } }, { 'If-Match': deck.etag }, ownerBearer);
      owner.need(exercise.status === 201, 'exercise creation answered ' + exercise.status);

      // The access picker of the hub, with the real keyboard. At the pass's own viewport from here on.
      await owner.metrics(pass.size);
      await navigate(world.deckPath, owner.tab);
      await until(() => exists('app-access-controls input[type="radio"]', owner.tab), 'the access picker did not load');
      const hub = await owner.page(`return { legend: document.querySelector('app-access-controls legend')?.textContent.trim(),
        levels: [...document.querySelectorAll('app-access-controls label.segment .text')].map(node => node.textContent.trim()),
        checked: document.querySelector('app-access-controls input[type="radio"]:checked')?.parentElement.textContent.trim(),
        heading: document.querySelector('section.publication h2')?.textContent.trim() };`);
      owner.need(JSON.stringify(hub.levels) === JSON.stringify(WORDS.levels), `the levels read ${JSON.stringify(hub.levels)}`);
      owner.need(hub.checked === 'Приватная', 'a new deck must start «Приватная»');
      await owner.focus('app-access-controls input[type="radio"]:checked');
      for (let step = 0; step < 3; step++) await owner.press('ArrowRight');
      await until(() => owner.page(`return document.querySelector('app-access-controls input[type="radio"]:checked')?.parentElement.textContent.trim() === 'Публичная'
        && document.querySelector('app-access-controls .change-title')?.textContent.trim() === 'Изменить доступ: Публичная';`), 'ArrowRight x3 did not stage «Публичная»');
      owner.need(!(await exists('app-publication-checklist', owner.tab)), 'the checklist must open only on «Сделать публичной…»');
      await owner.enter('app-access-controls .change-actions .button.primary', 'Сделать публичной…');
      await until(() => exists('app-publication-checklist', owner.tab), 'the checklist did not open');
      const items = () => owner.page(`return [...document.querySelectorAll('app-publication-checklist li.checklist-item')].map(li => ({
        title: li.querySelector('.checklist-title')?.textContent.trim(), state: li.getAttribute('data-state') }));`);
      await until(async () => (await items()).length === 7, 'the checklist has not seven items');
      let state = Object.fromEntries((await items()).map(entry => [entry.title, entry.state]));
      owner.need(state['Описание'] === 'done' && state['Публичный профиль'] === 'done' && state['Картинки'] === 'done',
        'description, profile and pictures should already be ready: ' + JSON.stringify(state));
      const beforePublish = await publicationState();
      let topicVia = 'suggestion';
      let publishedVia = 'checklist';
      if (state['Тема'] !== 'done' && await exists('app-publication-checklist li.checklist-item:nth-child(2) .field-error', owner.tab)) {
        // The directory did not load in the page. Name the cause from the wire: a request the page sent without a bearer is a product gap
        // (the public deck cannot be published through the UI); anything else is a failure with the server's own answer for comparison.
        const sent = owner.lastRequest('/api/topics');
        const probe = await owner.api('GET', '/api/topics', null, {}, ownerBearer);
        if (sent !== null && sent.authorization === false && sent.status === 401 && probe.status === 200) {
          gaps.push({ id: 'topics-request-sent-without-bearer', pass: pass.name, where: 'checklist «Сделать колоду публичной», item «Тема»',
            seen: 'the page shows «Не удалось загрузить список тем.» and «Загрузить темы снова»',
            evidence: `GET /api/topics: the page sent no Authorization header and got ${sent.status}; the same request with the owner's bearer answers ${probe.status}`,
            cause: 'frontend/src/app/auth.interceptor.ts isCredentialTarget() lists no `/topics` among learningExact/learningRoots', effect: 'a deck cannot be made «Публичная» in the UI' });
          publishedVia = 'api-fallback';
        } else {
          throw new SafeFailure(`owner: the topic directory did not load in the checklist; the page sent ${JSON.stringify(sent)}, the same GET /api/topics answers ${probe.status}, body shape ${JSON.stringify(topicShape(probe.body))}`);
        }
      } else if (state['Тема'] !== 'done') {
        if (await exists('app-publication-checklist .suggest button', owner.tab)) await owner.enter('app-publication-checklist .suggest button');
        else {
          topicVia = 'select';
          await owner.focus('app-publication-checklist [role="combobox"]');
          await owner.press('ArrowDown');
          await until(() => exists('[role="option"]', owner.tab), 'the topic list did not open');
          await owner.press('ArrowDown'); await owner.press('Enter');
        }
        await until(async () => (await items()).find(entry => entry.title === 'Тема')?.state === 'done', 'the topic was not accepted');
      }
      const languageShown = await owner.page(`return document.querySelector('app-publication-checklist li:nth-of-type(3) [role="combobox"]')?.textContent.trim() ?? null;`);
      let languageVia = 'detected';
      if (publishedVia === 'checklist') {
        state = Object.fromEntries((await items()).map(entry => [entry.title, entry.state]));
        if (state['Язык'] !== 'done') {
          languageVia = 'select';
          await owner.focus('app-publication-checklist li:nth-of-type(3) [role="combobox"]');
          await owner.press('ArrowDown');
          await until(() => exists('[role="option"]', owner.tab), 'the language list did not open');
          await owner.press('ArrowDown'); await owner.press('Enter');
          await until(async () => (await items()).find(entry => entry.title === 'Язык')?.state === 'done', 'the language was not accepted');
        }
        await until(() => owner.page(`return document.querySelector('.checklist-actions .button.primary')?.getAttribute('aria-disabled') === null;`), 'the submit button stayed disabled');
        await shoot(owner, `community-${pass.name}-checklist-owner.png`, { full: true });
        await owner.enter('.checklist-actions .button.primary', 'Опубликовать и сделать публичной');
      } else {
        // The same command the checklist would send (contracts/decks/README.md, «Save»), with the bearer the page failed to attach.
        await shoot(owner, `community-${pass.name}-checklist-owner.png`, { full: true });
        const topics = await owner.api('GET', '/api/topics', null, {}, ownerBearer);
        const leaves = topics.body.topics.flatMap(topic => topic.children.length === 0 ? [topic.topicId] : topic.children.map(child => child.topicId));
        const topicId = beforePublish.suggested?.topicIds?.[0] ?? leaves[0];
        const current = await owner.api('GET', `/api/decks/${world.deckId}/publication`, null, {}, ownerBearer);
        const saved = await owner.api('PUT', `/api/decks/${world.deckId}/publication`, { commandId: crypto.randomUUID(), visibility: 'PUBLIC',
          metadata: { topicId, contentLanguage: 'ru', targetLanguage: null, level: null, tags: [] }, requestsEnabled: true,
          publish: { expectedHeadRevisionId: current.body.headRevisionId, releaseNote: null } }, { 'If-Match': current.etag }, ownerBearer);
        owner.need(saved.status === 200, 'the API fallback of the publication answered ' + saved.status + ' ' + saved.text.slice(0, 160));
        await navigate(world.deckPath, owner.tab);
        await until(() => exists('section.publication .link-text', owner.tab), 'the hub does not show the public link after the API publication');
      }
      await until(async () => (await publicationState()).visibility === 'PUBLIC', 'the deck did not become public', 30000);
      const published = await publicationState();
      owner.need(/^[1-9A-HJ-NP-Za-km-z]{10}$/u.test(published.publicCode ?? ''), 'public code has the wrong shape');
      owner.need(/^\/d\/[1-9A-HJ-NP-Za-km-z]{10}\/[a-z0-9-]+$/u.test(published.link ?? ''), 'the public link is not /d/{code}/{slug}: ' + published.link);
      world.code = published.publicCode;
      world.link = published.link;
      await until(() => owner.page(`return [...document.querySelectorAll('section.publication .link-text')].some(node => node.textContent.includes(args[0]));`, world.code), 'the hub does not show the public link');
      const rows = await owner.page(`return [...document.querySelectorAll('app-selectable-material-list li.item-row .row-link')].map(node => node.textContent.trim());`);
      owner.need(rows.length === pass.materials.length, `the hub lists ${rows.length} materials`);
      world.titles = rows;
      owner.need(!(await owner.overflow()), 'the hub overflows horizontally after publication');
      await shoot(owner, `community-${pass.name}-published-owner.png`, { full: true });
      return { materials: rows.length, publishedVia, topicVia, languageVia, suggestedLanguage: beforePublish.suggested?.contentLanguage ?? null, suggestedTopics: beforePublish.suggested?.topicIds?.length ?? 0, languageShown, link: published.link.replace(world.code, '{code}'), keyboardLevelPick: true };
    });

    // ---- 2. the guest, in a new browser context ---------------------------------------------------------------------------------
    await stage(pass, 'guest', ['publish'], async () => {
      await guest.front();
      await guest.metrics(pass.size);
      const slug = world.link.split('/').at(-1);
      // A wrong slug and a missing slug are both replaced by the canonical address.
      await navigate(`/d/${world.code}/wrong-slug`, guest.tab);
      await until(() => exists('h1.hub-title', guest.tab), 'the guest hub did not render', 30000);
      await until(async () => (await guest.location()) === world.link, 'a wrong slug was not replaced by the canonical address');
      await navigate(`/d/${world.code}`, guest.tab);
      await until(async () => (await guest.location()) === world.link, 'a missing slug was not replaced by the canonical address');
      const cookies = (await guest.tab.call('Network.getCookies', { urls: [config.identity, config.frontend] })).cookies;
      guest.need(cookies.length === 0, 'the guest context holds cookies');
      guest.need(await guest.page('return sessionStorage.length === 0 && localStorage.length === 0;'), 'the guest context holds browser storage');
      const view = await guest.page(`const facts = [...document.querySelectorAll('.facts li')].map(node => node.innerText.replace(/\\s+/g, ' ').trim());
        return { title: document.querySelector('h1.hub-title')?.textContent.trim(), facts,
          chip: document.querySelector('.facts app-author-chip .login')?.textContent.trim() ?? null,
          referrer: document.querySelector('meta[name="referrer"]')?.getAttribute('content') ?? null,
          robots: document.querySelector('meta[name="robots"]')?.getAttribute('content') ?? null,
          documentTitle: document.title,
          description: document.querySelector('.hub-description')?.innerText.trim() ?? null,
          signIn: (() => { const link = document.querySelector('.primary-slot a.button.primary'); return link ? { text: link.textContent.trim(), href: link.getAttribute('href') } : null; })(),
          addButton: [...document.querySelectorAll('.primary-slot button')].map(node => node.textContent.trim()),
          forbidden: [...document.querySelectorAll('a, button')].map(node => node.textContent.trim()).filter(text => ['Учить', 'Изменить', 'Мастерская'].includes(text)) };`);
      guest.need(view.title === pass.title, `the guest sees the title «${view.title}»`);
      guest.need(view.chip === '@' + ownerLogin, `the author chip reads «${view.chip}», not @${ownerLogin}`);
      guest.need(view.facts.some(fact => fact.toLowerCase().includes('публичная')), 'the level «Публичная» is not shown: ' + JSON.stringify(view.facts));
      guest.need(view.facts.some(fact => new RegExp(`^${pass.materials.length} материал\\S* · 1 упражнени\\S*$`, 'u').test(fact)), 'the size line is wrong: ' + JSON.stringify(view.facts));
      guest.need(view.description === pass.description, 'the description is not the published one');
      guest.need(view.referrer === 'no-referrer', `meta referrer is «${view.referrer}»`);
      guest.need(view.robots === 'index,follow', `a PUBLIC deck should be indexable, robots is «${view.robots}»`);
      guest.need(view.documentTitle.startsWith(pass.title), 'the document title does not carry the deck title');
      guest.need(view.signIn?.text === WORDS.signInToCopy && /^\/login\?returnUrl=/u.test(view.signIn.href), 'the guest sign-in prompt is wrong: ' + JSON.stringify(view.signIn));
      guest.need(view.addButton.length === 0 && view.forbidden.length === 0, 'the guest sees actions of an owner: ' + JSON.stringify([view.addButton, view.forbidden]));
      // Materials: all of them, the owner's titles, in the page's list; then one opened with the keyboard.
      await until(async () => (await guest.count('app-public-materials-list li.item-row')) === pass.materials.length, 'the materials list is incomplete');
      const titles = await guest.page(`return [...document.querySelectorAll('app-public-materials-list li.item-row .row-link')].map(node => node.textContent.trim());`);
      guest.need(JSON.stringify([...titles].sort()) === JSON.stringify([...world.titles].sort()), 'public materials differ from the owner\'s: ' + JSON.stringify(titles));
      const reached = await guest.tabTo(`element => element.matches('app-public-materials-list .row-link')`, 'the first material link');
      guest.need(reached.focusVisible, 'the focus ring is not keyboard-visible on the material link');
      await guest.press('Enter');
      await until(async () => (await guest.search()).includes('material='), 'Enter on a material did not open it');
      await until(() => guest.has(pass.materials[0]), 'the opened material shows another text');
      const open = await guest.page(`return { heading: document.querySelector('#public-material-heading')?.textContent.trim(), back: document.querySelector('a.back-link')?.textContent.replace(/\\s+/g, ' ').trim() };`);
      guest.need(open.heading === `Материал 1 из ${pass.materials.length}` && open.back === '← К колоде', 'material heading or way back is wrong: ' + JSON.stringify(open));
      await shoot(guest, `community-${pass.name}-material-guest.png`);
      await guest.enter('a.back-link');
      await until(async () => !(await guest.search()).includes('material='), 'the way back did not return to the hub');
      // Exercises are plain-text previews: the prompt is there, the answer never leaves the server.
      await guest.focus('app-segmented-choice input[type="radio"]:checked');
      await guest.press('ArrowRight');
      await until(() => guest.has(pass.prompt), 'the exercise preview does not show the prompt');
      guest.need(!(await guest.has(pass.answer)), 'the accepted answer is visible to a guest');
      const raw = await guest.api('GET', `/api/public/decks/${world.code}/exercises?limit=20`, null, {}, null);
      guest.need(raw.status === 200 && !raw.text.includes(pass.answer), 'the public exercises answer carries the accepted answer or failed');
      guest.need(!(await guest.overflow()), 'the guest page overflows horizontally');
      const button = await guest.page(`const link = document.querySelector('.primary-slot a.button.primary'); return link ? link.getBoundingClientRect().height : 0;`);
      guest.need(button >= 44, `the sign-in prompt is ${button}px high`);
      await shoot(guest, `community-${pass.name}-hub-guest.png`, { full: true });
      // Not found, and the same screen for an unknown code (no oracle).
      await navigate('/d/1111111111', guest.tab);
      await until(() => guest.has(WORDS.notFound), 'an unknown code does not give «Колода не найдена»');
      guest.need(await guest.has(WORDS.notFoundBody), 'the not-found text is not the approved one');
      const wire = guest.wire.filter(entry => entry.path.startsWith('/api/public/decks'));
      guest.need(wire.length > 0 && wire.every(entry => entry.authorization === false), 'the guest sent a bearer to a public route');
      guest.need(wire.filter(entry => entry.status !== null && entry.status >= 500).length === 0, 'a public read answered 5xx: ' + JSON.stringify(wire.filter(entry => entry.status >= 500)));
      return { slugCanonicalised: true, noCookiesNoStorage: true, referrer: view.referrer, robots: view.robots, materials: titles.length, authorChip: true,
        keyboardOpenedMaterial: true, tabPresses: reached.presses, answerHidden: true, noHorizontalOverflow: true, primaryMinimumPx: Math.round(button),
        publicReads: wire.length, statuses: [...new Set(wire.map(entry => entry.status ?? 'no response'))] };
    });

    // ---- 3. the member copies the deck and starts preparing ---------------------------------------------------------------------
    await stage(pass, 'copy', ['publish'], async () => {
      await member.front();
      await member.metrics(pass.size);
      await navigate(world.link, member.tab);
      await until(() => exists('.primary-slot button.primary', member.tab), 'the signed-in view has no «Добавить к себе»', 30000);
      member.need(await member.read('.primary-slot button.primary') === WORDS.add, 'the primary action is not «Добавить к себе»');
      member.need(await member.page(`return !document.querySelector('.primary-slot a')`), 'a signed-in non-owner is offered a link instead of the copy');
      await member.page(`window.__mnemaPreparing = { seen: false, at: null };
        const visible = () => document.querySelector('app-copy-status h2')?.textContent.trim() === args[0];
        const observer = new MutationObserver(() => { if (visible()) { window.__mnemaPreparing = { seen: true, at: performance.now() }; observer.disconnect(); } });
        observer.observe(document.body, { subtree: true, childList: true, characterData: true }); return true;`, WORDS.preparing);
      const reached = await member.tabTo(`element => element.matches('.primary-slot button.primary')`, '«Добавить к себе»');
      member.need(reached.focusVisible, 'the focus ring is not keyboard-visible on «Добавить к себе»');
      const pressed = Date.now();
      await member.press('Enter');
      await until(async () => DECK_PATH.test((await member.location()).split('?')[0]), 'the copy did not open its hub', 30000);
      world.copyPath = (await member.location()).split('?')[0];
      const landedAfter = Date.now() - pressed;
      await until(() => exists('h1#deck-detail-title', member.tab), 'the copy hub has no title');
      // Preparing: bounded. The hub shows «Готовим колоду…» and then, without a reload, the ready deck.
      await until(async () => !(await exists('app-copy-status', member.tab)) && await exists('nav.hub-actions', member.tab), 'the copy was not materialized in time', 60000);
      const readyAfter = Date.now() - pressed;
      const seen = await member.page('return window.__mnemaPreparing;');
      member.need(seen.seen || readyAfter < 2500, `the hub never showed «${WORDS.preparing}» although preparation took ${readyAfter} ms`);
      // The hub leaves «Готовим колоду…» when the server says ready; its list is read again at that moment. Measure how long the list stays behind.
      const listCheck = Date.now();
      await until(() => member.page(`return document.querySelectorAll('app-selectable-material-list li.item-row').length === args[0];`, pass.materials.length), 'the ready copy never listed its materials (the hub kept showing the empty preparing list)', 20000);
      const listLagMs = Date.now() - listCheck;
      const hub = await member.page(`return { title: document.querySelector('h1#deck-detail-title')?.textContent.trim(),
        origin: document.querySelector('app-copy-origin')?.innerText.replace(/\\s+/g, ' ').trim() ?? null,
        materials: document.querySelector('#materials-heading')?.textContent.replace(/\\s+/g, ' ').trim() ?? null,
        rows: document.querySelectorAll('app-selectable-material-list li.item-row').length,
        actions: [...document.querySelectorAll('nav.hub-actions .button')].map(node => node.textContent.trim()) };`);
      member.need(hub.title === pass.title, `the copy is titled «${hub.title}»`);
      member.need(hub.origin?.startsWith(`Копия колоды «${pass.title}»`), `the source mark reads «${hub.origin}»`);
      member.need(hub.rows === pass.materials.length && hub.materials === `Материалы · ${pass.materials.length}`, `the copy lists ${hub.rows} materials (${hub.materials})`);
      member.need(hub.actions.includes('Учить'), 'the ready copy has no «Учить»');
      const detail = await member.api('GET', `/api/decks/${world.copyPath.split('/').at(-1)}`, null, {}, memberBearer);
      member.need(detail.status === 200 && detail.body.fork?.preparing === false && detail.body.fork?.failed === false && detail.body.fork?.sourceAvailable === true
        && detail.body.fork?.upstreamTitle === pass.title, 'the copy is not reported ready with its source: ' + JSON.stringify(detail.body?.fork));
      member.need(!(await member.overflow()), 'the copy hub overflows horizontally');
      await shoot(member, `community-${pass.name}-copy-ready-member.png`, { full: true });
      return { keyboardCopy: true, tabPresses: reached.presses, landedAfterMs: landedAfter, materializedAfterMs: readyAfter, preparingTextObserved: seen.seen, materialListLagMs: listLagMs,
        preparingText: WORDS.preparing, rows: hub.rows, sourceMark: true };
    });

    // ---- 3b. the member studies the copy ------------------------------------------------------------------------------------------
    await stage(pass, 'study', ['copy'], async () => {
      await member.front();
      const before = await owner.api('GET', `/api/decks/${world.deckId}/insights`, null, {}, ownerBearer);
      await navigate(world.copyPath + '/study', member.tab);
      await until(() => exists('.session-setup', member.tab), 'Study setup did not load', 30000);
      await member.focus('.session-setup [data-answer-control]');
      await member.press('Space');
      await until(async () => await exists('#study-0-answer', member.tab) || await exists('.notice.error', member.tab), 'Study did not start');
      member.need(await exists('#study-0-answer', member.tab), 'Study of the copy reached an error: ' + await member.read('.notice.error'));
      member.need(await member.has(pass.prompt), 'the copy\'s exercise is not the author\'s');
      member.need(!(await member.has(pass.answer)), 'Study leaked the accepted answer before an attempt');
      await until(() => member.page(`return document.activeElement?.id === 'study-0-answer';`), 'the answer field did not take focus');
      await shoot(member, `community-${pass.name}-study-member.png`);
      member.need(!(await member.overflow()), 'Study overflows horizontally');
      await fill('#study-0-answer', pass.answer, member.tab);
      await member.enter('button', 'Проверить ответ');
      await until(() => exists('#feedback-title', member.tab), 'no feedback for the copy\'s attempt', 30000);
      member.need(await member.page(`return document.activeElement?.id === 'feedback-title' && document.querySelector('.feedback-card.correct') !== null;`), 'feedback focus or verdict is missing');
      await shoot(member, `community-${pass.name}-study-feedback-member.png`);
      const after = await owner.api('GET', `/api/decks/${world.deckId}/insights`, null, {}, ownerBearer);
      owner.need(before.status === 200 && after.status === 200, 'insights of the source answered ' + before.status + '/' + after.status);
      const changed = diffPaths(before.body, after.body);
      owner.need(changed.length === 0, 'the learner\'s attempt on the copy changed the author\'s statistics at: ' + changed.slice(0, 6).join(', '));
      return { keyboardStart: true, answered: true, feedbackFocus: true, authorStatisticsUnchanged: true };
    });

    // ---- 4. the member opens the link again -------------------------------------------------------------------------------------------
    await stage(pass, 'reopen', ['copy'], async () => {
      await member.front();
      await navigate(world.link, member.tab);
      await until(() => exists('.primary-slot a.button.primary', member.tab), 'the second visit offers no «Открыть мою копию»', 30000);
      const slot = await member.page(`const link = document.querySelector('.primary-slot a.button.primary');
        return { text: link.textContent.trim(), href: link.getAttribute('href'), buttons: [...document.querySelectorAll('.primary-slot button')].map(node => node.textContent.trim()) };`);
      member.need(slot.text === WORDS.openCopy && slot.href === world.copyPath, 'the primary action is ' + JSON.stringify(slot));
      member.need(slot.buttons.length === 0, 'a second «Добавить к себе» is offered');
      const reached = await member.tabTo(`element => element.matches('.primary-slot a.button.primary')`, '«Открыть мою копию»');
      await member.press('Enter');
      await until(async () => (await member.location()) === world.copyPath && await exists('h1#deck-detail-title', member.tab), '«Открыть мою копию» did not open the copy');
      await until(async () => (await member.read('h1#deck-detail-title')) === pass.title, 'the opened copy is another deck');
      const again = await member.api('POST', '/api/decks/forks', { commandId: crypto.randomUUID(), sourceCode: world.code }, {}, memberBearer);
      member.need(again.status === 200 && again.body.existing === true && again.body.deckId === world.copyPath.split('/').at(-1), 'a second copy request did not return the live copy: ' + again.status);
      return { primaryAction: WORDS.openCopy, linksToCopy: true, keyboard: true, tabPresses: reached.presses, secondRequestReturnsLiveCopy: true };
    });

    // ---- 5. the owner turns the deck «По приглашению»; the member without a grant asks ----------------------------------------------
    await stage(pass, 'invite_only', ['publish'], async () => {
      await owner.front();
      await owner.metrics(pass.size);
      await navigate(world.deckPath, owner.tab);
      await until(() => exists('app-access-controls input[type="radio"]:checked', owner.tab), 'the access picker did not load');
      owner.need(await owner.page(`return document.querySelector('app-access-controls input[type="radio"]:checked')?.parentElement.textContent.trim() === 'Публичная';`), 'the deck is not shown as «Публичная»');
      await owner.focus('app-access-controls input[type="radio"]:checked');
      await owner.press('ArrowLeft');
      await until(() => owner.page(`return document.querySelector('app-access-controls .change-title')?.textContent.trim() === 'Изменить доступ: По приглашению';`), 'ArrowLeft did not stage «По приглашению»');
      const lines = await owner.page(`return [...document.querySelectorAll('app-access-controls .change-line')].map(node => node.textContent.trim());`);
      owner.need(lines.includes(WORDS.rotates), 'the consequence «ссылка изменится» is not said: ' + JSON.stringify(lines));
      await owner.enter('app-access-controls .change-actions .button.primary', 'Применить');
      await until(async () => (await publicationState()).visibility === 'INVITE', 'the level did not change', 30000);
      const invite = await publicationState();
      owner.need(invite.publicCode !== world.code, 'lowering the level kept the public code');
      owner.need(invite.link === `/d/${invite.publicCode}`, 'an invitation deck link must be /d/{code} without a slug: ' + invite.link);
      world.newCode = invite.publicCode;
      await until(() => exists('app-access-panel', owner.tab), 'the access panel (requests, invitations, people) did not appear');
      owner.need(await owner.page(`return [...document.querySelectorAll('app-access-controls label')].some(node => node.textContent.trim() === 'Принимать запросы доступа' && node.querySelector('input').checked);`), 'the requests switch is not on by default');

      // The old public link is gone for everybody, the guest included.
      await guest.front();
      await navigate(world.link, guest.tab);
      await until(() => guest.has(WORDS.notFound), 'the old public link still opens something after the level was lowered', 30000);
      guest.need(!(await guest.has(pass.title)), 'the old link leaks the deck title');
      // The new link, as a guest: an invitation-only screen with a sign-in, no title.
      await navigate(`/d/${world.newCode}`, guest.tab);
      await until(() => guest.has(WORDS.inviteOnly), 'a guest on an invitation link sees no «Колода доступна по приглашению»', 30000);
      guest.need(!(await guest.has(pass.title)), 'the invitation screen names the deck to a guest');

      // The member: the same screen with «Запросить доступ».
      await member.front();
      await navigate(`/d/${world.newCode}`, member.tab);
      await until(() => member.has(WORDS.inviteOnly), 'the member sees no «Колода доступна по приглашению»', 30000);
      member.need(await member.page(`return document.querySelector('app-access-screen h1')?.textContent.trim() === args[0];`, WORDS.inviteOnly), 'the heading is not the screen heading');
      member.need(await member.has(WORDS.inviteOnlyBody), 'the invitation text is not the approved one');
      member.need(!(await member.has(pass.title)), 'the invitation screen names the deck to someone without a grant');
      const titleless = await member.page('return document.title;');
      member.need(!titleless.includes(pass.title), 'the document title names the deck');
      member.need(await member.page(`return document.querySelector('meta[name="robots"]')?.getAttribute('content') === 'noindex' && document.querySelector('meta[name="referrer"]')?.getAttribute('content') === 'no-referrer';`), 'noindex / no-referrer are not set on the invitation screen');
      const asked = await member.tabTo(`element => element.matches('app-access-screen button.primary') && element.textContent.trim() === ${JSON.stringify(WORDS.askAccess)}`, '«Запросить доступ»');
      await member.press('Enter');
      await until(async () => await member.has(WORDS.requestSent) || await exists('.request-error', member.tab), 'neither the approved «Запрос отправлен…» text nor an error appeared', 30000);
      let requestVia = 'ui';
      if (await member.has(WORDS.requestSent)) {
        member.need(await member.page(`return document.activeElement?.classList.contains('request-sent');`), 'focus did not move to the confirmation');
      } else {
        // An error instead of the confirmation. A request the page sent without a bearer is a product gap; anything else is a failure.
        const sent = member.lastRequest('/api/public/decks/{code}/access-requests', 'POST');
        const shown = await member.read('.request-error');
        if (sent !== null && sent.authorization === false && sent.status === 401) {
          gaps.push({ id: 'access-request-sent-without-bearer', pass: pass.name, where: 'screen «Колода доступна по приглашению», button «Запросить доступ»',
            seen: `the page says «${shown}»`,
            evidence: `POST /api/public/decks/{code}/access-requests: the page sent no Authorization header and got ${sent.status}`,
            cause: 'frontend/src/app/auth.interceptor.ts isCredentialTarget() publicRead matches only /public/decks/{code}, /items, /exercises and /media; `/access-requests`, `/access-requests:withdraw` and `/api/invites:redeem` are not listed',
            effect: 'a signed-in reader cannot ask for access (and, by the same list, cannot redeem an invitation link) in the UI' });
          requestVia = 'api-fallback';
          const fallback = await member.api('POST', `/api/public/decks/${world.newCode}/access-requests`, null, {}, memberBearer);
          member.need(fallback.status === 202 && fallback.body?.status === 'PENDING', 'the API fallback of the access request answered ' + fallback.status);
        } else {
          throw new SafeFailure(`member: «Запросить доступ» ended with «${shown}»; the page sent ${JSON.stringify(sent)}`);
        }
      }
      member.need(!(await member.overflow()), 'the invitation screen overflows horizontally');
      await shoot(member, `community-${pass.name}-invite-only-member.png`);
      // The copy keeps working and says the source is closed (the code that it was copied by was rotated away).
      await navigate(world.copyPath, member.tab);
      await until(() => exists('app-copy-origin', member.tab), 'the copy has no source line', 30000);
      member.need(await member.page(`return document.querySelector('app-copy-origin')?.innerText.replace(/\\s+/g, ' ').includes(args[0]);`, WORDS.sourceClosed), 'the copy does not say the source is closed: ' + await member.read('app-copy-origin'));
      member.need(await exists('nav.hub-actions', member.tab), 'the copy stopped working');

      // The owner sees the request.
      await owner.front();
      await navigate(world.deckPath, owner.tab);
      await until(() => owner.page(`return document.querySelectorAll('app-access-requests .roster-row').length >= 1;`), 'the owner does not see the access request in the panel', 30000);
      const requests = await owner.page(`return [...document.querySelectorAll('app-access-requests .roster-row')].map(row => ({
        buttons: [...row.querySelectorAll('button')].map(node => node.textContent.trim()), hasTime: row.querySelector('time') !== null }));`);
      owner.need(requests.length === 1 && requests[0].buttons.join('|') === 'Принять|Отклонить' && requests[0].hasTime, 'the request row is wrong: ' + JSON.stringify(requests));
      await shoot(owner, `community-${pass.name}-requests-owner.png`, { full: true });
      return { oldLinkNotFound: true, codeRotated: true, linkWithoutSlug: true, guestScreen: true, requestApprovedText: requestVia === 'ui', requestVia, askedByKeyboard: true,
        tabPresses: asked.presses, sourceClosedOnCopy: true, ownerSeesRequest: requests.length, titleHidden: true };
    });

    // ---- 5b. approval: the same link now opens the deck for the member ------------------------------------------------------------------
    await stage(pass, 'approve', ['invite_only'], async () => {
      await owner.front();
      await owner.enter('app-access-requests .roster-row .button.primary', 'Принять');
      await until(() => owner.page(`return document.querySelectorAll('app-access-requests .roster-row').length === 0;`), 'the approved request stayed in the list');
      await member.front();
      await navigate(`/d/${world.newCode}`, member.tab);
      await until(() => exists('h1.hub-title', member.tab), 'after approval the same link does not open the deck', 30000);
      member.need(await member.read('h1.hub-title') === pass.title, 'the approved view shows another deck');
      member.need(await member.page(`return [...document.querySelectorAll('.facts li')].some(node => node.innerText.toLowerCase().includes('по приглашению'));`), 'the level «По приглашению» is not shown to the grantee');
      member.need(await member.read('.primary-slot a.button.primary') === WORDS.openCopy, 'the grantee who already holds a copy should be offered «Открыть мою копию»');
      return { sameLinkOpensForGrantee: true, level: 'По приглашению' };
    });
  }

  // ---- teardown ------------------------------------------------------------------------------------------------------------------
  await guestTab.close();
  // The base flow's last steps expect the first tab on a page that shows the profile and the logout control.
  for (const actor of [owner, member]) await navigate('/login', actor.tab).catch(() => {});
  for (const actor of [owner, member]) await actor.metrics({ width: 1280, height: 900, deviceScaleFactor: 1, mobile: false }).catch(() => {});
  record('community_summary', { state: failures.length === 0 && gaps.length === 0 ? 'passed' : 'failed', passes: PASSES.map(spec => spec.name), durationMs: Date.now() - started,
    failures: failures.length, gaps });
  return { failures, gaps };
}
