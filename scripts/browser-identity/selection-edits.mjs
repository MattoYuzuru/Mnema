// Real-browser check of the Workshop's selection edits (#293, AI-11) against the real Learning API with the Stub text provider
// (`--generation`; never a real provider, no key). Runs at the end of the Workshop scenarios on the signed-in account's tab, in a deck
// of its own. Node 24 built-ins only.
//
// The user's whole path is the real Angular UI on the real HTTP surface: a triple click selects a paragraph, Tab then Enter open the
// «Попросить Мнему» window, a preset sends the edit, the block says it is being rewritten, the strip and the inline word diff show the
// result, «Вернуть» and «Ещё раз» are real clicks, and on a 390 px touch viewport the bar at the bottom opens the sheet. Only the
// fixture (the deck and the proposal) and the slow turn of the «second edit» check are made through the authenticated API, and the
// polling of the page is cut with `Network.setBlockedURLs` (a request that fails, never an invented answer).
// The Stub has no media, so the media actions are covered by the component specs; the Browse check proves node ids stay out of Browse.
// A broken step is a finding for the product, never something to work around: it throws, a failure screenshot is written and the
// run fails with the step name.
import { writeFile } from 'node:fs/promises';
import { join } from 'node:path';

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const SHIFT = 8;
/** A prompt for which the Stub answers with its `headings` document: h1, a paragraph, h2, h3, a paragraph. Others are tried if it ever stops. */
const PROMPTS = ['Глаголы движения: правка выделенного фрагмента', ...Array.from({ length: 24 }, (_, index) => `Глаголы движения, вариант ${index + 1}`)];
const FIRST_PARAGRAPH = 'Первый абзац.';
const SLOW = '[[stub:rate-limit]] медленно';

export async function runWorkshopEdits(ctx, h) {
  const { tab, config, SafeFailure, until, navigate, saveScreenshot, setStep, inflight } = ctx;
  const { api, page, has, need, settle, metrics, desktop, awaitCapability, activeSessions, sessionPath: baseSessionPath, location } = h;
  const out = { stub: true, stages: {}, screenshots: [] };
  const startedAt = Date.now();

  // Calls that took long (the method and what the page was asked), so a stall names the call that stalled.
  const slowCalls = [];
  const rawCall = tab.call.bind(tab);
  tab.call = async (method, params) => {
    const started = Date.now();
    try { return await rawCall(method, params); } finally {
      const took = Date.now() - started;
      if (took > 1500) slowCalls.push(`${method}${method === 'Runtime.callFunctionOn' ? ' ' + String(params?.functionDeclaration ?? '').slice(26, 90).replace(/\s+/gu, ' ') : method === 'Input.dispatchKeyEvent' ? ' ' + params.key : ''} ${took}ms`);
    }
  };

  const failureShot = async name => { try { await saveScreenshot(`failure-edits-${name}.png`, tab); } catch { /* the original failure is the verdict */ } };
  const step = async (name, body) => {
    setStep(`edits_${name}`);
    try { const result = await body(); out.stages[name] = result ?? true; return result; } catch (error) {
      // A page that no longer answers is stopped where it is: the top of its stack says what it was doing.
      let stack = '';
      try {
        const paused = new Promise(resolve => tab.on('Debugger.paused', event => resolve(event.callFrames)));
        await tab.call('Debugger.enable');
        await tab.call('Debugger.pause');
        const frames = await Promise.race([paused, sleep(3000).then(() => [])]);
        stack = frames.length === 0 ? '' : ' | stack: ' + frames.slice(0, 8).map(frame => `${frame.functionName || '?'}@${String(frame.url).split('/').pop()}:${frame.location.lineNumber}`).join(' < ');
        await tab.call('Debugger.resume').catch(() => {});
      } catch { /* the page answered normally */ }
      await failureShot(name);
      const waiting = typeof inflight === 'function' ? inflight().slice(0, 8) : [];
      let slow = waiting.length === 0 ? '' : ' | in flight: ' + waiting.join('; ');
      try {
        const entries = await page('return (globalThis.__long ?? []).slice(-6);');
        slow += entries.length === 0 ? '' : ' | long frames: ' + JSON.stringify(entries);
      } catch { /* the page is still away */ }
      // What the server held at that moment (turns only: no text), to tell a product defect from a slow worker.
      let held = '';
      try {
        const detail = (await api('GET', artifactPath())).body;
        held = ` | server: state ${detail.state}, errorCode ${detail.errorCode}, revisions ${JSON.stringify(detail.revisions.slice(-2))}, turns ${JSON.stringify(detail.turns.slice(-2))}`;
      } catch (reading) { held = ` | server: unreadable (${String(reading?.message ?? reading).slice(0, 60)})`; }
      await writeFile(join(config.output, `failure-edits-${name}.txt`),
        (error instanceof SafeFailure ? error.message : `driver: ${String(error?.message ?? error).slice(0, 160)}`) + held + stack + slow + (slowCalls.length ? ' | slow calls: ' + slowCalls.slice(-6).join('; ') : '') + '\n').catch(() => {});
      throw error;
    }
  };
  const shot = async name => { await saveScreenshot(name, tab); out.screenshots.push(name); };

  // ---- low-level input -------------------------------------------------------------------------------------------------
  const KEYS = { Enter: ['Enter', 'Enter', 13], Escape: ['Escape', 'Escape', 27], Tab: ['Tab', 'Tab', 9] };
  const press = async (name, { modifiers = 0, keyCode } = {}) => {
    const [key, code, base] = KEYS[name];
    const virtualKeyCode = keyCode ?? base;
    const event = { key, code, windowsVirtualKeyCode: virtualKeyCode, nativeVirtualKeyCode: virtualKeyCode, modifiers };
    await tab.call('Page.bringToFront');
    await tab.call('Input.dispatchKeyEvent', name === 'Enter'
      ? { type: 'keyDown', text: '\r', unmodifiedText: '\r', ...event } : { type: 'rawKeyDown', ...event });
    await tab.call('Input.dispatchKeyEvent', { type: 'keyUp', ...event });
  };
  const insertText = async text => { await tab.call('Page.bringToFront'); await tab.call('Input.insertText', { text }); };
  const mouse = (type, point, clickCount = 1) => tab.call('Input.dispatchMouseEvent',
    { type, x: point.x, y: point.y, button: type === 'mouseMoved' ? 'none' : 'left', clickCount });
  /** A real mouse click (`times` = 3 selects a paragraph) at a point of the viewport. */
  const clickAt = async (point, times = 1) => {
    await tab.call('Page.bringToFront');
    await mouse('mouseMoved', point);
    for (let count = 1; count <= times; count++) { await mouse('mousePressed', point, count); await mouse('mouseReleased', point, count); }
    await settle();
  };
  /** The centre of the first visible element with this selector (or button text); the page is scrolled to it. */
  const centreOf = (selector, label = null) => page(`const [selector, label] = args;
    const pool = [...document.querySelectorAll(selector)].filter(node => { const rect = node.getBoundingClientRect(); return rect.width > 0 && rect.height > 0; });
    const node = label === null ? pool[0] : pool.find(item => item.textContent.replace(/\\s+/g, ' ').replaceAll('\\u00a0', ' ').trim() === label);
    if (!node) return null;
    if (node.getAttribute('aria-disabled') === 'true') return 'disabled';
    node.scrollIntoView({ block: 'center', behavior: 'instant' });
    const rect = node.getBoundingClientRect();
    return { x: rect.left + Math.min(rect.width / 2, 40), y: rect.top + Math.min(rect.height / 2, 14), width: rect.width, height: rect.height };`, selector, label);
  const click = async (selector, label = null) => {
    const point = await centreOf(selector, label);
    need(point !== null && point !== 'disabled', `«${label ?? selector}» is ${point === null ? 'absent' : 'disabled'}`);
    await clickAt(point);
  };
  const buttonPoint = async (label, scope) => {
    const point = await page(`const [label, scope] = args;
      const node = [...document.querySelectorAll(scope + ' button')].find(item => item.textContent.replace(/\\s+/g, ' ').replaceAll('\\u00a0', ' ').trim() === label);
      if (!node) return null;
      if (node.getAttribute('aria-disabled') === 'true') return 'disabled';
      node.scrollIntoView({ block: 'center', behavior: 'instant' });
      const rect = node.getBoundingClientRect();
      return { x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 };`, label, scope);
    need(point !== null && point !== 'disabled', `«${label}» is ${point === null ? 'absent' : 'disabled'} in ${scope}`);
    return point;
  };
  const press_ = async (label, scope) => clickAt(await buttonPoint(label, scope));

  // ---- the page and the API ----------------------------------------------------------------------------------------------
  let deck = null;
  let session = null;
  let artifactId = null;
  const sessionPath = id => `/api/decks/${deck.deckId}/generation-sessions/${id}`;
  const artifactPath = () => `${sessionPath(session)}/artifacts/${artifactId}`;
  const getArtifact = async (revisionId = null) => {
    const result = await api('GET', artifactPath() + (revisionId ? `?revisionId=${revisionId}` : ''));
    need(result.status === 200, `GET artifact answered ${result.status}`);
    return result.body;
  };
  const getSession = async () => {
    const result = await api('GET', sessionPath(session));
    need(result.status === 200, `GET session answered ${result.status}`);
    return result.body;
  };
  const credits = async () => {
    const usage = await api('GET', '/api/usage');
    need(usage.status === 200, `GET usage answered ${usage.status}`);
    return usage.body.credits;
  };
  const plain = document => document.root.content.map(node => ({ id: node.id, type: node.type,
    text: (function collect(item) { return item.attrs?.text ?? item.content.map(collect).join(''); })(node) }));
  const turnsOf = async () => (await getArtifact()).turns;
  const widget = () => page(`const text = value => (value ?? '').replace(/\\s+/gu, ' ').replaceAll('\\u00a0', ' ').trim();
    const blocks = [...document.querySelectorAll('app-proposal-document .native-document > [data-node-id]')].map(node => ({
      id: node.dataset.nodeId, tag: node.tagName.toLowerCase(), text: node.textContent.replace(/\\s+/g, ' ').trim(), busy: node.getAttribute('aria-busy'),
      rewriting: node.classList.contains('is-rewriting'), target: node.classList.contains('is-target') }));
    const strip = document.querySelector('app-proposal-document .rewrite-strip');
    const popover = document.querySelector('app-ai-prompt-window [popover]');
    const dialog = document.querySelector('app-ai-prompt-window dialog');
    const group = document.querySelector('app-proposal-document .selection-actions');
    const active = document.activeElement;
    return { blocks, nodeIds: document.querySelectorAll('[data-node-id]').length,
      group: group ? { open: group.matches(':popover-open'), label: group.getAttribute('aria-label'), buttons: [...group.querySelectorAll('button')].map(node => node.textContent.trim()), key: group.querySelector('.group-key')?.textContent ?? null,
        rect: (() => { const rect = group.getBoundingClientRect(); return { top: rect.top, bottom: rect.bottom, left: rect.left, right: rect.right }; })() } : null,
      bar: [...document.querySelectorAll('app-proposal-document .selection-bar button')].map(node => node.textContent.trim()),
      window: popover ? { role: popover.getAttribute('role'), modal: popover.getAttribute('aria-modal'), popover: popover.getAttribute('popover'), open: popover.matches(':popover-open'),
        label: document.getElementById(popover.getAttribute('aria-labelledby'))?.textContent.trim(), presets: [...popover.querySelectorAll('.chip')].map(node => node.textContent.trim()),
        field: popover.querySelector('textarea')?.value ?? null, fieldLabel: popover.querySelector('label')?.textContent.trim(), cost: text(popover.querySelector('.window-cost')?.textContent),
        quote: text(popover.querySelector('.window-quote')?.textContent), error: text(popover.querySelector('.window-error')?.textContent),
        enterkeyhint: popover.querySelector('textarea')?.getAttribute('enterkeyhint'), send: [...popover.querySelectorAll('.window-actions button')].map(node => node.textContent.trim()),
        microphone: Boolean(popover.querySelector('[aria-label*="микрофон" i], [aria-label*="Голос" i]')),
        focusInside: popover.contains(active), focusOnField: active === popover.querySelector('textarea'),
        rect: (() => { const rect = popover.getBoundingClientRect(); return { top: rect.top, bottom: rect.bottom, left: rect.left, right: rect.right }; })() } : null,
      sheet: dialog ? { open: dialog.open, modal: dialog.matches(':modal'), label: document.getElementById(dialog.getAttribute('aria-labelledby'))?.textContent.trim(),
        enterkeyhint: dialog.querySelector('textarea')?.getAttribute('enterkeyhint'), presets: [...dialog.querySelectorAll('.chip')].map(node => node.textContent.trim()),
        cost: text(dialog.querySelector('.window-cost')?.textContent), error: text(dialog.querySelector('.window-error')?.textContent),
        rect: (() => { const rect = dialog.getBoundingClientRect(); return { top: rect.top, bottom: rect.bottom, left: rect.left, right: rect.right, height: rect.height }; })(),
        padding: getComputedStyle(dialog).paddingBottom, focusOnTitle: active?.classList.contains('window-title') } : null,
      highlight: Boolean(CSS.highlights?.has('mnema-ai-target')), highlightApi: Boolean(CSS.highlights),
      strip: strip ? { text: text(strip.textContent), buttons: [...strip.querySelectorAll('button')].map(node => node.textContent.trim()),
        expanded: strip.querySelector('[aria-expanded]')?.getAttribute('aria-expanded') ?? null, failed: strip.classList.contains('is-failed') } : null,
      caption: text(document.querySelector('app-proposal-document .rewriting-caption')?.textContent),
      diff: (() => { const diff = document.querySelector('app-proposal-document .rewrite-diff'); if (!diff) return null;
        return { ins: [...diff.querySelectorAll('ins')].map(node => text(node.textContent)), del: [...diff.querySelectorAll('del')].map(node => text(node.textContent)),
          hidden: [...diff.querySelectorAll('.sr-only')].map(node => text(node.textContent)), label: diff.getAttribute('aria-label'), id: diff.id }; })(),
      history: (() => { const details = document.querySelector('app-proposal-document .edit-history'); if (!details) return null;
        return { open: details.open, summary: text(details.querySelector('summary')?.textContent), items: [...details.querySelectorAll('li')].map(item => ({
          text: text(item.textContent), revert: Boolean([...item.querySelectorAll('button')].find(node => node.textContent.trim() === 'Вернуть к этой версии')), current: item.classList.contains('is-current') })) }; })(),
      hint: text(document.querySelector('app-proposal-view .selection-hint')?.textContent),
      summary: text(document.querySelector('section.workshop .summary')?.textContent), regions: document.querySelectorAll('section.workshop [role=status]').length,
      notice: text(document.querySelector('section.workshop .notice')?.textContent),
      selection: document.getSelection()?.toString() ?? '',
      focus: active === document.body ? 'body' : { tag: active.tagName.toLowerCase(), cls: String(active.className).slice(0, 30), inHost: Boolean(active.closest('.document-host')) } };`);
  const selectionFor = id => page(`const node = document.querySelector('[data-node-id="' + args[0] + '"]'); return node ? { text: node.textContent } : null;`, id);

  /** Installs a recorder of what the page shows while a rewrite runs: busy blocks, the caption, the strip and the summary line. */
  const RECORDER = `
    if (globalThis.__mnemaEdits?.timer) clearInterval(globalThis.__mnemaEdits.timer);
    const recorder = globalThis.__mnemaEdits = { busyIds: [], rewriting: 0, caption: false, windows: 0, strip: [], summaries: [], regions: 0, maxBusyAt: null };
    const startedAt = performance.now();
    recorder.timer = setInterval(() => {
      const busy = [...document.querySelectorAll('app-proposal-document [data-node-id][aria-busy="true"]')].map(node => node.dataset.nodeId);
      for (const id of busy) if (!recorder.busyIds.includes(id)) { recorder.busyIds.push(id); recorder.maxBusyAt ??= Math.round(performance.now() - startedAt); }
      if (document.querySelector('app-proposal-document .is-rewriting')) recorder.rewriting++;
      if (document.querySelector('app-proposal-document .rewriting-caption')) recorder.caption = true;
      const strip = document.querySelector('app-proposal-document .rewrite-strip')?.textContent.replace(/\\s+/g, ' ').trim();
      if (strip && recorder.strip.at(-1) !== strip) recorder.strip.push(strip);
      const summary = (document.querySelector('section.workshop .summary')?.textContent ?? '').replaceAll('\\u00a0', ' ').trim();
      if (summary && recorder.summaries.at(-1) !== summary) recorder.summaries.push(summary);
      recorder.regions = Math.max(recorder.regions, document.querySelectorAll('section.workshop [role=status]').length);
    }, 30);
    return true;`;
  const recorded = () => page('return globalThis.__mnemaEdits ?? null;');

  /** Triple click on the paragraph that starts with `startsWith`: a real mouse selection of one paragraph. */
  const selectParagraph = async startsWith => {
    const point = await page(`const node = [...document.querySelectorAll('app-proposal-document .native-document > p')].find(item => item.textContent.trim().startsWith(args[0]));
      if (!node) return null; node.scrollIntoView({ block: 'center', behavior: 'instant' });
      const rect = node.getBoundingClientRect(); return { x: rect.left + 24, y: rect.top + 12 };`, startsWith);
    need(point !== null, `no paragraph starts with «${startsWith}»`);
    await clickAt(point, 3);
  };
  const waitGroup = async label => {
    try { await until(async () => (await widget()).group?.open === true || (await widget()).bar.length > 0, `${label}: the selection offered no action`, 8_000); } catch (error) {
      const view = await widget();
      const facts = await page(`const popover = document.querySelector('.selection-actions'); return { coarse: matchMedia('(pointer: coarse)').matches, hidden: document.hidden,
        popoverPresent: Boolean(popover), popoverOpen: popover?.matches(':popover-open') ?? null, selectionRanges: getSelection().rangeCount, scrollY: Math.round(scrollY) };`);
      throw new SafeFailure(`${error.message} (selection «${view.selection.slice(0, 40)}», ${JSON.stringify(facts)})`);
    }
  };
  /** The first opening is the keyboard's: Tab from the selection reaches the group (it follows the document in the tab order) and Enter
   *  presses it. Every other opening is a click. Headless Chrome on macOS wedges after synthetic Shift+F10 presses (its native context
   *  menu), so that key is covered by the component specs instead. */
  let keyboardOpened = false;
  const openWindow = async () => {
    if (!keyboardOpened) {
      keyboardOpened = true;
      await press('Tab');
      const focused = await page(`const active = document.activeElement; return { inGroup: Boolean(active?.closest('.selection-actions')), text: (active?.textContent ?? '').trim() };`);
      need(focused.inGroup && focused.text === 'Попросить Мнему…', `Tab from the selection did not reach «Попросить Мнему…» (focus is ${JSON.stringify(focused)})`);
      await press('Enter');
    } else await press_('Попросить Мнему…', 'app-proposal-document .selection-actions');
    await until(async () => (await widget()).window !== null || (await widget()).sheet !== null, 'the selection did not open the window', 8_000);
  };
  /** The box lies inside the viewport (a window that runs off the bottom would clip its button). */
  const inViewport = rect => page(`const [rect] = args; return rect.top >= -1 && rect.left >= -1 && rect.bottom <= innerHeight + 1 && rect.right <= innerWidth + 1;`, rect);
  const currentText = async () => plain((await getArtifact()).revision.payload.document);

  // ======================================================================================================================
  // 0. The fixture: a deck of its own and a proposal made by the Stub
  // ======================================================================================================================
  await desktop();
  await awaitCapability();
  for (const active of await activeSessions()) {
    const gone = await api('DELETE', baseSessionPath(active.sessionId));
    need([204, 404].includes(gone.status), `deleting an earlier session answered ${gone.status}`);
  }
  let initial = null;
  await step('fixture', async () => {
    const created = await api('POST', '/api/decks', { commandId: crypto.randomUUID(), metadata: { title: 'Правки Мнемы: проверка', description: '' } });
    need(created.status === 201, `POST deck answered ${created.status}`);
    deck = { deckId: created.body.deck?.deckId ?? created.body.deckId };
    need(deck.deckId, 'the created deck has no id');
    let attempts = 0;
    for (const prompt of PROMPTS) {
      attempts++;
      const made = await api('POST', `/api/decks/${deck.deckId}/generation-sessions`, { commandId: crypto.randomUUID(), spec: {
        kind: 'MATERIALS', prompt, sources: [], settings: { effort: 'SHORT', notesMode: 'ONE_PER_NOTE',
          media: { audio: { enabled: false, lang: 'ru', voice: null }, imageSearch: false }, factCheck: false, similarToDeck: false, planFirst: false, budgetPercent: null } } });
      need(made.status === 201, `POST generation-sessions answered ${made.status} ${JSON.stringify(made.body?.code ?? made.body?.detail ?? null)}`);
      const id = made.body.sessionId;
      await until(async () => (await api('GET', sessionPath(id))).body.artifacts.every(artifact => ['PROPOSED', 'FAILED'].includes(artifact.state)), 'the proposal did not settle', 90_000);
      const state = (await api('GET', sessionPath(id))).body;
      const candidate = state.artifacts[0];
      need(candidate.state === 'PROPOSED', `the proposal is ${candidate.state} (${candidate.errorCode})`);
      const detail = (await api('GET', `${sessionPath(id)}/artifacts/${candidate.artifactId}`)).body;
      const blocks = plain(detail.revision.payload.document);
      if (blocks.length === 5 && blocks[1].type === 'paragraph' && blocks[1].text === FIRST_PARAGRAPH) {
        session = id; artifactId = candidate.artifactId; initial = { revisionId: detail.currentRevisionId, blocks };
        break;
      }
      const dropped = await api('DELETE', sessionPath(id));
      need([204, 404].includes(dropped.status), `deleting a spare proposal answered ${dropped.status}`);
    }
    need(session !== null, 'the Stub produced no proposal with a heading, a paragraph and two more headings');
    need(detailTurnsEmpty(await getArtifact()), 'a new proposal already has turns');
    return { attempts: attempts, blocks: initial.blocks.map(block => block.type) };
  });
  function detailTurnsEmpty(detail) { return detail.turns.length === 0 && detail.revisions.length === 1; }
  const [headingBlock, paragraphBlock, h2Block, h3Block, lastBlock] = initial.blocks;

  await step('open', async () => {
    await navigate(`/decks/${deck.deckId}/workshop/${session}`, tab);
    await until(() => has('app-proposal-document .native-document'), 'the Workshop did not render the proposal', 30_000);
    // Long animation frames, with the script that held them: if the page ever stops answering, the failure names the function.
    await page(`globalThis.__long = []; try { new PerformanceObserver(list => { for (const entry of list.getEntries()) globalThis.__long.push({ type: entry.entryType, ms: Math.round(entry.duration),
      scripts: (entry.scripts ?? []).map(script => ({ fn: script.sourceFunctionName, invoker: script.invoker, file: String(script.sourceURL).split('/').pop(), at: script.sourceCharPosition, ms: Math.round(script.duration) })).slice(0, 4) }); })
      .observe({ type: 'long-animation-frame', buffered: true }); } catch { /* not supported */ } return true;`);
    const view = await widget();
    need(view.blocks.length === 5, `the Workshop shows ${view.blocks.length} blocks`);
    need(view.blocks.every((block, index) => block.id === initial.blocks[index].id), 'the Workshop blocks do not carry the node ids of the revision');
    need(view.hint === 'Выделите фрагмент текста, чтобы попросить Мнему переписать его. С клавиатуры: Shift+F10 или клавиша меню; Tab после выделения тоже доходит до кнопки, но после ссылок и плееров самого блока.', `the hint is «${view.hint}»`);
    need(view.history === null, 'a proposal without edits shows a history');
    // Node ids belong to the Workshop only: the whole page carries them on the top-level blocks and nowhere else.
    need(view.nodeIds === 5, `${view.nodeIds} elements carry data-node-id`);
    return { blocks: view.blocks.length };
  });

  // ======================================================================================================================
  // 1. Select with the mouse, open the window with the keyboard
  // ======================================================================================================================
  await step('select_and_window', async () => {
    await selectParagraph(FIRST_PARAGRAPH);
    await waitGroup('triple click');
    const grouped = await widget();
    need(grouped.group?.open === true && grouped.group.label === 'Действия с выделенным текстом', `the group is ${JSON.stringify(grouped.group)}`);
    need(grouped.group.buttons.join() === 'Попросить Мнему…', `the group offers ${JSON.stringify(grouped.group.buttons)}`);
    need(grouped.group.key === 'Shift+F10', `the group does not say the keyboard route (${grouped.group.key})`);
    need(await inViewport(grouped.group.rect), `the group runs off the screen: ${JSON.stringify(grouped.group.rect)}`);
    need(grouped.selection.trim() !== '', 'the selection is empty');
    // The window opens from the keyboard (Tab to the group, Enter).
    await openWindow();
    const opened = await widget();
    const win = opened.window;
    need(win !== null && opened.sheet === null, 'the desktop opened a sheet or no window');
    need(win.role === 'dialog' && win.modal === 'false' && win.popover === 'manual' && win.open === true, `the window is ${JSON.stringify({ role: win.role, modal: win.modal, popover: win.popover, open: win.open })}`);
    need(win.label === 'Попросить Мнему', `the window is labelled «${win.label}»`);
    need(win.presets.join() === 'Проще,Короче,Пример,Подробнее', `the presets are ${JSON.stringify(win.presets)}`);
    need(win.fieldLabel === 'Что изменить?', `the field label is «${win.fieldLabel}»`);
    need(win.focusOnField === true, 'focus is not in the field of the window');
    need(win.quote === `Выделено: «${FIRST_PARAGRAPH}»`, `the quote is «${win.quote}»`);
    need(win.microphone === false, 'the window offers a microphone while speech-to-text is off');
    need(win.send.join() === 'Отправить', `the buttons are ${JSON.stringify(win.send)}`);
    await until(async () => /% лимита$/u.test((await widget()).window?.cost ?? ''), 'the cost line did not appear', 10_000);
    const cost = (await widget()).window.cost;
    need(/^(≈ \d+(,\d)? % лимита|менее 0,1 % лимита)$/u.test(cost), `the cost line is «${cost}»`);
    const estimate = await api('POST', `/api/decks/${deck.deckId}/generation-estimates`, { edit: { sessionId: session, artifactId, action: 'REWRITE', targetNodeCount: 1 } });
    need(estimate.status === 200 && estimate.body.credits.p95 > 0, `the real edit estimate answered ${estimate.status}`);
    // The chosen block stays painted while focus is in the window.
    const painted = (await widget());
    need(painted.highlightApi ? painted.highlight === true : painted.blocks.find(block => block.id === paragraphBlock.id).target === true,
      'the chosen block is not painted while the window is open');
    need(await inViewport(win.rect), `the window runs off the screen: ${JSON.stringify(win.rect)}`);
    await shot('workshop-edit-window-1440.png');
    // Esc closes it, returns focus to the document and puts the selection back.
    await press('Escape');
    await until(async () => (await widget()).window === null, 'Esc did not close the window', 5_000);
    const closed = await widget();
    need(closed.focus !== 'body' && closed.focus.inHost === true, `after Esc focus is ${JSON.stringify(closed.focus)}, not the document`);
    need(closed.selection.trim() !== '' && closed.highlight === false, 'after Esc the selection did not come back or the highlight stayed');
    await waitGroup('after Esc');
    await openWindow();
    return { cost, highlightApi: painted.highlightApi, presets: win.presets, quote: win.quote };
  });

  // ======================================================================================================================
  // 2. The field: Shift+Enter is a new line, an IME Enter does not send; then «Проще» sends and the block says it is being rewritten
  // ======================================================================================================================
  let spent0 = null;
  let first = null;
  await step('rewrite_simpler', async () => {
    spent0 = await credits();
    await insertText('слишком сложно');
    await press('Enter', { modifiers: SHIFT });
    await insertText('второй строкой');
    need((await widget()).window.field === 'слишком сложно\nвторой строкой', 'Shift+Enter did not make a new line');
    need((await turnsOf()).length === 0, 'Shift+Enter sent the edit');
    await page(`globalThis.__mnemaKeys = []; window.addEventListener('keydown', event => globalThis.__mnemaKeys.push({ key: event.key, composing: event.isComposing, keyCode: event.keyCode, prevented: event.defaultPrevented })); return true;`);
    await tab.call('Input.imeSetComposition', { text: 'にほん', selectionStart: 3, selectionEnd: 3 });
    await press('Enter');
    const composing = await page('return globalThis.__mnemaKeys;');
    need(composing.some(entry => entry.key === 'Enter' && entry.composing === true), `no composing Enter was dispatched: ${JSON.stringify(composing)}`);
    need(composing.filter(entry => entry.key === 'Enter' && entry.composing).every(entry => entry.prevented === false), 'an Enter during an IME composition was handled as a send');
    await tab.call('Input.imeSetComposition', { text: '', selectionStart: 0, selectionEnd: 0 });
    await press('Enter', { keyCode: 229 });
    need((await turnsOf()).length === 0, 'an IME Enter sent the edit');
    // Clear the field (it is sent with the preset otherwise): select all and delete.
    await page(`const field = document.querySelector('app-ai-prompt-window textarea'); field.focus(); field.select(); return true;`);
    await tab.call('Input.dispatchKeyEvent', { type: 'rawKeyDown', key: 'Backspace', code: 'Backspace', windowsVirtualKeyCode: 8 });
    await tab.call('Input.dispatchKeyEvent', { type: 'keyUp', key: 'Backspace', code: 'Backspace', windowsVirtualKeyCode: 8 });
    need((await widget()).window.field === '', 'the field could not be cleared');
    await page(RECORDER);
    await press_('Проще', 'app-ai-prompt-window');
    await until(async () => (await widget()).window === null, 'the window did not close after «Проще»', 10_000);
    // Rewriting: the blocks of the range say so while the turn runs.
    await until(async () => (await recorded()).busyIds.length > 0, 'no block was marked aria-busy while the rewrite ran', 10_000);
    const rewriting = await recorded();
    need(rewriting.busyIds.join() === paragraphBlock.id, `the busy blocks are ${JSON.stringify(rewriting.busyIds)}`);
    await until(async () => (await widget()).strip !== null, 'the strip «Переписано» did not appear', 60_000);
    const done = await widget();
    const after = await getArtifact();
    const blocks = plain(after.revision.payload.document);
    need(after.turns.length === 1 && after.turns[0].status === 'APPLIED' && after.turns[0].preset === 'SIMPLER' && after.turns[0].action === 'REWRITE',
      `the turn is ${JSON.stringify(after.turns)}`);
    need(after.revisions.map(revision => revision.cause).join() === 'INITIAL,EDIT', `the revisions are ${JSON.stringify(after.revisions.map(revision => revision.cause))}`);
    // Acceptance 1: only the chosen block changed; every other block keeps its node id and its text.
    need(blocks.length === 5 && blocks.every((block, index) => block.id === initial.blocks[index].id), 'a node id changed');
    for (const index of [0, 2, 3, 4]) need(blocks[index].text === initial.blocks[index].text, `block ${index} changed`);
    need(blocks[1].text === `${FIRST_PARAGRAPH} Переписано: Проще.`, `the rewritten paragraph reads «${blocks[1].text}»`);
    need(done.blocks.map(block => block.id).join() === initial.blocks.map(block => block.id).join(), 'the page shows other node ids than before');
    need(done.blocks[1].text === blocks[1].text, `the page shows «${done.blocks[1].text}»`);
    need(done.blocks.every(block => block.busy === null && !block.rewriting), 'a block is still marked busy after the rewrite');
    need(done.strip.buttons.join() === 'Показать изменения,Оставить,Вернуть,Ещё раз', `the strip offers ${JSON.stringify(done.strip.buttons)}`);
    need(done.strip.text.startsWith('Переписано'), `the strip reads «${done.strip.text}»`);
    need(rewriting.rewriting > 0 && rewriting.caption === true, 'the rewriting block showed no dashed frame or caption');
    // The end reached the summary line, which is the page's only live region.
    await until(async () => (await recorded()).summaries.some(line => line.includes('Мнема переписала фрагмент.')), 'the summary line never said the fragment was rewritten', 12_000);
    const regions = (await recorded()).regions;
    need(regions === 1, `the Workshop has ${regions} role=status regions during an edit`);
    first = { revisionId: after.currentRevisionId, text: blocks[1].text };
    const spent = await credits();
    need(spent.used > spent0.used && spent.reserved === 0, `the edit was not debited exactly once (used ${spent0.used} -> ${spent.used}, reserved ${spent.reserved})`);
    return { busyAfterMs: rewriting.maxBusyAt, strip: done.strip.buttons, debited: spent.used - spent0.used, summaries: (await recorded()).summaries };
  });

  // ======================================================================================================================
  // 3. «Показать изменения»: the inline word diff
  // ======================================================================================================================
  await step('diff', async () => {
    await press_('Показать изменения', 'app-proposal-document .rewrite-strip');
    await until(async () => (await widget()).diff !== null, 'the diff did not appear', 10_000);
    const shown = await widget();
    need(shown.strip.expanded === 'true' && shown.strip.buttons[0] === 'Скрыть изменения', 'the toggle does not say the diff is shown');
    need(shown.diff.ins.join() === 'добавлено: Переписано: Проще.' && shown.diff.del.length === 0, `the diff is ${JSON.stringify(shown.diff)}`);
    need(shown.diff.hidden.join() === 'добавлено:', `the screen-reader prefixes are ${JSON.stringify(shown.diff.hidden)}`);
    need(shown.blocks.every(block => block.id !== paragraphBlock.id), 'the rewritten block is drawn next to its diff');
    need(shown.blocks.length === 4, `the page shows ${shown.blocks.length} blocks beside the diff`);
    const controls = await page(`const button = document.querySelector('.rewrite-strip [aria-expanded]'); return button.getAttribute('aria-controls') === document.querySelector('.rewrite-diff').id;`);
    need(controls, 'aria-controls does not point at the diff');
    await shot('workshop-edit-diff-1440.png');
    await press_('Скрыть изменения', 'app-proposal-document .rewrite-strip');
    await until(async () => (await widget()).diff === null, 'the diff did not close', 5_000);
    need((await widget()).blocks.some(block => block.id === paragraphBlock.id), 'the block did not come back');
    return { ins: shown.diff.ins };
  });

  // ======================================================================================================================
  // 4. «Ещё раз» (its own reservation), «Вернуть» (history kept), «Оставить»
  // ======================================================================================================================
  await step('again_and_undo', async () => {
    const before = await credits();
    await page(RECORDER);
    await press_('Ещё раз', 'app-proposal-document .rewrite-strip');
    await until(async () => (await getArtifact()).turns.length === 2, 'the second turn was not made', 15_000);
    await until(async () => (await getArtifact()).turns[1].status === 'APPLIED', 'the second turn did not finish', 60_000);
    await until(async () => (await widget()).strip !== null && (await widget()).blocks[1].text.includes('Переписано: Проще. Переписано: Проще.'), 'the page does not show the second rewrite', 15_000);
    const second = await getArtifact();
    need(second.revisions.length === 3, `${second.revisions.length} revisions after «Ещё раз»`);
    need(second.turns[1].preset === 'SIMPLER' && second.turns[1].turnId !== second.turns[0].turnId, 'the second turn is not the same request as a new turn');
    const spent = await credits();
    need(spent.used > before.used && spent.reserved === 0, `«Ещё раз» did not take its own reservation (used ${before.used} -> ${spent.used}, reserved ${spent.reserved})`);
    need((await recorded()).busyIds.join() === paragraphBlock.id, 'the second rewrite did not mark its block busy');
    // «Вернуть»: back to the revision the second rewrite started from; nothing is deleted.
    await press_('Вернуть', 'app-proposal-document .rewrite-strip');
    await until(async () => (await getArtifact()).currentRevisionId === first.revisionId, 'the pointer did not move back', 15_000);
    await until(async () => (await widget()).strip === null && (await widget()).blocks[1].text === first.text, 'the page does not show the earlier revision', 15_000);
    const back = await getArtifact();
    need(back.revisions.length === 3 && back.turns.length === 2, `history was deleted: ${back.revisions.length} revisions, ${back.turns.length} turns`);
    await until(async () => (await recorded()).summaries.some(line => line.includes('Вернули выбранную версию.')), 'the summary line did not say the version was restored', 12_000);
    return { revisions: back.revisions.length, turns: back.turns.length, undoneTo: 'the first result' };
  });

  await step('history', async () => {
    const view = await widget();
    need(view.history !== null && view.history.summary === 'История правок (2)', `the history says «${view.history?.summary}»`);
    await click('app-proposal-document .edit-history summary');
    await until(async () => (await widget()).history.open === true, 'the history did not open', 5_000);
    const opened = await widget();
    need(opened.history.items.length === 3, `the history lists ${opened.history.items.length} entries`);
    need(opened.history.items[0].text.startsWith('Исходная версия') && opened.history.items[0].revert, 'the first revision cannot be restored');
    need(opened.history.items[1].current === true && opened.history.items[1].revert === false, 'the shown version is not marked current');
    need(opened.history.items[2].revert === true, 'the later version cannot be restored (a revert goes forward too)');
    // Back to the original text through the history.
    await press_('Вернуть к этой версии', 'app-proposal-document .edit-history li:first-child');
    await until(async () => (await getArtifact()).currentRevisionId === initial.revisionId, 'the history did not restore the first revision', 15_000);
    await until(async () => (await widget()).blocks[1].text === FIRST_PARAGRAPH, 'the page does not show the original text', 15_000);
    const restored = await getArtifact();
    need(restored.revisions.length === 3, 'restoring deleted a revision');
    return { entries: opened.history.items.length };
  });

  // ======================================================================================================================
  // 5. A refused rewrite: the reason in words, the text unchanged, nothing debited
  // ======================================================================================================================
  await step('failed_rewrite', async () => {
    const before = await credits();
    await selectParagraph(FIRST_PARAGRAPH);
    await waitGroup('before the refusal');
    await openWindow();
    await insertText('[[stub:refusal]] объясни иначе');
    await page(RECORDER);
    await press('Enter');
    await until(async () => (await widget()).window === null, 'the window did not close after Enter', 10_000);
    await until(async () => (await widget()).strip?.failed === true, 'the failed rewrite shows no strip', 60_000);
    const view = await widget();
    need(view.strip.text.includes('Мнема отказалась переписывать этот фрагмент.') && view.strip.text.includes('Текст не изменился'), `the failure reads «${view.strip.text}»`);
    need(view.strip.buttons.join() === 'Ещё раз,Закрыть', `the failed strip offers ${JSON.stringify(view.strip.buttons)}`);
    need(view.blocks[1].text === FIRST_PARAGRAPH, 'a failed rewrite changed the text');
    const detail = await getArtifact();
    need(detail.currentRevisionId === initial.revisionId && detail.turns.at(-1).status === 'FAILED' && detail.turns.at(-1).errorCode === 'REFUSAL', `the artifact is ${JSON.stringify(detail.turns.at(-1))}`);
    const spent = await credits();
    need(spent.used === before.used && spent.reserved === 0, `a failed rewrite was charged (used ${before.used} -> ${spent.used}, reserved ${spent.reserved})`);
    await until(async () => (await recorded()).summaries.some(line => line.includes('Не удалось переписать фрагмент: текст не изменился.')), 'the summary line did not say the rewrite failed', 12_000);
    await press_('Закрыть', 'app-proposal-document .rewrite-strip');
    await until(async () => (await widget()).strip === null, 'the failed strip did not close', 5_000);
    return { errorCode: 'REFUSAL', charged: 0 };
  });

  // ======================================================================================================================
  // 6. A phone: a bar at the bottom opens a bottom sheet (a coarse pointer)
  // ======================================================================================================================
  await step('mobile_sheet', async () => {
    await metrics(390, 844, 1, true);
    await tab.call('Emulation.setTouchEmulationEnabled', { enabled: true, maxTouchPoints: 1 });
    await navigate(`/decks/${deck.deckId}/workshop/${session}`, tab);
    await until(() => has('app-proposal-document .native-document'), 'the Workshop did not reload at 390 px', 30_000);
    const coarse = await page(`return matchMedia('(pointer: coarse)').matches;`);
    need(coarse, 'the emulated phone does not have a coarse pointer (the harness cannot prove the sheet)');
    // Select a paragraph the way the platform does it for a script: the system menu belongs to the browser, the bar to the page.
    await page(`const node = [...document.querySelectorAll('app-proposal-document .native-document > p')].find(item => item.textContent.trim().startsWith(args[0]));
      node.scrollIntoView({ block: 'center', behavior: 'instant' });
      const range = document.createRange(); range.selectNodeContents(node); const selection = getSelection(); selection.removeAllRanges(); selection.addRange(range); return true;`, FIRST_PARAGRAPH);
    await until(async () => (await widget()).bar.length === 1, 'no bar appeared at the bottom for a selection', 8_000);
    const bar = await page(`const node = document.querySelector('app-proposal-document .selection-bar'); const rect = node.getBoundingClientRect(); const style = getComputedStyle(node);
      return { bottom: Math.round(rect.bottom), height: Math.round(rect.height), viewport: innerHeight, position: style.position, padding: style.paddingBottom,
        floating: Boolean(document.querySelector('app-proposal-document .selection-actions')), button: node.querySelector('button').getBoundingClientRect().height };`);
    need(bar.position === 'fixed' && bar.bottom === bar.viewport, `the bar is ${JSON.stringify(bar)}, not fixed at the bottom`);
    need(bar.floating === false, 'a floating group was drawn on a touch screen');
    need(bar.button >= 43.5, `the bar button is ${bar.button}px high`);
    await click('app-proposal-document .selection-bar button');
    await until(async () => (await widget()).sheet?.open === true, 'the bar did not open the sheet', 8_000);
    await until(async () => /% лимита$/u.test((await widget()).sheet?.cost ?? ''), 'the sheet shows no cost line', 10_000);
    const sheet = (await widget()).sheet;
    need(sheet.modal === true, 'the sheet is not a modal dialog (showModal)');
    need(sheet.label === 'Попросить Мнему', `the sheet is labelled «${sheet.label}»`);
    need(sheet.enterkeyhint === 'send', `the field has enterkeyhint=${sheet.enterkeyhint}`);
    need(sheet.presets.join() === 'Проще,Короче,Пример,Подробнее', `the sheet presets are ${JSON.stringify(sheet.presets)}`);
    need(Math.round(sheet.rect.bottom) === bar.viewport && sheet.rect.left === 0 && Math.round(sheet.rect.right) === 390, `the sheet sits at ${JSON.stringify(sheet.rect)}`);
    need(sheet.rect.height <= 844 * 0.85 + 1, `the sheet is ${sheet.rect.height}px high`);
    need(sheet.focusOnTitle === true, 'focus did not go to the title of the sheet');
    const overflow = await page(`return document.documentElement.scrollWidth > document.documentElement.clientWidth;`);
    need(!overflow, 'the sheet overflows horizontally');
    await shot('workshop-edit-sheet-390.png');
    await page(RECORDER);
    await press_('Короче', 'app-ai-prompt-window');
    await until(async () => (await widget()).sheet === null, 'the sheet did not close after «Короче»', 10_000);
    await until(async () => (await recorded()).busyIds.length > 0, 'no block was busy on the phone', 10_000);
    await shot('workshop-edit-rewriting-390.png');
    await until(async () => (await widget()).strip !== null, 'the strip did not appear on the phone', 60_000);
    const view = await widget();
    need(view.blocks[1].text === `${FIRST_PARAGRAPH} Переписано: Короче.`, `the phone shows «${view.blocks[1].text}»`);
    need((await recorded()).busyIds.join() === paragraphBlock.id, 'the phone showed no busy block');
    const wide = await page(`return document.documentElement.scrollWidth > document.documentElement.clientWidth;`);
    need(!wide, 'the strip overflows horizontally at 390 px');
    const small = await page(`return [...document.querySelectorAll('.rewrite-strip button')].map(node => Math.round(node.getBoundingClientRect().height)).filter(height => height < 44);`);
    need(small.length === 0, `strip buttons below 44 px at 390 px: ${JSON.stringify(small)}`);
    await shot('workshop-edit-strip-390.png');
    await press_('Показать изменения', 'app-proposal-document .rewrite-strip');
    await until(async () => (await widget()).diff !== null, 'the diff did not appear on the phone', 10_000);
    need((await widget()).diff.ins.join() === 'добавлено: Переписано: Короче.', 'the phone shows another diff');
    await shot('workshop-edit-diff-390.png');
    await press_('Скрыть изменения', 'app-proposal-document .rewrite-strip');
    await tab.call('Emulation.setTouchEmulationEnabled', { enabled: false });
    await desktop(); await settle();
    return { barBottom: bar.bottom, sheetHeight: Math.round(sheet.rect.height), enterkeyhint: sheet.enterkeyhint };
  });

  // ======================================================================================================================
  // 7. A second edit while the first runs: 409 EDIT_IN_PROGRESS, and the window says why
  // ======================================================================================================================
  await step('edit_in_progress', async () => {
    await navigate(`/decks/${deck.deckId}/workshop/${session}`, tab);
    await until(() => has('app-proposal-document .native-document'), 'the Workshop did not reload', 30_000);
    await selectParagraph(lastBlock.text.slice(0, 12));
    await waitGroup('before the slow edit');
    await openWindow();
    need((await widget()).window !== null, 'the window did not open on the last paragraph');
    // The page stops hearing about the session (its polling requests fail, nothing is made up): it still believes the artifact is proposed.
    await tab.call('Network.setBlockedURLs', { urls: [`*/generation-sessions/${session}/events*`] });
    try {
      const slow = await api('POST', `${artifactPath()}/edits`, { commandId: crypto.randomUUID(), expectedRevisionId: (await getArtifact()).currentRevisionId,
        action: 'FREE', target: { nodeIds: [lastBlock.id] }, instruction: SLOW });
      need(slow.status === 202 && slow.body.turn.status === 'QUEUED', `the slow edit answered ${slow.status}`);
      await press_('Подробнее', 'app-ai-prompt-window');
      await until(async () => /ещё переписывает предыдущий фрагмент/u.test((await widget()).window?.error ?? ''), 'the window did not explain the running edit', 15_000);
      const view = await widget();
      need(view.window.error === 'Мнема ещё переписывает предыдущий фрагмент — дождитесь окончания.', `the window says «${view.window.error}»`);
      need(view.window.send.join() === 'Отправить', 'the window is not ready for another try after the refusal');
      // The window stays open for as long as the user does not close it, even though the page now knows the artifact is being rewritten.
      await sleep(1500);
      need((await widget()).window?.error?.startsWith('Мнема ещё переписывает'), 'the explaining window did not stay open');
      need(await inViewport(view.window.rect), `the window with its message runs off the screen: ${JSON.stringify(view.window.rect)}`);
      await shot('workshop-edit-in-progress-1440.png');
    } finally {
      await tab.call('Network.setBlockedURLs', { urls: [] });
    }
    // The page hears again: the block is marked as being rewritten, and «Стоп» takes the turn back.
    await until(async () => (await widget()).blocks.some(block => block.busy === 'true'), 'the page did not show the running rewrite once it heard again', 30_000);
    // «×» closes the explaining window; the block that is being rewritten is then in view on its own.
    await click('app-ai-prompt-window .window-close');
    await until(async () => (await widget()).window === null, 'the window did not close with «×»', 5_000);
    await shot('workshop-edit-rewriting-1440.png');
    const turns = await turnsOf();
    need(turns.at(-1).status === 'QUEUED' || turns.at(-1).status === 'RUNNING', `the slow turn is ${turns.at(-1).status}`);
    await press_('Стоп', 'section.workshop .actions');
    await until(async () => (await turnsOf()).at(-1).status === 'CANCELLED', 'the turn was not cancelled by «Стоп»', 30_000);
    const after = await getArtifact();
    need(after.currentRevisionId === first.revisionId || after.currentRevisionId === initial.revisionId || after.revisions.some(revision => revision.revisionId === after.currentRevisionId),
      'the artifact moved to an unknown revision');
    const states = (await getSession()).artifacts.map(artifact => artifact.state);
    need(states.join() === 'PROPOSED', `the artifact is ${states.join()} after the cancellation`);
    await until(async () => (await widget()).blocks.every(block => block.busy === null), 'the block stays busy after the cancellation', 20_000);
    const spent = await credits();
    need(spent.reserved === 0, `a cancelled turn still holds ${spent.reserved} credits`);
    return { turn: 'CANCELLED', reserved: spent.reserved };
  });

  // ======================================================================================================================
  // 8. After a cancellation no rewrite is offered, the rewritten proposal is approved, and Browse shows it without node ids
  // ======================================================================================================================
  await step('approve_and_browse', async () => {
    await selectParagraph(FIRST_PARAGRAPH);
    await sleep(600);
    const none = await widget();
    need(none.group === null && none.window === null && none.hint === '', 'a cancelled session still offers a rewrite');
    // The current revision is the first result of the phone's «Короче»: approve exactly what is shown.
    const shown = (await widget()).blocks[1].text;
    await press_('Одобрить и далее →', 'app-proposal-view .proposal-actions');
    await until(async () => (await getSession()).artifacts[0].state === 'PUBLISHED', 'the proposal was not approved', 30_000);
    const published = (await getSession()).artifacts[0].publishedRef;
    need(published?.kind === 'ITEM', 'the approval published no material');
    await navigate(`/decks/${deck.deckId}/materials/${published.memberKey}`, tab);
    await until(() => has('app-native-document-renderer article'), 'Browse did not render the material', 25_000);
    const browse = await page(`return { nodeIds: document.querySelectorAll('[data-node-id]').length, text: document.querySelector('app-native-document-renderer article').textContent.replace(/\\s+/g, ' ').trim(),
      busy: document.querySelectorAll('[aria-busy="true"], .is-rewriting, .is-target').length };`);
    need(browse.nodeIds === 0 && browse.busy === 0, `Browse carries ${browse.nodeIds} node ids and ${browse.busy} Workshop marks`);
    need(browse.text.includes(shown), `Browse shows «${browse.text.slice(0, 80)}», not the approved «${shown}»`);
    return { nodeIdsInBrowse: browse.nodeIds, approved: shown };
  });

  out.durationMs = Date.now() - startedAt;
  return out;
}
