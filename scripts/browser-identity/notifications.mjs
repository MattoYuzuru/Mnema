// Real-browser check of the notification center (#284): a REAL producer, then the bell, panel, toasts and Study quiet mode.
// Runs with `--authoring --media`, after the base Study flow, on the signed-in account's tab. Node 24 built-ins only.
//
// Producer: a deliberately corrupt file ("broken-image.png": not a PNG, declared image/png) is dropped into the real
// editor upload surface. It passes the client checks, is uploaded and finalized, and the REAL media worker container
// rejects it, so the REAL Learning backend publishes MEDIA_PROCESSING_FAILED for the owner. Nothing is stubbed; the page is
// asked for nothing but what a user sees. A broken step is a finding for the product, never something to work around:
// it throws, a failure screenshot is written and the run fails with the step name.
import { writeFile } from 'node:fs/promises';
import { join } from 'node:path';

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

export async function runNotifications(ctx) {
  const { tab, config, record, SafeFailure, until, exists, sanitizedLocation, navigate, saveScreenshot, setStep,
    clickText, deckPath, bearer, answerText } = ctx;
  const need = (value, label) => { if (!value) throw new SafeFailure(label); };
  let lastCall = '';
  const page = (body, ...args) => { lastCall = body.trim().replace(/\s+/g, ' ').slice(0, 90); return tab.callFunction(`async function(...args) { ${body} }`, args); };
  const trace = [];
  const visibility = label => tab.call('Runtime.evaluate', { expression: 'document.visibilityState[0] + (document.hasFocus() ? "f" : "-")' })
    .then(result => trace.push(`${label}:${result.result?.value}`), () => trace.push(`${label}:blocked`));
  const renderSettled = async () => {
    await visibility('before-raf');
    return page(`return Promise.race([new Promise(resolve =>
      requestAnimationFrame(() => requestAnimationFrame(() => resolve(true)))),
      new Promise(resolve => setTimeout(() => resolve(false), 3000))]);`);
  };
  const press = async (key, code, virtualKeyCode, keyText) => {
    const event = { key, code, windowsVirtualKeyCode: virtualKeyCode, nativeVirtualKeyCode: virtualKeyCode };
    await tab.call('Input.dispatchKeyEvent', keyText === undefined
      ? { type: 'rawKeyDown', ...event } : { type: 'keyDown', text: keyText, unmodifiedText: keyText, ...event });
    await tab.call('Input.dispatchKeyEvent', { type: 'keyUp', ...event });
    await renderSettled();
  };
  const keys = { Tab: () => (trace.push('Tab'), press('Tab', 'Tab', 9)), Enter: () => (trace.push('Enter'), press('Enter', 'Enter', 13, '\r')),
    // Headless Chrome hides a background tab that receives Escape and stops painting it (rAF never fires again), so the
    // tab is brought to front first. This is a property of the harness's Chrome, not of the product.
    Escape: async () => { trace.push('Esc'); await tab.call('Page.bringToFront'); await press('Escape', 'Escape', 27); } };
  /** Light dismiss by a real mouse press on an empty part of the page. */
  const clickOutside = async () => {
    trace.push('click-outside');
    await tab.call('Page.bringToFront');
    for (const type of ['mousePressed', 'mouseReleased']) {
      await tab.call('Input.dispatchMouseEvent', { type, x: 40, y: 650, button: 'left', clickCount: 1 });
    }
    await renderSettled();
  };
  const viewport = async (width, height, mobile) => {
    trace.push(`viewport${width}`);
    await tab.call('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: 1, mobile });
    await renderSettled();
  };
  const reducedMotion = value => tab.call('Emulation.setEmulatedMedia',
    { features: [{ name: 'prefers-reduced-motion', value }] });

  // ----- what the user sees ----------------------------------------------------------------------------------------
  const toastState = () => page(`
    const region = document.querySelector('section.toast-region');
    if (!region) return null;
    const rect = element => { const r = element.getBoundingClientRect();
      return { left: r.left, right: r.right, top: r.top, bottom: r.bottom, width: r.width }; };
    let open = false; try { open = region.matches(':popover-open'); } catch { open = false; }
    return { open, label: region.getAttribute('aria-label'), popover: region.getAttribute('popover'),
      live: region.hasAttribute('aria-live') || region.querySelector('[aria-live], [role=status], [role=alert]') !== null,
      inViewport: innerWidth,
      toasts: [...region.querySelectorAll('.toast')].map(toast => ({ severity: toast.dataset.severity,
        text: toast.querySelector('.text')?.textContent.trim() ?? '', rect: rect(toast),
        closeName: toast.querySelector('button.close')?.getAttribute('aria-label') ?? null,
        animation: getComputedStyle(toast).animationName })),
      more: region.querySelector('.more')?.textContent.trim() ?? null };`);
  const bellState = () => page(`
    const bell = document.querySelector('app-notification-bell .bell');
    if (!bell) return null;
    const panel = document.querySelector('app-notification-bell [popover]');
    let open = false; try { open = panel.matches(':popover-open'); } catch { open = false; }
    const rect = panel.getBoundingClientRect();
    return { label: bell.getAttribute('aria-label'), expanded: bell.getAttribute('aria-expanded'),
      controls: bell.getAttribute('aria-controls'), panelId: panel.id, open,
      badge: bell.querySelector('.badge')?.textContent.trim() ?? null,
      badgeHidden: bell.querySelector('.badge')?.getAttribute('aria-hidden') ?? null,
      heading: panel.querySelector('h2')?.textContent.trim() ?? null,
      entries: [...panel.querySelectorAll('li')].map(item => item.querySelector('.text')?.textContent.trim() ?? ''),
      empty: panel.querySelector('.empty')?.textContent.trim() ?? null,
      panelRect: { left: rect.left, right: rect.right, top: rect.top, bottom: rect.bottom },
      innerWidth, scrollWidth: document.documentElement.scrollWidth, clientWidth: document.documentElement.clientWidth };`);
  const activeElement = () => page(`const e = document.activeElement;
    return { tag: e?.tagName ?? null, id: e?.id ?? '', cls: e?.className?.toString?.() ?? '',
      inRegion: Boolean(e?.closest?.('section.toast-region')), isBell: Boolean(e?.matches?.('.bell')),
      isBody: e === document.body, text: (e?.textContent ?? '').trim().slice(0, 40) };`);
  const serverList = () => page(`const response = await fetch(args[0] + '/api/notifications?limit=50',
      { credentials: 'omit', headers: { Authorization: args[1] } });
    return { status: response.status, body: response.ok ? await response.json() : null };`,
  config.frontend, 'Bearer ' + bearer);
  const serverFailures = async () => {
    const result = await serverList();
    need(result.status === 200, 'notification list unavailable from the real API');
    return result.body;
  };

  // The persistent status region announces and then clears its text, so record what it said instead of racing it.
  const observeAnnouncements = () => page(`
    globalThis.__notificationAnnouncements = [];
    const status = document.querySelector('app-toast-region [role=status]');
    if (!status) return false;
    new MutationObserver(() => { const text = status.textContent.trim();
      if (text) globalThis.__notificationAnnouncements.push(text); })
      .observe(status, { childList: true, characterData: true, subtree: true });
    return true;`);
  const announcements = () => page('return globalThis.__notificationAnnouncements ?? [];');

  /** A real "page became visible again": the store polls at once. The 45 s timer would do the same, only slower. */
  let visibilityKicks = 0;
  const kickPoll = async () => {
    visibilityKicks++;
    await page("document.dispatchEvent(new Event('visibilitychange')); return true;");
  };

  const failureShot = async name => {
    try { await saveScreenshot(`failure-notifications-${name}.png`, tab); } catch { /* the original failure is the verdict */ }
  };
  const stage = async (name, body) => {
    setStep(`notifications_${name}`);
    try { return await body(); } catch (error) {
      const alive = await tab.call('Runtime.evaluate', { expression: 'document.visibilityState + "/" + document.hasFocus() + "/" + document.querySelectorAll("[popover]:popover-open").length' })
        .then(result => result.result?.value, failure => String(failure?.message).slice(0, 60));
      let frames = '';
      if (String(alive).includes('timeout')) {
        // A blocked main thread: pause it and report where it is stuck (script basename, line, column only).
        let resolvePaused;
        const paused = new Promise(resolve => { resolvePaused = resolve; });
        tab.on('Debugger.paused', event => resolvePaused(event.callFrames));
        await tab.call('Debugger.enable').catch(() => {});
        await tab.call('Debugger.pause').catch(() => {});
        setTimeout(() => resolvePaused([]), 8000);
        frames = (await paused).slice(0, 8).map(frame => `${frame.functionName || '?'}@${String(frame.url).split('/').pop()}:${frame.location.lineNumber}:${frame.location.columnNumber}`).join(' <- ');
      }
      const snapshot = await bellState().then(state => state === null ? null : { open: state.open, expanded: state.expanded,
        entries: state.entries.length, empty: state.empty !== null, badge: state.badge }, () => 'unavailable');
      await failureShot(name);
      // Only our own labels and CDP method names: never page content, URLs or credentials.
      await writeFile(join(config.output, `failure-notifications-${name}.txt`),
        (error instanceof SafeFailure ? error.message : `driver: ${String(error?.message ?? error).slice(0, 160)} (trace: ${trace.filter(entry => !entry.startsWith('before-raf')).slice(-20).join(' ')}; last page call: ${lastCall}; page state: ${alive}; stuck at: ${frames})`) + `\nbell: ${JSON.stringify(snapshot)}\n`).catch(() => {});
      throw error;
    }
  };

  /** Drops the corrupt file into the editor's real upload surface and waits until it left the browser. */
  const dropCorruptImage = async name => {
    await navigate(deckPath + '/materials/new', tab);
    await until(() => exists('.editor-toolbar', tab), 'new-material editor absent');
    need(await clickText('.editor-toolbar button', 'Медиа', tab), 'media controls absent');
    need(await clickText('.rich-kind-picker button', 'Изображение', tab), 'image controls absent');
    await until(() => exists('app-native-media-upload .media-drop', tab), 'media upload surface absent');
    need(await page(`
      const bytes = new Uint8Array(2048);
      for (let i = 0; i < bytes.length; i++) bytes[i] = (i * 131 + 17) & 255;   // not a PNG signature, not an image at all
      const transfer = new DataTransfer();
      transfer.items.add(new File([bytes], args[0], { type: 'image/png' }));
      const drop = document.querySelector('app-native-media-upload .media-drop');
      if (!(drop instanceof HTMLElement)) return false;
      drop.dispatchEvent(new DragEvent('drop', { bubbles: true, cancelable: true, dataTransfer: transfer }));
      return true;`, name), 'corrupt image could not be dropped');
    await until(() => page(`const row = [...document.querySelectorAll('.media-row')]
      .find(item => item.textContent.includes(args[0]));
      const status = row?.querySelector('.media-status')?.textContent ?? '';
      return /Проверяем файл|Обрабатываем файл|не прошёл проверку/.test(status);`, name),
    'corrupt upload never reached server-side verification', 30_000);
  };

  /** SPA navigation by a real link click: the notification store of this page load survives it. */
  const clickLink = async selector => need(await page(`const link = document.querySelector(args[0]);
    if (!(link instanceof HTMLElement)) return false; link.click(); return true;`, selector), 'link absent: ' + selector);

  const newestMediaFailure = body => body.items.find(item => item.kind === 'MEDIA_PROCESSING_FAILED') ?? null;
  const REASON_TEXT = {
    VERIFICATION_REJECTED: 'Не удалось принять изображение: файл не прошёл проверку',
    PROCESSING_FAILED: 'Не удалось обработать изображение'
  };

  await tab.call('Page.bringToFront');
  const evidence = { producer: 'media-worker rejection of a corrupt upload through the real editor' };

  // ======================================================================================================================
  // 1. Toast, announcement and badge after a REAL failure, on a different page
  // ======================================================================================================================
  const first = await stage('toast', async () => {
    const before = await serverFailures();
    need(before.items.every(item => item.kind !== 'MEDIA_PROCESSING_FAILED'), 'a media failure existed before the corrupt upload');
    await dropCorruptImage('broken-image.png');
    await clickLink('.primary-nav a[href="/decks"]');
    await until(async () => (await sanitizedLocation(tab)) === '/decks' && await exists('.deck-row', tab),
      'SPA navigation to the deck list did not complete');
    need((await toastState()).toasts.length === 0 && (await bellState()).badge === null, 'a toast or badge existed before any failure');
    need(await observeAnnouncements(), 'persistent status region absent from the shell');

    // The real worker decides when the notification exists; poll the API like a user's other device would.
    let notification = null;
    await until(async () => { notification = newestMediaFailure(await serverFailures()); return notification !== null; },
      'the real media worker never published MEDIA_PROCESSING_FAILED', 120_000);
    need(notification.severity === 'ERROR' && ['VERIFICATION_REJECTED', 'PROCESSING_FAILED'].includes(notification.params.reason)
      && notification.params.mediaKind === 'IMAGE' && Object.keys(REASON_TEXT).includes(notification.params.reason),
    'the published notification does not describe a rejected image');
    need(notification.route === 'NONE', 'the media pipeline notification should carry no destination');
    evidence.reason = notification.params.reason;

    let last = 0;
    await until(async () => {
      if ((await toastState()).toasts.length > 0) return true;
      if (Date.now() - last > 2500) { last = Date.now(); await kickPoll(); }
      return false;
    }, 'no toast appeared after the failure and a visibility poll', 40_000);
    await renderSettled();

    const toast = await toastState();
    need(toast.toasts.length === 1, 'expected exactly one toast');
    const [item] = toast.toasts;
    need(item.severity === 'ERROR', 'the failure toast is not ERROR');
    const expected = REASON_TEXT[notification.params.reason];
    need(item.text.startsWith(expected), 'toast text does not name the outcome: ' + item.text);
    need(item.closeName === 'Закрыть уведомление', 'toast has no named close button');
    need(toast.open && toast.popover === 'manual' && toast.label === 'Уведомления', 'toast region is not a labelled top-layer popover');
    need(!toast.live, 'toast region must not be a live region');
    need(item.rect.left >= 0 && item.rect.right <= toast.inViewport, 'toast sticks out of the viewport');
    const said = await announcements();
    need(said.some(text => text.startsWith(expected)), 'persistent status region did not announce the outcome');
    const bell = await bellState();
    const count = Number(bell.badge);
    need(Number.isInteger(count) && count >= 1 && bell.badgeHidden === 'true', 'bell badge missing or not hidden from assistive technology');
    need(bell.label === `Уведомления, ${count} непрочитанн${count === 1 ? 'ое' : 'ых'}`, 'bell name does not carry the count: ' + bell.label);
    need(bell.heading === 'Входящие' && bell.controls === bell.panelId, 'bell does not control the «Входящие» panel');
    evidence.toast = { text: item.text, announced: true, badge: count, bellName: bell.label, visibilityKicks };
    return { notification, expected };
  });

  await stage('toast_visuals', async () => {
    await reducedMotion('reduce');
    let state = await toastState();
    // Angular scopes keyframe names (`_ngcontent-…-toast-fade`).
    need(state.toasts[0].animation.endsWith('toast-fade'), 'reduced motion must fade only, got ' + state.toasts[0].animation);
    await reducedMotion('no-preference');
    await renderSettled();
    state = await toastState();
    need(state.toasts[0].animation.endsWith('toast-in'), 'ordinary motion lost its slide-in, got ' + state.toasts[0].animation);
    await reducedMotion('reduce');
    await renderSettled();
    await saveScreenshot('notifications-toast-1440.png', tab);

    await viewport(390, 844, true);
    const mobile = await toastState();
    need(mobile.toasts.length === 1 && mobile.toasts[0].rect.left >= 0 && mobile.toasts[0].rect.right <= 390
      && mobile.toasts[0].rect.bottom <= 844, 'toast does not fit the 390 px viewport');
    need((await bellState()).scrollWidth <= 390, 'horizontal overflow at 390 px with a toast');
    await saveScreenshot('notifications-toast-390.png', tab);
    await viewport(1440, 900, false);
    evidence.reducedMotion = { fadeOnly: true }; evidence.mobile390 = { toastFits: true, noOverflow: true };
  });

  await stage('toast_keyboard', async () => {
    // Real keyboard: Tab from the last footer link enters the toast region (DOM order), Esc closes the toast.
    need(await page(`const link = [...document.querySelectorAll('.footer nav a')].at(-1);
      link.focus(); return document.activeElement === link;`), 'footer link cannot take focus');
    await keys.Tab();
    const inside = await activeElement();
    need(inside.inRegion && inside.cls.includes('close'), 'Tab from the footer did not reach the toast close button');
    await keys.Escape();
    await until(async () => { const state = await toastState(); return state.toasts.length === 0 && !state.open; },
      'Esc did not close the toast and its popover', 10_000);
    const focus = await activeElement();
    need(!focus.isBody && focus.tag === 'A' && focus.text.length > 0, 'focus was lost to <body> after closing the toast');
    evidence.toastEsc = { closed: true, focusReturnedTo: 'previous link', hoverPause: 'not exercised: ERROR has no timeout and the echo toast has no caller' };
  });

  // ======================================================================================================================
  // 2. Bell by keyboard, panel, read cursor, light dismiss, dismissal
  // ======================================================================================================================
  await stage('bell_panel', async () => {
    need(await page(`const link = [...document.querySelectorAll('.primary-nav a')].at(-1);
      link.focus(); return document.activeElement === link;`), 'last navigation link cannot take focus');
    await keys.Tab();
    need((await activeElement()).isBell, 'Tab from the navigation did not reach the bell');
    await keys.Enter();
    await until(async () => { const s = await bellState(); return s.open && s.entries.length > 0; }, 'bell panel did not open with the entry', 15_000);
    let bell = await bellState();
    need(bell.expanded === 'true' && bell.open, 'aria-expanded does not follow the open panel');
    need(bell.entries.length === 1 && bell.entries[0].startsWith(first.expected), 'panel does not list the failure: ' + bell.entries.join('|'));
    await until(async () => (await bellState()).badge === null, 'badge did not clear after the read cursor moved', 15_000);
    bell = await bellState();
    need(bell.label === 'Уведомления', 'bell name still reports unread: ' + bell.label);
    const server = await serverFailures();
    need(server.unreadCount === 0 && server.items.length === 1, 'server read cursor did not move to the shown notification');
    await keys.Escape();
    await until(async () => { const state = await bellState(); return !state.open && state.expanded === 'false'; },
      'Esc did not close the panel', 10_000);
    const focus = await activeElement();
    need(focus.isBell, 'focus did not return to the bell after Esc');
    evidence.panel = { keyboardOpen: true, listed: true, badgeCleared: true, serverUnread: 0, escReturnsFocusToBell: true };

    await keys.Enter();
    await until(async () => (await bellState()).open, 'panel did not reopen', 10_000);
    await viewport(390, 844, true);
    bell = await bellState();
    need(bell.panelRect.left >= 0 && bell.panelRect.right <= 390 && bell.panelRect.top >= 0 && bell.scrollWidth <= 390,
      'panel does not fit the 390 px viewport');
    await saveScreenshot('notifications-panel-390.png', tab);
    await viewport(1440, 900, false);
    await saveScreenshot('notifications-panel-1440.png', tab);

    trace.push('dismiss-click');
    need(await page(`const button = document.querySelector('app-notification-bell li .dismiss');
      if (!(button instanceof HTMLButtonElement)) return false; button.click(); return true;`), 'dismiss button absent');
    await until(async () => (await bellState()).empty !== null, 'dismissed entry did not leave the panel', 10_000);
    trace.push('dismissed');
    const after = await serverFailures();
    need(after.items.length === 0, 'server still lists the dismissed notification');
    await clickOutside();
    need(!(await bellState()).open, 'a click outside did not close the panel');
    evidence.dismiss = { entryRemoved: true, serverListEmpty: true };
  });

  // ======================================================================================================================
  // 3. Study quiet mode: a failure that arrives while a task is open waits for the feedback pause
  // ======================================================================================================================
  await stage('quiet_mode', async () => {
    const known = new Set((await serverFailures()).items.map(item => item.notificationId));
    await dropCorruptImage('broken-image-2.png');
    // Reach the answering phase of a practice session through links, so this page load's store keeps its baseline.
    await clickLink('.primary-nav a[href="/decks"]');
    await until(() => exists(`.deck-row[href="${deckPath}"]`, tab), 'deck list did not open');
    await clickLink(`.deck-row[href="${deckPath}"]`);
    await until(() => exists(`a.button.primary[href="${deckPath}/study"]`, tab), 'deck page did not open');
    await clickLink(`a.button.primary[href="${deckPath}/study"]`);
    await until(() => exists('.session-setup', tab), 'Study setup did not open');
    need(await clickText('.session-setup button', 'Начать короткую', tab), 'quick session action absent');
    await until(async () => await exists('.completion', tab) || await exists('#study-0-answer', tab), 'Study did not start');
    if (!(await exists('#study-0-answer', tab))) {
      need(await clickText('.completion button', 'Начать практику', tab), 'practice action absent');
      await until(() => exists('#study-0-answer', tab), 'practice session did not reach the answering phase');
    }
    need(await observeAnnouncements(), 'status region absent');

    let notification = null;
    await until(async () => {
      notification = (await serverFailures()).items.find(item => item.kind === 'MEDIA_PROCESSING_FAILED' && !known.has(item.notificationId)) ?? null;
      return notification !== null;
    }, 'the real media worker never published the second failure', 120_000);

    // The store learns of it now (a real visibility poll): the badge moves at once, the toast stays away.
    await kickPoll();
    await until(async () => (await bellState()).badge !== null, 'badge did not update while answering', 15_000);
    need(await exists('#study-0-answer', tab), 'Study left the answering phase unexpectedly');
    await sleep(1500);
    let state = await toastState();
    need(state.toasts.length === 0 && !state.open, 'a toast interrupted the answering phase');
    need((await announcements()).length === 0, 'the status region announced during the answering phase');
    const answering = await bellState();
    evidence.quiet = { badgeWhileAnswering: answering.label, toastWhileAnswering: false };

    // Natural pause: feedback.
    need(await page(`const field = document.querySelector('#study-0-answer');
      field.focus();
      const proto = field instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
      Object.getOwnPropertyDescriptor(proto, 'value').set.call(field, args[0]);
      field.dispatchEvent(new Event('input', { bubbles: true })); field.dispatchEvent(new Event('change', { bubbles: true }));
      return true;`, answerText), 'answer field absent');
    need(await clickText('button', 'Проверить ответ', tab), 'submit action absent');
    await until(() => exists('#feedback-title', tab), 'feedback did not arrive');
    await until(async () => (await toastState()).toasts.length > 0, 'the held toast did not show at the feedback pause', 15_000);
    state = await toastState();
    need(await exists('#feedback-title', tab) && state.toasts.length === 1 && state.toasts[0].severity === 'ERROR', 'held toast is not the failure at the pause');
    const focus = await activeElement();
    need(focus.id === 'feedback-title', 'the toast stole focus from the feedback heading');
    need((await announcements()).some(text => text.startsWith(REASON_TEXT[notification.params.reason])), 'held toast was not announced at the pause');
    await saveScreenshot('notifications-study-feedback-toast-1440.png', tab);
    evidence.quiet.toastAtFeedback = true; evidence.quiet.focusKept = 'feedback heading';

    // Close it with the «×» button (the single-pointer alternative to swipe and Esc) and finish the session.
    need(await page(`const close = document.querySelector('section.toast-region .toast button.close');
      if (!(close instanceof HTMLButtonElement)) return false; close.click(); return true;`), 'toast close button absent');
    await until(async () => (await toastState()).toasts.length === 0, 'close button did not remove the toast', 10_000);
    evidence.quiet.closedWithButton = true;
    need(await clickText('.feedback-card button.primary', 'Продолжить', tab), 'continue action absent');
    await until(() => exists('.completion', tab), 'practice session did not complete');
    // Leave the inbox clean for the following scenarios.
    need(await page(`const bell = document.querySelector('app-notification-bell .bell'); bell.click(); return true;`), 'bell absent');
    await until(async () => (await bellState()).open, 'panel did not open for cleanup', 10_000);
    // One entry at a time, each until it has left the panel, so nothing depends on how requests interleave.
    for (let remaining = (await bellState()).entries.length; remaining > 0; remaining--) {
      need(await page(`const button = document.querySelector('app-notification-bell li .dismiss');
        if (!(button instanceof HTMLButtonElement)) return false; button.click(); return true;`), 'cleanup dismiss button absent');
      await until(async () => (await bellState()).entries.length < remaining, 'cleanup dismissal did not leave the panel', 10_000);
    }
    await until(async () => (await bellState()).empty !== null && (await serverFailures()).items.length === 0,
      'panel and server list are not both empty after cleanup', 10_000);
    await clickOutside();
  });

  // Esc on the now EMPTY panel (the only combination the first runs could not get through): the page must stay alive.
  await stage('escape_empty_panel', async () => {
    await page(`const bell = document.querySelector('app-notification-bell .bell'); bell.focus(); return true;`);
    await keys.Enter();
    await until(async () => (await bellState()).open, 'panel did not open', 10_000);
    // The panel reloads on open; wait for the observable end state instead of reading it once.
    await until(async () => (await bellState()).empty !== null, 'panel is not empty', 10_000);
    need((await serverFailures()).items.length === 0, 'server list is not empty');
    await keys.Escape();
    // `toggle` is dispatched as a task and Angular renders on the next frame: wait for the closed state.
    await until(async () => { const state = await bellState(); return !state.open && state.expanded === 'false'; },
      'Esc did not close the empty panel', 10_000);
    need((await activeElement()).isBell, 'focus did not return to the bell');
  });

  evidence.visibilityKicks = visibilityKicks;
  record('notifications_center_real_media_failure', evidence);
  return evidence;
}
