// Real-browser check of image search in the Workshop (#296, AI-10) against the real Learning API with the Stub text provider and the Stub
// image source (`--generation --media`; never a real provider, no key, no network). Runs on the signed-in account's tab, in a deck of its
// own. Node 24 built-ins only.
//
// The composer path IS driven here (workshop.mjs covers the composer in general, not its «Изображения» chip): «Ещё настройки», the chip,
// Enter. Everything after is the real Angular UI on the real HTTP surface: the paper placeholder becomes the image, the attribution line,
// «Найти похожее» reached with Tab and opened with Enter, a typed query, «Искать», the status while the turn runs, the variants radio group
// (arrow keys move the local choice and send NOTHING: the network log is checked), «Использовать это изображение», the history's «Вернуть к
// этой версии», a `[[stub:image-none]]` search (failure announced, picture unchanged, nothing charged), approval and Browse (the published
// caption carries the attribution). Only the fixture deck is made through the authenticated API, and the state the page is compared with is
// read through it. A broken step is a finding for the product, never something to work around: it throws, a failure screenshot and a .txt
// are written, and the run fails with the step name.
import { writeFile } from 'node:fs/promises';
import { join } from 'node:path';

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const PROMPT = 'Рыжая лиса зимой: короткий материал';
const KEYS = { Enter: ['Enter', 'Enter', 13], Escape: ['Escape', 'Escape', 27], Tab: ['Tab', 'Tab', 9], ArrowDown: ['ArrowDown', 'ArrowDown', 40],
  ArrowUp: ['ArrowUp', 'ArrowUp', 38], ArrowRight: ['ArrowRight', 'ArrowRight', 39], ArrowLeft: ['ArrowLeft', 'ArrowLeft', 37] };
const NOTHING_FOUND = 'Не нашлось подходящих изображений. Изображение не изменилось, лимит не списан.';

export async function runWorkshopImages(ctx, h) {
  const { tab, config, SafeFailure, until, navigate, saveScreenshot, setStep } = ctx;
  const { api, page, has, need, settle, metrics, desktop, awaitCapability, activeSessions, sessionPath: baseSessionPath, location } = h;
  const out = { stub: true, stages: {}, screenshots: [] };
  const startedAt = Date.now();

  const failureShot = async name => { try { await saveScreenshot(`failure-images-${name}.png`, tab); } catch { /* the original failure is the verdict */ } };
  let deck = null;
  let session = null;
  let artifactId = null;
  const sessionPath = id => `/api/decks/${deck.deckId}/generation-sessions/${id}`;
  const artifactPath = () => `${sessionPath(session)}/artifacts/${artifactId}`;
  const getArtifact = async () => {
    const result = await api('GET', artifactPath());
    need(result.status === 200, `GET artifact answered ${result.status}`);
    return result.body;
  };
  const step = async (name, body) => {
    setStep(`images_${name}`);
    try { const result = await body(); out.stages[name] = result ?? true; return result; } catch (error) {
      await failureShot(name);
      let held = '';
      try {
        const detail = await getArtifact();
        held = ` | server: state ${detail.state}, errorCode ${detail.errorCode}, revisions ${detail.revisions.length}, turns ${JSON.stringify(detail.turns.slice(-2))}, slots ${JSON.stringify(detail.mediaSlots.map(slot => ({ k: slot.slotKey, s: slot.state, e: slot.errorCode, c: slot.candidates.map(c => c.state + (c.chosen ? '*' : '')) })))}`;
      } catch (reading) { held = ` | server: unreadable (${String(reading?.message ?? reading).slice(0, 60)})`; }
      await writeFile(join(config.output, `failure-images-${name}.txt`),
        (error instanceof SafeFailure ? error.message : `driver: ${String(error?.message ?? error).slice(0, 160)}`) + held + '\n').catch(() => {});
      throw error;
    }
  };
  const shot = async name => { await saveScreenshot(name, tab); out.screenshots.push(name); };

  // ---- low-level input -------------------------------------------------------------------------------------------------
  const press = async (name, { modifiers = 0 } = {}) => {
    const [key, code, virtualKeyCode] = KEYS[name];
    const event = { key, code, windowsVirtualKeyCode: virtualKeyCode, nativeVirtualKeyCode: virtualKeyCode, modifiers };
    await tab.call('Page.bringToFront');
    await tab.call('Input.dispatchKeyEvent', name === 'Enter'
      ? { type: 'keyDown', text: '\r', unmodifiedText: '\r', ...event } : { type: 'rawKeyDown', ...event });
    await tab.call('Input.dispatchKeyEvent', { type: 'keyUp', ...event });
  };
  const insertText = async text => { await tab.call('Page.bringToFront'); await tab.call('Input.insertText', { text }); };
  const mouse = (type, point, clickCount = 1) => tab.call('Input.dispatchMouseEvent',
    { type, x: point.x, y: point.y, button: type === 'mouseMoved' ? 'none' : 'left', clickCount });
  const clickAt = async point => {
    await tab.call('Page.bringToFront');
    await mouse('mouseMoved', point); await mouse('mousePressed', point); await mouse('mouseReleased', point);
    await settle();
  };
  /** The centre of the first visible element with this selector (and exact text); the page is scrolled to it. */
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

  // ---- the page ----------------------------------------------------------------------------------------------------------
  const widget = () => page(`const text = value => (value ?? '').replace(/\\s+/gu, ' ').replaceAll('\\u00a0', ' ').trim();
    const root = document.querySelector('app-proposal-document');
    const active = document.activeElement;
    const figure = root?.querySelector('figure.native-media');
    const img = figure?.querySelector('img');
    const radios = [...(root?.querySelectorAll('app-image-variants input[type=radio]') ?? [])];
    const panel = root?.querySelector('app-image-search-panel');
    const variants = root?.querySelector('details.variants');
    const strip = root?.querySelector('.rewrite-strip');
    return {
      image: img ? { src: Boolean(img.currentSrc), natural: img.naturalWidth, alt: img.alt } : null,
      placeholder: text(figure?.querySelector('.native-media-pending')?.textContent),
      credit: text(root?.querySelector('.image-credit')?.textContent),
      actions: [...(root?.querySelectorAll('.media-actions button') ?? [])].map(node => text(node.textContent)),
      panel: panel ? { label: panel.querySelector('[role=group]')?.getAttribute('aria-label'), field: panel.querySelector('input')?.value ?? null,
        placeholder: panel.querySelector('input')?.placeholder, fieldLabel: text(panel.querySelector('label')?.textContent), cost: text(panel.querySelector('.panel-cost')?.textContent),
        status: text(panel.querySelector('[role=status]')?.textContent), error: text(panel.querySelector('.panel-error')?.textContent),
        buttons: [...panel.querySelectorAll('button')].map(node => ({ text: text(node.textContent), disabled: node.getAttribute('aria-disabled') === 'true' })),
        focusOnField: active === panel.querySelector('input'), dialog: Boolean(panel.closest('dialog, [role=dialog]') || panel.querySelector('dialog, [role=dialog]')),
        rect: (() => { const rect = panel.getBoundingClientRect(); return { top: rect.top, bottom: rect.bottom, left: rect.left, right: rect.right }; })() } : null,
      trigger: (() => { const node = root?.querySelector('[data-focus-key^="search:"]'); return node ? { text: text(node.textContent), expanded: node.getAttribute('aria-expanded') } : null; })(),
      variants: variants ? { open: variants.open, summary: text(variants.querySelector('summary')?.textContent), legend: text(variants.querySelector('legend')?.textContent),
        count: radios.length, checked: radios.findIndex(radio => radio.checked), names: new Set(radios.map(radio => radio.name)).size,
        pending: variants.querySelector('fieldset')?.getAttribute('aria-disabled') === 'true', status: text(variants.querySelector('.status')?.textContent),
        commit: (() => { const button = variants.querySelector('.commit-button'); return button ? { text: text(button.textContent), disabled: button.getAttribute('aria-disabled') === 'true' } : null; })(),
        error: text(variants.querySelector('.error')?.textContent),
        cards: [...variants.querySelectorAll('li.card')].map(card => ({ source: text(card.querySelector('.card-source')?.textContent), license: text(card.querySelector('.card-license')?.textContent),
          link: card.querySelector('a')?.getAttribute('href') ?? null, linkLabel: card.querySelector('a')?.getAttribute('aria-label') ?? null, rel: card.querySelector('a')?.getAttribute('rel') ?? null,
          target: card.querySelector('a')?.getAttribute('target') ?? null, picture: Boolean(card.querySelector('img')), x: Math.round(card.getBoundingClientRect().left), w: Math.round(card.getBoundingClientRect().width) })),
        focusOnChecked: radios.includes(active) && active.checked } : null,
      strip: strip ? { text: text(strip.textContent), buttons: [...strip.querySelectorAll('button')].map(node => text(node.textContent)), failed: strip.classList.contains('is-failed') } : null,
      announcement: text(root?.querySelector(':scope > p.document-announcement')?.textContent),
      caption: text(root?.querySelector('.rewriting-caption')?.textContent),
      summary: text(document.querySelector('section.workshop .summary')?.textContent),
      history: (() => { const details = root?.querySelector('.edit-history'); return details ? { summary: text(details.querySelector('summary')?.textContent), items: [...details.querySelectorAll('li')].map(item => ({
        text: text(item.textContent), revert: Boolean([...item.querySelectorAll('button')].find(node => text(node.textContent) === 'Вернуть к этой версии')) })) } : null; })(),
      notice: text(document.querySelector('section.workshop .notice')?.textContent),
      focus: active === document.body ? 'body' : { tag: active.tagName.toLowerCase(), cls: String(active.className).slice(0, 30), text: text(active.textContent).slice(0, 30), type: active.type ?? null, checked: active.checked ?? null } };`);
  const imageNodeAsset = detail => detail.revision.payload.document.root.content.find(node => node.type === 'image')?.attrs.assetId ?? null;
  const slotOf = detail => detail.mediaSlots.find(slot => slot.kind === 'IMAGE');
  const credits = async () => {
    const usage = await api('GET', '/api/usage');
    need(usage.status === 200, `GET usage answered ${usage.status}`);
    return usage.body.credits;
  };
  // What the page sent: a selection (POST .../media-slots/{key}/selection) is a server write, so arrow keys must never produce one.
  const requests = [];
  tab.on('Network.requestWillBeSent', event => { requests.push({ at: Date.now(), method: event.request.method, url: event.request.url }); });
  const selectionRequests = since => requests.filter(entry => entry.at >= since && entry.method === 'POST' && /\/media-slots\/[^/]+\/selection$/u.test(entry.url.split('?')[0]));
  const editRequests = since => requests.filter(entry => entry.at >= since && entry.method === 'POST' && /\/artifacts\/[^/]+\/edits$/u.test(entry.url.split('?')[0]));
  /** Waits for the last turn of the artifact to end; returns it. */
  const lastTurnEnds = async (previousCount, label, timeout = 150_000) => {
    let turn = null;
    await until(async () => {
      const turns = (await getArtifact()).turns;
      turn = turns.length > previousCount ? turns.at(-1) : null;
      return turn !== null && !['QUEUED', 'RUNNING'].includes(turn.status);
    }, `${label}: the turn did not end`, timeout);
    return turn;
  };
  const focusedLabel = () => page(`const active = document.activeElement; return { text: (active?.textContent ?? '').replace(/\\s+/g, ' ').trim(), key: active?.getAttribute?.('data-focus-key') ?? null, tag: active?.tagName?.toLowerCase() };`);
  /** Real Tab presses from the image's own zoom button until «Найти похожее» has focus (at most `limit` stops). */
  const tabToSearch = async (limit = 8) => {
    await page(`const button = document.querySelector('app-proposal-document figure.native-media .image-open'); if (!button) return false; button.scrollIntoView({ block: 'center', behavior: 'instant' }); button.focus(); return document.activeElement === button;`);
    const seen = [];
    for (let index = 0; index < limit; index++) {
      await press('Tab');
      const now = await focusedLabel();
      seen.push(now.text);
      if (now.key !== null && now.key.startsWith('search:')) return seen;
    }
    throw new SafeFailure(`Tab from the image did not reach «Найти похожее» in ${limit} stops (saw ${JSON.stringify(seen)})`);
  };

  // ======================================================================================================================
  // 0. The fixture: a deck of its own, and the capability
  // ======================================================================================================================
  await desktop();
  await awaitCapability();
  for (const active of await activeSessions()) {
    const gone = await api('DELETE', baseSessionPath(active.sessionId));
    need([204, 404].includes(gone.status), `deleting an earlier session answered ${gone.status}`);
  }
  await step('fixture', async () => {
    const created = await api('POST', '/api/decks', { commandId: crypto.randomUUID(), metadata: { title: 'Поиск изображений: проверка', description: '' } });
    need(created.status === 201, `POST deck answered ${created.status}`);
    deck = { deckId: created.body.deck?.deckId ?? created.body.deckId };
    need(deck.deckId, 'the created deck has no id');
    const capabilities = await api('GET', '/api/capabilities');
    need(capabilities.status === 200 && capabilities.body?.imageSearch?.available === true,
      `GET /api/capabilities does not report imageSearch available (Stub image source): ${JSON.stringify(capabilities.body?.imageSearch ?? null)}`);
    const usage = await credits();
    return { deck: 'own', imageSearch: capabilities.body.imageSearch, creditsBefore: usage };
  });

  // ======================================================================================================================
  // 1. The composer: «Ещё настройки» → «Изображения», Enter
  // ======================================================================================================================
  await step('composer', async () => {
    await navigate(`/decks/${deck.deckId}/materials/new`, tab);
    await until(() => has('app-generation-composer textarea'), 'the composer did not open', 25_000);
    // The chip is behind «Ещё настройки» (progressive disclosure) and is off by default.
    const before = await page(`const details = document.querySelector('app-generation-settings details.more');
      const chip = [...document.querySelectorAll('app-generation-settings label.chip')].find(label => label.textContent.trim() === 'Изображения');
      return { open: details?.open ?? null, checked: chip?.querySelector('input')?.checked ?? null, disabled: chip?.querySelector('input')?.disabled ?? null };`);
    need(before.open === false && before.checked === false && before.disabled === false, `the image chip starts as ${JSON.stringify(before)}`);
    await click('app-generation-settings summary', 'Ещё настройки');
    await until(async () => (await page(`return document.querySelector('app-generation-settings details.more')?.open ?? false;`)), '«Ещё настройки» did not open');
    await click('app-generation-settings label.chip', 'Изображения');
    const chip = await page(`const chip = [...document.querySelectorAll('app-generation-settings label.chip')].find(label => label.textContent.trim() === 'Изображения'); return chip.querySelector('input').checked;`);
    need(chip === true, 'the click on «Изображения» did not check the chip');
    need(await page(`const textarea = document.querySelector('app-generation-composer textarea'); textarea.focus(); return document.activeElement === textarea;`), 'the prompt field could not take focus');
    await insertText(PROMPT);
    await press('Enter');
    await until(async () => /\/workshop\/[0-9a-f-]{36}$/u.test(await location()), 'Enter did not open the Workshop', 25_000);
    session = (await location()).match(/\/workshop\/([0-9a-f-]{36})$/u)[1];
    const detail = (await api('GET', sessionPath(session))).body;
    need(detail.spec.settings.media.imageSearch === true, `the stored spec says imageSearch ${detail.spec.settings.media.imageSearch}`);
    need(detail.spec.prompt === PROMPT, 'the stored prompt differs from what was typed');
    artifactId = detail.artifacts[0]?.artifactId ?? null;
    need(artifactId !== null, 'the session lists no artifact');
    return { imageSearchSent: true };
  });

  // ======================================================================================================================
  // 2. The paper placeholder becomes the image; the attribution line
  // ======================================================================================================================
  let first = null;
  await step('slot_ready', async () => {
    const seen = new Set();
    await until(async () => {
      const slots = (await getArtifact()).mediaSlots;
      for (const slot of slots) seen.add(`${slot.kind}:${slot.mode}:${slot.state}`);
      const slot = slots.find(held => held.kind === 'IMAGE');
      need(!slot || slot.state !== 'FAILED', `the image slot failed: ${slot?.errorCode}`);
      return slot !== undefined && slot.state === 'READY';
    }, 'the image slot did not become READY', 150_000);
    await until(async () => (await getArtifact()).state === 'PROPOSED', 'the material did not settle as PROPOSED', 60_000);
    await until(async () => { const view = await widget(); return view.image !== null && view.image.natural > 0; }, 'the image did not render in the Workshop', 60_000);
    const detail = await getArtifact();
    const slot = slotOf(detail);
    need(slot.mode === 'search', `the slot mode is ${slot.mode}`);
    need(slot.attribution !== null && slot.attribution.source === 'STUB', `the attribution is ${JSON.stringify(slot.attribution)}`);
    need(slot.candidates.length === 1 && slot.candidates[0].chosen === true && slot.candidates[0].state === 'READY', `the initial slot has ${JSON.stringify(slot.candidates.map(c => [c.state, c.chosen]))}`);
    first = { revisionId: detail.currentRevisionId, assetId: imageNodeAsset(detail), slot, candidateId: slot.candidates[0].candidateId };
    need(first.assetId === slot.assetId, 'the image node and the slot name different assets');
    // The page follows the slot through the events and a re-read of the artifact: the attribution line appears when the READY slot is read.
    await until(async () => (await widget()).credit !== '', 'the Workshop never showed the attribution line of the READY image', 45_000);
    const view = await widget();
    need(view.credit.includes('Тестовый источник') && view.credit.includes('CC0 1.0') && view.credit.startsWith('Фото: ')
      || (slot.attribution.author === '' && /^Тестовый источник · CC0 1\.0$/u.test(view.credit)), `the attribution line says «${view.credit}»`);
    const expected = [slot.attribution.author.trim() ? `Фото: ${slot.attribution.author.trim()}` : null, 'Тестовый источник', slot.attribution.license].filter(Boolean).join(' · ');
    need(view.credit === expected, `the attribution line says «${view.credit}», the slot says «${expected}»`);
    need(view.variants === null, 'a single found image offers variants');
    need(view.actions.includes('Найти похожее'), `the image actions are ${JSON.stringify(view.actions)}`);
    // No mark of AI on content: the image carries no «создано» wording.
    need(!/создан[оа] с ИИ|сгенерирован/iu.test(view.credit), 'the attribution carries an AI mark');
    await shot('workshop-image-ready-1440.png');
    return { slotStates: [...seen], attribution: view.credit, assetId: first.assetId };
  });

  // ======================================================================================================================
  // 3. «Найти похожее» by keyboard, a typed query, «Искать»
  // ======================================================================================================================
  let searched = null;
  await step('search', async () => {
    const stops = await tabToSearch();
    await press('Enter');
    await until(async () => (await widget()).panel !== null, 'Enter on «Найти похожее» did not open the panel', 8_000);
    await until(async () => (await widget()).panel?.focusOnField === true, 'focus is not in the field of the panel', 5_000);
    const opened = await widget();
    need(opened.panel.dialog === false, 'the panel is a dialog');
    need(opened.panel.label === 'Поиск изображения' && opened.panel.fieldLabel === 'Что искать', `the panel is ${JSON.stringify([opened.panel.label, opened.panel.fieldLabel])}`);
    need(opened.panel.placeholder === opened.image.alt && opened.panel.placeholder !== '', `the placeholder is «${opened.panel.placeholder}», the alt «${opened.image.alt}»`);
    need(opened.panel.cost === '1 кредит из ИИ-бюджета', `the cost line is «${opened.panel.cost}»`);
    need(opened.panel.buttons.map(button => button.text).join() === 'Искать,Отмена', `the panel buttons are ${JSON.stringify(opened.panel.buttons)}`);
    need(opened.trigger.expanded === 'true', 'the trigger does not say the panel is expanded');
    need(opened.image !== null && opened.image.natural > 0, 'the image went away while the panel was open');
    await insertText('лиса зимой снег');
    need((await widget()).panel.field === 'лиса зимой снег', 'the typed query is not in the field');
    await shot('workshop-image-panel-1440.png');
    const before = Date.now();
    const turnsBefore = (await getArtifact()).turns.length;
    // Tab to «Искать» and press it: the keyboard path of the panel.
    await press('Tab');
    need((await focusedLabel()).text === 'Искать', `Tab from the field reached «${(await focusedLabel()).text}», not «Искать»`);
    await press('Enter');
    // The turn is accepted: the panel says it is searching (aria-disabled controls), the image stays visible.
    const seen = { status: false, disabled: false, imageStayed: true, caption: false };
    const watch = setInterval(() => { page(`const status = document.querySelector('app-image-search-panel [role=status]')?.textContent.trim() ?? '';
      const buttons = [...document.querySelectorAll('app-image-search-panel button')];
      const img = document.querySelector('app-proposal-document figure.native-media img');
      return { status, disabled: buttons.length > 0 && buttons.every(button => button.getAttribute('aria-disabled') === 'true'), img: Boolean(img && img.naturalWidth > 0), caption: Boolean(document.querySelector('app-proposal-document .rewriting-caption')) };`)
        .then(now => { if (now.status === 'Ищу похожие изображения…') seen.status = true; if (now.disabled) seen.disabled = true; if (now.caption) seen.caption = true; if (now.status && !now.img) seen.imageStayed = false; }).catch(() => {}); }, 120);
    let turn;
    try { turn = await lastTurnEnds(turnsBefore, 'the search'); } finally { clearInterval(watch); }
    need(turn.status === 'APPLIED' && turn.action === 'IMAGE_SEARCH', `the search turn is ${turn.status} ${turn.errorCode}`);
    need(turn.instruction === 'лиса зимой снег', `the turn carries the query «${turn.instruction}»`);
    need(editRequests(before).length === 1, `the page sent ${editRequests(before).length} edit requests for one search`);
    await until(async () => (await widget()).panel === null && (await widget()).variants !== null, 'the panel did not give way to the variants', 30_000);
    await sleep(500);
    const view = await widget();
    const detail = await getArtifact();
    const slot = slotOf(detail);
    const ready = slot.candidates.filter(candidate => candidate.state === 'READY');
    need(ready.length >= 2, `the search found ${ready.length} READY candidates`);
    need(view.variants.legend === 'Варианты' && view.variants.count === ready.length && view.variants.names === 1, `the variants are ${JSON.stringify([view.variants.legend, view.variants.count, view.variants.names])}`);
    need(view.variants.open === true, 'the variants are closed right after a search');
    need(detail.currentRevisionId !== first.revisionId && imageNodeAsset(detail) !== first.assetId, 'the image did not change to the first new candidate');
    const chosen = ready.findIndex(candidate => candidate.chosen);
    need(chosen >= 0 && view.variants.checked === chosen, `the checked radio is ${view.variants.checked}, the server chose ${chosen}`);
    need(view.variants.commit.disabled === true, '«Использовать это изображение» is enabled before any choice');
    need(view.variants.focusOnChecked === true, `focus after the search is ${JSON.stringify(view.focus)}, not on the checked variant`);
    need(/^Нашла \d+ (вариант|варианта|вариантов), выбран первый\.$/u.test(view.announcement),
      `the announcement says «${view.announcement}»`);
    need(view.strip?.text.startsWith('Подобрано другое изображение') && view.strip.buttons.join() === 'Оставить,Вернуть,Ещё раз', `the strip is ${JSON.stringify(view.strip)}`);
    for (const card of view.variants.cards) {
      need(card.picture && card.source !== '' && card.license !== '', `a card is incomplete: ${JSON.stringify(card)}`);
      if (card.link !== null) need(card.link.startsWith('https://') && card.rel === 'noopener noreferrer' && card.target === '_blank' && card.linkLabel.startsWith('Страница изображения: '), `a link is ${JSON.stringify(card)}`);
    }
    // Only the owner's own assets are drawn: no <img> points at a stock site.
    const foreign = await page(`return [...document.querySelectorAll('app-image-variants img, app-proposal-document figure img')].map(img => img.currentSrc).filter(src => !src.startsWith(location.origin) && !/127\\.0\\.0\\.1|localhost/u.test(src));`);
    need(foreign.length === 0, `an image is drawn from elsewhere: ${JSON.stringify(foreign)}`);
    searched = { revisionId: detail.currentRevisionId, assetId: imageNodeAsset(detail), candidates: ready.length, chosenIndex: chosen, resultRevisionId: turn.resultRevisionId };
    await shot('workshop-image-variants-1440.png');
    return { tabStops: stops, runningSeen: seen, found: ready.length, announcement: view.announcement, cards: view.variants.cards.length };
  });

  // ======================================================================================================================
  // 4. The arrow keys move the local choice and send nothing; the button commits
  // ======================================================================================================================
  let selected = null;
  await step('choose', async () => {
    const before = Date.now();
    const revisionBefore = (await getArtifact()).currentRevisionId;
    const start = (await widget()).variants.checked;
    need((await widget()).variants.focusOnChecked, 'focus is not on the checked radio before the arrow keys');
    for (let index = 0; index < 3; index++) { await press('ArrowDown'); await sleep(150); }
    await press('ArrowUp');
    await sleep(300);
    const moved = await widget();
    need(moved.variants.checked !== start && moved.variants.focusOnChecked, `the arrow keys left the choice at ${moved.variants.checked} (started at ${start}), focus ${JSON.stringify(moved.focus)}`);
    need(moved.variants.commit.disabled === false, 'the button is still disabled after the choice moved');
    need(selectionRequests(before).length === 0, `an arrow key sent ${selectionRequests(before).length} selection requests`);
    need(editRequests(before).length === 0, 'an arrow key sent an edit request');
    need((await getArtifact()).currentRevisionId === revisionBefore, 'an arrow key changed the revision on the server');
    need(moved.variants.pending === false && moved.variants.status === '', 'the group is saving while only the local choice moved');
    // The picked candidate is the checked card now (a different one from the chosen); remember it before committing.
    const detail = await getArtifact();
    const ready = slotOf(detail).candidates.filter(candidate => candidate.state === 'READY');
    const picked = ready[moved.variants.checked];
    need(picked.chosen === false, 'the local choice is the chosen candidate');
    await shot('workshop-image-choice-1440.png');
    // Tab to the button and press it: the keyboard path.
    await press('Tab');
    await sleep(150);
    // The links of the cards sit between the radios and the button: walk to it.
    for (let index = 0; index < 8 && (await focusedLabel()).text !== 'Использовать это изображение'; index++) await press('Tab');
    need((await focusedLabel()).text === 'Использовать это изображение', `Tab did not reach the button (focus ${JSON.stringify(await focusedLabel())})`);
    await press('Enter');
    let savingSeen = false;
    await until(async () => {
      const view = await widget();
      if (view.variants?.status === 'Сохраняю выбор…') savingSeen = true;
      return (await getArtifact()).currentRevisionId !== revisionBefore;
    }, 'the choice did not make a new revision', 30_000);
    await until(async () => { const view = await widget(); return view.variants !== null && view.variants.pending === false; }, 'the group stays disabled after the answer', 15_000);
    await sleep(400);
    const done = await getArtifact();
    const view = await widget();
    need(selectionRequests(before).length === 1, `${selectionRequests(before).length} selection requests for one commit`);
    need(imageNodeAsset(done) === picked.assetId, 'the image did not change to the picked candidate');
    need(slotOf(done).candidates.find(candidate => candidate.chosen)?.candidateId === picked.candidateId, 'the server did not mark the picked candidate as chosen');
    need(view.variants.checked === moved.variants.checked, 'the checked radio moved after the answer');
    need(view.variants.focusOnChecked === true, `focus after the choice is ${JSON.stringify(view.focus)}, not on the checked radio`);
    need(view.variants.commit.disabled === true, 'the button stays enabled after the choice was saved');
    selected = { revisionId: done.currentRevisionId, assetId: picked.assetId, candidateId: picked.candidateId };
    await shot('workshop-image-chosen-1440.png');
    return { savingSeen, selectionRequests: 1, arrowRequests: 0 };
  });

  // ======================================================================================================================
  // 5. «Вернуть к этой версии» (the history): the revision the search made
  // ======================================================================================================================
  await step('revert', async () => {
    await click('app-proposal-document .edit-history summary');
    await until(async () => (await widget()).history !== null && (await page(`return document.querySelector('app-proposal-document .edit-history')?.open ?? false;`)), 'the history did not open');
    const items = (await widget()).history.items;
    need(items.some(item => item.revert), `the history offers no «Вернуть к этой версии» (${JSON.stringify(items)})`);
    const index = items.findIndex((item, position) => item.revert && position === items.length - 1);
    need(index >= 0, `the last entry (the search) has no «Вернуть к этой версии»: ${JSON.stringify(items)}`);
    const point = await page(`const li = [...document.querySelectorAll('app-proposal-document .edit-history li')].at(-1);
      const node = [...li.querySelectorAll('button')].find(button => button.textContent.trim() === 'Вернуть к этой версии'); node.scrollIntoView({ block: 'center', behavior: 'instant' });
      const rect = node.getBoundingClientRect(); return { x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 };`);
    await clickAt(point);
    await until(async () => (await getArtifact()).currentRevisionId === searched.resultRevisionId, 'the revert did not move the artifact back to the search revision', 30_000);
    const detail = await getArtifact();
    need(imageNodeAsset(detail) === searched.assetId, 'the image is not the one the search chose');
    await until(async () => { const view = await widget(); return view.variants?.checked === searched.chosenIndex; }, 'the variants did not follow the revert', 15_000);
    return { revertedTo: 'search revision' };
  });

  // ======================================================================================================================
  // 6. A search that finds nothing: announced, the picture unchanged, nothing charged
  // ======================================================================================================================
  await step('no_result', async () => {
    const before = await getArtifact();
    const spentBefore = (await credits()).spent;
    await tabToSearch();
    await press('Enter');
    await until(async () => (await widget()).panel?.focusOnField === true, 'the panel did not open for the second search', 8_000);
    await insertText('лиса [[stub:image-none]]');
    await press('Tab');
    await press('Enter');
    const turn = await lastTurnEnds(before.turns.length, 'the empty search');
    need(turn.status === 'FAILED' && turn.errorCode === 'NO_RESULT', `the empty search ended ${turn.status} ${turn.errorCode}`);
    await until(async () => (await widget()).announcement === NOTHING_FOUND, `the announcement is not «${NOTHING_FOUND}»`, 20_000);
    const after = await getArtifact();
    need(after.currentRevisionId === before.currentRevisionId && imageNodeAsset(after) === imageNodeAsset(before), 'the failed search changed the image or the revision');
    const view = await widget();
    need(view.panel === null, 'the panel stays open after the failed search');
    need(view.image !== null && view.image.natural > 0, 'the image is not visible after the failed search');
    need(view.strip?.failed === true && view.strip.text.includes('Не нашлось подходящих изображений.') && view.strip.text.includes('лимит не списан'), `the strip says ${JSON.stringify(view.strip)}`);
    const usage = await credits();
    need(usage.reserved === 0 && usage.spent === spentBefore, `the failed search left reserved ${usage.reserved}, spent ${usage.spent} (was ${spentBefore})`);
    await shot('workshop-image-none-1440.png');
    return { announcement: view.announcement, charged: false };
  });

  // ======================================================================================================================
  // 7. Responsive, reduced motion
  // ======================================================================================================================
  await step('responsive', async () => {
    const result = {};
    // Close the strip of the failed search (it is a quiet note), then open the panel by click at every width.
    const geometry = () => page(`const doc = document.documentElement;
      const wide = [...document.querySelectorAll('app-proposal-document *')].filter(node => { const rect = node.getBoundingClientRect(); return rect.width > 0 && rect.right > doc.clientWidth + 1; })
        .map(node => node.tagName.toLowerCase() + '.' + String(node.className).split(' ')[0]).slice(0, 4);
      const small = [...document.querySelectorAll('app-image-variants button, app-image-variants input, app-image-variants a, app-image-search-panel button, app-image-search-panel input[type=text], app-proposal-document .media-actions button')]
        .map(node => { const rect = node.getBoundingClientRect(); return { text: (node.textContent || node.type || '').trim().slice(0, 20), w: Math.round(rect.width), h: Math.round(rect.height) }; })
        .filter(entry => entry.w > 0 && (entry.h < 43.5) && !(entry.text === 'radio'));
      return { scrollWidth: doc.scrollWidth, clientWidth: doc.clientWidth, wide, small };`);
    for (const [tag, width, height, mobile] of [['1440', 1440, 900, false], ['390', 390, 844, true], ['320', 320, 800, false]]) {
      await metrics(width, height, 1, mobile);
      await settle();
      await page(`const trigger = document.querySelector('app-proposal-document [data-focus-key^="search:"]'); trigger?.scrollIntoView({ block: 'center', behavior: 'instant' }); return true;`);
      // The picture and the credit line settle after a resize; a click that lands on a layout that is still moving is tried again.
      for (let attempt = 0; attempt < 3 && (await widget()).panel === null; attempt++) {
        await sleep(500);
        await click('app-proposal-document [data-focus-key^="search:"]');
        await sleep(300);
      }
      await until(async () => (await widget()).panel !== null, `the panel did not open at ${width}`, 8_000);
      await settle();
      const panel = await geometry();
      need(panel.scrollWidth <= panel.clientWidth, `the page overflows horizontally at ${width} px with the panel open (${panel.scrollWidth} > ${panel.clientWidth}: ${panel.wide.join(' ')})`);
      await shot(`workshop-image-panel-${tag}.png`);
      await click('app-image-search-panel button', 'Отмена');
      await until(async () => (await widget()).panel === null, 'the panel did not close with «Отмена»', 5_000);
      await until(async () => (await focusedLabel()).key?.startsWith('search:') === true, 'focus did not return to «Найти похожее» after «Отмена»', 5_000);
      await page(`document.querySelector('app-proposal-document details.variants').open = true; document.querySelector('app-proposal-document details.variants').scrollIntoView({ block: 'center', behavior: 'instant' }); return true;`);
      await settle();
      const grid = await widget();
      const columns = new Set(grid.variants.cards.map(card => card.x)).size;
      const grids = await geometry();
      need(grids.scrollWidth <= grids.clientWidth, `the page overflows horizontally at ${width} px with the variants (${grids.scrollWidth} > ${grids.clientWidth}: ${grids.wide.join(' ')})`);
      need(columns === (width >= 600 ? 2 : 1), `the variants grid has ${columns} columns at ${width} px`);
      need(grids.small.length === 0, `controls below 44 px at ${width} px: ${JSON.stringify(grids.small)}`);
      await shot(`workshop-image-variants-${tag}.png`);
      result[tag] = { overflow: false, columns };
    }
    {
      // 2x root text on a 320 px window.
      await metrics(320, 800, 1, false);
      await page(`document.documentElement.style.fontSize = '32px'; return true;`);
      await settle();
      const doubled = await geometry();
      need(doubled.scrollWidth <= doubled.clientWidth, `the page overflows at 320 px with 2x root text (${doubled.wide.join(' ')})`);
      await shot('workshop-image-variants-320-2x-text.png');
      await page(`document.documentElement.style.fontSize = ''; return true;`);
      result['320-2x'] = { overflow: false };
    }
    // Reduced motion: nothing in the image UI transitions or animates (the global rule shortens everything to .01 ms: anything above 1 ms is a finding).
    await desktop();
    await tab.call('Emulation.setEmulatedMedia', { features: [{ name: 'prefers-reduced-motion', value: 'reduce' }] });
    await settle();
    const motion = await page(`const nodes = [...document.querySelectorAll('app-proposal-document, app-proposal-document *, app-image-variants *')];
      const moving = nodes.filter(node => { const style = getComputedStyle(node);
        return style.transitionDuration.split(',').some(value => parseFloat(value) > 0.001) || style.animationName !== 'none' && style.animationDuration.split(',').some(value => parseFloat(value) > 0.001); })
        .map(node => node.tagName.toLowerCase() + '.' + String(node.className).split(' ')[0]).slice(0, 5);
      return { reduced: matchMedia('(prefers-reduced-motion: reduce)').matches, moving, running: document.getAnimations().length };`);
    need(motion.reduced === true, 'reduced motion was not emulated');
    need(motion.moving.length === 0, `elements transition or animate under reduced motion: ${JSON.stringify(motion.moving)}`);
    await tab.call('Emulation.setEmulatedMedia', { features: [] });
    result.reducedMotion = motion;
    await desktop();
    return result;
  });

  // ======================================================================================================================
  // 8. Approve, and Browse shows the attribution in the published caption
  // ======================================================================================================================
  await step('approve_and_browse', async () => {
    const detail = await getArtifact();
    const slot = slotOf(detail);
    // The backend writes «Автор · Источник · Лицензия» into the caption with the same source label as the Workshop line («Тестовый источник» for the Stub).
    const expected = [slot.attribution.author.trim(), 'Тестовый источник', slot.attribution.license].filter(Boolean).join(' · ');
    await page(`document.querySelector('app-proposal-view .proposal-actions')?.scrollIntoView({ block: 'center', behavior: 'instant' }); return true;`);
    await click('app-proposal-view .proposal-actions button', 'Одобрить и далее →');
    await until(async () => (await api('GET', sessionPath(session))).body.artifacts[0].state === 'PUBLISHED', 'the material was not approved', 45_000);
    const published = (await api('GET', sessionPath(session))).body.artifacts[0].publishedRef;
    need(published?.kind === 'ITEM', 'the approval published no material');
    await navigate(`/decks/${deck.deckId}/materials/${published.memberKey}`, tab);
    await until(() => has('app-native-document-renderer article'), 'Browse did not render the material', 25_000);
    await until(async () => (await page(`return Boolean(document.querySelector('app-native-document-renderer article figure img'));`)), 'Browse did not draw the image', 40_000);
    // The picture is lazy: bring it into view and wait until it has really loaded.
    await page(`document.querySelector('app-native-document-renderer article figure:has(img)')?.scrollIntoView({ block: 'center', behavior: 'instant' }); return true;`);
    await until(async () => (await page(`return (document.querySelector('app-native-document-renderer article figure:has(img) img')?.naturalWidth ?? 0) > 0;`)), 'the published image did not load in Browse', 30_000);
    const browse = await page(`const figure = document.querySelector('app-native-document-renderer article figure.native-media:has(img)') ?? document.querySelector('app-native-document-renderer article figure:has(img)');
      return { caption: figure?.querySelector('figcaption')?.textContent.replace(/\\s+/g, ' ').trim() ?? null, natural: figure?.querySelector('img')?.naturalWidth ?? 0,
        nodeIds: document.querySelectorAll('[data-node-id]').length, credit: document.querySelectorAll('.image-credit, .audio-caption').length };`);
    need(browse.caption !== null && browse.caption.includes(expected), `the published caption is «${browse.caption}», expected it to contain «${expected}»`);
    need(browse.natural > 0, 'the published image did not render in Browse');
    need(browse.nodeIds === 0 && browse.credit === 0, `Browse carries ${browse.nodeIds} node ids and ${browse.credit} Workshop-only lines`);
    await shot('browse-image-attribution-1440.png');
    return { caption: browse.caption };
  });

  out.durationMs = Date.now() - startedAt;
  return out;
}
