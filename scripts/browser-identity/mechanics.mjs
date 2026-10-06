// Real-browser baseline of the seven exercise mechanics: create -> preview -> save -> reopen -> study.
// Runs only with `--authoring --media --mechanics`, after the base authoring flow, through the real Angular UI on a
// desktop viewport with real CDP keyboard/mouse input where the interaction is natural. Node 24 built-ins only.
//
// This is evidence, not a product fix: nothing here works around a defective control. A broken step is recorded
// as a failed scenario with its screenshot and the UI's own reason, and the run is reported as failed.
import { writeFile } from 'node:fs/promises';
import { join } from 'node:path';

// Page-side lookup shared by every helper. A `spec` addresses elements by role-like structure instead of brittle
// text selectors: `css` (required); optional ancestor `within` (+ `withinIndex`), optional `slot` (n-th slot editor
// inside the ancestor); filters `text`, `includes`, `label`, `labelIncludes`; `index` picks the n-th match.
const FIND = `function(spec) {
  let roots = spec.within
    ? [...document.querySelectorAll(spec.within)].filter((_, i) => spec.withinIndex === undefined || i === spec.withinIndex)
    : [document];
  if (spec.slot !== undefined) {
    roots = roots.map(root => root.querySelectorAll('app-exercise-slot-editor')[spec.slot]).filter(Boolean);
  }
  const found = [];
  for (const root of roots) for (const element of root.querySelectorAll(spec.css)) {
    const text = element.textContent.trim();
    const label = element.getAttribute('aria-label') || '';
    if (spec.text !== undefined && text !== spec.text) continue;
    if (spec.includes !== undefined && !text.includes(spec.includes)) continue;
    if (spec.label !== undefined && label !== spec.label) continue;
    if (spec.labelIncludes !== undefined && !label.includes(spec.labelIncludes)) continue;
    found.push(element);
  }
  return found;
}`;

const SELF_CHECK = {
  prompt: 'Что такое интервальное повторение?',
  reference: 'Эталон: повторять материал через растущие интервалы.',
  objective: 'Интервальное повторение (самопроверка)'
};
const CLOZE = {
  text: 'The cat sat on the mat while the cat slept.',
  segments: ['The ', ' sat on the mat while the ', ' slept.'],
  objective: 'Cloze: повторяющееся слово cat'
};
const MULTIPLE = {
  prompt: 'Какие из этих слов — города Франции?',
  options: ['Париж', 'Берлин', 'Лион'], correct: ['Париж', 'Лион'], mediaOption: 2,
  objective: 'Города Франции (несколько ответов)'
};
const SINGLE = {
  prompt: 'Какой город является столицей Японии?',
  options: ['Токио', 'Киото', 'Осака'], correct: 'Токио',
  objective: 'Столица Японии (один ответ)'
};
const MATCH = {
  pairs: [{ left: 'cat', right: 'кошка' }, { left: 'dog', right: null }, { left: 'bird', right: 'птица' }],
  objective: 'Подбор пар: слова и звук'
};
const FREE_RESPONSE_PROMPT = 'Прослушайте запись и напишите ответ';
// ORDER: a sentence with punctuation and a repeated word (word helper), a code block and an image frame.
const ORDER = {
  instruction: 'Восстановите предложение, код и кадр результата.',
  sentence: 'Это очень очень важно, правда?',
  words: ['Это', 'очень', 'очень', 'важно,', 'правда?'],
  code: 'for (int i = 0; i < n; i++) {\n    sum += i;\n}',
  codeName: 'for (int i = 0; i < n; i++) { sum += i; }',   // the accessible name collapses white space
  imageAlt: 'Кадр: готовый результат',
  objective: 'Порядок: предложение, код и кадр'
};
// CATEGORIZE: three real groups (the last one stays an empty distractor), a text item set and an uploaded audio item.
// A fourth group is created only to be removed again, which must move its item and leave nothing dangling.
const CATEGORIZE = {
  instruction: 'Распределите слова и звук по частям речи.',
  groups: ['Существительное', 'Глагол', 'Не относится'],
  spare: 'Лишняя группа',
  texts: [{ text: 'дом', group: 'Существительное' }, { text: 'бежать', group: 'Глагол' }, { text: 'река', group: 'Существительное' }],
  audioGroup: 'Глагол',
  objective: 'Распределение слов по частям речи'
};

export async function runMechanics(ctx) {
  const { tab, config, record, SafeFailure, until, exists, bodyIncludes, sanitizedLocation } = ctx;
  class UiFailure extends SafeFailure {}
  const need = (value, label) => { if (!value) throw new UiFailure(label); };
  const failures = [];
  const findings = [];
  const wire = { hints: 0, pairChecks: 0, previews: 0, attempts: [] };
  const attemptRequests = new Set();
  const editPaths = {};
  const root = 'form.inspector';

  // ----- observation of the real Study wire (no ids, tokens or answers are stored) -----------------------------------
  tab.on('Network.requestWillBeSent', event => {
    const url = new URL(event.request.url);
    if (event.request.method !== 'POST') return;
    if (url.pathname === '/api/exercise-previews') wire.previews++;
    else if (url.pathname.endsWith('/pair-checks')) wire.pairChecks++;
    if (url.pathname.endsWith('/hints')) wire.hints++;
    if (/\/study-sessions\/[0-9a-f-]{36}\/attempts$/.test(url.pathname)) attemptRequests.add(event.requestId);
  });
  tab.on('Network.loadingFinished', event => {
    if (!attemptRequests.delete(event.requestId)) return;
    ctx.run(tab.call('Network.getResponseBody', { requestId: event.requestId }).then(result => {
      const body = JSON.parse(result.base64Encoded ? Buffer.from(result.body, 'base64').toString() : result.body);
      wire.attempts.push({ result: body.feedback?.result ?? null, appliedRules: body.feedback?.appliedRules ?? [],
        evidenceClass: body.evidence?.evidenceClass ?? null });
    }));
  });

  // ----- page helpers: every value crosses CDP as an argument, never interpolated into page source -------------------
  const call = (body, ...args) => tab.callFunction(`function(...args) { const find = ${FIND}; ${body} }`, args);
  const pick = '(find(args[0])[args[0].index || 0])';
  const count = spec => call('return find(args[0]).length;', spec);
  const has = async spec => (await count(spec)) > 0;
  const text = spec => call(`const e = ${pick}; return e ? e.textContent.trim() : null;`, spec);
  const checked = spec => call(`const e = ${pick}; return e ? e.checked : null;`, spec);
  const waitFor = async (predicate, label, timeoutMs) => {
    // A UiFailure raised inside a predicate is a real verdict and must not be retried like a transient navigation error.
    let verdict = null;
    await until(async () => {
      try { return await predicate(); } catch (error) {
        if (error instanceof UiFailure) { verdict = error; return true; }
        throw error;
      }
    }, label, timeoutMs);
    if (verdict !== null) throw verdict;
  };
  const focusEl = async spec => (await scrollSettled(), need(await call(`const e = ${pick};
    if (!(e instanceof HTMLElement) || e.matches(':disabled')) return false;
    e.scrollIntoView({ block: 'center', behavior: 'instant' }); e.focus(); return document.activeElement === e;`, spec),
  'target could not take keyboard focus: ' + (spec.label ?? spec.text ?? spec.includes ?? spec.css)));
  // Angular renders zoneless: a state change made by an event is painted on the next frame, not synchronously. After
  // every real input wait two animation frames in the page so the DOM that follows reflects the action.
  const renderSettled = () => tab.callFunction(`function() { return new Promise(resolve =>
    requestAnimationFrame(() => requestAnimationFrame(() => resolve(true)))); }`, []);
  const press = async (key, code, virtualKeyCode, keyText) => {
    const event = { key, code, windowsVirtualKeyCode: virtualKeyCode };
    await tab.call('Input.dispatchKeyEvent', keyText === undefined
      ? { type: 'rawKeyDown', ...event } : { type: 'keyDown', text: keyText, unmodifiedText: keyText, ...event });
    await tab.call('Input.dispatchKeyEvent', { type: 'keyUp', ...event });
    await renderSettled();
  };
  const keys = {
    Tab: () => press('Tab', 'Tab', 9), ArrowDown: () => press('ArrowDown', 'ArrowDown', 40),
    Enter: () => press('Enter', 'Enter', 13, '\r'), Space: () => press(' ', 'Space', 32, ' ')
  };
  const typeInto = async (spec, content) => {
    await focusEl(spec);
    await call(`const e = ${pick}; if ('select' in e) e.select(); return true;`, spec);
    await tab.call('Input.insertText', { text: content });
    await renderSettled();
  };
  // The app sets `scroll-behavior: smooth` and also scrolls smoothly to a step it just opened. A click aimed at a rect
  // read while such an animation is still running lands elsewhere, so wait until the page position has been still for
  // several frames before measuring, then jump instantly (an explicit `behavior` overrides the CSS) and measure again.
  const scrollSettled = () => tab.callFunction(`function() { return new Promise(resolve => {
    let last = scrollY, still = 0, frames = 0;
    const tick = () => { frames++; if (scrollY === last) still++; else { still = 0; last = scrollY; }
      if (still >= 6 || frames > 180) resolve(still >= 6); else requestAnimationFrame(tick); };
    requestAnimationFrame(tick); }); }`, []);
  const realClick = async spec => {
    need(await scrollSettled(), 'the page kept scrolling and never settled before a click');
    const point = await call(`const e = ${pick};
      if (!(e instanceof HTMLElement) || e.matches(':disabled')) return null;
      e.scrollIntoView({ block: 'center', behavior: 'instant' });
      const r = e.getBoundingClientRect(); return { x: r.left + r.width / 2, y: r.top + r.height / 2 };`, spec);
    need(point !== null, 'click target absent or disabled: ' + (spec.text ?? spec.label ?? spec.includes ?? spec.css));
    await tab.call('Input.dispatchMouseEvent', { type: 'mouseMoved', x: point.x, y: point.y });
    await tab.call('Input.dispatchMouseEvent', { type: 'mousePressed', x: point.x, y: point.y, button: 'left', clickCount: 1 });
    await tab.call('Input.dispatchMouseEvent', { type: 'mouseReleased', x: point.x, y: point.y, button: 'left', clickCount: 1 });
    await renderSettled();
  };
  const activate = async spec => { await focusEl(spec); await keys.Space(); };
  const submitKey = async spec => { await focusEl(spec); await keys.Enter(); };
  const metrics = size => tab.call('Emulation.setDeviceMetricsOverride', size);
  const desktop = () => metrics({ width: 1440, height: 900, deviceScaleFactor: 1, mobile: false });
  const phone = () => metrics({ width: 390, height: 844, deviceScaleFactor: 1, mobile: true });
  const overflowing = () => call('return document.documentElement.scrollWidth > document.documentElement.clientWidth;');

  // ----- slot media helpers (a slot is addressed as { within, withinIndex, slot }) ---------------------------------
  const pair = (index, slot) => ({ within: root + ' section[data-pair]', withinIndex: index, slot });
  const option = index => ({ within: root + ' section[data-option]', withinIndex: index, slot: 0 });
  const slotBlocks = slot => call(`return find({ ...args[0], css: 'li.block' }).map(block => ({
    kind: block.querySelector('.block-head strong')?.textContent.replace(/^\\d+\\.\\s*/, '').trim() ?? null,
    value: block.querySelector('textarea, input[type="text"]')?.value ?? null,
    fileChosen: block.textContent.includes('Файл выбран') }));`, slot);
  const queuedRow = (slot, name) => call(`const row = find({ ...args[0], css: '.media-row', includes: args[1] })[0];
    if (!row) return null;
    return { ready: row.querySelector('.media-status')?.textContent?.includes('Готов к просмотру') === true,
      error: row.querySelector('.media-status[role="alert"]')?.textContent.trim() ?? null };`, slot, name);
  const MEDIA_KIND = { audio: { button: 'Добавить аудио', block: 'Аудио' }, image: { button: 'Добавить изображение', block: 'Изображение' } };
  async function uploadIntoSlot(slot, name, label, upload, kind = 'audio') {
    await realClick({ ...slot, css: 'button[data-add]', includes: MEDIA_KIND[kind].button });
    await waitFor(() => has({ ...slot, css: 'app-native-media-upload .media-drop' }), label + ': picker did not render');
    await upload();
    await waitFor(async () => {
      const row = await queuedRow(slot, name);
      need(row === null || row.error === null, label + ' upload failed in the UI: ' + row?.error);
      return row !== null && row.ready;
    }, label + ' did not become READY in the picker', 45_000);
    need(await call(`const row = find({ ...args[0], css: '.media-row', includes: args[1] })[0];
      const choose = [...(row?.querySelectorAll('button') ?? [])].find(b => b.textContent.trim() === 'Добавить в упражнение');
      if (!(choose instanceof HTMLButtonElement)) return false; choose.click(); return true;`, slot, name),
    label + ': READY file could not be attached to the slot');
    await waitFor(async () => (await slotBlocks(slot)).some(block => block.kind === MEDIA_KIND[kind].block), label + ' block did not enter the slot');
  }
  const dropAudio = (slot, name) => call(`const drop = find({ ...args[0], css: 'app-native-media-upload .media-drop' })[0];
    if (!(drop instanceof HTMLElement)) return false;
    const bytes = Uint8Array.from(atob(args[1]), value => value.charCodeAt(0));
    const transfer = new DataTransfer(); transfer.items.add(new File([bytes], args[2], { type: 'audio/mpeg' }));
    drop.dispatchEvent(new DragEvent('drop', { bubbles: true, cancelable: true, dataTransfer: transfer })); return true;`,
  slot, config.mediaClips.audio, name).then(done => need(done, 'synthetic audio file could not be dropped on the slot picker'));
  // A generated PNG, like the base flow's: no external fixture file is needed for an image item.
  const dropImage = (slot, name) => call(`const drop = find({ ...args[0], css: 'app-native-media-upload .media-drop' })[0];
    if (!(drop instanceof HTMLElement)) return false;
    return (async () => {
      const canvas = document.createElement('canvas'); canvas.width = 96; canvas.height = 72;
      const drawing = canvas.getContext('2d');
      drawing.fillStyle = '#eee8dc'; drawing.fillRect(0, 0, 96, 72); drawing.fillStyle = '#281378'; drawing.fillRect(12, 18, 72, 36);
      const blob = await new Promise(resolve => canvas.toBlob(resolve, 'image/png'));
      if (!blob) return false;
      const transfer = new DataTransfer(); transfer.items.add(new File([blob], args[1], { type: 'image/png' }));
      drop.dispatchEvent(new DragEvent('drop', { bubbles: true, cancelable: true, dataTransfer: transfer })); return true;
    })();`, slot, name).then(done => need(done, 'generated PNG could not be dropped on the slot picker'));
  /** An upload leaves the slot's empty text block in front of the media block; an item that is only media drops it. */
  const dropEmptyTextBlock = async (slot, label) => need(await call(`const b = find({ ...args[0], css: '[data-block="0"] button[aria-label^="Удалить блок"]' })[0];
    if (!(b instanceof HTMLButtonElement)) return false; b.click(); return true;`, slot), label + ': empty text block could not be removed');
  const slotButton = (slot, label) => call(`const b = find({ ...args[0], css: 'button', text: args[1] })[0];
    if (!(b instanceof HTMLButtonElement) || b.disabled) return false;
    b.scrollIntoView({ block: 'center', behavior: 'instant' }); b.focus(); return document.activeElement === b;`, slot, label);

  // ----- editor helpers ----------------------------------------------------------------------------------------------
  async function openEditor(mechanic) {
    await desktop();
    await ctx.navigate(ctx.materialPath + '/exercises/new', tab);
    const radio = 'input[name="mechanic"][value="' + mechanic + '"]';
    await waitFor(async () => (await exists(radio, tab)) && (await exists(root, tab)), mechanic + ' editor did not load');
    need(await ctx.click(radio, tab), mechanic + ' mechanic could not be selected');
    await waitFor(async () => await checked({ css: radio }) === true, mechanic + ' mechanic not marked');
    await waitFor(async () => (await has({ css: '#exercise-preview-anchor' })) && (await has({ css: 'section.step' })),
      mechanic + ' preview and first step did not open after choosing the tile');
  }
  // The editor opens one step at a time: «Продолжить» validates the step and opens the next one.
  const advance = async step => {
    await realClick({ css: '[data-continue]' });
    await waitFor(() => has({ css: '#step-' + step }), 'step «' + step + '» did not open after «Продолжить»');
  };
  const revealFinish = async () => {
    for (let guard = 0; guard < 4 && await has({ css: '[data-continue]' }); guard++) {
      await realClick({ css: '[data-continue]' });
      await new Promise(resolve => setTimeout(resolve, 150));
    }
    await waitFor(() => has({ css: '#step-finish' }), 'the save step did not open');
  };
  const setObjective = async title => {
    await revealFinish(); await typeInto({ css: '#objective-title' }, title); };
  async function saveNewExercise(label) {
    await realClick({ css: '.save-bar button[type="submit"]', text: 'Создать упражнение' });
    await waitFor(async () => {
      if (/^\/decks\/[0-9a-f-]{36}\/exercises\/[0-9a-f-]{36}\/edit$/.test(await sanitizedLocation(tab))) return true;
      const rejected = await text({ css: '#exercise-errors' });
      need(rejected === null, label + ' save rejected by the editor: ' + rejected);
      const banner = await text({ css: '.notice.error[role="alert"]' });
      need(banner === null, label + ' save failed: ' + banner);
      return false;
    }, label + ' did not reach its edit route after save', 30_000);
    return sanitizedLocation(tab);
  }
  async function reopen(label) {
    await tab.call('Page.reload', { ignoreCache: true });
    await waitFor(async () => (await exists(root, tab)) && (await exists('#exercise-preview-anchor', tab))
      && !(await bodyIncludes('Загружаем фрагменты материала', tab)), label + ' edit page did not reload');
  }
  async function chooseMaterialFragment(spec, labelStart) {
    await focusEl(spec);
    await keys.ArrowDown();
    await waitFor(() => has({ css: '[role="option"]' }), 'material fragment list did not open');
    const target = await call(`return [...document.querySelectorAll('[role="option"]')]
      .findIndex(o => o.textContent.trim().startsWith(args[0]));`, labelStart);
    need(target > 0, 'material fragment is not offered by the editor');
    for (let step = 0; step < target; step++) await keys.ArrowDown();
    await keys.Enter();
  }
  const marks = () => call(`return [...document.querySelectorAll(args[0] + ' section[data-option] > .check-line input')]
    .map(i => i.checked).join();`, root);
  const modeRadios = () => call(`return [...document.querySelectorAll(args[0] + ' .row-stack input[type="radio"]')]
    .map(i => i.checked).join();`, root);

  // ----- scenario runner: one soft-failing record per mechanic step -----------------------------------------------
  async function scenario(name, run) {
    ctx.setStep(name);
    const started = Date.now();
    try {
      const details = await run();
      record(name, { state: 'passed', ...details, durationMs: Date.now() - started });
      return true;
    } catch (error) {
      const reason = error instanceof SafeFailure ? error.message : 'driver_failure';
      const screenshot = `failure-${name}.png`;
      await ctx.saveScreenshot(screenshot, tab).catch(() => {});
      if (config.diagnosticsDir) {
        const page = await tab.evaluate('document.body.innerText.slice(0, 8000)').catch(() => '');
        await writeFile(join(config.diagnosticsDir, `failure-${name}.txt`), String(page));
      }
      record(name, { state: 'failed', reason, screenshot, durationMs: Date.now() - started, mediaTrace: ctx.mediaTrace() });
      failures.push(name);
      return false;
    }
  }
  const finding = (id, detail) => { findings.push({ id, detail }); };

  // =================================================================================================================
  // 0. One-page editor: initial state, demo, partial draft, mobile, reduced motion (real browser, real preview endpoint)
  // =================================================================================================================
  await scenario('mechanics_editor_flow', async () => {
    await desktop();
    await ctx.navigate(ctx.materialPath + '/exercises/new', tab);
    await waitFor(async () => (await has({ css: 'app-mechanic-picker label.tile' }))
      && !(await bodyIncludes('Загружаем фрагменты материала', tab)), 'editor did not load');
    need(!(await has({ css: '#exercise-preview-anchor' })) && !(await has({ css: 'section.step' })),
      'the initial state must show the type choice alone');
    need(!(await has({ css: 'aside, .preview-column, .material-card' })), 'a side column or the material block is back');
    need((await count({ css: 'label.tile' })) === 7, 'seven type tiles expected');
    need(/^К упражнениям · \d+$/.test(await text({ css: '.jump-link' }) ?? ''), 'the anchor to the existing exercises is missing');
    await ctx.saveFullScreenshot('editor-initial-1440.png', tab);

    // A tile opens the demo and scrolls to it once.
    await realClick({ css: 'label.tile', includes: 'Выбрать ответ' });
    await waitFor(() => has({ css: '#exercise-preview-anchor [data-mode="DEMO"] .stamp' }), 'the demo did not open after choosing a tile');
    need((await text({ css: '#exercise-preview-anchor .stamp' })) === 'Пример', 'the demo badge is missing');
    need((await text({ css: '[data-preview-caption]' })).includes('Это пример упражнения'), 'the demo explanation is missing');
    await waitFor(() => call(`const r = document.querySelector('#exercise-preview-anchor').getBoundingClientRect();
      return r.top > -2 && r.top < 260;`), 'the page did not scroll to the preview');
    await ctx.saveFullScreenshot('editor-demo-choice-1440.png', tab);

    // The demo is playable through the preview endpoint only: wrong answer, right answer, completion, restart.
    const before = { attempts: wire.attempts.length, hints: wire.hints, pairs: wire.pairChecks, previews: wire.previews };
    const demoOptions = { css: '#exercise-preview-anchor input[type="radio"]' };
    await activate({ ...demoOptions, index: 0 });
    await submitKey({ css: '#exercise-preview-anchor button[data-submit]' });
    await waitFor(() => has({ css: '#preview-result-title' }), 'the demo verdict did not arrive from the preview endpoint', 20_000);
    need((await text({ css: '#preview-result-title' })) === 'Нужно повторить', 'a wrong demo answer must be reported as wrong');
    await realClick({ css: '#exercise-preview-anchor button[data-restart]' });
    await waitFor(() => has({ css: '#exercise-preview-anchor app-learner-exercise' }), 'the demo did not restart');
    await activate({ ...demoOptions, index: 1 });
    await submitKey({ css: '#exercise-preview-anchor button[data-submit]' });
    await waitFor(() => has({ css: '#preview-result-title' }), 'the second demo verdict did not arrive', 20_000);
    need((await text({ css: '#preview-result-title' })) === 'Верно', 'the correct demo answer must be accepted');
    need((await text({ css: '#exercise-preview-anchor .result' })).includes('Пример завершён'), 'the completion note is missing');
    need(wire.previews === before.previews + 2 && wire.attempts.length === before.attempts && wire.hints === before.hints
      && wire.pairChecks === before.pairs, 'the demo must use only the preview endpoint (no attempt, hint or pair-check request)');
    await realClick({ css: '#exercise-preview-anchor button[data-restart]' });

    // The first authored input turns the same area into the author's own, still unfinished, task.
    await typeInto({ css: '#choice-prompt-text-0' }, 'Какой цвет у неба?');
    await waitFor(() => has({ css: '#exercise-preview-anchor [data-mode="AUTHOR_DRAFT"]' }), 'the preview did not switch to the author draft');
    const draft = await call(`const root = document.querySelector('#exercise-preview-anchor');
      return { badge: root.querySelector('.stamp')?.textContent.trim(), text: root.textContent,
        submitDisabled: root.querySelector('button[data-submit]')?.disabled === true, media: root.querySelectorAll('audio, img').length,
        reason: root.querySelector('.blocked')?.textContent.trim() ?? null };`);
    need(draft.badge === 'Ваше задание' && draft.text.includes('Какой цвет у неба?') && !draft.text.includes('Звук 1') && draft.media === 0,
      'the draft preview must show the author\'s content and none of the demo');
    need(draft.submitDisabled && draft.reason?.includes('Проверить ответ пока нельзя'), 'an unfinished draft must not be checkable');
    await ctx.saveFullScreenshot('editor-partial-draft-1440.png', tab);

    // Reduced motion: the jump to a chosen tile is instantaneous.
    await tab.call('Emulation.setEmulatedMedia', { features: [{ name: 'prefers-reduced-motion', value: 'reduce' }] });
    await call('window.scrollTo(0, 0); return true;');
    await realClick({ css: 'label.tile', includes: 'Сопоставить элементы' });
    await waitFor(() => has({ css: '#exercise-preview-anchor [data-mode]' }), 'the preview of the next tile did not appear');
    const place = () => call(`return Math.round(document.querySelector('#exercise-preview-anchor').getBoundingClientRect().top);`);
    await new Promise(resolve => setTimeout(resolve, 120));
    const first = await place();
    await new Promise(resolve => setTimeout(resolve, 120));
    const second = await place();
    await tab.call('Emulation.setEmulatedMedia', { features: [] });
    need(first === second && first > -2 && first < 260, `with reduced motion the jump must be complete at once (${first} then ${second})`);

    await phone();
    const wide = await overflowing();
    await ctx.saveFullScreenshot('editor-mobile-390.png', tab);
    await desktop();
    need(!wide, 'the editor overflows horizontally at 390 px');
    return { initialStateOnlyTypeChoice: true, demoPlayable: true, previewEndpointOnly: true, draftModeShowsOwnContent: true,
      submitBlockedWithReason: true, reducedMotionInstantJump: true, noHorizontalOverflow390: true, viewports: ['1440x900', '390x844'] };
  });

  // =================================================================================================================
  // 0b. Real-geometry checks that jsdom unit tests cannot make (they own none of this): reflow, containment, decoration
  // =================================================================================================================
  // No horizontal overflow of the editor in any mechanic with a long unbroken string, at 320 and 390 px.
  await scenario('mechanics_editor_reflow', async () => {
    const long = 'я'.repeat(300);
    const kinds = ['SELF_CHECK', 'FREE_RESPONSE', 'CLOZE', 'CHOICE', 'MATCH', 'ORDER', 'CATEGORIZE'];
    for (const mechanic of kinds) {
      await openEditor(mechanic);
      // Some mechanics open on a step without a text field (MATCH: optional instruction); add a text block first.
      const field = root + ' textarea, ' + root + ' input[type="text"]';
      const usable = () => call(`return [...document.querySelectorAll(args[0])].some(e => e.offsetParent !== null && !e.disabled && !e.readOnly);`, field);
      if (!(await usable())) {
        await realClick({ css: root + ' button[data-add]', includes: '+ Текст' });
        await waitFor(usable, mechanic + ' offered no text field for the long string');
      }
      need(await call(`const e = [...document.querySelectorAll(args[0])].find(x => x.offsetParent !== null && !x.disabled && !x.readOnly);
        e.scrollIntoView({ block: 'center', behavior: 'instant' }); e.focus(); if ('select' in e) e.select(); return document.activeElement === e;`, field),
      mechanic + ' text field could not take focus');
      await tab.call('Input.insertText', { text: long });
      await renderSettled();
      for (const width of [320, 390]) {
        await metrics({ width, height: 844, deviceScaleFactor: 1, mobile: false });
        await renderSettled();
        need(!(await overflowing()), `${mechanic} editor overflows horizontally at ${width} px with a 300-character string`);
      }
      await ctx.saveScreenshot(`mechanics-editor-reflow-${mechanic.toLowerCase()}-320.png`, tab);
    }
    await desktop();
    return { mechanics: kinds, widths: [320, 390], longUnbrokenString: 300, noHorizontalOverflow: true };
  });

  // The native renderer contains long Russian and unbreakable text at 320 px, with 2x root text and at 200% zoom.
  await scenario('mechanics_renderer_reflow', async () => {
    need(ctx.materialPath, 'base flow did not provide the material route');
    await desktop();
    await ctx.navigate(ctx.materialPath, tab);
    await waitFor(() => has({ css: 'app-native-document-renderer article p' }), 'the published material did not render');
    // Cloned blocks keep the renderer's scoped-style attributes, so the real stylesheet applies to the long content.
    need(await call(`const article = document.querySelector('app-native-document-renderer article');
      const paragraph = article?.querySelector('p');
      if (!article || !paragraph) return false;
      const heading = article.querySelector('h1, h2, h3');
      const long = paragraph.cloneNode(true); long.textContent = 'A'.repeat(512); article.append(long);
      if (heading) { const clone = heading.cloneNode(true);
        clone.textContent = 'Длинный русский заголовок о памяти и осмысленном обучении'; article.prepend(clone); }
      return true;`), 'long content could not be added to the rendered material');
    const contained = () => call(`const sheet = document.querySelector('app-native-document-renderer').closest('.paper-surface')
      ?? document.querySelector('app-native-document-renderer').parentElement;
      const limit = sheet.getBoundingClientRect().right + 1;
      const wide = [...sheet.querySelectorAll('*')].filter(e => e.getBoundingClientRect().right > limit)
        .map(e => e.tagName.toLowerCase() + (e.className && typeof e.className === 'string' ? '.' + e.className.trim().split(' ')[0] : '')).slice(0, 4).join(' ');
      const blocks = [...document.querySelectorAll('app-native-document-renderer article p, app-native-document-renderer article h1, app-native-document-renderer article h2, app-native-document-renderer article h3')];
      return { wide, text: blocks.length >= 2 && blocks.every(e => e.scrollWidth <= e.clientWidth + 1 && e.getBoundingClientRect().right <= limit),
        sheet: sheet.scrollWidth <= sheet.clientWidth, page: document.documentElement.scrollWidth <= document.documentElement.clientWidth,
        font: parseFloat(getComputedStyle(document.querySelector('app-native-document-renderer article')).fontSize) };`);
    // The sheet also holds media players whose controls are outside this scenario's contract (long text containment);
    // sheet-level overflow is recorded as an observation in the evidence, not as a failure of the text check.
    const observed = [];
    const widths = {};
    for (const width of [320, 390, 1440]) {
      await metrics({ width, height: 844, deviceScaleFactor: 1, mobile: false });
      await renderSettled();
      const result = await contained();
      need(result.text, `long text overflows the material at ${width} px`);
      if (!result.sheet) observed.push(`${width}px: ${result.wide}`);
      widths[width] = result.page;
    }
    await metrics({ width: 320, height: 844, deviceScaleFactor: 1, mobile: false });
    await renderSettled();
    const base = (await contained()).font;
    await call(`document.documentElement.style.fontSize = '32px'; return true;`);
    await renderSettled();
    const doubled = await contained();
    await ctx.saveScreenshot('mechanics-renderer-reflow-320-2x-text.png', tab);
    await call(`document.documentElement.style.fontSize = ''; return true;`);
    need(doubled.font >= base * 1.9, `2x root text did not scale the material (${base}px -> ${doubled.font}px)`);
    need(doubled.text, 'long text overflows the material at 320 px with 2x root text');
    if (!doubled.sheet) observed.push('320px at 2x root text: ' + doubled.wide);
    // 200% browser zoom on a 320 px window is a 160 CSS-pixel layout viewport.
    await metrics({ width: 160, height: 844, deviceScaleFactor: 2, mobile: false });
    await renderSettled();
    const zoomed = await contained();
    await ctx.saveScreenshot('mechanics-renderer-reflow-200-zoom.png', tab);
    await desktop();
    need(zoomed.text, 'long text overflows the material at 200% zoom on a 320 px window');
    if (!zoomed.sheet) observed.push('200% zoom on 320px: ' + zoomed.wide);
    return { widths: [320, 390, 1440], rootTextScale: 2, zoomPercent: 200, longUnbrokenString: 512, longTextContained: true,
      sheetOverflowObservations: observed };
  });

  // The decorative constellation: scattered, clear of the content and of each other on a wide page; omitted on a narrow one.
  await scenario('mechanics_constellation_geometry', async () => {
    need(ctx.deckPath, 'base flow did not provide the deck route');
    await metrics({ width: 1600, height: 1000, deviceScaleFactor: 1, mobile: false });
    await ctx.navigate(ctx.deckPath, tab);
    await waitFor(() => has({ css: 'app-deck-constellation .rail span' }), 'the constellation did not appear on a wide deck page', 20_000);
    const geometry = await call(`const main = document.querySelector('main#main-content');
      const page = main.lastElementChild?.firstElementChild;
      const edge = main.getBoundingClientRect(); const content = page.getBoundingClientRect(); const pad = getComputedStyle(page);
      const left = content.left + parseFloat(pad.paddingLeft); const right = content.right - parseFloat(pad.paddingRight);
      const stars = [...document.querySelectorAll('app-deck-constellation .rail span')].map(e => e.getBoundingClientRect());
      const apart = (a, b) => a.right <= b.left || b.right <= a.left || a.bottom <= b.top || b.bottom <= a.top;
      return { count: stars.length,
        insideMargin: stars.every(s => s.top >= edge.top + edge.height * .05 && s.bottom <= edge.bottom - edge.height * .05
          && s.left >= edge.left + edge.width * .05 && s.right <= edge.right - edge.width * .05),
        clearOfContent: stars.every(s => s.right <= left || s.left >= right),
        overlapping: stars.some((s, i) => stars.slice(i + 1).some(o => !apart(s, o))) };`);
    need(geometry.count >= 6, `expected at least 6 stars on a wide page, got ${geometry.count}`);
    need(geometry.insideMargin, 'stars leave the 5% margin of the page');
    need(geometry.clearOfContent, 'a star overlaps the page content');
    need(!geometry.overlapping, 'two stars overlap');
    await ctx.saveScreenshot('mechanics-constellation-1600.png', tab);
    await phone();
    await waitFor(async () => !(await has({ css: 'app-deck-constellation .rail' })), 'the decoration was not omitted when the safe area cannot fit it');
    await ctx.saveScreenshot('mechanics-constellation-omitted-390.png', tab);
    await desktop();
    return { wideViewport: '1600x1000', stars: geometry.count, clearOfContentAndEachOther: true, omittedAt390: true };
  });

  // The hold-to-delete button keeps its box when the longer countdown label replaces its label (arming only; no delete).
  await scenario('mechanics_hold_to_delete_geometry', async () => {
    await desktop();
    await ctx.navigate(ctx.deckPath, tab);
    // «Удалить колоду» sits in the metadata panel behind «Изменить» on the deck hub.
    await waitFor(() => has({ css: 'nav.hub-actions button', text: 'Изменить' }), 'the deck hub did not render «Изменить»');
    await realClick({ css: 'nav.hub-actions button', text: 'Изменить' });
    const hold = { css: 'app-hold-to-delete-button button.hold-button', includes: 'Удалить колоду' };
    await waitFor(() => has(hold), 'the deck delete button did not render');
    const box = () => call(`const r = ${pick}.getBoundingClientRect(); return [r.width, r.height].map(Math.round).join('x');`, hold);
    const before = await box();
    await realClick(hold);
    await waitFor(() => call(`return ${pick}.getAttribute('aria-pressed') === 'true';`, hold), 'the button did not arm');
    need((await box()) === before, `the hold-to-delete button changed size when its countdown label appeared (${before} -> ${await box()})`);
    await keys.Tab();   // leaving the control disarms it (blur)
    return { geometryUnchangedWhenArmed: true };
  });

  // =================================================================================================================
  // 1. SELF_CHECK: prompt + reference with a material fragment
  // =================================================================================================================
  const fragmentStart = ctx.materialText.slice(0, 11);
  const authored = {};
  authored.SELF_CHECK = await scenario('mechanics_self_check_authoring', async () => {
    await openEditor('SELF_CHECK');
    await typeInto({ css: '#self-check-prompt-text-0' }, SELF_CHECK.prompt);
    await advance('reference');
    await typeInto({ css: '#self-check-reference-text-0' }, SELF_CHECK.reference);
    await realClick({ css: 'button[data-add]', within: root, slot: 1, includes: 'Фрагмент материала' });
    await chooseMaterialFragment({ css: '#self-check-reference-material-1' }, fragmentStart);
    await waitFor(async () => (await text({ css: root + ' .preview-text' })) === ctx.materialText,
      'chosen material fragment was not resolved in the editor');
    await setObjective(SELF_CHECK.objective);
    editPaths.SELF_CHECK = await saveNewExercise('SELF_CHECK');
    await reopen('SELF_CHECK');
    const restored = await call(`return {
      mechanic: document.querySelector('input[name="mechanic"][value="SELF_CHECK"]')?.checked === true,
      prompt: document.querySelector('#self-check-prompt-text-0')?.value,
      reference: document.querySelector('#self-check-reference-text-0')?.value,
      fragment: document.querySelector(args[0] + ' .preview-text')?.textContent.trim() };`, root);
    need(restored.mechanic, 'SELF_CHECK mechanic not restored after reload');
    need(restored.prompt === SELF_CHECK.prompt, 'SELF_CHECK prompt not restored after reload');
    need(restored.reference === SELF_CHECK.reference, 'SELF_CHECK reference text not restored after reload');
    need(restored.fragment === ctx.materialText, 'SELF_CHECK material fragment not restored after reload');
    await ctx.saveFullScreenshot('mechanics-edit-self-check-1440.png', tab);
    return { saved: true, reopened: true, valuesRestored: true, referenceHasMaterialFragment: true, fragmentChosenByKeyboard: true };
  });

  // =================================================================================================================
  // 2. FREE_RESPONSE (authored with an uploaded audio prompt by the base flow): reopen and assert
  // =================================================================================================================
  await scenario('mechanics_free_response_reopen', async () => {
    need(ctx.freeResponseEditPath, 'base flow did not provide the free-response edit route');
    await desktop();
    await ctx.navigate(ctx.freeResponseEditPath, tab);
    await waitFor(async () => (await exists(root, tab)) && (await exists('#exercise-preview-anchor', tab))
      && !(await bodyIncludes('Загружаем фрагменты материала', tab)), 'free-response edit page did not load');
    const snapshot = await call(`const ai = document.querySelector('#free-response-ai');
      const hint = document.querySelector('#free-response-ai-hint');
      return {
        mechanic: document.querySelector('input[name="mechanic"][value="FREE_RESPONSE"]')?.checked === true,
        prompt: document.querySelector('#free-response-prompt-text-0')?.value,
        audioTitle: document.querySelector('#free-response-prompt-title-1')?.value,
        accepted: document.querySelector('app-text-answer-editor input[type="text"]')?.value,
        aiPresent: ai instanceof HTMLInputElement, aiDisabled: ai?.disabled === true, aiChecked: ai?.checked === true,
        aiRole: ai?.getAttribute('role'), aiDescribedBy: ai?.getAttribute('aria-describedby'),
        aiReason: hint?.textContent.replace(/\\s+/g, ' ').trim() ?? null,
        previewMode: document.querySelector('app-exercise-preview-host .preview')?.getAttribute('data-mode') ?? null,
        listBelowForm: (() => { const form = document.querySelector('form.inspector'); const list = document.querySelector('#existing-exercises');
          return !!form && !!list && (form.compareDocumentPosition(list) & Node.DOCUMENT_POSITION_FOLLOWING) !== 0; })() };`);
    need(snapshot.mechanic, 'FREE_RESPONSE mechanic not restored');
    need(snapshot.previewMode === 'AUTHOR_READY', 'editing an existing exercise must preview the exercise itself, not a demo: ' + snapshot.previewMode);
    need(snapshot.listBelowForm, 'the list of existing exercises is not below the builder');
    need(snapshot.prompt === FREE_RESPONSE_PROMPT, 'FREE_RESPONSE prompt not restored');
    need(snapshot.audioTitle === 'Звуковое объяснение', 'FREE_RESPONSE audio prompt block not restored');
    need(snapshot.accepted === ctx.materialText, 'FREE_RESPONSE accepted answer not restored');
    need(snapshot.aiPresent && snapshot.aiDisabled && !snapshot.aiChecked && snapshot.aiRole === 'switch',
      'AI switch is not a disabled, unchecked switch');
    need(snapshot.aiDescribedBy === 'free-response-ai-hint' && snapshot.aiReason?.includes('Пока недоступна')
      && /Функция отключена на сервере|поставщик проверки не подключён/.test(snapshot.aiReason), 'AI switch reason is missing');
    await ctx.saveFullScreenshot('mechanics-edit-free-response-1440.png', tab);
    return { reopened: true, acceptedAnswerRestored: true, audioPromptRestored: true, aiSwitchDisabled: true,
      aiReasonPresent: true, aiReason: snapshot.aiReason };
  });

  // =================================================================================================================
  // 3. CLOZE with a repeated word and a first-letter hint on the first blank
  // =================================================================================================================
  authored.CLOZE = await scenario('mechanics_cloze_authoring', async () => {
    await openEditor('CLOZE');
    await advance('passage');   // the optional context step is skipped
    await typeInto({ css: '#cloze-text-0' }, CLOZE.text);
    const makeBlank = async index => {
      need(await call(`const area = document.querySelector('#cloze-text-' + args[0]);
        const start = area.value.indexOf('cat');
        if (start < 0) return false;
        area.focus(); area.setSelectionRange(start, start + 3);
        area.dispatchEvent(new Event('select', { bubbles: true })); return true;`, index),
      'cloze text does not contain the word to blank');
      await waitFor(() => call(`return [...document.querySelectorAll('.hint[role="status"]')]
        .some(h => h.textContent.includes('Будет пропуском: «cat»'));`), 'selected range was not announced by the cloze editor');
      await realClick({ css: root + ' [data-make-blank]' });
    };
    await makeBlank(0);
    await waitFor(async () => (await count({ css: root + ' section[data-blank]' })) === 1, 'first blank not created');
    await makeBlank(1);   // the repeated word now lives in the text after blank 1
    await waitFor(async () => (await count({ css: root + ' section[data-blank]' })) === 2, 'second blank not created');
    need(await call(`const section = document.querySelectorAll(args[0] + ' section[data-blank]')[0];
      const input = [...section.querySelectorAll('.check-line')].find(l => l.textContent.includes('Первая буква'))?.querySelector('input');
      if (!(input instanceof HTMLInputElement)) return false;
      input.scrollIntoView({ block: 'center', behavior: 'instant' }); input.focus(); return document.activeElement === input;`, root),
    'first-letter hint switch could not take focus');
    await keys.Space();
    await waitFor(() => call(`const section = document.querySelectorAll(args[0] + ' section[data-blank]')[0];
      return [...section.querySelectorAll('.check-line')].find(l => l.textContent.includes('Первая буква'))?.querySelector('input')?.checked === true;`, root),
    'first-letter hint was not enabled on blank 1');
    await setObjective(CLOZE.objective);
    editPaths.CLOZE = await saveNewExercise('CLOZE');
    await reopen('CLOZE');
    const restored = await call(`return {
      mechanic: document.querySelector('input[name="mechanic"][value="CLOZE"]')?.checked === true,
      texts: [...document.querySelectorAll(args[0] + ' textarea.code-area')].map(a => a.value),
      blanks: [...document.querySelectorAll(args[0] + ' section[data-blank]')].map(section => ({
        answers: [...section.querySelectorAll('.alias-row input[type="text"]')].map(i => i.value),
        hint: [...section.querySelectorAll('.check-line')].find(l => l.textContent.includes('Первая буква'))?.querySelector('input')?.checked === true })) };`, root);
    need(restored.mechanic, 'CLOZE mechanic not restored');
    need(JSON.stringify(restored.texts) === JSON.stringify(CLOZE.segments), 'CLOZE text segments not restored after reload');
    need(restored.blanks.length === 2 && restored.blanks.every(b => b.answers.length === 1 && b.answers[0] === 'cat'),
      'CLOZE blank answers not restored after reload');
    need(restored.blanks[0].hint === true && restored.blanks[1].hint === false, 'CLOZE hint flags not restored after reload');
    await ctx.saveFullScreenshot('mechanics-edit-cloze-1440.png', tab);
    return { saved: true, reopened: true, blanks: 2, repeatedWord: 'cat', hintOnFirstBlankOnly: true,
      selectRangeFlow: true, valuesRestored: true };
  });

  // =================================================================================================================
  // 4a. CHOICE MULTIPLE: two correct options, one option with uploaded audio, and the mode-switch rule
  // =================================================================================================================
  const fillOptions = async labels => {
    await realClick({ css: root + ' [data-add-option]' });
    await waitFor(async () => (await count({ css: root + ' section[data-option]' })) === 3, 'third option not added');
    for (const [index, label] of labels.entries()) {
      await typeInto({ css: 'textarea', within: root + ' section[data-option]', withinIndex: index }, label);
    }
  };
  const modeSwitch = index => activate({ css: 'input[type="radio"]', within: root + ' .row-stack', index });
  const markOption = index => activate({ css: 'input[type="checkbox"]', within: root + ' section[data-option]', withinIndex: index });
  authored.CHOICE_MULTIPLE = await scenario('mechanics_choice_multiple_authoring', async () => {
    await openEditor('CHOICE');
    await typeInto({ css: '#choice-prompt-text-0' }, MULTIPLE.prompt);
    await advance('options');
    await fillOptions(MULTIPLE.options);
    await modeSwitch(1);
    await waitFor(async () => (await modeRadios()) === 'false,true', 'MULTIPLE mode not selected');
    await markOption(0);
    await markOption(2);
    need(await marks() === 'true,false,true', 'two correct marks were not set');
    // Mode-switch rule: SINGLE with two marks shows the fix-required error and keeps both marks.
    await modeSwitch(0);
    await waitFor(() => has({ css: '#choice-selection-error' }), 'switching to SINGLE with two marks showed no error');
    const error = await text({ css: '#choice-selection-error' });
    need(error.includes('сейчас отмечено 2') && error.includes('Снимите лишние отметки'), 'mode-switch error text differs: ' + error);
    need(await marks() === 'true,false,true', 'switching to SINGLE dropped correct marks');
    await modeSwitch(1);
    await waitFor(async () => !(await has({ css: '#choice-selection-error' })), 'error stayed after returning to MULTIPLE');
    const slot = option(MULTIPLE.mediaOption);
    await uploadIntoSlot(slot, 'choice-option-audio.mp3', 'option audio',
      () => dropAudio(slot, 'choice-option-audio.mp3'));
    await setObjective(MULTIPLE.objective);
    editPaths.CHOICE_MULTIPLE = await saveNewExercise('CHOICE MULTIPLE');
    await reopen('CHOICE MULTIPLE');
    const restored = await call(`return {
      mechanic: document.querySelector('input[name="mechanic"][value="CHOICE"]')?.checked === true,
      prompt: document.querySelector('#choice-prompt-text-0')?.value,
      texts: [...document.querySelectorAll(args[0] + ' section[data-option]')].map(s => s.querySelector('textarea')?.value),
      blocks: [...document.querySelectorAll(args[0] + ' section[data-option]')].map(s => s.querySelectorAll('li.block').length) };`, root);
    need(restored.mechanic && await modeRadios() === 'false,true', 'CHOICE MULTIPLE mode not restored');
    need(restored.prompt === MULTIPLE.prompt, 'CHOICE MULTIPLE prompt not restored');
    need(await marks() === 'true,false,true', 'CHOICE MULTIPLE correct marks not restored');
    need(JSON.stringify(restored.texts) === JSON.stringify(MULTIPLE.options), 'CHOICE MULTIPLE options not restored');
    need(JSON.stringify(restored.blocks) === '[1,1,2]', 'CHOICE MULTIPLE option audio not restored');
    await ctx.saveFullScreenshot('mechanics-edit-choice-multiple-1440.png', tab);
    return { saved: true, reopened: true, correctMarks: 2, optionWithUploadedAudio: true,
      modeSwitchToSingleWithTwoMarksShowsError: true, marksKeptOnModeSwitch: true, marksRestored: true };
  });

  // 4b. CHOICE SINGLE
  authored.CHOICE_SINGLE = await scenario('mechanics_choice_single_authoring', async () => {
    await openEditor('CHOICE');
    await typeInto({ css: '#choice-prompt-text-0' }, SINGLE.prompt);
    await advance('options');
    await fillOptions(SINGLE.options);
    await markOption(0);
    await setObjective(SINGLE.objective);
    editPaths.CHOICE_SINGLE = await saveNewExercise('CHOICE SINGLE');
    await reopen('CHOICE SINGLE');
    need(await modeRadios() === 'true,false', 'CHOICE SINGLE mode not restored');
    need(await marks() === 'true,false,false', 'CHOICE SINGLE mark not restored');
    const restored = await call(`return { prompt: document.querySelector('#choice-prompt-text-0')?.value,
      texts: [...document.querySelectorAll(args[0] + ' section[data-option]')].map(s => s.querySelector('textarea')?.value) };`, root);
    need(restored.prompt === SINGLE.prompt && JSON.stringify(restored.texts) === JSON.stringify(SINGLE.options),
      'CHOICE SINGLE values not restored');
    await ctx.saveFullScreenshot('mechanics-edit-choice-single-1440.png', tab);
    return { saved: true, reopened: true, marksRestored: true };
  });

  // =================================================================================================================
  // 5 + 6. MATCH: text<->text, text<->uploaded audio, and a recording from the synthetic microphone
  // =================================================================================================================
  let recording = { state: 'not_run', syntheticMicrophone: true, realDeviceMicrophone: false,
    reason: 'MATCH authoring did not reach the recording step' };
  authored.MATCH = await scenario('mechanics_match_authoring', async () => {
    await openEditor('MATCH');
    await advance('pairs');   // the general instruction is optional
    await realClick({ css: root + ' [data-add-pair]' });
    await waitFor(async () => (await count({ css: root + ' section[data-pair]' })) === 3, 'third pair not added');
    for (const [index, entry] of MATCH.pairs.entries()) {
      await typeInto({ ...pair(index, 0), css: 'textarea' }, entry.left);
      if (entry.right !== null) await typeInto({ ...pair(index, 1), css: 'textarea' }, entry.right);
    }
    // Pair 2, right side: an uploaded audio file is the whole item, so its empty text block is removed afterwards.
    const audioSide = pair(1, 1);
    await uploadIntoSlot(audioSide, 'match-right-audio.mp3', 'match audio', () => dropAudio(audioSide, 'match-right-audio.mp3'));
    need(await call(`const b = find({ ...args[0], css: '[data-block="0"] button[aria-label^="Удалить блок"]' })[0];
      if (!(b instanceof HTMLButtonElement)) return false; b.click(); return true;`, audioSide),
    'empty text block of the audio-only side could not be removed');
    await waitFor(async () => JSON.stringify((await slotBlocks(audioSide)).map(b => b.kind)) === '["Аудио"]', 'audio-only side not formed');

    // Pair 3, left side: its text plus a recording from the SYNTHETIC microphone (Chrome fake device flags).
    const recordSide = pair(2, 0);
    try {
      // «Записать аудио» opens the picker in recording mode and starts the recorder on that click.
      await realClick({ ...recordSide, css: 'button[data-record]', text: 'Записать аудио' });
      await waitFor(() => slotButton(recordSide, 'Остановить запись'), 'recording did not start with the fake microphone', 15_000);
      await new Promise(resolve => setTimeout(resolve, 1800));
      await keys.Enter();
      await waitFor(() => slotButton(recordSide, 'Загрузить запись'), 'recording preview did not appear', 15_000);
      await keys.Enter();
      await waitFor(async () => (await queuedRow(recordSide, 'Запись-')) !== null, 'recording did not enter the upload queue');
      await waitFor(async () => {
        const row = await queuedRow(recordSide, 'Запись-');
        need(row.error === null, 'recording upload failed in the UI: ' + row.error);
        return row.ready;
      }, 'recording did not become READY', 45_000);
      need(await call(`const row = find({ ...args[0], css: '.media-row', includes: 'Запись-' })[0];
        const choose = [...row.querySelectorAll('button')].find(b => b.textContent.trim() === 'Добавить в упражнение');
        if (!(choose instanceof HTMLButtonElement)) return false; choose.click(); return true;`, recordSide),
      'READY recording could not be attached');
      await waitFor(async () => (await slotBlocks(recordSide)).some(b => b.kind === 'Аудио'), 'recording block not added');
      recording = { state: 'passed', syntheticMicrophone: true, realDeviceMicrophone: false, reachedReady: true,
        recordStopUseFlow: true, usedInMatchSlot: true };
    } catch (error) {
      recording = { state: 'failed', syntheticMicrophone: true, realDeviceMicrophone: false,
        reason: error instanceof SafeFailure ? error.message : 'driver_failure', screenshot: 'failure-mechanics_recording_ui.png' };
      await ctx.saveScreenshot(recording.screenshot, tab).catch(() => {});
      failures.push('mechanics_recording_ui_synthetic_microphone');
      throw new UiFailure('recording step failed; MATCH was not saved: ' + recording.reason);
    }
    await setObjective(MATCH.objective);
    editPaths.MATCH = await saveNewExercise('MATCH');
    await reopen('MATCH');
    const restored = await call(`return {
      mechanic: document.querySelector('input[name="mechanic"][value="MATCH"]')?.checked === true,
      pairs: [...document.querySelectorAll(args[0] + ' section[data-pair]')].map(section => [...section.querySelectorAll('app-exercise-slot-editor')]
        .map(slot => [...slot.querySelectorAll('li.block')].map(block => ({
          kind: block.querySelector('.block-head strong')?.textContent.replace(/^\\d+\\.\\s*/, '').trim(),
          value: block.querySelector('textarea, input[type="text"]')?.value ?? null, file: block.textContent.includes('Файл выбран') })))) };`, root);
    need(restored.mechanic && restored.pairs.length === 3, 'MATCH pairs not restored');
    const [first, second, third] = restored.pairs;
    need(first[0][0]?.value === 'cat' && first[1][0]?.value === 'кошка', 'MATCH text<->text pair not restored');
    need(second[0][0]?.value === 'dog' && second[1].length === 1 && second[1][0].kind === 'Аудио' && second[1][0].file,
      'MATCH text<->audio pair not restored');
    need(third[0].length === 2 && third[0][0].value === 'bird' && third[0][1].kind === 'Аудио' && third[0][1].file
      && third[1][0]?.value === 'птица', 'MATCH recording pair not restored');
    await ctx.saveFullScreenshot('mechanics-edit-match-1440.png', tab);
    return { saved: true, reopened: true, pairs: 3, textToText: true, textToUploadedAudio: true,
      uploadedAudioThroughSlotMediaPicker: true, recordingPairRestored: true, valuesRestored: true };
  });
  record('mechanics_recording_ui_synthetic_microphone', recording);

  // =================================================================================================================
  // 7. ORDER and CATEGORIZE: board helpers shared by the author preview and Study (real keyboard, no drag)
  // =================================================================================================================
  const itemSection = (index, section = 'section[data-item]') => ({ within: root + ' ' + section, withinIndex: index });
  /** Opens the option list of an app-mnema-select with the keyboard and picks `optionIndex` (0 is the placeholder). */
  async function chooseOption(spec, optionIndex, label) {
    await focusEl(spec);
    await keys.ArrowDown();
    await waitFor(() => has({ css: '[role="option"]' }), label + ': the option list did not open');
    for (let step = 0; step < optionIndex; step++) await keys.ArrowDown();
    await keys.Enter();
  }
  const orderNames = scope => call(`return [...document.querySelectorAll(args[0] + ' li.order-item')].map(row =>
    (row.querySelector('[data-move="up"]')?.getAttribute('aria-label') ?? '').replace(/^[^:]+: /, ''));`, scope);
  /** Rearranges an ORDER board with the focused up arrows only (keyboard Space); identical names are interchangeable. */
  async function arrangeOrder(scope, desired) {
    for (let target = 0; target < desired.length; target++) {
      let current = await orderNames(scope);
      let from = current.findIndex((name, index) => index >= target && name === desired[target]);
      need(from >= 0, 'the order board does not offer «' + desired[target] + '»; it shows: ' + current.join(' | '));
      while (from > target) {
        const before = current.join('|');
        await activate({ css: '[data-move="up"]', within: scope + ' li.order-item', withinIndex: from });
        await waitFor(async () => (await orderNames(scope)).join('|') !== before, 'moving «' + desired[target] + '» up had no effect');
        current = await orderNames(scope);
        from -= 1;
      }
    }
  }
  const poolNames = scope => call(`return [...document.querySelectorAll(args[0] + ' [data-pool] [data-select]')]
    .map(button => button.getAttribute('aria-label').replace(/^Выбрать: /, ''));`, scope);
  /** Assigns every unassigned item with select-then-group keyboard presses; `groupOf(name)` is the target group label. */
  async function assignAll(scope, groupOf) {
    for (let guard = 0; guard < 20; guard++) {
      const pool = await poolNames(scope);
      if (pool.length === 0) return;
      const group = groupOf(pool[0]);
      await activate({ css: '[data-pool] [data-select]', within: scope });
      await activate({ css: 'button[data-place]', within: scope + ' section[data-category]', labelIncludes: '«' + group + '»' });
      await waitFor(async () => (await poolNames(scope)).length === pool.length - 1, '«' + pool[0] + '» was not placed into «' + group + '»');
    }
    throw new UiFailure('the unassigned list never emptied');
  }

  // =================================================================================================================
  // 8. ORDER: word helper, code, image frame, keyboard-only preview, save, reopen
  // =================================================================================================================
  authored.ORDER = await scenario('mechanics_order_authoring', async () => {
    await openEditor('ORDER');
    // The tile opens a playable demo (local audio and images); it is solved with the arrow buttons only.
    await waitFor(() => has({ css: '#exercise-preview-anchor [data-mode="DEMO"]' }), 'the ORDER demo did not open');
    const demoSequence = ['Схема звуковой волны: колебания редкие', 'Низкий звук', 'Высокий звук', 'Схема звуковой волны: колебания очень частые'];
    await arrangeOrder('#exercise-preview-anchor', demoSequence);
    await submitKey({ css: '#exercise-preview-anchor button[data-submit]' });
    await waitFor(() => has({ css: '#preview-result-title' }), 'the ORDER demo verdict did not arrive', 20_000);
    need((await text({ css: '#preview-result-title' })) === 'Верно' && (await text({ css: '#exercise-preview-anchor .result' })).includes('Пример завершён'),
      'the ORDER demo was not solved by its own key');
    await realClick({ css: '#exercise-preview-anchor button[data-restart]' });
    await realClick({ css: 'button[data-add]', within: root, slot: 0, includes: '+ Текст' });
    await typeInto({ css: '#order-prompt-text-0' }, ORDER.instruction);
    await advance('items');
    const rules = (await text({ css: '#order-rules' })).replace(/\s+/g, ' ');
    need(rules.includes('Каждый сегмент используется ровно один раз') && rules.includes('другие порядки, даже осмысленные, Mnema не определяет'),
      'the ORDER hint does not explain the single use of every segment and the undetected alternative orders');
    // «Разбить на слова»: punctuation stays with its word and the repeated word stays two separate items.
    await realClick({ css: '#step-items details > summary' });
    await waitFor(() => has({ css: '#order-source' }), 'the splitting helper did not open');
    await typeInto({ css: '#order-source' }, ORDER.sentence);
    await realClick({ css: '[data-split-words]' });
    await waitFor(async () => (await count({ css: root + ' section[data-item]' })) === ORDER.words.length, 'the word helper did not create the items');
    const words = await call(`return [...document.querySelectorAll(args[0] + ' section[data-item]')].map(section => section.querySelector('textarea')?.value);`, root);
    need(JSON.stringify(words) === JSON.stringify(ORDER.words), 'the word helper produced «' + words.join(' | ') + '»');
    need(await has({ css: root + ' [data-duplicate]' }), 'identical items are not marked as interchangeable');
    // A code block and an image frame are ordinary items.
    for (let index = 0; index < 2; index++) await realClick({ css: root + ' [data-add-item]' });
    await waitFor(async () => (await count({ css: root + ' section[data-item]' })) === ORDER.words.length + 2, 'two more items were not added');
    await typeInto({ ...itemSection(ORDER.words.length), css: 'textarea' }, ORDER.code);
    const frame = { ...itemSection(ORDER.words.length + 1), slot: 0 };
    await uploadIntoSlot(frame, 'order-frame.png', 'order image', () => dropImage(frame, 'order-frame.png'), 'image');
    await dropEmptyTextBlock(frame, 'order image item');
    await waitFor(async () => JSON.stringify((await slotBlocks(frame)).map(block => block.kind)) === '["Изображение"]', 'image-only item not formed');
    await typeInto({ ...frame, css: 'input[type="text"]' }, ORDER.imageAlt);
    await advance('finish');
    // The finished exercise is playable in the preview; the right order is built with the arrow buttons only.
    await waitFor(() => has({ css: '#exercise-preview-anchor [data-mode="AUTHOR_READY"]' }), 'the finished ORDER did not become playable');
    const previewBefore = wire.previews;
    const desired = [...ORDER.words, ORDER.codeName, ORDER.imageAlt];
    need(JSON.stringify(await orderNames('#exercise-preview-anchor')) !== JSON.stringify(desired), 'the preview opened in the authored order');
    await arrangeOrder('#exercise-preview-anchor', desired);
    await submitKey({ css: '#exercise-preview-anchor button[data-submit]' });
    await waitFor(() => has({ css: '#preview-result-title' }), 'the ORDER preview verdict did not arrive from the preview endpoint', 20_000);
    need((await text({ css: '#preview-result-title' })) === 'Верно', 'the authored order was not accepted by the preview evaluator');
    need(wire.previews === previewBefore + 1, 'the ORDER preview must use exactly one preview request');
    await realClick({ css: '#exercise-preview-anchor button[data-restart]' });
    await setObjective(ORDER.objective);
    editPaths.ORDER = await saveNewExercise('ORDER');
    await reopen('ORDER');
    const restored = await call(`return {
      mechanic: document.querySelector('input[name="mechanic"][value="ORDER"]')?.checked === true,
      items: [...document.querySelectorAll(args[0] + ' section[data-item]')].map(section => ({
        text: section.querySelector('textarea')?.value ?? null, alt: section.querySelector('input[type="text"]')?.value ?? null,
        file: section.textContent.includes('Файл выбран') })),
      mode: document.querySelector('app-exercise-preview-host .preview')?.getAttribute('data-mode') ?? null };`, root);
    need(restored.mechanic && restored.items.length === ORDER.words.length + 2, 'ORDER items not restored');
    need(JSON.stringify(restored.items.slice(0, ORDER.words.length).map(item => item.text)) === JSON.stringify(ORDER.words), 'ORDER words not restored in order');
    need(restored.items[ORDER.words.length].text === ORDER.code, 'ORDER code block not restored verbatim');
    need(restored.items.at(-1).alt === ORDER.imageAlt && restored.items.at(-1).file, 'ORDER image frame not restored');
    need(restored.mode === 'AUTHOR_READY', 'a reopened ORDER must preview itself, not a demo');
    await ctx.saveFullScreenshot('mechanics-edit-order-1440.png', tab);
    return { saved: true, reopened: true, wordHelperKeptPunctuation: true, repeatedWordsSeparateItems: true, codeKeptVerbatim: true,
      imageFrame: true, previewPlayedWithKeyboard: true, previewEndpointOnly: true, valuesRestored: true };
  });

  // =================================================================================================================
  // 9. CATEGORIZE: groups, items with group selects, group deletion with reassignment, keyboard preview
  // =================================================================================================================
  authored.CATEGORIZE = await scenario('mechanics_categorize_authoring', async () => {
    await openEditor('CATEGORIZE');
    // The tile opens a playable demo; putting everything into one group shows the per-item verdict of the preview endpoint.
    await waitFor(() => has({ css: '#exercise-preview-anchor [data-mode="DEMO"]' }), 'the CATEGORIZE demo did not open');
    await assignAll('#exercise-preview-anchor', () => 'Низкий звук');
    await submitKey({ css: '#exercise-preview-anchor button[data-submit]' });
    await waitFor(() => has({ css: '#preview-result-title' }), 'the CATEGORIZE demo verdict did not arrive', 20_000);
    need((await text({ css: '#preview-result-title' })) === 'Частично' && (await count({ css: '#exercise-preview-anchor .pair-feedback li' })) === 6,
      'the CATEGORIZE demo did not return a per-item verdict');
    await realClick({ css: '#exercise-preview-anchor button[data-restart]' });
    await realClick({ css: 'button[data-add]', within: root, slot: 0, includes: '+ Текст' });
    await typeInto({ css: '#categorize-prompt-text-0' }, CATEGORIZE.instruction);
    await advance('groups');
    for (let index = 0; index < 2; index++) await realClick({ css: root + ' [data-add-category]' });
    await waitFor(async () => (await count({ css: root + ' app-category-groups-editor section[data-category]' })) === 4, 'groups were not added');
    for (const [index, label] of [...CATEGORIZE.groups, CATEGORIZE.spare].entries()) {
      await typeInto({ ...itemSection(index, 'app-category-groups-editor section[data-category]'), css: 'input[type="text"]' }, label);
    }
    await advance('items');
    for (let index = 0; index < 2; index++) await realClick({ css: root + ' [data-add-item]' });
    await waitFor(async () => (await count({ css: root + ' section[data-item]' })) === 4, 'items were not added');
    for (const [index, entry] of CATEGORIZE.texts.entries()) await typeInto({ ...itemSection(index), css: 'textarea' }, entry.text);
    const audio = { ...itemSection(3), slot: 0 };
    await uploadIntoSlot(audio, 'categorize-audio.mp3', 'categorize audio', () => dropAudio(audio, 'categorize-audio.mp3'));
    await dropEmptyTextBlock(audio, 'categorize audio item');
    await waitFor(async () => JSON.stringify((await slotBlocks(audio)).map(block => block.kind)) === '["Аудио"]', 'audio-only item not formed');
    await typeInto({ ...audio, css: 'input[type="text"]' }, 'Запись: читать');
    // Every item gets its group with the keyboard; «река» first goes to the temporary group.
    const option = group => [...CATEGORIZE.groups, CATEGORIZE.spare].indexOf(group) + 1;
    const groupSelect = index => ({ ...itemSection(index), css: '[role="combobox"]' });
    for (const [index, entry] of CATEGORIZE.texts.entries()) {
      await chooseOption(groupSelect(index), option(index === 2 ? CATEGORIZE.spare : entry.group), 'group of item ' + (index + 1));
    }
    await chooseOption(groupSelect(3), option(CATEGORIZE.audioGroup), 'group of the audio item');
    await waitFor(async () => (await text(groupSelect(2))) === CATEGORIZE.spare, 'the temporary group was not chosen');
    // Removing the group that still holds «река» must ask first, then move the item: no dangling id, no lost item.
    await realClick({ css: 'button[data-remove]', ...itemSection(3, 'app-category-groups-editor section[data-category]') });
    await waitFor(() => has({ css: root + ' .removal' }), 'removing a group with items did not ask what to do with them');
    need((await text({ css: root + ' .removal' })).includes('элементов: 1'), 'the removal question does not count the items');
    need(await count({ css: root + ' app-category-groups-editor section[data-category]' }) === 4, 'the group was removed before the author decided');
    await chooseOption({ css: '[role="combobox"]', within: root + ' .removal' }, 1, 'reassign target');
    await realClick({ css: root + ' [data-reassign]' });
    await waitFor(async () => (await count({ css: root + ' app-category-groups-editor section[data-category]' })) === 3, 'the group was not removed after the reassignment');
    await waitFor(async () => (await text(groupSelect(2))) === CATEGORIZE.groups[0], 'the item of the removed group did not move to the chosen group');
    need((await text({ css: root + ' [data-note]' })).includes('перенесены'), 'the removal outcome was not announced');
    await advance('finish');
    // Keyboard preview: assign everything correctly and submit.
    await waitFor(() => has({ css: '#exercise-preview-anchor [data-mode="AUTHOR_READY"]' }), 'the finished CATEGORIZE did not become playable');
    const previewBefore = wire.previews;
    const target = name => name.startsWith('Аудио') ? CATEGORIZE.audioGroup : CATEGORIZE.texts.find(entry => entry.text === name).group;
    await assignAll('#exercise-preview-anchor', target);
    await submitKey({ css: '#exercise-preview-anchor button[data-submit]' });
    await waitFor(() => has({ css: '#preview-result-title' }), 'the CATEGORIZE preview verdict did not arrive', 20_000);
    need((await text({ css: '#preview-result-title' })) === 'Верно', 'the authored assignments were not accepted by the preview evaluator');
    need(wire.previews === previewBefore + 1, 'the CATEGORIZE preview must use exactly one preview request');
    await realClick({ css: '#exercise-preview-anchor button[data-restart]' });
    await setObjective(CATEGORIZE.objective);
    editPaths.CATEGORIZE = await saveNewExercise('CATEGORIZE');
    await reopen('CATEGORIZE');
    const restored = await call(`return {
      mechanic: document.querySelector('input[name="mechanic"][value="CATEGORIZE"]')?.checked === true,
      groups: [...document.querySelectorAll(args[0] + ' app-category-groups-editor section[data-category] input[type="text"]')].map(input => input.value),
      items: [...document.querySelectorAll(args[0] + ' section[data-item]')].map(section => ({
        group: section.querySelector('[role="combobox"]')?.textContent.trim() ?? null,
        text: section.querySelector('textarea')?.value ?? null, file: section.textContent.includes('Файл выбран') })),
      mode: document.querySelector('app-exercise-preview-host .preview')?.getAttribute('data-mode') ?? null };`, root);
    need(restored.mechanic && JSON.stringify(restored.groups) === JSON.stringify(CATEGORIZE.groups),
      'CATEGORIZE groups not restored (the empty distractor group must survive): ' + restored.groups.join(' | '));
    need(restored.items.length === 4, 'CATEGORIZE items not restored');
    for (const [index, entry] of CATEGORIZE.texts.entries()) {
      need(restored.items[index].text === entry.text && restored.items[index].group === entry.group, 'CATEGORIZE item ' + (index + 1) + ' not restored with its group');
    }
    need(restored.items[3].file && restored.items[3].group === CATEGORIZE.audioGroup, 'CATEGORIZE audio item not restored with its group');
    need(restored.mode === 'AUTHOR_READY', 'a reopened CATEGORIZE must preview itself, not a demo');
    await ctx.saveFullScreenshot('mechanics-edit-categorize-1440.png', tab);
    return { saved: true, reopened: true, groupDeletionAskedAndReassigned: true, emptyDistractorGroupKept: true, uploadedAudioItem: true,
      previewPlayedWithKeyboard: true, previewEndpointOnly: true, valuesRestored: true };
  });

  // =================================================================================================================
  // Study: standard sessions (5 new objectives each) present every authored mechanic; dispatch by what is shown.
  // =================================================================================================================
  const outcomeAfter = async (before, label) => {
    await waitFor(() => Promise.resolve(wire.attempts.length > before), label + ': attempt response not observed');
    return wire.attempts[before];
  };
  const feedbackTitle = () => text({ css: '#feedback-title' });
  const afterSubmit = async (label, expected) => {
    await waitFor(() => has({ css: '#feedback-title' }), label + ': feedback did not arrive from the real API', 25_000);
    need(await call("return document.activeElement?.id === 'feedback-title';"), label + ': feedback title did not receive focus');
    const title = await feedbackTitle();
    need(title === expected, `${label}: feedback title «${title}» differs from «${expected}»`);
  };
  const feedbackShot = name => ctx.saveScreenshot(name, tab);
  const proceed = async () => {
    await submitKey({ css: '.feedback-card button.primary', text: 'Продолжить' });
  };
  const answeringShot = async (name) => { await desktop(); await ctx.saveScreenshot(name, tab); };
  const optionLabel = label => call(`return [...document.querySelectorAll('.study-card .choice-option')]
    .findIndex(li => li.querySelector('label')?.textContent.trim().startsWith(args[0]));`, label);

  const handlers = {
    async SELF_CHECK() {
      need(await bodyIncludes(SELF_CHECK.prompt, tab), 'SELF_CHECK prompt not shown');
      need(!(await bodyIncludes(SELF_CHECK.reference, tab)), 'SELF_CHECK reference leaked before reveal');
      need(await call("return document.activeElement?.textContent.includes('Показать ответ') === true;"),
        'SELF_CHECK reveal control is not focused on arrival');
      const before = wire.attempts.length;
      await keys.Space();
      await waitFor(() => has({ css: '.study-card .reference' }), 'SELF_CHECK reference was not revealed');
      const reference = await text({ css: '.study-card .reference' });
      need(reference.includes(SELF_CHECK.reference) && reference.includes(ctx.materialText),
        'SELF_CHECK revealed reference lacks the text or the material fragment');
      await answeringShot('mechanics-study-self-check-1440.png');
      need(await call("return document.activeElement?.hasAttribute('data-first-rating') === true;"), 'first rating not focused after reveal');
      for (let step = 0; step < 3; step++) await keys.Tab();
      need(await call("return document.activeElement?.textContent.trim() === 'Вспомнил полностью';"), 'Tab order does not reach the FULL rating');
      await keys.Enter();
      await afterSubmit('SELF_CHECK', 'Верно');
      need((await text({ css: '.reference-line' })).includes('Вспомнил полностью'), 'SELF_CHECK feedback lacks the chosen rating');
      await feedbackShot('mechanics-study-self-check-feedback-1440.png');
      const outcome = await outcomeAfter(before, 'SELF_CHECK');
      return { referenceRevealedOnDemand: true, referenceHasMaterialFragment: true, keyboardOnly: true, rated: 'FULL',
        result: outcome.result, evidenceClass: outcome.evidenceClass };
    },
    async CLOZE() {
      const inputs = { css: '.cloze-blank input[type="text"]', within: '.study-card' };
      need((await count(inputs)) === 2, 'CLOZE did not present two blanks');
      need((await count({ css: '.cloze-hint', within: '.study-card' })) === 1, 'CLOZE should offer the hint on exactly one blank');
      need(!(await has({ css: '.cloze-letter', within: '.study-card' })), 'CLOZE showed a letter before it was requested');
      await answeringShot('mechanics-study-cloze-1440.png');
      const hintsBefore = wire.hints;
      await activate({ css: '.cloze-hint', within: '.study-card' });
      await waitFor(() => has({ css: '.cloze-letter strong', within: '.study-card' }), 'first-letter hint did not appear');
      const letter = await text({ css: '.cloze-letter strong', within: '.study-card' });
      need(letter === 'c' && wire.hints === hintsBefore + 1, `hint should reveal exactly the letter "c" through one request, got «${letter}»`);
      need(!(await has({ css: '.cloze-hint', within: '.study-card' })), 'hint button stayed after use');
      await typeInto({ ...inputs, index: 0 }, 'cat');
      await focusEl({ ...inputs, index: 0 });
      await keys.Tab();
      need(await call(`return document.activeElement === document.querySelectorAll('.study-card .cloze-blank input[type="text"]')[1];`),
        'Tab from blank 1 did not reach blank 2');
      await tab.call('Input.insertText', { text: 'dog' });
      await phone();
      const wide = await overflowing();
      await ctx.saveScreenshot('mechanics-study-cloze-390.png', tab);
      if (wide) finding('cloze_study_390_overflow', 'CLOZE Study answering view overflows horizontally at 390 px');
      await desktop();
      const before = wire.attempts.length;
      await focusEl({ ...inputs, index: 1 });
      await keys.Enter();
      await afterSubmit('CLOZE', 'Частично');
      const verdicts = await call(`return [...document.querySelectorAll('.feedback-card .cloze-verdict')].map(v => v.textContent.replace(/\\s+/g, ' ').trim());`);
      need(verdicts.length === 2, 'CLOZE feedback is not per blank');
      need(verdicts[0].startsWith('Верно') && verdicts[0].includes('с подсказкой'), 'blank 1 feedback should be correct and hinted: ' + verdicts[0]);
      need(verdicts[1].startsWith('Неверно') && verdicts[1].includes('ответ: cat') && !verdicts[1].includes('с подсказкой'),
        'blank 2 feedback should be wrong with the reference: ' + verdicts[1]);
      await feedbackShot('mechanics-study-cloze-feedback-1440.png');
      const outcome = await outcomeAfter(before, 'CLOZE');
      need(outcome.evidenceClass === 'MEDIUM', `hinted blank must cap evidence at MEDIUM, got ${outcome.evidenceClass}`);
      return { hintRequestedByKeyboard: true, hintLetterShown: letter, hintRequests: 1, repeatedWordBlanksIndependent: true,
        perBlankFeedback: verdicts, result: outcome.result, evidenceClass: outcome.evidenceClass, screenshot390: true,
        noHorizontalOverflow390: !wide };
    },
    async CHOICE_MULTIPLE() {
      const media = MULTIPLE.mediaOption;
      need((await count({ css: '.choice-option', within: '.study-card' })) === 3, 'CHOICE did not present three options');
      const lion = await optionLabel('Лион');
      need(lion >= 0, 'option with audio not presented');
      await waitFor(() => call(`const a = document.querySelectorAll('.study-card .choice-option')[args[0]]?.querySelector('audio');
        return a instanceof HTMLAudioElement && a.readyState >= 1;`, lion), 'option audio did not load in the shared player', 30_000);
      await answeringShot('mechanics-study-choice-multiple-1440.png');
      const selected = () => call("return [...document.querySelectorAll('.study-card .choice-option input')].filter(i => i.checked).length;");
      const player = { css: '.mnema-player-action', within: '.study-card .choice-option', withinIndex: lion };
      await realClick({ ...player, index: 0 });
      const started = await waitFor(() => has({ ...player, label: 'Пауза' }), 'play control inside the option did not react', 6_000)
        .then(() => true, () => false);
      need((await selected()) === 0 && (await count({ css: '.choice-option.is-selected', within: '.study-card' })) === 0,
        'clicking the option audio player toggled a selection');
      await realClick({ ...player, index: 1 });   // mute control
      need((await selected()) === 0, 'clicking the option audio mute control toggled a selection');
      if (started) await realClick({ ...player, index: 0 });
      for (const label of MULTIPLE.correct) {
        await activate({ css: 'input[type="checkbox"]', within: '.study-card .choice-option', withinIndex: await optionLabel(label) });
      }
      need((await selected()) === 2, 'keyboard selection of the two correct options failed');
      const before = wire.attempts.length;
      await submitKey({ css: 'button[data-submit]', within: '.study-card' });
      await afterSubmit('CHOICE MULTIPLE', 'Верно');
      need((await count({ css: '.feedback-card .choice-option.is-correct' })) === 2, 'feedback does not mark both correct options');
      await feedbackShot('mechanics-study-choice-multiple-feedback-1440.png');
      const outcome = await outcomeAfter(before, 'CHOICE MULTIPLE');
      return { checkboxSemantics: true, mediaPlayerClickDoesNotSelect: true, playbackStarted: started, mediaOption: media + 1,
        selectedByKeyboard: 2, result: outcome.result, evidenceClass: outcome.evidenceClass };
    },
    async CHOICE_SINGLE() {
      need((await count({ css: '.choice-option input[type="radio"]', within: '.study-card' })) === 3, 'CHOICE SINGLE is not rendered as radios');
      await answeringShot('mechanics-study-choice-single-1440.png');
      await activate({ css: 'input[type="radio"]', within: '.study-card .choice-option', withinIndex: await optionLabel(SINGLE.correct) });
      need((await call("return [...document.querySelectorAll('.study-card .choice-option input')].filter(i => i.checked).length;")) === 1,
        'radio selection failed');
      const before = wire.attempts.length;
      await submitKey({ css: 'button[data-submit]', within: '.study-card' });
      await afterSubmit('CHOICE SINGLE', 'Верно');
      await feedbackShot('mechanics-study-choice-single-feedback-1440.png');
      const outcome = await outcomeAfter(before, 'CHOICE SINGLE');
      return { radioSemantics: true, selectedByKeyboard: 1, result: outcome.result, evidenceClass: outcome.evidenceClass };
    },
    async MATCH() {
      const leftButton = name => ({ css: 'button[data-side="left"]', within: '.study-card', label: 'Выбрать: ' + name });
      const rightText = name => ({ css: 'button[data-side="right"]', within: '.study-card', label: 'Выбрать: ' + name });
      const rightAudio = { css: 'button[data-side="right"]', within: '.study-card', labelIncludes: 'Выбрать: Аудио' };
      const matched = () => count({ css: '.match-item.is-matched', within: '.study-card' });
      await waitFor(() => call(`const a = document.querySelector('.study-card .match-board audio');
        return a instanceof HTMLAudioElement && a.readyState >= 1;`), 'match audio did not load in the shared player', 30_000);
      await answeringShot('mechanics-study-match-1440.png');
      await phone();
      const wide = await overflowing();
      await ctx.saveScreenshot('mechanics-study-match-390.png', tab);
      if (wide) finding('match_study_390_overflow', 'MATCH Study answering view overflows horizontally at 390 px');
      await desktop();
      // Playing audio must not create or request a pair, even while a left item is selected.
      const checksBefore = wire.pairChecks;
      await activate(leftButton('dog'));
      need(await call(`return document.querySelector('.study-card button[data-side="left"][aria-pressed="true"]')?.getAttribute('aria-label') === 'Выбрать: dog';`),
        'left item was not selected');
      const players = await count({ css: '.mnema-player-controls > button:first-child', within: '.study-card .match-board' });
      need(players >= 2, 'match audio players are not rendered');
      for (let index = 0; index < players; index++) {
        await realClick({ css: '.mnema-player-controls > button:first-child', within: '.study-card .match-board', index });
      }
      await new Promise(resolve => setTimeout(resolve, 600));
      need(wire.pairChecks === checksBefore && (await matched()) === 0
        && await call(`return document.querySelector('.study-card button[data-side="left"][aria-pressed="true"]')?.getAttribute('aria-label') === 'Выбрать: dog';`),
      'playing match audio created or requested a pair');
      await activate(leftButton('dog'));   // deselect again
      // One deliberate wrong pair, then the correct ones.
      await activate(leftButton('cat'));
      await activate(rightText('птица'));
      await waitFor(() => has({ css: '.match-item.is-wrong', within: '.study-card' }), 'wrong pair was not marked');
      need((await text({ css: '.match-status', within: '.study-card' })).includes('Эта пара не подходит'), 'wrong pair status text missing');
      need((await matched()) === 0, 'wrong pair was accepted');
      await activate(rightText('кошка'));
      await waitFor(async () => (await matched()) === 2, 'correct pair cat/кошка was not accepted');
      await activate(leftButton('dog'));
      await activate(rightAudio);
      await waitFor(async () => (await matched()) === 4, 'correct pair dog/audio was not accepted');
      await activate(leftButton('bird'));
      await activate(rightText('птица'));
      await waitFor(async () => (await matched()) === 6, 'correct pair bird/птица was not accepted');
      need(wire.pairChecks === checksBefore + 4, `expected 4 pair checks (1 wrong, 3 right), saw ${wire.pairChecks - checksBefore}`);
      const before = wire.attempts.length;
      await waitFor(() => call("return document.activeElement?.hasAttribute('data-submit') === true;"), 'focus did not move to the submit action when complete');
      await keys.Enter();
      await afterSubmit('MATCH', 'Частично');
      need((await text({ css: '.feedback-card .notice' }))?.includes('Все пары найдены. Поскольку были ошибки при подборе'),
        'PAIR_RETRY notice missing');
      need((await count({ css: '.pair-feedback li', within: '.feedback-card' })) === 3, 'per-pair feedback missing');
      await feedbackShot('mechanics-study-match-feedback-1440.png');
      const outcome = await outcomeAfter(before, 'MATCH');
      need(outcome.appliedRules.includes('PAIR_RETRY'), 'attempt does not carry PAIR_RETRY');
      return { wrongPairFeedback: true, audioPlayDoesNotPair: true, pairChecks: 4, retryNotice: true, result: outcome.result,
        appliedRules: outcome.appliedRules, evidenceClass: outcome.evidenceClass, screenshot390: true, noHorizontalOverflow390: !wide };
    },
    async ORDER() {
      const scope = '.study-card';
      const desired = [...ORDER.words, ORDER.codeName, ORDER.imageAlt];
      await waitFor(async () => (await count({ css: 'li.order-item', within: scope })) === desired.length, 'ORDER did not present every item');
      await waitFor(() => call(`const image = document.querySelector('.study-card .order-item img');
        return image instanceof HTMLImageElement && image.complete && image.naturalWidth > 0;`), 'the image frame did not load in the shared renderer', 30_000);
      const issued = await orderNames(scope);
      need(JSON.stringify(issued) !== JSON.stringify(desired), 'ORDER was issued in the authored order');
      need([...issued].sort().join('|') === [...desired].sort().join('|'), 'ORDER did not issue exactly the authored items: ' + issued.join(' | '));
      await answeringShot('mechanics-study-order-1440.png');
      await phone();
      const wide = await overflowing();
      await ctx.saveScreenshot('mechanics-study-order-390.png', tab);
      if (wide) finding('order_study_390_overflow', 'ORDER Study answering view overflows horizontally at 390 px');
      await desktop();
      // The «На позицию N» select moves an item too; one real keyboard use of it, then the arrows finish the job.
      const firstSelect = { css: '[role="combobox"]', within: scope + ' li.order-item', withinIndex: 0 };
      const firstName = issued[0];
      await chooseOption(firstSelect, 2, 'position select');   // the list opens on the current position (1): two presses reach «На позицию 3»
      await waitFor(async () => (await orderNames(scope))[2] === firstName, 'the position select did not move the first item to position 3');
      need((await text({ css: '.order-status', within: scope })).includes('перемещён на позицию 3'), 'the move was not announced in the live region');
      await arrangeOrder(scope, desired);
      const before = wire.attempts.length;
      await submitKey({ css: 'button[data-submit]', within: scope });
      await afterSubmit('ORDER', 'Верно');
      need((await count({ css: '.feedback-card .positions li' })) === desired.length && (await count({ css: '.feedback-card .positions li.is-wrong' })) === 0,
        'ORDER feedback does not mark every position as right');
      need((await count({ css: '.feedback-card .correct-sequence li' })) === desired.length, 'the correct sequence is not shown');
      await feedbackShot('mechanics-study-order-feedback-1440.png');
      const outcome = await outcomeAfter(before, 'ORDER');
      need(outcome.result === 'CORRECT' && outcome.evidenceClass === 'MEDIUM', `ORDER outcome ${outcome.result}/${outcome.evidenceClass}, expected CORRECT/MEDIUM`);
      return { shuffledOnIssue: true, positionSelectByKeyboard: true, movedWithArrowsOnly: true, identicalWordsInterchangeable: true,
        liveRegionAnnounced: true, result: outcome.result, evidenceClass: outcome.evidenceClass, screenshot390: true, noHorizontalOverflow390: !wide };
    },
    async CATEGORIZE() {
      const scope = '.study-card';
      await waitFor(async () => (await count({ css: 'section[data-category]', within: scope })) === CATEGORIZE.groups.length, 'CATEGORIZE did not present every group');
      need((await poolNames(scope)).length === 4, 'CATEGORIZE did not present four items');
      await waitFor(() => call(`const a = document.querySelector('.study-card app-categorize-board audio');
        return a instanceof HTMLAudioElement && a.readyState >= 1;`), 'categorize audio did not load in the shared player', 30_000);
      await answeringShot('mechanics-study-categorize-1440.png');
      await phone();
      const wide = await overflowing();
      const columns = await call(`return getComputedStyle(document.querySelector('.study-card .groups')).gridTemplateColumns.split(' ').length;`);
      await ctx.saveScreenshot('mechanics-study-categorize-390.png', tab);
      if (wide) finding('categorize_study_390_overflow', 'CATEGORIZE Study answering view overflows horizontally at 390 px');
      if (columns !== 1) finding('categorize_study_390_columns', 'CATEGORIZE groups do not stack vertically at 390 px (' + columns + ' columns)');
      await desktop();
      // Pressing the player of the audio item must neither select it nor assign anything.
      const players = await count({ css: '.mnema-player-controls > button:first-child', within: scope + ' app-categorize-board' });
      need(players >= 1, 'the audio item has no player');
      for (let index = 0; index < players; index++) {
        await realClick({ css: '.mnema-player-controls > button:first-child', within: scope + ' app-categorize-board', index });
      }
      await new Promise(resolve => setTimeout(resolve, 500));
      need((await count({ css: '[data-select][aria-pressed="true"]', within: scope })) === 0 && (await poolNames(scope)).length === 4,
        'pressing the audio player selected or assigned an item');
      const groupFor = name => name.startsWith('Аудио') ? CATEGORIZE.audioGroup : CATEGORIZE.texts.find(entry => entry.text === name).group;
      // «река» first goes to the wrong group, then the learner changes the decision and puts it into the distractor on purpose.
      await assignAll(scope, name => name === 'река' ? 'Глагол' : groupFor(name));
      await activate({ css: '[data-select]', within: scope, label: 'Выбрать: река' });
      await activate({ css: 'button[data-place]', within: scope + ' section[data-category]', labelIncludes: '«Не относится»' });
      await waitFor(async () => (await text({ css: '[data-category] .counter', within: scope, index: 2 })) === 'Элементов: 1', 'the changed decision was not counted');
      need((await text({ css: '[data-category] .counter', within: scope, index: 1 })) === 'Элементов: 2'
        && (await text({ css: '[data-category] .counter', within: scope, index: 0 })) === 'Элементов: 1', 'the group counters did not follow the changed decision');
      need(await call("return document.querySelector('.study-card [data-pool] .empty') !== null;"), 'the pool does not say that everything is assigned');
      const before = wire.attempts.length;
      await waitFor(() => call("return !document.querySelector('.study-card button[data-submit]').disabled;"), 'submit stayed disabled with every item assigned');
      await submitKey({ css: 'button[data-submit]', within: scope });
      await afterSubmit('CATEGORIZE', 'Частично');
      need((await count({ css: '.feedback-card .pair-feedback li' })) === 4 && (await count({ css: '.feedback-card .pair-feedback li.is-wrong' })) === 1,
        'CATEGORIZE feedback does not mark exactly one wrong item out of four');
      const wrong = await text({ css: '.feedback-card .pair-feedback li.is-wrong' });
      need(wrong.includes('Ваша группа: Не относится') && wrong.includes('Правильная группа: Существительное'), 'the wrong item does not show both groups: ' + wrong);
      await feedbackShot('mechanics-study-categorize-feedback-1440.png');
      const outcome = await outcomeAfter(before, 'CATEGORIZE');
      need(outcome.result === 'PARTIAL' && outcome.evidenceClass === 'LOW', `CATEGORIZE outcome ${outcome.result}/${outcome.evidenceClass}, expected PARTIAL/LOW`);
      return { audioPlayerDoesNotAssign: true, assignedByKeyboard: true, decisionChangedBeforeSubmit: true, emptyDistractorGroup: true,
        countersFollowAssignments: true, perItemFeedback: true, result: outcome.result, evidenceClass: outcome.evidenceClass,
        groupsStackedAt390: columns === 1, screenshot390: true, noHorizontalOverflow390: !wide };
    },
    async FREE_RESPONSE() {
      await typeInto({ css: '.study-card textarea' }, ctx.materialText);
      const before = wire.attempts.length;
      await submitKey({ css: 'button[data-submit]', within: '.study-card' });
      await afterSubmit('FREE_RESPONSE', 'Верно');
      const outcome = await outcomeAfter(before, 'FREE_RESPONSE');
      return { result: outcome.result, evidenceClass: outcome.evidenceClass };
    }
  };

  const authoredAll = Object.entries(authored).filter(([, ok]) => ok).map(([key]) => key);
  const studied = new Set();
  const detect = async () => {
    await waitFor(async () => (await has({ css: '.study-card:not(.feedback-card) h2' })) || (await has({ css: '.completion' }))
      || (await has({ css: '.notice.error' })), 'Study did not present an exercise', 25_000);
    need(!(await has({ css: '.notice.error' })), 'Study reported an error: ' + (await text({ css: '.notice.error' })));
    if (await has({ css: '.completion' })) return null;
    const heading = await text({ css: '.study-card h2' });
    if (heading === 'Выберите ответ') {
      return (await text({ css: '.study-card .choice-set legend' })).includes('все подходящие') ? 'CHOICE_MULTIPLE' : 'CHOICE_SINGLE';
    }
    const kind = ({ 'Вспомните, затем сверьтесь': 'SELF_CHECK', 'Напишите ответ': 'FREE_RESPONSE', 'Заполните пропуски': 'CLOZE',
      'Соедините пары': 'MATCH', 'Восстановите порядок': 'ORDER', 'Распределите по группам': 'CATEGORIZE' })[heading];
    need(kind !== undefined, 'Study presented an unknown exercise: ' + heading);
    return kind;
  };
  let aborted = false;
  // A standard session introduces at most five new objectives, so seven authored exercises need a second session.
  async function studyRound(round) {
    let started = await scenario(round === 1 ? 'mechanics_study_session_start' : 'mechanics_study_session_start_' + round, async () => {
      await desktop();
      await ctx.navigate(ctx.deckPath + '/study', tab);
      await waitFor(() => has({ css: '.session-setup' }), 'Study preset setup did not load');
      await activate({ css: '.session-setup button', includes: 'Начать стандартную' });
      return { preset: 'STANDARD', maxNewObjectives: 5, round, authoredMechanics: authoredAll };
    });
    if (!started) { aborted = true; return; }
    for (let turn = 0; started && turn < 8; turn++) {
      let kind = null;
      try { kind = await detect(); } catch (error) {
        const reason = error instanceof SafeFailure ? error.message : 'driver_failure';
        await ctx.saveScreenshot('failure-mechanics_study_turn.png', tab).catch(() => {});
        record('mechanics_study_session', { state: 'failed', reason, round, turn: turn + 1, screenshot: 'failure-mechanics_study_turn.png' });
        failures.push('mechanics_study_session');
        aborted = true;
        return;
      }
      if (kind === null) return;
      if (studied.has(kind)) { record('mechanics_study_session', { state: 'failed', reason: kind + ' was presented twice' }); failures.push('mechanics_study_wire'); aborted = true; return; }
      studied.add(kind);
      started = await scenario('mechanics_study_' + kind.toLowerCase(), async () => {
        const details = await handlers[kind]();
        await proceed();
        return details;
      });
      if (!started) { aborted = true; return; }
    }
  }
  await studyRound(1);
  if (!aborted && authoredAll.some(key => !studied.has(key))) await studyRound(2);
  for (const key of authoredAll) {
    if (studied.has(key)) continue;
    if (aborted) {
      record('mechanics_study_' + key.toLowerCase(), { state: 'not_run', reason: 'an earlier Study step failed; see that scenario' });
    } else {
      record('mechanics_study_' + key.toLowerCase(), { state: 'failed', reason: 'authored exercise was not presented in the standard sessions' });
      failures.push('mechanics_study_' + key.toLowerCase());
    }
  }
  for (const key of ['SELF_CHECK', 'CLOZE', 'CHOICE_MULTIPLE', 'CHOICE_SINGLE', 'MATCH', 'ORDER', 'CATEGORIZE']) {
    if (!authoredAll.includes(key)) record('mechanics_study_' + key.toLowerCase(), { state: 'not_run', reason: 'authoring did not succeed' });
  }
  for (const entry of findings) failures.push('finding:' + entry.id);
  return { failures, findings, wire };
}
