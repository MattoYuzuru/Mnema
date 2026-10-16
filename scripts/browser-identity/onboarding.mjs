// Real-browser check of the onboarding tour (OB-E1: «Начало пути», the survey, the branch tours, pause and resume, the hold to finish, the skip and the
// replay) with an accessibility check of every surface, on desktop (1440 px), a phone (390 px) and a narrow window (320 px at 200 %). Runs with
// `--onboarding` against the real Identity and Learning jars (the Learning of this run has `learning.onboarding.enabled` and an `eligible-from` instant
// before the run, see run.py); node 24 built-ins only.
//
// Who is who: every account is registered through the real form and lives in a browser context of its own (`openGuestTab`: no shared cookie, so a second
// context is a second device). `journey` walks the own-deck branch end to end; `hold` finishes the tour early on a phone; `skip` takes «Мне это знакомо»
// in a narrow window; `community` walks the community branch up to the study gate over decks seeded through the real Learning API. Everything the person
// does goes through the real UI with CDP mouse and keyboard events; the few API reads (the journey, the learning profile, the deck list) only look at
// what the server stored. A broken stage is a finding for the product, never something to work around: it is recorded as a failed `onboarding_<stage>`
// result with the UI's own reason and a screenshot, and the stages that depend on it are skipped. A step the tour should have brought the learner to but
// did not is a `gap`: the scenario goes on by hand (as a learner would, through the router) so that later steps still produce evidence, and the run is red.
import { writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { makeActor } from './community.mjs';
import { seedCatalogDeck } from './catalog-seed.mjs';

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const DESKTOP = { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false };
const PHONE = { width: 390, height: 844, deviceScaleFactor: 1, mobile: true };
const NARROW = { width: 320, height: 900, deviceScaleFactor: 2, mobile: false };
const KEYS = { Enter: ['Enter', 'Enter', 13], Space: [' ', 'Space', 32], Tab: ['Tab', 'Tab', 9], Escape: ['Escape', 'Escape', 27], ArrowRight: ['ArrowRight', 'ArrowRight', 39] };

/** Text as the screen reads it: runs of white space and no-break spaces are one space. */
const norm = text => String(text ?? '').replace(/[\s ]+/gu, ' ').trim();
/** The same, as a prelude of the code that runs in the page. */
const PAGE_NORM = "const norm = s => (s ?? '').replace(/[\\s\\u00a0]+/g, ' ').trim();\n";
/** A short description of an element for a message: tag, id, class and the start of its text. */
const PAGE_DESCRIBE = "const describe = e => e ? e.tagName.toLowerCase() + (e.id ? '#' + e.id : '') + (typeof e.className === 'string' && e.className ? '.' + e.className.split(' ')[0] : '') + ' «' + norm(e.getAttribute && e.getAttribute('aria-label') || e.textContent).slice(0, 40) + '»' : null;\n";

/** The words of the product, copied from `core/onboarding/onboarding-texts.ts` (the texts the owner approved) with no-break spaces as plain spaces. */
const T = {
  eyebrow: 'Начало пути',
  welcomeTitle: 'Mnema помогает надолго запомнить то, что вы учите',
  start: 'Познакомиться — 2 минуты',
  skipAll: 'Мне это знакомо — сразу к делу',
  skipToast: 'Знакомство пропущено. Пройти его можно в профиле, а все возможности Mnema собраны в «Пути».',
  skipToastLink: 'Открыть «Путь»',
  goal: 'Для чего вам Mnema?',
  interests: 'Что хотите изучать?',
  experience: 'Учились ли вы по карточкам раньше?',
  days: 'Сколько дней в неделю хотите заниматься?',
  progressLabel: 'Пройдено вопросов',
  skip: 'Пропустить',
  next: 'Дальше',
  done: 'Готово',
  back: 'Назад',
  branchTitle: 'С чего начнём?',
  branchOwn: 'Создать свою',
  branchCommunity: 'Найти готовую колоду',
  branchSample: 'Посмотреть на примере',
  branchStamp: 'Советуем для начала',
  begin: 'Начать',
  replayTitle: 'С какой колоды начнём?',
  replayNew: 'Новая колода',
  newbie: 'Впервые',
  pause: 'Знакомство на паузе',
  resume: 'Продолжить знакомство',
  finish: 'Завершить знакомство',
  finishArmed: 'Удерживайте 2 секунды, чтобы завершить знакомство',
  finishToast: 'Знакомство завершено. Пройти его снова можно в профиле.',
  missing: 'Этого места сейчас нет на экране. Продолжим со следующего шага.',
  replay: 'Пройти знакомство заново',
  ownTitles: ['Назовите колоду', 'Первый материал', 'Первое упражнение', 'Пройдите первое занятие'],
  communityTitles: ['Поиск и полка «Для вас»', 'Что внутри колоды', 'Добавьте колоду к себе', 'Пройдите первое занятие'],
  doneEyebrow: 'Знакомство завершено',
  doneTitle: 'Начало положено',
  doneAction: 'К колоде'
};
const LOGIN = { journey: 'onb_journey', hold: 'onb_hold', skip: 'onb_skip', community: 'onb_community' };

/** Four decks of one author that the catalogue lists (ten materials each, an exercise, public): the branch choice needs four listed decks. */
const SEEDED = [
  { title: 'Испанские глаголы', description: 'Сорок самых частых глаголов с примерами и упражнениями.', topicId: 'spanish', content: 'ru', target: 'es', level: 'A1', tags: ['глаголы'] },
  { title: 'Английские фразовые глаголы', description: 'Фразовые глаголы с примерами из разговорной речи.', topicId: 'english', content: 'ru', target: 'en', level: 'A2', tags: ['глаголы'] },
  { title: 'Everyday English conversation', description: 'Short dialogues for the shop, the bank and the office.', topicId: 'english', content: 'en', target: 'en', level: 'B1', tags: ['dialogues'] },
  { title: 'SQL для аналитиков', description: 'Запросы, соединения и агрегаты на живых примерах.', topicId: 'sql-data', content: 'ru', target: null, level: 'INTERMEDIATE', tags: ['sql'] }
];

export async function runOnboarding(ctx) {
  const { config, record, SafeFailure, exists, navigate, fill, click, clickText, saveScreenshot, setStep, openGuestTab, signUp, signIn, signOut } = ctx;
  const failures = [];
  const findings = [];
  const actors = [];
  const devices = [];
  const ranOn = new Set();
  const started = Date.now();
  const a11y = {};
  const world = {};
  let at = '';

  const need = (value, what) => { if (!value) throw new SafeFailure(what); };
  const shoot = async (actor, name) => { try { await saveScreenshot(name, actor.tab); } catch { /* the verdict is the assertion, not the picture */ } };

  const stage = async (name, depends, body) => {
    const key = `onboarding_${name}`;
    setStep(key);
    const broken = depends.find(dependency => ranOn.has('!' + dependency));
    if (broken !== undefined) {
      record(key, { state: 'skipped', because: `onboarding_${broken} failed` });
      ranOn.add('!' + name);
      return null;
    }
    const begun = Date.now();
    at = '';
    try {
      const detail = (await body()) ?? {};
      record(key, { state: 'passed', durationMs: Date.now() - begun, ...detail });
      return detail;
    } catch (error) {
      const reason = (error instanceof SafeFailure ? error.message : 'driver: ' + String(error?.message ?? error).slice(0, 240)) + (at === '' ? '' : ` [after: ${at}]`);
      ranOn.add('!' + name);
      failures.push({ stage: key, reason });
      record(key, { state: 'failed', reason, durationMs: Date.now() - begun });
      for (const actor of actors) await shoot(actor, `failure-onboarding-${name}-${actor.label}.png`);
      await writeFile(join(config.output, `failure-${key}.txt`), reason + '\n').catch(() => {});
      return null;
    }
  };
  /** The tour should have brought the learner here and did not: the run is red, the scenario goes on by hand. */
  const gap = (id, reason) => {
    failures.push({ stage: 'gap:' + id, reason });
    record('onboarding_gap_' + id, { state: 'gap', reason });
  };
  /** Something true of the product that is worth reporting and does not break the contract of a step. */
  const finding = (id, summary, details = {}) => {
    findings.push({ id, summary, ...details });
    record('onboarding_finding_' + id, { state: 'finding', summary, ...details });
  };

  // ---- plumbing ---------------------------------------------------------------------------------------------------------------------
  /** Polls `probe` until `accept(result)`; a failure names the last thing seen. A navigation that replaces the page is just one more poll. */
  const waitFor = async (what, probe, accept, timeout = 15_000) => {
    const deadline = Date.now() + timeout;
    let last = null;
    while (Date.now() < deadline) {
      try { last = await probe(); if (accept(last)) return last; } catch (error) { last = { error: String(error?.message ?? error).slice(0, 80) }; }
      await sleep(150);
    }
    throw new SafeFailure(`${what} (last seen: ${JSON.stringify(last)?.slice(0, 400)})`);
  };
  const read = (actor, body, ...args) => actor.page(PAGE_NORM + PAGE_DESCRIBE + body, ...args);
  const learning = async (method, path, { bearer = null, payload, headers = {} } = {}) => {
    at = `${method} ${path.split('?')[0].replace(/[0-9a-f-]{36}/gu, '{id}')}`;
    const response = await fetch(config.learningOrigin + path, {
      method, signal: AbortSignal.timeout(25_000),
      headers: { ...(bearer === null ? {} : { Authorization: 'Bearer ' + bearer }), ...(payload === undefined ? {} : { 'Content-Type': 'application/json' }), ...headers },
      body: payload === undefined ? undefined : JSON.stringify(payload)
    });
    const text = await response.text();
    let body = null;
    try { body = text === '' ? null : JSON.parse(text); } catch { body = null; }
    return { status: response.status, etag: response.headers.get('etag'), body, text };
  };
  /** The journey as the server stored it, read with the person's own bearer. */
  const journey = async actor => {
    const answer = await actor.api('GET', '/api/onboarding');
    need(answer.status === 200 && answer.body?.journey, `GET /api/onboarding answered ${answer.status}`);
    return answer.body.journey;
  };
  /** The last requests this page sent to the journey and what came back: the first thing to read when the server is not where the page says it is. */
  const journeyWire = actor => actor.wire.filter(entry => entry.path.startsWith('/api/onboarding') && entry.method !== 'GET').slice(-8).map(entry => `${entry.method} ${entry.path.replace('/api/onboarding', '')} ${entry.status ?? '…'}`);
  const serverJourney = async (actor, what, accept, timeout = 15_000) => {
    try { return await waitFor(`the server journey ${what}`, () => journey(actor), accept, timeout); } catch (error) {
      if (error instanceof SafeFailure) throw new SafeFailure(`${error.message} [the page's last requests to the journey: ${JSON.stringify(journeyWire(actor))}]`);
      throw error;
    }
  };
  /** Holds a key down for `ms` without a character (no click is made by the browser) and releases it. */
  const holdKey = async (actor, name, ms) => {
    const [key, code, windowsVirtualKeyCode] = KEYS[name];
    const event = { key, code, windowsVirtualKeyCode, modifiers: 0 };
    await actor.front();
    await actor.tab.call('Input.dispatchKeyEvent', { type: 'rawKeyDown', ...event });
    await sleep(ms);
    await actor.tab.call('Input.dispatchKeyEvent', { type: 'keyUp', ...event });
    await actor.frames();
  };
  /**
   * Presses real Tab from where the focus is until the focused element satisfies `predicate` (page-side source of `(element, norm) => boolean`).
   * Returns the number of presses; it does not start from the top of the document, so a dialog that took the focus keeps it.
   */
  const tabUntil = async (actor, predicate, what, max = 60) => {
    for (let presses = 1; presses <= max; presses++) {
      await actor.press('Tab');
      const hit = await read(actor, `const e = document.activeElement; return Boolean(e && e !== document.body && (${predicate})(e, norm));`);
      if (hit) return presses;
    }
    throw new SafeFailure(`${actor.label}: Tab did not reach ${what} in ${max} presses`);
  };
  const buttonNamed = text => `(e, norm) => e.tagName === 'BUTTON' && norm(e.getAttribute('aria-label') || e.textContent) === ${JSON.stringify(text)}`;
  const radioInput = '(e) => e.tagName === \'INPUT\' && e.type === \'radio\'';
  /**
   * A mouse click at the middle of an element, after checking that the element is what the mouse would hit there: when something else covers the
   * point (the card of the tour over the control it points at) the click is refused with the name of the cover, because a learner could not make it.
   */
  const realClick = async (actor, selector, text = null) => {
    const found = `[...document.querySelectorAll(args[0])].find(x => args[1] === null || norm(x.textContent) === args[1] || norm(x.getAttribute('aria-label')) === args[1])`;
    const scrolled = await read(actor, `const e = ${found};
      if (!(e instanceof HTMLElement)) return false;
      e.scrollIntoView({ block: 'nearest', inline: 'nearest', behavior: 'instant' });
      return true;`, selector, text);
    need(scrolled, `${actor.label}: cannot find ${selector}${text === null ? '' : ' «' + text + '»'}`);
    // The cut-out of the tour follows a scroll on the next frame; a learner's eye is at least that slow.
    await actor.frames();
    await actor.frames();
    const measure = () => read(actor, `const e = ${found};
      // The first line box of an element that wraps (a link in a card): its middle is on the text, the middle of the bounding box may not be.
      const r = e.getClientRects()[0] ?? e.getBoundingClientRect(); const x = r.left + r.width / 2, y = r.top + r.height / 2;
      const top = document.elementFromPoint(x, y);
      return { x, y, width: r.width, height: r.height, hit: top === e || e.contains(top), cover: top ? describe(top) : null, inert: Boolean(e.closest('[inert]')) };`, selector, text);
    // A card that has just arrived plays its reveal (the ink-in of the grid) and is not under the mouse yet: a learner waits for it, up to a second and a half.
    let point = await measure();
    for (let attempt = 0; attempt < 10 && !point.hit && !point.inert; attempt++) { await sleep(150); point = await measure(); }
    need(point.width > 0 && point.height > 0, `${actor.label}: ${selector} has no size`);
    need(!point.inert, `${actor.label}: ${selector}${text === null ? '' : ' «' + text + '»'} is inert`);
    need(point.hit, `${actor.label}: ${selector}${text === null ? '' : ' «' + text + '»'} is covered by ${point.cover}`);
    await actor.front();
    await actor.tab.call('Input.dispatchMouseEvent', { type: 'mouseMoved', x: point.x, y: point.y });
    await actor.tab.call('Input.dispatchMouseEvent', { type: 'mousePressed', x: point.x, y: point.y, button: 'left', clickCount: 1 });
    await actor.tab.call('Input.dispatchMouseEvent', { type: 'mouseReleased', x: point.x, y: point.y, button: 'left', clickCount: 1 });
    await actor.frames();
    return point;
  };
  /** A mouse press that is held for `ms` before it is released at the same point. */
  const holdMouse = async (actor, selector, ms) => {
    const point = await read(actor, `const e = document.querySelector(args[0]); if (!e) return null; const r = e.getBoundingClientRect(); return { x: r.left + r.width / 2, y: r.top + r.height / 2 };`, selector);
    need(point !== null, `${actor.label}: cannot find ${selector}`);
    await actor.front();
    await actor.tab.call('Input.dispatchMouseEvent', { type: 'mouseMoved', x: point.x, y: point.y });
    await actor.tab.call('Input.dispatchMouseEvent', { type: 'mousePressed', x: point.x, y: point.y, button: 'left', clickCount: 1 });
    await sleep(ms);
    await actor.tab.call('Input.dispatchMouseEvent', { type: 'mouseReleased', x: point.x, y: point.y, button: 'left', clickCount: 1 });
    await actor.frames();
  };
  /** The accessibility tree as Chrome builds it: the nodes that are not ignored, with role, name, value and value text. */
  const axTree = async actor => {
    await actor.front();
    await actor.tab.call('Accessibility.enable').catch(() => {});
    const { nodes } = await actor.tab.call('Accessibility.getFullAXTree');
    return nodes.filter(node => !node.ignored).map(node => ({
      role: node.role?.value ?? '', name: norm(node.name?.value), value: node.value?.value ?? null,
      valuetext: node.properties?.find(property => property.name === 'valuetext')?.value?.value ?? null,
      level: node.properties?.find(property => property.name === 'level')?.value?.value ?? null }));
  };
  const named = (tree, role) => tree.filter(node => node.role === role).map(node => node.name);
  const motion = (actor, value) => actor.tab.call('Emulation.setEmulatedMedia', { features: value === null ? [] : [{ name: value[0], value: value[1] }] });
  const runningAnimations = actor => actor.page('return document.getAnimations().filter(animation => animation.playState === "running").length;');

  // ---- what the page shows -----------------------------------------------------------------------------------------------------------
  /** The first-entry dialog («Начало пути», a survey card, the branch choice), or null when it is not drawn. */
  const firstEntry = actor => read(actor, `const d = document.querySelector('app-first-entry dialog.first-entry');
    if (!d) return null;
    const heading = d.querySelector('h1, h2');
    const bar = d.querySelector('[role="progressbar"]');
    const rect = e => { const r = e.getBoundingClientRect(); return { top: r.top, bottom: r.bottom, left: r.left, right: r.right, width: r.width, height: r.height }; };
    return { open: d.open, modal: d.matches(':modal'), label: norm(d.getAttribute('aria-label')), heading: norm(heading?.textContent), headingFocused: document.activeElement === heading,
      active: describe(document.activeElement), stars: d.querySelectorAll('.sky .star').length, eyebrow: norm(d.querySelector('.eyebrow')?.textContent),
      counter: norm(d.querySelector('.progress .eyebrow')?.textContent) || null,
      progress: bar ? { now: bar.getAttribute('aria-valuenow'), max: bar.getAttribute('aria-valuemax'), text: bar.getAttribute('aria-valuetext'), name: bar.getAttribute('aria-label') } : null,
      buttons: [...d.querySelectorAll('button')].map(b => ({ text: norm(b.textContent), disabled: b.getAttribute('aria-disabled') === 'true', height: Math.round(b.getBoundingClientRect().height * 10) / 10,
        visible: b.getBoundingClientRect().width > 0 && b.getBoundingClientRect().bottom <= innerHeight + 1 && b.getBoundingClientRect().top >= -1 })),
      radios: [...d.querySelectorAll('input[type="radio"]')].map(r => ({ value: r.value, checked: r.checked, label: norm(r.closest('label')?.textContent) })),
      text: norm(d.textContent),
      overflow: { page: document.documentElement.scrollWidth > document.documentElement.clientWidth, dialog: d.scrollWidth > d.clientWidth + 1 },
      widths: { client: document.documentElement.clientWidth, scroll: document.documentElement.scrollWidth, dialogClient: d.clientWidth, dialogScroll: d.scrollWidth } };`);
  /** The step card of the tour (or the lack of it), with what is around it: the veil, the frame, the inert elements, the pause plaque. */
  const tourState = actor => read(actor, `const d = document.querySelector('app-tour-host dialog.tour-card');
    const heading = d?.querySelector('h2');
    const frame = document.querySelector('app-tour-scrim .frame');
    const fr = frame ? frame.getBoundingClientRect() : null;
    const dr = d ? d.getBoundingClientRect() : null;
    const plaque = document.querySelector('app-tour-pause .plaque');
    const hold = d?.querySelector('app-hold-to-delete-button button.hold-button');
    return { card: Boolean(d), open: Boolean(d?.open), modal: Boolean(d?.matches(':modal')), role: d?.getAttribute('role') ?? null,
      name: d ? norm(document.getElementById(d.getAttribute('aria-labelledby') ?? '')?.textContent) : null,
      counter: d ? norm(d.querySelector('.tour-head .eyebrow')?.textContent) : null, body: d ? norm(d.querySelector('.tour-body')?.textContent) : null,
      gate: d?.querySelector('.gate') ? norm(d.querySelector('.gate').textContent) : null,
      headingFocused: Boolean(d && document.activeElement === heading), active: describe(document.activeElement), sheet: Boolean(d?.classList.contains('sheet')),
      buttons: d ? [...d.querySelectorAll('button')].map(b => norm(b.getAttribute('aria-label') || b.querySelector('.hold-label')?.textContent || b.textContent)) : [],
      hold: hold ? { text: norm(hold.querySelector('.hold-label')?.textContent), pressed: hold.getAttribute('aria-pressed'), label: hold.getAttribute('aria-label'), glyph: Boolean(hold.querySelector('.hold-glyph')) } : null,
      live: norm((d ?? document).querySelector('[role="status"][aria-live]')?.textContent), scrim: Boolean(document.querySelector('app-tour-scrim')),
      frame: fr ? { x: Math.round(fr.x), y: Math.round(fr.y), width: Math.round(fr.width), height: Math.round(fr.height) } : null,
      cardBox: dr ? { left: Math.round(dr.left), top: Math.round(dr.top), right: Math.round(dr.right), bottom: Math.round(dr.bottom), width: Math.round(dr.width) } : null,
      inert: document.querySelectorAll('[inert]').length,
      plaque: plaque ? { label: norm(plaque.getAttribute('aria-label')), button: norm(plaque.querySelector('button')?.textContent), focused: document.activeElement === plaque.querySelector('button') } : null,
      dialogs: [...document.querySelectorAll('dialog[open]')].map(x => describe(x)), location: location.pathname + location.search,
      overflow: document.documentElement.scrollWidth > document.documentElement.clientWidth,
      widths: { client: document.documentElement.clientWidth, scroll: document.documentElement.scrollWidth } };`);
  /** What is inert: how many elements, whether the target of the step and the tour's own surfaces are live, whether the masthead is not. */
  const inertState = (actor, targetSelector) => read(actor, `const target = document.querySelector(args[0]);
    const inert = [...document.querySelectorAll('[inert]')];
    const header = document.querySelector('header');
    return { count: inert.length, tags: inert.map(e => e.tagName.toLowerCase()).slice(0, 12), targetLive: Boolean(target) && !target.closest('[inert]'),
      tourLive: !document.querySelector('app-tour-host')?.closest('[inert]') && (!document.querySelector('app-tour-card') || !document.querySelector('app-tour-card').closest('[inert]')),
      mastheadInert: header ? Boolean(header.closest('[inert]')) : null };`, targetSelector);
  /** Nothing of the tour is on the screen: no veil, no card, no plaque, no inert element, no open dialog. */
  const noVeil = async actor => {
    const state = await tourState(actor);
    return { clear: !state.scrim && !state.card && !state.plaque && state.inert === 0 && state.dialogs.length === 0, state };
  };
  const bodyHas = (actor, text) => actor.page('return document.body.innerText.includes(args[0]);', text);
  const location = actor => actor.page('return location.pathname + location.search;');
  const decks = async actor => {
    const answer = await actor.api('GET', '/api/decks?limit=50');
    need(answer.status === 200, `GET /api/decks answered ${answer.status}`);
    return answer.body?.items ?? answer.body?.decks ?? [];
  };

  /** A device is a browser context of its own with a freshly registered (or signed-in) account. */
  const newDevice = async (label, login, size, { register = true } = {}) => {
    const device = await openGuestTab();
    devices.push(device);
    await device.tab.call('Emulation.setDeviceMetricsOverride', size);
    // What the page's own requests to the journey got from the server, from the first one on (the sign-in's own page load asks before the account's actor exists).
    const journeyAnswers = [];
    device.tab.on('Network.responseReceived', event => {
      const path = new URL(event.response.url).pathname;
      if (path.startsWith('/api/onboarding')) journeyAnswers.push(`${event.response.status} ${path}`);
    });
    const bearer = register ? await signUp(device.tab, login) : await signIn(device.tab, login);
    const actor = makeActor(ctx, label, device.tab, bearer);
    actor.journeyAnswers = journeyAnswers;
    actors.push(actor);
    return { actor, device, bearer };
  };
  /** A failure to find a card says what the server holds for the account and where the learner is: the first thing a reader asks. */
  const withServerView = async (actor, promise) => {
    try { return await promise; } catch (error) {
      if (!(error instanceof SafeFailure)) throw error;
      const raw = await actor.api('GET', '/api/onboarding').catch(() => null);
      const view = raw?.body;
      const state = view ? { enabled: view.enabled, journey: view.journey && { state: view.journey.state, eligible: view.journey.eligible, autoOffer: view.journey.autoOffer, stepKey: view.journey.stepKey, branch: view.journey.branch } } : { status: raw?.status ?? null };
      throw new SafeFailure(`${error.message} [server: ${JSON.stringify(state)}; the page's own requests to /api/onboarding: ${JSON.stringify(actor.journeyAnswers ?? [])}; at ${await location(actor).catch(() => '?')}]`);
    }
  };
  /** Waits for the dialog of the first entry to show `heading`. */
  const awaitCard = (actor, heading, timeout = 20_000) => withServerView(actor, waitFor(`the first-entry card «${heading}»`, () => firstEntry(actor), state => state?.open && state.heading === norm(heading), timeout));
  /** Waits for the step card of the tour that carries `title`. */
  const awaitStep = (actor, title, counter, timeout = 20_000) => withServerView(actor, waitFor(`the tour step «${title}»`, () => tourState(actor),
    state => state.card && state.open && state.name === norm(title) && (counter === undefined || state.counter === counter), timeout));
  /** After a gate the page the tour wants is `expected`; when the learner is somewhere else the tour has not brought them, and the scenario goes on by hand. */
  const expectRoute = async (actor, id, expected, how) => {
    const matches = path => (expected instanceof RegExp ? expected.test(path) : path === expected);
    try { await waitFor(`the route ${expected}`, () => location(actor), matches, 6_000); return true; } catch { /* reported below */ }
    const seen = await location(actor);
    gap(id, `${how}: the tour step expects ${expected} but the learner is at ${seen} (the page's own navigation replaced the tour's)`);
    await shoot(actor, `failure-onboarding-gap-${id}.png`);
    return false;
  };
  /** Moves the single-page app to `path` the way a link does (history entry and a popstate the router listens to), keeping the tour engine alive. */
  const spaGo = async (actor, path) => {
    await actor.page(`history.pushState({}, '', args[0]); window.dispatchEvent(new PopStateEvent('popstate', { state: {} })); return true;`, path);
    await waitFor(`the route ${path}`, () => location(actor), seen => seen === path, 8_000);
  };

  // ---- the community seed (runs in the background while the own-deck branch is walked) -------------------------------------------------
  let seeding = null;
  const fixtureCall = async (actor, path, payload) => {
    at = `fixture ${path}`;
    const answer = await actor.page(`const response = await fetch(args[0], { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(args[1]) });
      const text = await response.text(); return { status: response.status, body: text === '' ? null : JSON.parse(text) };`, path, payload);
    need(answer.status === 200, `${path} answered ${answer.status}`);
    return answer.body;
  };
  const seedCommunity = async () => {
    const A = world.journeyActor;
    const [author] = await fixtureCall(A, '/__fixture/catalog-accounts', { names: ['onboarding_author'] });
    const sent = await A.page(`const headers = { Authorization: 'Bearer ' + args[1], 'Content-Type': 'application/json' };
      const response = await fetch(args[0] + '/api/accounts/me/public-profile', { method: 'PUT', credentials: 'omit', headers,
        body: JSON.stringify({ enabled: true, showDisplayName: false, showAvatar: false, showBio: false, textVersion: '2026-10-10' }) });
      return { status: response.status, body: await response.json() };`, config.identity, author.token);
    need(sent.status === 200 && sent.body.publishReady === true, 'the public profile consent was not accepted: ' + sent.status);
    const decks = [];
    for (const spec of SEEDED) decks.push(await seedCatalogDeck(learning, need, author.token, spec, { visibility: 'PUBLIC' }));
    await waitFor('the catalogue lists the four seeded decks', () => learning('GET', '/api/public/community/facets', { bearer: author.token }), answer => answer.body?.listedCount === SEEDED.length, 120_000);
    return decks;
  };
  const ensureSeed = () => {
    if (seeding === null) { seeding = seedCommunity(); seeding.catch(() => {}); }
    return seeding;
  };

  // ==== 1. «Начало пути»: the welcome of a new account (desktop, then a phone and a narrow window) ===================================================
  await stage('welcome', [], async () => {
    const { actor: A, bearer } = await newDevice('journey', LOGIN.journey, DESKTOP);
    world.journeyActor = A; world.journeyBearer = bearer;
    const s = await awaitCard(A, T.welcomeTitle, 30_000);
    // The twinkle of the stars is finite: look at the animations while the welcome is young, with the ordinary motion setting first.
    const baselineAnimations = await runningAnimations(A);
    A.need(s.modal, 'the welcome is not a modal dialog');
    A.need(s.eyebrow === T.eyebrow, `the eyebrow reads «${s.eyebrow}»`);
    A.need(s.headingFocused, `the focus is not on the heading of the welcome: ${s.active}`);
    A.need(s.label === norm(T.welcomeTitle), `the dialog is named «${s.label}»`);
    A.need(s.stars > 0, 'the welcome has no stars');
    A.need(s.buttons.length === 2 && s.buttons[0].text === T.start && s.buttons[1].text === T.skipAll, 'the buttons are ' + JSON.stringify(s.buttons.map(b => b.text)));
    A.need(s.buttons.every(b => b.visible && !b.disabled), 'a welcome button is not visible or is disabled');
    A.need(Math.abs(s.buttons[0].height - s.buttons[1].height) < 0.5, `the two buttons differ in height: ${s.buttons[0].height} and ${s.buttons[1].height}`);
    A.need(!s.overflow.page && !s.overflow.dialog, 'the welcome overflows at 1440 px');
    const server = await journey(A);
    A.need(server.state === 'OFFERED' && server.eligible === true && server.autoOffer === true, 'the server journey is ' + JSON.stringify(server));
    // Accessibility tree: the dialog has a name, both buttons are named, the heading is a level-one heading, nothing outside the dialog is reachable.
    const tree = await axTree(A);
    A.need(named(tree, 'dialog').includes(norm(T.welcomeTitle)), 'the AX tree has no dialog named by the welcome heading: ' + JSON.stringify(named(tree, 'dialog')));
    const buttons = named(tree, 'button');
    A.need(buttons.length === 2 && buttons.includes(T.start) && buttons.includes(T.skipAll), 'the AX buttons are ' + JSON.stringify(buttons));
    A.need(tree.some(node => node.role === 'heading' && node.name === norm(T.welcomeTitle) && node.level === 1), 'the AX tree has no level-one heading with the title');
    await shoot(A, 'onboarding-welcome-1440.png');
    // Reduced motion: no running animation (the stars do not twinkle), and the stars are still drawn.
    await motion(A, ['prefers-reduced-motion', 'reduce']);
    await A.frames();
    const reducedAnimations = await runningAnimations(A);
    A.need(reducedAnimations === 0, `${reducedAnimations} animations are running under prefers-reduced-motion: reduce`);
    await shoot(A, 'onboarding-welcome-reduced-motion-1440.png');
    await motion(A, null);
    // Forced colors: the sky is not drawn, the text and the buttons stay readable.
    await motion(A, ['forced-colors', 'active']);
    await A.frames();
    const forced = await read(A, `const d = document.querySelector('app-first-entry dialog.first-entry');
      const sky = d.querySelector('.sky'); const title = d.querySelector('h1');
      return { active: matchMedia('(forced-colors: active)').matches, sky: sky ? getComputedStyle(sky).display : 'absent', titleColor: getComputedStyle(title).color,
        ground: getComputedStyle(d.querySelector('app-night-sky')).backgroundColor, buttons: [...d.querySelectorAll('button')].map(b => ({ border: getComputedStyle(b).borderTopStyle, width: getComputedStyle(b).borderTopWidth })) };`);
    A.need(forced.active, 'forced-colors emulation did not take effect');
    A.need(forced.sky === 'none' || forced.sky === 'absent', `the sky is drawn in forced colors (${forced.sky})`);
    A.need(forced.titleColor !== forced.ground, 'the title and the ground are the same color in forced colors');
    await shoot(A, 'onboarding-welcome-forced-colors-1440.png');
    await motion(A, null);
    // Phone and narrow window: no horizontal scroll, both buttons visible and of one height.
    const layouts = {};
    for (const [name, size, picture] of [['390', PHONE, 'onboarding-welcome-390.png'], ['320', NARROW, 'onboarding-welcome-320-at-200-percent.png']]) {
      await A.metrics(size);
      await A.frames();
      const view = await firstEntry(A);
      A.need(!view.overflow.page && !view.overflow.dialog, `the welcome overflows at ${name} px: ${JSON.stringify(view.widths)}`);
      A.need(view.buttons.every(b => b.visible), `a welcome button is outside the window at ${name} px`);
      A.need(Math.abs(view.buttons[0].height - view.buttons[1].height) < 0.5, `the buttons differ in height at ${name} px`);
      layouts[name] = view.widths;
      await shoot(A, picture);
    }
    await A.metrics(DESKTOP);
    await A.frames();
    a11y.welcome = { dialogName: norm(T.welcomeTitle), buttons, headingLevel: 1, focusOnHeading: true, starsOnlyHere: true, reducedMotionRunningAnimations: reducedAnimations,
      baselineRunningAnimations: baselineAnimations, forcedColors: forced, narrow: layouts };
    return { stars: s.stars, equalButtonHeightPx: s.buttons[0].height, serverState: server.state, autoOffer: server.autoOffer, axDialog: true, baselineRunningAnimations: baselineAnimations,
      reducedMotionRunningAnimations: reducedAnimations, forcedColors: true, noHorizontalScroll: ['1440', '390', '320'] };
  });

  // ==== 2. the survey by keyboard only, a reload in the middle, the branch choice =================================================================
  await stage('survey', ['welcome'], async () => {
    const A = world.journeyActor;
    await A.front();
    // The welcome: Tab to the primary button, Enter.
    const toStart = await tabUntil(A, buttonNamed(T.start), '«Познакомиться — 2 минуты»', 6);
    await A.press('Enter');
    const narrowSurvey = {};
    const goal = await awaitCard(A, T.goal);
    A.need(goal.headingFocused, `the focus is not on the heading of the goal card: ${goal.active}`);
    A.need(goal.stars === 0, 'the survey card has stars');
    A.need(goal.counter === 'Вопрос 1 из 4', `the counter reads «${goal.counter}»`);
    A.need(goal.progress && goal.progress.text === '0 из 4' && goal.progress.now === '0' && goal.progress.max === '4' && goal.progress.name === T.progressLabel, 'the progress bar is ' + JSON.stringify(goal.progress));
    const goalTree = await axTree(A);
    A.need(named(goalTree, 'dialog').includes(norm(T.goal)), 'the AX dialog is not named by the goal question: ' + JSON.stringify(named(goalTree, 'dialog')));
    const bar = goalTree.find(node => node.role === 'progressbar');
    A.need(bar && bar.name === T.progressLabel && (bar.valuetext === '0 из 4' || bar.value === 0), 'the AX progressbar is ' + JSON.stringify(bar));
    A.need(named(goalTree, 'radio').length >= 2 && named(goalTree, 'radio').every(name => name !== ''), 'a goal radio has no accessible name');
    A.need(named(goalTree, 'button').every(name => name !== ''), 'a survey button has no accessible name');
    a11y.survey = { dialogName: norm(T.goal), progressbar: { name: bar.name, valuetext: bar.valuetext, value: bar.value }, radios: named(goalTree, 'radio'), buttons: named(goalTree, 'button') };
    await shoot(A, 'onboarding-survey-goal-1440.png');
    await serverJourney(A, 'is at survey.goal', j => j.state === 'IN_PROGRESS' && j.stepKey === 'survey.goal');
    // Answer the goal: Tab to the first option, Space, Tab to «Дальше», Enter.
    await tabUntil(A, radioInput, 'a goal option', 8);
    await A.press('Space');
    const chosen = await read(A, `return [...document.querySelectorAll('app-first-entry input[type="radio"]')].find(r => r.checked)?.value ?? null;`);
    A.need(chosen !== null, 'Space did not choose the focused goal');
    await tabUntil(A, buttonNamed(T.next), '«Дальше»', 10);
    await A.press('Enter');
    // The interests: skipped with the keyboard. (Without the community the card is not asked and the survey goes on to the experience.)
    const afterGoal = await waitFor('the card after the goal', () => firstEntry(A), s => s?.open && (s.heading === norm(T.interests) || s.heading === norm(T.experience)));
    const interestsAsked = afterGoal.heading === norm(T.interests);
    let tabsToSkip = null;
    if (interestsAsked) {
      A.need(afterGoal.counter === 'Вопрос 2 из 4' && afterGoal.progress?.text === '1 из 4', 'the interests card counts ' + JSON.stringify([afterGoal.counter, afterGoal.progress?.text]));
      A.need(afterGoal.headingFocused, `the focus is not on the heading of the interests card: ${afterGoal.active}`);
      A.need(afterGoal.stars === 0, 'the interests card has stars');
      // The widest card of the survey (the picker of topics) at 390 and 320 px: nothing scrolls sideways.
      for (const [name, size, picture] of [['390', PHONE, 'onboarding-survey-interests-390.png'], ['320', NARROW, 'onboarding-survey-interests-320-at-200-percent.png']]) {
        await A.metrics(size);
        await A.frames();
        const narrow = await firstEntry(A);
        A.need(!narrow.overflow.page && !narrow.overflow.dialog, `the interests card overflows at ${name} px: ${JSON.stringify(narrow.widths)}`);
        narrowSurvey[name] = narrow.widths;
        await shoot(A, picture);
      }
      await A.metrics(DESKTOP);
      await A.frames();
      tabsToSkip = await tabUntil(A, buttonNamed(T.skip), '«Пропустить»', 90);
      await A.press('Enter');
    }
    const experience = await awaitCard(A, T.experience);
    A.need(experience.stars === 0, 'the experience card has stars');
    A.need(experience.counter === 'Вопрос 3 из 4' && experience.progress?.text === '2 из 4', 'the experience card counts ' + JSON.stringify([experience.counter, experience.progress?.text]));
    A.need(experience.headingFocused, `the focus is not on the heading of the experience card: ${experience.active}`);
    // Reload in the middle: the same card opens again (the server keeps the position).
    await serverJourney(A, 'is at survey.experience', j => j.state === 'IN_PROGRESS' && j.stepKey === 'survey.experience');
    await A.tab.call('Page.reload', { ignoreCache: true });
    const reopened = await awaitCard(A, T.experience, 30_000);
    A.need(reopened.counter === 'Вопрос 3 из 4' && reopened.progress?.text === '2 из 4', 'after the reload the card counts ' + JSON.stringify([reopened.counter, reopened.progress?.text]));
    A.need(reopened.headingFocused, `after the reload the focus is not on the heading: ${reopened.active}`);
    await shoot(A, 'onboarding-survey-experience-after-reload-1440.png');
    // «Впервые»: Tab to the first option, Space, «Дальше».
    await tabUntil(A, radioInput, 'an experience option', 8);
    await A.press('Space');
    const picked = await read(A, `return [...document.querySelectorAll('app-first-entry input[type="radio"]')].find(r => r.checked)?.closest('label')?.textContent.trim() ?? null;`);
    A.need(picked === T.newbie, `Space chose «${picked}» instead of «${T.newbie}»`);
    await tabUntil(A, buttonNamed(T.next), '«Дальше»', 10);
    await A.press('Enter');
    // Days: 3 (the first option is 2; ArrowRight moves the choice to 3), «Готово».
    const days = await awaitCard(A, T.days);
    A.need(days.stars === 0, 'the days card has stars');
    A.need(days.counter === 'Вопрос 4 из 4' && days.progress?.text === '3 из 4', 'the days card counts ' + JSON.stringify([days.counter, days.progress?.text]));
    A.need(days.buttons.some(b => b.text === T.done) && !days.buttons.some(b => b.text === T.next), 'the last card has no «Готово»');
    await tabUntil(A, radioInput, 'a days option', 8);
    await A.press('ArrowRight');
    const three = await read(A, `return [...document.querySelectorAll('app-first-entry input[type="radio"]')].find(r => r.checked)?.value ?? null;`);
    A.need(three === '3', `the days choice is ${three} instead of 3`);
    await tabUntil(A, buttonNamed(T.done), '«Готово»', 10);
    await A.press('Enter');
    // The branch choice: «Создать свою» only. The catalogue is empty, there is no template.
    const choose = await awaitCard(A, T.branchTitle);
    A.need(choose.progress === null && choose.counter === null, 'the branch choice carries the survey progress');
    A.need(choose.stars === 0, 'the branch choice has stars');
    A.need(choose.radios.length === 1 && choose.radios[0].value === 'OWN' && choose.radios[0].label.startsWith(T.branchOwn), 'the branch options are ' + JSON.stringify(choose.radios));
    A.need(!choose.text.includes(T.branchCommunity), '«Найти готовую колоду» is offered with an empty catalogue');
    A.need(!choose.text.includes(T.branchSample), '«Посмотреть на примере» is offered with no template');
    A.need(!choose.text.includes(T.branchStamp), 'the sample stamp is shown');
    A.need(choose.buttons.some(b => b.text === T.begin), 'the branch choice has no «Начать»');
    await shoot(A, 'onboarding-branch-choice-1440.png');
    for (const [name, size] of [['390', PHONE], ['320', NARROW]]) {
      await A.metrics(size);
      await A.frames();
      const narrow = await firstEntry(A);
      A.need(!narrow.overflow.page && !narrow.overflow.dialog, `the branch choice overflows at ${name} px: ${JSON.stringify(narrow.widths)}`);
    }
    await A.metrics(DESKTOP);
    await A.frames();
    const keptJourney = await serverJourney(A, 'is at choose with the answers', j => j.stepKey === 'choose' && j.experience === 'NEW' && j.weeklyTarget === 3);
    const profile = await A.api('GET', '/api/learning-profile');
    A.need(profile.status === 200, `GET /api/learning-profile answered ${profile.status}`);
    world.profileBefore = profile.text;
    world.deckCountBefore = (await decks(A)).length;
    // The catalogue is empty now; from here the four decks that make the community branch are seeded while the own-deck branch is walked.
    ensureSeed();
    a11y.keyboardSurvey = { narrowInterestsCard: narrowSurvey, welcomeStartAfterTabs: toStart, goalChosenWithSpace: chosen, interestsSkippedWithTabs: tabsToSkip, experienceWithSpace: picked, daysWithArrowRight: three, finishedWithEnter: true };
    return { cards: interestsAsked ? 4 : 3, interestsAsked, keyboardOnly: true, reloadReopenedSameCard: true, progressbar: ['0 из 4', '1 из 4', '2 из 4', '3 из 4'], branchOptions: choose.radios.map(r => r.value),
      communityHidden: true, sampleHidden: true, starsOnlyOnWelcome: true, serverExperience: keptJourney.experience, serverWeeklyTarget: keptJourney.weeklyTarget };
  });

  // ==== 3. the own-deck branch end to end: name, material, exercise, study, finale ===========================================================
  await stage('own_details', ['survey'], async () => {
    const A = world.journeyActor;
    await tabUntil(A, buttonNamed(T.begin), '«Начать»', 12);
    await A.press('Enter');
    await awaitStep(A, T.ownTitles[0], 'Шаг 1 из 4', 30_000);
    // The card is modal until the target is on the screen (a step that has not found its place yet), and not modal once it has: the target must be live.
    const step = await withServerView(A, waitFor('the card of an action-gated step leaves its target live', () => tourState(A), s => s.card && !s.modal && s.frame !== null && s.inert > 0, 15_000));
    A.need(step.role === 'dialog', `the card has role ${step.role}`);
    A.need(step.headingFocused, `the focus is not on the heading of the card: ${step.active}`);
    // The step card owns its polite status region: the first card is announced by the focus on its heading, the live region speaks the counter of the next ones.
    A.need(step.live === '', `the live region of the first step says «${step.live}»`);
    A.need(step.gate !== null && step.gate.includes('Нажмите «Создать колоду», чтобы продолжить'), `the gate line reads «${step.gate}»`);
    A.need(step.buttons.includes(T.finish) && !step.buttons.includes(T.back), 'the buttons of the first step are ' + JSON.stringify(step.buttons));
    A.need(step.scrim && step.frame !== null, 'the veil or the frame of the cut-out is not drawn');
    A.need(step.location === '/decks/new', `the first step is at ${step.location}`);
    const server = await serverJourney(A, 'is at own.details', j => j.state === 'IN_PROGRESS' && j.stepKey === 'own.details' && j.branch === 'OWN');
    const target = '[data-tour-anchor="own.details"]';
    const inert = await inertState(A, target);
    A.need(inert.count > 0 && inert.targetLive && inert.tourLive && inert.mastheadInert === true, 'inert state of the action-gated step: ' + JSON.stringify(inert));
    const box = await read(A, `const r = document.querySelector(args[0]).getBoundingClientRect(); return { x: r.x, y: r.y, width: r.width, height: r.height };`, target);
    A.need(step.frame.x <= box.x && step.frame.y <= box.y && step.frame.x + step.frame.width >= box.x + box.width && step.frame.y + step.frame.height >= box.y + box.height
      && step.frame.width - box.width < 40, `the cut-out ${JSON.stringify(step.frame)} does not surround the form ${JSON.stringify(box)}`);
    const tree = await axTree(A);
    A.need(named(tree, 'dialog').includes(norm(T.ownTitles[0])), 'the AX dialog of the step is ' + JSON.stringify(named(tree, 'dialog')));
    A.need(named(tree, 'button').includes(T.finish) && named(tree, 'button').every(name => name !== ''), 'AX buttons of the step: ' + JSON.stringify(named(tree, 'button')));
    const linksWhileInert = named(tree, 'link').length;
    await shoot(A, 'onboarding-tour-own-details-1440.png');
    // Forced colors: the frame around the target becomes an outline in Highlight.
    await motion(A, ['forced-colors', 'active']);
    await A.frames();
    const forced = await read(A, `const frame = document.querySelector('app-tour-scrim .frame'); const veil = document.querySelector('app-tour-scrim .veil'); const s = getComputedStyle(frame);
      return { active: matchMedia('(forced-colors: active)').matches, outlineStyle: s.outlineStyle, outlineWidth: s.outlineWidth, outlineColor: s.outlineColor, shadow: s.boxShadow, veilFill: getComputedStyle(veil).fill };`);
    A.need(forced.active && forced.outlineStyle === 'solid' && parseFloat(forced.outlineWidth) >= 2 && forced.shadow === 'none', 'the frame in forced colors is ' + JSON.stringify(forced));
    await shoot(A, 'onboarding-tour-own-details-forced-colors-1440.png');
    await motion(A, null);
    // A phone and a narrow window: the card is a bottom sheet, nothing scrolls sideways.
    const layouts = {};
    for (const [name, size, picture] of [['390', PHONE, 'onboarding-tour-own-details-390.png'], ['320', NARROW, 'onboarding-tour-own-details-320-at-200-percent.png']]) {
      await A.metrics(size);
      await A.frames();
      await sleep(300);
      const view = await tourState(A);
      A.need(!view.overflow, `the tour step overflows at ${name} px: ${JSON.stringify(view.widths)}`);
      A.need(view.sheet && view.cardBox.bottom >= size.height - 40, `the card is not a bottom sheet at ${name} px: ${JSON.stringify(view.cardBox)}`);
      layouts[name] = view.widths;
      await shoot(A, picture);
    }
    await A.metrics(DESKTOP);
    await A.frames();
    a11y.actionGatedStep = { dialogName: norm(T.ownTitles[0]), buttons: named(tree, 'button'), nonModal: true, inert, linksWhileInert, forcedColors: forced, narrow: layouts };
    world.linksWhileInert = linksWhileInert;
    return { role: step.role, counter: step.counter, liveRegion: step.live, gate: step.gate, focusOnHeading: true, inert: inert.count, targetLive: true, frameSurroundsTarget: true,
      serverStep: server.stepKey, serverBranch: server.branch, axDialog: true, forcedColorsOutline: forced.outlineWidth, sheetAt390and320: true };
  });

  await stage('own_create_deck', ['own_details'], async () => {
    const A = world.journeyActor;
    await fill('#create-title', 'Мой первый набор', A.tab);
    await fill('#create-description', 'Слова для знакомства с Mnema', A.tab);
    // The tour's gate is the real button, clicked with the mouse: the card must not cover it.
    await realClick(A, 'form button[type="submit"]', 'Создать колоду');
    await waitFor('the deck hub or the material page', () => location(A), path => /^\/decks\/[0-9a-f-]{36}(\/materials\/new)?(\?write=1)?$/.test(path), 20_000);
    const deckId = (await location(A)).match(/^\/decks\/([0-9a-f-]{36})/)[1];
    world.deckId = deckId;
    await awaitStep(A, T.ownTitles[1], 'Шаг 2 из 4');
    const materialRoute = `/decks/${deckId}/materials/new?write=1`;
    const followed = await expectRoute(A, 'own_material_route', materialRoute, 'after «Создать колоду»');
    if (!followed) await spaGo(A, materialRoute);
    const server = await serverJourney(A, 'is at own.material', j => j.stepKey === 'own.material' && j.branch === 'OWN');
    return { deckCreated: true, serverStep: server.stepKey, tourBroughtTheLearnerToTheEditor: followed };
  });

  // ---- pause on this step, the same account on a second device, resume ------------------------------------------------------------------
  await stage('pause_resume', ['own_create_deck'], async () => {
    const A = world.journeyActor;
    const step = await awaitStep(A, T.ownTitles[1], 'Шаг 2 из 4');
    A.need(step.headingFocused, `the focus is not on the heading of the second step: ${step.active}`);
    const editor = await waitFor('the material editor', () => exists('.ProseMirror[contenteditable="true"]', A.tab), Boolean, 30_000);
    const inertBefore = await inertState(A, '[data-tour-anchor="own.material"]');
    A.need(inertBefore.count > 0 && inertBefore.targetLive, 'the material step is not inert around its editor: ' + JSON.stringify(inertBefore));
    const linksBefore = named(await axTree(A), 'link').length;
    await A.press('Escape');
    const paused = await waitFor('the pause plaque', () => tourState(A), s => s.plaque !== null && !s.card, 10_000);
    A.need(paused.plaque.label === T.pause && paused.plaque.button === T.resume, 'the plaque reads ' + JSON.stringify(paused.plaque));
    A.need(paused.plaque.focused, `the focus is not on «${T.resume}»: ${paused.active}`);
    A.need(paused.inert === 0 && !paused.scrim, 'a paused tour leaves the veil or an inert element');
    const linksAfter = named(await axTree(A), 'link').length;
    A.need(linksAfter > linksBefore, `the page behind the pause is no more reachable than behind the step (${linksBefore} -> ${linksAfter} links in the AX tree)`);
    const serverPaused = await serverJourney(A, 'is PAUSED at own.material', j => j.state === 'PAUSED' && j.stepKey === 'own.material' && j.branch === 'OWN');
    await shoot(A, 'onboarding-pause-1440.png');
    // The second device: a context of its own, the same account signed in through the form.
    const { actor: A2, device } = await newDevice('journey-second-device', LOGIN.journey, DESKTOP, { register: false });
    const plaque = await waitFor('the pause plaque on the second device', () => tourState(A2), s => s.plaque !== null, 25_000);
    A2.need(plaque.location === '/decks', `the second device was moved to ${plaque.location} instead of staying at /decks`);
    A2.need(!plaque.card && !plaque.scrim && plaque.inert === 0, 'the second device shows a step instead of the plaque');
    A2.need((await firstEntry(A2)) === null, 'the second device opened the first-entry dialog');
    const untouched = await journey(A2);
    A2.need(untouched.state === 'PAUSED' && untouched.rowVersion === serverPaused.rowVersion, `the second device changed the journey: ${untouched.state} v${untouched.rowVersion} (was v${serverPaused.rowVersion})`);
    await shoot(A2, 'onboarding-second-device-plaque-1440.png');
    // Resume on the second device: the same step.
    await realClick(A2, 'app-tour-pause button', T.resume);
    const resumedOn2 = await waitFor('the step after the resume on the second device', () => tourState(A2), s => s.card && s.name === norm(T.ownTitles[1]) && s.counter === 'Шаг 2 из 4', 15_000);
    const serverAfter2 = await serverJourney(A2, 'is IN_PROGRESS at own.material after the resume', j => j.state === 'IN_PROGRESS' && j.stepKey === 'own.material');
    // The deck the learner made is not known to this device: the step cannot take them to the editor and, after four seconds, says so.
    const settled = await waitFor('the step variant on the second device', () => tourState(A2), s => s.card && (s.body === norm(T.missing) || s.location.includes('/materials/new')), 12_000);
    await shoot(A2, 'onboarding-second-device-resumed-1440.png');
    if (settled.body === norm(T.missing)) {
      finding('second_device_loses_the_deck', `a tour resumed on a second device shows «${T.missing}» for «${T.ownTitles[1]}»: the deck made on the first device is not known there, so no route is followed`,
        { step: 'own.material', location: settled.location, files: ['onboarding-second-device-resumed-1440.png'] });
    }
    await device.close();
    actors.splice(actors.indexOf(A2), 1);
    // Back on the first device the plaque is still there; resuming it lands on the same step and the server follows.
    await A.front();
    const stale = await tourState(A);
    A.need(stale.plaque !== null, 'the first device lost its pause plaque while the second device resumed');
    // The learner wandered off to another page while the tour waited; «Продолжить знакомство» brings them back to the step's page.
    const materialRoute = `/decks/${world.deckId}/materials/new?write=1`;
    await spaGo(A, '/decks');
    await realClick(A, 'app-tour-pause button', T.resume);
    const back = await awaitStep(A, T.ownTitles[1], 'Шаг 2 из 4', 15_000);
    A.need(back.headingFocused, `the focus is not on the heading after the resume: ${back.active}`);
    const serverBack = await serverJourney(A, 'is IN_PROGRESS at own.material', j => j.state === 'IN_PROGRESS' && j.stepKey === 'own.material');
    if (!(await expectRoute(A, 'resume_does_not_navigate', materialRoute, 'after «Продолжить знакомство» pressed on /decks'))) await spaGo(A, materialRoute);
    await waitFor('the editor again', () => exists('.ProseMirror[contenteditable="true"]', A.tab), Boolean, 20_000);
    a11y.pause = { linksBehindStep: linksBefore, linksBehindPause: linksAfter };
    return { escapePausedTheTour: true, plaqueFocused: true, serverPaused: serverPaused.state, secondDevicePlaqueNoJump: true, secondDeviceVersionUntouched: serverPaused.rowVersion,
      resumedOnSecondDevice: { step: resumedOn2.name, variant: settled.body === norm(T.missing) ? 'text variant (anchor missing)' : 'editor' }, resumedOnFirstDevice: back.name,
      serverAfterSecondDevice: serverAfter2.state, serverFinal: serverBack.state, axLinksBehindStep: linksBefore, axLinksBehindPause: linksAfter, editorPresent: Boolean(editor) };
  });

  await stage('own_material', ['pause_resume'], async () => {
    const A = world.journeyActor;
    await A.front();
    await read(A, `const editor = document.querySelector('.ProseMirror[contenteditable="true"]'); editor.focus();
      const selection = getSelection(); const range = document.createRange(); range.selectNodeContents(editor); selection.removeAllRanges(); selection.addRange(range); return true;`);
    await A.tab.call('Input.insertText', { text: 'Память — способность хранить и воспроизводить опыт. Memory is the ability to keep and recall what was learned.' });
    await waitFor('the draft acknowledgement', () => bodyHas(A, 'Все изменения сохранены'), Boolean, 15_000);
    await realClick(A, 'button', 'Опубликовать');
    await waitFor('the published material', () => location(A), path => /^\/decks\/[0-9a-f-]{36}\/materials\/[0-9a-f-]{36}(\/exercises\/new)?(\?.*)?$/.test(path), 20_000);
    const memberKey = (await location(A)).match(/\/materials\/([0-9a-f-]{36})/)[1];
    world.memberKey = memberKey;
    await awaitStep(A, T.ownTitles[2], 'Шаг 3 из 4');
    const exerciseRoute = `/decks/${world.deckId}/materials/${memberKey}/exercises/new`;
    const followed = await expectRoute(A, 'own_exercise_route', exerciseRoute, 'after «Опубликовать»');
    if (!followed) await spaGo(A, exerciseRoute);
    const server = await serverJourney(A, 'is at own.exercise', j => j.stepKey === 'own.exercise');
    return { published: true, serverStep: server.stepKey, tourBroughtTheLearnerToTheExerciseForm: followed };
  });

  await stage('own_exercise', ['own_material'], async () => {
    const A = world.journeyActor;
    await A.front();
    await waitFor('the exercise types', () => exists('input[name="mechanic"][value="FREE_RESPONSE"]', A.tab), Boolean, 20_000);
    const inert = await inertState(A, '[data-tour-anchor="own.exercise"]');
    A.need(inert.count > 0 && inert.targetLive, 'the exercise step is not inert around its page: ' + JSON.stringify(inert));
    require_(await click('input[name="mechanic"][value="FREE_RESPONSE"]', A.tab), 'the free-response type could not be chosen');
    await waitFor('the prompt field', () => exists('#free-response-prompt-text-0', A.tab), Boolean, 15_000);
    await fill('#free-response-prompt-text-0', 'Введите английское слово «память»', A.tab);
    require_(await clickText('button', 'Продолжить', A.tab), 'continue to the answers is absent');
    await waitFor('the answers step', () => exists('#step-answers app-text-answer-editor input[type="text"]', A.tab), Boolean, 15_000);
    await read(A, `const input = document.querySelector('app-text-answer-editor input[type="text"]'); input.focus(); return document.activeElement === input;`);
    await A.tab.call('Input.insertText', { text: 'memory' });
    require_(await clickText('button', 'Продолжить', A.tab), 'continue to the save step is absent');
    await waitFor('the save step', () => exists('#step-finish', A.tab), Boolean, 15_000);
    await realClick(A, 'button', 'Создать упражнение');
    await awaitStep(A, T.ownTitles[3], 'Шаг 4 из 4', 20_000);
    const hubRoute = `/decks/${world.deckId}`;
    const followed = await expectRoute(A, 'own_study_route', hubRoute, 'after «Создать упражнение»');
    if (!followed) await spaGo(A, hubRoute);
    const server = await serverJourney(A, 'is at own.study', j => j.stepKey === 'own.study');
    return { created: true, serverStep: server.stepKey, tourBroughtTheLearnerToTheHub: followed };
  });

  await stage('own_study', ['own_exercise'], async () => {
    const A = world.journeyActor;
    await A.front();
    const anchor = 'a[data-tour-anchor="hub.start"]';
    const step = await waitFor('the study step on the hub', () => tourState(A), s => s.card && s.name === norm(T.ownTitles[3]) && s.gate !== null, 20_000);
    A.need(step.gate.includes('Нажмите «Учить», чтобы продолжить'), `the gate line reads «${step.gate}»`);
    await waitFor('the study link on the hub', () => exists(anchor, A.tab), Boolean, 15_000);
    const inert = await inertState(A, anchor);
    A.need(inert.count > 0 && inert.targetLive, 'the study step is not inert around «Учить»: ' + JSON.stringify(inert));
    await shoot(A, 'onboarding-tour-own-study-1440.png');
    await realClick(A, anchor, 'Учить');
    await waitFor('the study route', () => location(A), path => /^\/decks\/[0-9a-f-]{36}\/study$/.test(path), 15_000);
    // The session is the learner's own: no veil, no card, no plaque, no inert element and no open dialog, in the setup, while answering and at the feedback.
    await waitFor('the study setup', () => exists('.session-setup', A.tab), Boolean, 20_000);
    const veils = [];
    veils.push(['setup', (await noVeil(A))]);
    // The tour waits for a session the server has actually opened (the first preset), and only then folds into its pause.
    await read(A, `const button = document.querySelector('.session-setup [data-answer-control]'); button.focus(); return document.activeElement === button;`);
    await A.press('Space');
    await waitFor('the first task', () => exists('#study-0-answer', A.tab), Boolean, 20_000);
    const paused = await serverJourney(A, 'is PAUSED at own.study', j => j.state === 'PAUSED' && j.stepKey === 'own.study');
    veils.push(['answering', (await noVeil(A))]);
    await fill('#study-0-answer', 'memory', A.tab);
    require_(await clickText('button', 'Проверить ответ', A.tab), 'the check action is absent');
    await waitFor('the feedback', () => exists('#feedback-title', A.tab), Boolean, 20_000);
    veils.push(['feedback', (await noVeil(A))]);
    for (const [where, veil] of veils) A.need(veil.clear, `the study session has a veil at «${where}»: ${JSON.stringify(veil.state)}`);
    await shoot(A, 'onboarding-study-no-veil-1440.png');
    require_(await clickText('.feedback-card button.primary', 'Продолжить', A.tab), 'the continue action after the feedback is absent');
    await waitFor('the end of the session or the finale', () => read(A, `return Boolean(document.querySelector('.completion')) || Boolean(document.querySelector('app-finale dialog[open]'));`), Boolean, 30_000);
    return { gateText: step.gate, serverPausedWhenTheSessionOpened: paused.state, veilChecked: veils.map(([where]) => where) };
  });

  await stage('own_finale', ['own_study'], async () => {
    const A = world.journeyActor;
    await A.front();
    const finale = () => read(A, `const d = document.querySelector('app-finale dialog.finale-night');
      const ai = document.querySelector('app-finale dialog.finale-card');
      if (!d) return { night: false, aiCard: Boolean(ai) };
      const h = d.querySelector('h1');
      return { night: d.open, aiCard: Boolean(ai), modal: d.matches(':modal'), eyebrow: norm(d.querySelector('.eyebrow')?.textContent), heading: norm(h?.textContent), focused: document.activeElement === h,
        name: norm(document.getElementById(d.getAttribute('aria-labelledby') ?? '')?.textContent), stars: d.querySelectorAll('.sky .star').length,
        buttons: [...d.querySelectorAll('button')].map(b => norm(b.textContent)), overflow: document.documentElement.scrollWidth > document.documentElement.clientWidth };`);
    const first = await waitFor('the finale', finale, s => s.night || s.aiCard, 25_000);
    A.need(!first.aiCard, 'the AI finale is shown although AI is off in this run');
    const done = await waitFor('the congratulation', finale, s => s.night && s.heading === T.doneTitle, 10_000);
    A.need(done.eyebrow === T.doneEyebrow && done.modal && done.focused && done.name === T.doneTitle && done.stars > 0, 'the congratulation is ' + JSON.stringify(done));
    A.need(!done.overflow, 'the congratulation overflows');
    const tree = await axTree(A);
    A.need(named(tree, 'dialog').includes(T.doneTitle), 'the AX dialog of the congratulation is ' + JSON.stringify(named(tree, 'dialog')));
    await shoot(A, 'onboarding-finale-1440.png');
    const resumed = await serverJourney(A, 'is IN_PROGRESS at finale.done', j => j.state === 'IN_PROGRESS' && j.stepKey === 'finale.done');
    await realClick(A, 'app-finale button', T.doneAction);
    const completed = await serverJourney(A, 'is COMPLETED', j => j.state === 'COMPLETED' && j.finishedAt !== null && j.branch === 'OWN');
    await waitFor('the hub of the deck', () => location(A), path => path === `/decks/${world.deckId}`, 15_000);
    const after = await noVeil(A);
    A.need(after.clear, 'the tour left something on the screen after the end: ' + JSON.stringify(after.state));
    a11y.inertRemoved = { inertAfterTheTour: after.state.inert, veil: after.state.scrim, dialogs: after.state.dialogs.length };
    // A reload does not bring «Начало пути» back.
    await A.tab.call('Page.reload', { ignoreCache: true });
    await waitFor('the page after the reload', () => location(A), path => path === `/decks/${world.deckId}`, 20_000);
    await sleep(1500);
    A.need((await firstEntry(A)) === null && (await noVeil(A)).clear, 'the first entry or the tour came back after the end');
    const total = (await decks(A)).length;
    A.need(total === 1, `the learner has ${total} decks after the own-deck branch`);
    return { aiFinaleSkipped: true, congratulation: T.doneTitle, focusOnHeading: true, stars: done.stars, serverAtFinale: resumed.stepKey, serverFinal: completed.state, finishedAt: completed.finishedAt !== null,
      inertAfterTheTour: 0, decks: total };
  });

  // ==== 4. the replay from the profile ===========================================================================================================
  await stage('replay', ['own_finale'], async () => {
    const A = world.journeyActor;
    await A.front();
    await navigate('/profile', A.tab);
    await waitFor('the replay button', () => exists('app-replay-tour button', A.tab), Boolean, 20_000);
    A.need((await firstEntry(A)) === null, 'the first entry is open on the profile of a completed journey');
    await realClick(A, 'app-replay-tour button', T.replay);
    const choose = await awaitCard(A, T.branchTitle, 20_000);
    A.need(choose.progress === null && choose.counter === null, 'the replay shows the survey');
    A.need(choose.stars === 0, 'the replayed choice has stars');
    A.need(!choose.buttons.some(b => b.text === T.back), 'the replayed choice has a way back to a survey that is not asked');
    A.need(choose.radios.some(r => r.value === 'OWN'), 'the replayed choice has no «Создать свою»');
    const server = await journey(A);
    A.need(server.state === 'IN_PROGRESS' && server.stepKey === 'choose' && server.replayCount === 1 && server.branch === null, 'the server journey after the replay is ' + JSON.stringify(server));
    A.need(server.experience === 'NEW' && server.weeklyTarget === 3, 'the replay changed the answers kept by the journey');
    const profile = await A.api('GET', '/api/learning-profile');
    A.need(profile.status === 200 && profile.text === world.profileBefore, 'the learning profile changed during the replay');
    const total = (await decks(A)).length;
    A.need(total === world.deckCountBefore + 1, `the replay itself changed the number of decks: ${total}`);
    await shoot(A, 'onboarding-replay-choice-1440.png');
    // There is no way out of the choice other than a branch: Escape does nothing and «Начать» is the only button.
    await A.press('Escape');
    await sleep(300);
    const stillThere = await firstEntry(A);
    const noWayOut = stillThere !== null && stillThere.buttons.length === 1;
    if (noWayOut) {
      finding('replay_choice_has_no_way_out', 'the replayed branch choice cannot be closed: Escape is swallowed, there is no «Назад» or close button, «Начать» is its only button',
        { buttons: stillThere.buttons.map(b => b.text), files: ['onboarding-replay-choice-1440.png'] });
    }
    // «Своя колода» on a replay offers the decks the learner already has.
    await read(A, `const own = [...document.querySelectorAll('app-first-entry input[type="radio"]')].find(r => r.value === 'OWN'); own.click(); return true;`);
    await realClick(A, 'app-first-entry button', T.begin);
    const picker = await awaitCard(A, T.replayTitle, 15_000);
    A.need(picker.radios.length >= 2 && picker.radios[0].label === T.replayNew && picker.radios.some(r => r.label === 'Мой первый набор'), 'the deck choice is ' + JSON.stringify(picker.radios));
    await read(A, `const existing = [...document.querySelectorAll('app-first-entry input[type="radio"]')].find(r => r.closest('label').textContent.includes('Мой первый набор')); existing.click(); return true;`);
    await realClick(A, 'app-first-entry button', T.begin);
    const step = await awaitStep(A, T.ownTitles[1], 'Шаг 2 из 4', 20_000);
    const materialRoute = `/decks/${world.deckId}/materials/new?write=1`;
    await expectRoute(A, 'replay_material_route', materialRoute, 'after choosing the existing deck on a replay');
    const after = (await decks(A)).length;
    A.need(after === total, `starting the replayed tour made a deck (${total} -> ${after})`);
    await A.press('Escape');
    await waitFor('the pause after the replay', () => journey(A), j => j.state === 'PAUSED' && j.stepKey === 'own.material', 10_000);
    return { surveySkipped: true, branchChoice: choose.radios.map(r => r.value), serverStep: server.stepKey, replayCount: server.replayCount, answersUnchanged: true, decksBefore: world.deckCountBefore + 1, decksAfter: after,
      existingDeckOffered: true, startedAt: step.name, noWayOut };
  });

  // ==== 5. the hold to finish on a phone ============================================================================================================
  await stage('hold', [], async () => {
    // The catalogue lists four decks only once the seed is in: the second account meets the community branch, leaves it for its own deck and finishes the tour early.
    await ensureSeed().catch(() => {});
    const { actor: B, device: holdDevice } = await newDevice('hold', LOGIN.hold, PHONE);
    world.holdDevice = holdDevice;
    const welcome = await awaitCard(B, T.welcomeTitle, 30_000);
    B.need(!welcome.overflow.page && !welcome.overflow.dialog, 'the welcome overflows at 390 px');
    await realClick(B, 'app-first-entry button', T.start);
    // Every question is skipped.
    for (let card = 0; card < 6; card++) {
      const state = await waitFor('a survey card or the branch choice', () => firstEntry(B), s => s?.open && s.heading !== norm(T.welcomeTitle), 15_000);
      if (state.heading === norm(T.branchTitle)) break;
      await realClick(B, 'app-first-entry button', T.skip);
      await waitFor('the next card', () => firstEntry(B), s => s?.open && s.heading !== state.heading, 15_000);
    }
    await awaitCard(B, T.branchTitle, 15_000);
    const skipped = await journey(B);
    B.need(skipped.experience === null && skipped.weeklyTarget === null, 'a skipped question stored an answer: ' + JSON.stringify([skipped.experience, skipped.weeklyTarget]));
    await shoot(B, 'onboarding-branch-choice-390.png');
    // The community branch first: after two minutes without opening a deck the tour offers «Создать свою». The page's two timers (45 s and 120 s) are
    // compressed to 1.5 s and 4 s, and nothing else about the page or the product is changed.
    B.need((await firstEntry(B)).radios.some(r => r.value === 'COMMUNITY'), 'the community branch is not offered although four decks are listed');
    await read(B, `const wait = window.setTimeout.bind(window);
      window.setTimeout = (callback, delay, ...rest) => wait(callback, delay === 120000 ? 4000 : delay === 45000 ? 1500 : delay, ...rest);
      document.querySelector('app-first-entry input[value="COMMUNITY"]').click(); return true;`);
    await realClick(B, 'app-first-entry button', T.begin);
    await awaitStep(B, T.communityTitles[0], 'Шаг 1 из 4', 30_000);
    const browseHint = await waitFor('the hint at the search', () => read(B, `return norm(document.querySelector('app-community-page .browse-hint')?.textContent) || null;`), text => text !== null, 15_000);
    B.need(browseHint === 'Не знаете, что выбрать? Начните с темы из вашего списка.', `the hint reads «${browseHint}»`);
    const nudge = await waitFor('the offer of the own deck', () => read(B, `const n = document.querySelector('app-tour-nudge section.nudge');
      return n ? { name: norm(document.getElementById(n.getAttribute('aria-labelledby'))?.textContent), inert: Boolean(n.closest('[inert]')), focused: n.contains(document.activeElement),
        buttons: [...n.querySelectorAll('button')].map(b => norm(b.textContent)) } : null;`), state => state !== null, 15_000);
    B.need(nudge.name === 'Не нашли подходящую?' && nudge.buttons.includes(T.branchOwn) && nudge.buttons.includes(T.back), 'the offer is ' + JSON.stringify(nudge));
    if (nudge.inert) gap('stuck_nudge_inert', 'the offer «Не нашли подходящую?» is inside an inert part of the page: a learner can neither read it with a screen reader nor press its buttons');
    else B.need(nudge.focused, 'the offer did not take the focus to its heading');
    await shoot(B, 'onboarding-tour-community-stuck-nudge-390.png');
    if (nudge.inert) await read(B, `[...document.querySelectorAll('app-tour-nudge button')].find(b => norm(b.textContent) === args[0]).click(); return true;`, T.branchOwn);
    else await realClick(B, 'app-tour-nudge button', T.branchOwn);
    const step = await awaitStep(B, T.ownTitles[0], 'Шаг 1 из 4', 30_000);
    B.need(step.sheet, 'the card is not a bottom sheet at 390 px');
    await serverJourney(B, 'is at own.details after the offer', j => j.state === 'IN_PROGRESS' && j.stepKey === 'own.details' && j.branch === 'OWN');
    // A paused tour is not moved by a page load, and «Продолжить знакомство» brings the learner back to the page of the step.
    await B.press('Escape');
    await waitFor('the pause plaque', () => tourState(B), s => s.plaque !== null && !s.card, 10_000);
    await navigate('/profile', B.tab);
    await waitFor('the plaque after a page load', () => tourState(B), s => s.plaque !== null, 25_000);
    await sleep(800);
    const onLoad = await location(B);
    if (onLoad !== '/profile') gap('paused_tour_navigates_on_load', `a page load of /profile while the tour is paused ended at ${onLoad}`);
    await realClick(B, 'app-tour-pause button', T.resume);
    await awaitStep(B, T.ownTitles[0], 'Шаг 1 из 4', 15_000);
    if (!(await expectRoute(B, 'resume_does_not_navigate', '/decks/new', 'after «Продолжить знакомство» pressed on /profile'))) await spaGo(B, '/decks/new');
    await waitFor('the form of the first step', () => exists('[data-tour-anchor="own.details"]', B.tab), Boolean, 15_000);
    // The first press arms the gesture.
    const hold = 'app-tour-card app-hold-to-delete-button button.hold-button';
    await realClick(B, hold);
    const armed = await waitFor('the armed button', () => tourState(B), s => s.hold?.pressed === 'true', 5_000);
    B.need(norm(armed.hold.label) === T.finishArmed, `the armed button is named «${armed.hold.label}»`);
    B.need(armed.hold.glyph, 'the armed button shows no glyph');
    const armedTree = await axTree(B);
    B.need(named(armedTree, 'button').includes(T.finishArmed), 'the AX name of the armed button is ' + JSON.stringify(named(armedTree, 'button')));
    await shoot(B, 'onboarding-hold-armed-390.png');
    // Releasing the pointer before two seconds does nothing.
    await holdMouse(B, hold, 600);
    await sleep(400);
    const early = await tourState(B);
    B.need(early.card && early.hold?.pressed === 'true', 'an early release changed the armed button: ' + JSON.stringify(early.hold));
    B.need((await journey(B)).state === 'IN_PROGRESS', 'an early release finished the tour');
    // Holding Enter for two seconds finishes it.
    await read(B, `document.querySelector(args[0]).focus(); return true;`, hold);
    await holdKey(B, 'Enter', 2400);
    const completed = await serverJourney(B, 'is COMPLETED', j => j.state === 'COMPLETED' && j.finishedAt !== null, 10_000);
    const toast = await waitFor('the toast', () => read(B, `return norm(document.querySelector('app-toast-region .toast .text')?.textContent) || null;`), text => text !== null, 10_000);
    B.need(toast === T.finishToast, `the toast reads «${toast}»`);
    const after = await noVeil(B);
    B.need(after.clear, 'the tour left something on the screen after the hold: ' + JSON.stringify(after.state));
    await shoot(B, 'onboarding-hold-finished-390.png');
    return { communityHint: browseHint, ownDeckOffered: nudge.name, offerInert: nudge.inert, armedName: T.finishArmed, armedGlyph: true, releasedEarlyDidNothing: true, heldEnterFinished: true, toast, serverFinal: completed.state, sheetAt390: true, skippedQuestionsStoredNothing: true,
      inertAfterTheTour: 0 };
  });

  // ==== 6. «Мне это знакомо — сразу к делу» in a narrow window ========================================================================================
  await stage('skip', [], async () => {
    const { actor: C, device: skipDevice } = await newDevice('skip', LOGIN.skip, NARROW);
    world.skipDevice = skipDevice;
    const welcome = await awaitCard(C, T.welcomeTitle, 30_000);
    C.need(!welcome.overflow.page && !welcome.overflow.dialog, `the welcome overflows at 320 px: ${JSON.stringify(welcome.widths)}`);
    await realClick(C, 'app-first-entry button', T.skipAll);
    const toast = await waitFor('the skip toast', () => read(C, `const t = document.querySelector('app-toast-region .toast');
      return t ? { text: norm(t.querySelector('.text')?.textContent), link: norm(t.querySelector('.action')?.textContent), href: t.querySelector('.action')?.getAttribute('href') } : null;`), t => t !== null, 10_000);
    C.need(toast.text === T.skipToast && toast.link === T.skipToastLink && toast.href === '/profile', 'the skip toast is ' + JSON.stringify(toast));
    const skipped = await serverJourney(C, 'is SKIPPED', j => j.state === 'SKIPPED' && j.finishedAt !== null);
    C.need((await firstEntry(C)) === null, 'the first entry stays open after the skip');
    const focus = await read(C, `return describe(document.activeElement);`);
    await shoot(C, 'onboarding-skip-toast-320-at-200-percent.png');
    await C.tab.call('Page.reload', { ignoreCache: true });
    await sleep(2000);
    C.need((await firstEntry(C)) === null, 'the welcome came back after a reload of a skipped journey');
    return { toast: toast.text, link: toast.href, serverFinal: skipped.state, finishedAt: true, focusAfterSkip: focus, welcomeStaysClosedAfterReload: true, noHorizontalScrollAt320: true };
  });

  // ==== 7. the community branch up to the study gate ==================================================================================================
  await stage('community', [], async () => {
    const decksSeeded = await ensureSeed();
    const { actor: D } = await newDevice('community', LOGIN.community, DESKTOP);
    await awaitCard(D, T.welcomeTitle, 30_000);
    await realClick(D, 'app-first-entry button', T.start);
    for (let card = 0; card < 6; card++) {
      const state = await waitFor('a survey card or the branch choice', () => firstEntry(D), s => s?.open && s.heading !== norm(T.welcomeTitle), 15_000);
      if (state.heading === norm(T.branchTitle)) break;
      await realClick(D, 'app-first-entry button', T.skip);
      await waitFor('the next card', () => firstEntry(D), s => s?.open && s.heading !== state.heading, 15_000);
    }
    const choose = await awaitCard(D, T.branchTitle, 15_000);
    D.need(choose.radios.map(r => r.value).join() === 'COMMUNITY,OWN', 'the branch options are ' + JSON.stringify(choose.radios));
    D.need(choose.radios[0].label.startsWith(T.branchCommunity) && !choose.text.includes(T.branchSample), 'the branch labels are ' + JSON.stringify(choose.radios.map(r => r.label)));
    await shoot(D, 'onboarding-branch-choice-community-1440.png');
    await read(D, `document.querySelector('app-first-entry input[value="COMMUNITY"]').click(); return true;`);
    await realClick(D, 'app-first-entry button', T.begin);
    await awaitStep(D, T.communityTitles[0], 'Шаг 1 из 4', 30_000);
    await waitFor('the community page', () => location(D), path => path === '/community', 15_000);
    // The page of the step takes the focus to its own heading once it has loaded; the card's heading must still be where the focus is.
    await sleep(1500);
    const search = await tourState(D);
    if (!search.headingFocused) gap('community_search_focus', `the focus is on «${search.active}» and not on the heading of the step card (the page took it after the route change)`);
    D.need(search.gate === null, `the search step names a control: «${search.gate}»`);
    // A learner who skipped the interests card must not meet a notice about a save that never happened.
    const notice = await read(D, `return document.body.innerText.includes('Интересы обновились') || document.body.innerText.includes('Не удалось подтвердить сохранение');`);
    if (notice) {
      const text = await read(D, `return norm([...document.querySelectorAll('app-community-page p, app-community-page [role="status"], app-community-page [role="alert"]')].map(e => e.textContent).find(t => t.includes('Интересы обновились') || t.includes('Не удалось подтвердить')));`);
      const interestRequests = D.wire.filter(entry => entry.path.startsWith('/api/community/interests')).map(entry => `${entry.method} ${entry.path.replace('/api/community', '')} ${entry.status ?? '…'}`);
      gap('interests_notice_after_skip', `the community page of a learner who skipped the interests card says «${text}» (the page's requests: ${JSON.stringify(interestRequests)})`);
    }
    D.need(!search.modal, 'the search step is modal');
    await serverJourney(D, 'is at community.search', j => j.state === 'IN_PROGRESS' && j.stepKey === 'community.search' && j.branch === 'COMMUNITY');
    await shoot(D, 'onboarding-tour-community-search-1440.png');
    // Search by keyboard, open a result with the mouse.
    await waitFor('the book search', () => exists('app-community-page app-book-search input[type="search"]', D.tab), Boolean, 20_000);
    await D.focus('app-community-page app-book-search input[type="search"]');
    await D.tab.call('Input.insertText', { text: 'Английские' });
    await D.press('Enter');
    await waitFor('the search result', () => read(D, `return [...document.querySelectorAll('app-public-deck-card .title a')].map(a => norm(a.textContent));`), titles => titles.length > 0 && titles.some(t => t.includes('Английские')), 25_000);
    await realClick(D, 'app-public-deck-card .title a', 'Английские фразовые глаголы');
    // The tour moves on to «Что внутри колоды»: an informational step, modal, with the page behind it unreachable.
    const info = await awaitStep(D, T.communityTitles[1], 'Шаг 2 из 4', 25_000);
    D.need(info.modal && info.role === 'dialog' && info.headingFocused, `the informational step is ${JSON.stringify({ modal: info.modal, role: info.role, focus: info.active })}`);
    // «Назад» is not offered before a step whose action was done (going back would ask for the search again): the product's rule, recorded rather than asserted.
    D.need(info.gate === null && info.buttons.includes(T.next) && info.buttons.includes(T.finish), 'the buttons of the informational step are ' + JSON.stringify(info.buttons));
    D.need(info.live === 'Шаг 2 из 4', `the live region says «${info.live}»`);
    const infoTree = await axTree(D);
    D.need(named(infoTree, 'dialog').includes(norm(T.communityTitles[1])), 'the AX dialog of the informational step is ' + JSON.stringify(named(infoTree, 'dialog')));
    D.need(named(infoTree, 'button').every(name => name !== '') && named(infoTree, 'button').includes(T.next), 'AX buttons of the informational step: ' + JSON.stringify(named(infoTree, 'button')));
    await shoot(D, 'onboarding-tour-community-deck-1440.png');
    a11y.informationalStep = { dialogName: norm(T.communityTitles[1]), modal: true, buttons: named(infoTree, 'button'), focusOnHeading: true, liveRegion: info.live, backOffered: info.buttons.includes(T.back) };
    await realClick(D, 'app-tour-card button', T.next);
    const add = await awaitStep(D, T.communityTitles[2], 'Шаг 3 из 4', 15_000);
    D.need(add.gate !== null && add.gate.includes('Нажмите «Добавить к себе», чтобы продолжить') && !add.modal, 'the copy step is ' + JSON.stringify([add.gate, add.modal]));
    const addInert = await inertState(D, '[data-tour-anchor="community.add"]');
    D.need(addInert.count > 0 && addInert.targetLive, 'the copy step is not inert around its button: ' + JSON.stringify(addInert));
    await realClick(D, '[data-tour-anchor="community.add"]', 'Добавить к себе');
    const study = await awaitStep(D, T.communityTitles[3], 'Шаг 4 из 4', 30_000);
    const copyPath = await waitFor('the copy hub', () => location(D), path => /^\/decks\/[0-9a-f-]{36}$/.test(path), 20_000);
    await waitFor('the study link on the copy hub', () => exists('a[data-tour-anchor="hub.start"]', D.tab), Boolean, 30_000);
    await waitFor('the study step ready', () => tourState(D), s => s.card && s.gate !== null && s.body !== norm(T.missing), 15_000);
    await shoot(D, 'onboarding-tour-community-study-1440.png');
    await realClick(D, 'a[data-tour-anchor="hub.start"]', 'Учить');
    await waitFor('the study route', () => location(D), path => /^\/decks\/[0-9a-f-]{36}\/study$/.test(path), 15_000);
    await waitFor('the study setup', () => exists('.session-setup', D.tab), Boolean, 20_000);
    const setup = await noVeil(D);
    D.need(setup.clear, 'the study setup has a veil: ' + JSON.stringify(setup.state));
    await read(D, `const button = document.querySelector('.session-setup [data-answer-control]'); button.focus(); return document.activeElement === button;`);
    await D.press('Space');
    await waitFor('the first task', () => exists('#study-0-answer', D.tab), Boolean, 20_000);
    const paused = await serverJourney(D, 'is PAUSED at community.study', j => j.state === 'PAUSED' && j.stepKey === 'community.study' && j.branch === 'COMMUNITY');
    const veil = await noVeil(D);
    D.need(veil.clear, 'the study session has a veil: ' + JSON.stringify(veil.state));
    const owned = await decks(D);
    D.need(owned.length === 1, `the learner has ${owned.length} decks after one copy`);
    return { seeded: decksSeeded.length, branchOptions: choose.radios.map(r => r.value), searched: true, informationalStepModal: true, copyStepAction: add.gate, copyCreated: owned.length === 1, copyHub: copyPath.replace(/[0-9a-f-]{36}/u, '{id}'),
      serverAtStudy: paused.state, studyWithoutVeil: true, studyStep: study.name, sessionStarted: true, sessionCompleted: false };
  });

  // ==== 8. the accessibility evidence, collected ======================================================================================================
  await stage('a11y', ['welcome', 'survey', 'own_details', 'pause_resume', 'own_finale', 'community'], async () => {
    const missing = ['welcome', 'survey', 'keyboardSurvey', 'actionGatedStep', 'informationalStep', 'inertRemoved', 'pause'].filter(key => a11y[key] === undefined);
    need(missing.length === 0, 'accessibility evidence is missing for: ' + missing.join(', '));
    await writeFile(join(config.output, 'onboarding-a11y.json'), JSON.stringify(a11y, null, 2));
    return { surfaces: Object.keys(a11y), file: 'onboarding-a11y.json' };
  });

  // ==== 9. sign out with the real control (the runner audits the wire of two logouts) ===================================================================
  // The accounts whose journey is over (finished by the hold, skipped) are signed out: a tour that is still paused could move the page on a load and would take
  // the login page with it.
  await stage('sign_out', [], async () => {
    const finished = [world.holdDevice, world.skipDevice].filter(device => device !== undefined);
    need(finished.length === 2, 'the two accounts whose journey is over were not created');
    for (const device of finished) await signOut(device.tab);
    return { signedOut: finished.length };
  });

  for (const device of devices) await device.close().catch(() => {});
  record('onboarding_summary', { state: failures.length === 0 ? 'passed' : 'failed', stages: failures.length, findings: findings.length, durationMs: Date.now() - started });
  return { failures, findings };

  function require_(value, what) { if (!value) throw new SafeFailure(what); }
}
