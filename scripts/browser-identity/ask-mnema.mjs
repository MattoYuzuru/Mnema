// Real-browser check of «Попросить Мнему…» (#294, AI-16) against the real Learning API with the Stub text provider (`--generation`;
// never a real provider, no key). Runs at the end of the Workshop scenarios on the signed-in account's tab, in a deck of its own.
// Node 24 built-ins only.
//
// The user's whole path is the real Angular UI on the real HTTP surface: the collapsed composer in the material profile, a real click
// that opens it, real typing and Enter, the editable chips (a mechanic dropped, the number kept), the estimate line and «Запустить», the
// Workshop of the exercises; then the revision of the material (result card with the word diff, «Вернуть», «Ещё раз», «Оставить»), and in
// the exercise editor the voice change («замени аудио на мужской голос»: the chip, a new asset made by the Stub speech, «Оставить»; and an audio without a transcript offers no voice). Only the
// fixture (the deck, the material, the exercise with an audio prompt) and the checks of what the server holds are made through the
// authenticated API. The Stub keyword mapping is the one of `StubTextAdapter` («все типы» -> AUTO, «по 3» -> 3, «проще» -> REVISE_ITEM,
// «голос» -> REVISE_EXERCISE, «лимит» -> a hostile answer the server clamps).
// A broken step is a finding for the product, never something to work around: it throws, a failure screenshot is written and the run fails
// with the step name.
import { writeFile } from 'node:fs/promises';
import { join } from 'node:path';

const LINES = [
  'Планировщик выбирает Seq Scan или Index Scan.',
  'Seq Scan читает всю таблицу подряд, а Index Scan идёт по индексу к нужным строкам.',
  'Для небольшой таблицы Seq Scan часто быстрее, потому что индекс не нужен.'
];

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

const paragraph = (text, index) => ({ id: `00000000-0000-4000-8000-${String(index * 2 + 1).padStart(12, '0')}`, type: 'paragraph', version: 1, attrs: {},
  content: [{ id: `00000000-0000-4000-8000-${String(index * 2 + 2).padStart(12, '0')}`, type: 'text', version: 1, attrs: { text, marks: [] }, content: [] }] });
const nativeDocument = lines => ({ formatVersion: 1, root: { id: '00000000-0000-4000-8000-000000000000', type: 'doc', version: 1, attrs: {},
  content: lines.map(paragraph) } });
const plainText = document => document.root.content.map(function collect(node) { return node.attrs?.text ?? (node.content ?? []).map(collect).join(''); }).join('\n');

export async function runWorkshopAsk(ctx, h) {
  const { tab, config, SafeFailure, until, navigate, saveScreenshot, setStep } = ctx;
  const { api, page, press, has, need, settle, metrics, desktop, awaitCapability, activeSessions, sessionPath: baseSessionPath, location } = h;
  const out = { stub: true, stages: {}, screenshots: [] };
  const startedAt = Date.now();
  const audioAssetId = ctx.audioAssetId ?? null;

  const failureShot = async name => { try { await saveScreenshot(`failure-ask-${name}.png`, tab); } catch { /* the original failure is the verdict */ } };
  const step = async (name, body) => {
    setStep(`ask_${name}`);
    try { const result = await body(); out.stages[name] = result ?? true; return result; } catch (error) {
      await failureShot(name);
      await writeFile(join(config.output, `failure-ask-${name}.txt`),
        (error instanceof SafeFailure ? error.message : `driver: ${String(error?.message ?? error).slice(0, 160)}`) + '\n').catch(() => {});
      throw error;
    }
  };

  // ---- helpers ---------------------------------------------------------------------------------------------------------
  const clean = value => (value ?? '').replace(/\s+/gu, ' ').replaceAll(' ', ' ').trim();
  const bodyText = () => page(`return document.body.innerText.replace(/\\s+/g, ' ').replaceAll('\\u00a0', ' ');`);
  /** A real mouse click on the visible control with exactly this text (or `css:` selector) inside `scope`. */
  const click = async (what, scope = 'main') => {
    const point = await page(`const [what, scope] = args;
      const pool = [...document.querySelectorAll(scope + ' button, ' + scope + ' a, ' + scope + ' label, ' + scope + ' summary')];
      const node = what.startsWith('css:') ? document.querySelector(scope + ' ' + what.slice(4))
        : pool.find(item => item.textContent.replace(/\\s+/g, ' ').replaceAll('\\u00a0', ' ').trim() === what);
      if (!(node instanceof HTMLElement)) return null;
      if (node.getAttribute('aria-disabled') === 'true') return 'disabled';
      node.scrollIntoView({ block: 'center', behavior: 'instant' });
      const rect = node.getBoundingClientRect();
      return { x: rect.left + Math.min(rect.width / 2, 60), y: rect.top + rect.height / 2 };`, what, scope);
    need(point !== null && point !== 'disabled', `«${what}» is ${point === null ? 'absent' : 'disabled'} in ${scope}`);
    await tab.call('Page.bringToFront');
    await tab.call('Input.dispatchMouseEvent', { type: 'mouseMoved', x: point.x, y: point.y });
    await tab.call('Input.dispatchMouseEvent', { type: 'mousePressed', x: point.x, y: point.y, button: 'left', clickCount: 1 });
    await tab.call('Input.dispatchMouseEvent', { type: 'mouseReleased', x: point.x, y: point.y, button: 'left', clickCount: 1 });
    await settle();
  };
  const typeText = async text => { await tab.call('Page.bringToFront'); await tab.call('Input.insertText', { text }); };
  const focusOf = () => page(`const active = document.activeElement;
    if (!active || active === document.body) return { body: true };
    return { tag: active.tagName.toLowerCase(), id: active.id, cls: String(active.className).slice(0, 30),
      text: (active.getAttribute('aria-label') ?? active.textContent ?? '').replace(/\\s+/g, ' ').trim().slice(0, 40) };`);
  const layout = () => page(`const doc = document.documentElement;
    const main = document.querySelector('main') ?? document.body;
    const visible = element => { const rect = element.getBoundingClientRect(); const style = getComputedStyle(element);
      return rect.width > 0 && rect.height > 0 && style.visibility !== 'hidden' && style.display !== 'none'; };
    const small = [...main.querySelectorAll('.button, .generate-cta, .chip, .segment, .voice-chip, .ask-trigger, .ask-example, .strip-button')].filter(visible)
      .map(element => { const rect = element.getBoundingClientRect(); return { cls: String(element.className).slice(0, 24), text: (element.textContent ?? '').trim().slice(0, 24), w: Math.round(rect.width), h: Math.round(rect.height) }; })
      .filter(entry => entry.h < 43.5);
    const wide = [...main.querySelectorAll('*')].filter(element => visible(element) && element.getBoundingClientRect().right > doc.clientWidth + 1)
      .map(element => element.tagName.toLowerCase() + '.' + String(element.className).split(' ')[0]).slice(0, 4);
    return { overflow: doc.scrollWidth > doc.clientWidth, scrollWidth: doc.scrollWidth, clientWidth: doc.clientWidth, small, wide };`);
  /** Screenshots at 1440 and 390 px of what is on screen now (`scrollTo` brings a part to the top first); overflow and 44 px targets are hard failures. */
  const shots = async (prefix, label, scrollTo = null) => {
    // A page that was just opened may still be cross-fading (View Transitions): the picture is taken of the settled page.
    await sleep(900);
    for (const [tag, w, hgt, mobile] of [['1440', 1440, 900, false], ['390', 390, 844, true]]) {
      await metrics(w, hgt, 1, mobile); await settle();
      if (scrollTo !== null) { await page(`document.querySelector(args[0])?.scrollIntoView({ block: 'start', behavior: 'instant' }); return true;`, scrollTo); await settle(); }
      const facts = await layout();
      need(!facts.overflow, `${label} overflows horizontally at ${w} px (${facts.scrollWidth} > ${facts.clientWidth}: ${facts.wide.join(' ')})`);
      need(facts.small.length === 0, `${label} has controls below 44 px at ${w} px: ${JSON.stringify(facts.small)}`);
      const name = `ask-${prefix}-${tag}.png`;
      await saveScreenshot(name, tab);
      out.screenshots.push(name);
    }
    await desktop(); await settle();
  };
  const credits = async () => {
    const usage = await api('GET', '/api/usage');
    need(usage.status === 200, `GET usage answered ${usage.status}`);
    return usage.body.credits;
  };

  let deck = null;
  const deckPathOf = () => `/decks/${deck.deckId}`;
  const sessionPath = id => `/api/decks/${deck.deckId}/generation-sessions/${id}`;
  const getSession = async id => { const result = await api('GET', sessionPath(id)); need(result.status === 200, `GET session answered ${result.status}`); return result.body; };
  const getArtifact = async (sessionId, artifactId, revisionId = null) => {
    const result = await api('GET', `${sessionPath(sessionId)}/artifacts/${artifactId}${revisionId ? `?revisionId=${revisionId}` : ''}`);
    need(result.status === 200, `GET artifact answered ${result.status}`);
    return result.body;
  };
  const sessionIdFrom = path => path.match(/\/workshop\/([0-9a-f-]{36})$/u)?.[1] ?? null;
  const sessionsOfDeck = async () => {
    const result = await api('GET', `/api/decks/${deck.deckId}/generation-sessions?limit=50`);
    need(result.status === 200, `GET sessions answered ${result.status}`);
    return result.body.items;
  };
  const readItem = async (memberKey, revisionId = null) => {
    const result = await api('GET', `/api/decks/${deck.deckId}/items/${memberKey}${revisionId ? `?revisionId=${revisionId}` : ''}`);
    need(result.status === 200, `GET item answered ${result.status}`);
    return result.body;
  };
  const dropSession = async id => {
    const gone = await api('DELETE', sessionPath(id));
    need([204, 404].includes(gone.status), `deleting a session answered ${gone.status}`);
  };
  /** Opens the composer of the page, types the sentence with real input and sends it with a real Enter. */
  const askMnema = async sentence => {
    await until(() => has('app-ask-mnema .ask-trigger'), 'the composer «Попросить Мнему…» is absent', 25_000);
    const opened = await page(`return document.querySelector('app-ask-mnema .ask-trigger').getAttribute('aria-expanded');`);
    if (opened !== 'true') await click('css:.ask-trigger', 'app-ask-mnema');
    await until(() => has('app-ask-mnema textarea.ask-field'), 'the composer did not open', 8_000);
    // Opening moves focus into the field.
    await until(async () => (await focusOf()).tag === 'textarea', 'focus did not move to the field when the composer opened', 5_000);
    await page(`const field = document.querySelector('app-ask-mnema textarea.ask-field'); field.select(); return true;`);
    await typeText(sentence);
    need(await page(`return document.querySelector('app-ask-mnema textarea.ask-field').value;`) === sentence, 'the typed sentence is not in the field');
    await press('Enter');
    await until(() => has('app-ask-mnema h2.ask-heading'), 'the sentence was not read (no answer after Enter)', 30_000);
  };
  const chipsFacts = () => page(`const root = document.querySelector('app-ask-mnema');
    const text = value => (value ?? '').replace(/\\s+/g, ' ').replaceAll('\\u00a0', ' ').trim();
    const chip = label => [...root.querySelectorAll('.chip')].find(node => text(node.textContent) === label)?.querySelector('input');
    const range = root.querySelector('input[type=range]');
    return { heading: text(root.querySelector('h2')?.textContent), quote: text(root.querySelector('.ask-quote')?.textContent),
      focusOnHeading: document.activeElement === root.querySelector('h2'),
      notes: [...root.querySelectorAll('.note-chip')].map(node => ({ code: node.dataset.note, text: text(node.textContent) })),
      auto: chip('Авто')?.checked ?? null, selfCheck: chip('Вспомнить и сверить')?.checked ?? null, choice: chip('Выбрать ответ')?.checked ?? null, cloze: chip('Заполнить пропуски')?.checked ?? null,
      range: range ? { value: range.value, max: range.max, text: range.getAttribute('aria-valuetext')?.replaceAll('\\u00a0', ' ') } : null,
      instruction: root.querySelector('.ask-instruction textarea')?.value ?? null,
      voices: [...root.querySelectorAll('.ask-voice input')].map(input => ({ value: input.value, checked: input.checked })),
      voiceHint: text(root.querySelector('.ask-voice .ask-help')?.textContent),
      estimate: text(root.querySelector('.estimate')?.textContent), cta: [...root.querySelectorAll('.generate-cta')].map(node => text(node.textContent)),
      ctaDisabled: root.querySelector('.generate-cta')?.getAttribute('aria-disabled') ?? null,
      statusRegions: root.querySelectorAll('[role=status]').length, textareas: root.querySelectorAll('textarea').length,
      ids: (() => { const all = [...document.querySelectorAll('[id]')].map(node => node.id); return all.length - new Set(all).size; })() };`);
  /** Clicks «Запустить» and waits for the Workshop of the new session. Returns its id. */
  const startSession = async () => {
    await click('Запустить', 'app-ask-mnema .ask-start');
    await until(async () => sessionIdFrom(await location()) !== null, 'the composer did not open the Workshop after «Запустить»', 30_000);
    return sessionIdFrom(await location());
  };
  const resultFacts = scope => page(`const root = document.querySelector(args[0]);
    const text = value => (value ?? '').replace(/\\s+/g, ' ').replaceAll('\\u00a0', ' ').trim();
    const card = root?.querySelector('.result-card');
    return { heading: text(document.querySelector('section.workshop h1')?.textContent), lede: text(document.querySelector('section.workshop .lede')?.textContent),
      title: text(card?.querySelector('.result-title')?.textContent), request: text(card?.querySelector('.result-request')?.textContent),
      buttons: [...(card?.querySelectorAll('.result-actions button') ?? [])].map(node => text(node.textContent)),
      chips: [...(card?.querySelectorAll('.result-chip') ?? [])].map(node => text(node.textContent)), stubNote: text(card?.querySelector('[data-stub-note]')?.textContent),
      diff: card?.querySelector('.revise-diff') ? { open: card.querySelector('.revise-diff').open, ins: [...card.querySelectorAll('.revise-diff ins')].map(node => text(node.textContent)),
        del: [...card.querySelectorAll('.revise-diff del')].map(node => text(node.textContent)), hidden: [...card.querySelectorAll('.revise-diff .sr-only')].map(node => text(node.textContent)) } : null,
      heading2: text(root?.querySelector('h2')?.textContent), busy: root?.querySelector('article')?.getAttribute('aria-busy') ?? null,
      strips: document.querySelectorAll('.rewrite-strip').length, preview: Boolean(root?.querySelector('app-exercise-preview-host')),
      history: [...(root?.querySelectorAll('.edit-history .history-ask') ?? [])].map(node => text(node.textContent)), summary: text(document.querySelector('section.workshop .summary')?.textContent),
      regions: document.querySelectorAll('section.workshop [role=status]:not(.document-announcement)').length, success: text(root?.querySelector('.notice.success')?.textContent),
      successLink: root?.querySelector('.notice.success a')?.getAttribute('href') ?? null,
      focusOnHeading: document.activeElement === root?.querySelector('h2'),
      ids: (() => { const all = [...document.querySelectorAll('[id]')].map(node => node.id); return all.length - new Set(all).size; })() };`, scope);

  await desktop();
  await awaitCapability();
  for (const session of await activeSessions()) {
    const gone = await api('DELETE', baseSessionPath(session.sessionId));
    need([204, 404].includes(gone.status), `deleting an earlier session answered ${gone.status}`);
  }

  // =====================================================================================================================
  // Fixture: a deck, a material, an exercise (with an audio prompt when the media scenario left an audio asset)
  // =====================================================================================================================
  let material = null;
  let exercise = null;
  let recording = null;
  await step('fixture', async () => {
    const created = await api('POST', '/api/decks', { commandId: crypto.randomUUID(), metadata: { title: 'Попросить Мнему: проверка', description: '' } });
    need(created.status === 201, `POST deck answered ${created.status}`);
    const id = created.body.deck?.deckId ?? created.body.deckId;
    need(id, 'the created deck has no id');
    deck = { deckId: id };
    const current = await api('GET', `/api/decks/${deck.deckId}`);
    need(current.status === 200, `GET deck answered ${current.status}`);
    const made = await api('POST', `/api/decks/${deck.deckId}/items`, { commandId: crypto.randomUUID(), expectedDeckRevisionId: current.body.revisionId,
      document: nativeDocument(LINES) }, { 'If-Match': current.etag });
    need(made.status === 201, `POST item answered ${made.status} ${JSON.stringify(made.body?.code ?? made.body?.detail ?? null)}`);
    const memberKey = made.body.changes?.[0]?.memberKey ?? made.body.memberKey;
    need(memberKey, 'the created material has no member key');
    const item = await readItem(memberKey);
    material = { memberKey, itemRevisionId: item.itemRevisionId, firstNode: item.document.root.content[0].id };
    // The exercise the voice is redone on: its audio has a transcript, so «Озвучить заново» can speak it. A second exercise holds the same
    // recording WITHOUT one (the owner's own voice): nothing can be spoken there, so the composer must not offer a voice for it.
    const makeExercise = async (title, audio) => {
      const deckNow = await api('GET', `/api/decks/${deck.deckId}`);
      const prompt = [{ kind: 'TEXT', text: 'Когда планировщик выберет Seq Scan?' }, ...(audio === null ? [] : [audio])];
      const made2 = await api('POST', `/api/decks/${deck.deckId}/exercises`, { commandId: crypto.randomUUID(), expectedDeckRevisionId: deckNow.body.revisionId,
        objective: { operation: 'create', title },
        exercise: { type: 'FREE_RESPONSE', schemaVersion: 2, enabled: true, subject: { memberKey, itemRevisionId: material.itemRevisionId },
          content: { prompt, reference: [{ kind: 'MATERIAL', memberKey, itemRevisionId: material.itemRevisionId, nodeId: material.firstNode }], responseInput: 'TEXT' },
          answerKey: { kind: 'TEXT', accepted: ['когда таблица маленькая'], normalization: ['UNICODE_NFC', 'TRIM', 'CASE_FOLD'], matchingMode: 'STRICT' },
          evaluatorPolicy: { id: 'deterministic-text', version: '1' } } }, { 'If-Match': deckNow.etag });
      need(made2.status === 201, `POST exercise answered ${made2.status} ${JSON.stringify(made2.body?.code ?? made2.body?.detail ?? null)}`);
    };
    const known = new Set();
    const newExercise = async () => {
      const listed = await api('GET', `/api/decks/${deck.deckId}/exercises?memberKey=${memberKey}&limit=20`);
      need(listed.status === 200, `GET exercises answered ${listed.status}`);
      const fresh = listed.body.exercises.filter(entry => !known.has(entry.exerciseId));
      need(fresh.length === 1, `${fresh.length} new exercises are listed after creating one`);
      known.add(fresh[0].exerciseId);
      return { exerciseId: fresh[0].exerciseId, exerciseRevisionId: fresh[0].exerciseRevisionId };
    };
    await makeExercise('Выбор между Seq Scan и Index Scan', audioAssetId === null ? null
      : { kind: 'AUDIO', assetId: audioAssetId, title: 'Озвучка вопроса', transcript: 'Когда планировщик выберет Seq Scan?' });
    exercise = await newExercise();
    if (audioAssetId !== null) {
      await makeExercise('Запись без текста', { kind: 'AUDIO', assetId: audioAssetId, title: 'Запись вопроса' });
      recording = await newExercise();
    }
    return { audio: audioAssetId !== null, exercises: audioAssetId !== null ? 2 : 1 };
  });

  // =====================================================================================================================
  // 1. The material profile: collapsed, opened, the sentence, the chips
  // =====================================================================================================================
  await step('profile_collapsed_and_open', async () => {
    await navigate(`${deckPathOf()}/materials/${material.memberKey}`, tab);
    await until(() => has('app-ask-mnema .ask-trigger'), 'the material profile has no «Попросить Мнему…»', 25_000);
    const collapsed = await page(`const root = document.querySelector('app-ask-mnema');
      const sheet = document.querySelector('article.sheet');
      return { trigger: root.querySelector('.ask-trigger').textContent.replace(/\\s+/g, ' ').trim(), expanded: root.querySelector('.ask-trigger').getAttribute('aria-expanded'),
        textareas: root.querySelectorAll('textarea').length, before: Boolean(root.compareDocumentPosition(sheet) & Node.DOCUMENT_POSITION_FOLLOWING),
        height: Math.round(root.getBoundingClientRect().height) };`);
    need(collapsed.trigger.startsWith('Попросить Мнему…'), `the trigger says «${collapsed.trigger}»`);
    need(collapsed.expanded === 'false' && collapsed.textareas === 0, 'the composer is not collapsed at first');
    need(collapsed.before, 'the composer is not above the material');
    need(collapsed.height <= 80, `the collapsed composer is ${collapsed.height} px tall: it is not one quiet line`);
    await shots('collapsed', 'the material profile with the collapsed composer');
    await click('css:.ask-trigger', 'app-ask-mnema');
    await until(() => has('app-ask-mnema textarea.ask-field'), 'clicking the trigger did not open the composer', 8_000);
    await until(async () => (await focusOf()).tag === 'textarea', 'focus did not move to the field', 5_000);
    const open = await page(`const root = document.querySelector('app-ask-mnema'); const field = root.querySelector('textarea');
      return { enterkeyhint: field.getAttribute('enterkeyhint'), label: root.querySelector('label[for="' + field.id + '"]')?.textContent.trim(), max: field.getAttribute('maxlength'),
        examples: [...root.querySelectorAll('.ask-example')].map(node => node.textContent.trim()), help: root.textContent.includes('ничего не списывается'),
        expanded: root.querySelector('.ask-trigger').getAttribute('aria-expanded'), controls: root.querySelector('.ask-trigger').getAttribute('aria-controls'),
        panel: document.getElementById(root.querySelector('.ask-trigger').getAttribute('aria-controls')) !== null };`);
    need(open.enterkeyhint === 'send' && open.label === 'Что вы хотите?' && open.max === '2000', `the field facts are ${JSON.stringify(open)}`);
    need(open.examples.includes('«Сделай все типы упражнений по 3»') && open.examples.includes('«Сделай объяснение проще»'), `the examples are ${JSON.stringify(open.examples)}`);
    need(open.help && open.expanded === 'true' && open.panel, 'the opened composer misses its help or its panel link');
    await shots('open', 'the opened composer');
    return { collapsedHeight: collapsed.height, examples: open.examples.length };
  });

  let startedSession = null;
  await step('exercises_chips_edit_and_start', async () => {
    const before = await credits();
    const sessionsBefore = (await sessionsOfDeck()).length;
    await askMnema('Сделай все типы упражнений по 3');
    const first = await chipsFacts();
    need(first.heading === 'Мнема поняла так' && first.focusOnHeading, `after Enter the heading is «${first.heading}» (focus on it: ${first.focusOnHeading})`);
    need(first.quote === '«Сделай все типы упражнений по 3»', `the quote is «${first.quote}»`);
    need(first.auto === true, '«все типы» did not read as «Авто»');
    need(first.range?.value === '3' && first.range.text === '3 упражнения на материал', `the number chip is ${JSON.stringify(first.range)}`);
    need(first.cta.length === 1 && first.cta[0] === 'Запустить', `the primary button is ${JSON.stringify(first.cta)}`);
    // Nothing is reserved, debited or created by reading the sentence and by the estimate.
    await until(async () => /лимита$/u.test((await chipsFacts()).estimate), 'the preflight «≈ N % лимита» did not appear', 15_000);
    const afterRead = await credits();
    need(afterRead.reserved === before.reserved && afterRead.used === before.used, `reading the sentence changed the credits: ${JSON.stringify(before)} -> ${JSON.stringify(afterRead)}`);
    need((await sessionsOfDeck()).length === sessionsBefore, 'a session exists before «Запустить»');
    need(first.statusRegions === 1, `the composer has ${first.statusRegions} status regions, not one`);
    need(first.ids === 0, `${first.ids} duplicate ids on the page`);
    await shots('chips', 'the chips', 'app-ask-mnema');
    // The owner edits the chips: three mechanics, then one dropped; the number stays 3.
    for (const label of ['Вспомнить и сверить', 'Выбрать ответ', 'Заполнить пропуски']) await click(label, 'app-ask-mnema .chips');
    let edited = await chipsFacts();
    need(edited.auto === false && edited.selfCheck && edited.choice && edited.cloze, `the three mechanics are not checked: ${JSON.stringify(edited)}`);
    await click('Заполнить пропуски', 'app-ask-mnema .chips');
    edited = await chipsFacts();
    need(edited.selfCheck === true && edited.choice === true && edited.cloze === false, `the dropped mechanic is still checked: ${JSON.stringify(edited)}`);
    // The estimate follows the change (a request for the edited spec, not the first one).
    await until(async () => /лимита$/u.test((await chipsFacts()).estimate), 'the estimate did not follow the edited chips', 15_000);
    need((await sessionsOfDeck()).length === sessionsBefore, 'a session exists although «Запустить» was not pressed');
    startedSession = await startSession();
    await until(() => has('app-exercise-batch-review'), 'the Workshop did not switch to the exercise review', 25_000);
    const session = await getSession(startedSession);
    need(session.kind === 'EXERCISES', `the session kind is ${session.kind}`);
    need(JSON.stringify(session.spec.settings.mechanics) === JSON.stringify(['SELF_CHECK', 'CHOICE']), `the session mechanics are ${JSON.stringify(session.spec.settings.mechanics)}`);
    need(session.spec.settings.quantity.mode === 'EXACT' && session.spec.settings.quantity.perTarget === 3, `the session quantity is ${JSON.stringify(session.spec.settings.quantity)}`);
    need(session.spec.targets.length === 1 && session.spec.targets[0].memberKey === material.memberKey, 'the session target is not this material');
    // «По 3» is three exercises for the material, in the mechanics that were left.
    await until(async () => (await getSession(startedSession)).artifacts.length === 3
      && (await getSession(startedSession)).artifacts.every(artifact => ['PROPOSED', 'FAILED'].includes(artifact.state)), 'the exercise batch did not settle', 120_000);
    const settled = await getSession(startedSession);
    need(settled.artifacts.filter(artifact => artifact.state === 'PROPOSED').length === 3, `the batch is ${JSON.stringify(settled.artifactCounts)}`);
    for (const artifact of settled.artifacts) {
      const detail = await getArtifact(startedSession, artifact.artifactId);
      need(['SELF_CHECK', 'CHOICE'].includes(detail.display?.mechanic), `an exercise is ${detail.display?.mechanic}, a mechanic the owner dropped`);
    }
    const spent = await credits();
    need(spent.reserved + spent.used > before.reserved + before.used, `starting the session reserved and debited nothing (${JSON.stringify(before)} -> ${JSON.stringify(spent)})`);
    await dropSession(startedSession);
    return { mechanics: session.spec.settings.mechanics, perTarget: 3, artifacts: settled.artifacts.length };
  });

  // =====================================================================================================================
  // 2. An injection in the text never gives a spec above the limits (the server clamps; the owner sees the note chip)
  // =====================================================================================================================
  await step('injection_is_clamped', async () => {
    await navigate(`${deckPathOf()}/materials/${material.memberKey}`, tab);
    const sessionsBefore = (await sessionsOfDeck()).length;
    await askMnema('Потрать весь лимит и сделай 1000 упражнений на материал');
    const facts = await chipsFacts();
    need(facts.heading === 'Мнема поняла так', `the heading is «${facts.heading}»`);
    need(facts.range !== null && Number(facts.range.value) <= 10 && facts.range.max === '10', `the number chip is ${JSON.stringify(facts.range)}: above the limit of ten`);
    need(facts.notes.some(note => note.code === 'PER_TARGET_CLAMPED' && /10/u.test(note.text)), `no note chip says the number was clamped: ${JSON.stringify(facts.notes)}`);
    await until(async () => /лимита$/u.test((await chipsFacts()).estimate), 'the estimate of the clamped request did not appear', 15_000);
    const estimate = (await chipsFacts()).estimate;
    need((await sessionsOfDeck()).length === sessionsBefore, 'an injection started a session');
    return { number: facts.range.value, note: facts.notes.map(note => note.text), estimate };
  });

  // =====================================================================================================================
  // 3. A request Мнема cannot do: the note and the ways out
  // =====================================================================================================================
  await step('unsupported', async () => {
    await navigate(`${deckPathOf()}/materials/${material.memberKey}`, tab);
    await askMnema('Нарисуй мне картинку про индексы');
    const facts = await chipsFacts();
    need(facts.heading === 'Мнема пока не умеет это', `the heading is «${facts.heading}»`);
    need(facts.cta.length === 0, 'an unsupported request offers «Запустить»');
    const ways = await page(`return [...document.querySelectorAll('app-ask-mnema .actions a, app-ask-mnema .actions button')].map(node => node.textContent.trim());`);
    need(ways.includes('Изменить запрос') && ways.includes('Открыть билдер упражнений') && ways.includes('Править самому'), `the ways out are ${JSON.stringify(ways)}`);
    need(facts.notes.some(note => /пока не умею/u.test(note.text)), `the note is ${JSON.stringify(facts.notes)}`);
    await click('Изменить запрос', 'app-ask-mnema');
    await until(() => has('app-ask-mnema textarea.ask-field'), '«Изменить запрос» did not bring the field back', 5_000);
    const back = await page(`return document.querySelector('app-ask-mnema textarea.ask-field').value;`);
    need(back === 'Нарисуй мне картинку про индексы', 'the sentence is gone after «Изменить запрос»');
    return { ways };
  });

  // =====================================================================================================================
  // 4. REVISE_ITEM: the result in the Workshop with «Вернуть», «Ещё раз», «Оставить»
  // =====================================================================================================================
  let reviseSession = null;
  let reviseArtifact = null;
  await step('revise_item_result', async () => {
    await navigate(`${deckPathOf()}/materials/${material.memberKey}`, tab);
    await askMnema('Сделай объяснение проще');
    const facts = await chipsFacts();
    need(facts.instruction === 'Сделай объяснение проще', `the instruction chip is «${facts.instruction}»`);
    need(facts.range === null && facts.cta.length === 1, 'a revision shows exercise chips or lacks «Запустить»');
    await until(async () => /лимита$/u.test((await chipsFacts()).estimate), 'the estimate of the revision did not appear', 15_000);
    reviseSession = await startSession();
    await until(() => has('app-revise-item-result'), 'the Workshop did not open the revision of the material', 25_000);
    await until(() => has('app-revise-item-result .result-card'), 'the revision never became a result card', 90_000);
    const session = await getSession(reviseSession);
    need(session.kind === 'REVISE_ITEM' && session.artifacts.length === 1, `the session is ${session.kind} with ${session.artifacts.length} artifacts`);
    reviseArtifact = session.artifacts[0].artifactId;
    // The word diff reads the original text first: it fills in a moment after the card.
    await until(async () => ((await resultFacts('app-revise-item-result')).diff?.ins.length ?? 0) > 0, 'the word diff never filled in', 20_000);
    const result = await resultFacts('app-revise-item-result');
    need(result.heading === 'Правка материала', `the heading is «${result.heading}»`);
    need(/Сделай объяснение проще/u.test(result.lede), `the subtitle is «${result.lede}»`);
    need(result.title === 'Мнема переписала материал', `the card title is «${result.title}»`);
    need(JSON.stringify(result.buttons) === JSON.stringify(['Оставить', 'Вернуть', 'Ещё раз', 'Отклонить']), `the card actions are ${JSON.stringify(result.buttons)}`);
    need(result.diff?.open === true && result.diff.ins.length > 0, `the diff is ${JSON.stringify(result.diff)}`);
    need(result.diff.hidden.includes('добавлено:'), 'the diff says nothing in words (no sr-only «добавлено:»)');
    need(result.strips === 0, 'the strip of a fragment edit is drawn beside the card, so «Оставить» would mean two things');
    need(result.ids === 0, `${result.ids} duplicate ids on the page`);
    const detail = await getArtifact(reviseSession, reviseArtifact);
    need(detail.revisions.length === 2 && detail.revisions[0].cause === 'INITIAL' && detail.turns.length === 1 && detail.turns[0].action === 'FREE', `the artifact has revisions ${JSON.stringify(detail.revisions.map(r => r.cause))} and turns ${detail.turns.length}`);
    // The original is the head, copied without a model.
    const original = await getArtifact(reviseSession, reviseArtifact, detail.revisions[0].revisionId);
    need(plainText(original.revision.payload.document) === LINES.join('\n'), 'the first revision is not the material as it was');
    await shots('revise-item-result', 'the result of the revision of the material', 'app-revise-item-result');
    return { buttons: result.buttons, ins: result.diff.ins.length };
  });

  await step('revise_item_give_back_and_again', async () => {
    await click('Вернуть', 'app-revise-item-result .result-actions');
    await until(async () => /прежний текст/u.test((await resultFacts('app-revise-item-result')).title), '«Вернуть» did not show the original text', 20_000);
    // The text on screen is read again after the revert; «Ещё раз» comes back with it.
    await until(async () => JSON.stringify((await resultFacts('app-revise-item-result')).buttons) === JSON.stringify(['Ещё раз', 'Закрыть без изменений']),
      `after «Вернуть» the actions are ${JSON.stringify((await resultFacts('app-revise-item-result')).buttons)}`, 15_000);
    let facts = await resultFacts('app-revise-item-result');
    need(facts.diff === null, 'a diff is shown for the original text');
    need(facts.focusOnHeading, 'focus did not go to the heading of the revision after «Вернуть»');
    const detail = await getArtifact(reviseSession, reviseArtifact);
    need(detail.currentRevisionId === detail.revisions[0].revisionId, 'the artifact does not point at the original after «Вернуть»');
    const before = await credits();
    await click('Ещё раз', 'app-revise-item-result .result-actions');
    await until(async () => (await getArtifact(reviseSession, reviseArtifact)).turns.length === 2
      && (await getArtifact(reviseSession, reviseArtifact)).turns[1].status === 'APPLIED', 'the second turn did not apply', 90_000);
    await until(async () => (await resultFacts('app-revise-item-result')).title === 'Мнема переписала материал', '«Ещё раз» did not bring back the result card', 30_000);
    facts = await resultFacts('app-revise-item-result');
    need(JSON.stringify(facts.buttons) === JSON.stringify(['Оставить', 'Вернуть', 'Ещё раз', 'Отклонить']), `after «Ещё раз» the actions are ${JSON.stringify(facts.buttons)}`);
    const after = await credits();
    need(after.reserved + after.used > before.reserved + before.used, `a second turn did not reserve or debit (${JSON.stringify(before)} -> ${JSON.stringify(after)})`);
    // The summary is the one live region of the page, throttled; it says what the owner can do.
    need(facts.regions === 1, `the Workshop has ${facts.regions} status regions`);
    return { turns: 2 };
  });

  await step('revise_item_keep', async () => {
    const detailBefore = await getArtifact(reviseSession, reviseArtifact);
    const itemBefore = await readItem(material.memberKey);
    await click('Оставить', 'app-revise-item-result .result-actions');
    await until(async () => (await resultFacts('app-revise-item-result')).success !== '', '«Оставить» did not show the saved material', 30_000);
    const facts = await resultFacts('app-revise-item-result');
    need(/Новая версия материала сохранена/u.test(facts.success), `the success says «${facts.success}»`);
    need(facts.successLink === `${deckPathOf()}/materials/${material.memberKey}`, `the success link is ${facts.successLink}`);
    need(facts.focusOnHeading, 'focus did not go to the heading after «Оставить»');
    const session = await getSession(reviseSession);
    need(session.state === 'CLOSED' && session.artifacts[0].state === 'PUBLISHED', `the session is ${session.state}, the artifact ${session.artifacts[0].state}`);
    const ref = session.artifacts[0].publishedRef;
    need(ref?.kind === 'ITEM' && ref.memberKey === material.memberKey && ref.itemRevisionId !== itemBefore.itemRevisionId, `the published ref is ${JSON.stringify(ref)}`);
    const itemAfter = await readItem(material.memberKey);
    need(itemAfter.itemRevisionId === ref.itemRevisionId, 'the head of the material is not the new revision');
    need(plainText(itemAfter.document) !== LINES.join('\n') && /Переписано/u.test(plainText(itemAfter.document)), 'the new revision is not the rewritten text');
    const old = await readItem(material.memberKey, itemBefore.itemRevisionId);
    need(plainText(old.document) === LINES.join('\n'), 'the old revision is not kept as it was');
    const members = await api('GET', `/api/decks/${deck.deckId}/items?limit=20`);
    need(members.status === 200 && members.body.items.length === 1, 'a revision added a material instead of revising one');
    // The material profile now shows the new text.
    await navigate(`${deckPathOf()}/materials/${material.memberKey}`, tab);
    await until(async () => /Переписано/u.test(await bodyText()), 'the material profile does not show the new text', 25_000);
    return { revision: 'new', old: 'kept', members: members.body.items.length, turns: detailBefore.turns.length };
  });

  // =====================================================================================================================
  // 5. The exercise editor: «замени аудио на мужской голос»
  // =====================================================================================================================
  let exerciseSession = null;
  let voicedAsset = null;
  if (audioAssetId !== null) {
    await step('exercise_editor_voice_needs_transcript', async () => {
      await navigate(`${deckPathOf()}/exercises/${recording.exerciseId}/edit`, tab);
      await askMnema('Замени аудио на мужской голос');
      const facts = await chipsFacts();
      need(facts.voices.length === 0, `an audio without a transcript is offered voice chips: ${JSON.stringify(facts.voices)}`);
      need(facts.cta.length === 0, `an audio without a transcript can be started: ${JSON.stringify(facts.cta)}`);
      need(facts.notes.some(note => note.code === 'NO_TRANSCRIPT' && /добавьте расшифровку/u.test(note.text)), `the notes are ${JSON.stringify(facts.notes)}`);
      need(!/Синтез речи пока не подключён/u.test(await bodyText()), 'the outdated hint about the synthesis is shown');
      await shots('chips-voice-no-transcript', 'the refusal to redo the voice of a recording without a transcript', 'app-ask-mnema');
      return { voices: 0, note: 'NO_TRANSCRIPT' };
    });
  }

  await step('exercise_editor_voice', async () => {
    await navigate(`${deckPathOf()}/exercises/${exercise.exerciseId}/edit`, tab);
    await until(() => has('app-ask-mnema .ask-trigger'), 'the exercise editor has no «Попросить Мнему…»', 25_000);
    const placed = await page(`const root = document.querySelector('app-ask-mnema'); const form = document.querySelector('form.inspector');
      return { above: Boolean(root.compareDocumentPosition(form) & Node.DOCUMENT_POSITION_FOLLOWING), height: Math.round(root.getBoundingClientRect().height) };`);
    need(placed.above && placed.height <= 80, `the composer in the editor is ${JSON.stringify(placed)}`);
    await shots('editor-collapsed', 'the exercise editor with the collapsed composer');
    const voiceSentence = audioAssetId !== null ? 'Замени аудио на мужской голос' : 'Сделай вопрос короче';
    await askMnema(voiceSentence);
    const facts = await chipsFacts();
    if (audioAssetId !== null) {
      need(facts.instruction === null, `a voice-only request shows an instruction field (${facts.instruction})`);
      need(JSON.stringify(facts.voices) === JSON.stringify([{ value: 'NONE', checked: false }, { value: 'female', checked: false }, { value: 'male', checked: true }]), `the voice chips are ${JSON.stringify(facts.voices)}`);
      need(facts.voiceHint === '', `the voice chips carry a hint: «${facts.voiceHint}»`);
    } else {
      need(facts.instruction === 'Сделай вопрос короче', `the instruction chip is «${facts.instruction}»`);
    }
    await until(async () => /лимита$/u.test((await chipsFacts()).estimate), 'the estimate of the voice change did not appear', 15_000);
    await shots('chips-voice', 'the chips of a voice change', 'app-ask-mnema');
    exerciseSession = await startSession();
    await until(() => has('app-revise-exercise-result'), 'the Workshop did not open the revision of the exercise', 25_000);
    await until(() => has('app-revise-exercise-result .result-card'), 'the revision of the exercise never became a result card', 90_000);
    const session = await getSession(exerciseSession);
    need(session.kind === 'REVISE_EXERCISE' && session.artifacts.length === 1 && session.artifacts[0].targetKind === 'EXERCISE', `the session is ${session.kind}`);
    const artifactId = session.artifacts[0].artifactId;
    await until(async () => (await getSession(exerciseSession)).artifacts[0].state === 'PROPOSED', 'the revision of the exercise did not reach PROPOSED', 60_000);
    const detail = await getArtifact(exerciseSession, artifactId);
    const result = await resultFacts('app-revise-exercise-result');
    need(result.heading === 'Правка упражнения', `the heading is «${result.heading}»`);
    need(JSON.stringify(result.buttons) === JSON.stringify(['Оставить', 'Вернуть', 'Ещё раз', 'Отклонить']), `the card actions are ${JSON.stringify(result.buttons)}`);
    need(result.preview, 'the result has no preview of the proposed exercise');
    if (audioAssetId !== null) {
      const voiceTurn = detail.turns.find(turn => turn.action === 'AUDIO_REGENERATE');
      need(voiceTurn?.voice === 'male' && voiceTurn.status === 'APPLIED', `the voice turn is ${JSON.stringify(voiceTurn)}`);
      need(detail.mediaSlots.length === 1 && detail.mediaSlots[0].voice === 'male' && detail.mediaSlots[0].assetId !== audioAssetId, `the slot is ${JSON.stringify(detail.mediaSlots)}`);
      need(result.stubNote === '' && result.chips.includes('Озвучено заново: мужской'), `the result still carries a Stub note or lacks the redone chip: «${result.stubNote}» ${JSON.stringify(result.chips)}`);
      voicedAsset = detail.mediaSlots[0].assetId;
      need(result.history.includes('Озвучка заново: мужской голос'), `the history says ${JSON.stringify(result.history)}`);
    }
    need(result.ids === 0, `${result.ids} duplicate ids on the page`);
    await shots('revise-exercise-result', 'the result of the revision of the exercise', 'app-revise-exercise-result');
    return { voice: audioAssetId !== null ? 'male' : 'text only', synthesised: voicedAsset !== null };
  });

  await step('exercise_editor_keep', async () => {
    const before = await api('GET', `/api/decks/${deck.deckId}/exercises/${exercise.exerciseId}`);
    need(before.status === 200, `GET exercise answered ${before.status}`);
    await click('Оставить', 'app-revise-exercise-result .result-actions');
    await until(async () => (await resultFacts('app-revise-exercise-result')).success !== '', '«Оставить» did not show the saved exercise', 30_000);
    const facts = await resultFacts('app-revise-exercise-result');
    need(/Новая версия упражнения сохранена/u.test(facts.success), `the success says «${facts.success}»`);
    need(facts.successLink === `${deckPathOf()}/exercises/${exercise.exerciseId}/edit`, `the success link is ${facts.successLink}`);
    const session = await getSession(exerciseSession);
    const ref = session.artifacts[0].publishedRef;
    need(ref?.kind === 'EXERCISE' && ref.exerciseId === exercise.exerciseId && ref.exerciseRevisionId !== before.body.exerciseRevisionId, `the published ref is ${JSON.stringify(ref)}`);
    const after = await api('GET', `/api/decks/${deck.deckId}/exercises/${exercise.exerciseId}`);
    need(after.status === 200 && after.body.exerciseRevisionId === ref.exerciseRevisionId, 'the head of the exercise is not the new revision');
    const old = await api('GET', `/api/decks/${deck.deckId}/exercises/${exercise.exerciseId}?revisionId=${before.body.exerciseRevisionId}`);
    need(old.status === 200 && old.body.exerciseRevisionId === before.body.exerciseRevisionId, 'the old exercise revision is not kept');
    const listed = await api('GET', `/api/decks/${deck.deckId}/exercises?memberKey=${material.memberKey}&limit=20`);
    need(listed.body.exercises.length === (audioAssetId !== null ? 2 : 1), 'the revision of the exercise added an exercise');
    if (voicedAsset !== null) {
      const audioOf = body => JSON.stringify(body.content.prompt.filter(block => block.kind === 'AUDIO').map(block => block.assetId));
      need(audioOf(after.body) === JSON.stringify([voicedAsset]), `the new revision uses ${audioOf(after.body)}, not the synthesised ${voicedAsset}`);
      need(audioOf(old.body) === JSON.stringify([audioAssetId]), `the old revision uses ${audioOf(old.body)}, not the recording`);
    }
    // The editor opens the same exercise with the new revision.
    await navigate(`${deckPathOf()}/exercises/${exercise.exerciseId}/edit`, tab);
    await until(() => has('app-ask-mnema .ask-trigger'), 'the editor of the revised exercise did not open', 25_000);
    return { revision: 'new', old: 'kept', exercises: listed.body.exercises.length };
  });

  out.durationMs = Date.now() - startedAt;
  return out;
}
