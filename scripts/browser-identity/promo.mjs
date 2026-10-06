// Real-browser check of promo codes, the A/B assignment and the promo popup (#302) against the real Learning API. Runs with `--authoring --only-promo`
// on the signed-in second account's tab, after the base flow (a development aid and never the gate: the popup campaign must be on in Learning,
// and it would cover the pages of every other scenario). Node 24 built-ins only. Nothing is stubbed.
//
// The fixture boots a second Learning with the popup campaign on and the address limit of attempts raised (every request of this fixture
// comes from one loopback address); this scenario switches the proxy to it (`POST /__fixture/learning-promo`) and back. The account is a
// fresh one on the Free plan whose email is not verified and who is not an administrator; the fixture makes it both on the disposable
// database (`POST /__fixture/promo-account-verified-admin`), because Identity has no endpoint that verifies an email without a mailbox and
// the first administrator of an installation is never self-granted. A reset of the popup state (`POST /__fixture/promo-popup-reset`) lets one
// account be walked through dismiss, close, accept and decline in one run. Both fixture endpoints exist only with `--only-promo`.
//
// Stages: not verified and not an administrator (403 on the admin endpoints, `PROMO_NOT_ELIGIBLE` on a redemption, 400 without an
// Idempotency-Key, 401 without a token); an administrator creates a generated code once (the plain code is in the creation answer and nowhere
// else, MAX is refused), a discount code and a kill-switched one; `/plans` redeems by keyboard (a wrong code, the right one in lower case, the
// same one again, a discount, and the sixth attempt of the hour) with the messages the learner reads, the plan block and the API changing
// to Plus until a date without renewal, the same entitlement on `/api/usage`, the pending discount, the profile's field, and 390 px;
// the A/B variant is the server's and the period of `/plans` follows it; the popup appears only at the natural breakpoints (never on the editor,
// capture or the deck hub; on the Study completion screen it does; never in an active Study session, and then it has not even been asked), once per browser session, takes
// focus, traps Tab, closes with Esc and with «×» and the cooldown holds, accepts into `/plans`, and a decline is final.
// A broken step is a finding for the product, never something to work around: it throws, a failure screenshot and a .txt are written and the
// run fails with the step name.
import { writeFile } from 'node:fs/promises';
import { join } from 'node:path';

import { toolkit } from './plans.mjs';

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const MOSCOW_DAY = new Intl.DateTimeFormat('ru-RU', { day: 'numeric', month: 'long', timeZone: 'Europe/Moscow' });
const SESSION_KEY = 'mnema.promo-popup.session';
const PREFERENCE_KEY = 'mnema.promo-popup.preference.';
const CAMPAIGN_ID = 'browser-fixture-autumn';
/** Every kind of space folded to one, so a check does not depend on which no-break space the page picked. */
const flat = text => (text ?? '').replace(/[\s  ]+/gu, ' ').trim();

export async function runPromo(ctx) {
  const { config, record, SafeFailure, navigate, saveScreenshot, setStep, deckPath } = ctx;
  const { need, page, metrics, reducedMotion, frames, press, failureShot, until, exists, tab } = toolkit(ctx);
  const evidence = { stages: {}, screenshots: [] };

  const stage = async (name, body) => {
    setStep(`promo_${name}`);
    try { const result = await body(); evidence.stages[name] = result ?? true; return result; } catch (error) {
      await failureShot(`promo-${name}`);
      await writeFile(join(config.output, `failure-promo-${name}.txt`),
        (error instanceof SafeFailure ? error.message : `driver: ${String(error?.message ?? error).slice(0, 160)}`) + '\n').catch(() => {});
      throw error;
    }
  };
  const shot = async name => { await saveScreenshot(name, tab); evidence.screenshots.push(name); };

  /** The real API with the page's own bearer; every status keeps its problem body and Retry-After. `token: false` sends no Authorization. */
  const call = (method, path, { body, key, token = true } = {}) => page(`const [base, method, path, body, key, bearer] = args;
    const headers = {};
    if (bearer) headers.Authorization = 'Bearer ' + bearer;
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    if (key) headers['Idempotency-Key'] = key;
    const response = await fetch(base + path, { method, credentials: 'omit', headers, body: body === undefined ? undefined : JSON.stringify(body) });
    const text = await response.text();
    let json = null; try { json = text ? JSON.parse(text) : null; } catch { json = null; }
    return { status: response.status, retryAfter: response.headers.get('Retry-After'), recorded: response.headers.get('Promo-Event-Recorded'),
      cache: response.headers.get('Cache-Control'), body: json, text };`,
  config.frontend, method, path, body, key, token ? ctx.bearer : null);
  const uuid = () => page('return crypto.randomUUID();');
  const fixture = async path => {
    const status = await page(`const response = await fetch(args[0], { method: 'POST' }); return response.status;`, path);
    need(status === 204, `${path} answered ${status}`);
  };
  const insert = text => tab.call('Input.insertText', { text });
  const field = () => page(`const input = document.querySelector('app-promo-redeem input');
    if (!input) return null;
    const alert = document.querySelector('app-promo-redeem [role=alert]');
    const status = document.querySelector('app-promo-redeem [role=status]');
    return { label: document.querySelector('app-promo-redeem label')?.textContent.trim(), labelFor: document.querySelector('app-promo-redeem label')?.getAttribute('for') === input.id,
      autocomplete: input.getAttribute('autocomplete'), autocapitalize: input.getAttribute('autocapitalize'), spellcheck: input.getAttribute('spellcheck'),
      value: input.value, invalid: input.getAttribute('aria-invalid'), describedBy: input.getAttribute('aria-describedby'),
      error: alert?.textContent.trim() ?? '', alertId: alert?.id ?? null, success: status?.textContent.trim() ?? '', focused: document.activeElement === input,
      button: document.querySelector('app-promo-redeem button')?.textContent.trim() };`);
  /** Real keyboard: focus the field, replace its text, press Enter (the form's implicit submission). */
  const redeemByKeyboard = async text => {
    await page(`const input = document.querySelector('app-promo-redeem input'); input.focus(); input.select(); return true;`);
    await insert(text);
    await press('Enter');
  };
  const plans = () => call('GET', '/api/plans');
  const popupApi = () => call('GET', '/api/promo-popup');
  const dialog = () => page(`const dialog = document.querySelector('app-promo-popup dialog');
    if (!dialog) return null;
    const title = dialog.querySelector('h2');
    const rect = dialog.getBoundingClientRect();
    return { open: dialog.open, labelledBy: dialog.getAttribute('aria-labelledby') === title?.id, title: title?.textContent.trim(),
      focusOnTitle: document.activeElement === title, buttons: [...dialog.querySelectorAll('button')].map(node => node.getAttribute('aria-label') ?? node.textContent.trim()),
      checkboxes: dialog.querySelectorAll('input').length, modal: dialog.matches(':modal'), animation: getComputedStyle(dialog).animationName,
      width: rect.width, height: rect.height, innerWidth, innerHeight, text: dialog.textContent };`);
  const noPopup = async (label, wait = 1500) => {
    await sleep(wait);
    need(await dialog() === null, `${label}: the promo popup is on screen where it does not belong`);
  };
  const markerAbsent = async label => need(await page(`return sessionStorage.getItem(args[0]) === null;`, SESSION_KEY),
    `${label}: the page asked about the popup (the session marker is set), but it is not a place where it may`);
  /** The popup's event travels after the page has reacted: wait for the server to say it is silenced. */
  const silenced = label => until(async () => { const state = await popupApi(); return state.body?.eligible === false && state.body.campaign === null; },
    `${label}: the server still offers the popup`);
  // A new document resets the in-memory once guard too; clearing storage alone must not let a live tab repeat a popup.
  const forgetSession = async () => {
    await page(`sessionStorage.removeItem(args[0]); return true;`, SESSION_KEY);
    await navigate('/ai', tab);
  };

  await reducedMotion(true);
  await metrics(1440, 900, 1, false);
  await fixture('/__fixture/learning-promo');

  try {
    await stage('popup_disabled_receipt', async () => {
      await fixture('/__fixture/learning-default');
      const response = await call('POST', '/api/promo-popup/events', { body: { campaignId: CAMPAIGN_ID, event: 'DISMISSED' } });
      need(response.status === 204 && response.recorded === 'false', `a disabled campaign reported ${response.status}/${response.recorded}`);
      await fixture('/__fixture/learning-promo');
      return { status: response.status, recorded: false };
    });
    // ---- not verified, not an administrator -------------------------------------------------------------------------------------
    await stage('not_verified_not_admin', async () => {
      const create = await call('POST', '/api/admin/promo-codes', { body: { type: 'TIER_DAYS', plan: 'PLUS', days: 15, maxRedemptions: 3 } });
      need(create.status === 403 && create.body?.code === 'ACCESS_DENIED', `a non-administrator creating a code got ${create.status} ${create.body?.code}`);
      const list = await call('GET', '/api/admin/promo-codes');
      need(list.status === 403, `a non-administrator listing codes got ${list.status}`);
      const anonymous = await call('GET', '/api/admin/promo-codes', { token: false });
      need(anonymous.status === 401, `the admin endpoint without a token answered ${anonymous.status}`);
      const noKey = await call('POST', '/api/promo-codes/redemptions', { body: { code: 'ANYTHING' } });
      need(noKey.status === 400 && noKey.body?.reason === 'idempotency_key_required', `a redemption without an Idempotency-Key got ${noKey.status}`);
      const attempt = await call('POST', '/api/promo-codes/redemptions', { body: { code: 'NO-SUCH-CODE' }, key: await uuid() });
      need(attempt.status === 422 && attempt.body?.code === 'PROMO_NOT_ELIGIBLE', `an unverified account redeeming got ${attempt.status} ${attempt.body?.code}`);
      need((attempt.cache ?? '').includes('no-store'), 'a promo answer is cacheable');
      return { adminForbidden: 403, unverified: 'PROMO_NOT_ELIGIBLE' };
    });

    // ---- the fixture promotes the account; an administrator creates codes --------------------------------------------------------
    const codes = {};
    await stage('admin_creates_codes', async () => {
      await fixture('/__fixture/promo-account-verified-admin');
      const plus = await call('POST', '/api/admin/promo-codes',
        { body: { type: 'TIER_DAYS', plan: 'PLUS', days: 15, maxRedemptions: 3, channel: 'browser-fixture' } });
      need(plus.status === 201, `creating a Plus code got ${plus.status} ${plus.body?.code}`);
      codes.plus = plus.body.code;
      need(/^[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}$/u.test(codes.plus), 'the generated code is not twelve characters of the unambiguous alphabet');
      need(plus.body.hint?.length === 5 && plus.body.enabled === true && plus.body.redemptions === 0 && plus.body.channel === 'browser-fixture', 'the created code view is wrong');
      const max = await call('POST', '/api/admin/promo-codes', { body: { type: 'TIER_DAYS', plan: 'MAX', days: 5, maxRedemptions: 3 } });
      need(max.status === 400 && max.body?.reason === 'plan', 'a code for the Max tier was accepted');
      const until = new Date(Date.now() + 10 * 86_400_000).toISOString();
      const discount = await call('POST', '/api/admin/promo-codes', { body: { type: 'DISCOUNT_PERCENT', plan: 'PLUS', percent: 20, validUntil: until, maxRedemptions: 5 } });
      need(discount.status === 201, `creating a discount code got ${discount.status}`);
      codes.discount = discount.body.code;
      const killed = await call('POST', '/api/admin/promo-codes', { body: { type: 'TIER_MONTHS', plan: 'PRO', months: 2, maxRedemptions: 1 } });
      need(killed.status === 201, 'creating the code for the kill switch failed');
      const off = await call('PATCH', `/api/admin/promo-codes/${killed.body.codeId}`, { body: { enabled: false } });
      need(off.status === 200 && off.body.enabled === false, `the kill switch answered ${off.status}`);
      const list = await call('GET', '/api/admin/promo-codes');
      need(list.status === 200 && list.body.codes.some(entry => entry.codeId === plus.body.codeId && entry.redemptions === 0 && entry.hint === plus.body.hint),
        'the list does not show the created code with its hint and count');
      need(!list.text.includes(codes.plus.replace('-', '')) && !list.text.includes(codes.plus) && !list.text.includes(codes.discount),
        'a plain code is in the list: only the creation answer may carry it');
      return { generated: true, maxRefused: true, killSwitch: 'enabled=false' };
    });

    // ---- /plans: redemption by keyboard ---------------------------------------------------------------------------------------------
    await stage('redeem_by_keyboard', async () => {
      const before = await plans();
      need(before.body.current.plan === 'FREE' && before.body.current.source === 'CONFIG' && before.body.pendingDiscount === null, 'the account must start on Free without a discount');
      await navigate('/plans', tab);
      await until(() => exists('app-promo-redeem input', tab), 'the plans page has no promo field');
      let state = await field();
      need(state.label === 'Промокод' && state.labelFor && state.autocomplete === 'off' && state.autocapitalize === 'characters' && state.spellcheck === 'false',
        `the promo field is wrong: ${JSON.stringify(state)}`);
      need(state.button === 'Применить', `the button reads «${state.button}»`);
      need(await page(`return document.querySelector('.plan-status')?.textContent.trim() === '';`), 'the plan block says something before a code is redeemed');
      await shot('promo-field-1440.png');

      // a wrong code: one calm sentence under the field, an alert, the field marked, focus stays
      await redeemByKeyboard('WRONG-CODE-1');
      await until(async () => (await field()).error !== '', 'a wrong code showed no message');
      state = await field();
      need(state.error === 'Этот промокод не подходит. Проверьте, что он введён без опечаток, и попробуйте ещё раз.', `the wrong-code message reads «${state.error}»`);
      need(state.invalid === 'true' && state.describedBy.split(' ').includes(state.alertId) && state.focused, 'the field is not marked invalid, described by its alert and focused');
      need(state.value === 'WRONG-CODE-1', 'the typed code was thrown away');

      // the right code, typed in lower case, applied by Enter
      await redeemByKeyboard(codes.plus.toLowerCase());
      await until(async () => (await field()).success !== '', 'the right code showed no success');
      state = await field();
      const day = offset => MOSCOW_DAY.format(Date.now() + offset * 86_400_000);
      need([14.95, 15, 15.05].some(offset => state.success === `Plus до ${day(offset)}, без автопродления.`), `the success message reads «${state.success}»`);
      need(state.value === '' && state.invalid === null && state.error === '', 'the field kept the redeemed code or an old error');
      await until(() => page(`return document.querySelector('.plan-status .notice.success')?.textContent.includes('Ваш тариф: Plus до') ?? false;`), 'the plan block did not change to Plus');
      const block = await page(`return { text: document.querySelector('.plan-status .notice.success').textContent, freeChecked: [...document.querySelectorAll('app-plan-option')].find(card => card.querySelector('.plan-name').textContent.trim() === 'Free').querySelector('input').checked,
        stamps: [...document.querySelectorAll('app-plan-option')].filter(card => card.querySelector('.stamp.solid')?.textContent.includes('Ваш тариф')).map(card => card.querySelector('.plan-name').textContent.trim()),
        scrollWidth: document.documentElement.scrollWidth, clientWidth: document.documentElement.clientWidth };`);
      need(/без автопродления/u.test(block.text) && block.freeChecked && block.stamps.join() === 'Plus', `the plan block is wrong: ${JSON.stringify(block)}`);
      need(block.scrollWidth <= block.clientWidth, '/plans overflows horizontally after a redemption');
      const after = await plans();
      const days = (Date.parse(after.body.current.validUntil) - Date.now()) / 86_400_000;
      need(after.body.current.plan === 'PLUS' && after.body.current.source === 'PROMO' && after.body.current.autoRenew === false && days > 14.9 && days < 15.1,
        `the API says ${JSON.stringify(after.body.current)}`);
      const usage = await call('GET', '/api/usage');
      need(usage.status === 200 && usage.body.plan === 'PLUS', `the AI budget did not follow the entitlement: ${usage.body?.plan}`);
      await shot('promo-redeemed-1440.png');

      // the same code again: this account used it
      await redeemByKeyboard(codes.plus);
      await until(async () => (await field()).error !== '', 'a used code showed no message');
      state = await field();
      need(state.error === 'Вы уже использовали этот промокод.', `the used-code message reads «${state.error}»`);
      need(state.success === '', 'the earlier success message stayed next to a refusal');

      // a discount code (through the API: it is the fifth attempt of the hour)
      const discount = await call('POST', '/api/promo-codes/redemptions', { body: { code: codes.discount }, key: await uuid() });
      need(discount.status === 200 && discount.body.type === 'DISCOUNT_PERCENT' && discount.body.percent === 20
        && /^Скидка 20 % применится к оплате до \d+ \S+\.$/u.test(discount.body.message), `the discount answer is ${JSON.stringify(discount.body)}`);
      const discounted = await plans();
      need(discounted.body.pendingDiscount?.percent === 20, 'the API does not list the pending discount');
      need(JSON.stringify(discounted.body.current) === JSON.stringify(after.body.current), 'a pending discount changed the granted entitlement');
      await navigate('/plans', tab);
      await until(() => exists('.plan-status .notice', tab), 'the plans page does not show the discount');
      const lines = await page(`return [...document.querySelectorAll('.plan-status .notice')].map(node => node.textContent.trim());`);
      need(lines.some(line => /^Скидка 20 % на Plus применится к оплате до \d+ \S+\.$/u.test(flat(line))), `the pending discount line reads ${JSON.stringify(lines)}`);

      // the sixth attempt of the hour: the message names the wait; the API says 429 with Retry-After
      await redeemByKeyboard('SIXTH-ATTEMPT');
      await until(async () => (await field()).error !== '', 'the sixth attempt showed no message');
      state = await field();
      need(/^Слишком много попыток\. Повторите через \d+ (минуту|минуты|минут|час|часа|часов)\.$/u.test(state.error), `the rate-limit message reads «${state.error}»`);
      const limited = await call('POST', '/api/promo-codes/redemptions', { body: { code: 'SEVENTH' }, key: await uuid() });
      need(limited.status === 429 && limited.body?.code === 'RATE_LIMITED' && Number(limited.retryAfter) > 0, `the API answered ${limited.status} Retry-After ${limited.retryAfter}`);
      return { plan: 'PLUS until a date, no renewal', discount: 20, limited: limited.retryAfter };
    });

    await stage('profile_and_narrow', async () => {
      await navigate('/profile', tab);
      await until(() => exists('app-profile-plan app-promo-redeem input', tab), 'the profile has no promo field');
      const text = await page(`return { line: document.querySelector('.plan-line')?.textContent.trim(), label: document.querySelector('app-profile-plan app-promo-redeem label')?.textContent.trim(),
        discount: document.querySelector('app-profile-plan .notice')?.textContent.trim() ?? null };`);
      need(/^Тариф Plus, действует до \d+ \S+, без автопродления$/u.test(flat(text.line)) && text.label === 'Промокод', `the profile reads ${JSON.stringify(text)}`);
      need(/^Скидка 20 % на Plus применится к оплате до \d+ \S+\.$/u.test(flat(text.discount ?? '')), `the profile discount reads «${text.discount}»`);
      await metrics(390, 844, 1, true);
      for (const path of ['/plans', '/profile']) {
        await navigate(path, tab);
        await until(() => exists('app-promo-redeem input', tab), `${path} has no promo field at 390`);
        const narrow = await page(`const row = document.querySelector('app-promo-redeem .field-row').getBoundingClientRect();
          return { scrollWidth: document.documentElement.scrollWidth, clientWidth: document.documentElement.clientWidth, rowRight: row.right, innerWidth,
            button: document.querySelector('app-promo-redeem button').getBoundingClientRect().height };`);
        need(narrow.scrollWidth <= narrow.clientWidth && narrow.rowRight <= narrow.innerWidth, `${path} overflows at 390 CSS px`);
        need(narrow.button >= 44, `the promo button is ${narrow.button}px high at 390`);
      }
      await shot('promo-field-390.png');
      await metrics(1440, 900, 1, false);
      return { profile: 'plan, discount and field', width390: 'no overflow' };
    });

    // ---- the A/B variant is the server's ----------------------------------------------------------------------------------------------
    await stage('experiment_variant', async () => {
      const first = await plans();
      const second = await plans();
      const variant = first.body.experiments?.plans_year_first;
      need(['control', 'plans_year_first'].includes(variant) && variant === second.body.experiments.plans_year_first, `the variant is ${JSON.stringify(first.body.experiments)}`);
      await navigate('/plans', tab);
      await until(() => exists('app-plan-option input[type=radio]', tab), 'the plans did not render');
      const state = await page(`return { period: [...document.querySelectorAll('app-segmented-choice input[type=radio]')].find(radio => radio.checked)?.value ?? null,
        events: performance.getEntriesByType('resource').filter(entry => entry.name.endsWith('/api/experiment-events')).length };`);
      need(state.period === (variant === 'plans_year_first' ? 'YEAR' : 'MONTH'), `the variant ${variant} opened the period ${state.period}`);
      await until(async () => (await page(`return performance.getEntriesByType('resource').filter(entry => entry.name.endsWith('/api/experiment-events')).length;`)) >= 1,
        'rendering /plans sent no exposure');
      const accepted = await call('POST', '/api/experiment-events', { body: { key: 'plans_year_first', event: 'CONVERSION' } });
      const unknown = await call('POST', '/api/experiment-events', { body: { key: 'no_such_experiment', event: 'EXPOSURE' } });
      const malformed = await call('POST', '/api/experiment-events', { body: { key: 'plans_year_first', event: 'EXPOSURE', variant: 'control' } });
      need(accepted.status === 204 && unknown.status === 204 && malformed.status === 400, `experiment events answered ${accepted.status}/${unknown.status}/${malformed.status}`);
      return { variant, period: state.period };
    });

    // ---- the popup -------------------------------------------------------------------------------------------------------------------
    await stage('popup_places', async () => {
      await fixture('/__fixture/promo-popup-reset');
      const state = await popupApi();
      need(state.status === 200 && state.body.eligible === true && state.body.campaign?.id === CAMPAIGN_ID && state.body.campaign.title === 'Autumn offer'
        && state.body.campaign.cta === 'See the plans' && state.body.campaign.code === null, `the popup state is ${JSON.stringify(state.body)}`);
      need((state.cache ?? '').includes('no-store'), 'GET /api/promo-popup is cacheable');
      await forgetSession();
      // Not at the editor, capture or the deck hub of a deck: it is not even asked there.
      for (const [path, label] of [[`${deckPath}/materials/new`, 'material editor'], [`${deckPath}/capture`, 'capture'], [deckPath, 'deck hub']]) {
        await navigate(path, tab);
        await until(() => exists('main#main-content', tab), `${label}: the page did not render`);
        await noPopup(label);
        await markerAbsent(label);
      }
      // The end of a Study session is a natural breakpoint: the base flow finished its session, so the Study page opens on the completion screen.
      await navigate(`${deckPath}/study`, tab);
      await until(async () => (await exists('.completion', tab)) || (await exists('.session-setup', tab)), 'the Study page rendered neither the completion screen nor the setup');
      const finished = await exists('.completion', tab);
      if (finished) {
        await until(async () => (await dialog()) !== null, 'the popup did not appear on the Study completion screen');
        await shot('promo-popup-study-complete-1440.png');
        need(await page(`[...document.querySelectorAll('app-promo-popup dialog button')].find(node => node.textContent.trim() === 'Не сейчас').click(); return true;`), '«Не сейчас» is missing');
        await until(async () => (await dialog()) === null, '«Не сейчас» did not close the popup');
      } else await noPopup('Study setup');
      evidence.studyEnd = finished ? 'completion screen: popup shown' : 'setup: no popup';
      // Start practice, then open a fresh document while the active session is recoverable. Even without a once marker, a task must stay quiet.
      await fixture('/__fixture/promo-popup-reset');
      const started = finished && await page(`const button = [...document.querySelectorAll('button')].find(node => node.textContent.trim() === 'Начать практику'); if (!button) return false; button.click(); return true;`);
      let active = false;
      if (started) {
        await until(async () => (await exists('#study-0-answer', tab)) || (await exists('.completion', tab)) || (await exists('.notice.error', tab)), 'practice did not start');
        active = await exists('#study-0-answer', tab);
        if (active) {
          await forgetSession();
          await navigate(`${deckPath}/study`, tab);
          await until(() => exists('#study-0-answer', tab), 'the active Study session did not recover in a fresh document');
          await noPopup('active Study');
          await markerAbsent('active Study');
        }
      }
      evidence.activeStudy = active ? 'task open: no popup, not asked' : 'no task to open';
      need(!finished || active, 'the fixture could not open a Study task: the claim «never in an active session» was not exercised');
      return { quiet: ['material editor', 'capture', 'deck hub'], studyEnd: evidence.studyEnd, activeStudy: evidence.activeStudy };
    });

    await stage('popup_dismiss_with_escape', async () => {
      await fixture('/__fixture/promo-popup-reset');
      await forgetSession();
      await navigate('/decks', tab);
      await until(async () => (await dialog()) !== null, 'the popup did not appear on the deck list');
      await until(async () => (await dialog()).focusOnTitle, 'focus did not go to the popup title');
      const popup = await dialog();
      need(popup.open && popup.modal && popup.labelledBy && popup.title === 'Autumn offer', `the popup is wrong: ${JSON.stringify(popup)}`);
      need(JSON.stringify(popup.buttons) === JSON.stringify(['Закрыть', 'See the plans', 'Не сейчас', 'Больше не показывать']), `the popup buttons read ${JSON.stringify(popup.buttons)}`);
      need(popup.checkboxes === 0, 'the popup has a checkbox: nothing may be pre-ticked');
      need(popup.animation === 'none', `the popup animates under reduced motion (${popup.animation})`);
      need(popup.width <= popup.innerWidth && popup.height <= popup.innerHeight, 'the popup does not fit the viewport');
      await shot('promo-popup-1440.png');
      // The platform keeps Tab inside a modal dialog: the page behind is inert. After the last button focus goes to the browser's own UI (<body>
      // here) and comes back to the first; it never reaches a control of the page behind the popup.
      const inside = new Set();
      for (let index = 0; index < 9; index++) {
        await press('Tab');
        const where = await page(`const active = document.activeElement; const dialog = document.querySelector('app-promo-popup dialog');
          if (!active || active === document.body) return 'browser';
          return dialog.contains(active) ? 'popup:' + (active.getAttribute('aria-label') ?? active.textContent.trim()) : 'PAGE:' + active.tagName;`);
        need(!where.startsWith('PAGE:'), `Tab ${index + 1} reached the page behind the popup (${where})`);
        if (where.startsWith('popup:')) inside.add(where);
      }
      need(inside.size === 4, `Tab visited ${[...inside].join(', ')} instead of the four buttons of the popup`);
      // Esc dismisses it and focus lands on the page, not on <body>.
      await page(`document.querySelector('app-promo-popup dialog h2').focus(); return true;`);
      await press('Escape');
      await until(async () => (await dialog()) === null, 'Esc did not close the popup');
      need(await page(`return document.activeElement !== document.body;`), 'focus was lost to <body> when the popup closed');
      await silenced('Esc');
      await until(() => page(`return !Object.keys(localStorage).some(key => key.startsWith(args[0]));`, PREFERENCE_KEY),
        'the real recorded preference header did not confirm the dismissal receipt');
      need(await page(`return sessionStorage.getItem(args[0]) !== null;`, SESSION_KEY), 'the session marker was not set');
      // Once per browser session, and the cooldown holds when the session is new.
      await navigate('/decks', tab);
      await until(() => exists('main#main-content', tab), 'the deck list did not render');
      await noPopup('the same session');
      await forgetSession();
      await navigate('/decks', tab);
      await until(() => exists('main#main-content', tab), 'the deck list did not render');
      await noPopup('a new session inside the cooldown');
      return { escape: 'dismissed', onceInSession: true, cooldown: 'holds' };
    });

    await stage('popup_close_and_accept', async () => {
      await fixture('/__fixture/promo-popup-reset');
      await forgetSession();
      await navigate('/decks', tab);
      await until(async () => (await dialog()) !== null, 'the popup did not appear again after the reset');
      need(await page(`document.querySelector('app-promo-popup dialog [aria-label="Закрыть"]').click(); return true;`), 'the close button is missing');
      await until(async () => (await dialog()) === null, '«×» did not close the popup');
      await silenced('«×»');

      await fixture('/__fixture/promo-popup-reset');
      await forgetSession();
      await navigate('/decks', tab);
      await until(async () => (await dialog()) !== null, 'the popup did not appear for the primary action');
      await metrics(390, 844, 1, true);
      await frames();
      const narrow = await dialog();
      need(narrow.width >= narrow.innerWidth * 0.95 && narrow.height >= narrow.innerHeight * 0.95, `at 390 px the popup is ${narrow.width}x${narrow.height}, not almost the whole screen`);
      await shot('promo-popup-390.png');
      await metrics(320, 800, 1, true);
      await page(`document.documentElement.style.fontSize = '200%'; return true;`);
      await frames();
      const reflow = await page(`const surface = document.querySelector('app-promo-popup dialog'); const rect = surface.getBoundingClientRect();
        return { scrollWidth: surface.scrollWidth, clientWidth: surface.clientWidth, width: rect.width, viewport: innerWidth,
          rootSize: parseFloat(getComputedStyle(document.documentElement).fontSize) };`);
      need(reflow.rootSize >= 32 && reflow.width <= reflow.viewport && reflow.scrollWidth <= reflow.clientWidth,
        `the popup overflows at320 CSS px with doubled text: ${JSON.stringify(reflow)}`);
      await shot('promo-popup-320-double-text.png');
      await page(`document.documentElement.style.removeProperty('font-size'); return true;`);
      await metrics(1440, 900, 1, false);
      need(await page(`[...document.querySelectorAll('app-promo-popup dialog button')].find(node => node.textContent.trim() === 'See the plans').click(); return true;`), 'the primary action is missing');
      await until(async () => (await page('return location.pathname;')) === '/plans' && (await dialog()) === null, 'the primary action did not open /plans with the popup gone');
      need(await page(`return new URL(location.href).searchParams.get('promo') === null;`), 'a campaign without a code put a promo parameter in the address');
      await silenced('the primary action');
      return { close: 'dismissed', accept: '/plans' };
    });

    await stage('popup_decline_is_final', async () => {
      await fixture('/__fixture/promo-popup-reset');
      await forgetSession();
      await navigate('/decks', tab);
      await until(async () => (await dialog()) !== null, 'the popup did not appear for the decline');
      need(await page(`[...document.querySelectorAll('app-promo-popup dialog button')].find(node => node.textContent.trim() === 'Больше не показывать').click(); return true;`), 'the decline button is missing');
      await until(async () => (await dialog()) === null, '«Больше не показывать» did not close the popup');
      await forgetSession();
      await navigate('/decks', tab);
      await until(() => exists('main#main-content', tab), 'the deck list did not render');
      await noPopup('after a decline');
      await silenced('a decline');
      return { decline: 'final' };
    });
  } finally {
    await page(`document.documentElement.style.removeProperty('font-size'); return true;`).catch(() => {});
    await fixture('/__fixture/learning-default').catch(() => {});
    await reducedMotion(false).catch(() => {});
    await metrics(1280, 900, 1, false).catch(() => {});
  }
  record('promo_codes_experiments_popup_real_api', evidence);
  return evidence;
}
