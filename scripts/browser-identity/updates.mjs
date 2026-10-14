// Real-browser check of the update of a copy (Updates/7, #446, epics #419 and #444): an author publishes an update of a public deck, the
// learner who copied it sees «Есть обновление», reviews the plan on /decks/{id}/update, accepts the safe part, decides the conflict and the
// deletion, keeps studying, and finally detaches the copy.
// Runs with `--updates` against the real Identity and Learning jars (the ordinary Learning of this run has the community kill switches on, see
// run.py), on the two accounts of the base flow, once on a 1280 px desktop viewport and once on a 390 px mobile viewport, with real CDP keyboard
// events wherever the interaction is natural. Node 24 built-ins only.
//
// Who is who: the author is the first account (the first tab), the learner is the second account (the second tab). The fixture is made through the
// real UI (the decks and materials in the real editor, the public profile consent in /profile, the access change in the hub, the republication with
// «Что нового», the deletion of a material by a held key). The exceptions are the exercises, which are created through the authenticated API exactly
// like the Study fixture of the base flow (the exercise editor is covered by `--mechanics`), and the reads of the plan, of the progress and of
// the deck, which compare the page with the server.
//
// A broken stage is a finding for the product, never something to work around. Two kinds are recorded: a HARD failure (the flow cannot go on: the
// stages that depend on it are skipped) and a SOFT finding (a statement of the handoff that does not hold; the scenario goes on). Both make the
// run red.
import { writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { makeActor } from './community.mjs';

const DECK_PATH = /^\/decks\/[0-9a-f-]{36}$/;
const DESKTOP = { width: 1280, height: 900, deviceScaleFactor: 1, mobile: false };
const MOBILE = { width: 390, height: 844, deviceScaleFactor: 1, mobile: true };

/** The words of the product contract (docs/product/community-decks.md, «Обновления» and «Тексты для пользователя»), as the pages show them. */
const WORDS = {
  badge: 'Есть обновление',
  viewUpdate: 'Посмотреть обновление',
  checkUpdates: 'Проверить обновления',
  pageTitle: 'Обновление от автора',
  summaryTitle: 'Что изменил автор',
  tabs: ['Новое', 'Исправления', 'Конфликты', 'Удалено автором'],
  acceptSafe: 'Принять всё безопасное',
  applySelected: 'Применить выбранное',
  nothingSafe: 'Безопасного для приёма сейчас нет.',
  keepMine: 'Оставить моё',
  takeAuthor: 'Взять вариант автора',
  keep: 'Оставить у себя',
  deleteToo: 'Удалить и у меня',
  mine: 'Сейчас у вас',
  author: 'Вариант автора',
  upToDate: 'У вас всё как у автора',
  detachedState: 'Колода отвязана от автора, обновлений нет',
  appliedPrefix: 'Обновление применено',
  autoLabel: 'Принимать безопасное автоматически',
  autoOn: 'Безопасное будет приниматься автоматически.',
  autoOff: 'Обновления снова принимаются только вручную.',
  detach: 'Отвязать от автора',
  detached: 'Копия отвязана от автора.',
  publishUpdate: 'Опубликовать обновление'
};

const PASSES = [
  { name: 'desktop', size: DESKTOP, title: 'Испанский: путь обновлений', description: 'Слова и фразы для поездки: кафе, транспорт, отель, рынок.',
    materials: ['Кафе: una mesa, por favor — столик, пожалуйста', 'Транспорт: la estación — вокзал, станция', 'Отель: la llave — ключ от номера', 'Рынок: el precio — цена, стоимость'],
    heads: ['Кафе', 'Транспорт', 'Отель', 'Рынок'], added: { head: 'Билеты', text: 'Билеты: el billete — билет на поезд' },
    fix: ' Вежливо: una mesa para dos.', authorConflict: ' Ещё: la parada — остановка.', learnerEdit: ' Моя заметка: эстасьон.', later: ' Ещё: la habitación — комната.',
    note: 'Исправили кафе, добавили билеты и убрали рынок.',
    exercises: [{ on: 0, objective: 'Слово: столик', prompt: 'Введите по-испански: «столик, пожалуйста»', answer: 'una mesa, por favor' },
      { on: 2, objective: 'Слово: ключ', prompt: 'Введите по-испански: «ключ от номера»', answer: 'la llave' }],
    addedExercise: { objective: 'Слово: билет', prompt: 'Введите по-испански: «билет на поезд»', answer: 'el billete' } },
  { name: 'mobile', size: MOBILE, title: 'Португальский: путь обновлений', description: 'Слова и фразы для поездки: рынок, пляж, гостиница, такси.',
    materials: ['Рынок: o preço — цена, стоимость', 'Пляж: a toalha — полотенце для пляжа', 'Гостиница: a chave — ключ от комнаты', 'Такси: o taxista — водитель такси'],
    heads: ['Рынок', 'Пляж', 'Гостиница', 'Такси'], added: { head: 'Автобус', text: 'Автобус: o autocarro — автобус' },
    fix: ' Вежливо: um preço justo.', authorConflict: ' Ещё: a areia — песок.', learnerEdit: ' Моя заметка: «толья».', later: ' Ещё: o quarto — комната.',
    note: 'Исправили рынок, добавили автобус и убрали такси.',
    exercises: [{ on: 0, objective: 'Слово: цена', prompt: 'Введите по-португальски: «цена, стоимость»', answer: 'o preço' },
      { on: 2, objective: 'Слово: ключ', prompt: 'Введите по-португальски: «ключ от комнаты»', answer: 'a chave' }],
    addedExercise: { objective: 'Слово: автобус', prompt: 'Введите по-португальски: «автобус»', answer: 'o autocarro' } }
];

export async function runUpdates(ctx) {
  const { config, record, SafeFailure, until, exists, navigate, fill, clickText, saveScreenshot, saveFullScreenshot, setStep,
    ownerTab, ownerBearer, memberTab, memberBearer } = ctx;
  const owner = makeActor(ctx, 'author', ownerTab, ownerBearer);
  const member = makeActor(ctx, 'learner', memberTab, memberBearer);
  const failures = [];
  const started = Date.now();

  const shoot = async (actor, name, { full = false } = {}) => {
    try { await (full ? saveFullScreenshot : saveScreenshot)(name, actor.tab); } catch { /* the verdict is the assertion, not the picture */ }
  };

  /** Soft findings of the stage being run (statements of the handoff that do not hold; the stage goes on). */
  let findings = [];
  const soft = (condition, id, text) => { if (!condition) findings.push({ id, text }); return Boolean(condition); };

  const stage = async (pass, name, depends, body) => {
    const key = `updates_${pass.name}_${name}`;
    setStep(key);
    const broken = depends.find(dependency => pass.failed.has(dependency));
    if (broken !== undefined) {
      record(key, { state: 'skipped', because: `updates_${pass.name}_${broken} failed` });
      pass.failed.add(name);
      return null;
    }
    findings = [];
    const begun = Date.now();
    let detail = {};
    try {
      detail = (await body()) ?? {};
    } catch (error) {
      const reason = error instanceof SafeFailure ? error.message : 'driver: ' + String(error?.message ?? error).slice(0, 200) + ' @ ' + String(error?.stack ?? '').split('\n').slice(1, 3).map(line => line.trim().replace(/^at /, '')).join(' < ');
      pass.failed.add(name);
      failures.push({ stage: key, reason });
      for (const finding of findings) failures.push({ stage: key, reason: `${finding.id}: ${finding.text}` });
      record(key, { state: 'failed', reason, findings, durationMs: Date.now() - begun });
      for (const actor of [owner, member]) await shoot(actor, `failure-updates-${pass.name}-${name}-${actor.label}.png`);
      await writeFile(join(config.output, `failure-${key}.txt`), [reason, ...findings.map(finding => `${finding.id}: ${finding.text}`)].join('\n') + '\n').catch(() => {});
      return null;
    }
    if (findings.length > 0) {
      for (const finding of findings) failures.push({ stage: key, reason: `${finding.id}: ${finding.text}` });
      record(key, { state: 'failed', findings, durationMs: Date.now() - begun, ...detail });
      await writeFile(join(config.output, `failure-${key}.txt`), findings.map(finding => `${finding.id}: ${finding.text}`).join('\n') + '\n').catch(() => {});
      return detail;
    }
    record(key, { state: 'passed', durationMs: Date.now() - begun, ...detail });
    return detail;
  };

  // ---- helpers shared by the stages ---------------------------------------------------------------------------------------------------
  const draftWrites = actor => actor.wire.filter(entry => entry.path.includes('draft') && entry.method !== 'GET' && entry.status !== null && entry.status < 300).length;
  const materialRows = actor => actor.page(`return [...document.querySelectorAll('app-selectable-material-list li.item-row')].map(row => ({
    title: row.querySelector('.row-link')?.textContent.trim() ?? '', key: (row.querySelector('.row-link')?.getAttribute('href') ?? '').split('/').at(-1),
    count: row.querySelector('.count')?.textContent.replace(/\\s+/g, ' ').trim() ?? '' }));`);
  const openHub = async (actor, path, rowsExpected) => {
    await actor.front();
    await navigate(path, actor.tab);
    await until(() => exists('h1#deck-detail-title', actor.tab), 'the deck hub did not load', 30000);
    if (rowsExpected !== undefined) await until(async () => (await materialRows(actor)).length === rowsExpected, `the hub should list ${rowsExpected} materials`, 30000);
  };
  const apiJson = async (actor, bearer, method, path, payload = null, headers = {}) => actor.api(method, path, payload, headers, bearer);

  /** Types `text` into a new material of the deck with the real editor and publishes it; returns the member key. */
  const createMaterial = async (actor, deckPath, text) => {
    await navigate(deckPath + '/materials/new', actor.tab);
    await until(() => exists('.ProseMirror[contenteditable="true"]', actor.tab), 'new-material editor absent', 30000);
    actor.need(await actor.page(`const editor = document.querySelector('.ProseMirror[contenteditable="true"]');
      if (!(editor instanceof HTMLElement)) return false;
      editor.focus(); const selection = getSelection(); const range = document.createRange();
      range.selectNodeContents(editor); selection.removeAllRanges(); selection.addRange(range); return true;`), 'editor cannot take focus');
    await actor.tab.call('Input.insertText', { text });
    await until(() => actor.has('Все изменения сохранены'), 'draft acknowledgement absent');
    actor.need(await clickText('button', 'Опубликовать', actor.tab), 'material publication unavailable');
    await until(async () => /^\/decks\/[0-9a-f-]{36}\/materials\/[0-9a-f-]{36}$/.test(await actor.location()) && await actor.has(text), 'the new material did not reach Browse');
    return (await actor.location()).split('/').at(-1);
  };

  /** Appends `text` to the end of an existing material with the real editor and publishes the new revision. */
  const appendToMaterial = async (actor, deckPath, key, text) => {
    await actor.front();
    // The editor needs the position of the material (`?ordinal=`), which the «Редактировать» link of Browse carries: open it the way a person does.
    await navigate(`${deckPath}/materials/${key}`, actor.tab);
    await until(() => actor.page(`return [...document.querySelectorAll('a.button.primary')].some(link => link.textContent.trim() === 'Редактировать');`), 'Browse offers no «Редактировать»', 30000);
    await actor.enter('a.button.primary', 'Редактировать');
    await until(async () => (await actor.location()) === `${deckPath}/materials/${key}/edit`, '«Редактировать» did not open the editor');
    await until(() => exists('.ProseMirror[contenteditable="true"]', actor.tab), 'the material editor did not open: ' + await actor.read('section.notice'), 30000);
    await until(() => actor.page(`return document.querySelector('.ProseMirror').innerText.trim().length > 0;`), 'the editor shows no text of the material', 30000);
    const before = draftWrites(actor);
    actor.need(await actor.page(`const editor = document.querySelector('.ProseMirror[contenteditable="true"]');
      if (!(editor instanceof HTMLElement)) return false;
      editor.focus(); const selection = getSelection(); selection.selectAllChildren(editor); selection.collapseToEnd(); return true;`), 'editor cannot take focus');
    await actor.tab.call('Input.insertText', { text });
    await until(() => actor.page(`return document.querySelector('.ProseMirror').innerText.includes(args[0]);`, text.trim()), 'the typed text is not in the editor');
    await until(async () => draftWrites(actor) > before && await actor.has('Все изменения сохранены'), 'the draft was not acknowledged', 30000);
    await until(() => actor.page(`return [...document.querySelectorAll('button')].some(button => button.textContent.trim() === 'Опубликовать' && !button.disabled);`), 'publication stayed disabled', 30000);
    actor.need(await clickText('button', 'Опубликовать', actor.tab), 'material publication unavailable');
    await until(async () => (await actor.location()) === `${deckPath}/materials/${key}` && await actor.has(text.trim()), 'the edited material did not reach Browse', 30000);
  };

  /** One FREE_RESPONSE exercise about a material, through the authenticated API (like the Study fixture of the base flow). */
  const createExercise = async (actor, bearer, deckId, memberKey, spec) => {
    const deck = await apiJson(actor, bearer, 'GET', `/api/decks/${deckId}`);
    const item = await apiJson(actor, bearer, 'GET', `/api/decks/${deckId}/items/${memberKey}`);
    actor.need(deck.status === 200 && item.status === 200, 'deck or material read failed');
    const paragraph = item.body.document?.root?.content?.find(candidate => candidate.type === 'paragraph');
    actor.need(paragraph?.id, 'the material has no paragraph to quote');
    const created = await apiJson(actor, bearer, 'POST', `/api/decks/${deckId}/exercises`, {
      commandId: crypto.randomUUID(), expectedDeckRevisionId: deck.body.revisionId,
      objective: { operation: 'create', title: spec.objective },
      exercise: { type: 'FREE_RESPONSE', schemaVersion: 2, enabled: true,
        subject: { memberKey: item.body.memberKey, itemRevisionId: item.body.itemRevisionId },
        content: { prompt: [{ kind: 'TEXT', text: spec.prompt }],
          reference: [{ kind: 'MATERIAL', memberKey: item.body.memberKey, itemRevisionId: item.body.itemRevisionId, nodeId: paragraph.id }], responseInput: 'TEXT' },
        answerKey: { kind: 'TEXT', accepted: [spec.answer], normalization: ['UNICODE_NFC', 'TRIM', 'CASE_FOLD'], matchingMode: 'STRICT' },
        evaluatorPolicy: { id: 'deterministic-text', version: '1' } } }, { 'If-Match': deck.etag });
    actor.need(created.status === 201, `exercise creation answered ${created.status}`);
  };

  /**
   * Study of a deck with the real UI: starts the session, answers every card the fixture knows by its prompt (all answers are right),
   * stops after the card whose prompt contains `stopAt` (any card when `null`, at most `max` cards).
   */
  const studyCards = async (actor, deckPath, fixtures, { stopAt = null, max = 8, first = false } = {}) => {
    await actor.front();
    await navigate(deckPath + '/study', actor.tab);
    const seen = [];
    let restarted = false;
    for (let step = 0; step < max + 6; step++) {
      const screen = async () => await exists('.session-setup', actor.tab) ? 'setup' : await exists('article.study-card textarea[data-answer-control]', actor.tab) ? 'card'
        : await exists('#feedback-title', actor.tab) ? 'feedback' : await exists('.completion', actor.tab) ? 'completion' : await exists('.notice.error', actor.tab) ? 'error' : null;
      await until(async () => (await screen()) !== null, 'Study showed nothing', 30000);
      const now = await screen();
      if (now === 'error') throw new SafeFailure(`${actor.label}: Study reached an error: ${await actor.read('.notice.error')}`);
      if (now === 'setup') {
        await actor.focus('.session-setup [data-answer-control]');
        await actor.press('Space');
        await until(async () => (await screen()) !== 'setup', 'Study did not start', 30000);
        continue;
      }
      if (now === 'feedback') { await actor.enter('button', 'Продолжить'); await until(async () => (await screen()) !== 'feedback', 'Study did not go on', 30000); continue; }
      if (now === 'completion') {
        // An open session of an earlier visit ended without the expected exercise: a new session reads the deck as it is now.
        actor.need(stopAt !== null && !restarted, `Study ended after ${seen.length} cards without the expected exercise (${stopAt ?? 'any'})`);
        restarted = true;
        await navigate(deckPath + '/study', actor.tab);
        continue;
      }
      const text = await actor.page(`return document.querySelector('article.study-card').innerText;`);
      const fixture = fixtures.find(candidate => text.includes(candidate.prompt));
      actor.need(fixture !== undefined, 'Study issued a card that is not a fixture exercise: ' + text.slice(0, 120));
      actor.need(!text.includes(fixture.answer), 'Study leaked the accepted answer before an attempt');
      if (first) await until(() => actor.page(`return document.activeElement?.id === 'study-0-answer';`), 'the answer field did not take focus');
      await fill('article.study-card textarea[data-answer-control]', fixture.answer, actor.tab);
      await actor.enter('button', 'Проверить ответ');
      await until(() => exists('#feedback-title', actor.tab), 'no feedback for the attempt', 30000);
      actor.need(await actor.page(`return document.activeElement?.id === 'feedback-title' && document.querySelector('.feedback-card.correct') !== null;`), 'feedback focus or verdict is missing');
      seen.push({ prompt: fixture.prompt, isNew: text.includes('Новое'), restarted });
      if (stopAt === null || fixture.prompt.includes(stopAt)) return seen;
    }
    throw new SafeFailure(`${actor.label}: the expected exercise (${stopAt}) was not issued: ${JSON.stringify(seen)}`);
  };

  // ---- the review screen's reads ------------------------------------------------------------------------------------------------------
  const tabsOf = actor => actor.page(`return [...document.querySelectorAll('app-count-tabs [role="tab"]')].map(tab => ({
    name: tab.querySelector('.name')?.textContent.trim(), count: Number(tab.querySelector('.count')?.textContent), selected: tab.getAttribute('aria-selected') === 'true',
    tabindex: tab.tabIndex, id: tab.id, controls: tab.getAttribute('aria-controls'), accessible: tab.textContent.replace(/\\s+/g, ' ').trim() }));`);
  const rowsOf = actor => actor.page(`return [...document.querySelectorAll('.panel app-update-unit-row article.unit')].map(article => ({
    cls: article.dataset.class, title: article.querySelector('.title')?.textContent.trim() ?? '', kind: article.querySelector('.kind span:last-child')?.textContent.trim() ?? '',
    stamp: article.querySelector('.meta .stamp')?.textContent.trim() ?? '',
    checked: [...article.querySelectorAll('.decision input[type="radio"]')].find(input => input.checked)?.parentElement.textContent.trim() ?? null,
    options: [...article.querySelectorAll('.decision .segment .text')].map(node => node.textContent.trim()),
    expanded: article.querySelector('.disclosure button')?.getAttribute('aria-expanded') === 'true' }));`);
  const summaryOf = actor => actor.page(`const root = document.querySelector('app-update-summary');
    if (!root) return null;
    const button = root.querySelector('.action button.primary');
    return { heading: root.querySelector('h2')?.textContent.trim(), line: root.querySelector('.line')?.textContent.trim(), news: root.querySelector('.news-text')?.textContent.trim() ?? null,
      button: button?.textContent.trim(), disabled: button?.getAttribute('aria-disabled') === 'true', hint: root.querySelector('.action .hint')?.textContent.trim(),
      selectedButton: document.querySelector('.selected-action button')?.textContent.trim(), selectedDisabled: document.querySelector('.selected-action button')?.getAttribute('aria-disabled') === 'true',
      selectedHint: document.querySelector('#update-selected-note')?.textContent.trim() };`);
  const selectedTab = async actor => (await tabsOf(actor)).find(tab => tab.selected)?.name ?? null;
  const planOf = async (actor, bearer, copyId, query = '?limit=100') => {
    const answer = await apiJson(actor, bearer, 'GET', `/api/decks/${copyId}/update-plan${query}`);
    actor.need(answer.status === 200, 'the update plan answered ' + answer.status);
    return answer.body;
  };
  const forkOf = async (actor, bearer, copyId) => {
    const answer = await apiJson(actor, bearer, 'GET', `/api/decks/${copyId}`);
    actor.need(answer.status === 200, 'the copy answered ' + answer.status);
    return answer.body.fork;
  };
  const toastsOf = actor => actor.page(`return window.__mnemaToasts ?? [];`);
  const watchToasts = actor => actor.page(`window.__mnemaToasts = [];
    const note = () => { for (const node of document.querySelectorAll('.toast .text')) { const text = node.textContent.trim(); if (!window.__mnemaToasts.includes(text)) window.__mnemaToasts.push(text); } };
    new MutationObserver(note).observe(document.body, { subtree: true, childList: true, characterData: true }); note(); return true;`);
  const activeText = actor => actor.page(`const e = document.activeElement; return e ? { tag: e.tagName, text: e.textContent.trim().slice(0, 80), inSummary: e.closest('app-update-summary') !== null, id: e.id } : null;`);
  const progressOf = async (actor, bearer, deckId) => {
    const answer = await apiJson(actor, bearer, 'GET', `/api/decks/${deckId}/study-progress?limit=100`);
    actor.need(answer.status === 200, 'study progress answered ' + answer.status);
    return answer.body.items;
  };

  // ---- 0. the public profile consent of the author (idempotent: `--community` may have switched it on) ---------------------------------------------
  const consent = await (async () => {
    setStep('updates_consent');
    const begun = Date.now();
    try {
      await owner.metrics(DESKTOP);
      await navigate('/profile', owner.tab);
      await until(() => exists('#profile-username', owner.tab), 'profile form absent');
      const login = await owner.page('return document.querySelector("#profile-username").value;');
      owner.need(/^[A-Za-z0-9_.-]{3,50}$/u.test(login), 'the fixture account has no profile login');
      await until(() => exists('app-public-profile-settings input[role="switch"]', owner.tab), 'public profile switch absent');
      if (!(await owner.page(`return document.querySelector('app-public-profile-settings input[role="switch"]').checked;`))) {
        await owner.focus('app-public-profile-settings input[role="switch"]');
        await owner.press('Space');
      }
      await until(() => owner.page(`return document.querySelector('app-public-profile-settings input[role="switch"]').checked
        && document.querySelector('app-public-profile-settings .preview app-author-chip .login')?.textContent.trim() === '@' + args[0];`, login), 'the public profile was not switched on');
      record('updates_consent', { state: 'passed', durationMs: Date.now() - begun });
      return true;
    } catch (error) {
      const reason = error instanceof SafeFailure ? error.message : 'driver: ' + String(error?.message ?? error).slice(0, 160);
      failures.push({ stage: 'updates_consent', reason });
      record('updates_consent', { state: 'failed', reason });
      await shoot(owner, 'failure-updates-consent-author.png');
      return false;
    }
  })();

  for (const spec of PASSES) {
    const pass = { ...spec, failed: new Set() };
    if (!consent) { record(`updates_${pass.name}`, { state: 'skipped', because: 'updates_consent failed' }); continue; }
    const world = { deckPath: null, deckId: null, keys: [], code: null, link: null, copyPath: null, copyId: null, copyKeys: {}, progressBefore: null, attempted: null };
    const fixtures = [...pass.exercises, pass.addedExercise];
    for (const actor of [owner, member]) await actor.metrics(DESKTOP);

    // ---- 1. the author: a public deck with four materials and two exercises ---------------------------------------------------------------
    await stage(pass, 'publish', [], async () => {
      await owner.front();
      await navigate('/decks/new', owner.tab);
      await fill('#create-title', pass.title, owner.tab);
      await fill('#create-description', pass.description, owner.tab);
      await owner.tab.evaluate("[...document.querySelectorAll('form button[type=submit]')].find(button => !button.closest('search')).click()");
      await until(async () => DECK_PATH.test((await owner.location()).split('?')[0]) && await exists('#deck-detail-title', owner.tab), 'deck creation did not reach its hub');
      world.deckPath = (await owner.location()).split('?')[0];
      world.deckId = world.deckPath.split('/').at(-1);
      for (const text of pass.materials) world.keys.push(await createMaterial(owner, world.deckPath, text));
      for (const exercise of pass.exercises) await createExercise(owner, ownerBearer, world.deckId, world.keys[exercise.on], exercise);

      await navigate(world.deckPath, owner.tab);
      await until(() => exists('app-access-controls input[type="radio"]', owner.tab), 'the access picker did not load');
      await owner.focus('app-access-controls input[type="radio"]:checked');
      for (let step = 0; step < 3; step++) await owner.press('ArrowRight');
      await until(() => owner.page(`return document.querySelector('app-access-controls input[type="radio"]:checked')?.parentElement.textContent.trim() === 'Публичная';`), 'ArrowRight x3 did not stage «Публичная»');
      await owner.enter('app-access-controls .change-actions .button.primary', 'Сделать публичной…');
      await until(() => exists('app-publication-checklist', owner.tab), 'the checklist did not open');
      const items = () => owner.page(`return [...document.querySelectorAll('app-publication-checklist li.checklist-item')].map(li => ({
        title: li.querySelector('.checklist-title')?.textContent.trim(), state: li.getAttribute('data-state') }));`);
      await until(async () => (await items()).length === 7, 'the checklist has not seven items');
      let state = Object.fromEntries((await items()).map(entry => [entry.title, entry.state]));
      owner.need(state['Описание'] === 'done' && state['Публичный профиль'] === 'done', 'description and profile should already be ready: ' + JSON.stringify(state));
      if (state['Тема'] !== 'done') {
        owner.need(!(await exists('app-publication-checklist li.checklist-item:nth-child(2) .field-error', owner.tab)), 'the topic directory did not load in the checklist');
        await until(() => exists('app-publication-checklist li.checklist-item:nth-child(2) [role="combobox"]', owner.tab), 'the topic directory did not load into the checklist', 30000);
        if (await exists('app-publication-checklist .suggest button', owner.tab)) {
          await owner.enter('app-publication-checklist .suggest button');
        } else {
          await owner.focus('app-publication-checklist li.checklist-item:nth-child(2) [role="combobox"]');
          await owner.press('ArrowDown');
          await until(() => exists('[role="option"]', owner.tab), 'the topic list did not open');
          await owner.press('ArrowDown'); await owner.press('Enter');
        }
        try { await until(async () => (await items()).find(entry => entry.title === 'Тема')?.state === 'done', 'the topic was not accepted'); }
        catch (error) {
          const seen = await owner.page(`const li = document.querySelectorAll('app-publication-checklist li.checklist-item')[1];
            return { text: li?.innerText.replace(/\\s+/g, ' '), focus: document.activeElement?.outerHTML.slice(0, 160), suggest: [...document.querySelectorAll('app-publication-checklist .suggest button')].map(b => b.textContent.trim()) };`);
          throw new SafeFailure('owner: ' + error.message + ' ' + JSON.stringify(seen));
        }
      }
      state = Object.fromEntries((await items()).map(entry => [entry.title, entry.state]));
      if (state['Язык'] !== 'done') {
        await owner.focus('app-publication-checklist li:nth-of-type(3) [role="combobox"]');
        await owner.press('ArrowDown');
        await until(() => exists('[role="option"]', owner.tab), 'the language list did not open');
        await owner.press('ArrowDown'); await owner.press('Enter');
        await until(async () => (await items()).find(entry => entry.title === 'Язык')?.state === 'done', 'the language was not accepted');
      }
      await until(() => owner.page(`return document.querySelector('.checklist-actions .button.primary')?.getAttribute('aria-disabled') === null;`), 'the submit button stayed disabled');
      await owner.enter('.checklist-actions .button.primary', 'Опубликовать и сделать публичной');
      const publication = () => apiJson(owner, ownerBearer, 'GET', `/api/decks/${world.deckId}/publication`).then(answer => answer.body);
      await until(async () => (await publication()).visibility === 'PUBLIC', 'the deck did not become public', 30000);
      const published = await publication();
      world.code = published.publicCode;
      world.link = published.link;
      owner.need(/^\/d\/[1-9A-HJ-NP-Za-km-z]{10}\/[a-z0-9-]+$/u.test(world.link ?? ''), 'the public link is not /d/{code}/{slug}: ' + world.link);
      const rows = await materialRows(owner);
      owner.need(rows.length === 4, `the hub lists ${rows.length} materials`);
      return { materials: rows.length, exercises: pass.exercises.length, link: world.link.replace(world.code, '{code}') };
    });

    // ---- 2. the learner copies the deck and waits until it is ready ---------------------------------------------------------------------------
    await stage(pass, 'copy', ['publish'], async () => {
      await member.front();
      await member.metrics(pass.size);
      await navigate(world.link, member.tab);
      await until(() => exists('.primary-slot button.primary', member.tab), 'the signed-in view has no «Добавить к себе»', 30000);
      await member.enter('.primary-slot button.primary');
      await until(async () => DECK_PATH.test((await member.location()).split('?')[0]), 'the copy did not open its hub', 30000);
      world.copyPath = (await member.location()).split('?')[0];
      world.copyId = world.copyPath.split('/').at(-1);
      await until(async () => !(await exists('app-copy-status', member.tab)) && await exists('nav.hub-actions', member.tab), 'the copy was not materialized in time', 60000);
      await until(async () => (await materialRows(member)).length === 4, 'the ready copy never listed its four materials', 30000);
      for (const row of await materialRows(member)) world.copyKeys[pass.heads.find(head => row.title.startsWith(head))] = row.key;
      member.need(Object.keys(world.copyKeys).length === 4, 'the copy rows are not the four fixture materials: ' + JSON.stringify(world.copyKeys));
      const fork = await forkOf(member, memberBearer, world.copyId);
      member.need(fork?.preparing === false && fork?.updateAvailable === false && fork?.detached === false && fork?.autoUpdate === false, 'the fresh copy reports ' + JSON.stringify(fork));
      member.need(!(await exists('app-update-badge', member.tab)) && !(await exists('section.update-offer', member.tab)), 'a fresh copy shows an update badge or offer');
      member.need(await member.page(`return [...document.querySelectorAll('nav.hub-actions a')].some(link => link.textContent.trim() === args[0]);`, WORDS.checkUpdates), 'the quiet link «Проверить обновления» is missing from a ready copy');
      const plan = await planOf(member, memberBearer, world.copyId);
      member.need(plan.state === 'UP_TO_DATE', 'the plan of a fresh copy is ' + plan.state);
      return { copyRows: 4, freshPlan: plan.state };
    });

    // ---- 3. the learner studies one exercise ----------------------------------------------------------------------------------------------------
    await stage(pass, 'study', ['copy'], async () => {
      const seen = await studyCards(member, world.copyPath, fixtures, { first: true });
      member.need(seen.length === 1, 'one attempt expected');
      world.progressBefore = await progressOf(member, memberBearer, world.copyId);
      const assessed = world.progressBefore.filter(entry => entry.lastAssessedAt !== null);
      member.need(assessed.length === 1, 'exactly one material should carry an assessed attempt: ' + JSON.stringify(world.progressBefore.map(entry => [entry.title, entry.lastAssessedAt])));
      world.attempted = { title: assessed[0].title, memberKey: assessed[0].memberKey, lastAssessedAt: assessed[0].lastAssessedAt, state: assessed[0].state };
      return { attemptedPrompt: seen[0].prompt, attemptedMaterial: world.attempted.title.slice(0, 20) };
    });

    // ---- 4. the learner edits material #2 in the copy -------------------------------------------------------------------------------
    await stage(pass, 'learner_edit', ['copy'], async () => {
      await member.metrics(DESKTOP);
      await appendToMaterial(member, world.copyPath, world.copyKeys[pass.heads[1]], pass.learnerEdit);
      await member.metrics(pass.size);
      return { edited: pass.heads[1] };
    });

    // ---- 5. the author: a fix, a conflict, a new material with a new exercise, a deletion, a note; republication --------------------------
    await stage(pass, 'author_update', ['publish'], async () => {
      await owner.front();
      await owner.metrics(DESKTOP);
      await appendToMaterial(owner, world.deckPath, world.keys[0], pass.fix);
      await appendToMaterial(owner, world.deckPath, world.keys[1], pass.authorConflict);
      const addedKey = await createMaterial(owner, world.deckPath, pass.added.text);
      await createExercise(owner, ownerBearer, world.deckId, addedKey, pass.addedExercise);
      // Delete material #4 with the real UI: select by keyboard, arm, then hold Space.
      await openHub(owner, world.deckPath, 5);
      const index = (await materialRows(owner)).findIndex(row => row.title.startsWith(pass.heads[3]));
      owner.need(index >= 0, 'material #4 is not listed');
      owner.need(await owner.page(`const input = document.querySelectorAll('app-selectable-material-list li.item-row .check input')[args[0]];
        if (!(input instanceof HTMLElement)) return false; input.scrollIntoView({ block: 'center', behavior: 'instant' }); input.focus(); return document.activeElement === input;`, index), 'cannot focus the checkbox of material #4');
      await owner.press('Space');
      await until(() => owner.page(`const hold = document.querySelector('app-bulk-action-bar app-hold-to-delete-button button');
        return hold !== null && !hold.disabled && hold.getAttribute('aria-disabled') === null;`), 'deletion stayed disabled although a row is selected');
      await owner.focus('app-bulk-action-bar app-hold-to-delete-button button');
      await owner.press('Space');
      await until(() => owner.page(`return document.querySelector('app-bulk-action-bar app-hold-to-delete-button button')?.getAttribute('aria-pressed') === 'true';`), 'the first press must only arm the hold button', 6000);
      await owner.tab.call('Input.dispatchKeyEvent', { type: 'keyDown', text: ' ', unmodifiedText: ' ', key: ' ', code: 'Space', windowsVirtualKeyCode: 32 });
      try {
        await until(async () => (await materialRows(owner)).length === 4, 'the hold did not delete material #4 while the key stayed down', 20000);
      } finally {
        await owner.tab.call('Input.dispatchKeyEvent', { type: 'keyUp', key: ' ', code: 'Space', windowsVirtualKeyCode: 32 });
      }
      owner.need((await materialRows(owner)).every(row => !row.title.startsWith(pass.heads[3])), 'material #4 is still listed');
      // Republish with «Что нового».
      await openHub(owner, world.deckPath, 4);
      await until(() => owner.page(`return [...document.querySelectorAll('section.publication button')].some(button => button.textContent.trim() === args[0]);`, WORDS.publishUpdate), 'the hub offers no «Опубликовать обновление»', 30000);
      const state = (await apiJson(owner, ownerBearer, 'GET', `/api/decks/${world.deckId}/publication`)).body;
      owner.need(state.unpublishedChanges >= 4, 'unpublished changes: ' + state.unpublishedChanges);
      await owner.enter('section.publication .publication-status button', WORDS.publishUpdate);
      await until(() => exists('section.publication form.publication-update textarea', owner.tab), 'the release note form did not open');
      await fill('section.publication form.publication-update textarea', pass.note, owner.tab);
      await owner.enter('section.publication form.publication-update button[type="submit"]');
      await until(async () => { const now = (await apiJson(owner, ownerBearer, 'GET', `/api/decks/${world.deckId}/publication`)).body; return now.publishedRevisionId === now.headRevisionId && now.unpublishedChanges === 0; }, 'the update was not published', 30000);
      const now = (await apiJson(owner, ownerBearer, 'GET', `/api/decks/${world.deckId}/publication`)).body;
      owner.need(now.releaseNote === pass.note, 'the release note was not stored: ' + now.releaseNote);
      return { unpublishedBefore: state.unpublishedChanges, releaseNote: true };
    });

    // ---- 6. the learner sees the badge in «Мои колоды», the hub offer, and opens the review ---------------------------------------------------------
    await stage(pass, 'offer', ['author_update', 'copy', 'learner_edit', 'study'], async () => {
      await member.front();
      await member.metrics(pass.size);
      const fork = await forkOf(member, memberBearer, world.copyId);
      member.need(fork.updateAvailable === true, 'the copy does not report an update: ' + JSON.stringify(fork));
      await navigate('/decks', member.tab);
      await until(() => member.page(`return [...document.querySelectorAll('a.deck-row')].some(row => row.querySelector('strong')?.textContent.trim() === args[0]);`, pass.title), 'the copy is not listed in «Мои колоды»', 30000);
      const card = await member.page(`const row = [...document.querySelectorAll('a.deck-row')].find(candidate => candidate.querySelector('strong')?.textContent.trim() === args[0]);
        return { badge: row.querySelector('app-update-badge')?.textContent.trim() ?? null, text: row.innerText.replace(/\\s+/g, ' ') };`, pass.title);
      member.need(card.badge === WORDS.badge, `the card of the copy has the badge «${card.badge}»`);
      await shoot(member, `updates-${pass.name}-list-badge-learner.png`);
      await navigate(world.copyPath, member.tab);
      await until(() => exists('section.update-offer', member.tab), 'the hub shows no update offer', 30000);
      const offer = await member.page(`const section = document.querySelector('section.update-offer');
        return { heading: section.querySelector('h2')?.textContent.trim(), labelled: section.getAttribute('aria-labelledby') === section.querySelector('h2')?.id, link: section.querySelector('a.button')?.textContent.trim(), href: section.querySelector('a.button')?.getAttribute('href'),
          quiet: [...document.querySelectorAll('nav.hub-actions a')].some(link => link.textContent.trim() === args[0]) };`, WORDS.checkUpdates);
      member.need(offer.heading === WORDS.badge && offer.labelled && offer.link === WORDS.viewUpdate && offer.href === world.copyPath + '/update', 'the offer is wrong: ' + JSON.stringify(offer));
      soft(!offer.quiet, 'quiet-link-beside-offer', 'the quiet «Проверить обновления» link is shown next to the offer');
      await shoot(member, `updates-${pass.name}-hub-offer-learner.png`);
      const reached = await member.tabTo(`element => element.matches('section.update-offer a.button')`, '«Посмотреть обновление»');
      member.need(reached.focusVisible, 'the focus ring is not keyboard-visible on «Посмотреть обновление»');
      await member.press('Enter');
      await until(async () => (await member.location()) === world.copyPath + '/update', '«Посмотреть обновление» did not open the review');
      await until(() => exists('app-update-summary', member.tab), 'the review screen shows no summary', 30000);
      member.need(await member.read('h1#update-title') === WORDS.pageTitle, 'the review heading is wrong');
      return { badgeOnCard: true, offerShown: true, openedByKeyboard: true, tabPresses: reached.presses };
    });

    // ---- 7. the review screen: counts, tabs by keyboard, comparison, defaults ------------------------------------------------------------------
    await stage(pass, 'review', ['offer'], async () => {
      const plan = await planOf(member, memberBearer, world.copyId);
      member.need(plan.state === 'READY' && plan.releaseNote === pass.note, 'the plan is not READY with the release note: ' + plan.state + ' / ' + plan.releaseNote);
      const unitsByClass = plan.units.map(unit => `${unit.class}:${unit.kind}:${(unit.title ?? '').slice(0, 14)}`);
      const count = (cls, kind, head) => plan.units.filter(unit => unit.class === cls && unit.kind === kind && (head === undefined || (unit.title ?? '').startsWith(head))).length;
      member.need(count('NEW', 'MATERIAL', pass.added.head) === 1, 'the new material is not in the plan: ' + JSON.stringify(unitsByClass));
      member.need(count('NEW', 'EXERCISE') === 1, 'the new exercise is not in the plan: ' + JSON.stringify(unitsByClass));
      member.need(count('SAFE', 'MATERIAL', pass.heads[0]) === 1, 'the safe fix of material #1 is not in the plan: ' + JSON.stringify(unitsByClass));
      member.need(count('CONFLICT', 'MATERIAL', pass.heads[1]) === 1, 'the conflict on material #2 is not in the plan: ' + JSON.stringify(unitsByClass));
      member.need(count('DELETED', 'MATERIAL', pass.heads[3]) === 1, 'the deletion of material #4 is not in the plan: ' + JSON.stringify(unitsByClass));
      // The summary the server computes is the one the page must show, word for word.
      const wanted = plan.summary;
      const extraSafe = plan.units.filter(unit => unit.class === 'SAFE' && !(unit.kind === 'MATERIAL' && unit.title.startsWith(pass.heads[0]))).map(unit => `${unit.kind}:${(unit.title ?? '').slice(0, 20)}`);
      soft(wanted.newMaterials === 1 && wanted.newExercises === 1 && wanted.fixes === 1 && wanted.conflicts === 1 && wanted.deletedByAuthor === 1 && wanted.askAgain === 0,
        'summary-counts', `the author made 1 new material, 1 new exercise, 1 fix, 1 conflict and 1 deletion; the server summary is ${JSON.stringify(wanted)} (extra SAFE units: ${JSON.stringify(extraSafe)}; all units: ${JSON.stringify(unitsByClass)})`);
      const plural = (n, forms) => { const a = n % 100, b = n % 10; return a >= 11 && a <= 14 ? forms[2] : b === 1 ? forms[0] : b >= 2 && b <= 4 ? forms[1] : forms[2]; };
      const parts = [];
      if (wanted.newMaterials) parts.push(`+${wanted.newMaterials} ${plural(wanted.newMaterials, ['материал', 'материала', 'материалов'])}`);
      if (wanted.newExercises) parts.push(`+${wanted.newExercises} ${plural(wanted.newExercises, ['упражнение', 'упражнения', 'упражнений'])}`);
      if (wanted.fixes) parts.push(`${wanted.fixes} ${plural(wanted.fixes, ['исправление', 'исправления', 'исправлений'])}`);
      if (wanted.conflicts + wanted.askAgain) parts.push(`${wanted.conflicts + wanted.askAgain} ${plural(wanted.conflicts + wanted.askAgain, ['конфликт', 'конфликта', 'конфликтов'])}`);
      if (wanted.deletedByAuthor) parts.push(`${wanted.deletedByAuthor} удалено автором`);
      const summary = await summaryOf(member);
      member.need(summary !== null && summary.heading === WORDS.summaryTitle, 'the summary heading is wrong: ' + JSON.stringify(summary));
      member.need(summary.line === parts.join(', '), `the summary line reads «${summary.line}», the server's numbers say «${parts.join(', ')}»`);
      member.need(summary.news === pass.note, 'the author\'s «Что нового» is not shown: ' + summary.news);
      member.need(summary.button === WORDS.acceptSafe && !summary.disabled, 'the primary action is ' + JSON.stringify(summary));
      member.need(summary.selectedDisabled, '«Применить выбранное» should be inactive before any pick: ' + JSON.stringify(summary));
      // Tabs: names with counts, ARIA roles, the roving tabindex, the panel linked to the selected tab.
      const tabs = await tabsOf(member);
      member.need(JSON.stringify(tabs.map(tab => tab.name)) === JSON.stringify(WORDS.tabs), 'the tabs read ' + JSON.stringify(tabs.map(tab => tab.name)));
      const expectedCounts = [wanted.newMaterials + wanted.newExercises, wanted.fixes, wanted.conflicts + wanted.askAgain, wanted.deletedByAuthor];
      member.need(JSON.stringify(tabs.map(tab => tab.count)) === JSON.stringify(expectedCounts), `the tab counts are ${JSON.stringify(tabs.map(tab => tab.count))}, the server says ${JSON.stringify(expectedCounts)}`);
      member.need(tabs.every(tab => tab.accessible === `${tab.name}: ${tab.count}`), 'a tab name does not carry its count: ' + JSON.stringify(tabs.map(tab => tab.accessible)));
      member.need(tabs.filter(tab => tab.selected).length === 1 && tabs.filter(tab => tab.tabindex === 0).length === 1 && tabs.find(tab => tab.selected).name === 'Новое', 'the first tab should be the only selected one and the only stop of Tab: ' + JSON.stringify(tabs));
      member.need(await member.page(`const panel = document.querySelector('[role="tabpanel"]'); const tab = document.querySelector('[role="tab"][aria-selected="true"]');
        return panel?.id === tab?.getAttribute('aria-controls') && panel?.getAttribute('aria-labelledby') === tab?.id;`), 'the tab panel is not linked to the selected tab');
      member.need(!(await member.overflow()), 'the review overflows horizontally');
      await shoot(member, `updates-${pass.name}-review-learner.png`);
      await shoot(member, `updates-${pass.name}-review-full-learner.png`, { full: true });

      // Keyboard: Tab reaches the selected tab, arrows, Home and End move it; the panel lists the class of the tab.
      const reached = await member.tabTo(`element => element.matches('app-count-tabs [role="tab"]')`, 'the selected tab');
      member.need(reached.focusVisible, 'the focus ring is not keyboard-visible on the tab');
      const rowsFor = async expectedTab => { await until(async () => (await selectedTab(member)) === expectedTab, `the tab «${expectedTab}» was not selected`); return rowsOf(member); };
      const focusedTab = () => member.page(`return document.activeElement?.getAttribute('role') === 'tab' ? document.activeElement.querySelector('.name').textContent.trim() : null;`);
      let rows = await rowsFor('Новое');
      member.need(rows.length === wanted.newMaterials + wanted.newExercises && rows.every(row => row.cls === 'new') && rows.some(row => row.kind === 'Материал') && rows.some(row => row.kind === 'Упражнение'), 'the «Новое» tab lists ' + JSON.stringify(rows));
      member.need(rows.every(row => row.checked === null), 'a new unit must not start with a decision on screen: ' + JSON.stringify(rows.map(row => row.checked)));
      await member.press('ArrowRight');
      rows = await rowsFor('Исправления');
      member.need(await focusedTab() === 'Исправления', 'ArrowRight did not move the focus to the next tab');
      member.need(rows.length === wanted.fixes && rows.every(row => row.cls === 'fix'), 'the «Исправления» tab lists ' + JSON.stringify(rows));
      const fixRow = rows.find(row => row.kind === 'Материал' && row.title.startsWith(pass.heads[0]));
      member.need(fixRow !== undefined, 'the fix of material #1 is not listed under «Исправления»: ' + JSON.stringify(rows.map(row => [row.kind, row.title.slice(0, 20)])));
      // Expand the fix row with the keyboard: Tab to its disclosure button, Enter.
      const disclosure = await member.tabTo(`element => element.matches('.panel article.unit[data-class="fix"] .disclosure button') && element.getAttribute('aria-label').includes(${JSON.stringify(pass.heads[0])})`, 'the comparison button of the fix');
      member.need(disclosure.focusVisible, 'the focus ring is not keyboard-visible on «Сравнить»');
      await member.press('Enter');
      await until(() => member.page(`return document.querySelector('.panel [data-column="mine"]') !== null && document.querySelector('.panel [data-column="author"]') !== null;`), 'the comparison did not open', 30000);
      await until(() => member.page(`return document.querySelector('.panel [data-column="author"]')?.innerText.includes(args[0]);`, pass.fix.trim()), 'the author\'s column does not show the fix', 30000);
      const geometry = () => member.page(`const box = element => { const r = element.getBoundingClientRect(); const style = getComputedStyle(element); return { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height), shown: style.display !== 'none' && r.width > 0 && r.height > 0 }; };
        const compare = document.querySelector('.panel .compare'); const mine = document.querySelector('.panel [data-column="mine"]'); const author = document.querySelector('.panel [data-column="author"]');
        const sw = compare.querySelector('.switch');
        return { mine: box(mine), author: box(author), switchShown: getComputedStyle(sw).display !== 'none', side: compare.dataset.side, mineText: mine.innerText, authorText: author.innerText,
          mineHeading: mine.querySelector('h4')?.textContent.trim(), authorHeading: author.querySelector('h4')?.textContent.trim(), host: Math.round(compare.parentElement.getBoundingClientRect().width), rem: parseFloat(getComputedStyle(document.documentElement).fontSize),
          expandedLabel: document.querySelector('.panel article.unit[data-class="fix"] .disclosure button').getAttribute('aria-expanded'),
          radios: [...compare.querySelectorAll('.switch input[type="radio"]')].map(input => ({ label: input.parentElement.textContent.trim(), checked: input.checked })) };`);
      let view = await geometry();
      member.need(view.expandedLabel === 'true', 'the disclosure is not aria-expanded after Enter');
      member.need(view.mineHeading === WORDS.mine && view.authorHeading === WORDS.author, 'the column headings are ' + JSON.stringify([view.mineHeading, view.authorHeading]));
      const compareFacts = { containerPx: view.host, remPx: view.rem };
      if (!pass.size.mobile) {
        const sideBySide = view.mine.shown && view.author.shown && Math.abs(view.mine.y - view.author.y) <= 4 && view.author.x >= view.mine.x + view.mine.w - 2;
        soft(sideBySide && !view.switchShown, 'desktop-columns-side-by-side', `at ${pass.size.width} px the two columns should stand side by side without the switch: container ${view.host}px (44rem is ${Math.round(44 * view.rem)}px), mine ${JSON.stringify(view.mine)}, author ${JSON.stringify(view.author)}, switch shown: ${view.switchShown}`);
        if (!sideBySide) {
          // The switch is the way to read the other side: use it so that the rest of the checks hold.
          await member.focus('.panel .compare .switch input[type="radio"]:checked');
          await member.press('ArrowRight');
          await until(() => member.page(`return document.querySelector('.panel .compare').dataset.side === 'author';`), 'ArrowRight on the switch did not show the author\'s side');
        }
      } else {
        member.need(view.switchShown && view.radios.length === 2, 'the narrow comparison has no two-radio switch: ' + JSON.stringify(view.radios));
        member.need(view.side === 'mine' && view.mine.shown && !view.author.shown, `the narrow comparison should start on «${WORDS.mine}» only: ${JSON.stringify([view.side, view.mine.shown, view.author.shown])}`);
        member.need(view.radios.find(radio => radio.checked)?.label === WORDS.mine, 'the switch does not start on «Сейчас у вас»');
        const stop = await member.tabTo(`element => element.matches('.panel .compare .switch input[type="radio"]')`, 'the comparison switch');
        member.need(stop.focusVisible, 'the focus ring is not keyboard-visible on the switch');
        await member.press('ArrowRight');
        await until(() => member.page(`return document.querySelector('.panel .compare').dataset.side === 'author';`), 'ArrowRight on the switch did not show the author\'s side');
        view = await geometry();
        member.need(view.author.shown && !view.mine.shown, 'after ArrowRight only the author\'s column should show: ' + JSON.stringify([view.mine.shown, view.author.shown]));
        member.need(view.authorText.includes(pass.fix.trim()), 'the author\'s column does not carry the fix');
        compareFacts.switchByKeyboard = true;
        await shoot(member, `updates-${pass.name}-compare-author-learner.png`);
        await member.press('ArrowLeft');
        await until(() => member.page(`return document.querySelector('.panel .compare').dataset.side === 'mine';`), 'ArrowLeft on the switch did not return to the reader\'s side');
        view = await geometry();
        member.need(view.mine.shown && !view.author.shown, 'after ArrowLeft only the reader\'s column should show');
      }
      // The texts of the two sides (read from the DOM even if the narrow layout hides one of them).
      const texts = await member.page(`return { mine: document.querySelector('.panel [data-column="mine"]').textContent, author: document.querySelector('.panel [data-column="author"]').textContent };`);
      member.need(texts.mine.includes(pass.materials[0]) && !texts.mine.includes(pass.fix.trim()), 'the reader\'s column is not the copy\'s text');
      member.need(texts.author.includes(pass.materials[0]) && texts.author.includes(pass.fix.trim()), 'the author\'s column is not the author\'s fix');
      member.need(!(await member.overflow()), 'the open comparison overflows horizontally');
      await shoot(member, `updates-${pass.name}-compare-expanded-learner.png`);
      await shoot(member, `updates-${pass.name}-compare-expanded-full-learner.png`, { full: true });
      // Collapse again by keyboard (the button is where Tab left it): Tab from the tab list to the disclosure.
      await member.focus('.panel article.unit[data-class="fix"] .disclosure button');
      await member.press('Enter');
      await until(() => member.page(`return document.querySelector('.panel [data-column]') === null;`), 'the comparison did not collapse');

      // Conflicts: the default stays on «Оставить моё»; the deletion on «Оставить у себя».
      await member.focus('app-count-tabs [role="tab"][aria-selected="true"]');
      await member.press('ArrowRight');
      rows = await rowsFor('Конфликты');
      member.need(rows.length === wanted.conflicts + wanted.askAgain && rows.every(row => row.cls === 'conflict'), 'the «Конфликты» tab lists ' + JSON.stringify(rows));
      member.need(rows[0].checked === WORDS.keepMine && JSON.stringify(rows[0].options) === JSON.stringify([WORDS.keepMine, WORDS.takeAuthor]), 'the conflict does not start on «Оставить моё»: ' + JSON.stringify(rows[0]));
      member.need(rows[0].title.startsWith(pass.heads[1]), 'the conflict is not material #2: ' + rows[0].title);
      member.need(await member.has('Конфликт: вы и автор изменили одно и то же'), 'the note about conflicts is not shown');
      const aria = await member.page(`const article = document.querySelector('.panel article.unit'); const legend = article.querySelector('fieldset legend');
        return { heading: document.getElementById(article.getAttribute('aria-labelledby'))?.tagName, title: article.querySelector('.title').textContent.trim(), legend: legend?.textContent.trim(), legendHidden: legend?.classList.contains('visually-hidden'),
          label: article.querySelector('.disclosure button').getAttribute('aria-label'), expanded: article.querySelector('.disclosure button').getAttribute('aria-expanded'),
          liveRegions: document.querySelectorAll('.update-page [role="status"]').length, radios: article.querySelectorAll('fieldset input[type="radio"]').length };`);
      member.need(aria.heading === 'H3' && aria.legend === `Что сделать с «${aria.title}»` && aria.legendHidden === true && aria.label === `Сравнить: «${aria.title}»` && aria.expanded === 'false' && aria.liveRegions >= 1 && aria.radios === 2,
        'the row is not named for assistive technology as designed: ' + JSON.stringify(aria));
      await shoot(member, `updates-${pass.name}-conflict-learner.png`);
      await member.press('ArrowRight');
      rows = await rowsFor('Удалено автором');
      member.need(rows.length === wanted.deletedByAuthor && rows.every(row => row.cls === 'deleted'), 'the «Удалено автором» tab lists ' + JSON.stringify(rows));
      member.need(rows[0].checked === WORDS.keep && JSON.stringify(rows[0].options) === JSON.stringify([WORDS.keep, WORDS.deleteToo]), 'the deletion does not start on «Оставить у себя»: ' + JSON.stringify(rows[0]));
      member.need(rows[0].title.startsWith(pass.heads[3]), 'the deleted unit is not material #4: ' + rows[0].title);
      member.need(await member.page(`const legend = document.querySelector('.panel article.unit[data-class="deleted"] fieldset legend'); return legend?.textContent.trim() === 'Удалить «' + args[0] + '» и у меня?' && !legend.classList.contains('visually-hidden');`, rows[0].title),
        'the deletion must ask «Удалить «…» и у меня?» as a visible legend');
      // Home and End jump to the ends.
      await member.press('Home');
      await rowsFor('Новое');
      await member.press('End');
      await rowsFor('Удалено автором');
      await member.press('Home');
      await rowsFor('Новое');
      member.need(await member.page(`return document.querySelector('app-count-tabs [role="tab"][aria-selected="true"]') === document.activeElement;`), 'Home did not leave the focus on the selected tab');
      return { server: wanted, summaryLine: summary.line, tabsByKeyboard: true, defaultsKeepMine: true, defaultsKeep: true, ...compareFacts, extraSafeUnits: extraSafe.length };
    });

    // ---- 8. «Принять всё безопасное» -----------------------------------------------------------------------------------------------------------
    await stage(pass, 'accept_safe', ['review'], async () => {
      await member.front();
      await watchToasts(member);
      const before = member.requestCount('/api/decks/{id}/update-plan:apply');
      const reached = await member.tabTo(`element => element.matches('app-update-summary .action button.primary')`, '«Принять всё безопасное»');
      member.need(reached.focusVisible, 'the focus ring is not keyboard-visible on «Принять всё безопасное»');
      await member.press('Enter');
      await until(() => member.page(`return window.__mnemaToasts.some(text => text.startsWith(args[0]));`, WORDS.appliedPrefix), 'no toast after «Принять всё безопасное»', 30000);
      const toast = (await toastsOf(member)).find(text => text.startsWith(WORDS.appliedPrefix));
      member.need(member.requestCount('/api/decks/{id}/update-plan:apply') === before + 1 && member.lastRequest('/api/decks/{id}/update-plan:apply', 'POST').status === 200 && member.lastRequest('/api/decks/{id}/update-plan:apply', 'POST').authorization === true,
        'one apply with the bearer answered 200 was expected: ' + JSON.stringify(member.lastRequest('/api/decks/{id}/update-plan:apply', 'POST')));
      await until(() => member.page(`return document.querySelector('app-update-summary .line')?.textContent.trim() === args[0];`, '1 конфликт, 1 удалено автором'), 'the plan was not reloaded to the conflict and the deletion', 30000);
      const line = await member.read('.applied-line');
      member.need(line === toast, `the page keeps the outcome as «${line}»`);
      const focus = await activeText(member);
      member.need(focus?.inSummary && focus.tag === 'H2' && focus.text === WORDS.summaryTitle, 'the focus did not land on the summary heading: ' + JSON.stringify(focus));
      const summary = await summaryOf(member);
      member.need(summary.disabled && summary.hint === WORDS.nothingSafe, 'with nothing safe left the primary button should be inactive and say so: ' + JSON.stringify(summary));
      const tabs = await tabsOf(member);
      member.need(JSON.stringify(tabs.map(tab => tab.count)) === JSON.stringify([0, 0, 1, 1]), 'the tab counts after the apply are ' + JSON.stringify(tabs.map(tab => tab.count)));
      member.need(tabs.find(tab => tab.selected)?.name === 'Конфликты', 'the screen should move to the first non-empty tab «Конфликты»: ' + (await selectedTab(member)));
      const plan = await planOf(member, memberBearer, world.copyId);
      member.need(plan.state === 'READY' && plan.units.length === 2 && plan.units.some(unit => unit.class === 'CONFLICT') && plan.units.some(unit => unit.class === 'DELETED'),
        'the plan after the apply should hold the conflict and the deletion: ' + JSON.stringify(plan.units.map(unit => `${unit.class}:${unit.kind}`)));
      const fork = await forkOf(member, memberBearer, world.copyId);
      soft(fork.updateAvailable === true, 'badge-stays-with-pending', 'the badge «Есть обновление» must stay while a conflict and a deletion wait; the copy reports updateAvailable ' + fork.updateAvailable);
      await shoot(member, `updates-${pass.name}-after-safe-learner.png`);
      await navigate('/decks', member.tab);
      await until(() => member.page(`return [...document.querySelectorAll('a.deck-row')].some(row => row.querySelector('strong')?.textContent.trim() === args[0]);`, pass.title), 'the copy is not listed', 30000);
      soft(await member.page(`return [...document.querySelectorAll('a.deck-row')].find(row => row.querySelector('strong')?.textContent.trim() === args[0])?.querySelector('app-update-badge') !== null;`, pass.title),
        'badge-stays-in-list', 'the card of the copy lost its badge while a conflict and a deletion wait');
      return { toast, appliedLineEqualsToast: true, focusOnSummaryHeading: true, remaining: plan.units.length, badgeStays: fork.updateAvailable };
    });

    // ---- 9. the copy after the safe part: content, Study, history ----------------------------------------------------------------------------------
    await stage(pass, 'copy_after_safe', ['accept_safe', 'study'], async () => {
      await openHub(member, world.copyPath, 5);
      const rows = await materialRows(member);
      const byHead = Object.fromEntries([...pass.heads, pass.added.head].map(head => [head, rows.find(row => row.title.startsWith(head)) ?? null]));
      member.need(pass.heads.every(head => byHead[head] !== null) && byHead[pass.added.head] !== null, 'the copy lists ' + JSON.stringify(rows.map(row => row.title.slice(0, 20))));
      const read = async (key, expected) => { await navigate(`${world.copyPath}/materials/${key}`, member.tab); await until(() => member.has(expected), 'the material did not open: ' + expected, 30000); return member.body(); };
      let text = await read(byHead[pass.heads[0]].key, pass.materials[0]);
      member.need(text.includes(pass.materials[0]) && text.includes(pass.fix.trim()), 'material #1 does not show the author\'s fix');
      text = await read(byHead[pass.heads[1]].key, pass.materials[1]);
      member.need(text.includes(pass.learnerEdit.trim()) && !text.includes(pass.authorConflict.trim()), 'material #2 should keep the learner\'s edit and not take the author\'s: ' + text.slice(0, 200));
      text = await read(byHead[pass.added.head].key, pass.added.text);
      member.need(text.includes(pass.added.text), 'the new material #5 is not in the copy');
      member.need(byHead[pass.heads[3]] !== null, 'material #4 must still exist in the copy');
      await openHub(member, world.copyPath, 5);
      const added = (await materialRows(member)).find(row => row.title.startsWith(pass.added.head));
      member.need(/^1\s/u.test(added.count), `the new material should carry its exercise, the row says «${added.count}»`);
      // Study issues the new exercise among others.
      await member.metrics(pass.size);
      const seen = await studyCards(member, world.copyPath, fixtures, { stopAt: pass.addedExercise.prompt, max: 8 });
      member.need(seen.length >= 1 && seen.at(-1).prompt === pass.addedExercise.prompt, 'the new exercise was not issued: ' + JSON.stringify(seen));
      // The earlier attempt's history is intact.
      const progress = await progressOf(member, memberBearer, world.copyId);
      const earlier = progress.find(entry => entry.memberKey === world.attempted.memberKey);
      member.need(earlier !== undefined && earlier.lastAssessedAt !== null && earlier.lastAssessedAt >= world.attempted.lastAssessedAt,
        'the first attempt\'s progress was lost: before ' + JSON.stringify(world.attempted) + ', after ' + JSON.stringify(earlier));
      member.need(progress.filter(entry => entry.lastAssessedAt !== null).length >= 2, 'the new exercise attempt is not in the progress');
      return { fixShown: true, learnerEditKept: true, newMaterial: true, material4Kept: true, newExerciseIssuedAfterCards: seen.length, newExerciseNewBadge: seen.at(-1).isNew, historyKept: true,
        firstAttemptLastAssessedUnchanged: earlier.lastAssessedAt === world.attempted.lastAssessedAt };
    });

    // ---- 10. «Применить выбранное»: the author's version of the conflict and the deletion --------------------------------------------------------
    await stage(pass, 'apply_selected', ['accept_safe'], async () => {
      await member.front();
      await navigate(world.copyPath + '/update', member.tab);
      await until(() => exists('app-update-summary', member.tab), 'the review screen shows no summary', 30000);
      await watchToasts(member);
      await until(async () => (await selectedTab(member)) === 'Конфликты', 'the review should open on the first non-empty tab');
      // Conflict: Tab to the checked radio of the conflict row, ArrowRight selects «Взять вариант автора».
      await member.tabTo(`element => element.matches('.panel article.unit[data-class="conflict"] input[type="radio"]')`, 'the conflict decision');
      await member.press('ArrowRight');
      await until(() => member.page(`return document.querySelector('.panel article.unit[data-class="conflict"] input[type="radio"]:checked')?.parentElement.textContent.trim() === args[0];`, WORDS.takeAuthor), '«Взять вариант автора» was not chosen by ArrowRight');
      let summary = await summaryOf(member);
      member.need(!summary.selectedDisabled && summary.selectedHint.startsWith('Отмечено: 1'), 'after one pick «Применить выбранное» should say «Отмечено: 1»: ' + JSON.stringify(summary));
      await member.focus('app-count-tabs [role="tab"][aria-selected="true"]');
      await member.press('End');
      await until(async () => (await selectedTab(member)) === 'Удалено автором', 'the tab «Удалено автором» was not selected');
      await member.tabTo(`element => element.matches('.panel article.unit[data-class="deleted"] input[type="radio"]')`, 'the deletion decision');
      await member.press('ArrowRight');
      await until(() => member.page(`return document.querySelector('.panel article.unit[data-class="deleted"] input[type="radio"]:checked')?.parentElement.textContent.trim() === args[0];`, WORDS.deleteToo), '«Удалить и у меня» was not chosen by ArrowRight');
      summary = await summaryOf(member);
      member.need(summary.selectedHint.startsWith('Отмечено: 2'), 'the note should say «Отмечено: 2»: ' + JSON.stringify(summary));
      await shoot(member, `updates-${pass.name}-picks-learner.png`);
      const before = member.requestCount('/api/decks/{id}/update-plan:apply');
      await member.tabTo(`element => element.matches('.selected-action button')`, '«Применить выбранное»');
      await member.press('Enter');
      await until(() => member.page(`return document.querySelector('#update-state-title')?.textContent.trim() === args[0];`, WORDS.upToDate), 'the screen did not reach «У вас всё как у автора»', 30000);
      member.need(member.requestCount('/api/decks/{id}/update-plan:apply') === before + 1 && member.lastRequest('/api/decks/{id}/update-plan:apply', 'POST').status === 200, 'one apply answered 200 was expected');
      const toast = (await toastsOf(member)).find(text => text.startsWith(WORDS.appliedPrefix));
      member.need(toast !== undefined, 'no toast after «Применить выбранное»: ' + JSON.stringify(await toastsOf(member)));
      const focus = await member.page(`const e = document.activeElement; return { id: e?.id, text: e?.textContent.trim() };`);
      member.need(focus.id === 'update-state-title' && focus.text === WORDS.upToDate, 'the focus did not land on the heading of the new state: ' + JSON.stringify(focus));
      member.need(await member.has(toast) && !(await exists('app-update-summary', member.tab)), 'the up-to-date state keeps the applied line and drops the review');
      await shoot(member, `updates-${pass.name}-up-to-date-learner.png`);
      const plan = await planOf(member, memberBearer, world.copyId);
      member.need(plan.state === 'UP_TO_DATE', 'the plan is ' + plan.state);
      const fork = await forkOf(member, memberBearer, world.copyId);
      member.need(fork.updateAvailable === false, 'the copy still reports an update');
      // The copy now holds the author's #2 and no #4.
      await openHub(member, world.copyPath, 4);
      const rows = await materialRows(member);
      member.need(rows.every(row => !row.title.startsWith(pass.heads[3])), 'material #4 is still in the copy');
      const two = rows.find(row => row.title.startsWith(pass.heads[1]));
      await navigate(`${world.copyPath}/materials/${two.key}`, member.tab);
      await until(() => member.has(pass.authorConflict.trim()), 'material #2 does not show the author\'s version', 30000);
      member.need(!(await member.has(pass.learnerEdit.trim())), 'material #2 still shows the learner\'s edit');
      member.need(!(await exists('app-update-badge', member.tab)), 'a badge is shown on an up-to-date copy');
      await navigate('/decks', member.tab);
      await until(() => member.page(`return [...document.querySelectorAll('a.deck-row')].some(row => row.querySelector('strong')?.textContent.trim() === args[0]);`, pass.title), 'the copy is not listed', 30000);
      member.need(await member.page(`return [...document.querySelectorAll('a.deck-row')].find(row => row.querySelector('strong')?.textContent.trim() === args[0])?.querySelector('app-update-badge') === null;`, pass.title), 'the card keeps its badge after the last decision');
      return { toast, focusOnHeading: true, planState: plan.state, badgeGone: true, authorVersionTaken: true, deletionApplied: true };
    });

    // ---- 11. the automatic setting ----------------------------------------------------------------------------------------------------------------
    await stage(pass, 'auto_update', ['apply_selected'], async () => {
      await openHub(member, world.copyPath, 4);
      const box = 'section.auto-update input[type="checkbox"]';
      const state = () => member.page(`const input = document.querySelector(args[0]); return { checked: input?.checked, busy: input?.getAttribute('aria-busy') === 'true', label: input?.parentElement.textContent.trim() };`, box);
      let now = await state();
      member.need(now.label === WORDS.autoLabel && now.checked === false, 'the setting starts as ' + JSON.stringify(now));
      await member.tabTo(`element => element.matches('${box}')`, 'the automatic update setting');
      await member.press('Space');
      await until(async () => { const s = await state(); return s.checked === true && !s.busy; }, 'the setting did not turn on', 20000);
      member.need((await forkOf(member, memberBearer, world.copyId)).autoUpdate === true, 'the server did not store the setting');
      soft(await member.page(`return [...document.querySelectorAll('[role="status"], [aria-live]')].some(node => node.textContent.includes(args[0]));`, WORDS.autoOn),
        'auto-update-live-message', `the live region of the hub does not say «${WORDS.autoOn}» after the setting was turned on`);
      await shoot(member, `updates-${pass.name}-auto-on-learner.png`);
      await openHub(member, world.copyPath, 4);
      now = await state();
      member.need(now.checked === true, 'the checked state did not survive a reload');
      await member.tabTo(`element => element.matches('${box}')`, 'the automatic update setting');
      await member.press('Space');
      await until(async () => { const s = await state(); return s.checked === false && !s.busy; }, 'the setting did not turn off', 20000);
      member.need((await forkOf(member, memberBearer, world.copyId)).autoUpdate === false, 'the server did not store the cleared setting');
      await openHub(member, world.copyPath, 4);
      member.need((await state()).checked === false, 'the cleared state did not survive a reload');
      return { toggledByKeyboard: true, persistedOn: true, persistedOff: true };
    });

    // ---- 12. a second update, then the copy is detached ----------------------------------------------------------------------------------------------------
    await stage(pass, 'detach', ['apply_selected'], async () => {
      await owner.metrics(DESKTOP);
      await appendToMaterial(owner, world.deckPath, world.keys[2], pass.later);
      await openHub(owner, world.deckPath, 4);
      await until(() => owner.page(`return [...document.querySelectorAll('section.publication button')].some(button => button.textContent.trim() === args[0]);`, WORDS.publishUpdate), 'the hub offers no «Опубликовать обновление»', 30000);
      await owner.enter('section.publication .publication-status button', WORDS.publishUpdate);
      await until(() => exists('section.publication form.publication-update textarea', owner.tab), 'the release note form did not open');
      await owner.enter('section.publication form.publication-update button[type="submit"]');
      await until(async () => { const now = (await apiJson(owner, ownerBearer, 'GET', `/api/decks/${world.deckId}/publication`)).body; return now.publishedRevisionId === now.headRevisionId; }, 'the second update was not published', 30000);
      await openHub(member, world.copyPath, 4);
      await until(() => exists('section.update-offer', member.tab), 'the second update did not produce an offer', 30000);
      member.need(await exists('app-update-badge', member.tab), 'no badge for the second update');
      await watchToasts(member);
      // «⋯» -> «Отвязать от автора» -> confirm.
      await member.tabTo(`element => element.matches('app-action-menu button.trigger')`, 'the «⋯» button');
      await member.press('Enter');
      await until(() => member.page(`return document.querySelector('app-action-menu [role="menu"]')?.matches(':popover-open') === true;`), 'the «⋯» menu did not open');
      const items = await member.page(`return [...document.querySelectorAll('app-action-menu [role="menuitem"]')].map(node => node.textContent.trim());`);
      member.need(items.includes(WORDS.detach), 'the menu offers ' + JSON.stringify(items));
      for (let step = 0; step < 4; step++) {
        if (await member.page(`return document.activeElement?.textContent.trim() === args[0];`, WORDS.detach)) break;
        await member.press('ArrowDown');
      }
      member.need(await member.page(`return document.activeElement?.textContent.trim() === args[0];`, WORDS.detach), 'ArrowDown did not reach «Отвязать от автора»');
      await member.press('Enter');
      await until(() => exists('section.detach-confirm', member.tab), 'the detach confirmation did not open');
      member.need(await member.page(`return document.activeElement?.id === 'detach-title';`), 'the focus did not move to the confirmation heading');
      await shoot(member, `updates-${pass.name}-detach-confirm-learner.png`);
      await member.enter('section.detach-confirm button.button:not(.quiet)');
      await until(() => member.page(`return window.__mnemaToasts.includes(args[0]);`, WORDS.detached), 'no toast «Копия отвязана от автора.»', 30000);
      await until(async () => !(await exists('section.update-offer', member.tab)), 'the offer stayed after detaching');
      member.need(!(await exists('app-update-badge', member.tab)), 'the badge stayed after detaching');
      member.need(await member.page(`return ![...document.querySelectorAll('a')].some(link => link.getAttribute('href')?.endsWith('/update'));`), 'a link to the update review stayed after detaching');
      member.need(!(await exists('section.auto-update', member.tab)), 'the automatic setting stayed on a detached copy');
      member.need(await member.page(`return document.activeElement === document.querySelector('h1');`), 'the focus did not move to the hub heading');
      const fork = await forkOf(member, memberBearer, world.copyId);
      member.need(fork.detached === true && fork.updateAvailable === false, 'the copy reports ' + JSON.stringify(fork));
      const plan = await planOf(member, memberBearer, world.copyId);
      member.need(plan.state === 'DETACHED', 'the plan of a detached copy is ' + plan.state);
      await navigate(world.copyPath + '/update', member.tab);
      await until(() => member.page(`return document.querySelector('#update-state-title')?.textContent.trim() === args[0];`, WORDS.detachedState), 'the review of a detached copy does not say so', 30000);
      await navigate('/decks', member.tab);
      await until(() => member.page(`return [...document.querySelectorAll('a.deck-row')].some(row => row.querySelector('strong')?.textContent.trim() === args[0]);`, pass.title), 'the copy is not listed', 30000);
      member.need(await member.page(`return [...document.querySelectorAll('a.deck-row')].find(row => row.querySelector('strong')?.textContent.trim() === args[0])?.querySelector('app-update-badge') === null;`, pass.title), 'the card keeps its badge after detaching');
      await shoot(member, `updates-${pass.name}-detached-learner.png`);
      return { secondUpdateOffered: true, detachedByKeyboard: true, badgeGone: true, updateLinkGone: true, planState: plan.state };
    });
  }

  // ---- teardown -----------------------------------------------------------------------------------------------------------------------------------
  for (const actor of [owner, member]) await navigate('/login', actor.tab).catch(() => {});
  for (const actor of [owner, member]) await actor.metrics({ width: 1280, height: 900, deviceScaleFactor: 1, mobile: false }).catch(() => {});
  record('updates_summary', { state: failures.length === 0 ? 'passed' : 'failed', passes: PASSES.map(spec => spec.name), durationMs: Date.now() - started, failures: failures.length });
  return { failures, gaps: [] };
}
