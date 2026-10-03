// Real-browser check of the generation composer and the Workshop (#289) against the real Learning API with the Stub text
// provider (`--generation`; never a real provider, no key). Runs after every other authoring scenario on the signed-in account's
// tab, in the base deck. Node 24 built-ins only.
//
// Everything the user sees comes from the real Angular UI and the real HTTP surface of #287: the composer's preflight is the real
// `POST .../generation-estimates`, the Workshop is fed by real `.../events` polling, «Стоп» is a real cancellation. Only the notes
// that give the batch several materials are created through the authenticated API (the composer's note chips belong to #290).
// A broken step is a finding for the product, never something to work around: it throws, a failure screenshot is written and the
// run fails with the step name.
//
// Part A (this file, `runWorkshop`): composer, streaming, pager, persistence, stop, failures, deck entry, responsive and
// accessibility evidence. Part B (`runWorkshopApproval`, needs the #288 backend: approve, bulk approve, reject/undo, hand-off,
// retry, delete) plugs in behind a function boundary and is called from `runWorkshop` with the state part A leaves behind.
import { writeFile } from 'node:fs/promises';
import { join } from 'node:path';

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

const FIRST_PROMPT = 'Глаголы движения в японском';
const SECOND_LINE = 'Примеры из аниме';
/** The Stub fails or stalls on a marker anywhere in the prompt (`StubTextAdapter`); notes reach the prompt as untrusted blocks. */
const SLOW_MARKER = '[[stub:rate-limit]]';
const NOTES = [
  { label: 'ok-1', text: 'Слово «кормить»: 食べさせる, причинительный залог. Нужны два примера.' },
  { label: 'ok-2', text: 'Частица に и で: куда и где. Коротко и с примерами.' },
  { label: 'timeout', text: 'Заметка про いる и ある [[stub:timeout]] с отметкой для провайдера.' },
  { label: 'refusal', text: 'Заметка про вежливые формы [[stub:refusal]] с отметкой для провайдера.' }
];

/** Page-side recorder installed before the session is created, so the whole life of the Workshop is observed. */
const RECORDER = `
  const recorder = globalThis.__mnemaWorkshop = { startedAt: performance.now(), arrivals: [], busyTrue: 0, busyFalse: 0,
    summaries: [], maxBlocks: 0, dotStates: [], maxStatusRegions: 0, samples: 0 };
  const sample = () => {
    recorder.samples++;
    const at = Math.round(performance.now() - recorder.startedAt);
    const workshop = document.querySelector('section.workshop');
    if (!workshop) return;
    const article = workshop.querySelector('app-proposal-view article');
    const busy = article?.getAttribute('aria-busy');
    if (busy === 'true') recorder.busyTrue++; else if (busy === 'false') recorder.busyFalse++;
    const summary = (workshop.querySelector('.summary')?.textContent ?? '').replaceAll('\\u00a0', ' ').trim();
    if (summary && recorder.summaries.at(-1)?.text !== summary) recorder.summaries.push({ at, text: summary });
    const blocks = workshop.querySelectorAll('.draft .native-document > *, .final .native-document > *, .final article > *').length;
    recorder.maxBlocks = Math.max(recorder.maxBlocks, blocks);
    const dots = [...workshop.querySelectorAll('.dot')].map(dot => dot.dataset.status + (dot.getAttribute('aria-current') === 'step' ? '*' : '')).join(',');
    if (dots && recorder.dotStates.at(-1)?.dots !== dots) recorder.dotStates.push({ at, dots });
    recorder.maxStatusRegions = Math.max(recorder.maxStatusRegions, workshop.querySelectorAll('[role=status]').length);
  };
  new MutationObserver(records => {
    for (const record of records) {
      const target = record.target;
      if (record.type === 'attributes' && record.attributeName === 'class' && target instanceof Element && target.classList.contains('is-arriving')) {
        recorder.arrivals.push({ at: Math.round(performance.now() - recorder.startedAt), tag: target.tagName.toLowerCase() });
      }
    }
    sample();
  }).observe(document.body, { subtree: true, childList: true, characterData: true, attributes: true,
    attributeFilter: ['class', 'aria-busy', 'aria-current', 'data-status', 'data-state'] });
  recorder.timer = setInterval(sample, 40);
  return true;`;

const KEYS = {
  Enter: ['Enter', 'Enter', 13], Tab: ['Tab', 'Tab', 9], ArrowRight: ['ArrowRight', 'ArrowRight', 39], ArrowLeft: ['ArrowLeft', 'ArrowLeft', 37],
  Home: ['Home', 'Home', 36], End: ['End', 'End', 35], Escape: ['Escape', 'Escape', 27]
};
const SHIFT = 8;

export async function runWorkshop(ctx) {
  const { tab, config, record, SafeFailure, until, exists, navigate, saveScreenshot, setStep, bearer, deckPath } = ctx;
  const need = (value, label) => { if (!value) throw new SafeFailure(label); };
  const page = (body, ...args) => tab.callFunction(`async function(...args) { ${body} }`, args);
  const has = selector => exists(selector, tab);
  const deckId = deckPath.split('/').filter(Boolean).at(-1);
  const shared = { deckId, deckPath, sessions: {}, notes: [] };
  const evidence = { stub: true, provider: 'stub', plan: null, stages: {} };

  // ---- low-level helpers -------------------------------------------------------------------------------------------
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
  const metrics = (width, height, scale = 1, mobile = false) =>
    tab.call('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: scale, mobile });
  let media = { reduced: false, forced: false };
  const emulate = async next => {
    media = { ...media, ...next };
    const features = [];
    if (media.reduced) features.push({ name: 'prefers-reduced-motion', value: 'reduce' });
    if (media.forced) features.push({ name: 'forced-colors', value: 'active' });
    await tab.call('Emulation.setEmulatedMedia', { features });
  };
  const settle = () => page('return new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve(true))));');
  const failureShot = async name => {
    try { await saveScreenshot(`failure-workshop-${name}.png`, tab); } catch { /* the original failure is the verdict */ }
  };
  const stage = async (name, body) => {
    setStep(`workshop_${name}`);
    try { const result = await body(); evidence.stages[name] = result ?? true; return result; } catch (error) {
      await failureShot(name);
      await writeFile(join(config.output, `failure-workshop-${name}.txt`),
        (error instanceof SafeFailure ? error.message : `driver: ${String(error?.message ?? error).slice(0, 160)}`) + '\n').catch(() => {});
      throw error;
    }
  };

  /** The real API, called with the page's own bearer (no cookie). `body` is serialised here. */
  const api = (method, path, body) => page(`
    const response = await fetch(args[0] + args[2], { method: args[1], credentials: 'omit',
      headers: { Authorization: args[3], ...(args[4] === null ? {} : { 'Content-Type': 'application/json' }) },
      body: args[4] === null ? undefined : args[4] });
    const text = await response.text();
    let parsed = null; try { parsed = text ? JSON.parse(text) : null; } catch { parsed = { unparsable: text.slice(0, 80) }; }
    return { status: response.status, body: parsed, etag: response.headers.get('ETag'), cache: response.headers.get('Cache-Control') };`,
  config.frontend, method, path, 'Bearer ' + bearer, body === undefined ? null : JSON.stringify(body));
  const sessionPath = id => `/api/decks/${deckId}/generation-sessions/${id}`;
  const getSession = async id => {
    const result = await api('GET', sessionPath(id));
    need(result.status === 200, `GET generation session answered ${result.status}`);
    return result.body;
  };
  const artifactSnapshot = session => session.artifacts.map(artifact => ({ ordinal: artifact.ordinal, artifactId: artifact.artifactId,
    state: artifact.state, errorCode: artifact.errorCode ?? null, title: artifact.title }));
  const activeSessions = async () => {
    const result = await api('GET', `/api/decks/${deckId}/generation-sessions?active=true&limit=20`);
    need(result.status === 200, `GET active generation sessions answered ${result.status}`);
    return result.body.items;
  };
  const sessionIdFromPath = path => path.match(/\/workshop\/([0-9a-f-]{36})$/u)?.[1] ?? null;
  const location = () => page('return location.pathname + location.search;');

  // ---- composer helpers ---------------------------------------------------------------------------------------------
  const composerState = () => page(`
    const textarea = document.querySelector('app-generation-composer textarea');
    const root = document.querySelector('app-generation-composer');
    return { present: Boolean(root), editor: Boolean(document.querySelector('app-item-editor-page')),
      greeting: root?.querySelector('h1 label')?.textContent.trim() ?? null, labelFor: root?.querySelector('h1 label')?.getAttribute('for') ?? null,
      textareaId: textarea?.id ?? null, value: textarea?.value ?? null, active: document.activeElement === textarea,
      estimate: (root?.querySelector('.estimate')?.textContent ?? '').replaceAll('\\u00a0', ' ').trim(),
      statusRegions: root?.querySelectorAll('[role=status]').length ?? -1,
      busy: root?.querySelector('section.composer')?.getAttribute('aria-busy') ?? null,
      button: root?.querySelector('.generate-cta')?.textContent.trim() ?? null,
      path: location.pathname };`);
  const focusPrompt = () => page(`const textarea = document.querySelector('app-generation-composer textarea');
    if (!(textarea instanceof HTMLTextAreaElement)) return false;
    textarea.focus(); textarea.setSelectionRange(textarea.value.length, textarea.value.length); return document.activeElement === textarea;`);
  const clearPrompt = async () => {
    need(await focusPrompt(), 'the composer prompt field could not take focus');
    await page(`document.querySelector('app-generation-composer textarea').select(); return true;`);
    await tab.call('Input.dispatchKeyEvent', { type: 'rawKeyDown', key: 'Backspace', code: 'Backspace', windowsVirtualKeyCode: 8 });
    await tab.call('Input.dispatchKeyEvent', { type: 'keyUp', key: 'Backspace', code: 'Backspace', windowsVirtualKeyCode: 8 });
  };
  const KEY_LOG = `
    if (!globalThis.__mnemaKeys) { globalThis.__mnemaKeys = [];
      window.addEventListener('keydown', event => { if (event.target instanceof HTMLTextAreaElement) globalThis.__mnemaKeys.push({
        key: event.key, shift: event.shiftKey, composing: event.isComposing, keyCode: event.keyCode, prevented: event.defaultPrevented }); }); }
    globalThis.__mnemaKeys.length = 0; return true;`;
  const keyLog = () => page('return globalThis.__mnemaKeys ?? [];');
  /** The `[[stub:timeout]]` material trips the provider's circuit breaker (five transport failures in 60 s): the capability reads
   *  TEMPORARILY_UNAVAILABLE for about 30 s and the composer correctly gives way to the editor. Wait it out before opening it. */
  const awaitCapability = async () => {
    const started = Date.now();
    await until(async () => (await api('GET', '/api/capabilities')).body?.aiGeneration?.available === true,
      'aiGeneration did not become available again', 90_000);
    const waited = Date.now() - started;
    if (waited > 1500) evidence.circuitWaitMs = Math.max(evidence.circuitWaitMs ?? 0, waited);
  };
  const waitComposer = async () => {
    try { await until(() => has('app-generation-composer textarea'), 'x', 25_000); } catch {
      const now = await api('GET', '/api/capabilities');
      const seen = await page(`return performance.getEntriesByType('resource').filter(entry => /capabilities|decks\\/[0-9a-f-]{36}$/u.test(entry.name)).map(entry => entry.name.split('/api/')[1] + ' ' + entry.responseStatus);`);
      throw new SafeFailure(`the composer did not open (page requests ${JSON.stringify(seen)}); GET /api/capabilities answers ${now.status} ${JSON.stringify(now.body?.aiGeneration ?? now.body)}`);
    }
    await until(async () => (await composerState()).greeting !== null, 'the composer greeting is absent');
  };

  /** Layout facts of the current page: horizontal overflow, small primary targets, every control below 44 px. */
  const layout = () => page(`
    const doc = document.documentElement;
    const main = document.querySelector('main') ?? document.body;
    const visible = element => { const rect = element.getBoundingClientRect(); const style = getComputedStyle(element);
      return rect.width > 0 && rect.height > 0 && style.visibility !== 'hidden' && style.display !== 'none'; };
    const controls = [...main.querySelectorAll('a[href], button, textarea, select, input, summary')].filter(visible);
    const small = controls.map(element => { const rect = element.getBoundingClientRect();
      const inline = element.matches('a') && Boolean(element.closest('p, li')) && !element.matches('.button');
      return { tag: element.tagName.toLowerCase(), cls: String(element.className).slice(0, 30), text: (element.textContent ?? '').trim().slice(0, 24),
        w: Math.round(rect.width), h: Math.round(rect.height), inline }; })
      .filter(entry => entry.h < 44 || entry.w < 44);
    const primarySelector = '.generate-cta, .button, .step, .dot, textarea';
    const primary = controls.filter(element => element.matches(primarySelector))
      .map(element => { const rect = element.getBoundingClientRect(); return { cls: String(element.className).slice(0, 30), text: (element.textContent ?? '').trim().slice(0, 24), w: Math.round(rect.width), h: Math.round(rect.height) }; })
      .filter(entry => entry.h < 43.5 || (entry.w < 43.5 && !entry.cls.includes('generate')));
    const wide = [...main.querySelectorAll('*')].filter(element => visible(element) && element.getBoundingClientRect().right > doc.clientWidth + 1)
      .map(element => element.tagName.toLowerCase() + '.' + String(element.className).split(' ')[0]).slice(0, 4);
    return { innerWidth, dpr: devicePixelRatio, scrollWidth: doc.scrollWidth, clientWidth: doc.clientWidth,
      overflow: doc.scrollWidth > doc.clientWidth, fontPx: parseFloat(getComputedStyle(doc).fontSize),
      smallPrimary: primary, smallOthers: small.filter(entry => !entry.inline).slice(0, 8), wide };`);

  /** Real Tab presses from the top of the page: every stop of the page must show a visible focus indicator. */
  const focusSweep = async (limit = 14) => {
    await page(`document.activeElement?.blur?.(); scrollTo(0, 0); return true;`);
    const stops = [];
    for (let index = 0; index < limit; index++) {
      await press('Tab');
      const stop = await page(`const element = document.activeElement;
        if (!element || element === document.body) return null;
        const style = getComputedStyle(element);
        const outline = style.outlineStyle !== 'none' && parseFloat(style.outlineWidth) > 0;
        const shadow = style.boxShadow !== 'none';
        const inMain = Boolean(element.closest('main'));
        return { tag: element.tagName.toLowerCase(), cls: String(element.className).slice(0, 24), focusVisible: element.matches(':focus-visible'),
          outline, shadow, inMain, label: (element.getAttribute('aria-label') ?? element.textContent ?? '').trim().slice(0, 28) };`);
      if (stop === null) break;
      stops.push(stop);
    }
    return stops;
  };
  const assertFocusVisible = (label, stops) => {
    need(stops.length >= 3, `${label}: Tab reached only ${stops.length} focusable controls`);
    const bad = stops.filter(stop => stop.inMain && !(stop.focusVisible && (stop.outline || stop.shadow)));
    need(bad.length === 0, `${label}: no visible focus indicator on ${bad.map(stop => `${stop.tag}.${stop.cls}«${stop.label}»`).join(', ')}`);
  };

  const widths = [{ w: 1440, h: 900, scale: 1, mobile: false, tag: '1440' }, { w: 768, h: 1024, scale: 1, mobile: false, tag: '768' },
    { w: 390, h: 844, scale: 1, mobile: true, tag: '390' }, { w: 320, h: 800, scale: 1, mobile: false, tag: '320' }];
  const desktop = () => metrics(1440, 900, 1, false);

  /** Screenshots and geometry at every width; hard failures for overflow at 320..1440 and for small primary targets. */
  const responsiveSet = async (prefix, plan) => {
    const result = {};
    for (const { w, h, scale, mobile, tag } of widths.filter(entry => plan.includes(entry.tag))) {
      await metrics(w, h, scale, mobile); await settle();
      const report = await layout();
      need(!report.overflow, `${prefix} overflows horizontally at ${w} CSS px (${report.scrollWidth} > ${report.clientWidth}: ${report.wide.join(' ')})`);
      need(report.smallPrimary.length === 0, `${prefix} has primary targets below 44 px at ${w} px: ${JSON.stringify(report.smallPrimary)}`);
      await saveScreenshot(`workshop-${prefix}-${tag}.png`, tab);
      result[tag] = { overflow: false, belowFortyFour: report.smallOthers.map(entry => `${entry.tag}.${entry.cls} «${entry.text}» ${entry.w}x${entry.h}`) };
    }
    if (plan.includes('320')) {
      // 2x root text on a 320 px window (WCAG 1.4.4 / 1.4.10), then 200 % browser zoom on a 320 px window = 160 CSS px at DPR 2.
      await metrics(320, 800, 1, false);
      await page(`document.documentElement.style.fontSize = '32px'; return true;`); await settle();
      const doubled = await layout();
      need(doubled.fontPx >= 31, `${prefix}: 2x root text did not apply`);
      need(!doubled.overflow, `${prefix} overflows horizontally at 320 px with 2x root text (${doubled.wide.join(' ')})`);
      await saveScreenshot(`workshop-${prefix}-320-2x-text.png`, tab);
      await page(`document.documentElement.style.fontSize = ''; return true;`);
      await metrics(160, 800, 2, false); await settle();
      const zoomed = await layout();
      await saveScreenshot(`workshop-${prefix}-320-at-200-percent.png`, tab);
      result['2x-text'] = { overflow: false }; result['200-percent-zoom'] = { overflow: zoomed.overflow, wide: zoomed.wide };
      await desktop();
    }
    await desktop(); await settle();
    return result;
  };

  // =====================================================================================================================
  // Stage 1: the composer, through the real UI
  // =====================================================================================================================
  await emulate({ reduced: false, forced: false });
  await desktop();
  // The capability-off path first, against the ordinary Learning of this run (generation off, as shipped).
  await stage('capability_off', async () => {
    const off = await api('GET', '/api/capabilities');
    need(off.status === 200 && off.body?.aiGeneration?.available === false, `the ordinary Learning reports aiGeneration ${JSON.stringify(off.body?.aiGeneration ?? null)}`);
    await navigate(`${deckPath}/materials/new`, tab);
    await until(() => has('app-item-editor-page'), 'the plain editor did not open with aiGeneration off', 25_000);
    const state = await page(`return { composer: Boolean(document.querySelector('app-generation-composer')),
      note: document.querySelector('.ai-note')?.textContent.trim() ?? null, editor: Boolean(document.querySelector('app-item-editor-page')) };`);
    need(!state.composer && state.editor, 'the composer opened although aiGeneration is off');
    need(state.note?.startsWith('Помощник Мнема сейчас недоступен'), `the calm note is «${state.note}»`);
    await navigate(deckPath, tab);
    await until(() => has('nav.hub-actions'), 'the Deck page did not load');
    need(!(await has('app-deck-workshops section.workshops')), 'the Deck page lists Workshops although aiGeneration is off');
    return { aiGeneration: off.body.aiGeneration, plainEditor: true, note: state.note, composer: false, deckWorkshops: false };
  });
  evidence.capabilityOff = evidence.stages.capability_off;
  // From here on the proxy forwards to the second Learning of this run: the Stub text provider, aiGeneration on.
  const switched = await page(`const response = await fetch('/__fixture/learning-generation', { method: 'POST' }); return response.status;`);
  need(switched === 204, `the proxy did not switch to the Stub Learning (status ${switched})`);
  const capabilities = await api('GET', '/api/capabilities');
  need(capabilities.status === 200 && capabilities.body?.aiGeneration?.available === true,
    `GET /api/capabilities does not report aiGeneration available (Stub configuration): ${JSON.stringify(capabilities.body?.aiGeneration ?? null)}`);
  evidence.capability = { aiGeneration: capabilities.body.aiGeneration, status: 200 };
  const usage = await api('GET', '/api/usage');
  need(usage.status === 200, 'GET /api/usage is not available');
  evidence.plan = usage.body.plan;
  const before = await activeSessions();
  need(before.length === 0, `the account already has ${before.length} active generation sessions`);

  await stage('composer', async () => {
    await navigate(`${deckPath}/materials/new`, tab);
    await waitComposer();
    const opened = await composerState();
    need(!opened.editor, 'the plain editor opened although aiGeneration is available');
    need(/что будем учить сегодня\?$/u.test(opened.greeting ?? ''), `the greeting is «${opened.greeting}»`);
    need(opened.labelFor === opened.textareaId && opened.textareaId, 'the greeting is not the visible label of the request field');
    need(opened.statusRegions === 1, `the composer has ${opened.statusRegions} role=status regions, not one`);
    need(opened.estimate === '', `the preflight is shown before anything is typed («${opened.estimate}»)`);
    // The greeting heading takes focus on navigation (and the field is reachable right after).
    const heading = await page(`return document.activeElement?.tagName === 'H1' || document.activeElement === document.querySelector('app-generation-composer textarea');`);
    need(heading, 'neither the greeting nor the request field has focus after opening the composer');

    // Preflight: «≈ N % лимита» appears after the debounce, from the real estimate.
    need(await focusPrompt(), 'the prompt field could not take focus');
    const typedAt = Date.now();
    await insertText(FIRST_PROMPT);
    const seen = [];
    let readyAfter = null;
    for (let index = 0; index < 120; index++) {
      const text = (await composerState()).estimate;
      if (seen.at(-1) !== text) seen.push(text);
      if (/лимита$/u.test(text)) { readyAfter = Date.now() - typedAt; break; }
      await sleep(50);
    }
    need(readyAfter !== null, `the preflight did not appear (saw ${JSON.stringify(seen)})`);
    need(seen[0] === 'Считаем…' || seen.length === 1, `the preflight did not pass through «Считаем…» (${JSON.stringify(seen)})`);
    need(readyAfter >= 380, `the preflight appeared after ${readyAfter} ms: no debounce`);
    const shown = (await composerState()).estimate;
    const estimate = await api('POST', `/api/decks/${deckId}/generation-estimates`, { spec: {
      kind: 'MATERIALS', prompt: FIRST_PROMPT, sources: [], settings: { effort: 'AUTO', notesMode: 'ONE_PER_NOTE',
        media: { audio: { enabled: false, lang: 'ru', voice: null }, imageSearch: false }, factCheck: false, similarToDeck: true,
        planFirst: false, budgetPercent: null } } });
    need(estimate.status === 200, `POST generation-estimates answered ${estimate.status} ${JSON.stringify(estimate.body)?.slice(0, 300)}`);
    const percent = estimate.body.percentOfPeriodAllowance.p95;
    const expected = percent < 1 ? 'менее 1 % лимита' : `≈ ${percent} % лимита`;
    need(shown === expected, `the preflight says «${shown}», the real estimate says «${expected}»`);
    need(estimate.body.canStart === true, 'the real estimate says the request cannot start');

    // Responsive geometry of the composer with a filled prompt and the preflight on screen.
    evidence.composerResponsive = await responsiveSet('composer', ['1440', '390', '320']);
    await desktop();

    // Keyboard: Shift+Enter is a newline and sends nothing.
    const sessionsBefore = (await api('GET', `/api/decks/${deckId}/generation-sessions?limit=20`)).body.items.length;
    await page(KEY_LOG);
    need(await focusPrompt(), 'the prompt field could not take focus for Shift+Enter');
    await press('Enter', { modifiers: SHIFT });
    await insertText(SECOND_LINE);
    const afterShift = await composerState();
    const shiftLog = await keyLog();
    need(afterShift.value === `${FIRST_PROMPT}\n${SECOND_LINE}`, `Shift+Enter did not insert a newline (value ${JSON.stringify(afterShift.value)})`);
    need(shiftLog.length === 1 && shiftLog[0].key === 'Enter' && shiftLog[0].shift === true && shiftLog[0].prevented === false,
      `Shift+Enter keydown was prevented or missing: ${JSON.stringify(shiftLog)}`);
    need(afterShift.path === `${deckPath}/materials/new`, 'Shift+Enter navigated away');

    // IME: a composition (compositionstart ... ) and an Enter that confirms it must not send the form.
    await page(KEY_LOG);
    await page(`globalThis.__mnemaComposition = []; const textarea = document.querySelector('app-generation-composer textarea');
      for (const type of ['compositionstart', 'compositionupdate', 'compositionend']) textarea.addEventListener(type, () => globalThis.__mnemaComposition.push(type));
      return true;`);
    await tab.call('Input.imeSetComposition', { text: 'にほん', selectionStart: 3, selectionEnd: 3 });
    await press('Enter');
    const composing = await keyLog();
    const compositionEvents = await page('return globalThis.__mnemaComposition;');
    need(compositionEvents.includes('compositionstart'), 'the synthetic IME did not start a composition (harness cannot prove the guard)');
    need(composing.some(entry => entry.key === 'Enter' && entry.composing === true), `no Enter keydown was dispatched with isComposing=true: ${JSON.stringify(composing)}`);
    need(composing.filter(entry => entry.key === 'Enter' && entry.composing).every(entry => entry.prevented === false),
      'an Enter pressed during a composition was handled as a send');
    need((await composerState()).path === `${deckPath}/materials/new`, 'Enter during an IME composition submitted the form');
    await tab.call('Input.imeSetComposition', { text: '', selectionStart: 0, selectionEnd: 0 });
    // Safari reports the confirming Enter as keyCode 229 without isComposing.
    await page(KEY_LOG);
    await press('Enter', { keyCode: 229 });
    const legacy = await keyLog();
    need(legacy.some(entry => entry.keyCode === 229 && entry.prevented === false), `keyCode 229 Enter was handled as a send: ${JSON.stringify(legacy)}`);
    const sessionsDuring = (await api('GET', `/api/decks/${deckId}/generation-sessions?limit=20`)).body.items.length;
    need(sessionsDuring === sessionsBefore, 'a session was created by Shift+Enter or an IME Enter');
    // The IME run may have left text in the field: put the exact prompt back.
    await clearPrompt();
    await insertText(FIRST_PROMPT); await press('Enter', { modifiers: SHIFT }); await insertText(SECOND_LINE);
    need((await composerState()).value === `${FIRST_PROMPT}\n${SECOND_LINE}`, 'the prompt could not be restored after the IME check');
    evidence.composer = { greeting: opened.greeting, preflightSeen: seen, preflightMs: readyAfter, preflightText: shown, apiPercentP95: percent,
      shiftEnterNewline: true, imeComposition: { compositionEvents: [...new Set(compositionEvents)], enterKeydown: composing.filter(entry => entry.key === 'Enter') },
      keyCode229: true, noSessionFromKeys: true, statusRegions: 1 };
    return { greeting: true };
  });

  // =====================================================================================================================
  // Stage 2: Enter sends; the Workshop opens and the real events stream in
  // =====================================================================================================================
  await stage('stream', async () => {
    await page(RECORDER);
    await page(KEY_LOG);
    need(await focusPrompt(), 'the prompt field could not take focus before sending');
    await press('Enter');
    const sentAt = Date.now();
    await until(async () => sessionIdFromPath(await location()) !== null, 'Enter did not open the Workshop', 20_000);
    const sessionId = sessionIdFromPath(await location());
    shared.sessions.composer = sessionId;
    const enterKey = (await keyLog()).at(-1);
    need(enterKey?.key === 'Enter' && enterKey.prevented === true, 'the Enter that sends was not handled by the composer');
    await until(() => has('section.workshop app-batch-pager .dot'), 'the Workshop did not render its pager');
    evidence.workshopOpenedMs = Date.now() - sentAt;
    const finished = async () => ['REVIEW', 'CLOSED'].includes((await getSession(sessionId)).state);
    await until(finished, 'the Stub session did not reach REVIEW', 60_000);
    // Let the poller deliver the last events, then read what the recorder saw.
    await until(async () => (await page(`return document.querySelector('section.workshop .summary')?.textContent ?? ''`)).includes('готово'),
      'the Workshop summary never said «готово»', 20_000);
    await sleep(400);
    const recorded = await page(`const recorder = globalThis.__mnemaWorkshop;
      return { arrivals: recorder.arrivals, busyTrue: recorder.busyTrue, busyFalse: recorder.busyFalse, summaries: recorder.summaries,
        maxBlocks: recorder.maxBlocks, dotStates: recorder.dotStates, statusRegions: recorder.maxStatusRegions };`);
    const session = await getSession(sessionId);
    need(session.artifacts.length === 1 && session.artifacts[0].state === 'PROPOSED', `the Stub batch is not one PROPOSED artifact: ${JSON.stringify(artifactSnapshot(session))}`);
    need(session.spec.prompt === `${FIRST_PROMPT}\n${SECOND_LINE}`, 'the stored prompt differs from what was typed');
    const ui = await page(`const workshop = document.querySelector('section.workshop');
      const article = workshop.querySelector('app-proposal-view article');
      const statusRegions = [...workshop.querySelectorAll('[role=status]')];
      const dots = [...workshop.querySelectorAll('.dot')].map(dot => ({ status: dot.dataset.status, current: dot.getAttribute('aria-current'),
        label: dot.getAttribute('aria-label'), hasSvg: Boolean(dot.querySelector('svg')), tabindex: dot.getAttribute('tabindex') }));
      return { heading: workshop.querySelector('h1')?.textContent.trim(), summary: (workshop.querySelector('.summary')?.textContent ?? '').replaceAll('\\u00a0', ' ').trim(),
        busy: article?.getAttribute('aria-busy'), regions: statusRegions.length, regionInsideSummary: statusRegions.every(region => region.classList.contains('summary')),
        dots, position: workshop.querySelector('.pager .position')?.textContent.trim(), h2: article?.querySelector('h2')?.textContent.trim(),
        text: (article?.querySelector('.final')?.textContent ?? '').trim().length, state: article?.dataset.state,
        approve: [...article.querySelectorAll('button')].map(button => button.textContent.trim()) };`);
    need(ui.heading === 'Мастерская', `the Workshop heading is «${ui.heading}»`);
    need(ui.regions === 1 && ui.regionInsideSummary, `the Workshop has ${ui.regions} role=status regions (expected the one summary)`);
    need(ui.busy === 'false', `aria-busy is «${ui.busy}» after the batch finished`);
    need(ui.summary === '1 готово', `the summary says «${ui.summary}»`);
    need(ui.dots.length === 1 && ui.dots[0].current === 'step' && ui.dots[0].status === 'ready' && ui.dots[0].hasSvg
      && ui.dots[0].label === 'Материал 1 из 1, готов', `the pager dot is wrong: ${JSON.stringify(ui.dots)}`);
    need(ui.position === '1 из 1', `the pager position says «${ui.position}»`);
    need(ui.text > 20, 'the material text did not render');
    need(ui.state === 'PROPOSED', `the shown artifact state is ${ui.state}`);
    need(recorded.statusRegions === 1, `the Workshop showed ${recorded.statusRegions} role=status regions at some moment`);
    need(recorded.summaries.length >= 1, 'the recorder saw no summary text');
    // The Stub answers instantly, so the live phase is short; what was seen is evidence, not an assumption.
    evidence.stream = { sessionId: 'composer', events: 'real GET .../events polling', summaries: recorded.summaries, dotStates: recorded.dotStates,
      busyTrueSamples: recorded.busyTrue, busyFalseSamples: recorded.busyFalse, maxBlocksSeen: recorded.maxBlocks,
      isArrivingApplications: recorded.arrivals.length, finalSummary: ui.summary, heading: ui.heading, statusRegions: 1,
      pagerDot: ui.dots[0], position: ui.position, ariaBusyAfter: ui.busy, shown: ui.h2 };
    await saveScreenshot('workshop-stub-single-1440.png', tab);
    return { arrivals: recorded.arrivals.length };
  });

  // =====================================================================================================================
  // Stage 3: a batch with several materials (notes through the API), a refusal and a timeout; pager and keyboard
  // =====================================================================================================================
  await stage('batch', async () => {
    for (const note of NOTES) {
      const created = await api('POST', '/api/capture-notes', { commandId: crypto.randomUUID(), deckId, source: 'workshop-harness', text: note.text });
      need(created.status === 201, `POST capture-notes answered ${created.status}`);
      shared.notes.push({ label: note.label, noteId: created.body.capture.noteId, rowVersion: String(created.body.capture.rowVersion) });
    }
    const created = await api('POST', `/api/decks/${deckId}/generation-sessions`, { commandId: crypto.randomUUID(), spec: {
      kind: 'MATERIALS', prompt: 'Объясни коротко', sources: shared.notes.map(note => ({ role: 'SOURCE', type: 'NOTE', noteId: note.noteId, noteRowVersion: note.rowVersion })),
      settings: { effort: 'SHORT', notesMode: 'ONE_PER_NOTE', media: { audio: { enabled: false, lang: 'ru', voice: null }, imageSearch: false },
        factCheck: false, similarToDeck: false, planFirst: false, budgetPercent: null } } });
    need(created.status === 201, `POST generation-sessions answered ${created.status}: ${JSON.stringify(created.body?.code ?? created.body)}`);
    const sessionId = created.body.sessionId ?? created.body.session?.sessionId;
    need(typeof sessionId === 'string', 'the created session has no id');
    shared.sessions.batch = sessionId;
    need(created.body.artifacts.length === NOTES.length, `the batch has ${created.body.artifacts.length} artifacts, not ${NOTES.length}`);
    await navigate(`${deckPath}/workshop/${sessionId}`, tab);
    await until(() => has('section.workshop app-batch-pager .dot'), 'the batch Workshop did not render its pager');
    const terminal = session => session.artifacts.every(artifact => ['PROPOSED', 'FAILED'].includes(artifact.state));
    await until(async () => terminal(await getSession(sessionId)), 'the batch did not settle (every artifact PROPOSED or FAILED)', 120_000);
    const session = await getSession(sessionId);
    const states = artifactSnapshot(session);
    evidence.batchApi = states.map(({ ordinal, state, errorCode }) => ({ ordinal, state, errorCode }));
    need(states[0].state === 'PROPOSED' && states[1].state === 'PROPOSED', `notes 1 and 2 are not PROPOSED: ${JSON.stringify(evidence.batchApi)}`);
    need(states[2].state === 'FAILED' && states[3].state === 'FAILED', `notes 3 and 4 did not fail: ${JSON.stringify(evidence.batchApi)}`);
    need(states[3].errorCode === 'REFUSAL', `the refusal note failed with ${states[3].errorCode}`);
    shared.batchStates = states;
    await until(async () => (await page(`return document.querySelector('section.workshop .summary')?.textContent ?? ''`)).includes('не удались'),
      'the Workshop summary did not report the failed materials', 20_000);

    const dotsInfo = () => page(`const workshop = document.querySelector('section.workshop');
      return { dots: [...workshop.querySelectorAll('.dot')].map(dot => ({ status: dot.dataset.status, current: dot.getAttribute('aria-current'),
          label: dot.getAttribute('aria-label'), tabindex: dot.getAttribute('tabindex') })),
        position: workshop.querySelector('.pager .position')?.textContent.trim(), h2: workshop.querySelector('app-proposal-view h2')?.textContent.replace(/\\s+/g, ' ').trim(),
        summary: (workshop.querySelector('.summary')?.textContent ?? '').replaceAll('\\u00a0', ' ').trim(),
        activeLabel: document.activeElement?.getAttribute?.('aria-label') ?? null, activeIsDot: document.activeElement?.classList?.contains('dot') ?? false,
        failure: workshop.querySelector('#failure-title')?.textContent.trim() ?? null,
        failureNote: workshop.querySelector('.notice.error p:not(#failure-title)')?.textContent.trim() ?? null,
        buttons: [...workshop.querySelectorAll('app-proposal-view .proposal-actions button, app-proposal-view .proposal-actions a')].map(item => item.textContent.trim()),
        urlN: new URL(location.href).searchParams.get('n') };`);
    const initial = await dotsInfo();
    need(initial.dots.length === 4, `the pager has ${initial.dots.length} dots, not 4`);
    need(initial.dots.filter(dot => dot.current === 'step').length === 1, 'the pager does not have exactly one aria-current=step dot');
    need(initial.summary === '2 готово · 2 не удались', `the batch summary says «${initial.summary}»`);
    need(JSON.stringify(initial.dots.map(dot => dot.status)) === JSON.stringify(['ready', 'ready', 'failed', 'failed']),
      `the dot shapes are ${initial.dots.map(dot => dot.status)}`);
    need(initial.dots[0].current === 'step' && initial.position === '1 из 4', 'the first material to review is not selected');
    need(initial.dots[2].label === 'Материал 3 из 4, не удался', `a failed dot is named «${initial.dots[2].label}»`);
    await page(`document.querySelector('.dot[aria-current=step]').focus(); return true;`);
    const keyed = [];
    for (const [key, expectedPosition] of [['ArrowRight', '2 из 4'], ['End', '4 из 4'], ['ArrowLeft', '3 из 4'], ['Home', '1 из 4']]) {
      await press(key);
      await until(async () => (await dotsInfo()).position === expectedPosition, `${key} did not move the pager to ${expectedPosition}`, 5_000);
      const info = await dotsInfo();
      need(info.activeIsDot, `after ${key} focus is not on a pager dot`);
      need(info.dots.filter(dot => dot.current === 'step').length === 1 && info.dots.filter(dot => dot.tabindex === '0').length === 1,
        `after ${key} the dots do not have one current step and one tab stop`);
      keyed.push({ key, position: info.position, h2: info.h2, failure: info.failure, buttons: info.buttons, urlN: info.urlN, activeLabel: info.activeLabel });
    }
    // Failed materials show their reason; retry only where the server allows it (never after a refusal).
    await press('End');
    await until(async () => (await dotsInfo()).position === '4 из 4', 'End did not reach the refusal', 5_000);
    const refusal = await dotsInfo();
    need(refusal.failure === 'Мнема отказалась писать на эту тему. Измените запрос или напишите материал сами.', `the refusal reason is «${refusal.failure}»`);
    need(refusal.failureNote === 'За этот материал лимит не списан.', `the refusal note is «${refusal.failureNote}»`);
    need(!refusal.buttons.includes('Попробовать снова') && refusal.buttons.includes('Написать самому'), `refusal actions: ${JSON.stringify(refusal.buttons)}`);
    await press('ArrowLeft');
    await until(async () => (await dotsInfo()).position === '3 из 4', 'ArrowLeft did not reach the timed-out material', 5_000);
    const timeout = await dotsInfo();
    need(typeof timeout.failure === 'string' && timeout.failure.length > 5, 'the timed-out material shows no reason');
    need(timeout.buttons.includes('Попробовать снова'), `the retryable failure does not offer «Попробовать снова» (${JSON.stringify(timeout.buttons)})`);
    evidence.batch = { notes: NOTES.map(note => note.label), artifacts: 4, keyboard: keyed, summary: initial.summary,
      dotShapes: initial.dots.map(dot => dot.status), refusal: { code: states[3].errorCode, reason: refusal.failure, note: refusal.failureNote, actions: refusal.buttons },
      retryable: { code: states[2].errorCode, reason: timeout.failure, actions: timeout.buttons, retryRequiresBackend: '#288 (assert presence only)' },
      urlPosition: keyed.at(-1).urlN };
    await press('Home');
    await until(async () => (await dotsInfo()).position === '1 из 4', 'Home did not return to the first material', 5_000);
    return { dots: 4 };
  });

  // =====================================================================================================================
  // Stage 4: leave and come back; reload; the batch is intact. The Deck page lists the Workshops.
  // =====================================================================================================================
  const snapshotUi = () => page(`const workshop = document.querySelector('section.workshop');
    return { dots: [...workshop.querySelectorAll('.dot')].map(dot => dot.getAttribute('aria-label')),
      summary: (workshop.querySelector('.summary')?.textContent ?? '').replaceAll('\\u00a0', ' ').trim(),
      position: workshop.querySelector('.pager .position')?.textContent.trim(),
      textLength: (workshop.querySelector('app-proposal-view .final')?.textContent ?? '').replace(/\\s+/g, ' ').trim().length,
      head: (workshop.querySelector('app-proposal-view .final')?.textContent ?? '').replace(/\\s+/g, ' ').trim().slice(0, 60) };`);
  await stage('persistence', async () => {
    const before = { api: artifactSnapshot(await getSession(shared.sessions.batch)), ui: await snapshotUi() };
    // Leave through the Workshop's own link (a real click), land on the Deck page.
    need(await page(`const link = [...document.querySelectorAll('section.workshop a.text-link')].find(item => item.textContent.trim() === 'Выйти в колоду');
      if (!link) return false; link.click(); return true;`), '«Выйти в колоду» is absent from the Workshop');
    await until(async () => (await location()) === deckPath, 'the link did not return to the Deck page');
    await until(() => has('app-deck-workshops section.workshops'), 'the Deck page does not list the active Workshops');
    const listed = await page(`const section = document.querySelector('app-deck-workshops section.workshops');
      return { heading: section.querySelector('h2')?.textContent.replace(/\\s+/g, ' ').trim(),
        links: [...section.querySelectorAll('a')].map(link => ({ href: new URL(link.href).pathname, text: link.textContent.trim() })) };`);
    const active = await activeSessions();
    need(active.length === 2, `the account has ${active.length} active sessions, not 2`);
    need(listed.heading === 'Мастерская: 2 активные', `the Deck page says «${listed.heading}»`);
    need(listed.links.length === 2 && listed.links.every(link => /^Мастерская от \d+ [а-я]+/u.test(link.text)), `the Deck page links are not «Мастерская от <день> <месяц по-русски> …»: ${JSON.stringify(listed.links.map(link => link.text))}`);
    // Come back through the Deck page's link to the batch (a real click).
    const target = `${deckPath}/workshop/${shared.sessions.batch}`;
    need(listed.links.some(link => link.href === target), 'the Deck page does not link to the batch Workshop');
    need(await page(`const link = [...document.querySelectorAll('app-deck-workshops a')].find(item => new URL(item.href).pathname === args[0]);
      link.click(); return true;`, target), 'the Deck page link could not be clicked');
    await until(async () => (await location()).startsWith(target) && await has('section.workshop app-batch-pager .dot'), 'the Deck page link did not reopen the Workshop');
    await until(async () => (await snapshotUi()).summary.includes('не удались'), 'the reopened Workshop did not show the batch');
    const afterLeave = { api: artifactSnapshot(await getSession(shared.sessions.batch)), ui: await snapshotUi() };
    need(JSON.stringify(afterLeave.api) === JSON.stringify(before.api), 'the artifacts changed while the Workshop was closed');
    need(JSON.stringify(afterLeave.ui) === JSON.stringify(before.ui), `the Workshop differs after leaving and returning: ${JSON.stringify(afterLeave.ui)} vs ${JSON.stringify(before.ui)}`);
    // Full reload.
    await tab.call('Page.reload', { ignoreCache: false });
    await until(async () => has('section.workshop app-batch-pager .dot') && (await snapshotUi()).summary.includes('не удались'), 'the Workshop did not come back after a reload', 25_000);
    const afterReload = { api: artifactSnapshot(await getSession(shared.sessions.batch)), ui: await snapshotUi() };
    need(JSON.stringify(afterReload.api) === JSON.stringify(before.api), 'the artifacts changed across a reload');
    need(JSON.stringify(afterReload.ui) === JSON.stringify(before.ui), `the Workshop differs after a reload: ${JSON.stringify(afterReload.ui)} vs ${JSON.stringify(before.ui)}`);
    evidence.persistence = { leftThroughLink: '«Выйти в колоду»', deckHeading: listed.heading, deckLinks: listed.links.length,
      returnedThroughDeckLink: true, reloaded: true, artifactsUnchanged: before.api.length, uiUnchanged: true, summary: before.ui.summary };
    evidence.deckEntry = { heading: listed.heading, links: listed.links.map(link => link.text), workingLink: true };
    return { artifacts: before.api.length };
  });

  // =====================================================================================================================
  // Stage 5: «Стоп» on a running session
  // =====================================================================================================================
  await stage('stop', async () => {
    await awaitCapability();
    await navigate(`${deckPath}/materials/new`, tab);
    await waitComposer();
    need(await focusPrompt(), 'the prompt field could not take focus');
    await insertText(`${SLOW_MARKER} глаголы движения`);
    await until(async () => /лимита$/u.test((await composerState()).estimate), 'the preflight did not appear for the slow request');
    await press('Enter');
    await until(async () => sessionIdFromPath(await location()) !== null, 'Enter did not open the Workshop for the slow request', 20_000);
    const sessionId = sessionIdFromPath(await location());
    shared.sessions.stopped = sessionId;
    await until(() => page(`return [...document.querySelectorAll('section.workshop button')].some(button => button.textContent.trim() === 'Стоп');`),
      '«Стоп» is not offered while the session runs', 15_000);
    const running = await getSession(sessionId);
    need(['RUNNING', 'PLANNING', 'PLAN_READY'].includes(running.state), `the session was already ${running.state} when «Стоп» was pressed`);
    const busy = await page(`return document.querySelector('section.workshop app-proposal-view article')?.getAttribute('aria-busy') ?? null;`);
    const label = await page(`return [...document.querySelectorAll('section.workshop button')].find(button => button.textContent.trim() === 'Стоп')?.getAttribute('aria-label');`);
    need(label === 'Стоп: остановить генерацию', `the stop button is named «${label}»`);
    need(await page(`const button = [...document.querySelectorAll('section.workshop button')].find(item => item.textContent.trim() === 'Стоп'); button.click(); return true;`), '«Стоп» could not be pressed');
    await until(async () => (await getSession(sessionId)).state === 'CANCELLED', 'the API session did not become CANCELLED', 20_000);
    const cancelled = await getSession(sessionId);
    await until(async () => (await page(`return document.body.innerText;`)).includes('Вы остановили мастерскую'), 'the Workshop did not say it was stopped', 15_000);
    const after = await page(`return { stopOffered: [...document.querySelectorAll('section.workshop button')].some(button => button.textContent.trim() === 'Стоп'),
      failure: document.querySelector('section.workshop #failure-title')?.textContent.trim() ?? null,
      busy: document.querySelector('section.workshop app-proposal-view article')?.getAttribute('aria-busy') ?? null };`);
    need(!after.stopOffered, '«Стоп» is still offered after the session was cancelled');
    need(after.failure === 'Остановлено вами.', `the stopped material says «${after.failure}»`);
    need(cancelled.artifacts[0].state === 'FAILED' && cancelled.artifacts[0].errorCode === 'CANCELLED', `the artifact is ${JSON.stringify(artifactSnapshot(cancelled))}`);
    evidence.stop = { marker: SLOW_MARKER, slowViaStub: 'rate-limit retries keep the step running for several seconds',
      stateWhenPressed: running.state, ariaBusyWhenPressed: busy, apiStateAfter: cancelled.state, artifact: { state: cancelled.artifacts[0].state, code: cancelled.artifacts[0].errorCode },
      uiNote: 'Вы остановили мастерскую…', reason: after.failure };
    await saveScreenshot('workshop-stopped-1440.png', tab);
    return { cancelled: true };
  });

  // =====================================================================================================================
  // Stage 6: responsive, reduced motion, forced colours, focus and targets on the batch Workshop
  // =====================================================================================================================
  await stage('accessibility', async () => {
    await navigate(`${deckPath}/workshop/${shared.sessions.batch}`, tab);
    await until(async () => has('section.workshop app-batch-pager .dot') && (await snapshotUi()).summary.includes('не удались'), 'the batch Workshop did not load');
    await page(`document.querySelector('.dot[aria-current=step]').click(); return true;`);
    evidence.workshopResponsive = await responsiveSet('page', ['1440', '768', '390', '320']);

    // Focus is visible on every stop at 1440 and at 390.
    await desktop(); await settle();
    const stops = await focusSweep(14);
    assertFocusVisible('Workshop at 1440', stops);
    await metrics(390, 844, 1, true); await settle();
    const stopsMobile = await focusSweep(14);
    assertFocusVisible('Workshop at 390', stopsMobile);
    await saveScreenshot('workshop-focus-390.png', tab);
    await desktop(); await settle();
    evidence.focus = { stops1440: stops.length, stops390: stopsMobile.length, everyStopVisible: true };

    // Reduced motion: no smooth scrolling, no authored animation or transition longer than a blink, in the composer and the Workshop.
    await emulate({ reduced: true });
    const motion = () => page(`const all = [...document.querySelectorAll('main *')];
      const seconds = value => Math.max(0, ...String(value).split(',').map(part => part.trim().endsWith('ms') ? parseFloat(part) / 1000 : parseFloat(part) || 0));
      const longest = (property) => Math.max(0, ...all.map(element => seconds(getComputedStyle(element)[property])));
      return { reduced: matchMedia('(prefers-reduced-motion: reduce)').matches, scrollBehavior: getComputedStyle(document.documentElement).scrollBehavior,
        animation: longest('animationDuration'), transition: longest('transitionDuration') };`);
    const reducedWorkshop = await motion();
    need(reducedWorkshop.reduced && reducedWorkshop.scrollBehavior !== 'smooth', `reduced motion is not honoured (scroll-behavior ${reducedWorkshop.scrollBehavior})`);
    need(reducedWorkshop.animation <= 0.05 && reducedWorkshop.transition <= 0.2, `animation ${reducedWorkshop.animation}s / transition ${reducedWorkshop.transition}s under reduced motion`);
    await saveScreenshot('workshop-reduced-motion-1440.png', tab);
    await awaitCapability();
    await navigate(`${deckPath}/materials/new`, tab); await waitComposer();
    const reducedComposer = await motion();
    need(reducedComposer.animation <= 0.05 && reducedComposer.transition <= 0.2, `composer animation ${reducedComposer.animation}s / transition ${reducedComposer.transition}s under reduced motion`);
    await saveScreenshot('workshop-composer-reduced-motion-1440.png', tab);
    await emulate({ reduced: false });

    // Forced colours: the shapes and the focus ring survive (Chrome's emulation of Windows High Contrast).
    await emulate({ forced: true });
    await navigate(`${deckPath}/workshop/${shared.sessions.batch}`, tab);
    await until(async () => has('section.workshop app-batch-pager .dot') && (await snapshotUi()).summary.includes('не удались'), 'the batch Workshop did not load (forced colours)');
    const forced = await page(`const dots = [...document.querySelectorAll('section.workshop .dot svg')];
      const strokes = dots.flatMap(svg => [...svg.querySelectorAll('*')].map(node => getComputedStyle(node).stroke + '|' + getComputedStyle(node).fill));
      const dot = document.querySelector('.dot[aria-current=step]');
      return { active: matchMedia('(forced-colors: active)').matches, dots: dots.length, painted: strokes.every(value => !/^none\\|none$/u.test(value) && !value.includes('rgba(0, 0, 0, 0)')),
        currentOutline: getComputedStyle(dot).outlineStyle + ' ' + getComputedStyle(dot).outlineWidth };`);
    need(forced.active, 'forced-colors emulation did not apply');
    need(forced.dots === 4 && forced.painted, `the pager shapes are not painted under forced colours: ${JSON.stringify(forced)}`);
    need(!forced.currentOutline.startsWith('none'), `the current dot has no outline under forced colours (${forced.currentOutline})`);
    const forcedStops = await focusSweep(10);
    assertFocusVisible('Workshop in forced colours', forcedStops);
    await saveScreenshot('workshop-forced-colors-1440.png', tab);
    await awaitCapability();
    await navigate(`${deckPath}/materials/new`, tab); await waitComposer();
    need(await page(`return matchMedia('(forced-colors: active)').matches;`), 'forced colours dropped on the composer');
    const composerStops = await focusSweep(8);
    assertFocusVisible('Composer in forced colours', composerStops);
    await saveScreenshot('workshop-composer-forced-colors-1440.png', tab);
    await emulate({ forced: false });
    await desktop();
    evidence.motion = { workshop: reducedWorkshop, composer: reducedComposer };
    evidence.forcedColors = { emulated: true, shapesPainted: true, workshopFocusStops: forcedStops.length, composerFocusStops: composerStops.length };
    return { responsive: true };
  });

  evidence.sessions = { composer: 'prompt-only through the UI (1 artifact)', batch: `${NOTES.length} notes through the API (2 proposed, 1 timeout, 1 refusal)`, stopped: 'UI, cancelled by «Стоп»' };

  // Part B is a separate function boundary: it needs the #288 backend.
  evidence.approval = await runWorkshopApproval(ctx, { ...shared, api, page, press, stage, snapshotUi, artifactSnapshot, getSession, activeSessions });
  record('workshop_composer_stub_real_api', evidence);
  return evidence;
}

/**
 * Part B of #289: approve one (the material is in Browse), «Одобрить все готовые (N)» with the in-page confirmation, reject and
 * «Вернуть», «Править самому» (the editor shows the same content, then publish), retry of the failed material, hold-to-delete of a
 * session. It needs the #288 backend (approve, bulk approve, reject/undo, hand-off, retry, delete), which is not merged into this
 * branch yet. `shared` carries the batch part A left behind (`sessions.batch`, `batchStates`) and the helpers it used.
 */
export async function runWorkshopApproval(_ctx, _shared) {
  return { skipped: true, reason: 'part B waits for the #288 backend (approve, bulk approve, reject/undo, hand-off, retry, delete)' };
}
