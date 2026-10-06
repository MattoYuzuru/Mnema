// Real-browser check of the paywall and the goal question (#301) against the real Learning API: `/plans` over the real `GET /api/plans`,
// the «Для чего вам Mnema?» question over the real `GET/PUT /api/learning-profile`, and the public `/ai` page. Runs with `--authoring` on the
// signed-in account's tab (`--only-plans` runs only this scenario after the base flow, a development aid and never the gate). Node 24
// built-ins only. Nothing is stubbed; the account is a fresh one on the Free plan whose goal has never been answered.
//
// Stages: the question appears on the deck list and nowhere else (editor, capture, the deck hub, privacy, terms and home stay free of it,
// and a client-side navigation away from the list takes it down); it is answered with the keyboard and the API stores it; `/plans` shows
// the goal's heading, exactly one recommended tier, the account's own plan and a honest lede; the year switch shows the same arithmetic
// as the API's prices; an ticked auto-renew resets when the tier or the period changes; the CTA opens the always-present status region
// (no navigation, nothing charged); a real Tab walk from the top of the page to the call to action never leaves a focused control under
// the sticky bar at 1440, 390 and 320 CSS px (measured, with the root scroll padding equal to the bar's height plus room for the focus ring). `runAiPublic` runs after
// logout on the first tab: `/ai` is reachable from the footer without signing in.
// A broken step is a finding for the product, never something to work around: it throws, a failure screenshot and a .txt are written and
// the run fails with the step name.
import { writeFile } from 'node:fs/promises';
import { join } from 'node:path';

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const KEYS = { Enter: ['Enter', 'Enter', 13], Tab: ['Tab', 'Tab', 9], ' ': [' ', 'Space', 32], ArrowDown: ['ArrowDown', 'ArrowDown', 40],
  ArrowRight: ['ArrowRight', 'ArrowRight', 39], ArrowLeft: ['ArrowLeft', 'ArrowLeft', 37], Escape: ['Escape', 'Escape', 27] };
const GOAL_LABELS = ['Экзамены и сессия', 'Собеседование', 'Язык', 'Работа', 'Для себя'];
const PAYMENT_NOTICE = 'Оплату подключаем: тариф можно будет оформить здесь же. Пока доступен промокод.';
const MOSCOW_DAY = new Intl.DateTimeFormat('ru-RU', { day: 'numeric', month: 'long', timeZone: 'Europe/Moscow' });
const MONEY = new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 0 });
/** «5 119 ₽» with every kind of space folded to one, so the check does not depend on which no-break space the page picked. */
const flat = text => (text ?? '').replace(/[\s\u00a0\u202f]+/gu, ' ').trim();
const rub = amount => flat(`${MONEY.format(amount)} ₽`);

export function toolkit(ctx) {
  const { tab, config, SafeFailure, until, exists, saveScreenshot, bearer } = ctx;
  const need = (value, label) => { if (!value) throw new SafeFailure(label); };
  const page = (body, ...args) => tab.callFunction(`async function(...args) { ${body} }`, args);
  const metrics = (width, height, scale, mobile) =>
    tab.call('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: scale, mobile });
  const reducedMotion = value => tab.call('Emulation.setEmulatedMedia',
    { features: value ? [{ name: 'prefers-reduced-motion', value: 'reduce' }] : [] });
  const frames = () => page('return new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve(true))));');
  const press = async (name, { modifiers = 0 } = {}) => {
    const [key, code, virtualKeyCode] = KEYS[name];
    const event = { key, code, windowsVirtualKeyCode: virtualKeyCode, modifiers };
    await tab.call('Page.bringToFront');
    const down = name === 'Enter' ? { type: 'keyDown', text: '\r', unmodifiedText: '\r', ...event }
      : name === ' ' ? { type: 'keyDown', text: ' ', unmodifiedText: ' ', ...event } : { type: 'rawKeyDown', ...event };
    await tab.call('Input.dispatchKeyEvent', down);
    await tab.call('Input.dispatchKeyEvent', { type: 'keyUp', ...event });
    await frames();
  };
  /** The real API, fetched with the page's own bearer (no cookie). */
  const api = async (method, path, body) => {
    const result = await page(`const init = { method: args[1], credentials: 'omit',
      headers: { Authorization: args[2], ...(args[4] === undefined ? {} : { 'Content-Type': 'application/json' }) } };
      if (args[4] !== undefined) init.body = JSON.stringify(args[4]);
      const response = await fetch(args[0] + args[3], init);
      return { status: response.status, cache: response.headers.get('Cache-Control'), body: response.ok ? await response.json() : null };`,
    config.frontend, method, 'Bearer ' + bearer, path, body);
    return result;
  };
  const failureShot = async name => { try { await saveScreenshot(`failure-${name}.png`, tab); } catch { /* the original failure is the verdict */ } };
  return { need, page, metrics, reducedMotion, frames, press, api, failureShot, until, exists, tab };
}

export async function runPlans(ctx) {
  const { config, record, SafeFailure, navigate, saveScreenshot, setStep, deckPath } = ctx;
  const { need, page, metrics, reducedMotion, frames, press, api, failureShot, until, exists, tab } = toolkit(ctx);
  const evidence = { stages: {}, screenshots: [] };

  const stage = async (name, body) => {
    setStep(`plans_${name}`);
    try { const result = await body(); evidence.stages[name] = result ?? true; return result; } catch (error) {
      await failureShot(`plans-${name}`);
      await writeFile(join(config.output, `failure-plans-${name}.txt`),
        (error instanceof SafeFailure ? error.message : `driver: ${String(error?.message ?? error).slice(0, 160)}`) + '\n').catch(() => {});
      throw error;
    }
  };
  const shot = async name => { await saveScreenshot(name, tab); evidence.screenshots.push(name); };

  const question = () => page(`const form = document.querySelector('app-goal-onboarding form');
    if (!form) return null;
    const radios = [...form.querySelectorAll('input[type=radio]')];
    const buttons = [...form.querySelectorAll('button')].map(node => ({ text: node.textContent.trim(), disabled: node.disabled }));
    return { legend: form.querySelector('legend')?.textContent.trim() ?? null,
      labels: [...form.querySelectorAll('label.segment')].map(node => node.textContent.trim()),
      values: radios.map(radio => radio.value), checked: radios.filter(radio => radio.checked).map(radio => radio.value),
      names: new Set(radios.map(radio => radio.name)).size, buttons,
      scrollWidth: document.documentElement.scrollWidth, clientWidth: document.documentElement.clientWidth };`);

  /** After a load, the question has had every chance to appear: the profile API is local and fast. */
  const settledWithoutQuestion = async (path, label) => {
    await navigate(path, tab);
    await until(() => exists('main#main-content', tab), `${label}: the page did not render`);
    await sleep(900);
    need(await question() === null, `${label}: the goal question is shown where it does not belong`);
  };

  await reducedMotion(true);
  await metrics(1440, 900, 1, false);

  // ---- the question: where it appears ------------------------------------------------------------------------------------------
  await stage('onboarding_allowlist', async () => {
    const before = await api('GET', '/api/learning-profile');
    need(before.status === 200 && before.body.answeredAt === null && before.body.goal === null,
      'the fixture account must not have answered the goal question yet');
    need((before.cache ?? '').includes('no-store'), 'GET /api/learning-profile is cacheable');

    await navigate('/decks', tab);
    await until(async () => (await question()) !== null, 'the goal question did not appear on the deck list');
    const asked = await question();
    need(asked.legend === 'Для чего вам Mnema?', `the question reads «${asked.legend}»`);
    need(JSON.stringify(asked.labels) === JSON.stringify(GOAL_LABELS), `the options read ${JSON.stringify(asked.labels)}`);
    need(asked.values.length === 5 && asked.names === 1 && asked.checked.length === 0, 'the options are not one unselected radio group');
    need(asked.buttons.some(button => button.text === 'Продолжить' && button.disabled)
      && asked.buttons.some(button => button.text === 'Пропустить' && !button.disabled), '«Продолжить» must wait for a choice and «Пропустить» must be available');
    need(asked.scrollWidth <= asked.clientWidth, 'the deck list overflows horizontally with the question shown');
    evidence.askedOn = '/decks';
    await shot('plans-goal-question-1440.png');

    // Client-side navigation takes it down: open a deck from the list.
    const opened = await page(`const link = [...document.querySelectorAll('a[href^="/decks/"]')].find(node => !/\\/decks\\/new/u.test(node.getAttribute('href')));
      if (!link) return null; const href = link.getAttribute('href'); link.click(); return href;`);
    need(opened !== null, 'the deck list has no link to a deck');
    await until(async () => (await page('return location.pathname;')) === opened && (await question()) === null,
      'the question stayed on screen after leaving the deck list in the same document');
    evidence.hiddenAfterClientNavigation = opened;

    const quiet = [[`${deckPath}/materials/new`, 'material editor'], [`${deckPath}/capture`, 'capture'], [deckPath, 'deck hub'],
      ['/privacy', 'privacy'], ['/terms', 'terms'], ['/', 'home']];
    for (const [path, label] of quiet) await settledWithoutQuestion(path, label);
    evidence.quietOn = quiet.map(entry => entry[1]);
  });

  await stage('onboarding_answer', async () => {
    await navigate('/decks', tab);
    await until(async () => (await question()) !== null, 'the goal question did not appear on the deck list');
    // Keyboard only: focus the group, Space selects the first option, Tab reaches «Продолжить», Enter submits.
    await page(`document.querySelector('app-goal-onboarding input[type=radio]').focus(); return true;`);
    await press(' ');
    need((await question()).checked.join() === 'EXAMS', 'Space did not select «Экзамены и сессия»');
    need((await question()).buttons.some(button => button.text === 'Продолжить' && !button.disabled), '«Продолжить» stayed disabled after a choice');
    await press('Tab');
    need(await page(`return document.activeElement?.textContent.trim() === 'Продолжить';`), 'Tab from the radio group did not reach «Продолжить»');
    await press('Enter');
    await until(async () => (await question()) === null, 'the question stayed after «Продолжить»');
    const stored = await api('GET', '/api/learning-profile');
    need(stored.status === 200 && stored.body.goal === 'EXAMS' && stored.body.answeredAt !== null, `the API stored ${JSON.stringify(stored.body)}`);
    // The question disappeared from under the focus: it must land on the page, never on <body>.
    await until(() => page(`const active = document.activeElement;
      return active !== document.body && Boolean(active?.closest('#main-content') || active?.id === 'main-content');`), 'focus was lost after the answer');
    await navigate('/decks', tab);
    await sleep(900);
    need(await question() === null, 'the question came back after it was answered');
    evidence.goal = 'EXAMS';
  });

  // ---- /plans --------------------------------------------------------------------------------------------------------------
  const catalogue = await api('GET', '/api/plans');
  need(catalogue.status === 200 && (catalogue.cache ?? '').includes('no-store'), 'GET /api/plans is not available or cacheable');
  const plans = catalogue.body.plans;
  need(catalogue.body.current.plan === 'FREE', `the fixture account is expected on Free, the API says ${catalogue.body.current.plan}`);
  const paid = plans.filter(entry => entry.availability === 'AVAILABLE' && entry.priceRub.month > 0);
  need(paid.length >= 2, 'the catalogue offers fewer than two paid tiers');
  const recommended = plans.find(entry => entry.availability === 'AVAILABLE' && entry.recommendedFor.includes('EXAMS'));
  need(recommended !== undefined, 'the catalogue recommends no tier for exams');

  const cards = () => page(`return [...document.querySelectorAll('app-plan-option')].map(card => ({
    label: card.querySelector('.plan-name')?.textContent.trim(), checked: card.querySelector('input').checked, disabled: card.querySelector('input').disabled,
    stamps: [...card.querySelectorAll('.stamp')].map(node => node.textContent.trim()), price: card.querySelector('.price strong')?.textContent ?? '',
    note: card.querySelector('.price .note')?.textContent ?? '', saving: card.querySelector('.price .saving')?.textContent ?? '' }));`);
  const view = () => page(`const box = document.querySelector('.renew input[type=checkbox]');
    const notice = document.querySelector('p.notice[role=status][id$="-notice"]');
    const bar = document.querySelector('.cta-bar');
    const rect = node => { if (!node) return null; const r = node.getBoundingClientRect(); return { top: r.top, bottom: r.bottom, left: r.left, right: r.right, height: r.height }; };
    return { heading: document.querySelector('h1')?.textContent.trim(), lede: document.querySelector('.lede')?.textContent ?? '',
      renewLabel: document.querySelector('.renew .check-row')?.textContent ?? null, renewChecked: box?.checked ?? null,
      renewHint: document.querySelector('.renew .hint')?.textContent ?? null,
      notice: notice ? notice.textContent : null, noticeRole: notice?.getAttribute('role') ?? null,
      cta: bar?.querySelector('.button')?.textContent.trim() ?? null, ctaDescribedBy: bar?.querySelector('.button')?.getAttribute('aria-describedby') ?? null,
      noticeId: notice?.id ?? null, bar: rect(bar), pathname: location.pathname,
      period: [...document.querySelectorAll('app-segmented-choice input[type=radio]')].find(radio => radio.checked)?.value ?? null,
      onboarding: Boolean(document.querySelector('app-goal-onboarding form')),
      scrollWidth: document.documentElement.scrollWidth, clientWidth: document.documentElement.clientWidth,
      scrollPadding: getComputedStyle(document.documentElement).scrollPaddingBottom, barHeight: bar?.offsetHeight ?? null };`);

  await stage('plans_content', async () => {
    await navigate('/plans', tab);
    await until(() => exists('app-plan-option input[type=radio]', tab), 'the plans did not render');
    const state = await view();
    need(state.heading.startsWith('Подготовка к экзаменам: '), `the heading ignores the goal: «${state.heading}»`);
    need(!state.onboarding, 'the goal question shows again on /plans after it was answered');
    const listed = await cards();
    need(listed.length === plans.length, `${listed.length} tier cards, the API lists ${plans.length}`);
    const badge = listed.filter(card => card.stamps.some(stamp => stamp.startsWith('Рекомендуем для')));
    need(badge.length === 1 && badge[0].label === recommended.plan.charAt(0) + recommended.plan.slice(1).toLowerCase(),
      `recommendation is on ${JSON.stringify(badge.map(card => card.label))}, expected ${recommended.plan}`);
    need(badge[0].stamps.includes('Рекомендуем для подготовки к экзаменам'), 'the recommendation does not name the goal');
    need(listed.find(card => card.label === 'Free').stamps.includes('Ваш тариф') && listed.find(card => card.label === 'Free').checked,
      'the account\'s own plan is not marked and preselected');
    need(listed.filter(card => card.disabled).length === plans.filter(entry => entry.availability === 'TEASER').length, 'teaser tiers are not the only disabled ones');
    need(/голос и проверка ответов/iu.test(state.lede) && /Free/u.test(state.lede), 'the lede does not say Free also has voice and answer-check caps');
    need(state.cta === 'Остаться на Free' && state.renewLabel === null, 'a Free account on Free must see «Остаться на Free» and no auto-renew');
    need(state.scrollWidth <= state.clientWidth, '/plans overflows horizontally at 1440');
    await shot('plans-1440.png');
    return { recommended: recommended.plan, heading: state.heading };
  });

  await stage('period_math', async () => {
    // The period switch with a real ArrowRight on the radio group; the numbers are recomputed here from the API's prices.
    await page(`const month = [...document.querySelectorAll('app-segmented-choice input[type=radio]')].find(radio => radio.value === 'MONTH'); month.focus(); return true;`);
    const monthCards = await cards();
    for (const entry of paid) {
      const card = monthCards.find(candidate => candidate.label.toLowerCase() === entry.plan.toLowerCase());
      need(flat(card.price) === `${rub(entry.priceRub.month)} в месяц` && flat(card.note) === `≈ ${rub(entry.perDayRub)} в день`,
        `${entry.plan} month price shows «${flat(card.price)}» / «${flat(card.note)}»`);
    }
    await press('ArrowRight');
    need((await view()).period === 'YEAR', 'ArrowRight did not move the period to the year');
    const yearCards = await cards();
    for (const entry of paid) {
      const card = yearCards.find(candidate => candidate.label.toLowerCase() === entry.plan.toLowerCase());
      const perMonth = Math.round(entry.priceRub.year / 12);
      const savings = Math.max(0, entry.priceRub.month * 12 - entry.priceRub.year);
      need(flat(card.price) === `${rub(entry.priceRub.year)} в год`, `${entry.plan} year price shows «${flat(card.price)}»`);
      need(flat(card.note) === `≈ ${rub(perMonth)} в месяц`, `${entry.plan} per-month note shows «${flat(card.note)}»`);
      need(savings === 0 ? card.saving === '' : flat(card.saving) === `Экономия ${rub(savings)} в год`, `${entry.plan} saving shows «${flat(card.saving)}»`);
    }
    await shot('plans-year-1440.png');
    await press('ArrowLeft');
    need((await view()).period === 'MONTH', 'ArrowLeft did not return to the month');
    return { tiers: paid.map(entry => entry.plan) };
  });

  const tierRadio = plan => `app-plan-option:nth-of-type(${plans.findIndex(entry => entry.plan === plan) + 1}) input[type=radio]`;
  const choosePlan = async plan => {
    // A real click on the radio's label area through the DOM click is the user's gesture for a pointer; the keyboard path is walked later.
    need(await page(`const radio = document.querySelector(args[0]); if (!radio) return false; radio.click(); return radio.checked;`, tierRadio(plan)),
      `${plan} cannot be chosen`);
    await frames();
  };
  const dayAround = (offsetDays) => MOSCOW_DAY.format(Date.now() + offsetDays * 86_400_000);

  await stage('auto_renew', async () => {
    const plus = paid[0];
    await choosePlan(plus.plan);
    let state = await view();
    need(state.renewChecked === false, 'auto-renew is pre-ticked');
    const monthText = flat(state.renewLabel);
    need(monthText.startsWith(`Продлевать автоматически: ${rub(plus.priceRub.month)} каждые 30 дней, следующее списание `),
      `the month consent reads «${monthText}»`);
    need([dayAround(30), dayAround(30.05), dayAround(29.95)].some(day => monthText.endsWith(day)), `the renewal date in «${monthText}» is not 30 days from now`);
    need(/Когда подключим оплату/u.test(state.renewHint ?? ''), 'the hint under the checkbox promises a profile switch that does not exist yet');

    // Tick it with a real Space; a different tier resets it.
    await page(`document.querySelector('.renew input[type=checkbox]').focus(); return true;`);
    await press(' ');
    need((await view()).renewChecked === true, 'Space did not tick the auto-renew box');
    const other = paid[1];
    await choosePlan(other.plan);
    state = await view();
    need(state.renewChecked === false, 'the auto-renew tick survived a change of tier');
    need(flat(state.renewLabel).includes(rub(other.priceRub.month)), 'the consent text still names the previous tier\'s price');

    // A different period resets it as well, and the text follows.
    await page(`document.querySelector('.renew input[type=checkbox]').click(); return true;`);
    need((await view()).renewChecked === true, 'the auto-renew box cannot be ticked');
    await page(`[...document.querySelectorAll('app-segmented-choice input[type=radio]')].find(radio => radio.value === 'YEAR').click(); return true;`);
    await frames();
    state = await view();
    need(state.renewChecked === false, 'the auto-renew tick survived a change of period');
    const yearText = flat(state.renewLabel);
    need(yearText.startsWith(`Продлевать автоматически: ${rub(other.priceRub.year)} раз в год, следующее списание `)
      && yearText.endsWith(String(new Date().getUTCFullYear() + 1)), `the yearly consent reads «${yearText}»`);
    await page(`[...document.querySelectorAll('app-segmented-choice input[type=radio]')].find(radio => radio.value === 'MONTH').click(); return true;`);
    await frames();
    return { ticked: 'reset on tier and period' };
  });

  await stage('payment_notice', async () => {
    const plus = paid[0];
    await choosePlan(plus.plan);
    let state = await view();
    need(state.notice === '' && state.noticeRole === 'status', 'the status region must be in the page from the start, empty, with role=status');
    need(state.ctaDescribedBy === null, 'the call to action describes an empty notice');
    need(flat(state.cta) === `Перейти на ${plus.plan.charAt(0) + plus.plan.slice(1).toLowerCase()} — ${rub(plus.priceRub.month)} в месяц`, `the call to action reads «${state.cta}»`);
    await page(`const button = document.querySelector('.cta-bar .button'); button.focus(); return true;`);
    await press('Enter');
    await until(async () => (await view()).notice !== '', 'activating the call to action showed nothing');
    state = await view();
    need(flat(state.notice) === flat(PAYMENT_NOTICE), `the notice reads «${state.notice}»`);
    need(state.ctaDescribedBy === state.noticeId, 'the call to action is not described by the notice');
    need(state.pathname === '/plans', 'activating a paid tier navigated away');
    const after = await api('GET', '/api/plans');
    need(after.body.current.plan === 'FREE' && after.body.current.source === 'CONFIG', 'the call to action changed the entitlement');
    await shot('plans-notice-1440.png');
    // A different tier clears the notice.
    await choosePlan(paid[1].plan);
    need((await view()).notice === '', 'the notice outlived a change of tier');
    return { entitlement: 'unchanged', notice: 'always-present status region' };
  });

  // ---- focus never under the sticky bar -------------------------------------------------------------------------------------------
  const widths = [[1440, 900, 1, false, 'plans-focus-1440.png'], [390, 844, 1, true, 'plans-focus-390.png'],
    [320, 900, 2, false, 'plans-focus-320-at-200-percent.png']];
  await stage('keyboard_and_sticky_bar', async () => {
    const measured = [];
    for (const [width, height, scale, mobile, name] of widths) {
      await metrics(width, height, scale, mobile);
      await navigate('/plans', tab);
      await until(() => exists('app-plan-option input[type=radio]', tab), `the plans did not render at ${width}`);
      await choosePlan(paid[0].plan);
      await page('scrollTo(0, 0); document.activeElement?.blur?.(); return true;');
      await frames();
      const state = await view();
      need(state.bar !== null && state.barHeight > 0, `no call-to-action bar at ${width}`);
      need(state.scrollWidth <= state.clientWidth, `/plans overflows horizontally at ${width} CSS px`);
      need(state.scrollPadding === `${state.barHeight + 8}px`, `root scroll padding is ${state.scrollPadding}, the bar is ${state.barHeight}px high (plus 8px for the focus ring) at ${width}`);
      const stops = [];
      let reached = false;
      let worst = null;
      for (let index = 0; index < 160 && !reached; index++) {
        await press('Tab');
        const stop = await page(`const active = document.activeElement;
          if (!active || active === document.body) return { body: true };
          const bar = document.querySelector('.cta-bar').getBoundingClientRect();
          const rect = active.getBoundingClientRect();
          return { tag: active.tagName.toLowerCase(), name: (active.getAttribute('aria-label') || active.textContent || active.value || '').trim().replace(/\\s+/g, ' ').slice(0, 40),
            inBar: document.querySelector('.cta-bar').contains(active), top: rect.top, bottom: rect.bottom, height: rect.height,
            barTop: bar.top, innerHeight, hidden: rect.height > 0 && rect.bottom > bar.top + 0.5 && rect.top < bar.bottom && !document.querySelector('.cta-bar').contains(active),
            above: rect.top < 0 };`);
        stops.push(stop);
        need(!stop.hidden, `at ${width} px the focused ${stop.tag} «${stop.name}» is under the sticky bar (bottom ${Math.round(stop.bottom)}, bar top ${Math.round(stop.barTop)})`);
        need(!stop.above, `at ${width} px the focused ${stop.tag} «${stop.name}» is above the viewport`);
        if (stop.inBar) reached = true;
        const gap = stop.inBar ? null : stop.barTop - stop.bottom;
        if (gap !== null && (worst === null || gap < worst)) worst = gap;
      }
      need(worst === null || worst >= 6, `at ${width} px a focused control ends ${Math.round(worst)}px above the bar: no room for its focus ring`);
      need(reached, `Tab never reached the call to action at ${width} px in ${stops.length} presses`);
      measured.push({ width, stops: stops.length, closestGapPx: worst === null ? null : Math.round(worst) });
      await shot(name);
    }
    await metrics(1440, 900, 1, false);
    return measured;
  });

  await stage('profile_voice_withdrawal', async () => {
    const switchFixture = async variant => page('return (await fetch(args[0], { method: "POST" })).status;', `/__fixture/learning-${variant}`);
    let acceptedCleared = null;
    try {
      if (config.generation) {
        need(await switchFixture('generation') === 204, 'could not select the local Stub fixture');
        const terms = await api('GET', '/api/speech-consent');
        need(terms.status === 200, 'could not read the fixture voice consent terms');
        need((await api('PUT', '/api/speech-consent', terms.body.required)).status === 200, 'could not seed a consent in the disposable fixture');
      }
      await metrics(1440, 900, 1, false);
      await navigate('/profile#speech-consent', tab);
      await until(() => exists('#speech-consent app-speech-consent-settings button', tab), 'profile has no voice withdrawal control');
      await page('document.querySelector("#speech-consent button").focus(); return true;');
      await press('Enter');
      await until(() => page('return document.querySelector("#speech-consent [role=status]")?.textContent.includes("Согласие на распознавание отозвано");'),
        'keyboard withdrawal did not announce success');
      need(await page('return document.activeElement === document.querySelector("#speech-consent button");'), 'withdrawal lost keyboard focus');
      if (config.generation) {
        const after = await api('GET', '/api/speech-consent');
        need(after.status === 200 && after.body.accepted === null, 'withdrawal did not clear the real consent row');
        acceptedCleared = true;
        need(await switchFixture('default') === 204, 'could not restore the ordinary fixture');
        await navigate('/profile#speech-consent', tab);
        await until(() => exists('#speech-consent app-speech-consent-settings button', tab), 'withdrawal vanished when speech was disabled');
        await page('document.querySelector("#speech-consent button").focus(); return true;');
        await press('Enter');
        await until(() => page('return document.querySelector("#speech-consent [role=status]")?.textContent.includes("Согласие на распознавание отозвано");'),
          'withdrawal failed when speech was disabled');
      }
      await shot('plans-profile-speech-consent-1440.png');
      return { acceptedCleared, availableWithSpeechDisabled: true, keyboardFocusPreserved: true };
    } finally {
      if (config.generation) need(await switchFixture('default') === 204, 'the ordinary fixture was not restored');
    }
  });

  await reducedMotion(false);
  await metrics(1280, 900, 1, false);
  record('plans_paywall_goal_real_api', evidence);
  return evidence;
}

/** `/ai` without signing in: from the footer link, and by its own address. Runs on a tab that is anonymous. */
export async function runAiPublic(ctx) {
  const { config, record, SafeFailure, navigate, saveScreenshot, setStep } = ctx;
  const { need, page, metrics, until, exists, tab } = toolkit(ctx);
  setStep('ai_public');
  const evidence = {};
  try {
    await metrics(1280, 900, 1, false);
    await navigate('/', tab);
    await until(() => exists('footer a[href="/ai"]', tab), 'the footer has no «Как Mnema использует ИИ» link while signed out');
    need(await page(`return document.querySelector('footer a[href="/ai"]').textContent.trim() === 'Как Mnema использует ИИ';`), 'the footer link has the wrong text');
    need(await page(`document.querySelector('footer a[href="/ai"]').click(); return true;`), 'the footer link cannot be clicked');
    await until(() => page(`return location.pathname === '/ai' && document.querySelector('h1')?.textContent.trim() === 'Как Mnema использует ИИ';`),
      'the footer link did not open the public /ai page (a redirect to sign-in?)');
    const state = await page(`return { sections: [...document.querySelectorAll('article section h2')].map(node => node.textContent.trim()),
      toc: document.querySelectorAll('nav.toc a').length, scrollWidth: document.documentElement.scrollWidth, clientWidth: document.documentElement.clientWidth,
      text: document.body.innerText, signedIn: Boolean(document.querySelector('.identity')) };`);
    need(!state.signedIn, 'the tab is signed in: the check proves nothing about the public route');
    need(state.toc === 6 && state.sections.length === 6, `the page has ${state.sections.length} sections and ${state.toc} contents links`);
    need(!/кредит/iu.test(state.text), '/ai shows credits as a unit');
    need(state.scrollWidth <= state.clientWidth, '/ai overflows horizontally at 1280');
    evidence.viaFooter = true;
    await saveScreenshot('ai-public-1280.png', tab);
    // Directly, at the narrowest width.
    await metrics(320, 900, 2, false);
    await navigate('/ai', tab);
    await until(() => exists('article.ai-page h1', tab), 'a direct /ai load did not render the page');
    const narrow = await page(`return { path: location.pathname, scrollWidth: document.documentElement.scrollWidth, clientWidth: document.documentElement.clientWidth };`);
    need(narrow.path === '/ai' && narrow.scrollWidth <= narrow.clientWidth, '/ai redirects or overflows at 320 CSS px');
    evidence.direct = true;
    await saveScreenshot('ai-public-320-at-200-percent.png', tab);
  } catch (error) {
    try { await saveScreenshot('failure-ai-public.png', tab); } catch { /* the original failure is the verdict */ }
    await writeFile(join(config.output, 'failure-ai-public.txt'),
      (error instanceof SafeFailure ? error.message : `driver: ${String(error?.message ?? error).slice(0, 160)}`) + '\n').catch(() => {});
    throw error;
  } finally {
    await metrics(1280, 900, 1, false).catch(() => {});
  }
  record('ai_page_public_without_login', evidence);
  return evidence;
}
