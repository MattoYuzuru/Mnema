/** Owner console against real Identity/Learning/PostgreSQL and a disposable real bot bridge. */
import { toolkit } from './plans.mjs';

export async function runAdmin(ctx) {
  const { config, record, navigate, fill, submit, clickText, saveScreenshot, setStep } = ctx;
  const { need, page, metrics, reducedMotion, frames, press, api, until, exists, tab } = toolkit(ctx);
  setStep('admin_owner_access');
  const outsider = await api('GET', '/api/admin/console/access');
  need(outsider.status === 403, 'a learner gained console access');
  await navigate('/manage');
  await until(() => exists('#login-name'), 'anonymous console did not require sign-in');
  await fill('#login-name', config.eventsLogin); await fill('#password', config.password); await submit();
  await until(() => exists('app-admin-report-page .report-facts'), 'real owner report did not load');
  need(await page(`return !document.querySelector('app-goal-onboarding, app-promo-popup-host, app-notification-bell')
    && document.querySelector('app-admin-report-page')?.textContent.includes('Нет источника');`), 'console showed learner prompts or invented revenue');
  need(await page(`return document.querySelector('app-admin-report-page')?.textContent.includes('USD')
    && document.querySelector('app-admin-report-page')?.textContent.includes('кредит');`), 'money and product units are not labelled separately');
  await reducedMotion(true);
  for (const [width, height] of [[1440,1000],[768,1000],[390,1000],[320,1100]]) {
    await metrics(width,height,1,width < 500); await frames();
    need(await page('return document.documentElement.scrollWidth <= document.documentElement.clientWidth;'), 'console overview horizontal overflow');
    await saveScreenshot(`admin-overview-${width}.png`,tab);
  }
  await metrics(1440,1000,1,false);
  setStep('admin_users');
  await navigate('/manage/users');
  await until(() => exists('app-admin-users-page tbody a'), 'real Identity directory is empty');
  await fill('app-admin-users-page [formControlName=query]', 'browser_fixture');
  need(await clickText('app-admin-users-page button','Применить'), 'directory filter action absent');
  await until(() => page(`return [...document.querySelectorAll('app-admin-users-page tbody th')].some(x=>x.textContent.includes('browser_fixture'));`), 'directory filter did not return fixture accounts');
  const first = await page(`return document.querySelector('app-admin-users-page tbody a')?.getAttribute('href');`);
  need(first?.startsWith('/manage/users/'), 'directory detail link is not canonical');
  await navigate(first);
  await until(() => page(`return document.querySelector('app-admin-users-page')?.textContent.includes('Текущие данные аккаунта');`), 'user mini report did not arrive');
  need(await page(`return !document.querySelector('app-admin-users-page textarea[formControlName=prompt]')
    && document.querySelector('app-admin-users-page')?.textContent.includes('Списано кредитов');`), 'user usage missing or private content editor appeared');
  await saveScreenshot('admin-user-1440.png',tab);
  await navigate('/manage/users?query=browser_fixture');
  await until(() => exists('app-admin-users-page tbody a'), 'return to filtered directory failed');
  setStep('admin_promos');
  await navigate('/manage/promos');
  await until(() => exists('app-admin-promos-page form.editor'), 'current owner promo permission missing');
  await fill('app-admin-promos-page [formControlName=channel]','browser-console-fixture');
  need(await clickText('app-admin-promos-page button','Создать промокод'), 'create promo action missing');
  await until(() => exists('#code-created'), 'idempotent promo creation failed');
  need(await page(`return document.querySelector('app-admin-promos-page input[readonly]')?.value.replaceAll('-', '').length === 12;`), 'one-time random code missing');
  need(await clickText('app-admin-promos-page button','Код сохранён, закрыть'), 'one-time code acknowledgement missing');
  await until(() => page(`return document.querySelector('app-admin-promos-page tbody')?.textContent.includes('browser-console-fixture');`), 'created code missing from real list');
  need(await clickText('app-admin-promos-page tbody button','Выключить'), 'promo disable action absent');
  await until(() => page(`return document.querySelector('app-admin-promos-page tbody')?.textContent.includes('Выключен');`), 'promo did not disable');
  await saveScreenshot('admin-promos-1440.png',tab);
  setStep('admin_support');
  await navigate('/manage/support');
  await until(() => exists('.ticket-choice'), 'real bot queue did not load');
  await page(`const status=document.querySelector('app-admin-support-page [formControlName=status]');
    status.value='';status.dispatchEvent(new Event('change',{bubbles:true}));
    const field=document.querySelector('app-admin-support-page [formControlName=delivery]');
    field.value='uncertain';field.dispatchEvent(new Event('change',{bubbles:true}));return true;`);
  need(await clickText('app-admin-support-page button','Применить'), 'ticket filter action absent');
  await until(() => page(`return document.querySelectorAll('.ticket-choice').length===1 && document.querySelector('.ticket-choice')?.textContent.includes('Доставка неизвестна');`), 'uncertain-delivery queue filter failed');
  need(await clickText('app-admin-support-page button','Сбросить'), 'ticket reset missing');
  await until(() => page('return document.querySelectorAll(".ticket-choice").length===4;'), 'ticket reset did not restore queue');
  await navigate('/manage/support/1');
  await until(() => exists('.conversation .internal'), 'private note missing from admin conversation');
  need(await page(`return !document.querySelector('.conversation img, .conversation script')
    && document.querySelector('.conversation')?.textContent.includes('Внутренняя заметка');`), 'unsafe ticket HTML or missing internal note label');
  await fill('app-admin-support-page textarea[formControlName=text]', 'Synthetic owner answer <img src=x onerror=alert(1)>');
  need(await clickText('app-admin-support-page button','Обновить переписку'), 'thread refresh action absent');
  await until(() => page(`return document.querySelector('app-admin-support-page textarea')?.value.startsWith('Synthetic owner answer');`), 'thread refresh lost composer draft');
  need(await clickText('app-admin-support-page button','Отправить ответ'), 'reply action missing');
  await until(() => page(`return document.querySelector('app-admin-support-page')?.textContent.includes('Ответ принят в очередь.');`), 'reply was not acknowledged by actual bridge');
  await until(() => page(`return document.querySelector('.conversation')?.textContent.includes('Synthetic owner answer');`), 'queued reply missing from timeline');
  need(await page(`return !document.querySelector('.conversation img') && document.querySelector('.conversation')?.textContent.includes('В очереди');`), 'queued reply displayed unsafe HTML or fake delivery');
  for (const width of [1440,768,390,320]) {
    await metrics(width,1100,1,width < 500);await frames();
    need(await page('return document.documentElement.scrollWidth <= document.documentElement.clientWidth;'), 'support horizontal overflow');
    await saveScreenshot(`admin-support-${width}.png`,tab);
  }
  // Doubling actual text is separate from DPR; native browser zoom/AT remain unclaimed.
  await page(`document.documentElement.style.fontSize='200%';return true;`);await frames();
  need(await page('return document.documentElement.scrollWidth <= document.documentElement.clientWidth;'), 'support doubled text horizontal overflow');
  await saveScreenshot('admin-support-320-double-text.png',tab);
  await page(`document.documentElement.style.removeProperty('font-size');return true;`);
  await metrics(1440,1000,1,false);
  setStep('admin_audit');
  await navigate('/manage/audit');
  await until(() => page(`return document.querySelector('app-admin-audit-page')?.textContent.includes('Промокод');`), 'successful promo action absent from real audit');
  await saveScreenshot('admin-audit-1440.png',tab);
  await navigate('/manage/users');await until(() => exists('app-admin-users-page input[type=search]'),'directory keyboard target absent');
  await page(`document.querySelector('app-admin-users-page input[type=search]').focus();return true;`);await press('Tab');
  need(await page(`return document.activeElement instanceof HTMLElement && document.activeElement.tagName!=='BODY';`), 'keyboard left owner controls');
  await navigate('/login');await until(()=>exists('[data-testid=logout]'),'owner logout unavailable');
  need(await clickText('button','Выйти из аккаунта'),'owner logout failed');await until(()=>exists('#login-name'),'owner auth not cleared');
  record('admin_real_owner_directory_promos_reporting_and_bot_bridge',{outsiderStatus:403,financeSourceLabels:true,users:true,
    promoCreatedAndDisabled:true,privateNote:true,replyQueued:true,uncertainFilter:true,composerRetained:true,
    widths:[1440,768,390,320],doubledText:true,keyboard:true,realTelegramDelivery:false});
}
