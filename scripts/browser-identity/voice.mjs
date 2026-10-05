// Real-browser check of voice input (#298, AI-15): dictation and spoken answers against the real Learning API with the Stub transcription
// (`--generation`; never a real provider, no key, no network). Chrome's SYNTHETIC microphone (a tone) is the recording: this is never a
// real-microphone test, and the Stub does not listen to it. The Stub answers a fixed text («Тестовая расшифровка голосового ввода.», and
// «Тестовый устный ответ.» for a Study answer); the frontend has no test seam, so where the scenario needs another text or a slow provider
// the harness adds the `X-Stub-Transcript` header (percent-encoded UTF-8, honoured only by the Stub) to the real request the page sends.
// Runs at the end of the Workshop scenarios on the signed-in account's tab, in a deck of its own. Node 24 built-ins only.
//
// Driven for real: the mic button of the composer (consent dialog on the first press, Esc, «Согласен», recording, «Распознаю…», the text at
// the caret and NOT sent, focus in the field), the «Попросить Мнему…» window and the «На потом» field (the same), Study (a FREE_RESPONSE
// with TEXT_OR_SPEECH: «Ответить голосом», the transcript edited and sent as `answerSource: SPEECH`; typed answers as TYPED / no member),
// 1440/390/320 px states, reduced motion (nothing pulses), the keyboard path, and the calm 429 message with minutes. The state the page is
// compared with is read through the authenticated API. A broken step is a finding for the product, never something to work around: it
// throws, a failure screenshot and a .txt are written, and the run fails with the step name.
import { writeFile } from 'node:fs/promises';
import { join } from 'node:path';

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const DICTATION = 'Тестовая расшифровка голосового ввода.';
const SPOKEN_ANSWER = 'Тестовый устный ответ.';
const WINDOW_PROMPT = 'Глаголы движения: правка выделенного фрагмента';
const MIC = 'app-mic-button .mic-row .mic-button';
const KEYS = { Enter: ['Enter', 'Enter', 13], Escape: ['Escape', 'Escape', 27], Tab: ['Tab', 'Tab', 9], Backspace: ['Backspace', 'Backspace', 8] };

const paragraph = (text, index) => ({ id: `00000000-0000-4000-8000-${String(index * 2 + 1).padStart(12, '0')}`, type: 'paragraph', version: 1, attrs: {},
  content: [{ id: `00000000-0000-4000-8000-${String(index * 2 + 2).padStart(12, '0')}`, type: 'text', version: 1, attrs: { text, marks: [] }, content: [] }] });
const nativeDocument = lines => ({ formatVersion: 1, root: { id: '00000000-0000-4000-8000-000000000000', type: 'doc', version: 1, attrs: {},
  content: lines.map(paragraph) } });

export async function runWorkshopVoice(ctx, h) {
  const { tab, config, SafeFailure, until, navigate, saveScreenshot, setStep, setRequestHeaders, bearer } = ctx;
  const { api, page, need, has, settle, metrics, desktop, awaitCapability, activeSessions, sessionPath: baseSessionPath, location } = h;
  const out = { stub: true, syntheticMicrophone: true, stages: {}, screenshots: [] };
  const startedAt = Date.now();

  let deck = null;
  let micFocusScope = null;
  const failureShot = async name => { try { await saveScreenshot(`failure-voice-${name}.png`, tab); } catch { /* the original failure is the verdict */ } };
  const step = async (name, body) => {
    setStep(`voice_${name}`);
    try { const result = await body(); out.stages[name] = result ?? true; return result; } catch (error) {
      await failureShot(name);
      let held = '';
      try {
        held = ' | speech answers: ' + speechAnswers.slice(-6).join(', ') + ' | page: ' + JSON.stringify(await micFacts(micFocusScope ?? 'main')).slice(0, 400);
      } catch { /* the page is away */ }
      await writeFile(join(config.output, `failure-voice-${name}.txt`),
        (error instanceof SafeFailure ? error.message : `driver: ${String(error?.message ?? error).slice(0, 160)}`) + held + '\n').catch(() => {});
      throw error;
    }
  };
  const shot = async name => { await saveScreenshot(name, tab); out.screenshots.push(name); };

  // ---- the wire: requests the page sent, and the scripted transcript of the Stub ---------------------------------------------------
  const requests = [];
  tab.on('Network.requestWillBeSent', event => {
    const url = new URL(event.request.url);
    requests.push({ at: Date.now(), id: event.requestId, method: event.request.method, path: url.pathname, query: url.search, headers: event.request.headers, postData: event.request.postData ?? null });
  });
  const speechAnswers = [];
  tab.on('Network.responseReceived', event => {
    const path = new URL(event.response.url).pathname;
    if (/^\/api\/speech-/u.test(path)) speechAnswers.push(`${event.response.status} ${path}`);
  });
  let scriptNext = null;
  setRequestHeaders(request => {
    if (scriptNext === null || request.method !== 'POST' || new URL(request.url).pathname !== '/api/speech-inputs') return null;
    const value = scriptNext;
    scriptNext = null;
    return [{ name: 'X-Stub-Transcript', value: encodeURIComponent(value) }];
  });
  const sentSince = (since, test) => requests.filter(entry => entry.at >= since && test(entry));
  const speechPosts = since => sentSince(since, entry => entry.method === 'POST' && entry.path === '/api/speech-inputs');
  const header = (entry, name) => Object.entries(entry.headers).find(([key]) => key.toLowerCase() === name)?.[1] ?? null;
  const inputIds = [];
  const recordInput = async entry => {
    const body = await tab.call('Network.getResponseBody', { requestId: entry.id }).catch(() => null);
    const id = body === null ? null : JSON.parse(body.body)?.speechInputId ?? null;
    if (id !== null) inputIds.push(id);
    return id;
  };

  // ---- low-level input -------------------------------------------------------------------------------------------------------------
  const press = async name => {
    const [key, code, virtualKeyCode] = KEYS[name];
    const event = { key, code, windowsVirtualKeyCode: virtualKeyCode, nativeVirtualKeyCode: virtualKeyCode, modifiers: 0 };
    await tab.call('Page.bringToFront');
    await tab.call('Input.dispatchKeyEvent', name === 'Enter'
      ? { type: 'keyDown', text: '\r', unmodifiedText: '\r', ...event } : { type: 'rawKeyDown', ...event });
    await tab.call('Input.dispatchKeyEvent', { type: 'keyUp', ...event });
  };
  const insertText = async text => { await tab.call('Page.bringToFront'); await tab.call('Input.insertText', { text }); };
  const mouse = (type, point, clickCount = 1) => tab.call('Input.dispatchMouseEvent',
    { type, x: point.x, y: point.y, button: type === 'mouseMoved' ? 'none' : 'left', clickCount });
  const clickAt = async (point, clickCount = 1) => {
    await tab.call('Page.bringToFront');
    await mouse('mouseMoved', point); await mouse('mousePressed', point, clickCount); await mouse('mouseReleased', point, clickCount);
    await settle();
  };
  const centreOf = (selector, label = null) => page(`const [selector, label] = args;
    const pool = [...document.querySelectorAll(selector)].filter(node => { const rect = node.getBoundingClientRect(); return rect.width > 0 && rect.height > 0; });
    const node = label === null ? pool[0] : pool.find(item => item.textContent.replace(/\\s+/g, ' ').replaceAll('\\u00a0', ' ').trim() === label);
    if (!node) return null;
    if (node.getAttribute('aria-disabled') === 'true') return 'disabled';
    node.scrollIntoView({ block: 'center', behavior: 'instant' });
    const rect = node.getBoundingClientRect();
    return { x: rect.left + Math.min(rect.width / 2, 40), y: rect.top + Math.min(rect.height / 2, 14) };`, selector, label);
  const click = async (selector, label = null) => {
    const point = await centreOf(selector, label);
    need(point !== null && point !== 'disabled', `«${label ?? selector}» is ${point === null ? 'absent' : 'disabled'}`);
    await clickAt(point);
  };

  // ---- the page --------------------------------------------------------------------------------------------------------------------
  /** What the microphone of `scope` shows: the button, the recording markers, the one polite status and the disclosure. */
  const micFacts = scope => page(`const text = value => (value ?? '').replace(/\\s+/gu, ' ').replaceAll('\\u00a0', ' ').trim();
    const root = document.querySelector(args[0] + ' app-mic-button');
    const active = document.activeElement;
    if (!root) return { present: false };
    const button = root.querySelector('.mic-row .mic-button');
    const dialog = root.querySelector('dialog');
    return { present: true, button: text(button?.textContent), disabled: button?.getAttribute('aria-disabled') === 'true', recording: Boolean(root.querySelector('.is-recording')),
      quiet: [...root.querySelectorAll('.mic-quiet')].map(node => text(node.textContent)), clock: text(root.querySelector('.mic-clock')?.textContent),
      status: text(root.querySelector('.mic-status')?.textContent), statusRole: root.querySelector('.mic-status')?.getAttribute('role') ?? null, usage: text(root.querySelector('.mic-usage')?.textContent),
      dialog: dialog ? { open: dialog.open, modal: dialog.matches(':modal'), label: text(document.getElementById(dialog.getAttribute('aria-labelledby'))?.textContent),
        text: text(dialog.textContent), buttons: [...dialog.querySelectorAll('button')].map(node => text(node.textContent)), focusInside: dialog.contains(active),
        focus: dialog.contains(active) ? text(active.textContent) : null } : null,
      focus: active === document.body ? 'body' : { tag: active.tagName.toLowerCase(), cls: String(active.className).slice(0, 24), text: text(active.textContent).slice(0, 30), inMic: root.contains(active) } };`, scope);
  const geometry = scope => page(`const doc = document.documentElement;
    const root = document.querySelector(args[0] + ' app-mic-button');
    const small = [...(root?.querySelectorAll('.mic-button, .mic-quiet') ?? [])].map(node => { const rect = node.getBoundingClientRect();
      return { text: (node.textContent || '').trim().slice(0, 24), w: Math.round(rect.width), h: Math.round(rect.height) }; }).filter(entry => entry.w > 0 && entry.h < 43.5);
    const wide = [...(root?.querySelectorAll('*') ?? [])].filter(node => { const rect = node.getBoundingClientRect(); return rect.width > 0 && rect.right > doc.clientWidth + 1; })
      .map(node => node.tagName.toLowerCase() + '.' + String(node.className).split(' ')[0]).slice(0, 6);
    return { scrollWidth: doc.scrollWidth, clientWidth: doc.clientWidth, wide, small };`, scope);
  const needFits = async (scope, label) => {
    const facts = await geometry(scope);
    need(facts.scrollWidth <= facts.clientWidth, `${label}: the page overflows horizontally (${facts.scrollWidth} > ${facts.clientWidth}: ${facts.wide.join(' ')})`);
    need(facts.wide.length === 0, `${label}: the microphone runs past the screen edge (${facts.wide.join(' ')})`);
    need(facts.small.length === 0, `${label}: controls below 44 px: ${JSON.stringify(facts.small)}`);
  };
  const fieldOf = selector => page(`const field = document.querySelector(args[0]); const active = document.activeElement;
    return field ? { value: field.value, focused: active === field, start: field.selectionStart, end: field.selectionEnd } : null;`, selector);
  /** Puts `text` in the field with real input and the caret at `caret` (null: the end). */
  const fill = async (selector, text, caret = null) => {
    need(await page(`const field = document.querySelector(args[0]); if (!field) return false; field.focus(); field.select(); return document.activeElement === field;`, selector), `${selector} could not take focus`);
    await insertText(text);
    if (caret !== null) await page(`const field = document.querySelector(args[0]); field.setSelectionRange(args[1], args[1]); return true;`, selector, caret);
  };
  const waitIdle = (scope, label, timeout = 30_000) => until(async () => {
    const facts = await micFacts(scope);
    return facts.present && !facts.recording && facts.button !== 'Распознаю…' && facts.dialog?.open !== true && facts.status !== '' && !/Запись идёт/u.test(facts.status);
  }, `${label}: the microphone did not come back to rest`, timeout);
  const waitRecording = (scope, label) => until(async () => (await micFacts(scope)).recording === true, `${label}: the recording did not start`, 15_000);
  /** A whole dictation with the mouse: press, record `hold` ms, stop. Returns the speech request of this recording. */
  const dictate = async (scope, label, { hold = 2000, script = null, onRecording = null, onTranscribing = null } = {}) => {
    const since = Date.now();
    await click(`${scope} ${MIC}`);
    await waitRecording(scope, label);
    if (onRecording !== null) await onRecording();
    await sleep(hold);
    scriptNext = script;
    await click(`${scope} ${MIC}`, 'Остановить запись');
    if (onTranscribing !== null) {
      await until(async () => (await micFacts(scope)).button === 'Распознаю…', `${label}: «Распознаю…» did not appear`, 6_000);
      await onTranscribing();
    }
    await waitIdle(scope, label);
    const posts = speechPosts(since);
    need(posts.length === 1, `${label}: the page sent ${posts.length} speech inputs for one recording`);
    return posts[0];
  };
  const needRequest = (entry, purpose, label) => {
    const type = header(entry, 'content-type') ?? '';
    const duration = Number(header(entry, 'x-audio-duration-ms'));
    need(/^audio\/(mp4|mpeg|ogg|webm)/u.test(type), `${label}: the recording is sent as «${type}»`);
    need(duration >= 1500 && duration <= 4500, `${label}: the recorder measured ${duration} ms for about two seconds`);
    need(/^[0-9a-f-]{36}$/u.test(header(entry, 'idempotency-key') ?? ''), `${label}: no Idempotency-Key`);
    need(new URLSearchParams(entry.query).get('purpose') === purpose, `${label}: purpose is ${new URLSearchParams(entry.query).get('purpose')}, not ${purpose}`);
    return { type, duration };
  };
  /** After a transcript, nothing but speech (and reads, and the stateless cost estimate of the composer) left the page: the text is never sent. */
  const nothingSent = (since, label) => {
    const writes = sentSince(since, entry => entry.method !== 'GET' && entry.method !== 'OPTIONS' && entry.method !== 'HEAD' && !/^\/api\/speech-(inputs|consent)/u.test(entry.path)
      && !/^\/api\/(usage|capabilities)/u.test(entry.path) && !/\/generation-estimates$/u.test(entry.path));
    need(writes.length === 0, `${label}: the page sent ${JSON.stringify(writes.map(entry => `${entry.method} ${entry.path}`))} after the transcript: it must not be sent`);
  };
  const rawSpeech = (method, path, bodyBytes = null, headers = {}) => page(`const [base, method, path, token, size, headers] = args;
    const response = await fetch(base + path, { method, credentials: 'omit', headers: { Authorization: 'Bearer ' + token, ...headers },
      body: size === null ? undefined : new Uint8Array(size).fill(7) });
    const text = await response.text(); let body = null; try { body = text ? JSON.parse(text) : null; } catch { body = { unparsable: text.slice(0, 60) }; }
    return { status: response.status, body, retryAfter: response.headers.get('Retry-After') };`,
    config.frontend, method, path, bearer, bodyBytes, headers);

  // ======================================================================================================================
  // 0. The fixture: a deck, a material, three exercises, and the capability
  // ======================================================================================================================
  await desktop();
  await awaitCapability();
  for (const active of await activeSessions()) {
    const gone = await api('DELETE', baseSessionPath(active.sessionId));
    need([204, 404].includes(gone.status), `deleting an earlier session answered ${gone.status}`);
  }
  let session = null;
  let artifactId = null;
  let consentTerms = null;
  await step('fixture', async () => {
    const created = await api('POST', '/api/decks', { commandId: crypto.randomUUID(), metadata: { title: 'Голосовой ввод: проверка', description: '' } });
    need(created.status === 201, `POST deck answered ${created.status}`);
    deck = { deckId: created.body.deck?.deckId ?? created.body.deckId };
    need(deck.deckId, 'the created deck has no id');
    const capabilities = await api('GET', '/api/capabilities');
    need(capabilities.status === 200 && capabilities.body?.speechToText?.available === true,
      `GET /api/capabilities does not report speechToText available (Stub transcription): ${JSON.stringify(capabilities.body?.speechToText ?? null)}`);
    const consent = await api('GET', '/api/speech-consent');
    need(consent.status === 200 && consent.body?.accepted === null, `the account already has a speech consent: ${JSON.stringify(consent.body)}`);
    consentTerms = consent.body.required;
    need(['RU', 'ABROAD'].includes(consentTerms.processing) && consentTerms.version, `the required consent is ${JSON.stringify(consentTerms)}`);
    const current = await api('GET', `/api/decks/${deck.deckId}`);
    const made = await api('POST', `/api/decks/${deck.deckId}/items`, { commandId: crypto.randomUUID(), expectedDeckRevisionId: current.body.revisionId,
      document: nativeDocument(['Планировщик выбирает Seq Scan или Index Scan.']) }, { 'If-Match': current.etag });
    need(made.status === 201, `POST item answered ${made.status}`);
    const memberKey = made.body.changes?.[0]?.memberKey ?? made.body.memberKey;
    const item = (await api('GET', `/api/decks/${deck.deckId}/items/${memberKey}`)).body;
    const makeExercise = async (title, prompt, accepted, responseInput) => {
      const deckNow = await api('GET', `/api/decks/${deck.deckId}`);
      const exercise = await api('POST', `/api/decks/${deck.deckId}/exercises`, { commandId: crypto.randomUUID(), expectedDeckRevisionId: deckNow.body.revisionId,
        objective: { operation: 'create', title },
        exercise: { type: 'FREE_RESPONSE', schemaVersion: 2, enabled: true, subject: { memberKey, itemRevisionId: item.itemRevisionId },
          content: { prompt: [{ kind: 'TEXT', text: prompt }], reference: [], responseInput },
          answerKey: { kind: 'TEXT', accepted: [accepted], normalization: ['UNICODE_NFC', 'TRIM', 'CASE_FOLD'], matchingMode: 'STRICT' },
          evaluatorPolicy: { id: 'deterministic-text', version: '1' } } }, { 'If-Match': deckNow.etag });
      need(exercise.status === 201, `POST exercise «${title}» answered ${exercise.status} ${JSON.stringify(exercise.body?.code ?? exercise.body?.detail ?? null)}`);
    };
    await makeExercise('Устный ответ A', 'Назовите первый способ чтения таблицы (устно).', 'тестовый устный ответ', 'TEXT_OR_SPEECH');
    await makeExercise('Устный ответ B', 'Назовите второй способ чтения таблицы (письменно).', 'memory', 'TEXT_OR_SPEECH');
    await makeExercise('Только текст C', 'Назовите третий способ чтения таблицы (только текст).', 'memory', 'TEXT');
    // The proposal the «Попросить Мнему…» window works on: the Stub's document depends on the prompt; one with a top-level paragraph is needed.
    const sessionOf = id => `/api/decks/${deck.deckId}/generation-sessions/${id}`;
    for (const prompt of [WINDOW_PROMPT, ...Array.from({ length: 24 }, (_, index) => `Глаголы движения, вариант ${index + 1}`)]) {
      const started = await api('POST', `/api/decks/${deck.deckId}/generation-sessions`, { commandId: crypto.randomUUID(), spec: {
        kind: 'MATERIALS', prompt, sources: [], settings: { effort: 'SHORT', notesMode: 'ONE_PER_NOTE',
          media: { audio: { enabled: false, lang: 'ru', voice: null }, imageSearch: false }, factCheck: false, similarToDeck: false, planFirst: false, budgetPercent: null } } });
      need(started.status === 201, `POST generation-sessions answered ${started.status}`);
      const id = started.body.sessionId;
      await until(async () => (await api('GET', sessionOf(id))).body.artifacts.every(artifact => ['PROPOSED', 'FAILED'].includes(artifact.state)), 'the proposal did not settle', 90_000);
      const state = (await api('GET', sessionOf(id))).body;
      need(state.artifacts[0]?.state === 'PROPOSED', `the proposal is ${state.artifacts[0]?.state}`);
      const detail = (await api('GET', `${sessionOf(id)}/artifacts/${state.artifacts[0].artifactId}`)).body;
      if (detail.revision.payload.document.root.content.some(node => node.type === 'paragraph')) { session = id; artifactId = state.artifacts[0].artifactId; break; }
      const dropped = await api('DELETE', sessionOf(id));
      need([204, 404].includes(dropped.status), `deleting a spare proposal answered ${dropped.status}`);
    }
    need(session !== null, 'the Stub produced no proposal with a top-level paragraph');
    return { processing: consentTerms.processing, version: consentTerms.version, exercises: 3 };
  });
  const artifactTurns = async () => (await api('GET', `/api/decks/${deck.deckId}/generation-sessions/${session}/artifacts/${artifactId}`)).body.turns.length;

  // ======================================================================================================================
  // 1. The composer: consent, Esc, «Согласен», record, «Распознаю…», the text at the caret and not sent, focus stays
  // ======================================================================================================================
  const COMPOSER = 'app-generation-composer';
  const COMPOSER_FIELD = 'app-generation-composer textarea';
  await step('composer_consent_and_dictation', async () => {
    micFocusScope = COMPOSER;
    await navigate(`/decks/${deck.deckId}/materials/new`, tab);
    await until(() => has(COMPOSER_FIELD), 'the composer did not open', 25_000);
    await until(async () => (await micFacts(COMPOSER)).present, 'the composer shows no microphone although speechToText is available', 15_000);
    const idle = await micFacts(COMPOSER);
    need(idle.button === 'Начать запись' && !idle.recording && idle.dialog?.open === false, `the idle microphone is ${JSON.stringify(idle)}`);
    need(idle.statusRole === 'status', 'the microphone has no polite status region');
    await needFits(COMPOSER, 'the idle microphone at 1440');
    await shot('voice-composer-idle-1440.png');
    await fill(COMPOSER_FIELD, 'Начало конец', 7);
    // The first press asks for the consent: a modal dialog that says where the voice is processed.
    const since = Date.now();
    await click(`${COMPOSER} ${MIC}`);
    await until(async () => (await micFacts(COMPOSER)).dialog?.open === true, 'the first press did not open the consent dialog', 8_000);
    const dialog = (await micFacts(COMPOSER)).dialog;
    need(dialog.modal === true && dialog.label === 'Голосовой ввод', `the disclosure is ${JSON.stringify({ modal: dialog.modal, label: dialog.label })}`);
    const where = consentTerms.processing === 'RU' ? 'Речь распознаётся на наших серверах в России.' : 'зарубежный сервис распознавания';
    need(dialog.text.includes(where), `the disclosure does not say where the voice is processed («${where}»): «${dialog.text}»`);
    need(dialog.text.includes('Запись удаляется сразу после распознавания'), 'the disclosure does not say the recording is deleted');
    need(dialog.text.includes('не дольше минуты'), 'the disclosure does not say how long the recording is');
    need(dialog.buttons.join() === 'Согласен,Не сейчас', `the disclosure buttons are ${JSON.stringify(dialog.buttons)}`);
    need(dialog.focusInside, 'focus did not move into the disclosure');
    await shot('voice-consent-1440.png');
    // Esc closes it like «Не сейчас»: no consent, no recording, focus back on the microphone, the field untouched.
    await press('Escape');
    await until(async () => (await micFacts(COMPOSER)).dialog?.open === false, 'Esc did not close the disclosure', 5_000);
    await sleep(300);
    const closed = await micFacts(COMPOSER);
    need(closed.button === 'Начать запись' && !closed.recording, `after Esc the microphone is ${JSON.stringify(closed)}`);
    need(closed.focus?.inMic === true, `after Esc focus is ${JSON.stringify(closed.focus)}, not on the microphone`);
    need((await api('GET', '/api/speech-consent')).body.accepted === null, 'Esc recorded a consent');
    need(sentSince(since, entry => entry.method === 'PUT' && entry.path === '/api/speech-consent').length === 0, 'Esc sent a consent');
    need((await fieldOf(COMPOSER_FIELD)).value === 'Начало конец', 'Esc changed the text of the field');
    // Press again and accept with the keyboard.
    await press('Enter');
    await until(async () => (await micFacts(COMPOSER)).dialog?.open === true, 'Enter on the focused microphone did not open the disclosure', 8_000);
    need((await micFacts(COMPOSER)).dialog.focus === 'Согласен', `the first focus in the disclosure is «${(await micFacts(COMPOSER)).dialog.focus}»`);
    await press('Enter');
    await waitRecording(COMPOSER, 'after «Согласен»');
    const consent = (await api('GET', '/api/speech-consent')).body;
    need(consent.accepted !== null && String(consent.accepted.version) === String(consentTerms.version) && consent.accepted.processing === consentTerms.processing,
      `«Согласен» did not record the required consent: ${JSON.stringify(consent)}`);
    need(sentSince(since, entry => entry.method === 'PUT' && entry.path === '/api/speech-consent').length === 1, 'the page sent the consent not exactly once');
    // Recording: the words, the stop button, the clock; the transcript is scripted slow so «Распознаю…» can be seen.
    const recording = await micFacts(COMPOSER);
    need(recording.button === 'Остановить запись' && recording.quiet.join() === 'Отмена', `the recording microphone is ${JSON.stringify(recording)}`);
    need(/Запись идёт/u.test(recording.status), `the status says «${recording.status}»`);
    await shot('voice-composer-recording-1440.png');
    await sleep(2200);
    const clock = (await micFacts(COMPOSER)).clock;
    need(/^0:0[2-9] из 1:00$/u.test(clock), `the clock reads «${clock}» after about two seconds`);
    scriptNext = `[[stub:stt-slow]] ${DICTATION}`;
    await click(`${COMPOSER} ${MIC}`, 'Остановить запись');
    await until(async () => (await micFacts(COMPOSER)).button === 'Распознаю…', '«Распознаю…» did not appear', 6_000);
    const busy = await micFacts(COMPOSER);
    need(busy.disabled === true && busy.status === 'Распознаю…' && busy.quiet.join() === 'Отмена', `the transcribing microphone is ${JSON.stringify(busy)}`);
    await shot('voice-composer-transcribing-1440.png');
    await waitIdle(COMPOSER, 'the composer dictation');
    const posts = speechPosts(since);
    need(posts.length === 1, `${posts.length} speech inputs for one recording`);
    const wire = needRequest(posts[0], 'COMPOSER', 'the composer recording');
    need(new URLSearchParams(posts[0].query).get('deckId') === deck.deckId, 'the composer did not send the deck as recognition hints');
    const id = await recordInput(posts[0]);
    const field = await fieldOf(COMPOSER_FIELD);
    const expected = `Начало ${DICTATION} конец`;
    need(field.value === expected, `the field holds «${field.value}», not «${expected}»`);
    need(field.focused, 'the field lost focus after the transcript');
    need(field.start === field.end && field.start === `Начало ${DICTATION}`.length, `the caret is at ${field.start}-${field.end}, not right after the inserted text`);
    const done = await micFacts(COMPOSER);
    need(done.status === 'Текст добавлен в поле. Проверьте его и отправьте сами.' && done.button === 'Начать запись', `the microphone ended as ${JSON.stringify(done)}`);
    need((await location()).endsWith('/materials/new'), 'the page left the composer: the transcript must not be sent');
    nothingSent(since, 'composer');
    await shot('voice-composer-inserted-1440.png');
    return { consentRecorded: true, escClosedWithoutConsent: true, wire, text: expected, inputId: id, processing: consentTerms.processing };
  });

  // ======================================================================================================================
  // 2. The «Попросить Мнему…» window and the «На потом» field: the same behaviour
  // ======================================================================================================================
  await step('ask_window_dictation', async () => {
    micFocusScope = 'app-ai-prompt-window';
    await navigate(`/decks/${deck.deckId}/workshop/${session}`, tab);
    await until(() => has('app-proposal-document .native-document'), 'the Workshop did not render the proposal', 30_000);
    const point = await page(`const node = document.querySelector('app-proposal-document .native-document > p');
      if (!node) return null; node.scrollIntoView({ block: 'center', behavior: 'instant' }); const rect = node.getBoundingClientRect(); return { x: rect.left + 24, y: rect.top + 12 };`);
    need(point !== null, 'the proposal has no paragraph to select');
    await clickAt(point, 3);
    await until(() => has('app-proposal-document .selection-actions:popover-open'), 'the selection offered no action', 8_000);
    await click('app-proposal-document .selection-actions button', 'Попросить Мнему…');
    await until(() => has('app-ai-prompt-window [popover]:popover-open'), 'the window did not open', 8_000);
    await until(async () => (await micFacts('app-ai-prompt-window')).present, 'the window offers no microphone although speechToText is available', 8_000);
    const turns = await artifactTurns();
    await fill('app-ai-prompt-window textarea', 'Сделай');
    const since = Date.now();
    const entry = await dictate('app-ai-prompt-window', 'the window dictation', { script: 'проще и короче' });
    const wire = needRequest(entry, 'EDIT', 'the window recording');
    await recordInput(entry);
    const field = await fieldOf('app-ai-prompt-window textarea');
    need(field.value === 'Сделай проще и короче', `the window field holds «${field.value}»`);
    need(field.focused, 'the window field lost focus after the transcript');
    need(await has('app-ai-prompt-window [popover]:popover-open'), 'the window closed after the transcript');
    need(await artifactTurns() === turns, 'the transcript was sent as an edit');
    nothingSent(since, 'window');
    await shot('voice-window-inserted-1440.png');
    await metrics(390, 844, 1, true); await settle();
    await shot('voice-window-390.png');
    await desktop(); await settle();
    return { wire, text: field.value, turnsUnchanged: true };
  });

  await step('capture_dictation_by_keyboard', async () => {
    micFocusScope = 'main';
    await navigate(`/decks/${deck.deckId}/capture`, tab);
    await until(() => has('#capture-text'), 'the capture page did not open', 25_000);
    await until(async () => (await micFacts('main')).present, 'the capture page shows no microphone although speechToText is available', 15_000);
    await fill('#capture-text', 'Идея:');
    // The keyboard path: Tab from the field reaches the microphone, Enter records, Enter stops, focus is never lost.
    const since = Date.now();
    await press('Tab');
    need((await micFacts('main')).focus?.inMic === true, `Tab from the field reached ${JSON.stringify((await micFacts('main')).focus)}, not the microphone`);
    await press('Enter');
    await waitRecording('main', 'the keyboard recording');
    const recording = await micFacts('main');
    need(recording.focus?.inMic === true && recording.focus.text.startsWith('Остановить запись'), `after Enter the focus is ${JSON.stringify(recording.focus)}: the stop button must hold it`);
    await sleep(2000);
    await press('Enter');
    await until(async () => { const facts = await micFacts('main'); return facts.button === 'Распознаю…' || (!facts.recording && facts.status.startsWith('Текст добавлен')); }, 'Enter on the stop button did not stop', 8_000);
    await waitIdle('main', 'the capture dictation');
    const posts = speechPosts(since);
    need(posts.length === 1, `${posts.length} speech inputs for one recording`);
    const wire = needRequest(posts[0], 'CAPTURE', 'the capture recording');
    await recordInput(posts[0]);
    const field = await fieldOf('#capture-text');
    need(field.value === `Идея: ${DICTATION}` && field.focused, `the capture field is ${JSON.stringify(field)}`);
    nothingSent(since, 'capture');
    await shot('voice-capture-inserted-1440.png');
    return { wire, text: field.value, keyboardOnly: true };
  });

  // ======================================================================================================================
  // 3. Study: «Ответить голосом», the transcript edited and sent as SPEECH; typed answers as TYPED (and no member without the option)
  // ======================================================================================================================
  await step('study_spoken_and_typed_answers', async () => {
    micFocusScope = 'app-learner-exercise';
    await navigate(`/decks/${deck.deckId}/study`, tab);
    await until(() => has('.session-setup'), 'the Study setup did not open', 25_000);
    await click('.preset-card button', 'Начать стандартную');
    const answers = {};
    for (let index = 0; index < 3; index++) {
      await until(() => has('app-learner-exercise textarea[data-answer-control]'), `card ${index + 1} did not appear`, 25_000);
      const prompt = await page(`return document.querySelector('.exercise-prompt')?.textContent.replace(/\\s+/g, ' ').trim() ?? '';`);
      const key = /\(устно\)/u.test(prompt) ? 'A' : /\(письменно\)/u.test(prompt) ? 'B' : /только текст/u.test(prompt) ? 'C' : null;
      need(key !== null, `an unexpected card: «${prompt}»`);
      const mic = await micFacts('app-learner-exercise');
      const since = Date.now();
      if (key === 'C') {
        need(mic.present === false, 'a TEXT exercise offers «Ответить голосом»');
        await fill('app-learner-exercise textarea[data-answer-control]', 'memory');
      } else {
        need(mic.present && mic.button === 'Ответить голосом', `card ${key} shows the microphone as ${JSON.stringify(mic)}`);
        if (key === 'A') {
          await shot('voice-study-idle-1440.png');
          const entry = await dictate('app-learner-exercise', 'the Study dictation');
          const wire = needRequest(entry, 'STUDY_ANSWER', 'the Study recording');
          await recordInput(entry);
          const field = await fieldOf('app-learner-exercise textarea[data-answer-control]');
          need(field.value === SPOKEN_ANSWER && field.focused, `the answer field is ${JSON.stringify(field)} after the transcript`);
          need(sentSince(since, entry2 => entry2.method === 'POST' && /\/attempts$/u.test(entry2.path)).length === 0, 'the spoken answer was sent by itself');
          await shot('voice-study-inserted-1440.png');
          // The learner edits it (the final period goes) and sends: still SPEECH, because the text came from speech.
          await press('Backspace');
          need((await fieldOf('app-learner-exercise textarea[data-answer-control]')).value === 'Тестовый устный ответ', 'Backspace did not edit the transcript');
          answers.A = { wire };
        } else {
          // The learner types: the option is there, but nothing was spoken.
          await fill('app-learner-exercise textarea[data-answer-control]', 'memory');
        }
      }
      await click('app-learner-exercise button[data-submit]');
      await until(async () => sentSince(since, entry => entry.method === 'POST' && /\/attempts$/u.test(entry.path)).length === 1, `card ${key}: no answer was sent`, 10_000);
      const body = JSON.parse(sentSince(since, entry => entry.method === 'POST' && /\/attempts$/u.test(entry.path))[0].postData ?? '{}');
      const response = body.response ?? body.answer ?? body;
      const source = response.answerSource;
      if (key === 'A') need(source === 'SPEECH' && response.text === 'Тестовый устный ответ', `the edited spoken answer was sent as ${JSON.stringify(response)}`);
      if (key === 'B') need(source === 'TYPED' && response.text === 'memory', `the typed answer of a speech-capable exercise was sent as ${JSON.stringify(response)}`);
      if (key === 'C') need(source === undefined && response.text === 'memory', `the answer of a TEXT exercise was sent as ${JSON.stringify(response)}`);
      answers[key] = { ...answers[key], sent: { answerSource: source ?? null } };
      await until(() => has('.feedback-card button.primary'), `card ${key}: no feedback arrived`, 20_000);
      await click('.feedback-card button.primary');
      await settle();
    }
    await until(() => has('.completion'), 'the session did not complete after three answers', 25_000);
    return answers;
  });

  // ======================================================================================================================
  // 4. The input rows: DONE, deleted on request (the audio is deleted by the server when transcription ends)
  // ======================================================================================================================
  await step('input_rows_done_and_deleted', async () => {
    need(inputIds.length >= 4, `the scenario knows ${inputIds.length} speech inputs`);
    const first = await api('GET', `/api/speech-inputs/${inputIds[0]}`);
    need(first.status === 200 && first.body.state === 'DONE' && first.body.errorCode === null && first.body.text === DICTATION, `the composer input reads ${first.status} ${JSON.stringify(first.body)}`);
    need(first.body.seconds >= 2 && first.body.seconds <= 5 && Date.parse(first.body.expiresAt) > Date.now(), `the composer input meters ${first.body.seconds} s, expires ${first.body.expiresAt}`);
    for (const id of inputIds) {
      const view = await api('GET', `/api/speech-inputs/${id}`);
      need(view.status === 200 && view.body.state === 'DONE', `input ${id} is ${view.status} ${view.body?.state}`);
    }
    const removed = await api('DELETE', `/api/speech-inputs/${inputIds[0]}`);
    need(removed.status === 204, `DELETE answered ${removed.status}`);
    const again = await api('DELETE', `/api/speech-inputs/${inputIds[0]}`);
    need(again.status === 204, `a repeated DELETE answered ${again.status}`);
    const gone = await api('GET', `/api/speech-inputs/${inputIds[0]}`);
    need(gone.status === 404, `GET after DELETE answered ${gone.status}`);
    return { inputs: inputIds.length, allDone: true, deleted: 204, afterDelete: 404 };
  });

  // ======================================================================================================================
  // 5. Responsive states, consent at 390/320, reduced motion
  // ======================================================================================================================
  await step('responsive_and_reduced_motion', async () => {
    micFocusScope = COMPOSER;
    const result = {};
    await navigate(`/decks/${deck.deckId}/materials/new`, tab);
    await until(async () => (await micFacts(COMPOSER)).present, 'the composer shows no microphone', 25_000);
    for (const [tag, width, height, mobile] of [['390', 390, 844, true], ['320', 320, 800, false]]) {
      await metrics(width, height, 1, mobile); await settle();
      await needFits(COMPOSER, `the idle microphone at ${width}`);
      await shot(`voice-composer-idle-${tag}.png`);
      // The disclosure again (the consent is withdrawn through the API and the next press asks once more).
      const withdrawn = await api('DELETE', '/api/speech-consent');
      need(withdrawn.status === 204, `DELETE speech-consent answered ${withdrawn.status}`);
      await click(`${COMPOSER} ${MIC}`);
      await until(async () => (await micFacts(COMPOSER)).dialog?.open === true, `the disclosure did not open at ${width}`, 8_000);
      const rect = await page(`const dialog = document.querySelector('app-mic-button dialog'); const box = dialog.getBoundingClientRect();
        const small = [...dialog.querySelectorAll('button')].map(node => Math.round(node.getBoundingClientRect().height)).filter(value => value < 43.5);
        return { left: box.left, right: box.right, top: box.top, bottom: box.bottom, vw: innerWidth, vh: innerHeight, small, scrollable: dialog.scrollHeight > dialog.clientHeight + 1 };`);
      need(rect.left >= 0 && rect.right <= rect.vw + 1 && rect.top >= 0 && rect.bottom <= rect.vh + 1, `the disclosure runs off the screen at ${width}: ${JSON.stringify(rect)}`);
      need(rect.small.length === 0, `disclosure buttons below 44 px at ${width}: ${JSON.stringify(rect.small)}`);
      await shot(`voice-consent-${tag}.png`);
      await click('app-mic-button dialog button', 'Согласен');
      await waitRecording(COMPOSER, `recording at ${width}`);
      await needFits(COMPOSER, `the recording microphone at ${width}`);
      await shot(`voice-composer-recording-${tag}.png`);
      // «Отмена» leaves nothing behind and returns focus to the microphone.
      const before = speechPosts(0).length;
      await click(`${COMPOSER} .mic-quiet`, 'Отмена');
      await sleep(400);
      const cancelled = await micFacts(COMPOSER);
      need(cancelled.button === 'Начать запись' && !cancelled.recording && cancelled.status === 'Запись отменена.', `after «Отмена» the microphone is ${JSON.stringify(cancelled)}`);
      need(cancelled.focus?.inMic === true, `after «Отмена» focus is ${JSON.stringify(cancelled.focus)}`);
      need(speechPosts(0).length === before, '«Отмена» sent the recording');
      // A short recording with a slow Stub: the transcribing state at this width.
      await fill(COMPOSER_FIELD, 'Слово');
      await dictate(COMPOSER, `the ${width} dictation`, { hold: 1500, script: `[[stub:stt-slow]] ${DICTATION}`, onTranscribing: async () => {
        await needFits(COMPOSER, `the transcribing microphone at ${width}`);
        await shot(`voice-composer-transcribing-${tag}.png`);
      } });
      result[tag] = { fits: true, targets: true };
    }
    await desktop(); await settle();
    // Reduced motion: the recording state moves nothing (a still dot and words), in both modes.
    await tab.call('Emulation.setEmulatedMedia', { features: [{ name: 'prefers-reduced-motion', value: 'reduce' }] });
    await settle();
    await click(`${COMPOSER} ${MIC}`);
    await waitRecording(COMPOSER, 'the reduced-motion recording');
    const motion = await page(`const nodes = [...document.querySelectorAll('app-mic-button, app-mic-button *')];
      const moving = nodes.filter(node => { const style = getComputedStyle(node);
        return style.transitionDuration.split(',').some(value => parseFloat(value) > 0.001) || style.animationName !== 'none'; })
        .map(node => node.tagName.toLowerCase() + '.' + String(node.className).split(' ')[0]).slice(0, 5);
      // An animation that is running and lasts (or repeats) is motion; an instant (0 s) transition is not.
      const animations = document.getAnimations().filter(animation => animation.effect?.target?.closest?.('app-mic-button'))
        .map(animation => ({ type: animation.constructor.name, name: animation.animationName ?? animation.transitionProperty ?? null, state: animation.playState,
          ms: animation.effect.getComputedTiming().duration, iterations: animation.effect.getComputedTiming().iterations, node: animation.effect.target.tagName.toLowerCase() + '.' + String(animation.effect.target.className).split(' ')[0] }))
        .filter(entry => entry.state === 'running' && (entry.ms > 1 || entry.iterations > 1));
      return { reduced: matchMedia('(prefers-reduced-motion: reduce)').matches, moving, animations };`);
    need(motion.reduced === true, 'reduced motion was not emulated');
    need(motion.moving.length === 0 && motion.animations.length === 0, `the microphone moves under reduced motion: ${JSON.stringify(motion)}`);
    await shot('voice-composer-recording-reduced-motion-1440.png');
    await tab.call('Emulation.setEmulatedMedia', { features: [] });
    const normal = await page(`return { animations: document.getAnimations().filter(animation => animation.effect?.target?.closest?.('app-mic-button'))
      .map(animation => ({ type: animation.constructor.name, name: animation.animationName ?? animation.transitionProperty ?? null, state: animation.playState,
        ms: animation.effect.getComputedTiming().duration, iterations: animation.effect.getComputedTiming().iterations }))
      .filter(entry => entry.state === 'running' && (entry.ms > 1 || entry.iterations > 1)) };`);
    need(normal.animations.length === 0, `the recording dot pulses without reduced motion: ${JSON.stringify(normal)}`);
    await click(`${COMPOSER} .mic-quiet`, 'Отмена');
    result.reducedMotion = motion;
    return result;
  });

  // ======================================================================================================================
  // 6. Fair use: more than 20 inputs in ten minutes is a 429 with Retry-After, and the page says it calmly with minutes
  // ======================================================================================================================
  await step('rate_limit_429', async () => {
    micFocusScope = COMPOSER;
    const consent = await api('GET', '/api/speech-consent');
    if (consent.body.accepted === null) need((await api('PUT', '/api/speech-consent', consentTerms)).status === 200, 'PUT speech-consent did not answer 200');
    let limited = null;
    let accepted = 0;
    for (let index = 0; index < 30 && limited === null; index++) {
      const result = await rawSpeech('POST', '/api/speech-inputs?purpose=COMPOSER', 2048, { 'Content-Type': 'audio/webm;codecs=opus', 'Idempotency-Key': crypto.randomUUID(), 'X-Audio-Duration-Ms': '1000' });
      if (result.status === 202) accepted++;
      else if (result.status === 429) limited = result;
      else throw new SafeFailure(`a speech input answered ${result.status} ${JSON.stringify(result.body?.code ?? null)} while filling the rate window`);
    }
    need(limited !== null, 'no 429 after 30 quick speech inputs');
    const retry = Number(limited.retryAfter);
    need(Number.isInteger(retry) && retry >= 1 && retry <= 600, `Retry-After is «${limited.retryAfter}»`);
    need(limited.body?.code === 'RATE_LIMITED', `the 429 carries code ${limited.body?.code}`);
    await navigate(`/decks/${deck.deckId}/materials/new`, tab);
    await until(async () => (await micFacts(COMPOSER)).present, 'the composer shows no microphone', 25_000);
    await fill(COMPOSER_FIELD, 'До');
    await click(`${COMPOSER} ${MIC}`);
    await waitRecording(COMPOSER, 'the rate-limited recording');
    await sleep(1500);
    await click(`${COMPOSER} ${MIC}`, 'Остановить запись');
    await until(async () => /^Слишком много записей подряд/u.test((await micFacts(COMPOSER)).status), 'the 429 was not said on the page', 15_000);
    const facts = await micFacts(COMPOSER);
    const minutes = Number(facts.status.match(/через (\d+) мин\.$/u)?.[1]);
    need(Number.isInteger(minutes) && minutes >= Math.ceil((retry - 40) / 60) && minutes <= Math.ceil(retry / 60) && minutes >= 1,
      `the message says «${facts.status}» for Retry-After ${retry} s`);
    need(facts.button === 'Начать запись' && !facts.recording && facts.disabled === false, `after the 429 the microphone is ${JSON.stringify(facts)}`);
    need((await fieldOf(COMPOSER_FIELD)).value === 'До', 'the 429 changed the field');
    need(!/429|RATE_LIMITED|Retry-After/u.test(await page(`return document.body.innerText;`)), 'the page leaks the status code');
    await shot('voice-rate-limited-1440.png');
    await metrics(390, 844, 1, true); await settle();
    await shot('voice-rate-limited-390.png');
    await desktop(); await settle();
    return { acceptedBefore429: accepted, retryAfter: retry, message: facts.status };
  });

  out.durationMs = Date.now() - startedAt;
  return out;
}
