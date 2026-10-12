// Real-browser check of the community catalogue (Community/8, #455, epic #421): the empty Community, a seeded one (shelves, search, filters, infinite
// scroll, honest counts), the author's page, the masthead entry, the owner console's editorial shelf and hiding, the guest limit, reduced motion and an
// accessibility smoke, on a desktop (1440 px) and a mobile (390 px) viewport. Runs with `--catalog` against the real Identity and Learning jars; node 24
// built-ins only.
//
// Who is who: the author is the first account (the first tab, signed in), the learner is the second account (the second tab), the guest is a third tab in
// a NEW browser context (no cookies, no storage, no bearer). A second author (registered through the real Identity) holds the 30 filler decks that make the
// lists longer than one page, so that the first author's page stays one page long and can be read whole.
//
// The decks are seeded through the real Learning API (deck, materials in one bulk publication, one exercise, publication), exactly as the product's own
// clients send them, because the point here is the catalogue, not the authoring UI (`--community` drives that). Everything a reader does (the page,
// the book, the filters, the keyboard) goes through the real UI with CDP key events. The projection jobs and the rank generations are the real queue's:
// the harness only waits for them, and enqueues one more generation (a job with a key of its own, through the table the queue reads) whenever it has
// changed what the rank must see.
import { writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { makeActor } from './community.mjs';

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const DESKTOP = { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false };
const PHONE = { width: 390, height: 844, deviceScaleFactor: 1, mobile: true };

/** The words of the product contract and of the shipped components, as the page says them. */
const WORDS = {
  empty: 'Пока здесь пусто.',
  emptyBody: 'Сделайте свою колоду публичной, и она появится здесь первой.',
  how: 'Как поделиться колодой',
  steps: ['Откройте свою колоду.', 'В разделе «Доступ и публикация» выберите «Публичная».', 'Пройдите чек-лист: описание, тема и язык, публичный профиль.'],
  nothing: 'Ничего не нашли. Попробуйте другое слово или уберите фильтры.',
  relevance: 'По близости к запросу',
  notFound: 'Профиль не найден',
  notFoundBody: 'Такого профиля нет, или автор его скрыл.',
  self: 'Так ваш профиль видят другие',
  granted: 'Доступна вам',
  limited: 'Слишком много запросов.'
};

/** The fixture: three Spanish decks, four English, two SQL; one PUBLIC deck under the threshold, one LINK, one INVITE; thirty fillers of another author. */
const NAMED = [
  { key: 'es1', title: 'Испанские глаголы', description: 'Сорок самых частых глаголов с примерами и упражнениями.', topicId: 'spanish', content: 'ru', target: 'es', level: 'A1', tags: ['глаголы'] },
  { key: 'es2', title: 'Spanish verbs for travel', description: 'The verbs a traveller needs at the station, the hotel and the cafe.', topicId: 'spanish', content: 'en', target: 'es', level: 'A2', tags: ['travel'] },
  { key: 'es3', title: 'Español para principiantes', description: 'Primeras frases y palabras para empezar a hablar.', topicId: 'spanish', content: 'es', target: 'es', level: 'A1', tags: ['frases'] },
  { key: 'en1', title: 'Английские фразовые глаголы', description: 'Фразовые глаголы с примерами из разговорной речи.', topicId: 'english', content: 'ru', target: 'en', level: 'A2', tags: ['глаголы'] },
  { key: 'en2', title: 'Everyday English conversation', description: 'Short dialogues for the shop, the bank and the office.', topicId: 'english', content: 'en', target: 'en', level: 'B1', tags: ['dialogues'] },
  { key: 'en3', title: 'Деловые письма по-английски', description: 'Шаблоны писем и устойчивые обороты для работы.', topicId: 'english', content: 'ru', target: 'en', level: 'B2', tags: ['письма'] },
  { key: 'en4', title: 'Grammar in twenty evenings', description: 'A tidy course of tenses, articles and prepositions.', topicId: 'english', content: 'en', target: 'en', level: 'A2', tags: ['grammar'] },
  { key: 'sq1', title: 'SQL для аналитиков', description: 'Запросы, соединения и агрегаты на живых примерах.', topicId: 'sql-data', content: 'ru', target: null, level: 'INTERMEDIATE', tags: ['sql'] },
  { key: 'sq2', title: 'Window functions in PostgreSQL', description: 'Ranking, running totals and frames explained with small tables.', topicId: 'sql-data', content: 'en', target: null, level: 'ADVANCED', tags: ['postgresql'] }
];
const SMALL = { key: 'small', title: 'Колода ниже порога каталога', description: 'Девять карточек: этого мало для каталога, но хватает для ссылки и профиля.', topicId: 'other', content: 'ru', target: null, level: 'BEGINNER', tags: [], materials: 9 };
const LINKED = { key: 'link', title: 'Колода по ссылке для друзей', description: 'Её видят только те, у кого есть ссылка.', topicId: 'other', content: 'ru', target: null, level: 'BEGINNER', tags: [] };
const INVITED = { key: 'invite', title: 'Колода только для приглашённых', description: 'Её читают только те, кому автор дал доступ.', topicId: 'other', content: 'ru', target: null, level: 'BEGINNER', tags: [] };
const FILLERS = 30;
const LISTED_NAMED = NAMED.length;
const LISTED_TOTAL = LISTED_NAMED + FILLERS;

const paragraph = (text, index) => ({ id: `00000000-0000-4000-8000-${String(index * 2 + 1).padStart(12, '0')}`, type: 'paragraph', version: 1, attrs: {},
  content: [{ id: `00000000-0000-4000-8000-${String(index * 2 + 2).padStart(12, '0')}`, type: 'text', version: 1, attrs: { text, marks: [] }, content: [] }] });
const nativeDocument = lines => ({ formatVersion: 1, root: { id: '00000000-0000-4000-8000-000000000000', type: 'doc', version: 1, attrs: {}, content: lines.map(paragraph) } });

export async function runCatalog(ctx) {
  const { config, record, SafeFailure, until, exists, navigate, saveScreenshot, saveFullScreenshot, setStep, ownerTab, ownerBearer, memberTab, memberBearer, openGuestTab } = ctx;
  const owner = makeActor(ctx, 'owner', ownerTab, ownerBearer);
  const member = makeActor(ctx, 'member', memberTab, memberBearer);
  const guestTab = await openGuestTab();
  const guest = makeActor(ctx, 'guest', guestTab.tab, null);
  const actors = [owner, member, guest];
  const failures = [];
  const started = Date.now();
  const world = { ownerLogin: null, ownerId: null, memberLogin: null, fillerBearer: null, fillerLogin: null, hiddenLogin: null, decks: {}, fillerCodes: [] };
  const ranOn = new Set();
  /** The last thing this stage did that can fail without a message of its own (an API call, a fixture call): added to a failure so the log says where. */
  let at = '';

  const shoot = async (actor, name, { full = false } = {}) => {
    try { await (full ? saveFullScreenshot : saveScreenshot)(name, actor.tab); } catch { /* the verdict is the assertion, not the picture */ }
  };

  const stage = async (name, depends, body) => {
    const key = `catalog_${name}`;
    setStep(key);
    const broken = depends.find(dependency => ranOn.has('!' + dependency));
    if (broken !== undefined) {
      record(key, { state: 'skipped', because: `catalog_${broken} failed` });
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
      const reason = (error instanceof SafeFailure ? error.message : 'driver: ' + String(error?.message ?? error).slice(0, 200)) + (at === '' ? '' : ` [after: ${at}]`);
      ranOn.add('!' + name);
      failures.push({ stage: key, reason });
      record(key, { state: 'failed', reason, durationMs: Date.now() - begun });
      for (const actor of actors) await shoot(actor, `failure-catalog-${name}-${actor.label}.png`);
      await writeFile(join(config.output, `failure-${key}.txt`), reason + '\n').catch(() => {});
      return null;
    }
  };
  const need = (value, what) => { if (!value) throw new SafeFailure(what); };

  // ---- plumbing ---------------------------------------------------------------------------------------------------------------------
  /** A call of the real Learning API straight from this process (plain loopback HTTP); the bearer is the person's own. */
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
    return { status: response.status, etag: response.headers.get('etag'), retryAfter: response.headers.get('retry-after'), cache: response.headers.get('cache-control'), body, text };
  };
  /** The harness fixture endpoints (served by the proxy over HTTPS): called from a page, which trusts the run's certificate. */
  const fixture = async (path, payload = {}) => {
    at = `fixture ${path}`;
    const answer = await owner.page(`const response = await fetch(args[0], { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(args[1]) });
      const text = await response.text(); return { status: response.status, body: text === '' ? null : JSON.parse(text) };`, path, payload);
    need(answer.status === 200, `${path} answered ${answer.status}`);
    return answer.body;
  };
  const publicGet = (path, bearer = ownerBearer) => learning('GET', '/api/public/community' + path, { bearer });

  const settled = actor => actor.page(`const status = document.querySelector('app-community-page .status');
    const busy = status !== null && status.textContent.trim() !== '';
    return !busy && Boolean(document.querySelector('app-community-page app-deck-shelf, app-community-page app-deck-grid, app-community-page app-community-empty, app-community-page .notice'));`);
  /** Opens the Community (or any path) in a viewport and waits until the page has said what it has to say. */
  const openCommunity = async (actor, path = '/community', size = DESKTOP) => {
    await actor.front();
    await actor.metrics(size);
    await navigate(path, actor.tab);
    await until(() => exists('app-community-page h1', actor.tab), `${actor.label}: ${path} did not render`, 30000);
    await until(() => settled(actor), `${actor.label}: ${path} stayed loading`, 30000);
  };
  const cardsOf = actor => actor.page(`return [...document.querySelectorAll('app-public-deck-card')].map(card => ({
    title: card.querySelector('.title')?.textContent.trim() ?? null, href: card.querySelector('.title a')?.getAttribute('href') ?? null,
    note: card.querySelector('.stamp.solid')?.textContent.trim() ?? null,
    audience: [...card.querySelectorAll('.audience > div')].map(row => row.querySelector('dt')?.textContent.trim() + ' ' + (row.querySelector('dd [aria-hidden]')?.textContent.trim() ?? '')) }));`);
  const titlesOf = async actor => (await cardsOf(actor)).map(card => card.title);
  /** Elements that reach furthest to the right and are not inside a box that scrolls or clips: what makes a page wider than its viewport. */
  const widest = (actor, limit) => actor.page(`const clips = node => { for (let up = node.parentElement; up && up !== document.body && up !== document.documentElement; up = up.parentElement) {
        const style = getComputedStyle(up); if (['auto', 'scroll', 'hidden', 'clip'].includes(style.overflowX)) return true; } return false; };
      return [...document.querySelectorAll('body *')].filter(node => !clips(node)).map(node => ({ node: node.tagName.toLowerCase() + '.' + String(node.className).slice(0, 40), right: Math.round(node.getBoundingClientRect().right),
        parent: node.parentElement?.tagName.toLowerCase() + '.' + String(node.parentElement?.className).slice(0, 30) })).sort((a, b) => b.right - a.right).slice(0, args[0]);`, limit);
  const landmarks = actor => actor.page(`return { search: document.querySelectorAll('search, [role="search"]').length, h1: document.querySelectorAll('h1').length,
    overflow: document.documentElement.scrollWidth > document.documentElement.clientWidth };`);
  const countText = actor => actor.page(`return [...document.querySelectorAll('app-deck-grid p.count')].map(node => node.textContent.trim());`);
  const typeInBook = async (actor, text) => {
    await actor.focus('app-community-page app-book-search input[type="search"]');
    await actor.page(`document.querySelector('app-community-page app-book-search input[type="search"]').select(); return true;`);
    await actor.tab.call('Input.insertText', { text });
  };
  /** What a screen reader would be told: every `p.count` of the page, once, when it first has text, and again whenever its text changes. */
  const watchAnnouncements = actor => actor.page(`window.__announced = [];
    const seen = new WeakMap();
    const look = () => { for (const p of document.querySelectorAll('p.count')) { const text = p.textContent.trim();
      if (text !== '' && seen.get(p) !== text) { seen.set(p, text); window.__announced.push(text); } } };
    new MutationObserver(look).observe(document.body, { subtree: true, childList: true, characterData: true }); look(); return true;`);
  const announced = actor => actor.page('return window.__announced ?? [];');
  /** A dropdown of the filters by keyboard: the trigger is focused, ArrowDown opens it and walks the options until the active one reads `option`, Enter chooses. */
  const choose = async (actor, control, option) => {
    await actor.focus(`app-catalog-filters button.select-trigger[aria-label^="${control}:"]`);
    await actor.press('ArrowDown');
    for (let step = 0; step < 40; step++) {
      const active = await actor.page(`const trigger = document.querySelector('app-catalog-filters button.select-trigger[aria-label^="' + args[0] + ':"]');
        return document.getElementById(trigger?.getAttribute('aria-activedescendant') ?? '')?.textContent.trim() ?? null;`, control);
      if (active === option) { await actor.press('Enter'); return; }
      await actor.press('ArrowDown');
    }
    throw new SafeFailure(`${actor.label}: «${control}» has no option «${option}»`);
  };
  const focusedName = actor => actor.page(`const e = document.activeElement; return e ? { tag: e.tagName, text: (e.getAttribute('aria-label') ?? e.textContent ?? '').trim().slice(0, 60), visible: e.matches(':focus-visible') } : null;`);

  // ---- the seed -----------------------------------------------------------------------------------------------------------------------
  const seedDeck = async (bearer, spec, { visibility, materials = 10, withExercise = true }) => {
    const created = await learning('POST', '/api/decks', { bearer, payload: { commandId: randomUUID(), metadata: { title: spec.title, description: spec.description } } });
    need(created.status === 201, `${spec.title}: deck creation answered ${created.status}`);
    const deckId = created.body.deck?.deckId ?? created.body.deckId;
    need(deckId, `${spec.title}: the created deck has no id`);
    let deck = await learning('GET', `/api/decks/${deckId}`, { bearer });
    need(deck.status === 200, `${spec.title}: deck read answered ${deck.status}`);
    const keys = Array.from({ length: materials }, () => randomUUID());
    const bulk = await learning('POST', `/api/decks/${deckId}/items/publications`, { bearer, headers: { 'If-Match': deck.etag }, payload: {
      commandId: randomUUID(), expectedDeckRevisionId: deck.body.revisionId,
      changes: keys.map((memberKey, index) => ({ operation: 'create', memberKey, document: nativeDocument([`${spec.title}: карточка ${index + 1}`]) })) } });
    need(bulk.status === 200, `${spec.title}: the bulk of materials answered ${bulk.status} ${JSON.stringify(bulk.body?.code ?? bulk.body?.detail ?? null)}`);
    if (withExercise) {
      deck = await learning('GET', `/api/decks/${deckId}`, { bearer });
      const item = await learning('GET', `/api/decks/${deckId}/items/${keys[0]}`, { bearer });
      need(deck.status === 200 && item.status === 200, `${spec.title}: the first material cannot be read`);
      const node = item.body.document?.root?.content?.[0]?.id;
      need(node, `${spec.title}: the first material has no paragraph to quote`);
      const exercise = await learning('POST', `/api/decks/${deckId}/exercises`, { bearer, headers: { 'If-Match': deck.etag }, payload: {
        commandId: randomUUID(), expectedDeckRevisionId: deck.body.revisionId, objective: { operation: 'create', title: 'Слово: проверка' },
        exercise: { type: 'FREE_RESPONSE', schemaVersion: 2, enabled: true, subject: { memberKey: item.body.memberKey, itemRevisionId: item.body.itemRevisionId },
          content: { prompt: [{ kind: 'TEXT', text: 'Введите слово «проверка»' }],
            reference: [{ kind: 'MATERIAL', memberKey: item.body.memberKey, itemRevisionId: item.body.itemRevisionId, nodeId: node }], responseInput: 'TEXT' },
          answerKey: { kind: 'TEXT', accepted: ['проверка'], normalization: ['UNICODE_NFC', 'TRIM', 'CASE_FOLD'], matchingMode: 'STRICT' },
          evaluatorPolicy: { id: 'deterministic-text', version: '1' } } } });
      need(exercise.status === 201, `${spec.title}: the exercise answered ${exercise.status} ${JSON.stringify(exercise.body?.code ?? exercise.body?.detail ?? null)}`);
    }
    deck = await learning('GET', `/api/decks/${deckId}`, { bearer });
    const published = await learning('PUT', `/api/decks/${deckId}/publication`, { bearer, headers: { 'If-Match': '"0"' }, payload: {
      commandId: randomUUID(), visibility,
      metadata: { topicId: spec.topicId, contentLanguage: spec.content, targetLanguage: spec.target, level: spec.level, tags: spec.tags },
      requestsEnabled: true, publish: { expectedHeadRevisionId: deck.body.revisionId, releaseNote: null } } });
    need(published.status === 200, `${spec.title}: the publication answered ${published.status} ${JSON.stringify(published.body?.code ?? published.body?.failed ?? null)}`);
    const state = published.body.publication;
    return { deckId, code: state.publicCode, link: state.link, title: spec.title };
  };
  const consent = async (bearer) => {
    const sent = await owner.page(`const headers = { Authorization: 'Bearer ' + args[1], 'Content-Type': 'application/json' };
      const response = await fetch(args[0] + '/api/accounts/me/public-profile', { method: 'PUT', credentials: 'omit', headers,
        body: JSON.stringify({ enabled: true, showDisplayName: false, showAvatar: false, showBio: false, textVersion: '2026-10-10' }) });
      return { status: response.status, body: await response.json() };`, config.identity, bearer);
    need(sent.status === 200 && sent.body.publishReady === true, 'the public profile consent was not accepted: ' + sent.status);
  };
  const rankState = () => fixture('/__fixture/catalog-rank', {});
  /** Enqueues one more generation and waits until it is complete, then until `sort=popular` of the API shows `total` decks. */
  const newGeneration = async (total) => {
    const before = await rankState();
    await fixture('/__fixture/catalog-rank', { enqueue: true });
    await until(async () => (await rankState()).generations > before.generations, 'the rank generation was not built', 60000);
    if (total !== undefined) {
      await until(async () => (await publicGet('/decks?sort=popular&limit=1')).body?.total === total, `sort=popular does not list ${total} decks`, 20000);
    }
  };
  const listedFromApi = async () => {
    const titles = [];
    let cursor = null;
    do {
      const page = await publicGet(`/decks?sort=new&limit=48${cursor === null ? '' : '&cursor=' + encodeURIComponent(cursor)}`);
      need(page.status === 200, 'the browse list answered ' + page.status);
      titles.push(...page.body.decks);
      cursor = page.body.nextCursor;
    } while (cursor !== null);
    return titles;
  };
  const addedCountOf = async code => {
    let cursor = null;
    do {
      const page = await publicGet(`/decks?sort=new&limit=48${cursor === null ? '' : '&cursor=' + encodeURIComponent(cursor)}`);
      const found = page.body.decks.find(card => card.code === code);
      if (found) return found.addedCount;
      cursor = page.body.nextCursor;
    } while (cursor !== null);
    return undefined;
  };

  // ==== 1. the empty catalogue ====================================================================================================================
  await stage('empty', [], async () => {
    // The schedule's own first tick builds the first generation while the catalogue is still empty: wait for it (the hourly job, not one of ours).
    await until(async () => (await rankState()).scheduledJobsDone >= 1, 'the rank schedule did not build its first generation', 60000);
    const facets = await publicGet('/facets');
    const shelves = await publicGet('/shelves');
    need(facets.status === 200 && facets.body.listedCount === 0 && facets.body.topics.length === 0, 'the empty catalogue says ' + JSON.stringify(facets.body));
    need(shelves.status === 200 && shelves.body.listedCount === 0 && shelves.body.shelves.length === 0, 'the empty catalogue has shelves');
    await openCommunity(guest);
    need(await guest.has(WORDS.empty) && await guest.has(WORDS.emptyBody), 'the approved empty text is not shown');
    const view = await guest.page(`return { shelves: document.querySelectorAll('app-deck-shelf').length, grids: document.querySelectorAll('app-deck-grid li').length,
      filters: document.querySelectorAll('app-catalog-filters').length, book: document.querySelectorAll('app-community-page app-book-search input[type="search"]').length,
      h1: document.querySelector('h1')?.textContent.trim(), signIn: document.querySelector('app-community-empty a.button.primary')?.textContent.trim(),
      how: document.querySelector('details.how')?.open, label: document.querySelector('app-community-page app-book-search label')?.textContent.trim() };`);
    need(view.shelves === 0 && view.grids === 0 && view.filters === 0, 'the empty Community draws shelves, cards or filters: ' + JSON.stringify(view));
    need(view.book === 1 && view.label === 'Найти колоду', 'the book search is not there');
    need(view.h1 === 'Сообщество' && view.signIn === 'Войти' && view.how === false, 'heading, sign-in or the closed disclosure is wrong: ' + JSON.stringify(view));
    const marks = await landmarks(guest);
    need(marks.search === 1 && marks.h1 === 1, `search landmarks ${marks.search}, h1 ${marks.h1}`);
    // «Как поделиться колодой» by keyboard: Tab reaches the summary, Enter opens it, Enter closes it, Space opens it again.
    const reached = await guest.tabTo(`element => element.matches('details.how > summary')`, '«Как поделиться колодой»');
    need(reached.focusVisible, 'the summary has no keyboard focus ring');
    await guest.press('Enter');
    need(await guest.page(`return document.querySelector('details.how').open;`), 'Enter did not open the disclosure');
    const steps = await guest.page(`return [...document.querySelectorAll('details.how li')].map(node => node.textContent.trim());`);
    need(JSON.stringify(steps) === JSON.stringify(WORDS.steps), 'the three steps differ from the approved ones: ' + JSON.stringify(steps));
    await shoot(guest, 'catalog-desktop-empty-guest.png', { full: true });
    await guest.press('Enter');
    need(!(await guest.page(`return document.querySelector('details.how').open;`)), 'Enter did not close the disclosure');
    await guest.press('Space');
    need(await guest.page(`return document.querySelector('details.how').open;`), 'Space did not open the disclosure');
    // The signed-in reader is led to «Мои колоды».
    await openCommunity(member);
    need(await member.page(`return document.querySelector('app-community-empty a.button.primary')?.textContent.trim() === 'Мои колоды';`), 'a signed-in reader is not led to «Мои колоды»');
    // The same page on a phone.
    await openCommunity(guest, '/community', PHONE);
    const phone = await landmarks(guest);
    need(!phone.overflow && phone.search === 1, 'the empty Community overflows or has no single search at 390 px: ' + JSON.stringify(phone));
    await shoot(guest, 'catalog-mobile-empty-guest.png', { full: true });
    return { facetsEmpty: true, shelvesEmpty: true, approvedText: true, disclosureByKeyboard: true, steps: steps.length, singleSearchLandmark: true, scheduledGenerationBuilt: true };
  });

  // ==== 2. the seed ================================================================================================================================
  const seeded = await stage('seed', [], async () => {
    const login = await owner.page('return document.querySelector("#profile-username")?.value ?? null;');
    // The first account's login is its profile name; read it from Identity with its own bearer.
    const me = await owner.page(`const response = await fetch(args[0] + '/api/accounts/me', { credentials: 'omit', headers: { Authorization: 'Bearer ' + args[1] } });
      return { status: response.status, body: await response.json() };`, config.identity, ownerBearer);
    need(me.status === 200, 'the author profile answered ' + me.status);
    world.ownerLogin = me.body.profileUsername ?? login;
    world.ownerId = me.body.accountId ?? me.body.id;
    need(/^[A-Za-z0-9_.-]{3,50}$/u.test(world.ownerLogin ?? '') && world.ownerId, 'the author has no login or id');
    const learner = await member.page(`const response = await fetch(args[0] + '/api/accounts/me', { credentials: 'omit', headers: { Authorization: 'Bearer ' + args[1] } });
      return { status: response.status, body: await response.json() };`, config.identity, memberBearer);
    need(learner.status === 200, 'the learner profile answered ' + learner.status);
    world.memberLogin = learner.body.profileUsername;
    await consent(ownerBearer);
    const [filler, hidden] = await fixture('/__fixture/catalog-accounts', { names: ['catalog_shelf_author', 'catalog_no_consent'] });
    world.fillerBearer = filler.token;
    world.fillerLogin = filler.name;
    world.hiddenLogin = hidden.name;
    await consent(filler.token);
    for (const spec of NAMED) world.decks[spec.key] = await seedDeck(ownerBearer, spec, { visibility: 'PUBLIC' });
    world.decks.small = await seedDeck(ownerBearer, SMALL, { visibility: 'PUBLIC', materials: SMALL.materials });
    world.decks.link = await seedDeck(ownerBearer, LINKED, { visibility: 'LINK' });
    world.decks.invite = await seedDeck(ownerBearer, INVITED, { visibility: 'INVITE' });
    // The learner gets into the invitation-only deck the way a person does: with a one-time link.
    const invite = await learning('POST', `/api/decks/${world.decks.invite.deckId}/invites`, { bearer: ownerBearer, payload: { maxUses: 1, expiresInDays: 7 } });
    need(invite.status === 201 || invite.status === 200, 'the invitation answered ' + invite.status);
    const token = invite.body.token;
    need(typeof token === 'string' && token.length === 43, 'the invitation carries no token');
    const redeemed = await learning('POST', '/api/invites:redeem', { bearer: memberBearer, payload: { token } });
    need(redeemed.status === 200, 'the learner could not redeem the invitation: ' + redeemed.status);
    for (let index = 1; index <= FILLERS; index++) {
      const spec = { title: `Столицы мира, часть ${String(index).padStart(2, '0')}`, description: `Подборка №${index} для проверки длинного списка.`, topicId: 'other', content: 'ru', target: null, level: 'BEGINNER', tags: [] };
      world.fillerCodes.push((await seedDeck(world.fillerBearer, spec, { visibility: 'PUBLIC' })).code);
    }
    // The projection jobs are the real queue's: wait until the facets know every listed deck.
    await until(async () => (await publicGet('/facets')).body?.listedCount === LISTED_TOTAL, `the projection did not list ${LISTED_TOTAL} decks`, 120000);
    await newGeneration(LISTED_TOTAL);
    const facets = (await publicGet('/facets')).body;
    return { listed: facets.listedCount, namedListed: LISTED_NAMED, fillers: FILLERS, belowThreshold: SMALL.materials, linkDeck: true, inviteDeck: true, topics: facets.topics.map(topic => `${topic.id}:${topic.count}`),
      rankGenerations: (await rankState()).generations };
  });

  // ==== 3. the shelves ============================================================================================================================
  await stage('shelves', ['seed'], async () => {
    await openCommunity(guest);
    const shelves = await guest.page(`return [...document.querySelectorAll('app-deck-shelf')].map(node => ({ title: node.querySelector('h2.title')?.textContent.trim(),
      cards: node.querySelectorAll('app-public-deck-card').length, more: node.querySelector('a.more')?.getAttribute('aria-label') ?? null }));`);
    const byTitle = Object.fromEntries(shelves.map(shelf => [shelf.title, shelf]));
    need(byTitle['Новое'] && byTitle['Новое'].cards >= 4, '«Новое» is not shown with at least four decks: ' + JSON.stringify(shelves));
    need(byTitle['Английский']?.cards === 4, 'the English topic shelf (four decks) is not shown: ' + JSON.stringify(shelves));
    need(byTitle['Другое'] && byTitle['Другое'].cards >= 4, 'the filler topic shelf is not shown: ' + JSON.stringify(shelves));
    need(!byTitle['Испанский'] && !byTitle['SQL и данные'], 'a topic shelf with fewer than four decks is shown: ' + JSON.stringify(shelves));
    need(!byTitle['Выбор Мнемы'] && !byTitle['Учат сейчас'], 'an editorial or learning shelf is shown with nothing in it: ' + JSON.stringify(shelves));
    need(shelves.every(shelf => shelf.cards >= 4), 'a shelf with fewer than four cards is drawn');
    const hidden = [SMALL.title, LINKED.title, INVITED.title];
    const shown = await titlesOf(guest);
    need(!shown.some(title => hidden.includes(title)), 'a deck below the threshold, a LINK or an INVITE deck is on a shelf');
    const marks = await landmarks(guest);
    need(marks.search === 1 && marks.h1 === 1 && !marks.overflow, 'landmarks or overflow: ' + JSON.stringify(marks));
    const none = (await cardsOf(guest)).filter(card => card.audience.length > 0);
    need(none.length === 0, 'a card shows an audience before anybody copied a deck: ' + JSON.stringify(none));
    await shoot(guest, 'catalog-desktop-shelves-guest.png', { full: true });
    // «Все» of «Новое» by keyboard opens the browse list with the shelf's own query in the address.
    await guest.focus('app-deck-shelf a.more[aria-label="Все колоды: Новое"]');
    await guest.press('Enter');
    await until(async () => (await guest.location()) === '/community' && (await guest.search()) === '?sort=new', '«Все» did not lead to /community?sort=new');
    await until(() => settled(guest), 'the browse list stayed loading');
    need(await guest.page(`return document.querySelectorAll('app-deck-grid app-public-deck-card').length > 4 && document.querySelectorAll('app-deck-shelf').length === 0;`), 'the browse list is not shown');
    const focus = await focusedName(guest);
    need(focus !== null && ['H1', 'MAIN'].includes(focus.tag) && focus.visible, 'the focus after the route change is lost or not visible: ' + JSON.stringify(focus));
    await shoot(guest, 'catalog-desktop-browse-guest.png');
    // A phone: the shelves are rows that scroll sideways; the page does not.
    await openCommunity(guest, '/community', PHONE);
    const phone = await guest.page(`const rows = [...document.querySelectorAll('app-deck-shelf ul.row')];
      return { rows: rows.length, sideways: rows.filter(row => row.scrollWidth > row.clientWidth).length, overflow: document.documentElement.scrollWidth > document.documentElement.clientWidth };`);
    need(phone.rows >= 3 && phone.sideways === phone.rows && !phone.overflow, 'the shelves are not rows that scroll sideways at 390 px: ' + JSON.stringify(phone)
      + (phone.overflow ? ' widest ' + JSON.stringify(await widest(guest, 5)) : ''));
    await shoot(guest, 'catalog-mobile-shelves-guest.png', { full: true });
    return { shelves: shelves.map(shelf => `${shelf.title}:${shelf.cards}`), hiddenShelves: ['Испанский', 'SQL и данные', 'Выбор Мнемы', 'Учат сейчас'], allLinkLeadsToBrowse: true, focusAfterRoute: focus.tag, noAudienceYet: true, phoneRowsScroll: phone.rows };
  });

  // ==== 4. the search ============================================================================================================================
  await stage('search', ['seed'], async () => {
    await openCommunity(guest, '/community');
    await watchAnnouncements(guest);
    const decksRequests = () => guest.wire.filter(entry => entry.path === '/api/public/community/decks').length;
    const before = decksRequests();
    await typeInBook(guest, 'испанский');
    await sleep(900);
    need(decksRequests() === before, 'typing sent a request: there must be no live search');
    await guest.press('Enter');
    await until(async () => (await guest.search()) === '?q=%D0%B8%D1%81%D0%BF%D0%B0%D0%BD%D1%81%D0%BA%D0%B8%D0%B9', 'Enter did not put the text into the address');
    await until(async () => (await countText(guest)).length === 1, 'the result count was not shown');
    const spanish = ['Испанские глаголы', 'Spanish verbs for travel', 'Español para principiantes'];
    const found = await titlesOf(guest);
    need(JSON.stringify([...found].sort()) === JSON.stringify([...spanish].sort()), '«испанский» finds ' + JSON.stringify(found));
    need(JSON.stringify(await countText(guest)) === JSON.stringify(['Нашли 3 колоды']), 'the count text is ' + JSON.stringify(await countText(guest)));
    need((await announced(guest)).length === 1, 'the count was announced ' + (await announced(guest)).length + ' times');
    need(await guest.page(`return document.querySelector('app-deck-grid .status')?.getAttribute('role') === 'status';`), 'the count has no status region');
    // With a text the sorting is enabled and starts with «По близости к запросу», which is the checked one when the address names no sort.
    const sorting = await guest.page(`const group = document.querySelector('app-catalog-filters app-segmented-choice.sort fieldset');
      return { disabled: group?.disabled === true, note: document.querySelector('app-catalog-filters .sort-note') !== null,
        first: group?.querySelector('label')?.textContent.trim(), checked: group?.querySelector('input:checked')?.value };`);
    need(!sorting.disabled && !sorting.note && sorting.first === WORDS.relevance && sorting.checked === 'relevance', 'the sorting of a search is ' + JSON.stringify(sorting));
    await shoot(guest, 'catalog-desktop-search-guest.png', { full: true });
    // The same three for the English word of the same topic.
    await typeInBook(guest, 'Spanish');
    await guest.press('Enter');
    await until(async () => (await guest.search()) === '?q=Spanish', '«Spanish» did not reach the address');
    await until(async () => (await countText(guest)).length === 1 && (await titlesOf(guest)).length > 0, 'the second search did not finish');
    const english = await titlesOf(guest);
    need(JSON.stringify([...english].sort()) === JSON.stringify([...spanish].sort()), '«Spanish» finds ' + JSON.stringify(english));
    // A typo still finds the deck, first; and leaves out the English phrasal verbs that share one word.
    await typeInBook(guest, 'испнские глаголы');
    await guest.press('Enter');
    await until(async () => (await guest.search()).startsWith('?q=%D0%B8%D1%81%D0%BF%D0%BD'), 'the typo query did not reach the address');
    await until(async () => (await titlesOf(guest)).length > 0 && (await titlesOf(guest))[0] !== undefined && (await guest.read('app-deck-grid p.count')) !== null, 'the typo search did not finish');
    const typo = await titlesOf(guest);
    need(typo[0] === 'Испанские глаголы', 'the typo search puts «' + typo[0] + '» first: ' + JSON.stringify(typo));
    need(!typo.includes('Английские фразовые глаголы'), 'the typo search finds the English phrasal verbs');
    // The announcements: one per search, none from the continuation of the same list.
    const announcements = await announced(guest);
    need(announcements.length === 3 && announcements[0] === 'Нашли 3 колоды', 'three searches were announced as ' + JSON.stringify(announcements));
    // Decks that are not in the catalogue never appear, by their own words either (the API, with and without a bearer, and the page).
    for (const word of ['ниже порога', 'по ссылке для друзей', 'только для приглашённых', 'каталога']) {
      for (const bearer of [null, ownerBearer, memberBearer]) {
        const answer = await publicGet('/decks?q=' + encodeURIComponent(word), bearer);
        need(answer.status === 200 && answer.body.decks.every(card => ![SMALL.title, LINKED.title, INVITED.title].includes(card.title)), `«${word}» finds a deck that is not in the catalogue`);
      }
    }
    await typeInBook(guest, 'ниже порога');
    await guest.press('Enter');
    await until(async () => (await guest.search()).startsWith('?q=%D0%BD%D0%B8%D0%B6%D0%B5'), 'the query for the small deck did not reach the address');
    await until(async () => (await countText(guest)).includes(WORDS.nothing), 'the empty result sentence is not shown: ' + JSON.stringify(await countText(guest)));
    await openCommunity(guest, '/community?q=%D0%B8%D1%81%D0%BF%D0%B0%D0%BD%D1%81%D0%BA%D0%B8%D0%B9', PHONE);
    need((await titlesOf(guest)).length === 3 && !(await landmarks(guest)).overflow, 'the search at 390 px');
    await shoot(guest, 'catalog-mobile-search-guest.png', { full: true });
    return { noLiveSearch: true, spanishRu: found.length, spanishEn: english.length, typoFirst: typo[0], countAnnouncedOnce: true, announcements: announcements.length, hiddenDecksNeverFound: true };
  });

  // ==== 5. the filters ============================================================================================================================
  await stage('filters', ['seed'], async () => {
    await openCommunity(guest, '/community');
    await guest.focus('app-catalog-filters button.select-trigger[aria-label^="Тема:"]');
    await choose(guest, 'Тема', 'Английский');
    await until(async () => (await guest.search()) === '?topic=english', 'the topic did not reach the address');
    await until(async () => (await countText(guest)).join() === 'Нашли 4 колоды', 'four English decks expected: ' + JSON.stringify(await countText(guest)));
    await choose(guest, 'Уровень', 'A2');
    await until(async () => (await guest.search()) === '?topic=english&level=A2', 'the level did not reach the address');
    await until(async () => (await countText(guest)).join() === 'Нашли 2 колоды', 'two A2 English decks expected: ' + JSON.stringify(await countText(guest)));
    // Упражнения → «Письменный ответ», Внутри → «Аудио»: the checkboxes are reached by their labels.
    const tick = async label => {
      await guest.page(`const box = [...document.querySelectorAll('app-catalog-filters label.check-row')].find(row => row.textContent.trim() === args[0])?.querySelector('input'); box.focus(); return document.activeElement === box;`, label);
      await guest.press('Space');
    };
    await tick('Письменный ответ');
    await until(async () => (await guest.search()) === '?topic=english&level=A2&mechanics=written', 'the exercise filter did not reach the address: ' + await guest.search());
    await until(async () => (await countText(guest)).join() === 'Нашли 2 колоды', 'two decks expected with the written exercise');
    await tick('Аудио');
    await until(async () => (await guest.search()) === '?topic=english&level=A2&media=audio&mechanics=written', 'the media filter did not reach the address: ' + await guest.search());
    await until(async () => (await countText(guest)).join() === WORDS.nothing, 'no deck has audio: the sentence for no result is expected, got ' + JSON.stringify(await countText(guest)));
    const chips = () => guest.page(`return [...document.querySelectorAll('app-catalog-filters ul.chips button.chip')].map(button => button.getAttribute('aria-label'));`);
    const expectedChips = ['Убрать фильтр «Тема: Английский»', 'Убрать фильтр «Уровень: A2»', 'Убрать фильтр «Внутри: аудио»', 'Убрать фильтр «Упражнения: письменный ответ»'];
    need(JSON.stringify(await chips()) === JSON.stringify(expectedChips), 'the chips are ' + JSON.stringify(await chips()));
    await shoot(guest, 'catalog-desktop-filters-guest.png', { full: true });
    // A reload restores the filters, the chips and the count of the filters in the button.
    const url = (await guest.location()) + (await guest.search());
    await navigate(url, guest.tab);
    await until(() => settled(guest), 'the reloaded list stayed loading', 30000);
    need(JSON.stringify(await chips()) === JSON.stringify(expectedChips), 'the chips after a reload are ' + JSON.stringify(await chips()));
    need((await guest.read('app-catalog-filters button.disclosure')) === 'Фильтры · 4', 'the filters button after a reload reads ' + await guest.read('app-catalog-filters button.disclosure'));
    // Removing a chip moves the focus to the chip that took its place; the last chip leaves the focus on the one before.
    await guest.enter('app-catalog-filters ul.chips button.chip[aria-label="Убрать фильтр «Внутри: аудио»"]');
    await until(async () => (await chips()).length === 3, 'the chip was not removed');
    await until(() => focusedName(guest).then(focus => focus?.text === 'Убрать фильтр «Упражнения: письменный ответ»'), 'the focus did not move to the next chip: ' + JSON.stringify(await focusedName(guest)));
    need((await focusedName(guest)).visible, 'the focus ring is not visible after removing a chip');
    await until(async () => (await countText(guest)).join() === 'Нашли 2 колоды', 'the list did not come back after removing the media chip');
    // «Сбросить всё» leaves the sorting alone. Where the «Фильтры» button is not drawn (a wide page) the focus goes to the first control of the panel.
    await guest.enter('app-catalog-filters button.quiet', 'Сбросить всё');
    await until(async () => (await guest.search()) === '', 'the reset left filters in the address: ' + await guest.search());
    await until(async () => (await guest.read('app-catalog-filters button.disclosure')) === 'Фильтры', 'the button still counts filters');
    await until(() => focusedName(guest).then(focus => focus?.text === 'Тема: Любая'), 'the focus did not go to the first control of the filters: ' + JSON.stringify(await focusedName(guest)));
    await until(() => exists('app-deck-shelf', guest.tab), 'the shelves did not come back after the reset');
    // The sorting by keyboard: ArrowRight over the radios waits for the last key, then the address follows.
    await guest.focus('app-catalog-filters app-segmented-choice.sort input[type="radio"]:checked');
    await guest.press('ArrowRight');
    await guest.press('ArrowRight');
    await until(async () => (await guest.search()) === '?sort=new', 'the sorting did not reach the address: ' + await guest.search(), 8000);
    // The phone: the filters sit behind «Фильтры»; the button says so and is a real disclosure; after the reset the focus is on it.
    await openCommunity(guest, '/community?topic=english&level=A2&mechanics=written', PHONE);
    const toggle = await guest.page(`const button = document.querySelector('app-catalog-filters button.disclosure');
      return { expanded: button.getAttribute('aria-expanded'), label: button.textContent.trim(), shown: button.getBoundingClientRect().height > 0 };`);
    need(toggle.expanded === 'false' && toggle.label === 'Фильтры · 3' && toggle.shown, 'the phone filters button: ' + JSON.stringify(toggle));
    await guest.enter('app-catalog-filters button.disclosure');
    need(await guest.page(`return document.querySelector('app-catalog-filters button.disclosure').getAttribute('aria-expanded') === 'true';`), 'Enter did not open the filters on a phone');
    need(!(await landmarks(guest)).overflow, 'the filters overflow at 390 px');
    await shoot(guest, 'catalog-mobile-filters-guest.png', { full: true });
    await guest.enter('app-catalog-filters ul.chips button.chip[aria-label="Убрать фильтр «Уровень: A2»"]');
    await until(() => focusedName(guest).then(focus => focus?.text === 'Убрать фильтр «Упражнения: письменный ответ»' && focus.visible), 'the focus did not move to the next chip on a phone: ' + JSON.stringify(await focusedName(guest)));
    await guest.enter('app-catalog-filters button.quiet', 'Сбросить всё');
    await until(async () => (await guest.search()) === '', 'the reset on a phone left filters in the address');
    await until(() => focusedName(guest).then(focus => focus?.text === 'Фильтры' && focus.visible), 'the focus did not go to the «Фильтры» button on a phone: ' + JSON.stringify(await focusedName(guest)));
    return { topic: 'english', level: 'A2', mechanics: 'written', media: 'audio', chips: 4, reloadRestores: true, chipRemovalFocus: true, resetFocusDesktop: 'first control', resetFocusPhone: 'Фильтры', sortByKeyboard: true, phoneDisclosure: true };
  });

  // ==== 6. the infinite scroll =====================================================================================================================
  await stage('scroll', ['seed'], async () => {
    await openCommunity(guest, '/community?sort=new');
    await until(async () => (await cardsOf(guest)).length >= 24, 'the first page did not show 24 cards');
    const first = (await cardsOf(guest)).length;
    need(first === 24, `the first page shows ${first} cards, not 24`);
    const forbidden = await guest.page(`return [...document.querySelectorAll('a, button')].map(node => node.textContent.trim()).filter(text => /показать ещё|загрузить ещё|следующая|ещё колоды/i.test(text));`);
    need(forbidden.length === 0, 'the list has a page or «show more» button: ' + JSON.stringify(forbidden));
    // Real scroll to the end of the page: the next page arrives by itself.
    await until(async () => {
      await guest.page('window.scrollTo(0, document.documentElement.scrollHeight); return true;');
      await sleep(300);
      return (await cardsOf(guest)).length === LISTED_TOTAL;
    }, `the list did not continue to ${LISTED_TOTAL} cards`, 30000);
    const shown = (await cardsOf(guest)).map(card => card.title);
    need(new Set(shown).size === shown.length, 'the continued list repeats a deck');
    const expected = (await listedFromApi()).map(card => card.title);
    need(JSON.stringify(shown) === JSON.stringify(expected), 'the continued list is not the server order');
    const requests = guest.wire.filter(entry => entry.path === '/api/public/community/decks' && entry.status === 200).length;
    need(guest.wire.filter(entry => entry.path === '/api/public/community/decks' && entry.status !== null && entry.status >= 400).length === 0, 'a list request failed');
    await sleep(600);
    need((await cardsOf(guest)).length === LISTED_TOTAL, 'the list grew past the catalogue');
    await shoot(guest, 'catalog-desktop-scroll-guest.png');
    return { firstPage: first, continued: shown.length, unique: true, serverOrder: true, noButtons: true, requests };
  });

  // ==== 7. the counts ==============================================================================================================================
  const target = NAMED[0];
  const copiers = [];
  await stage('counts', ['seed'], async () => {
    const code = world.decks[target.key].code;
    const slug = world.decks[target.key].link.split('/').at(-1);
    // The learner finds the deck through the search and copies it with the keyboard.
    at = 'UI: the learner opens the search';
    await openCommunity(member, '/community?q=' + encodeURIComponent(target.title));
    await until(async () => (await titlesOf(member)).includes(target.title), 'the learner does not find the deck');
    at = 'UI: the learner opens the card';
    await member.enter('app-public-deck-card .title a', target.title);
    await until(async () => (await member.location()) === world.decks[target.key].link, 'the card did not lead to the deck page: ' + await member.location());
    await until(() => exists('.primary-slot button.primary', member.tab), 'the deck page has no «Добавить к себе»', 30000);
    at = 'UI: the learner copies the deck by keyboard';
    const reached = await member.tabTo(`element => element.matches('.primary-slot button.primary')`, '«Добавить к себе»');
    await member.press('Enter');
    await until(async () => /^\/decks\/[0-9a-f-]{36}$/u.test((await member.location()).split('?')[0]), 'the copy did not open its hub', 30000);
    // One copy: nothing is shown. The rank must see it first.
    await newGeneration();
    need(await addedCountOf(code) === null, 'one copy is visible in the API');
    at = 'UI: the guest reads the card after one copy';
    await openCommunity(guest, '/community?q=' + encodeURIComponent(target.title));
    const one = (await cardsOf(guest)).find(card => card.title === target.title);
    need(one && one.audience.length === 0, 'the card shows an audience after one copy: ' + JSON.stringify(one));
    // The author cannot copy his own deck, and trying changes nothing.
    const own = await learning('POST', '/api/decks/forks', { bearer: ownerBearer, payload: { commandId: randomUUID(), sourceCode: code } });
    need(own.status === 400 || own.status === 409 || own.status === 422, 'the author copied his own deck: ' + own.status);
    // Eight more people (nine in all): still nothing, the boundary is ten.
    for (let from = 0; from < 9; from += 3) {
      const names = [1, 2, 3].map(offset => `catalog_copier_${from + offset}`);
      copiers.push(...await fixture('/__fixture/catalog-accounts', { names }));
    }
    for (const person of copiers.slice(0, 8)) {
      const forked = await learning('POST', '/api/decks/forks', { bearer: person.token, payload: { commandId: randomUUID(), sourceCode: code } });
      need(forked.status === 200 || forked.status === 201 || forked.status === 202, `${person.name} could not copy: ${forked.status}`);
    }
    await newGeneration();
    need(await addedCountOf(code) === null, 'nine copies are visible in the API');
    await openCommunity(guest, '/community?q=' + encodeURIComponent(target.title));
    need((await cardsOf(guest)).find(card => card.title === target.title)?.audience.length === 0, 'the card shows an audience after nine copies');
    // The tenth copy: «Добавили 10».
    const last = await learning('POST', '/api/decks/forks', { bearer: copiers[8].token, payload: { commandId: randomUUID(), sourceCode: code } });
    need(last.status === 200 || last.status === 201 || last.status === 202, 'the ninth extra person could not copy: ' + last.status);
    await newGeneration();
    need(await addedCountOf(code) === 10, 'ten copies are not shown as 10 in the API: ' + await addedCountOf(code));
    await openCommunity(guest, '/community?q=' + encodeURIComponent(target.title));
    const ten = (await cardsOf(guest)).find(card => card.title === target.title);
    need(ten && ten.audience.some(line => line === 'Добавили 10'), 'the card does not read «Добавили 10»: ' + JSON.stringify(ten));
    // The author opens his own deck and the catalogue again: nothing moves (his own account never counts).
    await openCommunity(owner, '/community?q=' + encodeURIComponent(target.title));
    await owner.enter('app-public-deck-card .title a', target.title);
    await until(async () => (await owner.location()) === world.decks[target.key].link, 'the author could not open his own deck');
    await newGeneration();
    need(await addedCountOf(code) === 10, 'the author\'s own visit changed the audience: ' + await addedCountOf(code));
    await openCommunity(guest, '/community?q=' + encodeURIComponent(target.title));
    need((await cardsOf(guest)).find(card => card.title === target.title)?.audience.some(line => line === 'Добавили 10'), 'the card lost «Добавили 10»');
    await shoot(guest, 'catalog-desktop-counts-guest.png');
    const raw = await learning('GET', `/api/public/community/decks?q=${encodeURIComponent(target.title)}`, { bearer: null });
    need(!JSON.stringify(raw.body).match(/"(score|rank|deckId|revisionId)"/u), 'a card carries a score, a rank, a deck id or a revision');
    return { oneCopy: 'hidden', nineCopies: 'hidden', tenCopies: 'Добавили 10', authorOwnCopy: own.status, authorVisitNoChange: true, keyboardCopy: true, tabPresses: reached.presses, noInternalsOnCards: true };
  });

  // ==== 8. the author's page =======================================================================================================================
  await stage('author', ['seed'], async () => {
    const path = '/u/' + world.ownerLogin;
    const read = async actor => {
      await actor.front(); await actor.metrics(DESKTOP);
      await navigate(path, actor.tab);
      await until(() => exists('app-author-page h1, app-access-screen h1, app-access-screen h2', actor.tab), 'the author page did not render', 30000);
      await until(() => actor.page(`return document.querySelector('app-author-page .status')?.textContent.trim() === '' && (document.querySelector('app-author-page app-deck-grid li') !== null || document.querySelector('app-author-page .empty-state') !== null);`), 'the author page stayed loading', 30000);
      return actor.page(`return { name: document.querySelector('app-author-header h1')?.textContent.trim(), self: document.querySelector('app-author-header .notice.self')?.textContent.replace(/\\s+/g, ' ').trim() ?? null,
        h2: document.querySelector('#author-decks-title')?.textContent.trim() };`);
    };
    // The guest.
    const guestHeader = await read(guest);
    need(guestHeader.name === world.ownerLogin || guestHeader.name === '@' + world.ownerLogin, 'the header reads ' + JSON.stringify(guestHeader));
    need(guestHeader.self === null && guestHeader.h2 === 'Колоды автора', 'a guest sees the self notice or no deck list: ' + JSON.stringify(guestHeader));
    const guestCards = await cardsOf(guest);
    const guestTitles = guestCards.map(card => card.title);
    need(guestTitles.includes(SMALL.title), 'the deck below the threshold is not in the author\'s list');
    need(NAMED.every(spec => guestTitles.includes(spec.title)), 'a listed deck is missing from the author\'s list: ' + JSON.stringify(guestTitles));
    need(!guestTitles.includes(INVITED.title) && !guestTitles.includes(LINKED.title), 'a guest sees the LINK or the INVITE deck: ' + JSON.stringify(guestTitles));
    need(!(await guest.has(WORDS.granted)), 'a guest sees «Доступна вам»');
    const marks = await landmarks(guest);
    need(marks.h1 === 1 && marks.search === 1 && !marks.overflow, 'landmarks of the author page: ' + JSON.stringify(marks));
    await shoot(guest, 'catalog-desktop-author-guest.png', { full: true });
    // The learner who holds the grant: first what the server says to him, then what the page shows.
    const server = await publicGet(`/authors/${world.ownerId}/decks?limit=48`, memberBearer);
    need(server.status === 200 && server.body.decks.some(entry => entry.access === 'GRANTEE' && entry.deck.code === world.decks.invite.code), 'the server does not list the INVITE deck for the granted learner: ' + JSON.stringify(server.body?.decks?.map(entry => entry.access + ':' + entry.deck.title)));
    const stranger = await publicGet(`/authors/${world.ownerId}/decks?limit=48`, null);
    need(stranger.body.decks.every(entry => entry.access === 'PUBLIC'), 'the server lists a GRANTEE deck for a guest');
    await read(member);
    const memberCards = await cardsOf(member);
    const invitedCard = memberCards.find(card => card.title === INVITED.title);
    need(invitedCard && invitedCard.note === WORDS.granted, 'the granted learner does not see the INVITE deck marked «Доступна вам»: ' + JSON.stringify(invitedCard));
    need(invitedCard.href === world.decks.invite.link, 'the INVITE deck leads to ' + invitedCard.href + ', not ' + world.decks.invite.link);
    need(!memberCards.some(card => card.title === LINKED.title), 'the learner sees the LINK deck');
    need(memberCards.filter(card => card.note === WORDS.granted).length === 1, 'more than one deck is marked «Доступна вам»');
    await shoot(member, 'catalog-desktop-author-member.png', { full: true });
    // The author himself: «Так ваш профиль видят другие», no invitation part, the link to the profile settings.
    const ownHeader = await read(owner);
    need(ownHeader.self?.startsWith(WORDS.self) && ownHeader.self.includes('Настроить профиль'), 'the own profile does not say how others see it: ' + JSON.stringify(ownHeader));
    need(!(await cardsOf(owner)).some(card => card.title === INVITED.title || card.note === WORDS.granted), 'the author sees his own INVITE deck as a grant');
    await owner.enter('app-author-header a', 'Настроить профиль');
    await until(async () => (await owner.location()) === '/profile', 'the link did not lead to the profile settings');
    await until(() => exists('app-public-profile-settings a.page-link', owner.tab), 'the profile settings have no link to the public page', 20000);
    await owner.enter('app-public-profile-settings a.page-link');
    await until(async () => (await owner.location()) === path, 'the link in the settings did not lead to the public page');
    // A person without consent and a name nobody has: the same «Профиль не найден».
    for (const name of [world.hiddenLogin, 'catalog_nobody_here']) {
      await navigate('/u/' + name, guest.tab);
      await until(() => guest.has(WORDS.notFound), `/u/${name} does not say «Профиль не найден»`, 30000);
      need(await guest.has(WORDS.notFoundBody), 'the not-found sentence is not the approved one');
    }
    // The phone.
    await guest.metrics(PHONE);
    await navigate(path, guest.tab);
    await until(() => exists('app-author-page app-deck-grid li', guest.tab), 'the author page did not render on a phone', 30000);
    need(!(await landmarks(guest)).overflow, 'the author page overflows at 390 px');
    await shoot(guest, 'catalog-mobile-author-guest.png', { full: true });
    return { decksForGuest: guestTitles.length, belowThresholdListed: true, inviteForGrantedOnly: true, linkAbsent: true, selfNotice: true, settingsLink: true, noConsent: 'Профиль не найден', unknownName: 'Профиль не найден' };
  });

  // ==== 9. the masthead entry ======================================================================================================================
  await stage('masthead', ['seed'], async () => {
    const entry = 'app-book-search[data-mode="closed"] button.entry';
    await guest.front(); await guest.metrics(DESKTOP);
    await navigate('/', guest.tab);
    await until(() => exists(entry, guest.tab), 'the masthead has no closed book on the home page', 30000);
    need((await guest.page(`return document.querySelector(args[0]).getAttribute('aria-expanded');`, entry)) === 'false', 'the closed book is already open');
    need(await guest.has('Сообщество'), 'the masthead has no «Сообщество»');
    await guest.enter(entry);
    await until(() => guest.page(`return document.activeElement?.matches('app-book-search[data-mode="closed"] input[type="search"]') === true;`), 'the focus did not move into the field');
    await guest.tab.call('Input.insertText', { text: 'испанский' });
    await guest.press('Enter');
    await until(async () => (await guest.location()) === '/community' && (await guest.search()) === '?q=%D0%B8%D1%81%D0%BF%D0%B0%D0%BD%D1%81%D0%BA%D0%B8%D0%B9', 'Enter did not lead to /community?q=…');
    await until(() => settled(guest), 'the search page stayed loading');
    need((await titlesOf(guest)).length === 3, 'the masthead search found ' + (await titlesOf(guest)).length + ' decks');
    need(!(await exists('app-book-search[data-mode="closed"]', guest.tab)), 'the masthead book stays on /community');
    const marks = await landmarks(guest);
    need(marks.search === 1, `/community has ${marks.search} search landmarks`);
    // Escape folds the book and returns the focus to its button.
    await navigate('/', guest.tab);
    await until(() => exists(entry, guest.tab), 'the home page lost the closed book', 30000);
    await guest.enter(entry);
    await guest.press('Escape');
    await until(() => focusedName(guest).then(focus => focus?.text === 'Открыть поиск колод'), 'Escape did not return the focus to the book button: ' + JSON.stringify(await focusedName(guest)));
    // A signed-in reader sees the same entry, and no horizontal overflow at any width.
    const widths = [390, 768, 900, 1280];
    const measured = {};
    for (const actor of [guest, owner]) {
      for (const path of ['/', '/community', '/decks']) {
        if (actor === guest && path === '/decks') continue;
        for (const width of widths) {
          await actor.front();
          await actor.metrics({ width, height: 900, deviceScaleFactor: 1, mobile: width < 500 });
          await navigate(path, actor.tab);
          await until(() => exists('header.masthead', actor.tab), `${path} did not render`, 30000);
          // The page is measured when it has said what it has to say (the styles of a lazy route arrive after its markup).
          if (path === '/community') await until(() => settled(actor), `${path} stayed loading`, 30000);
          else await until(() => actor.page(`return document.querySelector('main')?.children.length > 0;`), `${path} has no content`, 30000);
          await sleep(500);
          const fits = await actor.page(`return { scroll: document.documentElement.scrollWidth, width: args[0], ok: document.documentElement.scrollWidth <= args[0] };`, width);
          if (!fits.ok) {
            throw new SafeFailure(`${actor.label} ${path} at ${width}px: scrollWidth ${fits.scroll} > ${width}; widest ${JSON.stringify(await widest(actor, 5))}`);
          }
          measured[`${actor.label}:${path}:${width}`] = fits.scroll;
        }
      }
    }
    await guest.metrics(PHONE);
    await navigate('/', guest.tab);
    await until(() => exists(entry, guest.tab), 'the phone masthead lost the book', 30000);
    await shoot(guest, 'catalog-mobile-masthead-guest.png');
    await owner.metrics(DESKTOP);
    return { closedBook: true, enterNavigates: 'q=испанский', notOnCommunity: true, escapeRestoresFocus: true, widths, noOverflow: Object.keys(measured).length };
  });

  // ==== 12. reduced motion =========================================================================================================================
  await stage('motion', ['seed'], async () => {
    const probe = `(() => { const watch = new MutationObserver((records, observer) => {
        const spread = document.querySelector('app-book-search .spread'); const cell = document.querySelector('app-deck-grid .cell');
        window.__motion = window.__motion ?? {};
        if (spread && !window.__motion.spread) window.__motion.spread = { name: getComputedStyle(spread).animationName, opening: spread.classList.contains('is-opening') };
        if (cell && !window.__motion.cell) window.__motion.cell = { name: getComputedStyle(cell).animationName };
        if (window.__motion.spread && window.__motion.cell) observer.disconnect(); });
      watch.observe(document, { subtree: true, childList: true }); })();`;
    const registered = await guest.tab.call('Page.addScriptToEvaluateOnNewDocument', { source: probe });
    await guest.front(); await guest.metrics(DESKTOP);
    // Normal motion first: the positive control (the same page does animate when it may).
    await guest.tab.call('Emulation.setEmulatedMedia', { features: [{ name: 'prefers-reduced-motion', value: 'no-preference' }] });
    await navigate('/community?sort=new', guest.tab);
    await until(() => settled(guest), 'the normal-motion page stayed loading');
    await until(() => guest.page('return Boolean(window.__motion?.spread && window.__motion?.cell);'), 'the motion probe saw nothing');
    const normal = await guest.page('return window.__motion;');
    need(normal.spread.opening && normal.spread.name !== 'none', 'the book does not open when motion is allowed: ' + JSON.stringify(normal));
    need(normal.cell.name !== 'none', 'the cards do not arrive with the ink reveal when motion is allowed: ' + JSON.stringify(normal));
    // Reduced motion: no opening, no cascade, a still hint.
    await guest.tab.call('Emulation.setEmulatedMedia', { features: [{ name: 'prefers-reduced-motion', value: 'reduce' }] });
    await navigate('/community?sort=new', guest.tab);
    await until(() => settled(guest), 'the reduced-motion page stayed loading');
    await until(() => guest.page('return Boolean(window.__motion?.spread && window.__motion?.cell);'), 'the motion probe saw nothing (reduced)');
    const reduced = await guest.page('return window.__motion;');
    need(reduced.spread.name === 'none' && reduced.cell.name === 'none', 'an animation runs under reduced motion: ' + JSON.stringify(reduced));
    const running = await guest.page(`return document.getAnimations().filter(animation => animation.playState === 'running' && !(animation.effect?.target instanceof Element && animation.effect.target.matches('app-notification-bell *'))).map(animation => animation.animationName ?? animation.constructor.name);`);
    need(running.length === 0, 'animations are running under reduced motion: ' + JSON.stringify(running));
    const hint = async () => guest.page(`return document.querySelector('app-community-page app-typewriter-hint .idea')?.textContent ?? null;`);
    const firstHint = await hint();
    await sleep(2800);
    const secondHint = await hint();
    need(firstHint !== null && firstHint === secondHint, 'the hint moves under reduced motion: ' + JSON.stringify([firstHint, secondHint]));
    await shoot(guest, 'catalog-desktop-reduced-motion-guest.png');
    await guest.tab.call('Page.removeScriptToEvaluateOnNewDocument', { identifier: registered.identifier }).catch(() => {});
    await guest.tab.call('Emulation.setEmulatedMedia', { features: [] });
    return { normalMotion: { book: normal.spread.name, cards: normal.cell.name }, reducedMotion: { book: reduced.spread.name, cards: reduced.cell.name }, runningAnimations: 0, hintStill: true };
  });

  // ==== 13. accessibility smoke ====================================================================================================================
  await stage('a11y', ['seed'], async () => {
    await guest.tab.call('Accessibility.enable').catch(() => {});
    const audit = async (actor, label) => {
      const tree = await actor.tab.call('Accessibility.getFullAXTree');
      const interactive = new Set(['link', 'button', 'textbox', 'searchbox', 'combobox', 'checkbox', 'radio', 'switch', 'tab', 'menuitem', 'slider', 'spinbutton']);
      const unnamed = tree.nodes.filter(node => !node.ignored && interactive.has(node.role?.value) && !String(node.name?.value ?? '').trim()).map(node => node.role.value);
      const roles = tree.nodes.filter(node => !node.ignored).map(node => node.role?.value);
      need(unnamed.length === 0, `${label}: interactive elements without an accessible name: ${JSON.stringify(unnamed)}`);
      need(roles.filter(role => role === 'search').length === 1, `${label}: ${roles.filter(role => role === 'search').length} search landmarks`);
      const marks = await landmarks(actor);
      need(marks.h1 === 1, `${label}: ${marks.h1} h1 headings`);
      return { nodes: tree.nodes.length, interactive: roles.filter(role => interactive.has(role)).length };
    };
    const results = {};
    for (const [name, path, actor] of [['community', '/community', guest], ['results', '/community?q=%D0%B8%D1%81%D0%BF%D0%B0%D0%BD%D1%81%D0%BA%D0%B8%D0%B9', guest],
      ['filtered', '/community?topic=english&level=A2', guest], ['signed-in', '/community', member]]) {
      await openCommunity(actor, path);
      results[name] = await audit(actor, name);
    }
    await guest.metrics(DESKTOP);
    await navigate('/u/' + world.ownerLogin, guest.tab);
    await until(() => exists('app-author-page app-deck-grid li', guest.tab), 'the author page did not render', 30000);
    results.author = await audit(guest, 'author');
    // The focus is visible after a route change made with the keyboard: a card, then the way back.
    await openCommunity(guest, '/community?q=%D0%B8%D1%81%D0%BF%D0%B0%D0%BD%D1%81%D0%BA%D0%B8%D0%B9');
    await guest.enter('app-public-deck-card .title a', 'Испанские глаголы');
    await until(async () => (await guest.location()).startsWith('/d/'), 'the card did not lead to the deck page');
    await until(() => exists('h1.hub-title', guest.tab), 'the deck page did not render', 30000);
    const focus = await focusedName(guest);
    need(focus !== null && focus.visible && ['H1', 'MAIN'].includes(focus.tag), 'the focus after opening a deck is lost or not visible: ' + JSON.stringify(focus));
    return { pages: Object.keys(results), audited: results, focusAfterRoute: focus.tag, focusVisible: true };
  });

  // ==== 11. the owner console: the editorial shelf, hiding ===========================================================================================
  await stage('console', ['seed'], async () => {
    const admin = (await fixture('/__fixture/catalog-admin-token', {})).token;
    const outsider = await learning('GET', '/api/admin/community/editorial', { bearer: ownerBearer });
    need(outsider.status === 403, 'a learner token reached the editorial shelf: ' + outsider.status);
    const state = await learning('GET', '/api/admin/community/editorial', { bearer: admin });
    need(state.status === 200 && Array.isArray(state.body.entries), 'the console owner cannot read the editorial shelf: ' + state.status);
    const picks = ['es1', 'en2', 'sq1', 'en4', 'es3'].map(key => world.decks[key]);
    const put = async codes => {
      const current = await learning('GET', '/api/admin/community/editorial', { bearer: admin });
      return learning('PUT', '/api/admin/community/editorial', { bearer: admin, headers: { 'If-Match': current.etag }, payload: { commandId: randomUUID(), codes } });
    };
    // Add five decks, then reorder: the shelf follows the owner's order.
    let saved = await put(picks.map(deck => deck.code));
    need(saved.status === 200, 'the editorial shelf was not saved: ' + saved.status + ' ' + JSON.stringify(saved.body?.code ?? null));
    const order = [picks[3], picks[0], picks[4], picks[2], picks[1]];
    saved = await put(order.map(deck => deck.code));
    need(saved.status === 200 && saved.body.entries.map(entry => entry.code).join() === order.map(deck => deck.code).join(), 'the new order was not saved');
    await openCommunity(guest, '/community');
    const shelf = () => guest.page(`const node = [...document.querySelectorAll('app-deck-shelf')].find(shelf => shelf.querySelector('h2.title')?.textContent.trim() === 'Выбор Мнемы');
      return node ? [...node.querySelectorAll('app-public-deck-card .title')].map(title => title.textContent.trim()) : null;`);
    await until(async () => (await shelf()) !== null, '«Выбор Мнемы» is not shown', 20000);
    need(JSON.stringify(await shelf()) === JSON.stringify(order.map(deck => deck.title)), '«Выбор Мнемы» reads ' + JSON.stringify(await shelf()));
    const firstShelf = await guest.page(`return document.querySelector('app-deck-shelf h2.title')?.textContent.trim();`);
    need(firstShelf === 'Выбор Мнемы', 'the editorial shelf is not the first one: ' + firstShelf);
    await shoot(guest, 'catalog-desktop-editorial-guest.png', { full: true });
    // The learner files a complaint, the console owner hides the deck: it falls out of the shelf and the catalogue at once.
    const victim = order[0];
    const complaint = await learning('POST', `/api/public/decks/${victim.code}/complaints`, { bearer: memberBearer, payload: { reason: 'SPAM' } });
    need(complaint.status === 202, 'the complaint answered ' + complaint.status);
    const queue = await learning('GET', '/api/admin/complaints?status=OPEN', { bearer: admin });
    const filed = queue.body?.items?.find(item => item.publicCode === victim.code);
    need(queue.status === 200 && filed, 'the complaint is not in the console queue');
    const decided = await learning('POST', `/api/admin/complaints/${filed.complaintId}:decide`, { bearer: admin, payload: { decision: 'HIDDEN' } });
    need(decided.status === 200, 'the decision answered ' + decided.status);
    const gone = await publicGet('/decks?q=' + encodeURIComponent(victim.title), null);
    need(gone.status === 200 && !gone.body.decks.some(card => card.code === victim.code), 'the hidden deck is still found by search');
    await openCommunity(guest, '/community');
    need(JSON.stringify(await shelf()) === JSON.stringify(order.slice(1).map(deck => deck.title)), 'the shelf after hiding reads ' + JSON.stringify(await shelf()));
    const everywhere = [...(await listedFromApi()).map(card => card.title)];
    need(!everywhere.includes(victim.title), 'the hidden deck is still in the browse list');
    await until(async () => (await publicGet('/facets')).body?.listedCount === LISTED_TOTAL - 1, 'the facets still count the hidden deck', 20000);
    await navigate(victim.link, guest.tab);
    await until(() => guest.has('Колода не найдена'), 'a guest still opens the hidden deck', 30000);
    return { editorialSaved: order.length, order: 'owner order', hiddenDeck: victim.title, shelfAfterHide: order.length - 1, searchGone: true, browseGone: true, facetsDown: true, deckPageNotFound: true };
  });

  // ==== 10. the guest limit (last: it uses up the minute's allowance of the harness's network) ==========================================================
  await stage('limits', ['seed'], async () => {
    let first = null;
    let sent = 0;
    let reply = null;
    for (let index = 1; index <= 400 && first === null; index++) {
      reply = await learning('GET', '/api/public/community/decks?limit=1');
      sent = index;
      if (reply.status === 429) first = index;
      else need(reply.status === 200, `a guest read answered ${reply.status}`);
    }
    need(first !== null, 'no 429 after 400 guest reads');
    need(first <= 125, `the guest limit of 120 a minute let ${first - 1} reads through`);
    const retry = Number(reply.retryAfter);
    need(Number.isInteger(retry) && retry >= 1 && retry <= 60, 'Retry-After is ' + reply.retryAfter);
    need(reply.body?.code === 'RATE_LIMITED' && (reply.cache ?? '').includes('no-store'), 'the 429 body is ' + reply.text.slice(0, 120));
    // The page says so calmly and recovers on «Повторить» after the wait.
    await guest.front(); await guest.metrics(DESKTOP);
    await navigate('/community', guest.tab);
    await until(() => exists('app-community-page .notice.error', guest.tab), 'the limited page shows no notice', 30000);
    const text = await guest.read('app-community-page .notice.error p');
    need(text?.startsWith(WORDS.limited), 'the notice reads «' + text + '»');
    await shoot(guest, 'catalog-desktop-limited-guest.png');
    await sleep((retry + 2) * 1000);
    await guest.enter('app-community-page .notice.error button', 'Повторить');
    await until(async () => !(await exists('app-community-page .notice.error', guest.tab)) && await settled(guest), 'the page did not recover after the wait', 30000);
    return { reads: sent, first429: first, retryAfterSeconds: retry, code: reply.body.code, noStore: true, noticeShown: true, recoveredOnRetry: true };
  });

  // ---- teardown ------------------------------------------------------------------------------------------------------------------
  await guestTab.close().catch(() => {});
  // The base flow's last steps expect the first tab on a page that shows the profile and the logout control.
  for (const actor of [owner, member]) await navigate('/login', actor.tab).catch(() => {});
  for (const actor of [owner, member]) await actor.metrics({ width: 1280, height: 900, deviceScaleFactor: 1, mobile: false }).catch(() => {});
  record('catalog_summary', { state: failures.length === 0 ? 'passed' : 'failed', failures: failures.length, durationMs: Date.now() - started });
  return { failures, gaps: [] };
}
