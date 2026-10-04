// Real-browser check of the planner «Сначала показать план» (#295, AI-14) against the real Learning API with the Stub text provider
// (`--generation`; never a real provider, no key). Runs at the end of the Workshop scenarios on the signed-in account's tab, in decks
// of its own. Node 24 built-ins only.
//
// The user's whole path is the real Angular UI on the real HTTP surface: the exercise builder with «Ещё настройки» → «Сначала показать
// план» (and the plan's cost line from the real estimate), «Составить план», the Workshop in PLANNING and PLAN_READY with the plan as
// an editable list (a row removed with «Убрать», one taken back with «Вернуть», a count moved with the keyboard, a mechanic unchecked),
// the live total, «Запустить по плану» → exactly the planned artifacts; the plan that fails (`[[stub:plan-invalid-always]]`) ends the
// session with its reason and charges nothing; and the Materials composer with the plan (a topic retyped, an effort changed).
// Only the fixtures (decks, materials) and the checks of what the server holds are made through the authenticated API.
// A broken step is a finding for the product, never something to work around: it throws, a failure screenshot is written and the run
// fails with the step name.
import { writeFile } from 'node:fs/promises';
import { join } from 'node:path';

const MATERIALS = [
  { key: 'scan', title: 'Планировщик выбирает Seq Scan или Index Scan', lines: [
    'Планировщик выбирает Seq Scan или Index Scan.',
    'Seq Scan читает всю таблицу подряд, а Index Scan идёт по индексу к нужным строкам.',
    'Для небольшой таблицы Seq Scan часто быстрее, потому что индекс не нужен.',
    'Статистика таблицы помогает планировщику оценить число строк.'] },
  { key: 'btree', title: 'Индексы B-tree ускоряют поиск', lines: [
    'Индексы B-tree ускоряют поиск.',
    'B-tree хранит ключи в отсортированном порядке и держит дерево сбалансированным.',
    'Поиск по равенству и по диапазону использует один и тот же индекс.',
    'Каждый индекс замедляет вставку, потому что его тоже нужно обновлять.'] },
  { key: 'parts', title: 'Партиции делят большую таблицу на части', lines: [
    'Партиции делят большую таблицу на части.',
    'Партицию можно выбрать по диапазону дат или по списку значений.',
    'Планировщик отсекает ненужные партиции и читает только подходящие.',
    'Слишком много партиций тоже вредит: растёт время планирования.'] }
];

/** The material whose title makes the Stub's plan invalid at every attempt (repair and the strong route too): the plan fails. */
const POISONED = { key: 'poison', title: 'Материал для плана, который не получится [[stub:plan-invalid-always]]', lines: [
  'Материал для плана, который не получится [[stub:plan-invalid-always]]',
  'Второй абзац нужен, чтобы материал был настоящим, а не пустым заголовком.'] };

const paragraph = (text, index) => ({ id: `00000000-0000-4000-8000-${String(index * 2 + 1).padStart(12, '0')}`, type: 'paragraph', version: 1, attrs: {},
  content: [{ id: `00000000-0000-4000-8000-${String(index * 2 + 2).padStart(12, '0')}`, type: 'text', version: 1, attrs: { text, marks: [] }, content: [] }] });
const nativeDocument = lines => ({ formatVersion: 1, root: { id: '00000000-0000-4000-8000-000000000000', type: 'doc', version: 1, attrs: {},
  content: lines.map(paragraph) } });

const MECHANIC_LABELS = { SELF_CHECK: 'Вспомнить и сверить', CHOICE: 'Выбрать ответ', CLOZE: 'Заполнить пропуски' };

/** «≈ 2,8 % лимита», as the Workshop says it: a share of the bar, one decimal under ten percent. */
function share(credits, bar) {
  const percent = credits / bar * 100;
  if (percent < 0.1) return 'менее 0,1 % лимита';
  const text = new Intl.NumberFormat('ru-RU', { maximumFractionDigits: percent < 10 ? 1 : 0 }).format(percent);
  return `≈ ${text} % лимита`;
}
const exercisesWord = count => {
  const lastTwo = count % 100; const last = count % 10;
  return lastTwo >= 11 && lastTwo <= 14 ? 'упражнений' : last === 1 ? 'упражнение' : last >= 2 && last <= 4 ? 'упражнения' : 'упражнений';
};

export async function runWorkshopPlanner(ctx, h) {
  const { tab, config, SafeFailure, until, navigate, saveScreenshot, setStep } = ctx;
  const { api, page, press, has, need, settle, metrics, desktop, awaitCapability, activeSessions, sessionPath: baseSessionPath, location } = h;
  const out = { stub: true, stages: {}, screenshots: [] };
  const startedAt = Date.now();

  const failureShot = async name => { try { await saveScreenshot(`failure-planner-${name}.png`, tab); } catch { /* the original failure is the verdict */ } };
  const step = async (name, body) => {
    setStep(`planner_${name}`);
    try { const result = await body(); out.stages[name] = result ?? true; return result; } catch (error) {
      await failureShot(name);
      await writeFile(join(config.output, `failure-planner-${name}.txt`),
        (error instanceof SafeFailure ? error.message : `driver: ${String(error?.message ?? error).slice(0, 160)}`) + '\n').catch(() => {});
      throw error;
    }
  };

  // ---- helpers ---------------------------------------------------------------------------------------------------------
  const norm = value => (value ?? '').replace(/\s+/gu, ' ').replaceAll('\u00a0', ' ').trim();
  /**
   * A real mouse click on the visible control with exactly this text (or a CSS selector) inside `scope`. The page is brought to the front
   * first and the layout is given two frames to settle after the scroll, so the point that is measured is the point that is clicked (a
   * sticky bar moves while the page scrolls under it).
   */
  const click = async (what, scope = 'main') => {
    await tab.call('Page.bringToFront');
    const point = await page(`const [what, scope] = args;
      const pool = [...document.querySelectorAll(scope + ' button, ' + scope + ' a, ' + scope + ' label, ' + scope + ' summary')];
      const node = what.startsWith('css:') ? document.querySelector(scope + ' ' + what.slice(4))
        : pool.find(item => item.textContent.replace(/\\s+/g, ' ').replaceAll('\\u00a0', ' ').trim() === what);
      if (!(node instanceof HTMLElement)) return null;
      if (node.getAttribute('aria-disabled') === 'true') return 'disabled';
      node.scrollIntoView({ block: 'center', behavior: 'instant' });
      await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
      const rect = node.getBoundingClientRect();
      return { x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 };`, what, scope);
    need(point !== null && point !== 'disabled', `«${what}» is ${point === null ? 'absent' : 'disabled'} in ${scope}`);
    await tab.call('Input.dispatchMouseEvent', { type: 'mouseMoved', x: point.x, y: point.y });
    await tab.call('Input.dispatchMouseEvent', { type: 'mousePressed', x: point.x, y: point.y, button: 'left', clickCount: 1 });
    await tab.call('Input.dispatchMouseEvent', { type: 'mouseReleased', x: point.x, y: point.y, button: 'left', clickCount: 1 });
    await settle();
  };
  const bodyText = async () => norm(await page(`return document.body.innerText;`));
  const layout = () => page(`const doc = document.documentElement;
    const main = document.querySelector('main') ?? document.body;
    const visible = element => { const rect = element.getBoundingClientRect(); const style = getComputedStyle(element);
      return rect.width > 0 && rect.height > 0 && style.visibility !== 'hidden' && style.display !== 'none'; };
    const small = [...main.querySelectorAll('.button, .generate-cta, .chip, .segment, .check, input[type=range]')].filter(visible)
      .map(element => { const rect = element.getBoundingClientRect(); return { cls: String(element.className).slice(0, 24), text: (element.textContent ?? '').trim().slice(0, 24), w: Math.round(rect.width), h: Math.round(rect.height) }; })
      .filter(entry => entry.h < 43.5);
    const wide = [...main.querySelectorAll('*')].filter(element => visible(element) && element.getBoundingClientRect().right > doc.clientWidth + 1)
      .map(element => element.tagName.toLowerCase() + '.' + String(element.className).split(' ')[0]).slice(0, 4);
    return { overflow: doc.scrollWidth > doc.clientWidth, scrollWidth: doc.scrollWidth, clientWidth: doc.clientWidth, small, wide };`);
  /** Screenshots at 1440 and 390 px, with the overflow and the 44 px target checks. */
  const shots = async (prefix, label, scrollTo = null) => {
    for (const [tag, w, hgt, mobile] of [['1440', 1440, 900, false], ['390', 390, 844, true]]) {
      await metrics(w, hgt, 1, mobile); await settle();
      if (scrollTo !== null) { await page(`document.querySelector(args[0])?.scrollIntoView({ block: 'start', behavior: 'instant' }); return true;`, scrollTo); await settle(); }
      const facts = await layout();
      need(!facts.overflow, `${label} overflows horizontally at ${w} px (${facts.scrollWidth} > ${facts.clientWidth}: ${facts.wide.join(' ')})`);
      need(facts.small.length === 0, `${label} has controls below 44 px at ${w} px: ${JSON.stringify(facts.small)}`);
      const name = `planner-${prefix}-${tag}.png`;
      await saveScreenshot(name, tab);
      out.screenshots.push(name);
    }
    await desktop(); await settle();
  };
  const focusOf = () => page(`const active = document.activeElement;
    if (!active || active === document.body) return { body: true };
    return { tag: active.tagName.toLowerCase(), cls: String(active.className), label: (active.getAttribute('aria-label') ?? active.textContent ?? '').replace(/\\s+/g, ' ').trim().slice(0, 60) };`);

  // ---- the account's plan state: the plan is debited apart and counted once --------------------------------------------
  const usage = async () => {
    const result = await api('GET', '/api/usage');
    need(result.status === 200, `GET /api/usage answered ${result.status}`);
    return { used: result.body.credits.used, smartPlans: result.body.caps?.smartPlan?.used ?? 0, bar: result.body.credits.total };
  };

  // ---- fixtures --------------------------------------------------------------------------------------------------------
  const makeDeck = async (title, materials) => {
    const created = await api('POST', '/api/decks', { commandId: crypto.randomUUID(), metadata: { title, description: '' } });
    need(created.status === 201, `POST deck answered ${created.status}`);
    const id = created.body.deck?.deckId ?? created.body.deckId;
    need(id, 'the created deck has no id');
    const deck = { deckId: id, materials: [] };
    for (const material of materials) {
      const current = await api('GET', `/api/decks/${id}`);
      need(current.status === 200, `GET deck answered ${current.status}`);
      const made = await api('POST', `/api/decks/${id}/items`, { commandId: crypto.randomUUID(), expectedDeckRevisionId: current.body.revisionId,
        document: nativeDocument(material.lines) }, { 'If-Match': current.etag });
      need(made.status === 201, `POST item answered ${made.status} ${JSON.stringify(made.body?.code ?? made.body?.detail ?? null)}`);
      const memberKey = made.body.changes?.[0]?.memberKey ?? made.body.memberKey;
      need(memberKey, 'the created material has no member key');
      deck.materials.push({ ...material, memberKey });
    }
    return deck;
  };
  const sessionIdFrom = path => path.match(/\/workshop\/([0-9a-f-]{36})$/u)?.[1] ?? null;
  const getSession = async (deck, id) => {
    const result = await api('GET', `/api/decks/${deck.deckId}/generation-sessions/${id}`);
    need(result.status === 200, `GET generation session answered ${result.status}`);
    return result.body;
  };
  const getArtifact = async (deck, sessionId, artifactId) => {
    const result = await api('GET', `/api/decks/${deck.deckId}/generation-sessions/${sessionId}/artifacts/${artifactId}`);
    need(result.status === 200, `GET artifact answered ${result.status}`);
    return result.body;
  };
  const dropSession = async (deck, id) => {
    const gone = await api('DELETE', `/api/decks/${deck.deckId}/generation-sessions/${id}`);
    need([204, 404].includes(gone.status), `deleting a session answered ${gone.status}`);
  };
  const rows = () => page(`return [...document.querySelectorAll('app-workshop-plan ol.rows > li')].map(li => { const range = li.querySelector('input[type=range]');
    return { id: li.dataset.row, title: li.querySelector('.row-title')?.textContent.trim() ?? li.querySelector('.title-input')?.value ?? null,
      why: li.querySelector('.why')?.textContent.trim() ?? null,
      chips: Object.fromEntries([...li.querySelectorAll('.chip')].map(chip => [chip.textContent.trim(), chip.querySelector('input').checked])),
      count: range ? Number(range.value) : null, text: range?.getAttribute('aria-valuetext')?.replaceAll('\\u00a0', ' ') ?? null,
      min: range?.min ?? null, max: range?.max ?? null, rangeLabel: range?.getAttribute('aria-label') ?? null,
      effort: [...li.querySelectorAll('input[type=radio]')].find(radio => radio.checked)?.closest('label')?.textContent.trim() ?? null,
      removeLabel: li.querySelector('.row-remove')?.getAttribute('aria-label') ?? null }; });`);
  /** What the page knows about a launch that did not happen: the approval requests it made and what it says. */
  const launchFacts = () => page(`return { approvals: performance.getEntriesByType('resource').filter(entry => /plan-approval/u.test(entry.name)).map(entry => entry.responseStatus),
    alert: document.querySelector('app-workshop-plan [role=alert]')?.textContent.replace(/\\s+/g, ' ').trim() ?? null,
    button: [...document.querySelectorAll('app-workshop-plan .plan-actions button')].map(button => button.textContent.trim() + ':' + (button.getAttribute('aria-disabled') ?? '')),
    total: document.querySelector('app-workshop-plan .total')?.textContent.replace(/\\s+/g, ' ').trim() ?? null };`);
  const untilLaunched = async (predicate, label, timeoutMs) => {
    try { await until(predicate, label, timeoutMs); } catch (error) {
      if (error instanceof SafeFailure) throw new SafeFailure(`${label} (${JSON.stringify(await launchFacts())})`);
      throw error;
    }
  };
  const totals = () => page(`return document.querySelector('app-workshop-plan .total')?.textContent.replace(/\\s+/g, ' ').replaceAll('\\u00a0', ' ').trim() ?? null;`);

  await desktop();
  await awaitCapability();
  for (const session of await activeSessions()) {
    const gone = await api('DELETE', baseSessionPath(session.sessionId));
    need([204, 404].includes(gone.status), `deleting an earlier session answered ${gone.status}`);
  }

  let deck = null;
  await step('fixture', async () => {
    deck = await makeDeck('Планировщик: проверка', MATERIALS);
    return { materials: deck.materials.length };
  });
  const [scan, btree, parts] = deck.materials;

  // =====================================================================================================================
  // 1. The builder: «Ещё настройки» → «Сначала показать план», with the plan's own cost line from the real estimate
  // =====================================================================================================================
  const before = {};
  await step('builder_option', async () => {
    await navigate(`/decks/${deck.deckId}/exercises/generate?members=${deck.materials.map(material => material.memberKey).join(',')}`, tab);
    await until(() => has('app-exercise-builder-page form'), 'the exercise builder did not open', 25_000);
    const facts = () => page(`const root = document.querySelector('app-exercise-builder-page');
      const details = root.querySelector('details.more');
      const box = details?.querySelector('input[type=checkbox]');
      return { summary: details?.querySelector('summary')?.textContent.trim(), open: details?.open, boxLabel: box?.closest('label')?.textContent.trim() ?? null,
        checked: box?.checked ?? null, described: box?.getAttribute('aria-describedby') ?? null,
        hint: box ? document.getElementById(box.getAttribute('aria-describedby').split(' ')[0])?.textContent.trim() : null,
        cost: details?.querySelector('.cost')?.textContent.replaceAll('\\u00a0', ' ').trim() ?? null,
        estimate: root.querySelector('.estimate')?.textContent.replaceAll('\\u00a0', ' ').trim() ?? '',
        cta: root.querySelector('.generate-cta')?.textContent.trim() };`);
    const first = await facts();
    need(first.summary === 'Ещё настройки' && first.open === false, `the option is not behind a closed «Ещё настройки» (${JSON.stringify(first)})`);
    need(first.boxLabel === 'Сначала показать план' && first.checked === false, `the plan option is ${JSON.stringify([first.boxLabel, first.checked])}`);
    need(/План стоит отдельно/u.test(first.hint ?? ''), `the plan option says «${first.hint}»`);
    need(first.cta === 'Создать упражнения', `the button says «${first.cta}» before the plan is asked for`);
    // The Stub makes SELF_CHECK, CHOICE and CLOZE: the plan may only use what is chosen.
    for (const label of Object.values(MECHANIC_LABELS)) await click(label, 'app-exercise-builder-page .chips');
    await click('Точно', 'app-exercise-builder-page app-segmented-choice');
    await click('Ещё настройки', 'app-exercise-builder-page');
    await click('Сначала показать план', 'app-exercise-builder-page details');
    const checked = await facts();
    need(checked.checked === true && checked.cta === 'Составить план', `after checking the plan the box is ${checked.checked} and the button says «${checked.cta}»`);
    await until(async () => /^План: /u.test((await facts()).cost ?? ''), 'the plan\'s cost line did not appear', 15_000);
    const line = (await facts()).cost;
    const wire = await api('POST', `/api/decks/${deck.deckId}/generation-estimates`, { spec: { kind: 'EXERCISES',
      targets: await page(`const response = await fetch(args[0] + '/api/decks/' + args[1] + '/items?limit=20&include=exerciseCount', { credentials: 'omit', headers: { Authorization: args[2] } });
        const body = await response.json(); return body.items.map(item => ({ memberKey: item.memberKey, itemRevisionId: item.itemRevisionId }));`, config.frontend, deck.deckId, 'Bearer ' + ctx.bearer),
      settings: { mechanics: ['SELF_CHECK', 'CHOICE', 'CLOZE'], priority: 'UNCOVERED_FIRST', quantity: { mode: 'EXACT', perTarget: 3 }, planFirst: true, budgetPercent: null } } });
    need(wire.status === 200, `POST generation-estimates with planFirst answered ${wire.status}`);
    const planLine = wire.body.breakdown[0];
    need(planLine.operation === 'SMART_PLAN_FLASH' && planLine.credits === 20, `the plan line of the estimate is ${JSON.stringify(planLine)}`);
    // The line says the plan's share of the estimate, not of the whole request.
    const planShare = Math.round(wire.body.percentOfPeriodAllowance.p95 * planLine.credits / wire.body.credits.p95);
    const expected = planShare < 1 ? 'План: менее 1 % лимита' : `План: ≈ ${planShare} % лимита`;
    need(line === expected, `the cost line «${line}» is not the plan's share of the estimate («${expected}»)`);
    need(wire.body.credits.p95 > planLine.credits, 'the estimate does not add the batch to the plan');
    await shots('builder', 'the exercise builder with the plan option');
    return { cost: line, planCredits: planLine.credits };
  });

  // =====================================================================================================================
  // 2. Составить план: PLANNING (nothing is made), PLAN_READY with the plan as an editable list; the plan is debited apart
  // =====================================================================================================================
  let sessionId = null;
  let plan = null;
  await step('plan_ready', async () => {
    Object.assign(before, await usage());
    await click('Составить план', 'app-exercise-builder-page .action-row');
    await until(async () => sessionIdFrom(await location()) !== null, 'the builder did not open the Workshop', 30_000);
    sessionId = sessionIdFrom(await location());
    // The Stub answers at once: PLANNING may be over before the page asks. When it is on screen it is the quiet wait with its own «Отменить».
    let sawPlanning = false;
    await until(async () => {
      const text = await bodyText();
      if (text.includes('Мнема составляет план…')) sawPlanning = true;
      return await has('app-workshop-plan ol.rows');
    }, 'the Workshop did not show the plan', 60_000);
    const session = await getSession(deck, sessionId);
    need(session.kind === 'EXERCISES' && session.state === 'PLAN_READY', `the session is ${session.kind} ${session.state}, not a PLAN_READY exercise session`);
    need(session.artifacts.length === 0, `the PLAN_READY session already has ${session.artifacts.length} artifacts`);
    plan = session.plan;
    need(plan && plan.kind === 'EXERCISES' && plan.approved === false && plan.items.length === 3, `the plan is ${JSON.stringify(plan && { kind: plan.kind, approved: plan.approved, items: plan.items?.length })}`);
    need(plan.items.every(item => item.mechanics.length >= 1 && item.mechanics.length <= 2 && item.mechanics.every(mechanic => mechanic in MECHANIC_LABELS)),
      `a plan row uses mechanics outside the chosen three: ${JSON.stringify(plan.items.map(item => item.mechanics))}`);
    need(plan.items.every(item => item.count === 3), `a plan row does not follow «Точно 3»: ${JSON.stringify(plan.items.map(item => item.count))}`);
    need(plan.cost.planCredits === 20 && plan.cost.holdActive === true && plan.cost.batchCredits <= plan.cost.holdCredits, `the plan cost is ${JSON.stringify(plan.cost)}`);
    need(session.usage.spentCredits === 20, `the plan was debited ${session.usage.spentCredits} credits, not its own 20`);
    // The plan is debited apart, and one smart plan is counted.
    const after = await usage();
    need(after.used - before.used === 20, `the account's used credits moved by ${after.used - before.used}, not by the plan's 20`);
    need(after.smartPlans - before.smartPlans === 1, `the smart plans moved by ${after.smartPlans - before.smartPlans}, not by one`);
    const view = await page(`const root = document.querySelector('section.workshop');
      return { heading: root.querySelector('h1')?.textContent.trim(), planTitle: root.querySelector('app-workshop-plan h2')?.textContent.trim(),
        lede: root.querySelector('.lede')?.textContent.replaceAll('\\u00a0', ' ').trim(), status: root.querySelector('.exercise-summary')?.textContent.trim(),
        regions: root.querySelectorAll('[role=status]').length, stop: Boolean([...root.querySelectorAll('.workshop-heading button')].find(button => button.textContent.trim() === 'Стоп')),
        review: Boolean(root.querySelector('app-exercise-batch-review, app-batch-pager')), paid: root.querySelector('.paid')?.textContent.replaceAll('\\u00a0', ' ').trim(),
        list: root.querySelector('ol.rows')?.getAttribute('aria-label'), launch: [...root.querySelectorAll('.plan-actions button')].map(button => button.textContent.trim()),
        ids: (() => { const all = [...document.querySelectorAll('[id]')].map(node => node.id); return all.length - new Set(all).size; })() };`);
    need(view.heading === 'Мастерская упражнений' && view.planTitle === 'План упражнений', `the Workshop heading is «${view.heading}» / «${view.planTitle}»`);
    need(view.lede === 'Для 3 материалов: сначала план, потом упражнения.', `the lede is «${view.lede}»`);
    await until(async () => (await page(`return document.querySelector('.exercise-summary')?.textContent.trim();`)) === 'План готов: проверьте его и запустите', 'the live region does not say that the plan is ready', 20_000);
    need(!view.stop && !view.review, `the Workshop shows the review or a «Стоп» before the launch (${JSON.stringify(view)})`);
    need(view.regions === 2, `the page has ${view.regions} role=status regions (the summary and the live total are expected)`);
    need(view.paid === 'Составление плана: ' + share(20, plan.cost.barCredits) + ' — уже списано. Отдельно от упражнений.', `the paid line is «${view.paid}»`);
    need(view.launch.join('|') === 'Запустить по плану|Отменить', `the plan actions are ${JSON.stringify(view.launch)}`);
    need(view.ids === 0, `${view.ids} duplicate ids on the plan page`);
    const list = await rows();
    need(list.length === 3 && list.every(row => row.min === '1' && row.max === '10' && row.count === 3 && row.text === '3 упражнения'), `the plan rows are ${JSON.stringify(list.map(row => [row.title, row.count, row.text, row.min, row.max]))}`);
    need(list.every(row => row.why && row.why.length > 0 && row.removeLabel === 'Убрать из плана: ' + row.title && row.rangeLabel === 'Количество упражнений: ' + row.title), 'a row lacks its reason, its named «Убрать» or its named count');
    need(list.every(row => !('Авто' in row.chips) && Object.values(row.chips).some(Boolean)), `a row has «Авто» or no mechanic: ${JSON.stringify(list.map(row => row.chips))}`);
    need(list.every(row => Object.keys(row.chips).length === 3), `a row offers ${JSON.stringify(list.map(row => Object.keys(row.chips)))} instead of the three allowed mechanics`);
    const expectedTotal = `Всего 9 упражнений · ${share(Math.ceil(plan.rates.exercisesPerFive * 9 / 5), plan.cost.barCredits)}`;
    need(await totals() === expectedTotal, `the live total is «${await totals()}», not «${expectedTotal}»`);
    await shots('ready', 'the plan of exercises', 'app-workshop-plan');
    out.plan = { items: plan.items.length, sawPlanning, usedMoved: after.used - before.used, smartPlans: after.smartPlans - before.smartPlans };
    return out.plan;
  });

  // =====================================================================================================================
  // 3. Edit: take a row off and back, move a count with the keyboard, drop a mechanic; the total follows every change
  // =====================================================================================================================
  let edited = null;
  await step('edit_plan', async () => {
    const list = await rows();
    // The row to remove is the middle one; the others are edited. The title of a row is the first line of its material.
    const [rowA, rowB, rowC] = list;
    const material = title => deck.materials.find(entry => entry.lines[0] === title);
    need(list.every(row => material(row.title)), `a plan row is named «${list.map(row => row.title).join('», «')}», not after a material`);
    const total = async count => {
      const credits = Math.ceil(plan.rates.exercisesPerFive * count / 5);
      const expected = `Всего ${count} ${exercisesWord(count)} · ${share(credits, plan.cost.barCredits)}`;
      await until(async () => (await totals()) === expected, `the live total is «${await totals()}», not «${expected}»`, 5_000);
    };
    // Remove the middle row with a real click: focus goes to the «Убрать» of the row now at its place.
    await click(`css:li[data-row="${rowB.id}"] .row-remove`, 'app-workshop-plan');
    await until(async () => (await rows()).length === 2, 'the row was not removed');
    await total(6);
    const focus = await focusOf();
    need(focus.cls.includes('row-remove') && focus.label === 'Убрать из плана: ' + rowC.title, `after «Убрать» focus is ${JSON.stringify(focus)}, not the «Убрать» of the next row`);
    const off = await page(`const details = document.querySelector('app-workshop-plan details.off-plan');
      return details ? { summary: details.querySelector('summary').textContent.trim(), items: [...details.querySelectorAll('li')].map(li => li.textContent.replace(/\\s+/g, ' ').replaceAll('\\u00a0', ' ').trim()) } : null;`);
    need(off && off.summary === 'Материалы вне плана (1)' && off.items.length === 1 && off.items[0].includes(rowB.title), `the off-plan list is ${JSON.stringify(off)}`);
    // Take it back: it returns as it was, and out again.
    await click('Материалы вне плана (1)', 'app-workshop-plan');
    await click(`css:details.off-plan button`, 'app-workshop-plan');
    await until(async () => (await rows()).length === 3, 'the row was not taken back');
    const back = (await rows()).find(row => row.title === rowB.title);
    need(back && back.count === 3 && JSON.stringify(back.chips) === JSON.stringify(rowB.chips), `the row came back as ${JSON.stringify(back)}, not as it was`);
    await total(9);
    await click(`css:li[data-row="${back.id}"] .row-remove`, 'app-workshop-plan');
    await until(async () => (await rows()).length === 2, 'the row was not removed again');
    await total(6);
    // A count moves with the keyboard: two ArrowRight from 3 make 5, and the spoken value follows.
    need(await page(`const range = document.querySelector('app-workshop-plan li[data-row="' + args[0] + '"] input[type=range]'); range.scrollIntoView({ block: 'center' }); range.focus(); return document.activeElement === range;`, rowA.id), 'the count took no focus');
    await press('ArrowRight'); await press('ArrowRight');
    await until(async () => (await rows()).find(row => row.id === rowA.id)?.text === '5 упражнений', 'ArrowRight x2 did not make «5 упражнений»', 5_000);
    await total(8);
    // A mechanic is unchecked in the last row; the last one that is left cannot be unchecked.
    const chosen = Object.entries(rowC.chips).filter(([, on]) => on).map(([label]) => label);
    need(chosen.length === 2, `the last row has ${chosen.length} mechanics, not two`);
    await click(chosen[0], `app-workshop-plan li[data-row="${rowC.id}"]`);
    let last = (await rows()).find(row => row.id === rowC.id);
    need(Object.values(last.chips).filter(Boolean).length === 1 && last.chips[chosen[1]] === true, `after unchecking «${chosen[0]}» the row has ${JSON.stringify(last.chips)}`);
    await click(chosen[1], `app-workshop-plan li[data-row="${rowC.id}"]`);
    last = (await rows()).find(row => row.id === rowC.id);
    need(last.chips[chosen[1]] === true, 'the last mechanic of a row could be unchecked');
    need(/Нужен хотя бы один тип/u.test(norm(await page(`return document.querySelector('app-workshop-plan li[data-row="' + args[0] + '"] .hint')?.textContent ?? '';`, rowC.id))), 'the row does not say why the last mechanic stays');
    // The server's plan is untouched by the editing: nothing is sent before the launch.
    const untouched = (await getSession(deck, sessionId)).plan;
    need(JSON.stringify(untouched.items) === JSON.stringify(plan.items) && untouched.approved === false, 'editing the plan changed the plan on the server');
    edited = { kept: [rowA, rowC].map(row => material(row.title).memberKey), counts: { [material(rowA.title).memberKey]: 5, [material(rowC.title).memberKey]: 3 },
      mechanics: { [material(rowA.title).memberKey]: Object.entries(rowA.chips).filter(([, on]) => on).map(([label]) => label), [material(rowC.title).memberKey]: [chosen[1]] },
      removed: material(rowB.title).memberKey };
    await shots('edited', 'the edited plan', 'app-workshop-plan');
    return { total: await totals(), kept: edited.kept.length, focus: 'next «Убрать»' };
  });

  // =====================================================================================================================
  // 4. «Запустить по плану»: exactly the edited artifacts, with exactly those mechanics; the plan debit stays apart
  // =====================================================================================================================
  await step('launch', async () => {
    await page(`performance.clearResourceTimings(); return true;`);
    await click('Запустить по плану', 'app-workshop-plan');
    await untilLaunched(async () => (await getSession(deck, sessionId)).state !== 'PLAN_READY', 'the launch did not leave PLAN_READY', 30_000);
    await until(() => has('app-exercise-batch-review'), 'the Workshop did not switch to the exercise review after the launch', 30_000);
    need(!(await has('app-workshop-plan')), 'the plan is still on the page after the launch');
    const focus = await focusOf();
    need(focus.tag === 'h1', `after the launch focus is ${JSON.stringify(focus)}, not the Workshop title`);
    await until(async () => { const session = await getSession(deck, sessionId); return session.artifacts.length === 8 && session.artifacts.every(artifact => ['PROPOSED', 'FAILED'].includes(artifact.state)); },
      'the planned artifacts did not settle', 120_000);
    const session = await getSession(deck, sessionId);
    need(session.plan.approved === true && session.plan.items.length === 2, `the stored plan is ${JSON.stringify({ approved: session.plan.approved, items: session.plan.items.length })}`);
    need(session.plan.items.map(item => item.memberKey).join() === edited.kept.join(), 'the stored plan does not keep the order of the rows');
    need(session.plan.items.every(item => item.count === edited.counts[item.memberKey]), 'the stored plan does not carry the edited counts');
    need(session.artifacts.length === 8, `the launch made ${session.artifacts.length} artifacts, not the 8 of the plan`);
    const perMember = {};
    const mechanics = {};
    for (const artifact of session.artifacts.filter(item => item.state === 'PROPOSED')) {
      const detail = await getArtifact(deck, sessionId, artifact.artifactId);
      const member = detail.revision.payload.command.exercise.subject.memberKey;
      perMember[member] = (perMember[member] ?? 0) + 1;
      (mechanics[member] ??= new Set()).add(detail.display.mechanic);
    }
    need(!(edited.removed in perMember), 'an exercise was written for the material that was taken off the plan');
    need(Object.keys(perMember).length === 2 && edited.kept.every(member => perMember[member] === edited.counts[member]), `the exercises per material are ${JSON.stringify(perMember)}, not ${JSON.stringify(edited.counts)}`);
    const labelOf = mechanic => MECHANIC_LABELS[mechanic];
    for (const member of edited.kept) {
      const used = [...mechanics[member]].map(labelOf);
      need(used.every(label => edited.mechanics[member].includes(label)), `exercises of a material use ${JSON.stringify(used)}, outside the plan's ${JSON.stringify(edited.mechanics[member])}`);
    }
    // The plan cannot be launched twice: a second approval answers the state conflict, and nothing more is made.
    const again = await api('POST', `/api/decks/${deck.deckId}/generation-sessions/${sessionId}/plan-approval`, { commandId: crypto.randomUUID(), expectedSessionVersion: (await getSession(deck, sessionId)).rowVersion,
      plan: { items: [{ memberKey: edited.kept[0], mechanics: ['CHOICE'], count: 1 }] } });
    need(again.status === 409 && again.body?.code === 'GENERATION_STATE_CONFLICT', `a second launch answered ${again.status} ${again.body?.code}`);
    need((await getSession(deck, sessionId)).artifacts.length === 8, 'a second launch changed the artifacts');
    // The plan stays debited apart from the batch: the plan's own 20 plus what the batch cost, never less.
    need(session.usage.spentCredits >= 20, `the session spent ${session.usage.spentCredits}`);
    const cards = await page(`return document.querySelectorAll('app-exercise-batch-review li.entry').length;`);
    need(cards === 8, `the review shows ${cards} cards, not 8`);
    await shots('launched', 'the Workshop after the launch');
    await dropSession(deck, sessionId);
    return { artifacts: 8, perMember: Object.values(perMember), second: again.status, spent: session.usage.spentCredits };
  });

  // =====================================================================================================================
  // 5. A plan that cannot be made: the session ends with its reason, nothing is charged, no smart plan is counted
  // =====================================================================================================================
  await step('plan_failed', async () => {
    const poisoned = await makeDeck('Планировщик: план не получится', [POISONED]);
    const start = await usage();
    await navigate(`/decks/${poisoned.deckId}/exercises/generate?members=${poisoned.materials[0].memberKey}`, tab);
    await until(() => has('app-exercise-builder-page form'), 'the builder did not open for the poisoned material', 25_000);
    await click('Ещё настройки', 'app-exercise-builder-page');
    await click('Сначала показать план', 'app-exercise-builder-page details');
    await click('Составить план', 'app-exercise-builder-page .action-row');
    await until(async () => sessionIdFrom(await location()) !== null, 'the builder did not open the Workshop', 30_000);
    const failedId = sessionIdFrom(await location());
    await until(async () => (await getSession(poisoned, failedId)).state === 'CANCELLED', 'the plan that cannot be made did not end the session', 120_000);
    const session = await getSession(poisoned, failedId);
    need(session.endReason === 'PLAN_FAILED' && session.plan === null, `the session ended with ${session.endReason} and plan ${JSON.stringify(session.plan)}`);
    need(session.artifacts.length === 0 && session.usage.spentCredits === 0 && session.usage.reservedCredits === 0, `a failed plan left ${JSON.stringify(session.usage)} and ${session.artifacts.length} artifacts`);
    await until(async () => (await bodyText()).includes('Мнеме не удалось составить план. Ничего не создано, лимит не списан.'), 'the Workshop does not say that the plan failed', 30_000);
    need(!(await has('app-workshop-plan, app-exercise-batch-review')), 'a plan or an empty review is shown for a plan that failed');
    // The live region changes at most once in two seconds, so it may still be saying the earlier sentence for a moment.
    await until(async () => (await page(`return document.querySelector('.exercise-summary')?.textContent.trim();`)) === 'Не удалось составить план', 'the live region does not say that the plan failed', 10_000);
    const end = await usage();
    need(end.used === start.used && end.smartPlans === start.smartPlans, `a failed plan moved the account (${JSON.stringify(start)} -> ${JSON.stringify(end)})`);
    await shots('failed', 'the Workshop of a failed plan');
    await dropSession(poisoned, failedId);
    return { endReason: session.endReason, charged: 0 };
  });

  // =====================================================================================================================
  // 6. The Materials composer with the plan: a topic retyped, an effort changed, exactly those materials written
  // =====================================================================================================================
  await step('materials_plan', async () => {
    await awaitCapability();
    await navigate(`/decks/${deck.deckId}/materials/new`, tab);
    await until(() => has('app-generation-composer textarea'), 'the composer did not open', 25_000);
    await page(`const textarea = document.querySelector('app-generation-composer textarea'); textarea.focus(); return document.activeElement === textarea;`);
    await tab.call('Input.insertText', { text: 'Объясни, как планировщик PostgreSQL выбирает способ чтения таблицы' });
    await click('Ещё настройки', 'app-generation-composer');
    await click('Сначала показать план', 'app-generation-composer details');
    const composer = () => page(`const root = document.querySelector('app-generation-composer');
      return { cta: root.querySelector('.generate-cta')?.textContent.trim(), cost: root.querySelector('.cost')?.textContent.replaceAll('\\u00a0', ' ').trim() ?? null,
        estimate: root.querySelector('.estimate')?.textContent.replaceAll('\\u00a0', ' ').trim() ?? '' };`);
    await until(async () => /^План: /u.test((await composer()).cost ?? '') && /лимита$/u.test((await composer()).estimate), 'the composer shows no cost line for the plan', 15_000);
    need((await composer()).cta === 'Составить план', `the composer button says «${(await composer()).cta}»`);
    await shots('composer', 'the composer with the plan option');
    const start = await usage();
    await click('Составить план', 'app-generation-composer .action-row');
    await until(async () => sessionIdFrom(await location()) !== null, 'the composer did not open the Workshop', 30_000);
    const materialsId = sessionIdFrom(await location());
    await until(() => has('app-workshop-plan ol.rows'), 'the Workshop did not show the plan of materials', 60_000);
    const session = await getSession(deck, materialsId);
    need(session.kind === 'MATERIALS' && session.state === 'PLAN_READY' && session.artifacts.length === 0, `the session is ${session.kind} ${session.state} with ${session.artifacts.length} artifacts`);
    need(session.plan.kind === 'MATERIALS' && session.plan.items.length >= 1, 'the plan of materials is empty');
    const after = await usage();
    need(after.used - start.used === 20 && after.smartPlans - start.smartPlans === 1, `the plan of materials moved the account by ${after.used - start.used} credits and ${after.smartPlans - start.smartPlans} plans`);
    const list = await rows();
    need(list.length === session.plan.items.length && list.every(row => row.title && row.effort), `the rows are ${JSON.stringify(list.map(row => [row.title, row.effort]))}`);
    need((await page(`return document.querySelector('app-workshop-plan h2')?.textContent.trim();`)) === 'План материалов', 'the plan of materials has the wrong title');
    need(/Всего \d+ материал/u.test(await totals()), `the live total of materials is «${await totals()}»`);
    await shots('materials-ready', 'the plan of materials', 'app-workshop-plan');
    // Retype the topic of the first row with the real keyboard, and make it «Подробно».
    need(await page(`const input = document.querySelector('app-workshop-plan li .title-input'); input.scrollIntoView({ block: 'center' }); input.focus(); input.select(); return document.activeElement === input;`), 'the topic took no focus');
    await tab.call('Input.insertText', { text: 'Как планировщик выбирает Seq Scan' });
    await click('Подробно', 'app-workshop-plan li:first-child');
    const changed = (await rows())[0];
    need(changed.title === 'Как планировщик выбирает Seq Scan' && changed.effort === 'Подробно', `the first row is ${JSON.stringify([changed.title, changed.effort])}`);
    await page(`performance.clearResourceTimings(); return true;`);
    await click('Запустить по плану', 'app-workshop-plan');
    await untilLaunched(async () => (await getSession(deck, materialsId)).state !== 'PLAN_READY', 'the launch of the materials did not leave PLAN_READY', 30_000);
    const running = await getSession(deck, materialsId);
    need(running.artifacts.length === list.length, `the launch made ${running.artifacts.length} materials, not the ${list.length} of the plan`);
    need(running.plan.approved === true && running.plan.items[0].title === 'Как планировщик выбирает Seq Scan' && running.plan.items[0].effort === 'DETAILED',
      `the stored plan is ${JSON.stringify(running.plan.items[0])}`);
    await until(async () => (await getSession(deck, materialsId)).artifacts.every(artifact => ['PROPOSED', 'FAILED'].includes(artifact.state)), 'the planned materials did not settle', 120_000);
    await until(() => has('app-proposal-view'), 'the Workshop did not show the planned material', 30_000);
    await dropSession(deck, materialsId);
    return { rows: list.length, notes: session.plan.notes.map(note => note.code), usedMoved: after.used - start.used };
  });

  out.durationMs = Date.now() - startedAt;
  return out;
}
