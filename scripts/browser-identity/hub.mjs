// Real-browser check of the Deck hub (#285): statistics figures, sort, «Эталон», multi-select, the bulk action bar and
// hold-to-delete. Runs with `--authoring --media --mechanics`, after the exercise mechanics, on the signed-in account's tab,
// against the real Learning API. Node 24 built-ins only.
//
// The deck already holds the base flow's material with its authored exercises. This scenario adds two materials without
// exercises and one disposable material with a single exercise, all through the real editor (the exercise through the same
// authenticated API the Study fixture uses), and then deletes the disposable one with a REAL 3 s keyboard hold.
// A broken step is a finding for the product, never something to work around: it is recorded as one failed `hub_*`
// result with its screenshot and the UI's own reason, and the run fails.
import { writeFile } from 'node:fs/promises';
import { join } from 'node:path';

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const MECHANICS = ['SELF_CHECK', 'FREE_RESPONSE', 'CLOZE', 'CHOICE', 'MATCH', 'ORDER', 'CATEGORIZE'];
const STATE_LABELS = { NOT_STARTED: 'Не начато', LEARNING: 'Учится', DUE: 'К повторению', ON_TRACK: 'В порядке' };
const MISSING_A = 'Хаб: материал без упражнений А';
const MISSING_B = 'Хаб: материал без упражнений Б';
const DISPOSABLE = 'Хаб: временный материал на удаление';
const SHIFT = 8;

export async function runHub(ctx) {
  const { tab, config, record, SafeFailure, until, exists, bodyIncludes, sanitizedLocation, navigate, saveScreenshot,
    saveFullScreenshot, clickText, deckPath, bearer } = ctx;
  class UiFailure extends SafeFailure {}
  const need = (value, label) => { if (!value) throw new UiFailure(label); };
  const failures = [];
  const deckId = deckPath.split('/').at(-1);

  // ----- page helpers: every value crosses CDP as an argument, never interpolated into page source -----------------
  const page = (body, ...args) => tab.callFunction(`async function(...args) { ${body} }`, args);
  const renderSettled = () => page(`return new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve(true))));`);
  const press = async (key, code, virtualKeyCode, keyText) => {
    const event = { key, code, windowsVirtualKeyCode: virtualKeyCode, nativeVirtualKeyCode: virtualKeyCode };
    await tab.call('Input.dispatchKeyEvent', keyText === undefined
      ? { type: 'rawKeyDown', ...event } : { type: 'keyDown', text: keyText, unmodifiedText: keyText, ...event });
    await tab.call('Input.dispatchKeyEvent', { type: 'keyUp', ...event });
    await renderSettled();
  };
  const keys = {
    Space: () => press(' ', 'Space', 32, ' '), Enter: () => press('Enter', 'Enter', 13, '\r'),
    Escape: () => press('Escape', 'Escape', 27), ArrowRight: () => press('ArrowRight', 'ArrowRight', 39),
    Tab: () => press('Tab', 'Tab', 9)
  };
  const metrics = size => tab.call('Emulation.setDeviceMetricsOverride', size);
  const desktop = () => metrics({ width: 1440, height: 900, deviceScaleFactor: 1, mobile: false });
  const phone = () => metrics({ width: 390, height: 844, deviceScaleFactor: 1, mobile: true });
  const zoomed = () => metrics({ width: 320, height: 900, deviceScaleFactor: 2, mobile: false });
  const documentOverflows = () => page(`return document.documentElement.scrollWidth > document.documentElement.clientWidth;`);

  /** Waits for a page-side predicate; a UiFailure thrown by `check` is a verdict and is not retried. */
  const waitFor = async (check, label, timeoutMs) => {
    let verdict = null;
    await until(async () => {
      try { return await check(); } catch (error) {
        if (error instanceof UiFailure) { verdict = error; return true; }
        throw error;
      }
    }, label, timeoutMs);
    if (verdict !== null) throw verdict;
  };
  const focus = async (selector, index = 0) => need(await page(`const e = document.querySelectorAll(args[0])[args[1]];
    if (!(e instanceof HTMLElement) || e.matches(':disabled')) return false;
    e.scrollIntoView({ block: 'center', behavior: 'instant' }); e.focus(); return document.activeElement === e;`, selector, index),
  'target could not take keyboard focus: ' + selector);
  const realClick = async (selector, index = 0, modifiers = 0) => {
    const point = await page(`const e = document.querySelectorAll(args[0])[args[1]];
      if (!(e instanceof HTMLElement) || e.matches(':disabled')) return null;
      e.scrollIntoView({ block: 'center', behavior: 'instant' });
      const r = e.getBoundingClientRect(); return { x: r.left + r.width / 2, y: r.top + r.height / 2 };`, selector, index);
    need(point !== null, 'click target absent or disabled: ' + selector);
    await tab.call('Input.dispatchMouseEvent', { type: 'mouseMoved', x: point.x, y: point.y, modifiers });
    await tab.call('Input.dispatchMouseEvent', { type: 'mousePressed', x: point.x, y: point.y, button: 'left', clickCount: 1, modifiers });
    await tab.call('Input.dispatchMouseEvent', { type: 'mouseReleased', x: point.x, y: point.y, button: 'left', clickCount: 1, modifiers });
    await renderSettled();
  };

  // ----- the real API, called from the page with the account's bearer ---------------------------------------------
  const api = (path, init = {}) => page(`const response = await fetch(args[0] + '/api' + args[1], { credentials: 'omit',
      ...args[3], headers: { Authorization: args[2], ...(args[3].body ? { 'Content-Type': 'application/json' } : {}), ...(args[3].headers ?? {}) } });
    const text = await response.text();
    return { status: response.status, etag: response.headers.get('etag'), body: text ? JSON.parse(text) : null };`,
  config.frontend, path, 'Bearer ' + bearer, init);
  const insightsApi = async () => {
    const response = await api(`/decks/${deckId}/insights`);
    need(response.status === 200, 'insights endpoint answered ' + response.status);
    return response.body;
  };
  const listApi = async query => {
    const response = await api(`/decks/${deckId}/items?limit=100&include=exerciseCount${query}`);
    need(response.status === 200, 'item list endpoint answered ' + response.status);
    return response.body;
  };

  // ----- what the user sees ---------------------------------------------------------------------------------------
  const rows = () => page(`return [...document.querySelectorAll('app-selectable-material-list li.item-row')].map(row => ({
    title: row.querySelector('.row-link')?.textContent.trim() ?? null,
    href: row.querySelector('.row-link')?.getAttribute('href') ?? null,
    folio: Number(row.querySelector('.folio')?.textContent) || null,
    count: (() => { const t = row.querySelector('.count')?.textContent.replace(/\\s+/g, ' ').trim() ?? '';
      return t.startsWith('нет') ? 0 : Number(t.split(' ')[0]); })(),
    missingWarning: row.querySelector('.count.missing') !== null,
    selected: row.querySelector('.check input')?.checked === true,
    starred: row.querySelector('button.star')?.getAttribute('aria-pressed') === 'true',
    starName: row.querySelector('button.star')?.getAttribute('aria-label') ?? null }));`);
  const rowIndex = async title => {
    const index = (await rows()).findIndex(row => row.title === title);
    need(index >= 0, 'material not listed in the hub: ' + title);
    return index;
  };
  const header = () => page(`const input = document.querySelector('.select-all input');
    return input ? { checked: input.checked, indeterminate: input.indeterminate } : null;`);
  const bar = () => page(`const region = document.querySelector('app-bulk-action-bar section');
    if (!region) return null;
    const host = region.parentElement; const rect = host.getBoundingClientRect();
    const hold = region.querySelector('app-hold-to-delete-button button');
    const consequence = region.querySelector('.consequence');
    return { role: region.getAttribute('role'), label: region.getAttribute('aria-label'),
      count: region.querySelector('.count')?.textContent.replace(/\\s+/g, ' ').trim() ?? null,
      buttons: [...region.querySelectorAll('button')].map(button => button.textContent.replace(/\\s+/g, ' ').trim()),
      sticky: getComputedStyle(host).position === 'sticky', insideViewport: rect.top >= 0 && rect.bottom <= innerHeight + 1,
      holdEnabled: hold ? !hold.disabled : null, holdArmed: hold?.getAttribute('aria-pressed') === 'true',
      consequence: consequence ? { text: consequence.textContent.trim(), visible: !consequence.hidden } : null,
      reservedHeight: document.documentElement.style.getPropertyValue('--mn-bulk-bar-height'),
      hint: region.querySelector('.hint')?.textContent.trim() ?? null };`);
  const tables = () => page(`return [...document.querySelectorAll('app-deck-insights article.widget')].map(widget => ({
    title: widget.querySelector('h3')?.textContent.trim() ?? null,
    rows: [...widget.querySelectorAll('details tbody tr')].map(row => [...row.children].map(cell => cell.textContent.trim())),
    caption: widget.querySelector('figcaption')?.textContent.replace(/\\s+/g, ' ').trim() ?? null,
    action: widget.querySelector('.action a, .action button')?.textContent.trim() ?? null }));`);
  const figures = () => page(`return [...document.querySelectorAll('app-deck-insights figure')].map(figure => {
    const image = figure.querySelector('svg[role="img"]');
    const caption = figure.querySelector('figcaption');
    const named = image && caption ? document.getElementById(image.getAttribute('aria-labelledby')) : null;
    return { drawn: image !== null, labelledByCaption: named === caption && caption !== null,
      captionLength: caption?.textContent.trim().length ?? 0 };
  });`);
  const status = () => page(`return document.querySelector('app-deck-materials .note')?.textContent.replace(/\\s+/g, ' ').trim() ?? null;`);

  // The charts are `@defer (on viewport)`: scroll each widget into view so the real IntersectionObserver fires.
  async function revealCharts() {
    await page(`for (const e of document.querySelectorAll('app-deck-insights article.widget')) e.scrollIntoView({ block: 'center', behavior: 'instant' });
      return true;`);
    await waitFor(async () => (await figures()).length === 5 && (await figures()).every(figure => figure.drawn),
      'the statistics drawings were not rendered when scrolled into view');
    await page(`window.scrollTo({ top: 0, behavior: 'instant' }); return true;`);
  }
  const expectedTables = insights => [
    [['С упражнениями', insights.coverage.withExercises], ['Без упражнений', insights.coverage.withoutExercises], ['Всего', insights.coverage.total]],
    Object.keys(STATE_LABELS).map(state => [STATE_LABELS[state], insights.states[state]]),
    insights.dueByDay.map(day => [null, day.materials]),
    MECHANICS.map(mechanic => [null, insights.exercisesByMechanic[mechanic]]),
    [['Ждут разбора', insights.captures.open]]
  ].map(group => group.map(([label, value]) => [label, String(value)]));
  // A null label (the day and mechanic names) is not compared; only its number is.
  /** The tables on the page must carry exactly the numbers the API sent (the first cell is only compared when known). */
  async function assertFiguresMatchApi(label) {
    const insights = await insightsApi();
    const expected = expectedTables(insights);
    const shown = await tables();
    need(shown.length === 5, label + ': five widgets expected');
    shown.forEach((widget, index) => {
      const wanted = expected[index];
      const actual = widget.rows.slice(0, wanted.length);
      const ok = actual.length === wanted.length && wanted.every(([name, value], row) =>
        actual[row][1] === value && (name === null || actual[row][0] === name));
      need(ok, `${label}: table "${widget.title}" differs from the API (${JSON.stringify(actual)} vs ${JSON.stringify(wanted)})`);
      need(widget.caption !== null && widget.caption.length > 10, `${label}: widget "${widget.title}" has no caption`);
      need(widget.action !== null, `${label}: widget "${widget.title}" ends without an action`);
    });
    const coverage = shown[0].caption;
    need(coverage.includes(`С упражнениями ${insights.coverage.withExercises} из ${insights.coverage.total} `),
      `${label}: coverage caption does not state ${insights.coverage.withExercises} of ${insights.coverage.total}: ${coverage}`);
    return insights;
  }

  // ----- fixtures through the real editor and API -------------------------------------------------------------------
  async function publishMaterial(text) {
    await navigate(deckPath + '/materials/new', tab);
    await waitFor(() => exists('.ProseMirror[contenteditable="true"]', tab), 'new-material editor absent');
    need(await page(`const editor = document.querySelector('.ProseMirror[contenteditable="true"]');
      if (!(editor instanceof HTMLElement)) return false;
      editor.focus(); const selection = getSelection(); const range = document.createRange();
      range.selectNodeContents(editor); selection.removeAllRanges(); selection.addRange(range); return true;`),
    'new-material editor could not take focus');
    await tab.call('Input.insertText', { text });
    await waitFor(() => bodyIncludes('Все изменения сохранены', tab), 'draft acknowledgement absent for "' + text + '"');
    need(await clickText('button', 'Опубликовать', tab), 'publication unavailable for "' + text + '"');
    await waitFor(async () => /^\/decks\/[0-9a-f-]{36}\/materials\/[0-9a-f-]{36}$/.test(await sanitizedLocation(tab))
      && await bodyIncludes(text, tab), 'publication of "' + text + '" did not reach the material page');
    return (await sanitizedLocation(tab)).split('/').at(-1);
  }
  async function addExercise(memberKey) {
    const deck = await api(`/decks/${deckId}`);
    const item = await api(`/decks/${deckId}/items/${memberKey}`);
    need(deck.status === 200 && item.status === 200, 'deck or material read failed');
    const node = item.body.document?.root?.content?.find(candidate => candidate.type === 'paragraph');
    need(node?.id, 'the new material has no paragraph to quote');
    const created = await api(`/decks/${deckId}/exercises`, { method: 'POST', headers: { 'If-Match': deck.etag }, body: JSON.stringify({
      commandId: crypto.randomUUID(), expectedDeckRevisionId: deck.body.revisionId,
      objective: { operation: 'create', title: 'Хаб: упражнение на удаляемый материал' },
      exercise: { type: 'FREE_RESPONSE', schemaVersion: 2, enabled: true,
        subject: { memberKey: item.body.memberKey, itemRevisionId: item.body.itemRevisionId },
        content: { prompt: [{ kind: 'TEXT', text: 'Введите слово «хаб»' }],
          reference: [{ kind: 'MATERIAL', memberKey: item.body.memberKey, itemRevisionId: item.body.itemRevisionId, nodeId: node.id }],
          responseInput: 'TEXT' },
        answerKey: { kind: 'TEXT', accepted: ['hub'], normalization: ['UNICODE_NFC', 'TRIM', 'CASE_FOLD'], matchingMode: 'STRICT' },
        evaluatorPolicy: { id: 'deterministic-text', version: '1' } } }) });
    need(created.status === 201, 'exercise creation answered ' + created.status);
  }
  async function openHub() {
    await desktop();
    await navigate(deckPath, tab);
    await waitFor(async () => (await rows()).length > 0, 'the deck hub did not list its materials');
  }

  /** A hung renderer is a product finding: name where the main thread is stuck (script, line, column only). */
  async function assertResponsive(when) {
    const answer = await Promise.race([page(`return 1;`).then(() => 'alive', () => 'error'), sleep(5000).then(() => 'hung')]);
    if (answer === 'alive') return;
    let frames = '';
    if (answer === 'hung') {
      let resolvePaused;
      const paused = new Promise(resolve => { resolvePaused = resolve; });
      tab.on('Debugger.paused', event => resolvePaused(event.callFrames));
      await tab.call('Debugger.enable').catch(() => {});
      await tab.call('Debugger.pause').catch(() => {});
      setTimeout(() => resolvePaused([]), 6000);
      frames = (await paused).slice(0, 6).map(frame => `${frame.functionName || '?'}@${String(frame.url).split('/').pop()}:${frame.location.lineNumber}:${frame.location.columnNumber}`).join(' <- ');
    }
    throw new UiFailure(`the page stopped responding ${when} (${answer}); stuck at: ${frames || 'unknown (native code)'}`);
  }

  // ----- scenario runner: one soft-failing record -----------------------------------------------------------------
  const name = 'hub_overview_sort_star_select_delete';
  ctx.setStep(name);
  const started = Date.now();
  const facts = {};
  try {
    await tab.call('Page.bringToFront');
    await desktop();

    // 0. Data: two materials without exercises and one disposable material with exactly one exercise.
    await publishMaterial(MISSING_A);
    await publishMaterial(MISSING_B);
    const disposableKey = await publishMaterial(DISPOSABLE);
    await addExercise(disposableKey);
    await openHub();
    const initial = await rows();
    need(initial.length === 4, 'the hub should list four materials, found ' + initial.length);
    need(initial.at(-1).title === DISPOSABLE, 'new materials must follow the first one in authoring order');
    need(initial[0].folio === 1 && initial.map(row => row.folio).join() === '1,2,3,4', 'every row shows its authoring ordinal');
    need(initial[0].count > 0 && initial[1].count === 0 && initial[2].count === 0 && initial[3].count === 1,
      'exercise counts differ from the authored data: ' + initial.map(row => row.count).join());
    need(initial[1].missingWarning && initial[2].missingWarning, '«нет упражнений» must carry its own warning mark and text');
    need(!(await bodyIncludes('Просмотреть материалы', tab)), 'the old «Просмотреть материалы» entry is still present');
    facts.materials = 4;

    // 1. Statistics: five figures with captions, a real table each, numbers equal to the API's.
    await revealCharts();
    const figs = await figures();
    need(figs.length === 5 && figs.every(figure => figure.drawn && figure.labelledByCaption && figure.captionLength > 10),
      'every statistics figure needs a drawing named by its caption: ' + JSON.stringify(figs));
    need(await page(`return document.querySelectorAll('app-deck-insights details table').length === 5
      && [...document.querySelectorAll('app-deck-insights details summary')].every(s => s.textContent.trim() === 'Показать таблицей');`),
    'every figure needs a <details> with a real table');
    // Open the first table with a real click on its summary and see that it becomes visible.
    await realClick('app-deck-insights details summary', 0);
    need(await page(`const d = document.querySelector('app-deck-insights details'); return d.open && d.querySelector('table').getClientRects().length > 0;`),
      'the table alternative did not open');
    const insights = await assertFiguresMatchApi('initial statistics');
    need(insights.coverage.total === 4 && insights.coverage.withoutExercises >= 2, 'coverage must show materials without exercises: '
      + JSON.stringify(insights.coverage));
    need(!(await page(`const text = document.querySelector('app-deck-insights').innerText;
      return text.toLowerCase().includes('стрик') || text.includes('%');`)),
      'statistics show a vanity metric');
    facts.insightsMatchApi = true; facts.coverage = insights.coverage;
    await saveFullScreenshot('hub-1440.png', tab);

    // Responsive evidence: 390, and 320 CSS px rasterized at 2x. The document never scrolls sideways.
    await phone();
    need(!(await documentOverflows()), 'the hub overflows horizontally at 390 px');
    await revealCharts();
    await saveFullScreenshot('hub-390.png', tab);
    await zoomed();
    need(await page(`return innerWidth === 320 && devicePixelRatio === 2 && document.documentElement.scrollWidth <= 320;`),
      'the hub does not reflow at 320 px / 200 %');
    await revealCharts();
    await saveFullScreenshot('hub-320-at-200-percent.png', tab);
    facts.widths = [1440, 390, 320]; facts.noHorizontalOverflow = true;
    await desktop();

    // 2. Sort «Без упражнений сначала» with the keyboard: arrow key on the radio group.
    need(await page(`return document.querySelector('app-segmented-choice input:checked')?.closest('label')?.textContent.trim() === 'По порядку';`),
      'the authoring order must be the default sort');
    await focus('app-segmented-choice input:checked');
    await keys.ArrowRight();
    await waitFor(async () => (await rows())[0]?.title === MISSING_A && (await rows()).map(row => row.count).join() === '0,0,1,' + initial[0].count,
      'the keyboard sort did not list materials without exercises first');
    const sorted = await rows();
    need(sorted.map(row => row.folio).join() === '2,3,4,1', 'every row must keep its true ordinal after sorting: ' + sorted.map(row => row.folio).join());
    const serverOrder = (await listApi('&sort=exerciseCount')).items.map(item => item.memberKey);
    need(sorted.map(row => row.href.split('/').at(-1)).join() === serverOrder.join(), 'the list order differs from the API sorted order');
    need(await page(`return document.querySelector('app-segmented-choice .hint')?.textContent.includes('Сначала материалы без упражнений');`),
      'the live hint under the sort did not change');
    facts.sortByKeyboard = true;

    // 3. «Эталон» with the keyboard; the state survives a reload and is stored by the server.
    const starIndex = await rowIndex(MISSING_A);
    const starName = 'Эталон: ' + MISSING_A;
    need((await rows())[starIndex].starName === starName, 'the star must be named after its material');
    await focus('app-selectable-material-list button.star', starIndex);
    await keys.Space();
    await waitFor(async () => (await rows())[await rowIndex(MISSING_A)].starred, 'the star did not turn on from the keyboard');
    need((await listApi('')).exemplars.count === 1 && (await listApi('')).items.find(item => item.title === MISSING_A)?.exemplar === true,
      'the server did not store the exemplar flag');
    await navigate(deckPath, tab);
    await waitFor(async () => (await rows()).length === 4, 'hub did not reload');
    need((await rows())[await rowIndex(MISSING_A)].starred, 'the exemplar star did not survive a reload');
    need(await page(`return document.querySelector('.budget')?.textContent.replace(/\\s+/g, ' ').includes('Эталонов: 1 из 10');`), 'the budget note is missing');
    await focus('app-selectable-material-list button.star', await rowIndex(MISSING_A));
    await keys.Space();
    await waitFor(async () => !(await rows())[await rowIndex(MISSING_A)].starred, 'the star did not turn off from the keyboard');
    need((await listApi('')).exemplars.count === 0, 'the server kept the removed exemplar');
    facts.exemplarByKeyboard = true; facts.survivesReload = true;

    // 4. Selection: checkbox, Shift+click range, tri-state «Выбрать все», the bulk bar, Esc.
    need((await header()).checked === false && (await header()).indeterminate === false && await bar() === null,
      'nothing may be selected on load, and no bar may show');
    await realClick('app-selectable-material-list li .check input', 0);
    await realClick('app-selectable-material-list li .check input', 1, SHIFT);
    const twoSelected = await rows();
    need(twoSelected.map(row => row.selected).join() === 'true,true,false,false', 'checkbox + Shift+click did not select the range: '
      + twoSelected.map(row => row.selected).join());
    need((await header()).indeterminate === true && (await header()).checked === false, '«Выбрать все» must be mixed with a partial selection');
    const shownBar = await bar();
    need(shownBar !== null && shownBar.role === 'region' && shownBar.label === 'Действия с выбранными', 'bulk bar region absent');
    need(shownBar.count === 'Выбрано 2 материала', 'bulk bar must say how many are selected: ' + shownBar.count);
    need(shownBar.sticky && shownBar.insideViewport, 'the bulk bar must stick inside the viewport');
    need(/^\d+px$/.test(shownBar.reservedHeight) && parseInt(shownBar.reservedHeight, 10) > 0, 'the page did not reserve the bar height for focused rows');
    need(!shownBar.buttons.some(label => label.includes('ИИ')), 'the AI button must be absent while the capability is off: ' + shownBar.buttons.join('|'));
    need(shownBar.buttons.some(label => label.startsWith('Удалить выбранные · 2')) && shownBar.buttons.includes('Снять выбор'), 'bulk bar actions are missing');
    await saveFullScreenshot('hub-selection-1440.png', tab);
    await realClick('app-selectable-material-list .select-all input');
    need((await header()).checked === true && (await rows()).every(row => row.selected), '«Выбрать все» did not select every loaded row');
    await realClick('app-selectable-material-list .select-all input');
    need((await header()).checked === false && (await header()).indeterminate === false && (await rows()).every(row => !row.selected) && await bar() === null,
      '«Выбрать все» did not clear the selection and the bar');
    await page(`window.__hubClicks = []; document.addEventListener('click', event => window.__hubClicks.push(
      [event.target.closest?.('li')?.querySelector('.row-link')?.textContent.trim().slice(-3), event.shiftKey, event.detail]), true); return true;`);
    await realClick('app-selectable-material-list li .check input', 0);
    await realClick('app-selectable-material-list li .check input', 1, SHIFT);
    need((await bar())?.count === 'Выбрано 2 материала', 'could not reselect two rows: ' + JSON.stringify(await page(`return window.__hubClicks;`))
      + ' ' + (await rows()).map(row => row.selected).join());
    await phone();
    await waitFor(async () => (await bar())?.insideViewport === true, 'the bulk bar left the 390 px viewport');
    need(!(await documentOverflows()), 'the selection layout overflows horizontally at 390 px');
    await saveScreenshot('hub-selection-390.png', tab);
    await desktop();
    await focus('app-selectable-material-list li .check input', 0);
    await keys.Escape();
    await waitFor(async () => await bar() === null && (await rows()).every(row => !row.selected), 'Esc did not clear the selection');
    facts.selection = { range: true, triState: true, bulkBar: true, escClears: true, aiButtonAbsent: true };

    // 5. Delete the disposable material with a real 3 s hold, consequences visible.
    await realClick('app-selectable-material-list li .check input', await rowIndex(DISPOSABLE));
    await waitFor(async () => (await bar())?.holdEnabled === true, 'deletion stayed disabled although a selection exists: ' + JSON.stringify(await bar()));
    const revision = (await listApi('')).deckRevisionId;
    const preview = await api(`/decks/${deckId}/items/deletions/preview`, { method: 'POST',
      body: JSON.stringify({ itemIds: [disposableKey], expectedDeckRevisionId: revision }) });
    need(preview.status === 200 && preview.body.materialCount === 1 && preview.body.affectedExerciseCount === 1,
      'the server preview must count one material and one exercise: ' + JSON.stringify(preview.body));
    await focus('app-bulk-action-bar app-hold-to-delete-button button');
    await keys.Space();   // activation arms the button; the hold is a second, uninterrupted press
    const armed = await bar();
    need(armed.holdArmed, 'the first press must only arm the hold button');
    need(armed.consequence?.visible && armed.consequence.text === 'Удалит 1 материал и 1 упражнение. История занятий сохранится.',
      'the consequence text is not visible while armed: ' + JSON.stringify(armed.consequence));
    await saveScreenshot('hub-hold-to-delete-1440.png', tab);
    const holdStarted = Date.now();
    await tab.call('Input.dispatchKeyEvent', { type: 'keyDown', text: ' ', unmodifiedText: ' ', key: ' ', code: 'Space', windowsVirtualKeyCode: 32, nativeVirtualKeyCode: 32 });
    await sleep(600);
    const holding = await page(`const b = document.querySelector('app-hold-to-delete-button button');
      return { holding: b.classList.contains('holding'), label: b.textContent.replace(/\\s+/g, ' ').trim(), disabled: b.disabled,
        active: document.activeElement === b, pressed: b.getAttribute('aria-pressed') };`);
    need(holding.holding, 'the hold did not begin on a keyboard press: ' + JSON.stringify(holding));
    await sleep(900);
    need((await rows()).length === 4, 'the material disappeared before the 3 s hold was over');
    // The key stays down until the hold completes (headless Chrome may throttle page timers, so a fixed sleep is not a hold).
    await waitFor(async () => (await rows()).length === 3, 'the hold did not delete the material while the key stayed down', 15_000);
    const heldMs = Date.now() - holdStarted;
    await tab.call('Input.dispatchKeyEvent', { type: 'keyUp', key: ' ', code: 'Space', windowsVirtualKeyCode: 32, nativeVirtualKeyCode: 32 });
    need(heldMs >= 3000, 'the material was deleted after only ' + heldMs + ' ms: the hold must last 3 s');
    need((await rows()).every(row => row.title !== DISPOSABLE), 'the deleted material is still listed');
    await waitFor(async () => (await status())?.includes('Удалено 1 материал.'), 'the outcome of the deletion was not stated');
    need(await bar() === null, 'the bulk bar must go away after the deletion');
    need(await page(`return document.querySelector('#materials-heading')?.textContent.replace(/\\s+/g, ' ').trim() === 'Материалы · 3';`),
      'the material total did not update');
    const gone = await api(`/decks/${deckId}/items/${disposableKey}`);
    need(gone.status === 404, 'the server still serves the deleted material (' + gone.status + ')');
    // Statistics follow the deletion without a reload: coverage, states and the exercise sum.
    await waitFor(async () => {
      const shown = await tables();
      return shown[0]?.rows[2]?.[1] === '3';
    }, 'statistics did not refresh after the deletion');
    const after = await assertFiguresMatchApi('statistics after the deletion');
    need(after.coverage.total === 3 && after.coverage.withoutExercises === 2 && after.coverage.withExercises === 1,
      'coverage after the deletion is wrong: ' + JSON.stringify(after.coverage));
    facts.holdDelete = { keyboardHoldMs: heldMs, consequenceVisible: true, listUpdated: true, coverageUpdated: true, serverGone: true };

    // 6. Keyboard focus on a row and reduced motion.
    await page(`document.querySelector('app-selectable-material-list .select-all input').focus(); return true;`);
    await keys.Tab();   // the first row's checkbox
    await keys.Tab();   // the first row's link
    need(await page(`return document.querySelector('.item-row:has(.row-link:focus-visible)') !== null;`),
      'keyboard focus on a row link must be visible on its row (:has(:focus-visible))');
    await saveScreenshot('hub-row-focus-1440.png', tab);
    await tab.call('Emulation.setEmulatedMedia', { features: [{ name: 'prefers-reduced-motion', value: 'reduce' }] });
    try {
      need(await page(`return matchMedia('(prefers-reduced-motion: reduce)').matches
        && getComputedStyle(document.documentElement).scrollBehavior === 'auto'
        && getComputedStyle(document.querySelector('app-deck-insights .widgets')).scrollBehavior === 'auto';`),
      'reduced motion did not turn smooth scrolling off in the hub');
        await realClick('app-selectable-material-list li .check input', 0);
        need(await page(`const hold = document.querySelector('app-hold-to-delete-button .hold-wave');
        return hold !== null && getComputedStyle(hold).display === 'none';`), 'the hold wave must not animate under reduced motion');
      await saveFullScreenshot('hub-reduced-motion-1440.png', tab);
    } finally {
      await tab.call('Emulation.setEmulatedMedia', { features: [] });
    }
    facts.focusVisibleOnRow = true; facts.reducedMotion = true;
    // Esc was exercised above. Here the selection is cleared with its own button: an Esc sent at this point (after the
    // reduced-motion emulation was reset) hung the shared renderer in repeated runs, see assertResponsive.
    await realClick('app-bulk-action-bar button.quiet');
    await waitFor(async () => await bar() === null, 'the selection could not be cleared with «Снять выбор»');
    await assertResponsive('after the selection was cleared');

    record(name, { state: 'passed', ...facts, durationMs: Date.now() - started });
  } catch (error) {
    const reason = error instanceof SafeFailure ? error.message : 'driver_failure';
    const screenshot = `failure-${name}.png`;
    await saveScreenshot(screenshot, tab).catch(() => {});
    if (config.diagnosticsDir) {
      const text = await tab.evaluate('document.body.innerText.slice(0, 8000)').catch(() => '');
      await writeFile(join(config.diagnosticsDir, `failure-${name}.txt`), String(text));
    }
    record(name, { state: 'failed', reason, screenshot, completed: facts,
      ...(config.diagnosticsDir && !(error instanceof SafeFailure) ? { driverDetail: String(error?.message ?? error).slice(0, 160) } : {}), durationMs: Date.now() - started });
    failures.push(name);
  } finally {
    await desktop().catch(() => {});
  }
  return { failures };
}
