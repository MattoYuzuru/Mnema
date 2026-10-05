// Real-browser check of web research and «Источники» in the Workshop (#299, AI-18) against the real Learning API with the Stub text provider
// and the Stub web search (`--generation`; never a real provider, no key, no network: the results live on https://example.org/stub/research/<n>
// and no page is ever opened). Runs on the signed-in account's tab, in a deck of its own. Node 24 built-ins only.
//
// Driven for real: the composer's «Ещё настройки» (the «Проверять факты» box disabled with its reason on «Кратко», enabled on «Средне» and
// «Подробно», the hint with the request cap and the credits), a «Подробно» material with fact checking created with Enter, the Workshop's «Ищу источники…» while RESEARCH runs
// (recorded when the page is on time; the Stub is faster than the lazy Workshop chunk), the proposal with the «Источники» section (numbered links that open in a new tab, rel noopener noreferrer,
// announced) and the line «Проверено по N источникам», the ledger (`/api/usage` before and after: the debit is the text plus 5 credits per
// paid request), a `[[stub:search-down]]` request that still produces the material with no sources and no research line, approval and Browse
// (the sources section and its links), 1440/390/320 px states, the keyboard path and reduced motion. The state the page is compared with is read
// through the authenticated API. A broken step is a finding for the product, never something to work around: it throws, a failure screenshot and
// a .txt are written, and the run fails with the step name.
import { writeFile } from 'node:fs/promises';
import { join } from 'node:path';

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const PROMPT = 'Рыжая лиса зимой: подробный материал с проверкой фактов';
const DOWN_PROMPT = 'Рыжая лиса зимой: подробный материал [[stub:search-down]]';
const PLAIN_PROMPT = 'Рыжая лиса зимой: подробный материал без проверки';
const STUB_URL = 'https://example.org/stub/research/';
const CREDITS_PER_REQUEST = 5;
const KEYS = { Enter: ['Enter', 'Enter', 13], Escape: ['Escape', 'Escape', 27], Tab: ['Tab', 'Tab', 9], ArrowRight: ['ArrowRight', 'ArrowRight', 39], ArrowLeft: ['ArrowLeft', 'ArrowLeft', 37] };
const HINTS = {
  SHORT: 'Недоступно на «Кратко»: выберите «Средне» или «Подробно».',
  MEDIUM: /^Поищу в сети \(до 2 запросов\) и сошлюсь на источники\. Стоит до 10 кредитов\.$/u,
  DETAILED: /^Поищу в сети \(до 6 запросов\) и сошлюсь на источники\. Стоит до 30 кредитов\.$/u
};

export async function runWorkshopResearch(ctx, h) {
  const { tab, config, SafeFailure, until, navigate, saveScreenshot, setStep } = ctx;
  const { api, page, has, need, settle, metrics, desktop, awaitCapability, activeSessions, sessionPath: baseSessionPath, location } = h;
  const out = { stub: true, stages: {}, screenshots: [] };
  const startedAt = Date.now();

  const failureShot = async name => { try { await saveScreenshot(`failure-research-${name}.png`, tab); } catch { /* the original failure is the verdict */ } };
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
    setStep(`research_${name}`);
    try { const result = await body(); out.stages[name] = result ?? true; return result; } catch (error) {
      await failureShot(name);
      let held = '';
      try {
        const detail = await getArtifact();
        held = ` | server: state ${detail.state}, errorCode ${detail.errorCode}, research ${JSON.stringify(detail.research)}, revisions ${detail.revisions.length}`;
      } catch (reading) { held = ` | server: unreadable (${String(reading?.message ?? reading).slice(0, 60)})`; }
      await writeFile(join(config.output, `failure-research-${name}.txt`),
        (error instanceof SafeFailure ? error.message : `driver: ${String(error?.message ?? error).slice(0, 160)}`) + held + '\n').catch(() => {});
      throw error;
    }
  };
  const shot = async name => { await saveScreenshot(name, tab); out.screenshots.push(name); };

  // ---- low-level input -------------------------------------------------------------------------------------------------
  const press = async name => {
    const [key, code, virtualKeyCode] = KEYS[name];
    const event = { key, code, windowsVirtualKeyCode: virtualKeyCode, nativeVirtualKeyCode: virtualKeyCode, modifiers: 0 };
    await tab.call('Page.bringToFront');
    await tab.call('Input.dispatchKeyEvent', name === 'Enter'
      ? { type: 'keyDown', text: '\r', unmodifiedText: '\r', ...event } : { type: 'rawKeyDown', ...event });
    await tab.call('Input.dispatchKeyEvent', { type: 'keyUp', ...event });
  };
  const insertText = async text => { await tab.call('Page.bringToFront'); await tab.call('Input.insertText', { text }); };
  const mouse = (type, point) => tab.call('Input.dispatchMouseEvent', { type, x: point.x, y: point.y, button: type === 'mouseMoved' ? 'none' : 'left', clickCount: 1 });
  const clickAt = async point => {
    await tab.call('Page.bringToFront');
    await mouse('mouseMoved', point); await mouse('mousePressed', point); await mouse('mouseReleased', point);
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

  // ---- the page --------------------------------------------------------------------------------------------------------
  /** The «Проверять факты» box of the composer: its state, its hint and what describes it. */
  const factBox = () => page(`const text = value => (value ?? '').replace(/\\s+/gu, ' ').replaceAll('\\u00a0', ' ').trim();
    const settings = document.querySelector('app-generation-settings');
    const label = [...(settings?.querySelectorAll('label.check') ?? [])].find(node => text(node.textContent) === 'Проверять факты');
    if (!label) return null;
    const input = label.querySelector('input');
    const hint = document.getElementById(input.getAttribute('aria-describedby') ?? '');
    const active = document.activeElement;
    return { checked: input.checked, disabled: input.disabled, hint: text(hint?.textContent), describedBy: Boolean(hint), focused: active === input,
      effort: text(settings.querySelector('input[type=radio]:checked')?.closest('label')?.textContent) };`);
  /** The sources section and the research line of the proposal (or of the material in Browse when `scope` says so). */
  const sourcesOf = scope => page(`const text = value => (value ?? '').replace(/\\s+/gu, ' ').replaceAll('\\u00a0', ' ').trim();
    const root = document.querySelector(args[0]);
    if (!root) return null;
    const heading = [...root.querySelectorAll('h1, h2, h3, h4, h5, h6, [role=heading]')].find(node => text(node.textContent) === 'Источники') ?? null;
    const links = [...root.querySelectorAll('a[href^="${STUB_URL}"]')].map(link => { const item = link.closest('li');
      return { href: link.getAttribute('href'), rel: link.getAttribute('rel'), target: link.getAttribute('target'), label: text(link.textContent),
        announced: Boolean(link.querySelector('.native-visually-hidden')) && text(link.textContent).includes('откроется в новой вкладке'),
        inList: item ? item.parentElement.tagName.toLowerCase() : null, itemText: item ? text(item.textContent).slice(0, 80) : null,
        h: Math.round(link.getBoundingClientRect().height), w: Math.round(link.getBoundingClientRect().width) }; });
    const after = heading ? [...root.querySelectorAll('h1, h2, h3, h4, h5, h6, [role=heading], a[href^="${STUB_URL}"]')] : [];
    const afterHeading = heading ? after.slice(after.indexOf(heading) + 1).filter(node => node.tagName === 'A').length : 0;
    return { heading: heading ? { tag: heading.tagName.toLowerCase(), text: text(heading.textContent) } : null, links, linksAfterHeading: afterHeading,
      line: text(root.querySelector('.research-line')?.textContent), text: text(root.textContent).length };`, scope);
  const credits = async () => {
    const usage = await api('GET', '/api/usage');
    need(usage.status === 200, `GET usage answered ${usage.status}`);
    return usage.body.credits;
  };
  /** Credits used once the holds of the last material are settled (nothing reserved any more). */
  const settledUsed = async label => {
    let held = null;
    await until(async () => { held = await credits(); return held.reserved === 0; }, `${label}: credits are still reserved`, 60_000);
    return held.used;
  };
  const focusedLabel = () => page(`const active = document.activeElement; return { text: (active?.textContent ?? '').replace(/\\s+/g, ' ').trim(), href: active?.getAttribute?.('href') ?? null, tag: active?.tagName?.toLowerCase() };`);
  const createDeck = async title => {
    const created = await api('POST', '/api/decks', { commandId: crypto.randomUUID(), metadata: { title, description: '' } });
    need(created.status === 201, `POST deck answered ${created.status}`);
    const id = created.body.deck?.deckId ?? created.body.deckId;
    need(id, 'the created deck has no id');
    return { deckId: id };
  };
  const chooseEffort = async label => {
    await click('app-generation-settings app-segmented-choice label', label);
    await until(async () => (await factBox())?.effort === label, `the effort did not become «${label}»`, 5_000);
  };
  const openMore = async () => {
    const open = await page(`return document.querySelector('app-generation-settings details.more')?.open ?? false;`);
    if (!open) await click('app-generation-settings summary', 'Ещё настройки');
    await until(() => page(`return document.querySelector('app-generation-settings details.more')?.open ?? false;`), '«Ещё настройки» did not open');
  };
  /** Types the prompt and presses Enter in the composer of the current deck; leaves the Workshop of the new session open. */
  const submit = async prompt => {
    need(await page(`const textarea = document.querySelector('app-generation-composer textarea'); textarea.focus(); return document.activeElement === textarea;`), 'the prompt field could not take focus');
    await insertText(prompt);
    await press('Enter');
    await until(async () => /\/workshop\/[0-9a-f-]{36}$/u.test(await location()), 'Enter did not open the Workshop', 25_000);
    session = (await location()).match(/\/workshop\/([0-9a-f-]{36})$/u)[1];
    const detail = (await api('GET', sessionPath(session))).body;
    artifactId = detail.artifacts[0]?.artifactId ?? null;
    need(artifactId !== null, 'the session lists no artifact');
    return detail;
  };
  const proposed = async label => {
    await until(async () => ['PROPOSED', 'FAILED'].includes((await getArtifact()).state), `${label}: the material did not settle`, 150_000);
    const detail = await getArtifact();
    need(detail.state === 'PROPOSED', `${label}: the material is ${detail.state} ${detail.errorCode}`);
    await until(() => has('app-proposal-document'), `${label}: the Workshop did not draw the material`, 30_000);
    await settle();
    return detail;
  };

  // ======================================================================================================================
  // 0. The fixture and the capability
  // ======================================================================================================================
  await desktop();
  await awaitCapability();
  for (const active of await activeSessions()) {
    const gone = await api('DELETE', baseSessionPath(active.sessionId));
    need([204, 404].includes(gone.status), `deleting an earlier session answered ${gone.status}`);
  }
  await step('fixture', async () => {
    deck = await createDeck('Проверка фактов: источники');
    const capabilities = await api('GET', '/api/capabilities');
    need(capabilities.status === 200 && capabilities.body?.webSearch?.available === true,
      `GET /api/capabilities does not report webSearch available (Stub web search): ${JSON.stringify(capabilities.body?.webSearch ?? null)}`);
    return { deck: 'own', webSearch: capabilities.body.webSearch, creditsBefore: await credits() };
  });

  // ======================================================================================================================
  // 1. A control: «Подробно» without fact checking, to know what the text alone costs
  // ======================================================================================================================
  let textOnly = null;
  await step('control_text_cost', async () => {
    await navigate(`/decks/${deck.deckId}/materials/new`, tab);
    await until(() => has('app-generation-composer textarea'), 'the composer did not open', 25_000);
    await openMore();
    await chooseEffort('Подробно');
    const box = await factBox();
    need(box.checked === false, 'fact checking starts checked');
    const before = await settledUsed('before the control');
    const detail = await submit(PLAIN_PROMPT);
    need(detail.spec.settings.factCheck === false, `the stored spec says factCheck ${detail.spec.settings.factCheck}`);
    const material = await proposed('the control material');
    need(material.research === null || material.research.results.length === 0, `an unresearched material carries research ${JSON.stringify(material.research)}`);
    const after = await settledUsed('after the control');
    textOnly = after - before;
    need(textOnly > 0, `the control material cost ${textOnly} credits`);
    return { textOnlyCredits: textOnly };
  });

  // ======================================================================================================================
  // 2. The composer: the box on every effort, the hint with the cap and the credits
  // ======================================================================================================================
  await step('composer_box', async () => {
    await navigate(`/decks/${deck.deckId}/materials/new`, tab);
    await until(() => has('app-generation-composer textarea'), 'the composer did not open', 25_000);
    await openMore();
    const seen = {};
    // Behind «Ещё настройки», off by default; «Кратко» disables it and says why.
    await chooseEffort('Кратко');
    seen.short = await factBox();
    need(seen.short !== null, 'the «Проверять факты» box is absent although web search is available');
    need(seen.short.disabled === true && seen.short.checked === false && seen.short.hint === HINTS.SHORT && seen.short.describedBy === true, `«Кратко»: ${JSON.stringify(seen.short)}`);
    await chooseEffort('Средне');
    seen.medium = await factBox();
    need(seen.medium.disabled === false && HINTS.MEDIUM.test(seen.medium.hint), `«Средне»: ${JSON.stringify(seen.medium)}`);
    await chooseEffort('Подробно');
    seen.detailed = await factBox();
    need(seen.detailed.disabled === false && seen.detailed.checked === false && HINTS.DETAILED.test(seen.detailed.hint), `«Подробно»: ${JSON.stringify(seen.detailed)}`);
    await shot('research-composer-hint-1440.png');
    // A click on the disabled box changes nothing.
    await chooseEffort('Кратко');
    const point = await centreOf('app-generation-settings label.check', 'Проверять факты');
    need(point !== null, 'the disabled box has no place on the page');
    await clickAt(point === 'disabled' ? { x: 0, y: 0 } : point);
    need((await factBox()).checked === false, 'a click turned on the box that «Кратко» disables');
    await shot('research-composer-short-1440.png');
    await chooseEffort('Подробно');
    return { short: seen.short.hint, medium: seen.medium.hint, detailed: seen.detailed.hint };
  });

  // ======================================================================================================================
  // 3. «Подробно» with fact checking, Enter; «Ищу источники…» while RESEARCH runs
  // ======================================================================================================================
  let researched = null;
  let researchStatus = null;
  await step('create_and_research', async () => {
    // Space on the box by keyboard: focus it, press Space (a real key event), the box is checked.
    await page(`const input = [...document.querySelectorAll('app-generation-settings label.check')].find(node => node.textContent.trim() === 'Проверять факты').querySelector('input'); input.scrollIntoView({ block: 'center', behavior: 'instant' }); input.focus(); return document.activeElement === input;`);
    await tab.call('Input.dispatchKeyEvent', { type: 'keyDown', key: ' ', code: 'Space', text: ' ', unmodifiedText: ' ', windowsVirtualKeyCode: 32, nativeVirtualKeyCode: 32, modifiers: 0 });
    await tab.call('Input.dispatchKeyEvent', { type: 'keyUp', key: ' ', code: 'Space', windowsVirtualKeyCode: 32, nativeVirtualKeyCode: 32, modifiers: 0 });
    await until(async () => (await factBox()).checked === true, 'Space on the focused box did not check it', 5_000);
    const before = await settledUsed('before the researched material');
    // The text «Ищу источники…» is brief with the Stub: a document-wide observer, installed before the turn starts, records every moment it was on the page.
    await page(`window.__researchSeen = { text: false, pager: false, status: false };
      const look = () => { const body = document.body.textContent;
        if (body.includes('Ищу источники…')) window.__researchSeen.text = true;
        if (body.includes('ищу источники')) window.__researchSeen.pager = true; };
      window.__researchObserver?.disconnect();
      window.__researchObserver = new MutationObserver(look);
      window.__researchObserver.observe(document.body, { childList: true, subtree: true, characterData: true });
      return true;`);
    const detail = await submit(PROMPT);
    need(detail.spec.settings.factCheck === true, `the stored spec says factCheck ${detail.spec.settings.factCheck}`);
    need(detail.spec.settings.effort === 'DETAILED', `the stored effort is ${detail.spec.settings.effort}`);
    const material = await proposed('the researched material');
    researchStatus = await page(`window.__researchObserver?.disconnect(); return window.__researchSeen;`);
    // The Stub search answers within milliseconds and the Workshop route is a lazy chunk, so the page usually opens after RESEARCH has ended: the
    // wording is therefore recorded as evidence, not demanded (the component specs hold it; a slow Stub marker would make it a gate).
    need(material.research !== null && material.research.requests >= 1 && material.research.requests <= 6, `research.requests is ${JSON.stringify(material.research?.requests)}`);
    need(material.research.results.length >= 1 && material.research.results.every((result, index) => result.n === index + 1 && result.url.startsWith(STUB_URL) && result.provider === 'STUB'),
      `the research results are ${JSON.stringify(material.research.results)}`);
    const after = await settledUsed('after the researched material');
    const debit = after - before;
    const requests = material.research.requests;
    // The ledger holds the text and CREDITS_PER_REQUEST per paid request; «roughly»: the text part may differ by a few credits.
    need(debit >= requests * CREDITS_PER_REQUEST && Math.abs(debit - (textOnly + requests * CREDITS_PER_REQUEST)) <= CREDITS_PER_REQUEST,
      `the debit is ${debit} credits for ${requests} requests; the text alone cost ${textOnly} (expected about ${textOnly + requests * CREDITS_PER_REQUEST})`);
    researched = { session, artifactId, revisionId: material.currentRevisionId, requests, sources: material.research.results.length, debit };
    return { researchSeen: researchStatus, requests, sources: researched.sources, debit, textOnly };
  });

  // ======================================================================================================================
  // 4. The proposal: «Источники», numbered links, «Проверено по N источникам»
  // ======================================================================================================================
  await step('proposal_sources', async () => {
    const detail = await getArtifact();
    const count = detail.research.results.length;
    await until(async () => (await sourcesOf('app-proposal-document'))?.heading !== null, 'the proposal has no «Источники» heading', 30_000);
    await settle();
    const view = await sourcesOf('app-proposal-view');
    need(view.heading.text === 'Источники', `the heading says «${view.heading.text}»`);
    need(view.links.length === count && view.linksAfterHeading === count, `the proposal draws ${view.links.length} source links (${view.linksAfterHeading} after the heading), the server found ${count}`);
    view.links.forEach((link, index) => {
      need(link.href === detail.research.results[index].url, `link ${index + 1} points at ${link.href}, the server has ${detail.research.results[index].url}`);
      need(link.target === '_blank' && link.rel === 'noopener noreferrer', `link ${index + 1} opens with target ${link.target} rel ${link.rel}`);
      need(link.announced, `link ${index + 1} does not say it opens in a new tab: «${link.label}»`);
      need(link.inList === 'ol' || /^\[?\d+/u.test(link.itemText ?? ''), `link ${index + 1} is not numbered: ${link.inList} «${link.itemText}»`);
    });
    const word = count % 10 === 1 && count % 100 !== 11 ? 'источнику' : 'источникам';
    need(view.line === `Проверено по ${count} ${word}`, `the research line says «${view.line}», expected «Проверено по ${count} ${word}»`);
    // The citation in the text is an inline link to one of the sources.
    const cited = await page(`return [...document.querySelectorAll('app-proposal-document p a[href^="${STUB_URL}"]')].map(link => link.textContent.replace(/\\s+/g, ' ').trim());`);
    const readSummary = () => page(`return (document.querySelector('section.workshop .summary')?.textContent ?? '').replace(/\\s+/g, ' ').trim();`);
    const firstSummary = await readSummary();
    let summary = firstSummary;
    await until(async () => { summary = await readSummary(); return !/пишется|ищу источники/iu.test(summary); }, `the Workshop summary says «${summary}» for a settled material and does not catch up`, 30_000);
    await shot('research-proposal-sources-1440.png');
    return { summary, summaryRightAfterProposed: firstSummary, count, line: view.line, listTag: view.links[0]?.inList, cited };
  });

  // ======================================================================================================================
  // 5. The keyboard path: Tab reaches the source links, Enter opens one in a new tab (no network: the tab is stopped before it loads)
  // ======================================================================================================================
  await step('keyboard', async () => {
    await page(`const heading = [...document.querySelectorAll('app-proposal-document h1, app-proposal-document h2, app-proposal-document h3')].find(node => node.textContent.trim() === 'Источники');
      heading.scrollIntoView({ block: 'center', behavior: 'instant' });
      const previous = [...document.querySelectorAll('app-proposal-document a, app-proposal-document button')].filter(node => heading.compareDocumentPosition(node) & Node.DOCUMENT_POSITION_PRECEDING).at(-1);
      (previous ?? document.body).focus?.(); return true;`);
    // A sequential Tab walk from just above the heading must meet every source link, in order.
    const hrefs = [];
    for (let stop = 0; stop < 40 && hrefs.length < 3; stop++) {
      await press('Tab');
      const now = await focusedLabel();
      if (now.href?.startsWith(STUB_URL) && !hrefs.includes(now.href)) hrefs.push(now.href);
    }
    const expected = (await getArtifact()).research.results.map(result => result.url);
    need(hrefs.length >= Math.min(3, expected.length) && hrefs.every((href, index) => href === expected[index]), `Tab reached ${JSON.stringify(hrefs)}, expected the first of ${JSON.stringify(expected)} in order`);
    const focus = await page(`const active = document.activeElement; const style = getComputedStyle(active);
      return { outline: style.outlineStyle, width: parseFloat(style.outlineWidth), shadow: style.boxShadow !== 'none' };`);
    need((focus.outline !== 'none' && focus.width > 0) || focus.shadow, `the focused source link has no visible focus mark: ${JSON.stringify(focus)}`);
    return { tabbed: hrefs };
  });

  // ======================================================================================================================
  // 6. Search is down: the material is still written, with no sources and no research line
  // ======================================================================================================================
  await step('search_down', async () => {
    await navigate(`/decks/${deck.deckId}/materials/new`, tab);
    await until(() => has('app-generation-composer textarea'), 'the composer did not open', 25_000);
    await openMore();
    await chooseEffort('Подробно');
    await click('app-generation-settings label.check', 'Проверять факты');
    need((await factBox()).checked === true, 'the click did not check «Проверять факты»');
    const detail = await submit(DOWN_PROMPT);
    need(detail.spec.settings.factCheck === true, 'the stored spec lost factCheck');
    const material = await proposed('the material of a down search');
    need(material.state === 'PROPOSED', `the material is ${material.state}`);
    need(material.research === null || material.research.results.length === 0, `a down search left sources ${JSON.stringify(material.research)}`);
    await settle();
    const view = await sourcesOf('app-proposal-view');
    need(view.heading === null && view.links.length === 0, `the proposal shows sources although none were found: ${JSON.stringify(view.heading)} ${view.links.length}`);
    need(view.line === '', `the research line says «${view.line}» with no sources`);
    need(view.text > 40, 'the material of a down search is empty');
    const notice = await page(`return (document.querySelector('section.workshop .notice')?.textContent ?? '').replace(/\\s+/g, ' ').trim();`);
    await shot('research-search-down-1440.png');
    return { state: material.state, requests: material.research?.requests ?? null, notice };
  });

  // ======================================================================================================================
  // 7. Responsive and reduced motion on the researched material
  // ======================================================================================================================
  await step('responsive', async () => {
    // Back to the researched session.
    session = researched.session;
    artifactId = researched.artifactId;
    await navigate(`/decks/${deck.deckId}/workshop/${session}`, tab);
    await until(async () => (await sourcesOf('app-proposal-view'))?.heading !== null, 'the researched proposal did not come back', 40_000);
    const result = {};
    const geometry = () => page(`const doc = document.documentElement;
      const wide = [...document.querySelectorAll('app-proposal-view *')].filter(node => { const rect = node.getBoundingClientRect(); return rect.width > 0 && rect.right > doc.clientWidth + 1; })
        .map(node => node.tagName.toLowerCase() + '.' + String(node.className).split(' ')[0]).slice(0, 4);
      const small = [...document.querySelectorAll('app-proposal-view a[href^="${STUB_URL}"]')].map(node => { const rect = node.getBoundingClientRect(); return { text: node.textContent.trim().slice(0, 20), w: Math.round(rect.width), h: Math.round(rect.height) }; })
        .filter(entry => entry.w > 0 && entry.h < 24);
      return { scrollWidth: doc.scrollWidth, clientWidth: doc.clientWidth, wide, small };`);
    for (const [tag, width, height, mobile] of [['1440', 1440, 900, false], ['390', 390, 844, true], ['320', 320, 800, false]]) {
      await metrics(width, height, 1, mobile);
      await settle();
      await page(`const heading = [...document.querySelectorAll('app-proposal-view h1, app-proposal-view h2, app-proposal-view h3')].find(node => node.textContent.trim() === 'Источники'); heading?.scrollIntoView({ block: 'center', behavior: 'instant' }); return true;`);
      await settle();
      const facts = await geometry();
      need(facts.scrollWidth <= facts.clientWidth, `the page overflows horizontally at ${width} px (${facts.scrollWidth} > ${facts.clientWidth}: ${facts.wide.join(' ')})`);
      need(facts.small.length === 0, `source links are too short at ${width} px: ${JSON.stringify(facts.small)}`);
      await shot(`research-proposal-sources-${tag}.png`);
      result[tag] = { overflow: false };
    }
    {
      await metrics(320, 800, 1, false);
      await page(`document.documentElement.style.fontSize = '32px'; return true;`);
      await settle();
      const doubled = await geometry();
      need(doubled.scrollWidth <= doubled.clientWidth, `the page overflows at 320 px with 2x root text (${doubled.wide.join(' ')})`);
      await shot('research-proposal-sources-320-2x-text.png');
      await page(`document.documentElement.style.fontSize = ''; return true;`);
      result['320-2x'] = { overflow: false };
    }
    await desktop();
    await tab.call('Emulation.setEmulatedMedia', { features: [{ name: 'prefers-reduced-motion', value: 'reduce' }] });
    await settle();
    const motion = await page(`const nodes = [...document.querySelectorAll('app-proposal-view, app-proposal-view *')];
      const moving = nodes.filter(node => { const style = getComputedStyle(node);
        return style.transitionDuration.split(',').some(value => parseFloat(value) > 0.001) || style.animationName !== 'none' && style.animationDuration.split(',').some(value => parseFloat(value) > 0.001); })
        .map(node => node.tagName.toLowerCase() + '.' + String(node.className).split(' ')[0]).slice(0, 5);
      return { reduced: matchMedia('(prefers-reduced-motion: reduce)').matches, moving };`);
    need(motion.reduced === true, 'reduced motion was not emulated');
    need(motion.moving.length === 0, `elements transition or animate under reduced motion: ${JSON.stringify(motion.moving)}`);
    await tab.call('Emulation.setEmulatedMedia', { features: [] });
    result.reducedMotion = motion;
    await desktop();
    return result;
  });

  // ======================================================================================================================
  // 8. Approve, and Browse shows the sources section and its links
  // ======================================================================================================================
  await step('approve_and_browse', async () => {
    const detail = await getArtifact();
    const urls = detail.research.results.map(result => result.url);
    await page(`document.querySelector('app-proposal-view .proposal-actions')?.scrollIntoView({ block: 'center', behavior: 'instant' }); return true;`);
    await click('app-proposal-view .proposal-actions button', 'Одобрить и далее →');
    await until(async () => (await api('GET', sessionPath(session))).body.artifacts[0].state === 'PUBLISHED', 'the material was not approved', 45_000);
    const published = (await api('GET', sessionPath(session))).body.artifacts[0].publishedRef;
    need(published?.kind === 'ITEM', 'the approval published no material');
    await navigate(`/decks/${deck.deckId}/materials/${published.memberKey}`, tab);
    await until(() => has('app-native-document-renderer article'), 'Browse did not render the material', 25_000);
    await until(async () => (await sourcesOf('app-native-document-renderer article'))?.heading !== null, 'Browse shows no «Источники» heading', 25_000);
    const view = await sourcesOf('app-native-document-renderer article');
    need(view.links.length === urls.length && view.links.every((link, index) => link.href === urls[index]), `Browse links are ${JSON.stringify(view.links.map(link => link.href))}, the proposal had ${JSON.stringify(urls)}`);
    need(view.links.every(link => link.target === '_blank' && link.rel === 'noopener noreferrer' && link.announced), `a Browse link does not open safely: ${JSON.stringify(view.links[0])}`);
    need(view.line === '', 'Browse carries the Workshop-only research line');
    await shot('browse-research-sources-1440.png');
    return { links: view.links.length };
  });

  out.durationMs = Date.now() - startedAt;
  return out;
}
