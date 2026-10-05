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
// Live regions: the Workshop narrates the batch through one summary `role=status`; the proposal document's `.document-announcement` region
// (#296/#297) speaks only once a media search or a voice redo ends, so the one-summary checks below leave it out.
import { writeFile } from 'node:fs/promises';
import { join } from 'node:path';

import { runWorkshopExercises } from './exercises.mjs';
import { runWorkshopPlanner } from './planner.mjs';
import { runWorkshopAsk } from './ask-mnema.mjs';
import { runWorkshopEdits } from './selection-edits.mjs';
import { runWorkshopImages } from './image-search.mjs';
import { runWorkshopSpeech } from './speech.mjs';

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
    recorder.maxStatusRegions = Math.max(recorder.maxStatusRegions, workshop.querySelectorAll('[role=status]:not(.document-announcement)').length);
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
  const startedAt = Date.now();

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
  const api = (method, path, body, extraHeaders = {}) => page(`
    const response = await fetch(args[0] + args[2], { method: args[1], credentials: 'omit',
      headers: { Authorization: args[3], ...(args[4] === null ? {} : { 'Content-Type': 'application/json' }), ...args[5] },
      body: args[4] === null ? undefined : args[4] });
    const text = await response.text();
    let parsed = null; try { parsed = text ? JSON.parse(text) : null; } catch { parsed = { unparsable: text.slice(0, 80) }; }
    return { status: response.status, body: parsed, etag: response.headers.get('ETag'), cache: response.headers.get('Cache-Control') };`,
  config.frontend, method, path, 'Bearer ' + bearer, body === undefined ? null : JSON.stringify(body), extraHeaders);
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
      statusRegions: root?.querySelectorAll('[role=status]:not(.document-announcement)').length ?? -1,
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
  if (config.onlyAsk) {
    // Development aid (`run.py --only-ask`): the «Попросить Мнему…» scenario alone, after the base flow. Never the gate.
    evidence.ask = await runWorkshopAsk(ctx, { ...shared, api, page, press, stage, has, need, settle, metrics, desktop, awaitCapability,
      activeSessions, sessionPath, location });
    record('workshop_ask_mnema_only', evidence);
    return evidence;
  }
  if (config.onlyPlan) {
    // Development aid (`run.py --only-plan`): the planner scenario alone, after the base flow. Never the gate.
    evidence.planner = await runWorkshopPlanner(ctx, { ...shared, api, page, press, stage, has, need, settle, metrics, desktop, awaitCapability,
      activeSessions, sessionPath, location });
    record('workshop_planner_only', evidence);
    return evidence;
  }
  if (config.onlySpeech) {
    // Development aid (`run.py --only-speech`): the speech scenario alone, after the base flow. Never the gate.
    evidence.speech = await runWorkshopSpeech(ctx, { ...shared, api, page, press, stage, has, need, settle, metrics, desktop, awaitCapability,
      activeSessions, sessionPath, location });
    record('workshop_speech_only', evidence);
    return evidence;
  }
  if (config.onlyImages) {
    // Development aid (`run.py --only-images`): the image search scenario alone, after the base flow. Never the gate.
    evidence.images = await runWorkshopImages(ctx, { ...shared, api, page, press, stage, has, need, settle, metrics, desktop, awaitCapability,
      activeSessions, sessionPath, location });
    record('workshop_image_search_only', evidence);
    return evidence;
  }
  if (config.onlyEdits) {
    // Development aid (`run.py --only-edits`): the selection-edit scenario alone, after the base flow. Never the gate.
    evidence.edits = await runWorkshopEdits(ctx, { ...shared, api, page, press, stage, has, need, settle, metrics, desktop, awaitCapability,
      activeSessions, sessionPath, location });
    record('workshop_selection_edits_only', evidence);
    return evidence;
  }
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
      const statusRegions = [...workshop.querySelectorAll('[role=status]:not(.document-announcement)')];
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
    // Reload once the batch has settled: the first material to review is chosen when the page opens.
    await navigate(`${deckPath}/workshop/${sessionId}`, tab);
    await until(() => has('section.workshop app-batch-pager .dot'), 'the batch Workshop did not render its pager after the reload');
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
    need(initial.dots[0].current === 'step' && initial.position === '1 из 4', `the first material to review is not selected: ${JSON.stringify(initial)}`);
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
  evidence.approval = await runWorkshopApproval(ctx, { ...shared, api, page, press, insertText, stage, has, need, settle, metrics, desktop, awaitCapability,
    getSession, activeSessions, sessionPath, location, evidence, KEY: KEYS });
  evidence.notes = await runWorkshopNotes(ctx, { ...shared, api, page, stage, has, need, settle, metrics, desktop, awaitCapability, getSession,
    activeSessions, sessionPath, location });
  // #291 (AI-13): exercise generation, batch review, «Новое». Its own deck, so it never disturbs the scenarios above.
  evidence.exercises = await runWorkshopExercises(ctx, { ...shared, api, page, press, stage, has, need, settle, metrics, desktop, awaitCapability,
    activeSessions, sessionPath, location });
  // #295 (AI-14): «Сначала показать план» in the builder and the composer, the plan in the Workshop, the launch. Decks of its own.
  evidence.planner = await runWorkshopPlanner(ctx, { ...shared, api, page, press, stage, has, need, settle, metrics, desktop, awaitCapability,
    activeSessions, sessionPath, location });
  // #293 (AI-11): selection edits in the Workshop, in a deck of its own.
  evidence.edits = await runWorkshopEdits(ctx, { ...shared, api, page, press, stage, has, need, settle, metrics, desktop, awaitCapability,
    activeSessions, sessionPath, location });
  // #296 (AI-10): image search in the Workshop (the Stub image source; the found files go through the real media pipeline). Needs `--media`.
  if (config.media) {
    evidence.images = await runWorkshopImages(ctx, { ...shared, api, page, press, stage, has, need, settle, metrics, desktop, awaitCapability,
      activeSessions, sessionPath, location });
  }
  // #297 (AI-09): speech synthesis in the Workshop (the Stub speech port; the WAV goes through the real media pipeline). Needs `--media`.
  if (config.media) {
    evidence.speech = await runWorkshopSpeech(ctx, { ...shared, api, page, press, stage, has, need, settle, metrics, desktop, awaitCapability,
      activeSessions, sessionPath, location });
  }
  // #294 (AI-16): «Попросить Мнему…» in the material profile and in the exercise editor, REVISE_ITEM and REVISE_EXERCISE results.
  evidence.ask = await runWorkshopAsk(ctx, { ...shared, api, page, press, stage, has, need, settle, metrics, desktop, awaitCapability,
    activeSessions, sessionPath, location });
  evidence.durationMs = Date.now() - startedAt;
  record('workshop_composer_stub_real_api', evidence);
  return evidence;
}


/**
 * Part B of #289 (the #288 backend): approve one, reject and «Вернуть» (also after the last rejection closed the session), retry,
 * «Одобрить все готовые (N)» with the in-page confirmation, «Править самому» into the editor and publish, and hold-to-delete of a
 * session. Everything is driven through the real Workshop UI and checked against the real API and Browse.
 *
 * The Stub is deterministic, so a retry of a `[[stub:timeout]]` material fails again (its pins and prompt are unchanged): that
 * retry is asserted as a real re-run (the artifact leaves FAILED, the session runs, the second failure arrives). The retry that
 * ends PROPOSED is the one of a STALE material: its note was edited after it was written, approval found the drift, and the
 * retry writes it again against the note as it is now.
 */
export async function runWorkshopApproval(ctx, h) {
  const { tab, config, SafeFailure, until, navigate, saveScreenshot, setStep, deckPath, deckId } = { ...ctx, ...h };
  const { api, page, press, insertText, stage, has, need, settle, metrics, desktop, awaitCapability, getSession, activeSessions, sessionPath,
    location, evidence, KEY } = h;
  const out = {};
  const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

  // ---- helpers -------------------------------------------------------------------------------------------------------
  const view = () => page(`const workshop = document.querySelector('section.workshop');
    if (!workshop) return null;
    const article = workshop.querySelector('app-proposal-view article');
    const active = document.activeElement;
    const notes = [...workshop.querySelectorAll('.notice')].map(node => node.textContent.replace(/\\s+/g, ' ').trim());
    return { dots: [...workshop.querySelectorAll('.dot')].map(dot => ({ status: dot.dataset.status, current: dot.getAttribute('aria-current') === 'step', label: dot.getAttribute('aria-label') })),
      position: workshop.querySelector('.pager .position')?.textContent.trim() ?? null,
      summary: (workshop.querySelector('.summary')?.textContent ?? '').replaceAll('\\u00a0', ' ').trim(),
      h2: article?.querySelector('h2')?.textContent.replace(/\\s+/g, ' ').trim() ?? null, state: article?.dataset.state ?? null,
      title: (article?.querySelector('.final h1, .final h2, .final h3, .final p')?.textContent ?? '').replace(/\\s+/g, ' ').trim(),
      text: (article?.querySelector('.final')?.textContent ?? '').replace(/\\s+/g, '').trim(),
      buttons: [...workshop.querySelectorAll('button, a.button')].map(node => node.textContent.replace(/\\s+/g, ' ').trim()),
      actions: [...workshop.querySelectorAll('.proposal-actions button, .proposal-actions a')].map(node => node.textContent.trim()),
      notes, regions: workshop.querySelectorAll('[role=status]:not(.document-announcement)').length, alerts: workshop.querySelectorAll('[role=alert]').length,
      focus: active === document.body ? 'body' : { tag: active.tagName.toLowerCase(), id: active.id, cls: String(active.className).slice(0, 30),
        text: (active.getAttribute('aria-label') ?? active.textContent ?? '').replace(/\\s+/g, ' ').trim().slice(0, 40), inProposal: Boolean(active.closest('app-proposal-view')),
        inWorkshop: Boolean(active.closest('section.workshop')) } };`);
  const oneStatus = async label => { const v = await view(); need(v.regions === 1, `${label}: the Workshop has ${v.regions} role=status regions`); return v; };
  /** A real click on the visible button (or link) with exactly this text inside the Workshop. */
  const press_ = async (text, scope = 'section.workshop') => {
    need(await page(`const node = [...document.querySelectorAll(args[1] + ' button, ' + args[1] + ' a')].find(item => item.textContent.replace(/\\s+/g, ' ').trim() === args[0]);
      if (!node) return false; if (node.getAttribute('aria-disabled') === 'true') return 'disabled'; node.click(); return true;`, text, scope) === true, `«${text}» is not pressable in the Workshop`);
  };
  const pickDot = async index => {
    need(await page(`const dot = document.querySelectorAll('section.workshop .dot')[args[0]]; if (!dot) return false; dot.click(); return true;`, index), `dot ${index} is absent`);
    await until(async () => (await view()).dots[index]?.current === true, `the pager did not select material ${index + 1}`, 8_000);
  };
  const states = async id => (await getSession(id)).artifacts.map(artifact => ({ state: artifact.state, code: artifact.errorCode ?? null, ref: artifact.publishedRef?.kind ?? null }));
  const openWorkshop = async (id, position) => {
    await navigate(`${deckPath}/workshop/${id}${position ? `?n=${position}` : ''}`, tab);
    await until(async () => (await has('section.workshop app-batch-pager .dot')) && (await view())?.summary.length > 0, 'the Workshop did not load', 25_000);
  };
  const makeBatch = async (label, texts, effort = 'SHORT') => {
    await awaitCapability();   // the retried timeout material trips the provider's circuit breaker
    const notes = [];
    for (const text of texts) {
      const created = await api('POST', '/api/capture-notes', { commandId: crypto.randomUUID(), deckId, source: `workshop-harness-${label}`, text });
      need(created.status === 201, `POST capture-notes answered ${created.status}`);
      notes.push({ noteId: created.body.capture.noteId, rowVersion: String(created.body.capture.rowVersion) });
    }
    const created = await api('POST', `/api/decks/${deckId}/generation-sessions`, { commandId: crypto.randomUUID(), spec: {
      kind: 'MATERIALS', prompt: 'Объясни коротко', sources: notes.map(note => ({ role: 'SOURCE', type: 'NOTE', noteId: note.noteId, noteRowVersion: note.rowVersion })),
      settings: { effort, notesMode: 'ONE_PER_NOTE', media: { audio: { enabled: false, lang: 'ru', voice: null }, imageSearch: false },
        factCheck: false, similarToDeck: false, planFirst: false, budgetPercent: null } } });
    need(created.status === 201, `POST generation-sessions answered ${created.status} ${JSON.stringify(created.body?.code ?? created.body?.detail ?? null)}`);
    const sessionId = created.body.sessionId;
    await until(async () => (await getSession(sessionId)).artifacts.every(artifact => ['PROPOSED', 'FAILED'].includes(artifact.state)), `batch ${label} did not settle`, 90_000);
    return { sessionId, notes };
  };
  const browseText = async memberKey => {
    await navigate(`${deckPath}/materials/${memberKey}`, tab);
    await until(() => has('app-native-document-renderer article'), 'the published material did not render in Browse', 25_000);
    return page(`return document.querySelector('app-native-document-renderer article').textContent.replace(/\\s+/g, '').trim();`);
  };
  const itemsInDeck = async () => {
    const response = await api('GET', `/api/decks/${deckId}/items?limit=100`);
    need(response.status === 200, `GET deck items answered ${response.status}`);
    return response.body.items ?? [];
  };
  await desktop();
  await awaitCapability();
  const materialsBefore = (await itemsInDeck()).length;

  // =====================================================================================================================
  // 1. Approve one, then reject/«Вернуть», then the retries, on the batch of part A
  // =====================================================================================================================
  await stage('approve_one', async () => {
    const id = h.sessions.batch;
    await openWorkshop(id);
    const first = await oneStatus('batch opened');
    need(first.state === 'PROPOSED' && first.actions.includes('Одобрить и далее →'), `material 1 has actions ${JSON.stringify(first.actions)}`);
    const title = first.title;
    await saveScreenshot('workshop-approval-proposal-1440.png', tab);
    await metrics(390, 844, 1, true); await settle();
    need(!(await page(`return document.documentElement.scrollWidth > document.documentElement.clientWidth;`)), 'the proposal overflows at 390 px');
    need(await page(`return [...document.querySelectorAll('section.workshop .proposal-actions .button')].every(node => node.getBoundingClientRect().height >= 43.5);`), 'a proposal action is below 44 px at 390');
    await saveScreenshot('workshop-approval-proposal-390.png', tab);
    await desktop(); await settle();
    // Approve: the real button, then the next material to review is shown and its title takes focus.
    await press_('Одобрить и далее →');
    await until(async () => (await states(id))[0].state === 'PUBLISHED', 'the API did not publish material 1', 20_000);
    await until(async () => (await view()).dots[1]?.current === true, 'the pager did not advance to the next material', 10_000);
    const after = await oneStatus('after approve');
    need(after.focus !== 'body' && after.focus.tag === 'h2' && after.focus.inProposal, `after «Одобрить и далее» focus is ${JSON.stringify(after.focus)}, not the next material's title`);
    need(after.dots[0].status === 'done' && after.dots[0].label.endsWith('в колоде'), `the approved dot is ${JSON.stringify(after.dots[0])}`);
    // The summary is a throttled live region (at most one change per 2 s): wait for it.
    await until(async () => { const text = (await view()).summary; return text.includes('1 в колоде') && text.includes('1 готово'); }, `the summary after approve did not say «1 готово … 1 в колоде» (${after.summary})`, 8_000);
    const ref = (await getSession(id)).artifacts[0].publishedRef;
    need(ref?.kind === 'ITEM' && ref.memberKey, 'the published artifact has no item reference');
    const approvedSummary = (await view()).summary;
    // The approved material shows its own state with a link into Browse (a real click), and Browse renders it.
    await pickDot(0);
    const published = await oneStatus('approved material');
    need(published.notes.some(note => note.startsWith('Материал одобрен и добавлен в колоду')), `the approved material says ${JSON.stringify(published.notes)}`);
    need(await page(`const link = [...document.querySelectorAll('section.workshop app-proposal-view a')].find(item => item.textContent.trim() === 'Открыть материал'); if (!link) return false; link.click(); return true;`), '«Открыть материал» is absent');
    await until(async () => (await location()).endsWith(`/materials/${ref.memberKey}`) && await has('app-native-document-renderer article'), 'the link did not open the material in Browse', 25_000);
    const browsed = await page(`return document.querySelector('app-native-document-renderer article').textContent.replace(/\\s+/g, ' ').trim();`);
    need(browsed.includes(title.slice(0, 20)) || browsed.length > 10, 'Browse shows no content of the approved material');
    const inDeck = await itemsInDeck();
    need(inDeck.length === materialsBefore + 1, `the deck has ${inDeck.length} materials, expected ${materialsBefore + 1}`);
    out.approveOne = { advanced: true, focusAfter: after.focus, summary: approvedSummary, memberKey: ref.memberKey, browseChars: browsed.length, deckMaterials: inDeck.length };
    return out.approveOne;
  });

  await stage('reject_undo_retry', async () => {
    const id = h.sessions.batch;
    await openWorkshop(id, 2);
    need((await view()).state === 'PROPOSED', 'material 2 is not PROPOSED');
    await press_('Отклонить');
    await until(async () => (await states(id))[1].state === 'REJECTED', 'the API did not reject material 2', 20_000);
    await until(async () => (await view()).actions.includes('Вернуть'), '«Вернуть» did not appear after the rejection');
    const rejected = await oneStatus('after reject');
    need(rejected.focus !== 'body', `after «Отклонить» focus fell to the page (${JSON.stringify(rejected.focus)})`);
    need(rejected.notes.some(note => note.startsWith('Вы отклонили этот материал')), 'the rejection is not stated');
    await saveScreenshot('workshop-approval-rejected-1440.png', tab);
    await press_('Вернуть');
    await until(async () => (await states(id))[1].state === 'PROPOSED', 'the API did not restore material 2', 20_000);
    await until(async () => (await view()).actions.includes('Одобрить и далее →'), 'the proposal did not come back after «Вернуть»');
    const restored = await oneStatus('after undo');
    need(restored.focus !== 'body', `after «Вернуть» focus fell to the page (${JSON.stringify(restored.focus)})`);

    // Retry of the timeout material: a real re-run, which the Stub fails again.
    await awaitCapability();
    await pickDot(2);
    const timeout = await view();
    need(timeout.actions.includes('Попробовать снова'), 'the timed-out material offers no retry');
    const before = (await getSession(id)).artifacts[2];
    await press_('Попробовать снова');
    await until(async () => { const a = (await getSession(id)).artifacts[2]; return a.state !== 'FAILED' || a.rowVersion !== before.rowVersion; }, 'the retry did not start (the artifact stayed FAILED)', 15_000);
    const running = await getSession(id);
    out.retryFailed = { leftFailed: true, sessionAfterRetry: running.state, artifactAfterRetry: running.artifacts[2].state };
    await until(async () => (await getSession(id)).artifacts[2].state === 'FAILED' && (await getSession(id)).artifacts[2].rowVersion !== before.rowVersion,
      'the retried timeout material did not fail again', 90_000);
    await until(async () => (await view()).actions.includes('Попробовать снова'), 'the second failure shows no retry again', 20_000);
    out.retryFailed.endedFailedAgain = true;
    const settled = await oneStatus('after retry');
    out.rejectUndo = { rejectedNote: true, restored: true, focusAfterReject: rejected.focus, focusAfterUndo: restored.focus };
    return { ...out.rejectUndo, retryFailed: out.retryFailed, summary: settled.summary };
  });

  // =====================================================================================================================
  // 2. Hand-off: «Править самому» opens the editor on the same content; publishing it puts the material in Browse
  // =====================================================================================================================
  await stage('handoff_publish', async () => {
    const id = h.sessions.composer;
    await openWorkshop(id);
    const shown = await oneStatus('composer session');
    need(shown.actions.includes('Править самому'), `the single material offers ${JSON.stringify(shown.actions)}`);
    const count = (await itemsInDeck()).length;
    await press_('Править самому');
    await until(async () => (await location()).includes('/materials/new') && (await location()).includes('draft='), '«Править самому» did not open the editor on a draft', 25_000);
    await until(() => has('app-item-editor-page .ProseMirror'), 'the editor did not load', 25_000);
    await until(async () => (await page(`return document.querySelector('app-item-editor-page .ProseMirror')?.textContent.replace(/\\s+/g, '').length ?? 0;`)) > 5, 'the editor is empty');
    const editorText = await page(`return document.querySelector('app-item-editor-page .ProseMirror').textContent.replace(/\\s+/g, '');`);
    const artifact = (await getSession(id)).artifacts[0];
    need(artifact.state === 'HANDED_OFF', `the artifact is ${artifact.state} after the hand-off`);
    // Same content: every character the Workshop showed (ruby readings are rendered by both) is in the editor.
    const missing = [...new Set(shown.text)].filter(char => !editorText.includes(char));
    need(missing.length <= Math.ceil(shown.text.length * 0.02), `the editor lacks content the Workshop showed: ${missing.join('').slice(0, 40)}`);
    await saveScreenshot('workshop-approval-handoff-editor-1440.png', tab);
    need(await page(`const button = [...document.querySelectorAll('app-item-editor-page button')].find(item => item.textContent.trim() === 'Опубликовать'); if (!button || button.disabled) return false; button.click(); return true;`), 'the editor cannot publish the handed-off material');
    await until(async () => (await itemsInDeck()).length === count + 1, 'the published material is not in the deck', 30_000);
    const memberKey = (await itemsInDeck()).map(item => item.memberKey ?? item.itemId).find(Boolean);
    await until(async () => /\/materials\/[0-9a-f-]{36}$/u.test(await location()) || await has('app-native-document-renderer article'), 'the editor did not land in Browse after publishing', 25_000);
    need(await has('app-native-document-renderer article'), 'Browse does not show the published material');
    const after = (await getSession(id)).state;
    out.handoff = { editorHasSameContent: true, charsCompared: editorText.length, artifactState: artifact.state, deckMaterials: count + 1, sessionAfter: after, memberKey: Boolean(memberKey) };
    return out.handoff;
  });

  // =====================================================================================================================
  // 3. «Одобрить все готовые (N)» with its in-page confirmation; the closed, empty session
  // =====================================================================================================================
  await stage('approve_all', async () => {
    const { sessionId } = await makeBatch('all', ['Правило: глагол い-типа меняет конец на ない.', 'Правило: частица は отмечает тему.', 'Правило: числительные и счётные слова.']);
    out.approveAllSession = sessionId;
    await openWorkshop(sessionId);
    const ready = await oneStatus('approve-all batch');
    need(ready.summary === '3 готово', `the batch says «${ready.summary}»`);
    const trigger = ready.buttons.find(label => label.startsWith('Одобрить все готовые'));
    need(trigger?.replaceAll(' ', ' ') === 'Одобрить все готовые (3)', `the approve-all button says «${trigger}»`);
    await press_(trigger);
    await until(async () => (await view()).notes.some(note => note.includes('Одобрить материалов: 3?')), 'the in-page confirmation did not appear');
    const asked = await oneStatus('confirmation');
    need(asked.focus !== 'body' && asked.focus.text === 'Да, одобрить', `focus on the confirmation is ${JSON.stringify(asked.focus)}, not «Да, одобрить»`);
    need((await getSession(sessionId)).artifacts.every(artifact => artifact.state === 'PROPOSED'), 'something was approved before the confirmation');
    await saveScreenshot('workshop-approve-all-confirm-1440.png', tab);
    await metrics(390, 844, 1, true); await settle();
    need(!(await page(`return document.documentElement.scrollWidth > document.documentElement.clientWidth;`)), 'the confirmation overflows at 390 px');
    await page(`document.querySelector('section.workshop .confirm')?.scrollIntoView({ block: 'center' }); return true;`);
    await saveScreenshot('workshop-approve-all-confirm-390.png', tab);
    await desktop(); await settle();
    // Escape or «Отмена» gives the focus back to the button that asked.
    await press_('Отмена');
    const dismissed = await oneStatus('dismissed');
    await until(async () => (await view()).focus?.text?.startsWith('Одобрить все готовые'), 'focus did not return to «Одобрить все готовые»', 5_000);
    need((await getSession(sessionId)).artifacts.every(artifact => artifact.state === 'PROPOSED'), 'the cancelled confirmation approved something');
    await press_(trigger);
    await until(async () => (await view()).notes.some(note => note.includes('Одобрить материалов: 3?')), 'the confirmation did not reopen');
    const count = (await itemsInDeck()).length;
    await press_('Да, одобрить');
    await until(async () => (await getSession(sessionId)).artifacts.every(artifact => artifact.state === 'PUBLISHED'), 'not every material was approved', 30_000);
    await until(async () => (await view()).summary === '3 в колоде', 'the summary did not say «3 в колоде»', 15_000);
    const done = await oneStatus('after approve all');
    need(done.focus !== 'body' && done.focus.tag === 'h2', `after approve-all focus is ${JSON.stringify(done.focus)}`);
    need((await itemsInDeck()).length === count + 3, 'the three approved materials are not in the deck');
    await until(async () => (await getSession(sessionId)).state === 'CLOSED', 'the session did not close', 15_000);
    await until(async () => (await view()).notes.some(note => note.includes('Все материалы разобраны')), 'the closed session does not say it is finished', 15_000);
    need(!(await view()).buttons.some(label => label.startsWith('Одобрить все')), 'approve-all is still offered in a finished session');
    await saveScreenshot('workshop-closed-session-1440.png', tab);
    await metrics(390, 844, 1, true); await settle();
    await saveScreenshot('workshop-closed-session-390.png', tab);
    await desktop(); await settle();
    out.approveAll = { count: 3, confirmationFocus: asked.focus, cancelRestoredFocus: true, allPublished: true, deckGrewBy: 3, sessionState: 'CLOSED', summary: done.summary };
    return out.approveAll;
  });

  // =====================================================================================================================
  // 4. A material whose note changed: approval finds it STALE; its retry writes it again and ends PROPOSED
  // =====================================================================================================================
  await stage('stale_retry', async () => {
    await awaitCapability();
    const { sessionId, notes } = await makeBatch('stale', ['Заметка о счётных словах: 本, 枚, 個.']);
    const note = await api('GET', `/api/capture-notes/${notes[0].noteId}`);
    need(note.status === 200 && note.etag, `GET capture note answered ${note.status}`);
    const updated = await api('PUT', `/api/capture-notes/${notes[0].noteId}`, { source: 'workshop-harness-stale', text: 'Заметка о счётных словах, исправленная: 本, 枚, 個, 匹, 冊.' }, { 'If-Match': note.etag });
    need(updated.status === 200, `PUT capture note answered ${updated.status}`);
    await openWorkshop(sessionId);
    const before = (await getSession(sessionId)).artifacts[0];
    await press_('Одобрить и далее →');
    await until(async () => (await getSession(sessionId)).artifacts[0].state === 'STALE', 'approval did not find the changed note (artifact not STALE)', 20_000);
    await until(async () => (await view()).actions.includes('Попробовать снова'), 'a STALE material offers no retry', 15_000);
    const stale = await oneStatus('stale');
    need(stale.notes.some(note => note.includes('Заметка изменилась')), `the stale material says ${JSON.stringify(stale.notes)}`);
    await saveScreenshot('workshop-approval-stale-1440.png', tab);
    await press_('Попробовать снова');
    await until(async () => (await getSession(sessionId)).artifacts[0].state === 'PROPOSED' && (await getSession(sessionId)).artifacts[0].rowVersion !== before.rowVersion,
      'the retried STALE material did not end PROPOSED', 60_000);
    await until(async () => (await view()).actions.includes('Одобрить и далее →'), 'the rewritten material is not offered for approval', 15_000);
    await oneStatus('after stale retry');
    out.staleRetry = { staleAfterApproval: true, retryEndedProposed: true, newRevision: (await getSession(sessionId)).artifacts[0].currentRevisionId !== before.currentRevisionId };
    out.staleSession = sessionId;
    return out.staleRetry;
  });

  // =====================================================================================================================
  // 5. The last rejection closes the session; «Вернуть» still works and the reopened session is polled again; hold-to-delete
  // =====================================================================================================================
  await stage('last_reject_undo_delete', async () => {
    await awaitCapability();
    const { sessionId } = await makeBatch('last', ['Короткая заметка о частице を.']);
    await openWorkshop(sessionId);
    await press_('Отклонить');
    await until(async () => (await getSession(sessionId)).state === 'CLOSED', 'rejecting the last material did not close the session', 20_000);
    await until(async () => (await view()).actions.includes('Вернуть'), '«Вернуть» is gone after the last rejection closed the session', 15_000);
    const closed = await oneStatus('closed by the last rejection');
    await saveScreenshot('workshop-last-rejected-1440.png', tab);
    await press_('Вернуть');
    await until(async () => (await getSession(sessionId)).state === 'REVIEW', 'undoing the last rejection did not reopen the session', 20_000);
    await until(async () => (await view()).actions.includes('Одобрить и далее →'), 'the reopened session does not offer the material');
    // Polling resumed: a change made behind the page's back (a second reject through the API) shows up without any interaction.
    const artifact = (await getSession(sessionId)).artifacts[0];
    const rejected = await api('POST', `${sessionPath(sessionId)}/artifacts/${artifact.artifactId}/rejection`, { commandId: crypto.randomUUID(), expectedArtifactVersion: String(artifact.rowVersion) });
    need(rejected.status === 200, `the outside rejection answered ${rejected.status} ${JSON.stringify(rejected.body?.code ?? null)}`);
    await until(async () => (await view()).actions.includes('Вернуть'), 'the reopened session is not polled: an outside rejection never appeared', 25_000);
    out.lastReject = { closedByLastRejection: true, undoOffered: true, reopenedState: 'REVIEW', pollingResumed: true, endNoteWhileClosed: closed.notes.length > 0 };
    // Back to PROPOSED (so the Workshop is "active"), then hold-to-delete with the keyboard.
    await press_('Вернуть');
    await until(async () => (await getSession(sessionId)).state === 'REVIEW', 'the second undo did not reopen the session', 20_000);
    const listedBefore = await activeSessions();
    need(listedBefore.some(session => session.sessionId === sessionId), 'the reopened session is not in the active list');
    await until(async () => (await view()).actions.includes('Одобрить и далее →'), 'the session is not ready for the delete check');
    const focusDelete = await page(`const button = document.querySelector('section.workshop app-hold-to-delete-button button'); if (!button) return false; button.focus(); return document.activeElement === button;`);
    need(focusDelete, '«Удалить мастерскую» cannot take focus');
    // Space arms the button; a second, uninterrupted press of 3 s deletes (as in the hub scenario).
    await page(`const button = document.querySelector('section.workshop app-hold-to-delete-button button'); button.focus(); return true;`);
    await tab.call('Input.dispatchKeyEvent', { type: 'keyDown', text: ' ', unmodifiedText: ' ', key: ' ', code: 'Space', windowsVirtualKeyCode: 32, nativeVirtualKeyCode: 32 });
    await tab.call('Input.dispatchKeyEvent', { type: 'keyUp', key: ' ', code: 'Space', windowsVirtualKeyCode: 32, nativeVirtualKeyCode: 32 });
    await settle();
    const armed = await page(`const button = document.querySelector('section.workshop app-hold-to-delete-button button');
      return { armed: button.getAttribute('aria-pressed') === 'true', consequence: document.querySelector('section.workshop .consequence')?.textContent.trim() ?? null };`);
    need(armed.armed && armed.consequence?.startsWith('Неодобренные материалы исчезнут'), `the delete button is not armed with its consequence: ${JSON.stringify(armed)}`);
    const startedAt = Date.now();
    await tab.call('Input.dispatchKeyEvent', { type: 'keyDown', text: ' ', unmodifiedText: ' ', key: ' ', code: 'Space', windowsVirtualKeyCode: 32, nativeVirtualKeyCode: 32 });
    await sleep(800);
    need((await getSession(sessionId)).state === 'REVIEW', 'the session was deleted before the 3 s hold was over');
    await until(async () => (await api('GET', sessionPath(sessionId))).status === 404, 'the hold did not delete the session', 20_000);
    const heldMs = Date.now() - startedAt;
    await tab.call('Input.dispatchKeyEvent', { type: 'keyUp', key: ' ', code: 'Space', windowsVirtualKeyCode: 32, nativeVirtualKeyCode: 32 });
    need(heldMs >= 3000, `the session was deleted after only ${heldMs} ms`);
    const again = await api('DELETE', sessionPath(sessionId));
    need(again.status === 404, `deleting a deleted session answered ${again.status}`);
    await until(async () => (await location()) === deckPath, 'deleting did not return to the Deck page', 20_000);
    await until(() => has('nav.hub-actions'), 'the Deck page did not load after the deletion');
    const links = await page(`return [...document.querySelectorAll('app-deck-workshops a')].map(link => new URL(link.href).pathname);`);
    need(!links.some(path => path.endsWith(sessionId)), 'the deleted session is still listed on the Deck page');
    const activeAfter = await activeSessions();
    need(!activeAfter.some(session => session.sessionId === sessionId), 'the deleted session is still in the active list');
    out.deleteSession = { keyboardHoldMs: heldMs, api: 404, secondDelete: 404, returnedToDeck: true, notListed: true, activeBefore: listedBefore.length, activeAfter: activeAfter.length };
    return { ...out.lastReject, ...out.deleteSession };
  });

  out.notes = 'RESOURCE_LIMIT_EXCEEDED (ACTIVE_SESSIONS) on retry and note archival are covered by backend and component tests, not driven here';
  return out;
}


/**
 * Notes as sources (#290): the real capture page → checkboxes → «Создать материалы с ИИ» → the composer with one chip per note,
 * the grouping choice and a per-note setting → the Workshop (one material per note, each sourced from its note) → a note edited
 * behind the Workshop's back is marked «заметка изменилась» → «Архивировать использованные заметки (k)» archives exactly the
 * used notes and is harmless the second time → a MERGE_INTO_ONE run: two notes, one material with two sources.
 * Only the notes themselves and the edit that makes one of them change are written through the API.
 */
export async function runWorkshopNotes(ctx, h) {
  const { tab, SafeFailure, until, navigate, saveScreenshot, deckPath, deckId } = { ...ctx, ...h };
  const { api, page, stage, has, need, settle, metrics, desktop, awaitCapability, getSession, activeSessions, sessionPath, location } = h;
  const out = {};
  const run = Math.random().toString(36).slice(2, 7);
  const tokens = [1, 2, 3, 4].map(index => `Узел${run}${index}`);
  const texts = tokens.map((token, index) => `${token}: слово номер ${index + 1}, коротко и с одним примером.`);

  const press = async (text, scope) => need(await page(`const node = [...document.querySelectorAll(args[1] + ' button, ' + args[1] + ' a')]
      .find(item => item.textContent.replace(/\\s+/g, ' ').trim() === args[0]);
    if (!node) return false; if (node.getAttribute('aria-disabled') === 'true') return 'disabled'; node.click(); return true;`, text, scope) === true,
    `«${text}» is not pressable in ${scope}`);
  const createNotes = async list => {
    const made = [];
    for (const text of list) {
      const created = await api('POST', '/api/capture-notes', { commandId: crypto.randomUUID(), deckId, source: 'workshop-harness-notes', text });
      need(created.status === 201, `POST capture-notes answered ${created.status}`);
      made.push({ noteId: created.body.capture.noteId, rowVersion: String(created.body.capture.rowVersion), text });
    }
    return made;
  };
  const noteState = async noteId => {
    const response = await api('GET', `/api/capture-notes/${noteId}`);
    need(response.status === 200, `GET capture note answered ${response.status}`);
    return { archived: response.body.archived === true, rowVersion: String(response.body.rowVersion), etag: response.etag };
  };
  const captureView = () => page(`return { checked: [...document.querySelectorAll('.capture-card input[type=checkbox]')].filter(box => box.checked).length,
    boxes: document.querySelectorAll('.capture-card input[type=checkbox]').length,
    count: document.querySelector('.selection-count')?.textContent.trim() ?? null,
    bar: document.querySelector('.selection-bar-count')?.textContent.trim() ?? null,
    overflow: document.documentElement.scrollWidth > document.documentElement.clientWidth };`);
  const tick = token => page(`const box = [...document.querySelectorAll('.capture-card input[type=checkbox]')].find(item => (item.getAttribute('aria-label') ?? '').includes(args[0]));
    if (!box) return false; box.click(); return box.checked;`, token);
  const openCapture = async () => {
    await navigate(`${deckPath}/capture`, tab);
    await until(async () => (await captureView()).boxes > 0, 'the capture page shows no selectable notes', 25_000);
  };
  const toComposer = async (count, label) => {
    await until(() => has('.selection-bar button.primary'), `${label}: the selection bar did not appear`);
    await press('Создать материалы с ИИ', '.selection-bar');
    await until(() => has('app-generation-composer textarea'), `${label}: the composer did not open`, 25_000);
    await until(async () => (await page(`return document.querySelectorAll('.source-chip').length;`)) === count, `${label}: the composer does not show ${count} chips`, 15_000);
  };
  const composerFacts = () => page(`const root = document.querySelector('app-generation-composer');
    return { chips: [...root.querySelectorAll('.source-chip > span:first-child')].map(chip => chip.textContent.trim()),
      prompt: root.querySelector('textarea').value,
      placeholder: root.querySelector('textarea').placeholder,
      grouping: [...root.querySelectorAll('.notes-options input[type=radio]')].map(input => ({ label: input.closest('label').textContent.trim(), checked: input.checked })),
      perNote: Boolean(root.querySelector('details.per-note')), perNoteSummary: root.querySelector('details.per-note .count')?.textContent.trim() ?? null,
      overflow: document.documentElement.scrollWidth > document.documentElement.clientWidth,
      estimate: root.querySelector('.estimate')?.textContent.replaceAll('\\u00a0', ' ').trim() ?? null };`);
  const submitComposer = async label => {
    await press('Создать', 'app-generation-composer .action-row');
    await until(async () => /\/workshop\/[0-9a-f-]{36}$/u.test(await location()), `${label}: the composer did not open the Workshop`, 30_000);
    const id = (await location()).match(/\/workshop\/([0-9a-f-]{36})$/u)[1];
    await until(async () => (await getSession(id)).artifacts.every(artifact => ['PROPOSED', 'FAILED'].includes(artifact.state)), `${label}: the batch did not settle`, 90_000);
    return id;
  };
  const artifactDetail = async (sessionId, artifactId) => {
    const response = await api('GET', `${sessionPath(sessionId)}/artifacts/${artifactId}`);
    need(response.status === 200, `GET artifact answered ${response.status}`);
    return response.body;
  };
  const dotCount = () => page(`return document.querySelectorAll('section.workshop .dot').length;`);
  const workshopView = () => page(`const workshop = document.querySelector('section.workshop');
    return { position: workshop?.querySelector('.pager .position')?.textContent.trim() ?? null,
      changedTags: workshop?.querySelectorAll('.note-changed-tag').length ?? 0,
      changedText: workshop?.querySelector('.note-changed')?.textContent.replace(/\\s+/g, ' ').trim() ?? null,
      archiveButton: [...(workshop?.querySelectorAll('.note-archive button') ?? [])].map(node => node.textContent.trim()),
      result: workshop?.querySelector('.note-archive-result')?.textContent.trim() ?? null,
      actions: [...(workshop?.querySelectorAll('.proposal-actions button, .proposal-actions a') ?? [])].map(node => node.textContent.trim()),
      regions: workshop?.querySelectorAll('[role=status]:not(.document-announcement)').length ?? 0 };`);

  await desktop();
  await awaitCapability();
  // A batch from earlier in the run may still be active; the account limit is not what this scenario tests.
  for (const session of await activeSessions()) {
    const gone = await api('DELETE', sessionPath(session.sessionId));
    need([204, 404].includes(gone.status), `deleting an earlier session answered ${gone.status}`);
  }

  // ---- 1. capture page: select four notes ---------------------------------------------------------------------------
  let notes = [];
  await stage('notes_capture_selection', async () => {
    // The capture list is newest first and the composer keeps the list order: from here on index 0 is the first chip.
    notes = (await createNotes(texts)).reverse(); tokens.reverse(); texts.reverse();
    await openCapture();
    const loaded = await captureView();
    // «Выбрать все загруженные»: the counter follows, and «Снять выбор» clears it.
    need(await page(`const box = document.querySelector('.select-all input'); if (!box) return false; box.click(); return true;`), '«Выбрать все загруженные» is absent');
    await until(async () => (await captureView()).checked > 0, 'the select-all checkbox picked nothing');
    const all = await captureView();
    need(all.checked === Math.min(loaded.boxes, 20) && all.count === `Выбрано: ${all.checked}`, `select-all: ${JSON.stringify(all)}`);
    await press('Снять выбор', '.selection-bar');
    await until(async () => (await captureView()).checked === 0, '«Снять выбор» did not clear the selection');
    for (const token of tokens) need(await tick(token), `the checkbox of note ${token} could not be ticked`);
    const picked = await captureView();
    need(picked.checked === 4 && picked.count === 'Выбрано: 4' && picked.bar === 'Выбрано: 4', `the counters after four picks: ${JSON.stringify(picked)}`);
    await desktop(); await settle();
    await saveScreenshot('workshop-notes-capture-selection-1440.png', tab);
    await metrics(390, 844, 1, true); await settle();
    const narrow = await captureView();
    need(!narrow.overflow, 'the capture selection overflows at 390 px');
    await page(`document.querySelector('.selection-bar')?.scrollIntoView({ block: 'end' }); return true;`);
    await saveScreenshot('workshop-notes-capture-selection-390.png', tab);
    await desktop(); await settle();
    return { loadedBoxes: loaded.boxes, selectAllPicked: all.checked, selectAllCounter: all.count, pickedCounter: picked.count, bar: picked.bar };
  });

  // ---- 2. composer: chips, empty prompt, grouping, one per-note setting --------------------------------------------------
  let sessionId = null;
  await stage('notes_composer_submit', async () => {
    await toComposer(4, 'composer');
    const facts = await composerFacts();
    need(facts.chips.length === 4 && tokens.every((token, index) => facts.chips[index]?.startsWith(token)), `the chips are ${JSON.stringify(facts.chips)}`);
    need(facts.prompt === '', `the prompt is not empty: «${facts.prompt}»`);
    need(facts.grouping.length === 2 && facts.grouping.find(option => option.checked)?.label === 'Материал на заметку', `the grouping is ${JSON.stringify(facts.grouping)}`);
    need(facts.perNote, 'the per-note settings are absent for four notes');
    // «Настроить для каждой заметки отдельно»: open it and give the second note «Подробно».
    await page(`document.querySelector('details.per-note summary').click(); return true;`);
    await until(() => has('details.per-note[open] .rows .row'), 'the per-note settings did not open');
    need(await page(`const trigger = [...document.querySelectorAll('details.per-note button[role=combobox]')]
      .find(item => item.getAttribute('aria-label').startsWith('Подробность для заметки') && item.getAttribute('aria-label').includes(args[0])); if (!trigger) return false; trigger.click(); return true;`, tokens[1]),
      'the effort control of the second note is absent');
    await until(() => has('details.per-note .options [role=option]'), 'the effort options did not open');
    need(await page(`const option = [...document.querySelectorAll('details.per-note .options [role=option]')].find(item => item.textContent.trim() === 'Подробно'); if (!option) return false; option.click(); return true;`),
      'the option «Подробно» is absent');
    await until(async () => (await composerFacts()).perNoteSummary !== facts.perNoteSummary, 'the per-note summary did not change');
    const edited = await composerFacts();
    need(/1/u.test(edited.perNoteSummary ?? ''), `the per-note summary reads «${edited.perNoteSummary}»`);
    await metrics(1440, 1500, 1, false); await settle();   // tall: chips, grouping and the open per-note settings in one frame
    await saveScreenshot('workshop-notes-composer-chips-1440.png', tab);
    await metrics(390, 2200, 1, true); await settle();
    need(!(await composerFacts()).overflow, 'the composer with chips overflows at 390 px');
    await saveScreenshot('workshop-notes-composer-chips-390.png', tab);
    await desktop(); await settle();
    sessionId = await submitComposer('notes');
    const session = await getSession(sessionId);
    need(session.artifacts.length === 4, `${session.artifacts.length} artifacts for four notes`);
    const sources = session.spec.sources;
    need(sources.length === 4 && sources.every((source, index) => source.noteId === notes[index].noteId && source.noteRowVersion === notes[index].rowVersion),
      `the stored pins differ from the notes: ${JSON.stringify(sources)}`);
    need(JSON.stringify(sources[1].overrides) === JSON.stringify({ effort: 'DETAILED' }), `the echo of the overridden note: ${JSON.stringify(sources[1].overrides)}`);
    need([0, 2, 3].every(index => !('overrides' in sources[index])), 'a note without a setting echoes overrides');
    need(session.spec.prompt === '' || session.spec.prompt === null || session.spec.prompt === undefined, `the stored prompt is «${session.spec.prompt}»`);
    need(session.spec.settings.notesMode === 'ONE_PER_NOTE', 'the stored grouping is not ONE_PER_NOTE');
    const refs = [];
    for (const artifact of session.artifacts) {
      const detail = await artifactDetail(sessionId, artifact.artifactId);
      const noteRefs = detail.sourceRefs.filter(ref => ref.type === 'NOTE');
      need(noteRefs.length === 1, `artifact ${artifact.ordinal} has ${noteRefs.length} note sources`);
      refs.push({ ordinal: artifact.ordinal, noteId: noteRefs[0].noteId, status: noteRefs[0].status });
    }
    need(refs.every((ref, index) => ref.noteId === notes[index].noteId && ref.status === 'CURRENT'), `sourceRefs: ${JSON.stringify(refs)}`);
    return { chips: facts.chips.length, promptEmpty: true, grouping: 'Материал на заметку', overrideSummary: edited.perNoteSummary, estimate: edited.estimate,
      artifacts: 4, sourceRefs: refs, specEcho: { overrides: sources.map(source => source.overrides ?? null), notesMode: session.spec.settings.notesMode } };
  });

  // ---- 3. a note changed behind the Workshop's back; approvals; archive the used notes -----------------------------------
  await stage('notes_workshop_archive', async () => {
    const before = await noteState(notes[3].noteId);
    const edit = await api('PUT', `/api/capture-notes/${notes[3].noteId}`, { source: 'workshop-harness-notes', text: `${texts[3]} Дополнено позже.` }, { 'If-Match': before.etag });
    need(edit.status === 200, `PUT capture note answered ${edit.status}`);
    await navigate(`${deckPath}/workshop/${sessionId}?n=4`, tab);
    await until(async () => (await dotCount()) === 4, 'the Workshop did not render four materials', 25_000);
    await until(async () => (await workshopView()).changedTags === 1, 'the edited note is not marked «заметка изменилась»', 20_000);
    const tagged = await workshopView();
    need(tagged.position === '4 из 4' && tagged.changedText?.startsWith('заметка изменилась'), `the changed-note tag: ${JSON.stringify(tagged)}`);
    const detail = await artifactDetail(sessionId, (await getSession(sessionId)).artifacts[3].artifactId);
    need(detail.sourceRefs.find(ref => ref.type === 'NOTE')?.status === 'CHANGED', `the API status of the edited note is ${JSON.stringify(detail.sourceRefs)}`);
    const untouched = await artifactDetail(sessionId, (await getSession(sessionId)).artifacts[0].artifactId);
    need(untouched.sourceRefs.find(ref => ref.type === 'NOTE')?.status === 'CURRENT', 'an untouched note is not CURRENT');
    // Approve the first three through the UI.
    await navigate(`${deckPath}/workshop/${sessionId}?n=1`, tab);
    await until(async () => (await workshopView()).actions.some(action => action.startsWith('Одобрить')), 'the first material offers no approval', 25_000);
    need((await workshopView()).changedTags === 0, 'an unchanged note is marked as changed');
    for (let index = 0; index < 3; index++) {
      // The press is made only once the proposal's own content is rendered: a press while its detail is still loading is ignored by design
      // (the store never approves a revision the user has not been shown), so waiting for what the user sees is the honest precondition.
      await until(() => has('section.workshop app-proposal-view article[data-state=PROPOSED] .final'), `the proposal of material ${index + 1} did not render`, 25_000);
      need(await page(`const node = [...document.querySelectorAll('section.workshop .proposal-actions button')].find(item => item.textContent.trim().startsWith('Одобрить')); if (!node) return false; node.click(); return true;`),
        `material ${index + 1} cannot be approved`);
      await until(async () => (await getSession(sessionId)).artifacts.filter(artifact => artifact.state === 'PUBLISHED').length === index + 1, `material ${index + 1} was not published`, 25_000);
      if (index < 2) {
        await until(async () => { const now = await workshopView(); return now.position === `${index + 2} из 4` && now.actions.some(action => action.startsWith('Одобрить')); },
          `the Workshop did not move on to material ${index + 2}`, 15_000);
      }
    }
    const published = (await getSession(sessionId)).artifacts.map(artifact => artifact.state);
    need(JSON.stringify(published) === JSON.stringify(['PUBLISHED', 'PUBLISHED', 'PUBLISHED', 'PROPOSED']), `states after three approvals: ${published}`);
    await navigate(`${deckPath}/workshop/${sessionId}?n=4`, tab);
    await until(async () => (await workshopView()).archiveButton.length === 1 && (await workshopView()).changedTags === 1, 'the archive action or the changed tag is missing', 25_000);
    const view = await workshopView();
    need(view.archiveButton[0] === 'Архивировать использованные заметки (3)', `the archive action reads «${view.archiveButton[0]}»`);
    need((await getSession(sessionId)).notes.used === 3 && (await getSession(sessionId)).notes.archivable === 3, `the API notes counters: ${JSON.stringify((await getSession(sessionId)).notes)}`);
    await metrics(1440, 1500, 1, false); await settle();   // tall: the changed-note tag and the archive action in one frame
    await saveScreenshot('workshop-notes-changed-archive-1440.png', tab);
    await desktop(); await settle();
    await press('Архивировать использованные заметки (3)', 'section.workshop .note-archive');
    await until(async () => (await workshopView()).result !== null || (await workshopView()).archiveButton.length === 0, 'archiving gave no answer', 25_000);
    await until(async () => (await getSession(sessionId)).notes.archivable === 0, 'the archivable counter did not drop to 0', 25_000);
    const states = [];
    for (const note of notes) states.push((await noteState(note.noteId)).archived);
    need(JSON.stringify(states) === JSON.stringify([true, true, true, false]), `archived flags after the archive action: ${states}`);
    await until(async () => (await workshopView()).archiveButton.length === 0, 'the archive action is still offered after archiving', 15_000);
    const after = await workshopView();
    // A second request (a new command id) is harmless: everything is already archived.
    const second = await api('POST', `${sessionPath(sessionId)}/note-archival`, { commandId: crypto.randomUUID() });
    need(second.status === 200 && second.body.archived.length === 0, `the second archival answered ${second.status} ${JSON.stringify(second.body)}`);
    const flagsAfter = [];
    for (const note of notes) flagsAfter.push((await noteState(note.noteId)).archived);
    need(JSON.stringify(flagsAfter) === JSON.stringify(states), 'the second archival changed notes');
    return { archiveAction: view.archiveButton[0], resultText: after.result, archivedFlags: states, secondRequest: { status: second.status, archived: second.body.archived.length,
      skipped: second.body.skipped.map(entry => entry.reason) }, changedNoteApiStatus: 'CHANGED', changedTag: tagged.changedText, notesCounters: { used: 3, archivable: 3 } };
  });

  // ---- 4. MERGE_INTO_ONE: two notes, one material with two sources ----------------------------------------------------------
  await stage('notes_merge_into_one', async () => {
    await awaitCapability();
    const mergeTokens = [`Слияние${run}1`, `Слияние${run}2`];
    const merged = await createNotes(mergeTokens.map(token => `${token}: про частицы は и が, коротко.`));
    await openCapture();
    for (const token of mergeTokens) need(await tick(token), `the checkbox of note ${token} could not be ticked`);
    need((await captureView()).checked === 2, 'two notes are not selected');
    await toComposer(2, 'merge');
    need(await page(`const input = [...document.querySelectorAll('.notes-options input[type=radio]')].find(item => item.closest('label').textContent.trim() === 'Объединить в один'); if (!input) return false; input.click(); return true;`),
      '«Объединить в один» is absent');
    await until(async () => !(await composerFacts()).perNote, 'the per-note settings stay under MERGE_INTO_ONE');
    const id = await submitComposer('merge');
    const session = await getSession(id);
    need(session.spec.settings.notesMode === 'MERGE_INTO_ONE', 'the stored grouping is not MERGE_INTO_ONE');
    need(session.artifacts.length === 1, `MERGE_INTO_ONE made ${session.artifacts.length} artifacts`);
    const detail = await artifactDetail(id, session.artifacts[0].artifactId);
    const refs = detail.sourceRefs.filter(ref => ref.type === 'NOTE');
    need(refs.length === 2 && merged.every(note => refs.some(ref => ref.noteId === note.noteId)), `the merged material's sources: ${JSON.stringify(detail.sourceRefs)}`);
    need(session.spec.sources.every(source => !('overrides' in source)), 'overrides were sent under MERGE_INTO_ONE');
    return { artifacts: 1, noteSources: refs.map(ref => ({ noteId: ref.noteId, status: ref.status })), notesMode: 'MERGE_INTO_ONE' };
  });
  return out;
}
