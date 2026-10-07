/** Public events and owner editor against real Identity + Learning + PostgreSQL. No response fixtures. */
import { toolkit } from './plans.mjs';

export async function runEvents(ctx) {
  const { config, record, navigate, fill, submit, clickText, saveScreenshot, setStep } = ctx;
  const { need, page, metrics, frames, api, until, exists, tab } = toolkit(ctx);
  setStep('events_owner_access');
  const outsider = await api('GET', '/api/admin/events/access');
  need(outsider.status === 403, 'learner gained editorial access');
  await navigate('/events');
  await until(() => exists('.empty-state'), 'events empty state absent');
  await navigate('/manage/events');
  await until(() => exists('#login-name'), 'anonymous editor did not require sign-in');
  await fill('#login-name', config.eventsLogin); await fill('#password', config.password); await submit();
  await until(() => exists('.manage-events form'), 'owner editor did not open after real sign-in');
  setStep('events_draft_publish');
  await fill('[formControlName=title]', 'Изменения Мнемы');
  await fill('[formControlName=eventDate]', '2026-10-07');
  await fill('[formControlName=bodyMarkdown]', 'Проверяем **новую страницу**.\n\n[Материалы](/decks)\n\n<img src=x onerror=alert(1)>');
  need(await clickText('button', 'Предпросмотр текста'), 'editor preview absent');
  need(await page(`return document.querySelector('.preview strong')?.textContent === 'новую страницу'
    && !document.querySelector('.preview img');`), 'Markdown preview is unsafe or did not render');
  need(await clickText('button', 'Сохранить черновик'), 'save draft action absent');
  await until(() => page(`return document.querySelector('.manage-events')?.textContent.includes('Черновик сохранён.');`), 'draft save failed');
  const draftList = await page(`const response = await fetch('/api/events', {credentials:'omit'}); return response.ok ? await response.json() : null;`);
  need(draftList?.items.length === 0, 'draft leaked into public events');
  need(await page(`const input = document.querySelector('[formControlName=published]'); input.click(); return input.checked;`), 'publication choice unavailable');
  need(await clickText('button', 'Опубликовать'), 'publish event action absent');
  await until(() => page(`return document.querySelector('.manage-events')?.textContent.includes('Событие опубликовано.');`), 'event publication failed');
  for (const width of [1440, 390, 320]) {
    await metrics(width, 1000, 1, width < 500); await frames();
    need(await page(`return document.documentElement.scrollWidth <= document.documentElement.clientWidth;`), 'event editor horizontal overflow');
    await saveScreenshot(`events-editor-${width}.png`, tab);
  }
  await navigate('/events');
  await until(() => page(`return document.querySelector('.timeline h2')?.textContent === 'Изменения Мнемы';`), 'published event did not appear');
  need(await page(`return document.querySelector('.timeline time')?.getAttribute('datetime') === '2026-10-07'
    && document.querySelector('.timeline strong')?.textContent === 'новую страницу'
    && !document.querySelector('.timeline img, .timeline iframe, .timeline script');`), 'public event date or safe body failed');
  for (const width of [1440, 390, 320]) {
    await metrics(width, 1000, 1, width < 500); await frames();
    need(await page(`return document.documentElement.scrollWidth <= document.documentElement.clientWidth;`), 'public event timeline horizontal overflow');
    await saveScreenshot(`events-timeline-${width}.png`, tab);
  }
  await metrics(1280, 900, 1, false);
  await navigate('/login');
  await until(() => exists('[data-testid=logout]'), 'editor logout unavailable');
  need(await clickText('button', 'Выйти из аккаунта'), 'editor logout action failed');
  await until(() => exists('#login-name'), 'editor session not cleared');
  record('events_real_owner_draft_publish_public_timeline', { outsiderStatus: 403, draftsPrivate: true,
    safeMarkdown: true, widths: [1440, 390, 320], noHorizontalOverflow: true });
}
