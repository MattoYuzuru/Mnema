// Real-browser check of the semantic assessment of explanations (#292, AI-20) against the real Learning API with the Stub
// provider (`--assessment`; never a real provider, no key). Runs at the end of the authoring scenarios on the signed-in
// account's tab, in a deck of its own. Node 24 built-ins only.
//
// The user's whole path is the real Angular UI on the real HTTP surface:
//   * the rubric editor: «Проверять смысл ответа с ИИ» on a FREE_RESPONSE, the reference answer, key points with tier and weight,
//     the live 2-3 / 1-4 / 0-2 counters, the refusal to continue while the rule is broken, «Тонкая настройка», save and reopen;
//   * Study: a complete answer is accepted, a partial one lists what is there and what is missing, an answer about pancakes to a
//     question about PostgreSQL is not accepted, a slow grading shows the waiting card and offers «Оценить себя» only after
//     5 seconds, the self-check mode survives a reload and a late grade changes nothing, and a grade can be disputed.
// Only the fixture is made through the authenticated API: the deck, one material and four more AI-checked exercises (the first
// one is authored through the editor). The Stub grades by markers placed in the learner's answer, and otherwise by a lexical
// heuristic (see the Stub's Javadoc); a marker is only used where the scenario needs an exact verdict or a delay.
// A broken step is a finding for the product, never something to work around: it throws, a failure screenshot is written and the
// run fails with the step name.
import { writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

const MATERIAL = { title: 'Как PostgreSQL хранит и обслуживает данные', lines: [
  'Как PostgreSQL хранит и обслуживает данные.',
  'Инерция, B-tree, транзакции и автовакуум встречаются в разных частях системы.',
  'Планировщик выбирает способ чтения таблицы по статистике.'] };

/** Every exercise is a FREE_RESPONSE with rubric v1; `ui` marks the one that is authored through the editor. */
const EXERCISES = {
  inertia: { prompt: 'Объясните, что такое инерция.', objective: 'Инерция: объяснение', ui: true,
    reference: 'Инерция — свойство тела сохранять скорость, пока на него не действуют другие тела.',
    points: [['CORE', 3, 'Говорит о сохранении скорости или состояния движения'],
      ['CORE', 3, 'Называет условие: скорость меняется только при воздействии других тел'],
      ['DETAIL', 1, 'Приводит пример: движение по инерции после выключения двигателя'],
      ['TERM', 1, 'Называет явление словом «инерция»']],
    misconceptions: ['Скорость тела падает сама по себе'], terms: ['инерция', 'инертность'],
    answer: 'Инерция — свойство тела сохранять скорость: тело продолжает движение с той же скоростью, пока на него не действуют другие тела. '
      + 'Например, автомобиль катится после выключения двигателя.' },
  btree: { prompt: 'Объясните, как B-tree ускоряет поиск.', objective: 'B-tree: объяснение',
    reference: 'B-tree хранит ключи по порядку, поиск идёт от корня вниз, а дерево остаётся сбалансированным.',
    points: [['CORE', 3, 'Говорит, что ключи хранятся отсортированными'], ['CORE', 3, 'Говорит, что поиск идёт от корня вниз по дереву'],
      ['DETAIL', 1, 'Говорит, что дерево остаётся сбалансированным']], misconceptions: [], terms: [],
    answer: 'Ключи лежат по порядку, и по ним можно быстро искать. [[stub:assess-partial]]' },
  transaction: { prompt: 'Объясните, что такое транзакция.', objective: 'Транзакция: объяснение',
    reference: 'Транзакция — группа операций, которая выполняется целиком или не выполняется совсем.',
    points: [['CORE', 3, 'Говорит, что операции объединены в одну группу'], ['CORE', 3, 'Говорит, что результат либо целиком, либо никак'],
      ['DETAIL', 1, 'Называет атомарность или откат']], misconceptions: [], terms: ['атомарность'],
    answer: 'Транзакция — это группа операций, которая выполняется целиком или не выполняется совсем. [[stub:assess-slow]]' },
  vacuum: { prompt: 'Объясните, зачем нужен автовакуум.', objective: 'Автовакуум: объяснение',
    reference: 'Автовакуум убирает мёртвые версии строк, освобождает место и обновляет статистику.',
    points: [['CORE', 3, 'Говорит, что удалённые и изменённые строки оставляют мёртвые версии'],
      ['CORE', 3, 'Говорит, что автовакуум освобождает место для повторного использования'],
      ['DETAIL', 1, 'Упоминает обновление статистики для планировщика']], misconceptions: [], terms: [],
    answer: 'Удалённые и изменённые строки оставляют мёртвые версии, а автовакуум освобождает это место для повторного использования '
      + 'и обновляет статистику для планировщика.' },
  resume: { prompt: 'Объясните, что такое индекс в базе данных.', objective: 'Индекс: объяснение',
    reference: 'Индекс — отдельная структура, которая ускоряет поиск строк по значению столбца.',
    points: [['CORE', 3, 'Говорит, что индекс — отдельная структура данных'], ['CORE', 3, 'Говорит, что индекс ускоряет поиск по значению столбца'],
      ['DETAIL', 1, 'Упоминает цену: индекс замедляет запись']], misconceptions: [], terms: [],
    answer: 'Индекс — это отдельная структура, которая ускоряет поиск строк по значению столбца. [[stub:assess-slow]]' },
  pancakes: { prompt: 'Объясните, как работает оптимизатор PostgreSQL.', objective: 'Оптимизатор: объяснение',
    reference: 'Оптимизатор сравнивает стоимость разных планов по статистике и выбирает самый дешёвый.',
    points: [['CORE', 3, 'Говорит, что оптимизатор сравнивает стоимость разных планов'], ['CORE', 3, 'Говорит, что оценки берутся из статистики таблиц'],
      ['DETAIL', 1, 'Говорит, что выбирается самый дешёвый план']], misconceptions: [], terms: [],
    answer: 'Рецепт блинов: смешайте муку, молоко и яйца, жарьте на сковороде до золотистого цвета.' }
};

/** Canonical S7 deadline control: parsed only from the learner answer by the Stub, with the unchanged20s runtime deadline. */
export const DEADLINE_EXERCISE = Object.freeze({ ...EXERCISES.pancakes,
  prompt: 'Объясните, как оптимизатор выбирает план запроса.', objective: 'Оптимизатор: срок проверки',
  answer: 'По статистике таблиц оценивают затраты вариантов выполнения; затем выбирают вариант с минимальной стоимостью. [[stub:assess-deadline]]' });

const paragraph = (value, index) => ({ id: `00000000-0000-4000-8000-${String(index * 2 + 1).padStart(12, '0')}`, type: 'paragraph', version: 1, attrs: {},
  content: [{ id: `00000000-0000-4000-8000-${String(index * 2 + 2).padStart(12, '0')}`, type: 'text', version: 1, attrs: { text: value, marks: [] }, content: [] }] });
const nativeDocument = lines => ({ formatVersion: 1, root: { id: '00000000-0000-4000-8000-000000000000', type: 'doc', version: 1, attrs: {},
  content: lines.map(paragraph) } });
const rubricOf = entry => ({ referenceAnswer: entry.reference,
  criteria: entry.points.map(([tier, weight, description]) => ({ criterionId: randomUUID(), description, tier, weight })),
  misconceptions: entry.misconceptions, acceptableTerms: entry.terms });

export async function runAssessment(ctx) {
  const { tab, config, record, SafeFailure, until, exists, navigate, saveScreenshot, saveFullScreenshot, clickText, setStep, bearer, deckPath } = ctx;
  class UiFailure extends SafeFailure {}
  const need = (value, label) => { if (!value) throw new UiFailure(label); };
  const page = (body, ...args) => tab.callFunction(`async function(...args) { ${body} }`, args);
  const has = selector => exists(selector, tab);
  const out = { stub: true, stages: {}, screenshots: [], wire: { submits: [], polls: 0, selfChecks: 0, selfRatings: 0, disputes: 0 } };
  const startedAt = Date.now();
  let deck = null;
  let materialPath = null;
  const attempts = { dispute: null, selfCheck: null, sessionId: null, deadline: null };
  let captureDeadline = false;

  // ---- the real wire of the learner's answers (statuses and counts only, no ids kept except for the two follow-up reads) -----------
  const requests = new Map();
  tab.on('Network.requestWillBeSent', event => {
    const url = new URL(event.request.url);
    const match = url.pathname.match(/\/study-sessions\/([0-9a-f-]{36})\/attempts(?:\/([0-9a-f-]{36})(?:\/(self-check|self-rating|dispute))?)?$/u);
    if (match === null) return;
    const kind = event.request.method === 'POST' && match[2] === undefined ? 'submit'
      : event.request.method === 'GET' && match[2] !== undefined && match[3] === undefined ? 'poll'
        : event.request.method === 'POST' && match[3] !== undefined ? match[3] : null;
    if (kind === null) return;
    requests.set(event.requestId, { kind });
    if (kind === 'submit' && captureDeadline) {
      // Keep only synthetic command ids, never request headers or the learner text.
      try {
        const command = JSON.parse(event.request.postData ?? '{}');
        if (typeof command.attemptId === 'string') attempts.deadline = { sessionId: match[1], attemptId: command.attemptId };
      } catch { /* The stage fails if the real command cannot be observed. */ }
    }
    attempts.sessionId = match[1];
    if (kind === 'poll') out.wire.polls++;
    else if (kind === 'self-check') { out.wire.selfChecks++; attempts.selfCheck = match[2]; }
    else if (kind === 'self-rating') out.wire.selfRatings++;
    else if (kind === 'dispute') { out.wire.disputes++; attempts.dispute = match[2]; }
  });
  tab.on('Network.responseReceived', event => {
    const request = requests.get(event.requestId);
    if (request !== undefined && request.kind === 'submit') out.wire.submits.push(event.response.status);
  });

  // ---- helpers ---------------------------------------------------------------------------------------------------------------
  const failureShot = async name => { try { await saveScreenshot(`failure-assessment-${name}.png`, tab); } catch { /* the original failure is the verdict */ } };
  const step = async (name, body) => {
    setStep(`assessment_${name}`);
    try { const result = await body(); out.stages[name] = result ?? true; return result; } catch (error) {
      await failureShot(name);
      await writeFile(join(config.output, `failure-assessment-${name}.txt`),
        (error instanceof SafeFailure ? error.message : `driver: ${String(error?.message ?? error).slice(0, 160)}`) + '\n').catch(() => {});
      throw error;
    }
  };
  const api = (method, path, body, extraHeaders = {}) => page(`
    const response = await fetch(args[0] + args[2], { method: args[1], credentials: 'omit',
      headers: { Authorization: args[3], ...(args[4] === null ? {} : { 'Content-Type': 'application/json' }), ...args[5] },
      body: args[4] === null ? undefined : args[4] });
    const text = await response.text();
    let parsed = null; try { parsed = text ? JSON.parse(text) : null; } catch { parsed = { unparsable: text.slice(0, 80) }; }
    return { status: response.status, body: parsed, etag: response.headers.get('ETag') };`,
  config.frontend, method, path, 'Bearer ' + bearer, body === undefined ? null : JSON.stringify(body), extraHeaders);
  const settle = () => page('return new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve(true))));');
  const metrics = (width, height, mobile) => tab.call('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: 1, mobile });
  const desktop = () => metrics(1440, 900, false);
  const phone = () => metrics(390, 844, true);
  const bodyText = () => page(`return document.body.innerText.replace(/\\s+/g, ' ').replaceAll('\\u00a0', ' ');`);
  const textOf = selector => page(`const node = document.querySelector(args[0]); return node ? node.textContent.replace(/\\s+/g, ' ').replaceAll('\\u00a0', ' ').trim() : null;`, selector);
  const count = selector => page('return document.querySelectorAll(args[0]).length;', selector);
  const location = () => page('return location.pathname;');
  const buttonExists = label => page(`return [...document.querySelectorAll('button')].some(button => button.textContent.replace(/\\s+/g, ' ').trim() === args[0]);`, label);
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
  const scrollSettled = () => page(`return new Promise(resolve => { let last = scrollY, still = 0, frames = 0;
    const tick = () => { frames++; if (scrollY === last) still++; else { still = 0; last = scrollY; }
      if (still >= 6 || frames > 180) resolve(still >= 6); else requestAnimationFrame(tick); };
    requestAnimationFrame(tick); });`);
  /** A real mouse click on the first visible element matching `selector` (and, when given, with exactly this text). */
  const click = async (selector, label = null, prefix = false) => {
    need(await scrollSettled(), 'the page kept scrolling and never settled before a click');
    const point = await page(`const [selector, label, prefix] = args;
      const node = [...document.querySelectorAll(selector)].find(candidate => label === null || (prefix
        ? candidate.textContent.replace(/\\s+/g, ' ').replaceAll('\\u00a0', ' ').trim().startsWith(label)
        : candidate.textContent.replace(/\\s+/g, ' ').replaceAll('\\u00a0', ' ').trim() === label));
      if (!(node instanceof HTMLElement) || node.matches(':disabled')) return null;
      node.scrollIntoView({ block: 'center', behavior: 'instant' });
      const rect = node.getBoundingClientRect();
      return { x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 };`, selector, label, prefix);
    need(point !== null, `${label ?? selector} is absent or disabled`);
    await tab.call('Page.bringToFront');
    await tab.call('Input.dispatchMouseEvent', { type: 'mouseMoved', x: point.x, y: point.y });
    await tab.call('Input.dispatchMouseEvent', { type: 'mousePressed', x: point.x, y: point.y, button: 'left', clickCount: 1 });
    await tab.call('Input.dispatchMouseEvent', { type: 'mouseReleased', x: point.x, y: point.y, button: 'left', clickCount: 1 });
    await settle();
  };
  const typeInto = async (selector, value) => {
    need(await page(`const node = document.querySelector(args[0]);
      if (!(node instanceof HTMLInputElement || node instanceof HTMLTextAreaElement)) return false;
      node.scrollIntoView({ block: 'center', behavior: 'instant' }); node.focus(); node.select(); return document.activeElement === node;`, selector),
    `${selector} cannot take focus`);
    await tab.call('Input.insertText', { text: value });
    await settle();
  };
  /** A native select is changed the way a change event reaches the component; the keyboard route of a closed select differs per OS. */
  const choose = async (selector, value) => {
    need(await page(`const node = document.querySelector(args[0]);
      if (!(node instanceof HTMLSelectElement)) return false;
      node.value = args[1]; node.dispatchEvent(new Event('change', { bubbles: true })); return node.value === args[1];`, selector, value),
    `${selector} could not be set to ${value}`);
    await settle();
  };
  const layout = () => page(`const doc = document.documentElement;
    const visible = element => { const rect = element.getBoundingClientRect(); const style = getComputedStyle(element);
      return rect.width > 0 && rect.height > 0 && style.visibility !== 'hidden' && style.display !== 'none'; };
    const small = [...document.querySelectorAll('main button.button, main .button')].filter(visible).map(element => { const rect = element.getBoundingClientRect();
      return { text: (element.textContent ?? '').trim().slice(0, 24), h: Math.round(rect.height) }; }).filter(entry => entry.h < 43.5);
    const wide = [...document.querySelectorAll('main *')].filter(element => visible(element) && element.getBoundingClientRect().right > doc.clientWidth + 1)
      .map(element => element.tagName.toLowerCase() + '.' + String(element.className).split(' ')[0]).slice(0, 4);
    return { overflow: doc.scrollWidth > doc.clientWidth, scrollWidth: doc.scrollWidth, clientWidth: doc.clientWidth, small, wide };`);
  /** 1440 and 390 px screenshots of the current screen; no horizontal overflow and no primary target under 44 px at either. */
  const shots = async (name, label, { full = false, scrollTo = null } = {}) => {
    for (const [tag, set] of [['1440', desktop], ['390', phone]]) {
      await set(); await settle();
      if (scrollTo !== null) { await page(`document.querySelector(args[0])?.scrollIntoView({ block: 'center', behavior: 'instant' }); return true;`, scrollTo); await settle(); }
      const facts = await layout();
      need(!facts.overflow, `${label} overflows horizontally at ${tag} px (${facts.scrollWidth} > ${facts.clientWidth}: ${facts.wide.join(' ')})`);
      need(facts.small.length === 0, `${label} has buttons below 44 px at ${tag} px: ${JSON.stringify(facts.small)}`);
      const file = `assessment-${name}-${tag}.png`;
      await (full ? saveFullScreenshot(file, tab) : saveScreenshot(file, tab));
      out.screenshots.push(file);
    }
    await desktop(); await settle();
  };

  // ---- fixture: the capability, a deck, a material --------------------------------------------------------------------------------
  await desktop();
  await step('capability_and_fixture', async () => {
    // The ordinary Learning of this run (everything AI off, as shipped) says so; then the proxy goes to the Stub instance.
    const switched = await page(`const response = await fetch('/__fixture/learning-generation', { method: 'POST' }); return response.status;`);
    need(switched === 204, `the proxy did not switch to the Stub Learning (status ${switched})`);
    // An earlier scenario may have tripped a breaker of the Stub (the generation scenarios do); wait it out like they do.
    await until(async () => (await api('GET', '/api/capabilities')).body?.aiAssessment?.available === true,
      'aiAssessment did not become available (Stub configuration)', 90_000);
    const capabilities = await api('GET', '/api/capabilities');
    need(capabilities.status === 200 && capabilities.body?.aiAssessment?.available === true && capabilities.body.aiAssessment.reason === null,
      `GET /api/capabilities does not report aiAssessment available: ${JSON.stringify(capabilities.body?.aiAssessment ?? null)}`);
    const created = await api('POST', '/api/decks', { commandId: randomUUID(), metadata: { title: 'Проверка смысла ответа', description: '' } });
    need(created.status === 201, `POST deck answered ${created.status}`);
    const id = created.body.deck?.deckId ?? created.body.deckId;
    need(id, 'the created deck has no id');
    const current = await api('GET', `/api/decks/${id}`);
    need(current.status === 200, `GET deck answered ${current.status}`);
    const made = await api('POST', `/api/decks/${id}/items`, { commandId: randomUUID(), expectedDeckRevisionId: current.body.revisionId,
      document: nativeDocument(MATERIAL.lines) }, { 'If-Match': current.etag });
    need(made.status === 201, `POST item answered ${made.status}`);
    const memberKey = made.body.changes?.[0]?.memberKey ?? made.body.memberKey;
    need(memberKey, 'the created material has no member key');
    deck = { deckId: id, memberKey };
    materialPath = `/decks/${id}/materials/${memberKey}`;
    return { aiAssessment: capabilities.body.aiAssessment };
  });

  // ---- 1. the rubric editor, through the real UI ---------------------------------------------------------------------------------
  const author = EXERCISES.inertia;
  const reopened = () => page(`const points = [...document.querySelectorAll('app-ai-rubric-editor li[data-criterion]')];
    const lists = [...document.querySelectorAll('app-ai-rubric-editor details.fine input[type="text"]')].map(input => input.value);
    return { aiOn: document.querySelector('app-free-response-editor input[role="switch"]')?.checked === true,
      reference: document.querySelector('#free-response-rubric-reference')?.value ?? null,
      points: points.map(point => ({ description: point.querySelector('textarea')?.value, tier: point.querySelector('select[id$="-tier"]')?.value,
        weight: point.querySelector('select[id$="-weight"]')?.value })),
      fineOpen: document.querySelector('app-ai-rubric-editor details.fine')?.open === true, lists,
      title: document.querySelector('#step-answers-title')?.textContent.trim() ?? null,
      deterministic: Boolean(document.querySelector('app-text-answer-editor')) };`);
  await step('rubric_editor', async () => {
    await navigate(`${materialPath}/exercises/new`, tab);
    await waitFor(async () => (await has('input[name="mechanic"][value="FREE_RESPONSE"]')) && (await has('form.inspector')), 'the exercise editor did not load');
    await click('label.tile', 'Ввести ответ', true);
    await waitFor(async () => (await has('#exercise-preview-anchor')) && (await has('section.step')), 'the preview and the first step did not open');
    await typeInto('#free-response-prompt-text-0', author.prompt);
    await click('[data-continue]');
    await waitFor(() => has('#step-answers'), 'the answers step did not open');

    // The switch is enabled because the server reports the capability; the deterministic list is what it shows until then.
    const before = await page(`const toggle = document.querySelector('app-free-response-editor input[role="switch"]');
      return { present: Boolean(toggle), disabled: toggle?.disabled, checked: toggle?.checked,
        hint: document.querySelector('app-free-response-editor .ai-switch .hint')?.textContent.replace(/\\s+/g, ' ').trim() ?? null,
        deterministic: Boolean(document.querySelector('app-text-answer-editor')), rubric: Boolean(document.querySelector('app-ai-rubric-editor')) };`);
    need(before.present && before.disabled === false && before.checked === false, `the AI switch is ${JSON.stringify(before)}`);
    need(before.deterministic && !before.rubric, 'the answer list is not the default editor');
    need(before.hint.includes('сравнит ответ с эталоном'), `the switch hint is «${before.hint}»`);
    await click('app-free-response-editor input[role="switch"]');
    await waitFor(() => has('app-ai-rubric-editor'), 'the rubric editor did not appear after switching AI checking on');
    const opened = await reopened();
    need(opened.aiOn && !opened.deterministic, 'the deterministic answer list stayed next to the rubric');
    need(opened.title === '3. Эталон и пункты проверки', `the step title is «${opened.title}»`);
    need(opened.points.length === 3 && opened.points.map(point => point.tier).join() === 'CORE,CORE,DETAIL', `the starting points are ${JSON.stringify(opened.points)}`);
    need(!opened.fineOpen, '«Тонкая настройка» is open by default');
    const counters = () => page(`return [...document.querySelectorAll('app-ai-rubric-editor .tier-count')].map(node => node.textContent.replace(/\\s+/g, ' ').trim()).join(' ');`);
    need((await counters()) === 'Суть: 2 (нужно 2–3) Детали: 1 (нужно 1–4) Термины: 0 (нужно 0–2)', `the counters read «${await counters()}»`);
    need(await page(`return document.querySelector('app-ai-rubric-editor .tier-counts')?.getAttribute('role') === 'status';`), 'the counters are not a status region');

    // Nothing is filled: «Продолжить» refuses and says where, in words.
    await click('[data-continue]');
    await waitFor(() => has('#step-answers .step-problem'), 'the empty rubric was not refused');
    need(!(await has('#step-finish')), 'the save step opened with an empty rubric');
    const refused = await page(`return { reference: document.querySelector('#free-response-rubric-reference')?.getAttribute('aria-invalid'),
      errors: [...document.querySelectorAll('app-ai-rubric-editor .field-error')].map(node => node.textContent.trim()) };`);
    need(refused.reference === 'true' && refused.errors.some(error => error.includes('эталонный ответ')) && refused.errors.filter(error => error.includes('Опишите пункт')).length === 3,
      `the refusal says ${JSON.stringify(refused)}`);

    // Filling: reference, the three starting points, a fourth (terminology) and the fine settings.
    await typeInto('#free-response-rubric-reference', author.reference);
    for (const [index, [, , description]] of author.points.slice(0, 3).entries()) {
      await typeInto(`app-ai-rubric-editor li[data-criterion]:nth-of-type(${index + 1}) textarea`, description);
    }
    await click('app-ai-rubric-editor [data-add-point]');
    await waitFor(async () => (await count('app-ai-rubric-editor li[data-criterion]')) === 4, 'the fourth point was not added');
    need(await page(`const added = document.querySelectorAll('app-ai-rubric-editor li[data-criterion]')[3]?.querySelector('textarea');
      return document.activeElement === added;`), 'the new point did not take focus');
    await typeInto('app-ai-rubric-editor li[data-criterion]:nth-of-type(4) textarea', author.points[3][2]);
    await choose('app-ai-rubric-editor li[data-criterion]:nth-of-type(4) select[id$="-tier"]', 'TERM');
    await choose('app-ai-rubric-editor li[data-criterion]:nth-of-type(4) select[id$="-weight"]', '1');
    need((await counters()) === 'Суть: 2 (нужно 2–3) Детали: 1 (нужно 1–4) Термины: 1 (нужно 0–2)', `the counters read «${await counters()}»`);

    await click('app-ai-rubric-editor details.fine summary');
    await click('app-ai-rubric-editor app-rubric-text-list:nth-of-type(1) [data-add]');
    await typeInto('app-ai-rubric-editor app-rubric-text-list:nth-of-type(1) input[type="text"]', author.misconceptions[0]);
    for (const [index, term] of author.terms.entries()) {
      await click('app-ai-rubric-editor app-rubric-text-list:nth-of-type(2) [data-add]');
      await typeInto(`app-ai-rubric-editor app-rubric-text-list:nth-of-type(2) .alias-row:nth-of-type(${index + 1}) input[type="text"]`, term);
    }
    // One point is removed and put back: focus must land on the neighbour, and the counts must follow.
    await click('app-ai-rubric-editor li[data-criterion]:nth-of-type(4) button', 'Убрать');
    await waitFor(async () => (await count('app-ai-rubric-editor li[data-criterion]')) === 3, 'the point was not removed');
    need((await counters()).includes('Термины: 0 (нужно 0–2)'), 'the counters did not follow the removal');
    need(await page(`const active = document.activeElement; return active instanceof HTMLTextAreaElement && Boolean(active.closest('li[data-criterion]'));`),
      'focus did not move to a neighbouring point after the removal');
    await click('app-ai-rubric-editor [data-add-point]');
    await waitFor(async () => (await count('app-ai-rubric-editor li[data-criterion]')) === 4, 'the point was not added again');
    await typeInto('app-ai-rubric-editor li[data-criterion]:nth-of-type(4) textarea', author.points[3][2]);
    await choose('app-ai-rubric-editor li[data-criterion]:nth-of-type(4) select[id$="-tier"]', 'TERM');
    await choose('app-ai-rubric-editor li[data-criterion]:nth-of-type(4) select[id$="-weight"]', '1');
    await shots('rubric-editor', 'the rubric editor', { full: true });

    await click('[data-continue]');
    await waitFor(() => has('#step-finish'), 'the save step did not open after a valid rubric');
    await typeInto('#objective-title', author.objective);
    await click('.save-bar button[type="submit"]', 'Создать упражнение');
    await waitFor(async () => /^\/decks\/[0-9a-f-]{36}\/exercises\/[0-9a-f-]{36}\/edit$/u.test(await location())
      || (await has('#exercise-errors')) || (await has('.notice.error[role="alert"]')), 'the exercise did not reach its edit route after saving', 30_000);
    need(!(await has('#exercise-errors')) && !(await has('.notice.error[role="alert"]')),
      `the editor refused the save: ${await textOf('#exercise-errors') ?? await textOf('.notice.error[role="alert"]')}`);

    // Reopen after a real reload: the rubric is what was typed, the switch is on, the fine settings are open and filled.
    await tab.call('Page.reload', { ignoreCache: true });
    await waitFor(async () => (await has('app-ai-rubric-editor')) && (await has('#exercise-preview-anchor')), 'the saved exercise did not reload into the rubric editor');
    const again = await reopened();
    need(again.aiOn && again.reference === author.reference, 'the reference answer was not restored');
    need(JSON.stringify(again.points.map(point => [point.tier, Number(point.weight), point.description])) === JSON.stringify(author.points),
      `the points were not restored: ${JSON.stringify(again.points)}`);
    need(again.fineOpen && again.lists.join('|') === [...author.misconceptions, ...author.terms].join('|'), `the fine settings were not restored: ${JSON.stringify(again.lists)}`);
    return { pointsRestored: again.points.length, tiers: again.points.map(point => point.tier), fineSettingsRestored: true,
      refusalNamedThePoints: true, countersLive: true, focusAfterAdd: true, focusAfterRemove: true };
  });

  await step('preview_has_no_model', async () => {
    await waitFor(() => has('#exercise-preview-anchor [data-submit]'), 'the preview has no check button');
    await typeInto('#exercise-preview-anchor textarea', 'Проверка предпросмотра');
    await click('#exercise-preview-anchor button[data-submit]');
    await waitFor(() => has('#preview-result-title'), 'the preview did not answer', 20_000);
    const shown = await bodyText();
    need(shown.includes('В предпросмотре ИИ не проверяет ответ'), 'the preview does not say that the AI does not check in the preview');
    need((await textOf('#preview-result-title')) === 'Проверка недоступна', `the preview title is «${await textOf('#preview-result-title')}»`);
    return { previewDoesNotCallAModel: true };
  });

  /** One more AI-checked exercise through the same authenticated API the editor uses. */
  const createExercise = async (key, subject, target = deck, entry = EXERCISES[key]) => {
    const current = await api('GET', `/api/decks/${target.deckId}`);
    need(current.status === 200 && current.etag, `GET deck answered ${current.status}`);
    const made = await api('POST', `/api/decks/${target.deckId}/exercises`, { commandId: randomUUID(), expectedDeckRevisionId: current.body.revisionId,
      objective: { operation: 'create', title: entry.objective },
      exercise: { type: 'FREE_RESPONSE', schemaVersion: 2, enabled: true, subject: { memberKey: subject.memberKey, itemRevisionId: subject.itemRevisionId },
        content: { prompt: [{ kind: 'TEXT', text: entry.prompt }], reference: [], responseInput: 'TEXT' },
        answerKey: { kind: 'TEXT', accepted: [entry.reference.slice(0, 200)], normalization: ['UNICODE_NFC', 'TRIM', 'CASE_FOLD'], matchingMode: 'STRICT' },
        evaluatorPolicy: { id: 'ai-semantic', version: '1', rubric: rubricOf(entry) } } }, { 'If-Match': current.etag });
    need(made.status === 201, `POST exercise «${key}» answered ${made.status} ${JSON.stringify(made.body?.code ?? made.body?.detail ?? null)}`);
  };
  const materialSubject = async (target = deck) => {
    const items = await api('GET', `/api/decks/${target.deckId}/items?limit=20`);
    need(items.status === 200, `GET items answered ${items.status}`);
    const subject = items.body.items.find(item => item.memberKey === target.memberKey);
    need(subject?.itemRevisionId, 'the material has no revision');
    return subject;
  };

  // ---- 2. the other four exercises, through the same authenticated API the editor uses ------------------------------------------------
  await step('api_exercises', async () => {
    const subject = await materialSubject();
    for (const key of ['btree', 'transaction', 'vacuum', 'pancakes']) await createExercise(key, subject);
    return { exercises: 5 };
  });

  // ---- 3. Study: one standard session holds the five new objectives; the scenario answers each by what the card asks -----------------
  const submitAnswer = async entry => {
    await waitFor(() => has('app-learner-exercise textarea[data-answer-control]'), `the answer field of «${entry.prompt}» did not appear`, 20_000);
    await typeInto('app-learner-exercise textarea[data-answer-control]', entry.answer);
    const sent = Date.now();
    await click('app-learner-exercise button[data-submit]');
    return sent;
  };
  const feedbackTitle = () => textOf('#feedback-title');
  const awaitFeedback = async (label, timeoutMs = 15_000) => {
    await waitFor(async () => (await has('#feedback-title')) || (await has('#self-check-title')), `${label}: no result and no self-check view arrived`, timeoutMs);
  };
  const cardFacts = () => page(`const result = document.querySelector('app-assessment-result');
    const list = label => [...document.querySelectorAll('[aria-labelledby="' + label + '"] li')].map(item => item.textContent.replace(/\\s+/g, ' ').trim());
    return { title: document.querySelector('#feedback-title')?.textContent.trim() ?? null,
      covered: list('assessment-covered-title'),
      coveredQuotes: [...document.querySelectorAll('[aria-labelledby="assessment-covered-title"] q')].map(node => node.textContent),
      missing: list('assessment-missing-title'), next: document.querySelector('[data-next-stricter]')?.textContent.trim() ?? null,
      reference: document.querySelector('app-learner-feedback .comparison')?.textContent.replace(/\\s+/g, ' ').trim() ?? null,
      hasResult: Boolean(result), dispute: [...document.querySelectorAll('button')].some(button => button.textContent.trim() === 'Оспорить оценку'),
      note: document.querySelector('.progress-note')?.textContent.trim() ?? null,
      focus: document.activeElement?.id ?? null };`);
  const toNext = async () => { await click('.feedback-card button.primary', 'Продолжить'); await settle(); };

  const handlers = {
    inertia: async entry => {
      await submitAnswer(entry);
      await awaitFeedback('inertia');
      const facts = await cardFacts();
      need(facts.title === 'Засчитано', `the complete answer is titled «${facts.title}»`);
      need(facts.covered.length === 4 && facts.coveredQuotes.length === 4 && facts.missing.length === 0, `covered ${facts.covered.length}, missing ${facts.missing.length}`);
      need(facts.coveredQuotes.every(quote => entry.answer.includes(quote)), 'a quote shown is not in the learner\'s own answer');
      need(facts.next === null || facts.next.startsWith('В следующий раз'), `the next-time note is «${facts.next}»`);
      need(facts.reference.includes('Эталон') && facts.reference.includes(entry.reference), 'the reference is not shown after the answer');
      need(facts.dispute, '«Оспорить оценку» is not offered');
      need(facts.focus === 'feedback-title', `focus is on «${facts.focus}», not on the result`);
      await shots('result-complete', 'the complete result', { full: true });
      await toNext();
      return { title: facts.title, covered: 4, missing: 0, quotesAreTheLearnersOwn: true, nextTimeNote: facts.next !== null };
    },
    btree: async entry => {
      await submitAnswer(entry);
      await awaitFeedback('btree');
      const facts = await cardFacts();
      need(facts.title === 'Частично', `the partial answer is titled «${facts.title}»`);
      need(facts.covered.length === 1 && facts.missing.length === 2, `covered ${facts.covered.length}, missing ${facts.missing.length}`);
      need(facts.next === null || (facts.next.startsWith('В следующий раз я попрошу точнее:') && facts.next.includes('поиск идёт от корня')), `the next-time note is «${facts.next}»`);
      await shots('result-partial', 'the partial result', { full: true });
      await toNext();
      return { title: facts.title, covered: facts.covered.length, missing: facts.missing.length, nextTimeNote: facts.next !== null };
    },
    pancakes: async entry => {
      await submitAnswer(entry);
      await awaitFeedback('pancakes');
      const facts = await cardFacts();
      need(facts.title === 'Пока не засчитано', `an answer about pancakes is titled «${facts.title}»`);
      need(facts.covered.length === 0 && facts.missing.length === 3, `covered ${facts.covered.length}, missing ${facts.missing.length}`);
      await shots('result-offtopic', 'the not-accepted result');
      await toNext();
      return { title: facts.title, covered: 0, missing: 3 };
    },
    transaction: async entry => {
      const sent = await submitAnswer(entry);
      await waitFor(() => has('#assessing-title'), 'the waiting card did not appear after sending a slow answer', 6000);
      const waiting = await page(`const card = document.querySelector('app-assessment-waiting');
        return { title: document.querySelector('#assessing-title')?.textContent.trim(), focus: document.activeElement?.id,
          status: card?.querySelector('[role="status"]')?.textContent.replace(/\\s+/g, ' ').trim() ?? null,
          answer: card?.querySelector('.answer-text')?.textContent ?? null, field: Boolean(document.querySelector('textarea')),
          reference: document.body.innerText.includes(args[0]) };`, EXERCISES.transaction.reference);
      need(waiting.title === 'Мнема проверяет ответ…', `the waiting title is «${waiting.title}»`);
      need(waiting.focus === 'assessing-title', `focus is on «${waiting.focus}», not on the waiting card`);
      need(waiting.answer === entry.answer && !waiting.field, 'the answer is not kept read-only on the waiting card');
      need(!waiting.reference, 'the reference is on screen before the answer was graded');
      need(waiting.status?.includes('несколько секунд') === true, `the status says «${waiting.status}»`);
      await sleep(Math.max(0, 3500 - (Date.now() - sent)));
      need(!(await buttonExists('Оценить себя')), '«Оценить себя» is offered before 5 seconds');
      await waitFor(() => buttonExists('Оценить себя'), '«Оценить себя» did not appear', 9000);
      const offeredAfter = Date.now() - sent;
      need(offeredAfter >= 4700, `«Оценить себя» appeared after ${offeredAfter} ms, before 5 seconds`);
      const offered = await page(`const button = [...document.querySelectorAll('button')].find(candidate => candidate.textContent.trim() === 'Оценить себя');
        return { primary: button.classList.contains('primary'), status: document.querySelector('app-assessment-waiting [role="status"]')?.textContent.replace(/\\s+/g, ' ').trim() };`);
      need(!offered.primary && offered.status.includes('дольше обычного'), `the offer reads ${JSON.stringify(offered)}`);
      await saveScreenshot('assessment-assessing-1440.png', tab); out.screenshots.push('assessment-assessing-1440.png');
      await phone(); await settle();
      await saveScreenshot('assessment-assessing-390.png', tab); out.screenshots.push('assessment-assessing-390.png');
      await desktop(); await settle();
      await click('button', 'Оценить себя');
      await waitFor(() => has('#self-check-title'), 'the self-check view did not open after «Оценить себя»', 8000);
      const clickedAfter = Date.now() - sent;
      const view = await page(`const root = document.querySelector('app-assessment-self-check');
        return { notice: root.querySelector('.notice')?.textContent.trim(), comparison: root.querySelector('.comparison')?.textContent.replace(/\\s+/g, ' ').trim(),
          points: [...root.querySelectorAll('.points li')].map(item => item.textContent.trim()),
          ratings: [...root.querySelectorAll('.ratings button')].map(button => button.textContent.trim()), focus: document.activeElement?.id };`);
      need(view.notice.includes('Вы решили оценить себя сами'), `the reason is «${view.notice}»`);
      need(view.comparison.includes(entry.answer) && view.comparison.includes(EXERCISES.transaction.reference), 'the self-check view lacks the answer or the reference');
      need(view.points.length === 3 && view.ratings.join('|') === 'Не вспомнил|Вспомнил с подсказкой|Вспомнил частично|Вспомнил полностью', `the view is ${JSON.stringify(view)}`);
      need(view.focus === 'self-check-title', `focus is on «${view.focus}»`);
      await shots('self-check', 'the self-check view', { full: true });
      // The model's late grade (≈ 8 s after sending) must change nothing.
      await sleep(Math.max(0, 9500 - (Date.now() - sent)));
      need((await has('#self-check-title')) && !(await has('#feedback-title')), 'a late grade replaced the self-check view');
      // The self-check survives a reload with the learner's own text.
      await tab.call('Page.reload', { ignoreCache: true });
      await waitFor(() => has('#self-check-title'), 'the self-check view was not resumed after a reload', 20_000);
      need((await bodyText()).includes(entry.answer), 'the learner\'s answer was not restored after the reload');
      await click('app-assessment-self-check .ratings button', 'Вспомнил частично');
      await waitFor(() => has('#feedback-title'), 'the self-rating did not complete the attempt', 15_000);
      const done = await cardFacts();
      need(done.title === 'Частично' && !done.hasResult && !done.dispute, `after the self-rating: ${JSON.stringify(done)}`);
      need(done.reference.includes(EXERCISES.transaction.reference), 'the reference is missing after the self-rating');
      await toNext();
      return { offeredAfterMs: offeredAfter, selfCheckAfterMs: clickedAfter, lateGradeIgnored: true, resumedAfterReload: true, title: done.title };
    },
    vacuum: async entry => {
      await submitAnswer(entry);
      await awaitFeedback('vacuum');
      const facts = await cardFacts();
      need(facts.title === 'Засчитано' && facts.dispute, `the answer is titled «${facts.title}»`);
      await click('button[data-dispute-open]');
      await waitFor(() => has('.dispute'), 'the dispute question did not open in place');
      const question = await page(`const group = document.querySelector('.dispute');
        return { role: group.getAttribute('role'), text: group.textContent.replace(/\\s+/g, ' ').trim(), focus: document.activeElement?.hasAttribute('data-dispute-confirm') };`);
      need(question.role === 'group' && question.text.includes('Снять эту оценку?') && question.focus === true, `the dispute question is ${JSON.stringify(question)}`);
      await shots('dispute-confirm', 'the dispute question', { scrollTo: '.dispute' });
      await click('button[data-dispute-confirm]');
      await waitFor(async () => (await feedbackTitle()) === 'Оценка снята', 'the dispute did not take the grade back', 15_000);
      const after = await cardFacts();
      need(after.note === 'Оценка снята, прогресс не изменился.', `the note is «${after.note}»`);
      need(!after.hasResult && !after.dispute && after.reference.includes(entry.reference) && after.reference.includes(entry.answer), `after the dispute: ${JSON.stringify(after)}`);
      need(out.wire.disputes === 1 && attempts.dispute !== null, 'exactly one dispute request was expected');
      const read = await api('GET', `/api/decks/${deck.deckId}/study-sessions/${attempts.sessionId}/attempts/${attempts.dispute}`);
      need(read.status === 200 && read.body?.status === 'NOT_ASSESSED' && read.body?.disputed === true && read.body?.evidence === null,
        `the disputed attempt reads ${read.status} ${JSON.stringify({ status: read.body?.status, disputed: read.body?.disputed })}`);
      await shots('disputed', 'the disputed result', { scrollTo: '#feedback-title' });
      await toNext();
      return { title: after.title, note: after.note, attemptReadsAsDisputed: true };
    }
  };

  await step('study', async () => {
    await navigate(`/decks/${deck.deckId}/study`, tab);
    await waitFor(() => has('.session-setup'), 'the Study setup did not open', 25_000);
    await click('.preset-card button', 'Начать стандартную');
    const done = [];
    for (let index = 0; index < 5; index++) {
      await waitFor(() => has('app-learner-exercise textarea[data-answer-control]'), `card ${index + 1} did not appear`, 25_000);
      const prompt = await textOf('.exercise-prompt');
      const key = Object.keys(EXERCISES).find(candidate => prompt?.includes(EXERCISES[candidate].prompt));
      need(key !== undefined, `an unexpected card: «${prompt}»`);
      done.push(key);
      out.stages[`study_${key}`] = await handlers[key](EXERCISES[key]);
    }
    await waitFor(() => has('.completion'), 'the session did not complete after five answers', 25_000);
    need(new Set(done).size === 5, `the cards were ${done.join(', ')}`);
    return { order: done };
  });

  // ---- 4. a reload while the grading is still running: the waiting card comes back, no second submit, the result arrives on its own ---------
  await step('resume_after_reload', async () => {
    await createExercise('resume', await materialSubject());
    const entry = EXERCISES.resume;
    await navigate(`/decks/${deck.deckId}/study`, tab);
    await waitFor(() => has('.session-setup'), 'the second Study setup did not open', 25_000);
    await click('.preset-card button', 'Начать стандартную');
    // The disputed exercise is unassessed again and due at once, so it may come first: answer it and go on.
    const answerOthers = async () => {
      for (let guard = 0; guard < 3; guard++) {
        await waitFor(async () => (await has('app-learner-exercise textarea[data-answer-control]')) || (await has('.completion')), 'the next card did not appear', 25_000);
        if (await has('.completion')) return;
        const prompt = await textOf('.exercise-prompt');
        if (prompt?.includes(entry.prompt)) return;
        need(prompt?.includes(EXERCISES.vacuum.prompt), `an unexpected card in the second session: «${prompt}»`);
        await submitAnswer(EXERCISES.vacuum);
        await awaitFeedback('second session');
        await toNext();
      }
    };
    await answerOthers();
    need((await textOf('.exercise-prompt'))?.includes(entry.prompt), 'the second session did not hold the new exercise');
    const sent = await submitAnswer(entry);
    await waitFor(() => has('#assessing-title'), 'the waiting card did not appear', 6000);
    const submitsBefore = out.wire.submits.length;
    const pollsBefore = out.wire.polls;
    await sleep(1000);
    await tab.call('Page.reload', { ignoreCache: true });
    await waitFor(() => has('#assessing-title'), 'the waiting card did not come back after a reload', 20_000);
    const resumed = await page(`const card = document.querySelector('app-assessment-waiting');
      return { answer: card?.querySelector('.answer-text')?.textContent ?? null, field: Boolean(document.querySelector('textarea')),
        reference: document.body.innerText.includes(args[0]), title: document.querySelector('#assessing-title')?.textContent.trim() };`, entry.reference);
    need(resumed.answer === entry.answer && !resumed.field && !resumed.reference, `the resumed card is ${JSON.stringify({ ...resumed, answer: resumed.answer?.slice(0, 20) })}`);
    need(out.wire.submits.length === submitsBefore, 'the reload sent the answer again');
    // The offer counts from the stored send time, not from the reload: it must not come 5 s after the reload.
    await waitFor(() => buttonExists('Оценить себя'), '«Оценить себя» did not appear after the reload', 9000);
    const offeredAfter = Date.now() - sent;
    need(offeredAfter >= 4500 && offeredAfter <= 6600, `after the reload «Оценить себя» appeared ${offeredAfter} ms after sending (stored time expected: about 5000)`);
    // Nothing is pressed: the grade arrives on its own (≈ 8 s after sending) and replaces the waiting card.
    await waitFor(() => has('#feedback-title'), 'the result did not arrive after the reload', 15_000);
    const facts = await cardFacts();
    need(facts.title === 'Засчитано' && facts.hasResult && facts.covered.length >= 1 && !(await has('#self-check-title')),
      `after the reload the result is ${JSON.stringify({ title: facts.title, covered: facts.covered.length, hasResult: facts.hasResult })}`);
    need(facts.reference.includes(entry.answer) && facts.dispute, 'the answer or «Оспорить оценку» is missing on the resumed result');
    need(out.wire.polls > pollsBefore, 'no poll followed the reload');
    await toNext();
    await answerOthers();
    await waitFor(() => has('.completion'), 'the second session did not complete', 25_000);
    return { offeredAfterMs: offeredAfter, noSecondSubmit: true, resultWithoutAnyPress: true, pollsAfterReload: out.wire.polls - pollsBefore };
  });

  // ---- the wire: every AI answer was a 202, nothing leaked ---------------------------------------------------------------------------
  await step('wire', async () => {
    need(out.wire.submits.length >= 6 && out.wire.submits.length <= 7 && out.wire.submits.every(status => status === 202), `the submits answered ${JSON.stringify(out.wire.submits)}`);
    need(out.wire.selfChecks === 1 && out.wire.selfRatings === 1 && out.wire.disputes === 1, `self-checks ${out.wire.selfChecks}, ratings ${out.wire.selfRatings}, disputes ${out.wire.disputes}`);
    need(out.wire.polls >= 6, `only ${out.wire.polls} polls`);
    return { submits: out.wire.submits.length, polls: out.wire.polls };
  });

  // The existing wire assertions above still describe the original5s/8s/reload/dispute flow. This isolated card adds one202 only.
  await step('automatic_deadline', async () => {
    const madeDeck = await api('POST', '/api/decks', { commandId: randomUUID(), metadata: { title: 'Срок проверки объяснения', description: '' } });
    need(madeDeck.status === 201, `deadline deck creation answered ${madeDeck.status}`);
    const id = madeDeck.body.deck?.deckId ?? madeDeck.body.deckId;
    need(id, 'the deadline deck has no id');
    const current = await api('GET', `/api/decks/${id}`);
    need(current.status === 200 && current.etag, 'the deadline deck cannot be read');
    const made = await api('POST', `/api/decks/${id}/items`, { commandId: randomUUID(), expectedDeckRevisionId: current.body.revisionId,
      document: nativeDocument(MATERIAL.lines) }, { 'If-Match': current.etag });
    need(made.status === 201, `deadline material creation answered ${made.status}`);
    const memberKey = made.body.changes?.[0]?.memberKey ?? made.body.memberKey;
    need(memberKey, 'the deadline material has no member key');
    const target = { deckId: id, memberKey };
    await createExercise('deadline', await materialSubject(target), target, DEADLINE_EXERCISE);
    await navigate(`/decks/${id}/study`, tab);
    await waitFor(() => has('.session-setup'), 'deadline Study setup did not open', 25_000);
    await click('.preset-card button', 'Начать стандартную');
    await waitFor(() => has('app-learner-exercise textarea[data-answer-control]'), 'the isolated deadline card did not open', 25_000);
    need((await textOf('.exercise-prompt'))?.includes(DEADLINE_EXERCISE.prompt), 'the wrong card opened for the deadline proof');
    // Issuing the presentation may introduce the objective. Baseline only after issuance; the answer must not add canonical assessment.
    const beforeUsage = await api('GET', '/api/usage');
    const beforeProgress = await api('GET', `/api/decks/${id}/study-progress?limit=100`);
    need(beforeUsage.status === 200 && beforeProgress.status === 200, 'the deadline baseline could not be read');
    const counters = { submits: out.wire.submits.length, selfChecks: out.wire.selfChecks, selfRatings: out.wire.selfRatings, disputes: out.wire.disputes };
    let sent;
    captureDeadline = true;
    try {
      sent = await submitAnswer(DEADLINE_EXERCISE);
      await waitFor(() => has('#assessing-title'), 'the deadline waiting card did not open', 6000);
      await waitFor(() => out.wire.submits.length === counters.submits + 1 && attempts.deadline !== null,
        'the deadline answer has no observed202 response and command', 6000);
    } finally { captureDeadline = false; }
    need(out.wire.submits.at(-1) === 202 && attempts.deadline !== null, 'the deadline answer was not accepted as one real asynchronous command');
    const waiting = await page(`return { answer: document.querySelector('app-assessment-waiting .answer-text')?.textContent,
      field: Boolean(document.querySelector('textarea')), reference: document.body.innerText.includes(args[0]) };`, DEADLINE_EXERCISE.reference);
    need(waiting.answer === DEADLINE_EXERCISE.answer && !waiting.field && !waiting.reference, 'the deadline waiting card is editable or exposes its reference');
    await shots('deadline-waiting', 'the deadline waiting view', { full: true });
    await waitFor(() => buttonExists('Оценить себя'), 'the deadline card did not offer self-check after5s', 9000);
    // Deliberately do not press it. A recorded default20s expiry, rather than a client-forced choice, must end the wait.
    await waitFor(() => has('#self-check-title'), 'the default20s deadline did not automatically offer self-check', 25_000);
    const automaticAfterMs = Date.now() - sent;
    need(automaticAfterMs >= 19_500, `automatic self-check arrived early (${automaticAfterMs}ms instead of the default20s)`);
    const path = `/api/decks/${id}/study-sessions/${attempts.deadline.sessionId}/attempts/${attempts.deadline.attemptId}`;
    const attempt = await api('GET', path);
    need(attempt.status === 200 && attempt.body.status === 'SELF_CHECK' && attempt.body.reason === 'DEADLINE'
      && !('evidence' in attempt.body) && !('transition' in attempt.body), 'the timeout produced a terminal grade instead of DEADLINE self-check');
    const afterUsage = await api('GET', '/api/usage');
    const afterProgress = await api('GET', `/api/decks/${id}/study-progress?limit=100`);
    need(afterUsage.status === 200 && afterProgress.status === 200, 'the deadline state could not be read');
    need(afterUsage.body.fairUse.assessment.used === beforeUsage.body.fairUse.assessment.used
      && afterUsage.body.fairUse.assessment.usedToday === beforeUsage.body.fairUse.assessment.usedToday, 'the undelivered deadline grade debited fair use');
    need(JSON.stringify(afterProgress.body.items) === JSON.stringify(beforeProgress.body.items), 'the deadline changed public canonical progress');
    need(await page(`return document.activeElement?.id === 'self-check-title' && !document.querySelector('app-assessment-result');`),
      'the automatic deadline self-check lost focus or rendered a grade');
    await shots('deadline-self-check', 'the automatic deadline self-check', { full: true });
    await tab.call('Page.reload', { ignoreCache: true });
    await waitFor(() => has('#self-check-title'), 'deadline self-check was not restored after reload', 20_000);
    const restored = await api('GET', path);
    need(restored.body.status === 'SELF_CHECK' && restored.body.reason === 'DEADLINE', 'reloading replaced the deadline self-check');
    need(out.wire.submits.length === counters.submits + 1 && out.wire.selfChecks === counters.selfChecks
      && out.wire.selfRatings === counters.selfRatings && out.wire.disputes === counters.disputes, 'the automatic deadline sent an extra answer or explicit choice');
    return { configuredDeadlineMs: 20_000, stubAttemptCapMs: 25_000, faultFixture: true, automaticAfterMs, reason: 'DEADLINE', one202: true, explicitChoiceRequests: 0,
      assessmentUsageUnchanged: true, publicProgressUnchanged: true, noTerminalGrade: true, reloadRetained: true };
  });

  record('assessment_semantic_stub_real_api', { ...out, durationMs: Date.now() - startedAt });
}
