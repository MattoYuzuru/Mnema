// Real-browser baseline of the five exercise mechanics: create -> save -> reopen -> study.
// Runs only with `--authoring --media --mechanics`, after the base authoring flow, through the real Angular UI on a
// desktop viewport with real CDP keyboard/mouse input where the interaction is natural. Node22 built-ins only.
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
  const focusEl = async spec => need(await call(`const e = ${pick};
    if (!(e instanceof HTMLElement) || e.matches(':disabled')) return false;
    e.scrollIntoView({ block: 'center' }); e.focus(); return document.activeElement === e;`, spec),
  'target could not take keyboard focus: ' + (spec.label ?? spec.text ?? spec.includes ?? spec.css));
  const press = async (key, code, virtualKeyCode, keyText) => {
    const event = { key, code, windowsVirtualKeyCode: virtualKeyCode, nativeVirtualKeyCode: virtualKeyCode };
    await tab.call('Input.dispatchKeyEvent', keyText === undefined
      ? { type: 'rawKeyDown', ...event } : { type: 'keyDown', text: keyText, unmodifiedText: keyText, ...event });
    await tab.call('Input.dispatchKeyEvent', { type: 'keyUp', ...event });
  };
  const keys = {
    Tab: () => press('Tab', 'Tab', 9), ArrowDown: () => press('ArrowDown', 'ArrowDown', 40),
    Enter: () => press('Enter', 'Enter', 13, '\r'), Space: () => press(' ', 'Space', 32, ' ')
  };
  const typeInto = async (spec, content) => {
    await focusEl(spec);
    await call(`const e = ${pick}; if ('select' in e) e.select(); return true;`, spec);
    await tab.call('Input.insertText', { text: content });
  };
  const realClick = async spec => {
    const point = await call(`const e = ${pick};
      if (!(e instanceof HTMLElement) || e.matches(':disabled')) return null;
      e.scrollIntoView({ block: 'center' });
      const r = e.getBoundingClientRect(); return { x: r.left + r.width / 2, y: r.top + r.height / 2 };`, spec);
    need(point !== null, 'click target absent or disabled: ' + (spec.text ?? spec.label ?? spec.includes ?? spec.css));
    await tab.call('Input.dispatchMouseEvent', { type: 'mouseMoved', x: point.x, y: point.y });
    await tab.call('Input.dispatchMouseEvent', { type: 'mousePressed', x: point.x, y: point.y, button: 'left', clickCount: 1 });
    await tab.call('Input.dispatchMouseEvent', { type: 'mouseReleased', x: point.x, y: point.y, button: 'left', clickCount: 1 });
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
  async function uploadIntoSlot(slot, name, label, upload) {
    await realClick({ ...slot, css: 'button[data-add]', includes: 'Добавить аудио' });
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
    await waitFor(async () => (await slotBlocks(slot)).some(block => block.kind === 'Аудио'), label + ' block did not enter the slot');
  }
  const dropAudio = (slot, name) => call(`const drop = find({ ...args[0], css: 'app-native-media-upload .media-drop' })[0];
    if (!(drop instanceof HTMLElement)) return false;
    const bytes = Uint8Array.from(atob(args[1]), value => value.charCodeAt(0));
    const transfer = new DataTransfer(); transfer.items.add(new File([bytes], args[2], { type: 'audio/mpeg' }));
    drop.dispatchEvent(new DragEvent('drop', { bubbles: true, cancelable: true, dataTransfer: transfer })); return true;`,
  slot, config.mediaClips.audio, name).then(done => need(done, 'synthetic audio file could not be dropped on the slot picker'));
  const slotButton = (slot, label) => call(`const b = find({ ...args[0], css: 'button', text: args[1] })[0];
    if (!(b instanceof HTMLButtonElement) || b.disabled) return false;
    b.scrollIntoView({ block: 'center' }); b.focus(); return document.activeElement === b;`, slot, label);

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
  const setObjective = async title => { await revealFinish(); await typeInto({ css: '#objective-title' }, title); };
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
    need((await count({ css: 'label.tile' })) === 5, 'five type tiles expected');
    need(/^К упражнениям · \d+$/.test(await text({ css: '.jump-link' }) ?? ''), 'the anchor to the existing exercises is missing');
    await ctx.saveFullScreenshot('editor-initial-1440.png', tab);

    // A tile opens the demo and scrolls to it once.
    await realClick({ css: 'label.tile', includes: 'Выбрать ответ' });
    await waitFor(() => has({ css: '#exercise-preview-anchor [data-mode="DEMO"] .badge' }), 'the demo did not open after choosing a tile');
    need((await text({ css: '#exercise-preview-anchor .badge' })) === 'Пример', 'the demo badge is missing');
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
      return { badge: root.querySelector('.badge')?.textContent.trim(), text: root.textContent,
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
      input.scrollIntoView({ block: 'center' }); input.focus(); return document.activeElement === input;`, root),
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
  // Study: one standard session (5 new objectives) presents every authored mechanic; dispatch by what is shown.
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
  let started = await scenario('mechanics_study_session_start', async () => {
    await desktop();
    await ctx.navigate(ctx.deckPath + '/study', tab);
    await waitFor(() => has({ css: '.session-setup' }), 'Study preset setup did not load');
    await activate({ css: '.session-setup button', includes: 'Начать стандартную' });
    return { preset: 'STANDARD', maxNewObjectives: 5, authoredMechanics: authoredAll };
  });
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
      'Соедините пары': 'MATCH' })[heading];
    need(kind !== undefined, 'Study presented an unknown exercise: ' + heading);
    return kind;
  };
  let aborted = !started;
  for (let turn = 0; started && turn < 8; turn++) {
    let kind = null;
    try { kind = await detect(); } catch (error) {
      const reason = error instanceof SafeFailure ? error.message : 'driver_failure';
      await ctx.saveScreenshot('failure-mechanics_study_turn.png', tab).catch(() => {});
      record('mechanics_study_session', { state: 'failed', reason, turn: turn + 1, screenshot: 'failure-mechanics_study_turn.png' });
      failures.push('mechanics_study_session');
      aborted = true;
      break;
    }
    if (kind === null) break;
    if (studied.has(kind)) { record('mechanics_study_session', { state: 'failed', reason: kind + ' was presented twice in one session' }); failures.push('mechanics_study_wire'); aborted = true; break; }
    studied.add(kind);
    started = await scenario('mechanics_study_' + kind.toLowerCase(), async () => {
      const details = await handlers[kind]();
      await proceed();
      return details;
    });
    if (!started) aborted = true;
  }
  for (const key of authoredAll) {
    if (studied.has(key)) continue;
    if (aborted) {
      record('mechanics_study_' + key.toLowerCase(), { state: 'not_run', reason: 'an earlier Study step failed; see that scenario' });
    } else {
      record('mechanics_study_' + key.toLowerCase(), { state: 'failed', reason: 'authored exercise was not presented in the standard session' });
      failures.push('mechanics_study_' + key.toLowerCase());
    }
  }
  for (const key of ['SELF_CHECK', 'CLOZE', 'CHOICE_MULTIPLE', 'CHOICE_SINGLE', 'MATCH']) {
    if (!authoredAll.includes(key)) record('mechanics_study_' + key.toLowerCase(), { state: 'not_run', reason: 'authoring did not succeed' });
  }
  for (const entry of findings) failures.push('finding:' + entry.id);
  return { failures, findings, wire };
}
