// Real-browser check of exercise generation with batch review (#291, AI-13) against the real Learning API with the Stub text
// provider (`--generation`; never a real provider, no key). Runs at the end of the Workshop scenarios on the signed-in account's tab,
// in a deck of its own, so Study sees only the exercises this scenario made. Node 24 built-ins only.
//
// The user's whole path is the real Angular UI on the real HTTP surface: the Deck hub's bulk bar, the builder (the composer in
// exercise mode), the Workshop's batch review (a playable preview per proposal, the «Оставить» choice, «Изменить» into the editor,
// «Сохранить выбранные (N)»), «Новое» in the list of the material and in Study. Only the fixture is made through the authenticated
// API: the deck and three materials (one clean, one the Stub breaks once and repairs, one it never repairs; a marker sits in the
// second paragraph, never in the first: the first line is the material's title, and a title reaches the deck brief of every step). The request asks for
// three mechanics (the Stub breaks the CHOICE of a marked material), three exercises per material.
// A broken step is a finding for the product, never something to work around: it throws, a failure screenshot is written and the
// run fails with the step name.
import { writeFile } from 'node:fs/promises';
import { join } from 'node:path';

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const SHIFT = 8;

const MATERIALS = [
  { key: 'clean', title: 'Планировщик выбирает Seq Scan или Index Scan', lines: [
    'Планировщик выбирает Seq Scan или Index Scan.',
    'Seq Scan читает всю таблицу подряд, а Index Scan идёт по индексу к нужным строкам.',
    'Для небольшой таблицы Seq Scan часто быстрее, потому что индекс не нужен.',
    'Статистика таблицы помогает планировщику оценить число строк.'] },
  { key: 'repaired', title: 'Индексы B-tree ускоряют поиск', lines: [
    'Индексы B-tree ускоряют поиск.',
    'B-tree хранит ключи в отсортированном порядке и держит дерево сбалансированным. [[stub:broken-key]]',
    'Поиск по равенству и по диапазону использует один и тот же индекс.',
    'Каждый индекс замедляет вставку, потому что его тоже нужно обновлять.'] },
  { key: 'broken', title: 'Партиции делят большую таблицу на части', lines: [
    'Партиции делят большую таблицу на части.',
    'Партицию можно выбрать по диапазону дат или по списку значений. [[stub:broken-key-always]]',
    'Планировщик отсекает ненужные партиции и читает только подходящие.',
    'Слишком много партиций тоже вредит: растёт время планирования.'] }
];

const paragraph = (text, index) => ({ id: `00000000-0000-4000-8000-${String(index * 2 + 1).padStart(12, '0')}`, type: 'paragraph', version: 1, attrs: {},
  content: [{ id: `00000000-0000-4000-8000-${String(index * 2 + 2).padStart(12, '0')}`, type: 'text', version: 1, attrs: { text, marks: [] }, content: [] }] });
const nativeDocument = lines => ({ formatVersion: 1, root: { id: '00000000-0000-4000-8000-000000000000', type: 'doc', version: 1, attrs: {},
  content: lines.map(paragraph) } });

export async function runWorkshopExercises(ctx, h) {
  const { tab, config, SafeFailure, until, navigate, saveScreenshot, setStep } = ctx;
  const { api, page, press, has, need, settle, metrics, desktop, awaitCapability, activeSessions, sessionPath: baseSessionPath, location } = h;
  const out = { stub: true, stages: {}, screenshots: [] };
  const baseDeckId = h.deckId;

  const failureShot = async name => { try { await saveScreenshot(`failure-exercises-${name}.png`, tab); } catch { /* the original failure is the verdict */ } };
  const step = async (name, body) => {
    setStep(`exercises_${name}`);
    try { const result = await body(); out.stages[name] = result ?? true; return result; } catch (error) {
      await failureShot(name);
      await writeFile(join(config.output, `failure-exercises-${name}.txt`),
        (error instanceof SafeFailure ? error.message : `driver: ${String(error?.message ?? error).slice(0, 160)}`) + '\n').catch(() => {});
      throw error;
    }
  };

  // ---- helpers ---------------------------------------------------------------------------------------------------------
  const text = value => (value ?? '').replace(/\s+/gu, ' ').trim();
  /** A real mouse click on the visible control with exactly this text (or a CSS selector) inside `scope`. */
  const click = async (what, scope = 'main') => {
    const point = await page(`const [what, scope] = args;
      const pool = [...document.querySelectorAll(scope + ' button, ' + scope + ' a, ' + scope + ' label, ' + scope + ' summary')];
      const node = what.startsWith('css:') ? document.querySelector(scope + ' ' + what.slice(4))
        : pool.find(item => item.textContent.replace(/\\s+/g, ' ').replaceAll('\\u00a0', ' ').trim() === what);
      if (!(node instanceof HTMLElement)) return null;
      if (node.getAttribute('aria-disabled') === 'true') return 'disabled';
      node.scrollIntoView({ block: 'center', behavior: 'instant' });
      const rect = node.getBoundingClientRect();
      return { x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 };`, what, scope);
    need(point !== null && point !== 'disabled', `«${what}» is ${point === null ? 'absent' : 'disabled'} in ${scope}`);
    await tab.call('Page.bringToFront');
    await tab.call('Input.dispatchMouseEvent', { type: 'mouseMoved', x: point.x, y: point.y });
    await tab.call('Input.dispatchMouseEvent', { type: 'mousePressed', x: point.x, y: point.y, button: 'left', clickCount: 1 });
    await tab.call('Input.dispatchMouseEvent', { type: 'mouseReleased', x: point.x, y: point.y, button: 'left', clickCount: 1 });
    await settle();
  };
  const focusOf = () => page(`const active = document.activeElement;
    if (!active || active === document.body) return { body: true };
    return { tag: active.tagName.toLowerCase(), id: active.id, text: (active.getAttribute('aria-label') ?? active.textContent ?? '').replace(/\\s+/g, ' ').trim().slice(0, 40),
      afterSave: active.hasAttribute('data-after-save'), card: active.closest('[data-card]')?.getAttribute('data-card') ?? null,
      inFooter: Boolean(active.closest('.review-footer')) };`);
  const bodyText = () => page(`return document.body.innerText.replace(/\\s+/g, ' ').replaceAll('\\u00a0', ' ');`);
  /** Horizontal overflow and primary targets under 44 px (WCAG 2.5.5 level of the paper design doc), the same bar as the Workshop. */
  const layout = () => page(`const doc = document.documentElement;
    const main = document.querySelector('main') ?? document.body;
    const visible = element => { const rect = element.getBoundingClientRect(); const style = getComputedStyle(element);
      return rect.width > 0 && rect.height > 0 && style.visibility !== 'hidden' && style.display !== 'none'; };
    const small = [...main.querySelectorAll('.button, .generate-cta, .chip, .segment, .keep')].filter(visible)
      .map(element => { const rect = element.getBoundingClientRect(); return { cls: String(element.className).slice(0, 24), text: (element.textContent ?? '').trim().slice(0, 24), w: Math.round(rect.width), h: Math.round(rect.height) }; })
      .filter(entry => entry.h < 43.5);
    const wide = [...main.querySelectorAll('*')].filter(element => visible(element) && element.getBoundingClientRect().right > doc.clientWidth + 1)
      .map(element => element.tagName.toLowerCase() + '.' + String(element.className).split(' ')[0]).slice(0, 4);
    return { overflow: doc.scrollWidth > doc.clientWidth, scrollWidth: doc.scrollWidth, clientWidth: doc.clientWidth, small, wide };`);
  /** Screenshots at 1440 and 390 px; the 44 px target check applies only to the pages this task owns (`strictTargets`). */
  const shots = async (prefix, label, strictTargets = true, scrollTo = null) => {
    const result = {};
    for (const [tag, w, hgt, mobile] of [['1440', 1440, 900, false], ['390', 390, 844, true]]) {
      await metrics(w, hgt, 1, mobile); await settle();
      if (scrollTo !== null) { await page(`document.querySelector(args[0])?.scrollIntoView({ block: 'start', behavior: 'instant' }); return true;`, scrollTo); await settle(); }
      const facts = await layout();
      need(!facts.overflow, `${label} overflows horizontally at ${w} px (${facts.scrollWidth} > ${facts.clientWidth}: ${facts.wide.join(' ')})`);
      if (strictTargets) need(facts.small.length === 0, `${label} has controls below 44 px at ${w} px: ${JSON.stringify(facts.small)}`);
      const name = `exercises-${prefix}-${tag}.png`;
      await saveScreenshot(name, tab);
      out.screenshots.push(name);
      result[tag] = { overflow: false };
    }
    await desktop(); await settle();
    return result;
  };

  // The fixture deck: its own, so Study and the lists see only what this scenario makes.
  let deck = null;
  const deckPathOf = () => `/decks/${deck.deckId}`;
  const sessionPath = id => `/api/decks/${deck.deckId}/generation-sessions/${id}`;
  const getSession = async id => {
    const result = await api('GET', sessionPath(id));
    need(result.status === 200, `GET generation session answered ${result.status}`);
    return result.body;
  };
  const getArtifact = async (sessionId, artifactId) => {
    const result = await api('GET', `${sessionPath(sessionId)}/artifacts/${artifactId}`);
    need(result.status === 200, `GET artifact answered ${result.status}`);
    return result.body;
  };
  const listExercises = async memberKey => {
    const result = await api('GET', `/api/decks/${deck.deckId}/exercises?memberKey=${memberKey}&limit=20`);
    need(result.status === 200, `GET exercises answered ${result.status}`);
    return result.body.exercises;
  };
  const sessionIdFrom = path => path.match(/\/workshop\/([0-9a-f-]{36})$/u)?.[1] ?? null;

  await desktop();
  await awaitCapability();
  for (const session of await activeSessions()) {
    const gone = await api('DELETE', baseSessionPath(session.sessionId));
    need([204, 404].includes(gone.status), `deleting an earlier session answered ${gone.status}`);
  }

  await step('fixture', async () => {
    const created = await api('POST', '/api/decks', { commandId: crypto.randomUUID(), metadata: { title: 'Упражнения с ИИ: проверка', description: '' } });
    need(created.status === 201, `POST deck answered ${created.status}`);
    const id = created.body.deck?.deckId ?? created.body.deckId;
    need(id, 'the created deck has no id');
    deck = { deckId: id, materials: [] };
    for (const material of MATERIALS) {
      const current = await api('GET', `/api/decks/${deck.deckId}`);
      need(current.status === 200, `GET deck answered ${current.status}`);
      const made = await api('POST', `/api/decks/${deck.deckId}/items`, { commandId: crypto.randomUUID(), expectedDeckRevisionId: current.body.revisionId,
        document: nativeDocument(material.lines) }, { 'If-Match': current.etag });
      need(made.status === 201, `POST item answered ${made.status} ${JSON.stringify(made.body?.code ?? made.body?.detail ?? null)}`);
      const memberKey = made.body.changes?.[0]?.memberKey ?? made.body.memberKey;
      need(memberKey, 'the created material has no member key');
      deck.materials.push({ ...material, memberKey });
    }
    return { materials: deck.materials.length };
  });
  const [clean, repaired, broken] = deck.materials;

  // =====================================================================================================================
  // 1. The Deck hub: select, then «Упражнения с ИИ для выбранных»
  // =====================================================================================================================
  await step('hub_entry', async () => {
    await navigate(deckPathOf(), tab);
    await until(() => has('app-deck-materials li.item-row'), 'the Deck hub did not list the materials', 25_000);
    need(!(await has('app-bulk-action-bar')), 'the bulk bar is visible with nothing selected');
    for (let index = 0; index < 3; index++) {
      need(await page(`const box = document.querySelectorAll('app-deck-materials li .check input')[args[0]]; if (!box) return false; box.click(); return box.checked;`, index),
        `material ${index + 1} could not be selected`);
    }
    await until(() => has('app-bulk-action-bar'), 'the bulk bar did not appear');
    const bar = await bodyText();
    need(bar.includes('Выбрано 3 материала'), 'the bulk bar does not count the selection');
    await click('Упражнения с ИИ для выбранных', 'app-bulk-action-bar');
    await until(async () => (await location()).startsWith(`${deckPathOf()}/exercises/generate`), 'the bulk bar did not open the exercise builder', 15_000);
    const path = await location();
    need(/members=/u.test(path), 'the builder address does not carry the members');
    for (const material of deck.materials) need(path.includes(material.memberKey), 'the builder address misses a selected member');
    need(!/55555555|itemRevisionId|revision/iu.test(path), 'the builder address carries a revision');
    return { path: path.replace(/[0-9a-f-]{36}/gu, '<id>') };
  });

  // =====================================================================================================================
  // 2. The builder: the composer in exercise mode
  // =====================================================================================================================
  await step('builder', async () => {
    await until(() => has('app-exercise-builder-page form'), 'the exercise builder did not open', 25_000);
    const facts = () => page(`const root = document.querySelector('app-exercise-builder-page');
      const box = label => [...root.querySelectorAll('.chip')].find(chip => chip.textContent.trim() === label)?.querySelector('input');
      return { heading: root.querySelector('h1')?.textContent.trim(), summary: root.querySelector('.targets-title')?.textContent.replaceAll('\\u00a0', ' ').trim(),
        auto: box('Авто')?.checked, choice: box('Выбрать ответ')?.checked, cloze: box('Заполнить пропуски')?.checked,
        legends: [...root.querySelectorAll('legend')].map(node => node.textContent.trim()),
        radios: [...root.querySelectorAll('input[type=radio]')].map(input => ({ value: input.value, checked: input.checked })),
        estimate: root.querySelector('.estimate')?.textContent.replaceAll('\\u00a0', ' ').trim() ?? '',
        cta: [...root.querySelectorAll('.generate-cta')].map(node => node.textContent.trim()),
        statusRegions: root.querySelectorAll('[role=status]').length, textareas: root.querySelectorAll('textarea').length };`);
    const first = await facts();
    need(first.heading === 'Упражнения с ИИ', `the heading is «${first.heading}»`);
    need(first.summary === 'Для 3 материалов', `the targets summary is «${first.summary}»`);
    need(first.auto === true && first.choice === false, 'the builder does not start with «Авто»');
    need(first.legends.includes('Что сначала'), 'the priority is not offered for three materials');
    need(first.radios.find(radio => radio.value === 'UNCOVERED_FIRST')?.checked === true, 'UNCOVERED_FIRST is not the default priority');
    need(first.cta.length === 1 && first.cta[0] === 'Создать упражнения', `the primary button is ${JSON.stringify(first.cta)}`);
    need(first.textareas === 0, 'the exercise builder has a free-text field');
    await until(async () => /лимита$/u.test((await facts()).estimate), 'the preflight «≈ N % лимита» did not appear', 15_000);
    const estimate = (await facts()).estimate;
    // The preflight is the real estimate for the same request.
    const wire = await api('POST', `/api/decks/${deck.deckId}/generation-estimates`, { spec: { kind: 'EXERCISES',
      targets: await page(`const decks = await fetch(args[0] + '/api/decks/' + args[1] + '/items?limit=20&include=exerciseCount', { credentials: 'omit', headers: { Authorization: args[2] } });
        const body = await decks.json(); return body.items.map(item => ({ memberKey: item.memberKey, itemRevisionId: item.itemRevisionId }));`, config.frontend, deck.deckId, 'Bearer ' + ctx.bearer),
      settings: { mechanics: 'AUTO', priority: 'UNCOVERED_FIRST', quantity: { mode: 'AUTO' }, planFirst: false, budgetPercent: null } } });
    need(wire.status === 200, `POST generation-estimates for EXERCISES answered ${wire.status}`);
    need(estimate.includes(`${wire.body.percentOfPeriodAllowance.p95}`) || /менее 1/u.test(estimate), `the preflight «${estimate}» is not the server's ${wire.body.percentOfPeriodAllowance.p95} %`);

    // «Авто» is exclusive: a mechanic unchecks it, and unchecking the mechanic returns to it.
    await click('Выбрать ответ', 'app-exercise-builder-page .chips');
    let state = await facts();
    need(state.auto === false && state.choice === true, 'checking a mechanic did not uncheck «Авто»');
    await click('Выбрать ответ', 'app-exercise-builder-page .chips');
    state = await facts();
    need(state.auto === true && state.choice === false, 'unchecking the last mechanic did not return to «Авто»');

    // «Точно»: a native range with its own spoken value; arrow keys move it.
    await click('Точно', 'app-exercise-builder-page app-segmented-choice');
    await until(() => has('app-exercise-builder-page input[type=range]'), 'the «Точно» slider did not appear');
    const slider = () => page(`const range = document.querySelector('app-exercise-builder-page input[type=range]');
      return { min: range.min, max: range.max, value: range.value, text: range.getAttribute('aria-valuetext')?.replaceAll('\\u00a0', ' '),
        out: document.querySelector('app-exercise-builder-page output')?.textContent.replaceAll('\\u00a0', ' ').trim(), label: range.labels?.[0]?.textContent.trim() };`);
    const before = await slider();
    need(before.min === '1' && before.max === '10', `the slider range is ${before.min}..${before.max}`);
    need(before.text === '3 упражнения на материал' && before.out === before.text, `the slider says «${before.text}» / «${before.out}»`);
    need(await page(`const range = document.querySelector('app-exercise-builder-page input[type=range]'); range.focus(); return document.activeElement === range;`), 'the slider took no focus');
    await press('ArrowRight'); await press('ArrowRight');
    // The native value moves at once; the spoken value and the output follow after Angular renders.
    await until(async () => (await slider()).text === '5 упражнений на материал', `ArrowRight x2 left the slider at «${(await slider()).text}» (${(await slider()).value})`, 5_000);
    const moved = await slider();
    need(moved.value === '5' && moved.out === moved.text, `ArrowRight x2 left the slider at «${moved.text}» / «${moved.out}»`);
    await press('ArrowLeft'); await press('ArrowLeft');
    await until(async () => (await slider()).text === '3 упражнения на материал', 'ArrowLeft did not bring the slider back', 5_000);
    await click('Не больше X% лимита', 'app-exercise-builder-page app-segmented-choice');
    const percent = await slider();
    need(percent.min === '1' && percent.max === '100' && /лимита$/u.test(percent.text), `the percent slider says «${percent.text}» (${percent.min}..${percent.max})`);
    await click('Точно', 'app-exercise-builder-page app-segmented-choice');
    // Three mechanics, chosen by their names: every material gets a SELF_CHECK, a CHOICE and a CLOZE (the Stub breaks the CHOICE of a marked one).
    for (const label of ['Вспомнить и сверить', 'Выбрать ответ', 'Заполнить пропуски']) await click(label, 'app-exercise-builder-page .chips');
    state = await facts();
    need(state.auto === false && state.choice === true && state.cloze === true, `after choosing three mechanics the chips are ${JSON.stringify(state)}`);
    const responsive = await shots('builder', 'the exercise builder');
    return { estimate, auto: 'exclusive', slider: 'keyboard', mechanics: 3, responsive };
  });

  // =====================================================================================================================
  // 3. Create: a session of three materials, nine exercises (three each: eight proposed, one the Stub never repairs), then the batch review
  // =====================================================================================================================
  let sessionId = null;
  let counts = null;
  await step('create_and_stream', async () => {
    await click('Создать упражнения', 'app-exercise-builder-page .action-row');
    await until(async () => sessionIdFrom(await location()) !== null, 'the builder did not open the Workshop', 30_000);
    sessionId = sessionIdFrom(await location());
    await until(() => has('app-exercise-batch-review'), 'the Workshop did not switch to the exercise review', 25_000);
    const heading = await page(`return document.querySelector('section.workshop h1')?.textContent.trim();`);
    need(heading === 'Мастерская упражнений', `the Workshop heading is «${heading}»`);
    await until(async () => (await getSession(sessionId)).artifacts.length === 9
      && (await getSession(sessionId)).artifacts.every(artifact => ['PROPOSED', 'FAILED'].includes(artifact.state)), 'the exercise batch did not settle', 120_000);
    const session = await getSession(sessionId);
    need(session.kind === 'EXERCISES', `the session kind is ${session.kind}`);
    const states = session.artifacts.reduce((count, artifact) => ({ ...count, [artifact.state]: (count[artifact.state] ?? 0) + 1 }), {});
    const failedDetail = session.artifacts.filter(artifact => artifact.state === 'FAILED').map(artifact => `${artifact.ordinal}:${artifact.errorCode}`).join(',');
    // The repaired material is shown whole; the CHOICE the Stub never repairs is FAILED(INVALID_OUTPUT) and never proposed.
    need((states.PROPOSED ?? 0) >= 6 && (states.FAILED ?? 0) >= 1 && (states.PROPOSED ?? 0) + (states.FAILED ?? 0) === 9,
      `the batch is ${JSON.stringify(states)} (failed: ${failedDetail}), not at least 6 proposed and the rest failed`);
    need(session.artifacts.filter(artifact => artifact.state === 'FAILED').every(artifact => artifact.errorCode === 'INVALID_OUTPUT'), `a failed exercise is not INVALID_OUTPUT (${failedDetail})`);
    counts = { proposed: states.PROPOSED, failed: states.FAILED };
    const details = [];
    for (const artifact of session.artifacts.filter(item => item.state === 'PROPOSED')) details.push(await getArtifact(sessionId, artifact.artifactId));
    const mechanics = [...new Set(details.map(detail => detail.display?.mechanic))];
    need(mechanics.length >= 3 && !mechanics.includes(undefined), `the proposals use ${mechanics.length} mechanics (${mechanics.join(', ')}), not at least three`);
    need(details.every(detail => detail.revision.payload.kind === 'EXERCISE_COMMAND' && detail.display?.quotes && typeof detail.display.objectiveTitle === 'string'),
      'a proposal lacks the payload or the display members');
    out.session = { states, mechanics, failedOrdinals: failedDetail };
    return out.session;
  });

  await step('review_layout', async () => {
    await until(async () => (await page(`return document.querySelectorAll('app-exercise-batch-review li.card app-exercise-preview-host').length;`)) === counts.proposed,
      `the ${counts.proposed} proposals did not render as playable previews`, 30_000);
    // The summary is a throttled live region (one change per two seconds) fed by polling: wait for it to catch up with the server.
    const expectedSummary = `${counts.proposed} готово · ${counts.failed} ${counts.failed === 1 ? 'не удался' : 'не удались'}`;
    await until(async () => (await page(`return document.querySelector('.exercise-summary')?.textContent.replaceAll('\\u00a0', ' ').trim();`)) === expectedSummary,
      `the summary never became «${expectedSummary}»`, 30_000);
    const view = await page(`const root = document.querySelector('app-exercise-batch-review');
      return { groups: [...root.querySelectorAll('.group-title')].map(node => node.textContent.trim()),
        cards: root.querySelectorAll('li.card').length, checked: [...root.querySelectorAll('li.card input[type=checkbox]')].filter(box => box.checked).length,
        summary: document.querySelector('.exercise-summary')?.textContent.replaceAll('\\u00a0', ' ').trim(),
        save: root.querySelector('[data-save]')?.textContent.trim(), failedText: root.querySelector('[data-state=FAILED]')?.textContent.replace(/\\s+/g, ' ').trim() ?? null,
        failedRetry: Boolean([...root.querySelectorAll('[data-state=FAILED] button')].find(button => button.textContent.trim() === 'Повторить')),
        ids: (() => { const all = [...document.querySelectorAll('[id]')].map(node => node.id); return all.length - new Set(all).size; })(),
        regions: document.querySelectorAll('section.workshop [role=status]').length };`);
    need(view.cards === 9, `the review shows ${view.cards} cards, not nine`);
    for (const material of deck.materials.slice(0, 2)) need(view.groups.some(group => group.startsWith(material.title.slice(0, 20))), `no group is named after «${material.title}»`);
    need(view.groups.includes('Не удались'), 'the failed exercises have no group of their own');
    need(view.checked === counts.proposed, `${view.checked} proposals are checked by default, not ${counts.proposed}`);
    need(view.summary === `${counts.proposed} готово · ${counts.failed} ${counts.failed === 1 ? 'не удался' : 'не удались'}`, `the summary is «${view.summary}»`);
    need(view.save === `Сохранить выбранные (${counts.proposed})`, `the primary button says «${view.save}»`);
    need(/не смогла собрать корректное упражнение/u.test(view.failedText ?? '') && /лимит не списан/u.test(view.failedText ?? ''), `a failed exercise says «${view.failedText}»`);
    need(view.failedRetry, 'a failed exercise has no «Повторить»');
    need(view.ids === 0, `${view.ids} duplicate ids on the review page`);
    const responsive = await shots('review', 'the exercise review');
    return { cards: view.cards, groups: view.groups.length, responsive };
  });

  // =====================================================================================================================
  // 4. A proposal is played in its preview: the preview endpoint only, no attempt, no schedule, nothing saved
  // =====================================================================================================================
  await step('preview_play', async () => {
    const before = await listExercises(clean.memberKey);
    need(before.length === 0, 'an exercise exists before anything was saved');
    await page(`performance.clearResourceTimings(); return true;`);
    const mechanicOf = await page(`return [...document.querySelectorAll('app-exercise-batch-review li.card[data-state=PROPOSED]')].map(card => ({
      id: card.dataset.card, title: card.querySelector('h3')?.textContent.trim(), reveal: Boolean(card.querySelector('[data-answer-control]')), choice: Boolean(card.querySelector('input[type=radio], input[type=checkbox][name]')) }));`);
    const target = mechanicOf.find(entry => entry.reveal) ?? null;
    need(target !== null, `no proposal can be played with a reveal button (${JSON.stringify(mechanicOf.map(entry => entry.title))})`);
    const scope = `[data-card="${target.id}"]`;
    await click('css:[data-answer-control]', scope);
    await until(() => has(`${scope} [data-first-rating]`), 'revealing the reference did not offer the self-rating');
    await click('css:[data-first-rating]', scope);
    await until(() => has(`${scope} [id$="-result-title"]`), 'the preview did not show a result', 20_000);
    const result = await page(`const card = document.querySelector(args[0]); const title = card.querySelector('[id$="-result-title"]');
      return { text: title.textContent.trim(), focused: document.activeElement === title, restart: Boolean([...card.querySelectorAll('button')].find(button => button.textContent.trim() === 'Начать заново')) };`, scope);
    need(['Верно', 'Нужно повторить', 'Частично', 'Неуверенно'].includes(result.text), `the preview result is «${result.text}»`);
    need(result.focused, 'focus did not move to the preview result');
    need(result.restart, 'the preview result has no «Начать заново»');
    const requests = await page(`return performance.getEntriesByType('resource').map(entry => new URL(entry.name).pathname).filter(path => path.startsWith('/api/'));`);
    need(requests.some(path => path === '/api/exercise-previews'), 'the preview did not use the author preview endpoint');
    need(!requests.some(path => /study-sessions|attempts|pair-checks|\/hints|transcript/u.test(path)), `the preview called a Study endpoint: ${requests.join(', ')}`);
    need((await listExercises(clean.memberKey)).length === 0 && (await listExercises(repaired.memberKey)).length === 0, 'playing a proposal saved an exercise');
    return { previewRequests: requests.filter(path => path === '/api/exercise-previews').length, studyRequests: 0, saved: 0 };
  });

  // =====================================================================================================================
  // 5. «Изменить»: the proposal opens in the editor; saving approves it with the edited exercise
  // =====================================================================================================================
  let editedMember = null;
  await step('edit_proposal', async () => {
    const session = await getSession(sessionId);
    const details = [];
    for (const artifact of session.artifacts.filter(item => item.state === 'PROPOSED')) details.push(await getArtifact(sessionId, artifact.artifactId));
    const mine = details.find(detail => detail.revision.payload.command.exercise.subject.memberKey === clean.memberKey);
    need(mine, 'no proposal of the clean material');
    editedMember = clean.memberKey;
    await click('Изменить', `[data-card="${mine.artifactId}"]`);
    await until(async () => (await location()).includes('/exercises/new?session='), 'the «Изменить» link did not open the editor', 20_000);
    const path = await location();
    need(path.includes(`session=${sessionId}`) && path.includes(`artifact=${mine.artifactId}`), 'the editor address does not name the session and the artifact');
    await until(() => has('app-exercise-authoring-page #step-finish'), 'the editor did not open the proposal with every step', 25_000);
    const state = await page(`const root = document.querySelector('app-exercise-authoring-page');
      return { eyebrow: root.querySelector('.eyebrow')?.textContent.trim(), back: root.querySelector('a[data-back-workshop]')?.textContent.trim(),
        save: [...root.querySelectorAll('.save-actions button')].map(button => button.textContent.trim()), preview: Boolean(root.querySelector('app-exercise-preview-host')),
        title: root.querySelector('#objective-title')?.value ?? null };`);
    need(state.eyebrow === 'Правка упражнения Мнемы', `the editor eyebrow is «${state.eyebrow}»`);
    need(state.back === '← К мастерской', `the way back is «${state.back}»`);
    need(state.save.includes('Сохранить в колоду'), `the save button is ${JSON.stringify(state.save)}`);
    need(state.preview, 'the editor shows no preview of the proposal');
    need(typeof state.title === 'string' && state.title.length > 0, 'the proposal has no objective title in the editor');
    // The edit: a new objective title, typed with the real keyboard.
    need(await page(`const input = document.querySelector('#objective-title'); input.scrollIntoView({ block: 'center' }); input.focus(); input.select(); return document.activeElement === input;`), 'the objective title took no focus');
    await tab.call('Input.insertText', { text: 'Правка: выбор метода чтения' });
    need(await page(`return document.querySelector('#objective-title').value;`) === 'Правка: выбор метода чтения', 'the typed objective title did not land');
    await click('Сохранить в колоду', 'app-exercise-authoring-page');
    await until(async () => sessionIdFrom(await location()) === sessionId, 'saving did not return to the Workshop', 25_000);
    await until(async () => (await getSession(sessionId)).artifacts.find(artifact => artifact.artifactId === mine.artifactId)?.state === 'PUBLISHED', 'the API did not publish the edited proposal', 20_000);
    const saved = (await listExercises(clean.memberKey)).find(exercise => exercise.objective.title === 'Правка: выбор метода чтения');
    need(saved, 'the saved exercise does not carry the edited objective title');
    need(saved.isNew === true, 'the exercise saved from a proposal is not marked «Новое»');
    await until(() => has(`[data-card="${mine.artifactId}"] app-new-badge`), 'the card of the edited proposal does not say «Сохранено» with «Новое»', 20_000);
    out.edited = { objectiveTitle: saved.objective.title, isNew: saved.isNew };
    return out.edited;
  });

  // =====================================================================================================================
  // 6. Keep / uncheck, «Сохранить выбранные (N)», the toast, focus, then «Отклонить остальные»
  // =====================================================================================================================
  let savedIds = [];
  await step('save_selected', async () => {
    await until(() => has('app-exercise-batch-review [data-save]'), 'the review did not come back');
    const open = await page(`return [...document.querySelectorAll('app-exercise-batch-review li.card[data-state=PROPOSED]')].map(card => card.dataset.card);`);
    need(open.length === counts.proposed - 1, `${open.length} proposals are left to review, not ${counts.proposed - 1}`);
    // Take the check off the last proposal with a real click: the count follows, and the proposal stays out.
    const leftOut = open.at(-1);
    await click('css:input[type=checkbox]', `[data-card="${leftOut}"]`);
    const afterUncheck = await page(`return document.querySelector('app-exercise-batch-review [data-save]').textContent.trim();`);
    const kept = counts.proposed - 2;
    need(afterUncheck === `Сохранить выбранные (${kept})`, `after unchecking the button says «${afterUncheck}»`);
    await click(`Сохранить выбранные (${kept})`, 'app-exercise-batch-review');
    await until(async () => (await bodyText()).includes(`Новые упражнения: ${kept} — уже в колоде`), `the toast «Новые упражнения: ${kept} — уже в колоде» did not appear`, 20_000);
    await until(async () => (await getSession(sessionId)).artifacts.filter(artifact => artifact.state === 'PUBLISHED').length === kept + 1, 'the API did not publish the kept proposals', 20_000);
    savedIds = (await getSession(sessionId)).artifacts.filter(artifact => artifact.state === 'PUBLISHED').map(artifact => artifact.artifactId);
    await until(async () => { const now = await focusOf(); return !now.body; }, 'after saving focus stayed on nothing (the body)', 10_000);
    const focus = await focusOf();
    need(!focus.body && focus.tag !== 'h1', `after saving focus is ${JSON.stringify(focus)} (the page heading or nothing)`);
    need(focus.afterSave === true || focus.inFooter === true, `after saving focus is not on the next control of the footer: ${JSON.stringify(focus)}`);
    const footer = await page(`const root = document.querySelector('app-exercise-batch-review');
      return { rejectLeft: [...root.querySelectorAll('.review-footer button')].map(button => button.textContent.trim()), note: root.querySelector('.footer-note')?.textContent.trim() };`);
    need(footer.rejectLeft.includes('Отклонить остальные (1)'), `the footer offers ${JSON.stringify(footer.rejectLeft)} after saving`);
    await saveScreenshot('exercises-saved-1440.png', tab);
    out.screenshots.push('exercises-saved-1440.png');
    await click('Отклонить остальные (1)', 'app-exercise-batch-review');
    await until(async () => (await getSession(sessionId)).artifacts.find(artifact => artifact.artifactId === leftOut)?.state === 'REJECTED', 'the API did not reject the left-out proposal', 20_000);
    await until(async () => (await bodyText()).includes('Отклонено: в колоду не попадёт'), 'the card does not say it was rejected', 10_000);
    // The server's truth: the kept ones are in the deck and new; the rejected one is not.
    const all = [...await listExercises(clean.memberKey), ...await listExercises(repaired.memberKey), ...await listExercises(broken.memberKey)];
    need(all.length === counts.proposed - 1, `${all.length} exercises are in the deck, not ${counts.proposed - 1}`);
    need(all.every(exercise => exercise.isNew === true), 'a saved exercise is not marked «Новое»');
    // The saved ones that share a direction share one objective (reuse by title at approval time): the edited one has its own.
    const objectives = new Set(all.map(exercise => exercise.objective.objectiveId));
    need(objectives.size <= 4, `${objectives.size} objectives for ${all.length} exercises of three materials: the approval does not reuse an objective`);
    return { saved: all.length, rejected: 1, objectives: objectives.size };
  });

  // =====================================================================================================================
  // 7. «Новое» in the list of the material
  // =====================================================================================================================
  const listBadges = () => page(`return { rows: document.querySelectorAll('#existing-exercises li').length,
    badges: [...document.querySelectorAll('#existing-exercises li app-new-badge')].map(node => node.textContent.trim()) };`);
  let listed = null;
  await step('new_in_list', async () => {
    const forClean = (await listExercises(clean.memberKey)).length;
    need(forClean >= 1, 'the clean material has no saved exercise');
    listed = clean;
    await navigate(`${deckPathOf()}/materials/${clean.memberKey}/exercises/new`, tab);
    await until(() => has('#existing-exercises li'), 'the list of the material shows no exercise', 25_000);
    const before = await listBadges();
    need(before.badges.length === before.rows && before.badges.every(label => label === 'Новое'), `the list shows ${JSON.stringify(before)}: «Новое» is not on every new row`);
    const responsive = await shots('list', 'the exercise list of the material', false, '#existing-exercises');
    return { rows: before.rows, newMarks: before.badges.length, responsive };
  });

  // =====================================================================================================================
  // 8. «Новое» in Study: every exercise of this deck is new, so the first card carries the mark
  // =====================================================================================================================
  await step('study_new', async () => {
    await navigate(`${deckPathOf()}/study`, tab);
    await until(() => has('.session-setup'), 'Study did not offer its presets', 25_000);
    await click('Начать стандартную', '.session-setup');
    await until(() => has('.study-card'), 'Study did not issue a card', 25_000);
    const card = await page(`const card = document.querySelector('.study-card');
      return { eyebrow: card.querySelector('.eyebrow')?.textContent.replace(/\\s+/g, ' ').trim(), badge: card.querySelector('app-new-badge')?.textContent.trim() ?? null };`);
    need(card.badge === 'Новое', `the first Study card has the mark «${card.badge}»: the exercises of this deck are all new`);
    need(card.eyebrow.includes('Вспомните без подсказки'), `the Study card eyebrow is «${card.eyebrow}»`);
    for (const [tag, w, hgt, mobile] of [['1440', 1440, 900, false], ['390', 390, 844, true]]) {
      await metrics(w, hgt, 1, mobile); await settle();
      const facts = await layout();
      need(!facts.overflow, `the Study card overflows at ${w} px`);
      const name = `exercises-study-new-${tag}.png`;
      await saveScreenshot(name, tab);
      out.screenshots.push(name);
    }
    await desktop(); await settle();
    return { badge: card.badge };
  });

  // =====================================================================================================================
  // 9. Opening an exercise clears its mark on the server, and the list stops showing it
  // =====================================================================================================================
  await step('new_cleared_on_open', async () => {
    await navigate(`${deckPathOf()}/materials/${listed.memberKey}/exercises/new`, tab);
    await until(() => has('#existing-exercises li'), 'the list did not come back', 25_000);
    const before = await listBadges();
    const row = await page(`const link = document.querySelector('#existing-exercises li a.button'); return link ? link.getAttribute('href') : null;`);
    need(row !== null, 'the list has no «Настроить» link');
    const exerciseId = row.match(/exercises\/([0-9a-f-]{36})\/edit/u)?.[1];
    need(exerciseId, 'the «Настроить» link does not name an exercise');
    await navigate(row, tab);
    await until(() => has('app-exercise-authoring-page #step-finish'), 'the saved exercise did not open in the editor', 25_000);
    await until(async () => (await listExercises(listed.memberKey)).find(exercise => exercise.exerciseId === exerciseId)?.isNew === false,
      'opening the exercise did not clear «Новое» on the server', 15_000);
    await navigate(`${deckPathOf()}/materials/${listed.memberKey}/exercises/new`, tab);
    await until(() => has('#existing-exercises li'), 'the list did not come back after opening');
    const after = await listBadges();
    need(after.badges.length === before.badges.length - 1, `after opening one exercise the list shows ${after.badges.length} of ${before.badges.length} marks`);
    return { newBefore: before.badges.length, newAfter: after.badges.length };
  });

  return out;
}
