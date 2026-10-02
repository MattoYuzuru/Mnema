// Real-browser check of the profile's «ИИ-бюджет» block (#281): the shared usage meter fed by the REAL `GET /api/usage`.
// Runs with `--authoring`, last of the authoring scenarios, on the signed-in account's tab. Node 24 built-ins only.
//
// Nothing is stubbed. The scenario opens `/profile`, fetches `/api/usage` with the page's own bearer and asserts that the
// text the page shows says what the API says (plan, percent, renewal date in ru-RU / Europe/Moscow, locked share, weekly
// ticks), that the bar is `aria-hidden`, and that nothing overflows horizontally at 390 and 320 CSS px (DPR 2). It then
// repeats what a notification link does: a navigation to `/profile#ai-budget` while the profile is already open (the same
// document, only the fragment changes) and one from a fresh load. In both the heading must take focus and be in view.
// Numbers are never fixed: whatever the earlier flows consumed, the page must agree with the API.
// A broken step is a finding for the product, never something to work around: it throws, a failure screenshot is written and
// the run fails with the step name.
import { writeFile } from 'node:fs/promises';
import { join } from 'node:path';

const NBSP = '\u00a0';
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const PLAN_NAMES = { FREE: 'Free', PLUS: 'Plus', PRO: 'Pro', MAX: 'Max' };

/** «5 октября» in the account's calendar zone, formatted by the harness's own Intl (not by the page). */
const moscowDay = instant => new Intl.DateTimeFormat('ru-RU', { day: 'numeric', month: 'long', timeZone: 'Europe/Moscow' })
  .format(Date.parse(instant));
/** Rounded half up, clamped to 0..100: the contract's `percentUsed` rule. */
const percentOf = (part, whole) => whole > 0 ? Math.min(100, Math.max(0, Math.floor(part / whole * 100 + 0.5))) : 0;

export async function runUsage(ctx) {
  const { tab, config, record, SafeFailure, until, exists, navigate, saveScreenshot, setStep, bearer } = ctx;
  const need = (value, label) => { if (!value) throw new SafeFailure(label); };
  const page = (body, ...args) => tab.callFunction(`async function(...args) { ${body} }`, args);
  const metrics = (width, height, scale, mobile) =>
    tab.call('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: scale, mobile });
  const reducedMotion = value => tab.call('Emulation.setEmulatedMedia',
    { features: value ? [{ name: 'prefers-reduced-motion', value: 'reduce' }] : [] });

  const failureShot = async name => {
    try { await saveScreenshot(`failure-usage-${name}.png`, tab); } catch { /* the original failure is the verdict */ }
  };
  const stage = async (name, body) => {
    setStep(`usage_${name}`);
    try { return await body(); } catch (error) {
      await failureShot(name);
      await writeFile(join(config.output, `failure-usage-${name}.txt`),
        (error instanceof SafeFailure ? error.message : `driver: ${String(error?.message ?? error).slice(0, 160)}`) + '\n').catch(() => {});
      throw error;
    }
  };

  /** The real API answer, fetched with the page's own bearer (no cookie). */
  const serverUsage = async () => {
    const result = await page(`const response = await fetch(args[0] + '/api/usage',
      { credentials: 'omit', headers: { Authorization: args[1] } });
      return { status: response.status, cache: response.headers.get('Cache-Control'),
        body: response.ok ? await response.json() : null };`, config.frontend, 'Bearer ' + bearer);
    need(result.status === 200 && result.body !== null, 'GET /api/usage is not available from the real API');
    need((result.cache ?? '').includes('no-store'), 'GET /api/usage is cacheable');
    return result.body;
  };

  /** What a reader sees in the block. */
  const blockState = () => page(`
    const section = document.querySelector('section#ai-budget');
    if (!section) return null;
    const heading = section.querySelector('#ai-budget-heading');
    const meter = section.querySelector('app-usage-meter');
    const rect = element => { const r = element.getBoundingClientRect();
      return { left: r.left, right: r.right, top: r.top, bottom: r.bottom, width: r.width, height: r.height }; };
    return { labelledBy: section.getAttribute('aria-labelledby'), headingText: heading?.textContent.trim() ?? null,
      headingTabindex: heading?.getAttribute('tabindex') ?? null, headingRect: heading ? rect(heading) : null,
      sectionRect: rect(section), status: section.querySelector('[role=status]')?.textContent.trim() ?? null,
      plan: section.querySelector('.plan')?.textContent.trim() ?? null,
      label: meter?.querySelector('.label')?.textContent.trim() ?? null,
      summary: meter?.querySelector('.summary')?.textContent ?? null,
      barHidden: meter?.querySelector('.bar')?.getAttribute('aria-hidden') ?? null,
      barRole: meter?.querySelector('.bar')?.getAttribute('role') ?? null,
      barTextContent: meter?.querySelector('.bar')?.textContent.trim() ?? null,
      ticks: meter?.querySelectorAll('.bar .tick').length ?? -1,
      locked: Boolean(meter?.querySelector('.bar .locked')), reserved: Boolean(meter?.querySelector('.bar .reserved')),
      usedWidth: meter?.querySelector('.bar .used')?.style.inlineSize ?? null,
      counters: [...section.querySelectorAll('.fair-use li')].map(item => item.textContent),
      alerts: section.querySelectorAll('[role=alert]').length,
      activeId: document.activeElement?.id ?? '', activeIsBody: document.activeElement === document.body,
      innerWidth, innerHeight, scrollY, scrollWidth: document.documentElement.scrollWidth,
      clientWidth: document.documentElement.clientWidth, dpr: devicePixelRatio };`);
  const waitForBlock = async () => {
    await until(() => exists('section#ai-budget app-usage-meter .summary', tab), 'the «ИИ-бюджет» block did not render the meter');
  };

  /** What the API says must be what the text says. */
  const assertConsistent = (label, state, usage) => {
    const { credits, weeklyUnlock, fairUse } = usage;
    need(state.labelledBy === 'ai-budget-heading' && state.headingText === 'ИИ-бюджет' && state.headingTabindex === '-1',
      `${label}: the section is not labelled by its «ИИ-бюджет» heading`);
    need(state.alerts === 0 && state.status === null, `${label}: the block shows an error or a pending status`);
    need(state.plan === `Тариф ${PLAN_NAMES[usage.plan]}`, `${label}: plan text «${state.plan}» differs from the API plan ${usage.plan}`);
    need(typeof state.label === 'string' && state.label.startsWith('ИИ в '), `${label}: the meter label is not «ИИ в <месяце>»`);
    const percent = percentOf(credits.used + credits.reserved, credits.total);
    need(percent === credits.percentUsed, `${label}: the API's percentUsed disagrees with its own credits`);
    const sentence = state.summary ?? '';
    need(sentence.startsWith(`Использовано ${percent}${NBSP}%`), `${label}: the sentence does not say ${percent} % used`);
    need(credits.reserved > 0 === sentence.includes('зарезервировано'), `${label}: reserved share and its sentence disagree`);
    const materials = Math.floor(credits.remaining / 10);
    need(sentence.includes(`хватит на ≈${NBSP}${materials}${NBSP}`), `${label}: «≈ N материалов» is not ${materials}`);
    need(sentence.includes(`обновится ${moscowDay(usage.period.end)}`), `${label}: the renewal date is not ${moscowDay(usage.period.end)} (ru-RU, Europe/Moscow)`);
    need(!/кредит/iu.test(sentence) && !/кредит/iu.test(state.plan), `${label}: credits are shown as a unit`);
    need(state.barHidden === 'true' && state.barRole === null && state.barTextContent === '', `${label}: the bar is not a decorative aria-hidden element`);
    const locked = credits.total - Math.min(credits.total, credits.unlocked);
    need(state.locked === locked > 0, `${label}: locked part of the bar disagrees with the API`);
    if (locked > 0) {
      const next = weeklyUnlock?.nextUnlockAt ? ` ${moscowDay(weeklyUnlock.nextUnlockAt)}` : ' позже';
      need(sentence.includes(`ещё ${percentOf(locked, credits.total)}${NBSP}% откроется${next}`), `${label}: the locked share or next unlock date is wrong`);
    } else need(!sentence.includes('откроется'), `${label}: the text announces an unlock that is not pending`);
    const expectedTicks = weeklyUnlock === null || credits.unlocked >= credits.total ? 0
      : new Set(weeklyUnlock.portions.map((_, index) => weeklyUnlock.portions.slice(0, index + 1).reduce((a, b) => a + b, 0) / credits.total)
        .filter(fraction => fraction > 0 && fraction < 1)).size;
    need(state.ticks === expectedTicks, `${label}: ${state.ticks} weekly ticks, the API implies ${expectedTicks}`);
    const warned = ['stt', 'assessment'].filter(key => fairUse[key].warn && fairUse[key].limit !== null);
    need(state.counters.length === warned.length, `${label}: fair-use counters (${state.counters.length}) disagree with the API's warn flags (${warned.length})`);
    need(state.scrollWidth <= state.clientWidth, `${label}: the page overflows horizontally`);
    return { percent, materials, ticks: expectedTicks, locked, counters: warned.length };
  };

  const clipShot = async (name, rect) => {
    const capture = await tab.call('Page.captureScreenshot', { format: 'png', fromSurface: true, captureBeyondViewport: true,
      clip: { x: rect.left, y: rect.top, width: rect.width, height: rect.height, scale: 1 } });
    await writeFile(join(config.output, name), Buffer.from(capture.data, 'base64'));
  };
  /** The section's rectangle in page coordinates, for a clipped element screenshot. */
  const sectionPageRect = () => page(`const r = document.querySelector('section#ai-budget').getBoundingClientRect();
    return { left: r.left + scrollX, top: r.top + scrollY, width: r.width, height: r.height };`);

  const evidence = {};
  await reducedMotion(true);
  const widths = [[1440, 900, 1, false, 'usage-budget-1440.png'], [390, 844, 1, true, 'usage-budget-390.png'],
    [320, 900, 2, false, 'usage-budget-320-at-200-percent.png']];

  await stage('block_matches_api', async () => {
    await metrics(1440, 900, 1, false);
    await navigate('/profile', tab);
    await waitForBlock();
    const usage = await serverUsage();
    need(usage.plan === 'FREE', `the fixture account is expected to be on the Free plan, the API says ${usage.plan}`);
    evidence.api = { plan: usage.plan, percentUsed: usage.credits.percentUsed, used: usage.credits.used,
      reserved: usage.credits.reserved, unlocked: usage.credits.unlocked, total: usage.credits.total };
    const state = await blockState();
    evidence.shown = assertConsistent('profile at 1440', state, usage);
    need(usage.weeklyUnlock !== null, 'a Free account must carry weekly portions');
    // Free ticks: three boundaries (13, 26, 38 of 50) unless every portion is already open.
    need(evidence.shown.ticks === (usage.credits.unlocked >= usage.credits.total ? 0 : 3), 'Free weekly ticks are not three');
    evidence.summary = state.summary.replaceAll(NBSP, ' ');
    // The other profile sections still work next to the block.
    need(await exists('#profile-username', tab) && await exists('#notifications-heading', tab), 'the profile lost its other sections');
  });

  await stage('responsive_and_screenshots', async () => {
    for (const [width, height, scale, mobile, name] of widths) {
      await metrics(width, height, scale, mobile);
      await page('return new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve(true))));');
      const state = await blockState();
      need(state !== null && state.scrollWidth <= state.clientWidth, `the profile overflows horizontally at ${width} CSS px`);
      need(state.sectionRect.left >= -0.5 && state.sectionRect.right <= state.clientWidth + 0.5,
        `the «ИИ-бюджет» block leaves the ${width} px viewport horizontally`);
      need(await page(`return matchMedia('(prefers-reduced-motion: reduce)').matches;`), 'reduced motion is not in effect');
      await clipShot(name, await sectionPageRect());
    }
    evidence.screenshots = widths.map(entry => entry[4]);
    evidence.noHorizontalOverflow = [1440, 390, 320];
    evidence.reducedMotion = true;
    await metrics(1440, 900, 1, false);
  });

  await stage('fragment_same_document', async () => {
    // A notification link while the profile is already open changes only the fragment: no new document.
    await metrics(1440, 900, 1, false);
    await page('return new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve(true))));');
    await page(`globalThis.__usageMarker = 'same-document'; scrollTo(0, 0); document.activeElement?.blur?.(); return true;`);
    const before = await blockState();
    need(before.activeId !== 'ai-budget-heading' && before.scrollY === 0, 'the profile did not start away from the block');
    await tab.call('Page.navigate', { url: `${config.frontend}/profile#ai-budget` });
    await until(async () => (await blockState())?.activeId === 'ai-budget-heading', 'the fragment link did not focus the «ИИ-бюджет» heading');
    const after = await blockState();
    need(await page(`return globalThis.__usageMarker === 'same-document' && location.hash === '#ai-budget';`), 'the fragment link reloaded the document');
    need(after.headingRect.top >= 8 && after.headingRect.bottom <= after.innerHeight,
      `the «ИИ-бюджет» heading is not in view with room for its focus ring after the fragment link (top ${Math.round(after.headingRect.top)}, bottom ${Math.round(after.headingRect.bottom)})`);
    evidence.fragmentSameDocument = { focused: true, inView: true, documentKept: true, scrolled: after.scrollY > 0 || after.headingRect.top >= 0 };
    // A different fragment does not steal focus.
    await page(`document.activeElement?.blur?.(); return true;`);
    await tab.call('Page.navigate', { url: `${config.frontend}/profile#elsewhere` });
    await sleep(400);
    need((await blockState()).activeId !== 'ai-budget-heading', 'an unrelated fragment moved focus to the «ИИ-бюджет» heading');
  });

  await stage('fragment_fresh_load', async () => {
    // The link of a notification opened from another page: a fresh load of the profile with the fragment.
    await navigate('/decks', tab);
    await until(() => exists('main', tab), 'the deck list did not load');
    await navigate('/profile#ai-budget', tab);
    await waitForBlock();
    await until(async () => (await blockState())?.activeId === 'ai-budget-heading', 'a fresh /profile#ai-budget did not focus the heading');
    const state = await blockState();
    need(state.headingRect.top >= 8 && state.headingRect.bottom <= state.innerHeight,
      `the heading is not in view with room for its focus ring after a fresh /profile#ai-budget (top ${Math.round(state.headingRect.top)}, bottom ${Math.round(state.headingRect.bottom)}, viewport ${state.innerHeight})`);
    evidence.fragmentFreshLoad = { focused: true, inView: true };
    assertConsistent('profile#ai-budget', state, await serverUsage());
    await saveScreenshot('usage-budget-fragment-1440.png', tab);
    evidence.screenshots.push('usage-budget-fragment-1440.png');
  });

  await reducedMotion(false);
  await metrics(1280, 900, 1, false);
  record('usage_profile_ai_budget_real_api', evidence);
  return evidence;
}
