import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { TestBed } from '@angular/core/testing';
import { NEVER, of, throwError } from 'rxjs';

import { spyObj, type SpyObj } from '../../../testing/mocks';
import { LearningGoalStore } from '../goal/learning-goal.store';
import { LearningGoal } from '../goal/goal.models';
import { PlansApiService, parsePlans } from './plans-api.service';
import { PlansPageComponent } from './plans-page.component';
import { plansBody } from './plans-test-data';

const NBSP = '\u00a0';

describe('PlansPageComponent', () => {
    let api: SpyObj<PlansApiService>;
    let root: HTMLElement;

    async function open(options: { teaser?: boolean; current?: string; goal?: LearningGoal | null; url?: string } = {}): Promise<RouterTestingHarness> {
        api.load.mockReturnValue(of(parsePlans(plansBody({ teaser: options.teaser, current: options.current }))));
        const store = TestBed.inject(LearningGoalStore);
        store.state.set('ready');
        store.goal.set(options.goal ?? null);
        const harness = await RouterTestingHarness.create();
        await harness.navigateByUrl(options.url ?? '/plans', PlansPageComponent);
        await harness.fixture.whenStable();
        harness.detectChanges();
        root = harness.routeNativeElement as HTMLElement;
        return harness;
    }

    beforeEach(() => {
        api = spyObj<PlansApiService>({ load: vi.fn().mockName('PlansApiService.load') });
        TestBed.configureTestingModule({
            providers: [provideRouter([{ path: 'plans', component: PlansPageComponent }, { path: 'decks', component: PlansPageComponent }]),
                { provide: PlansApiService, useValue: api }]
        });
    });

    const radios = () => [...root.querySelectorAll<HTMLInputElement>('app-plan-option input[type=radio]')];
    const cta = () => root.querySelector<HTMLButtonElement>('.cta-bar .button')!;

    it('preselects Free for a Free account and shows a neutral heading without a goal', async () => {
        await open();
        expect(root.querySelector('h1')?.textContent).toBe('Тарифы Mnema');
        expect(radios().map(radio => radio.checked)).toEqual([true, false, false, false]);
        expect(cta().textContent).toBe('Остаться на Free');
        expect(root.querySelector('app-plan-option .stamp.solid')?.textContent).toContain('Ваш тариф');
        expect(root.querySelector('.renew')).toBeNull();
        expect(root.textContent).not.toContain('Рекомендуем для');
    });

    it('addresses the learner by goal and marks the recommended tier with text', async () => {
        await open({ goal: 'EXAMS' });
        expect(root.querySelector('h1')?.textContent).toMatch(/^Подготовка к экзаменам: /u);
        const options = [...root.querySelectorAll('app-plan-option')];
        expect(options[2].textContent).toContain('Рекомендуем для подготовки к экзаменам');
        expect(options[2].querySelector('.is-recommended')).not.toBeNull();
        expect(options.filter(option => option.textContent?.includes('Рекомендуем для'))).toHaveLength(1);
        expect(radios()[0].checked).toBe(true);
    });

    it('shows three highlights per tier', async () => {
        await open();
        for (const option of root.querySelectorAll('app-plan-option')) expect(option.querySelectorAll('.lines li')).toHaveLength(3);
        expect(root.querySelector('app-plan-option .lines li')?.textContent).toBe('≈ 2 материала с ИИ в месяц');
    });

    it('describes each radio by its price and highlights', async () => {
        await open();
        const radio = radios()[1];
        const ids = radio.getAttribute('aria-describedby')!.split(' ');
        expect(ids).toHaveLength(2);
        expect(root.querySelector(`#${ids[0]}`)?.textContent).toContain(`449${NBSP}₽`);
        expect(root.querySelector(`#${ids[1]}`)?.textContent).toContain('Голос: 300 мин в месяц');
        expect(root.querySelector(`label[for="${radio.id}"]`)?.textContent).toBe('Plus');
    });

    it('changes the call to action with the choice and the period', async () => {
        const harness = await open();
        radios()[1].click();
        harness.detectChanges();
        expect(cta().textContent).toBe(`Перейти на Plus — 449${NBSP}₽ в${NBSP}месяц`);
        const year = [...root.querySelectorAll<HTMLInputElement>('app-segmented-choice input[type=radio]')].find(radio => radio.value === 'YEAR')!;
        year.click();
        harness.detectChanges();
        expect(cta().textContent).toBe(`Перейти на Plus — 5${NBSP}119${NBSP}₽ в${NBSP}год`);
        const price = root.querySelectorAll('app-plan-option')[1].querySelector('.price')!;
        expect(price.textContent).toContain(`≈ 427${NBSP}₽ в${NBSP}месяц`);
        expect(price.textContent).toContain(`Экономия 269${NBSP}₽ в${NBSP}год`);
        expect(root.querySelector('app-segmented-choice .hint')?.textContent).toContain(`5–10${NBSP}%`);
    });

    it('offers an auto-renew checkbox that is never ticked, with the amount and the date', async () => {
        const harness = await open();
        vi.useFakeTimers({ toFake: ['Date'] });
        try {
            radios()[1].click();
            harness.detectChanges();
            const box = root.querySelector<HTMLInputElement>('.renew input[type=checkbox]')!;
            expect(box.checked).toBe(false);
            expect(root.querySelector('.renew .check-row')?.textContent).toMatch(/^\s*Продлевать автоматически: 449\u00a0₽ каждые 30\u00a0дней, следующее списание \d{1,2} \S+\s*$/u);
            box.click();
            harness.detectChanges();
            expect(box.checked).toBe(true);
            radios()[0].click();
            harness.detectChanges();
            expect(root.querySelector('.renew')).toBeNull();
        } finally { vi.useRealTimers(); }
    });

    const year = () => [...root.querySelectorAll<HTMLInputElement>('app-segmented-choice input[type=radio]')].find(radio => radio.value === 'YEAR')!;
    const notice = () => root.querySelector<HTMLElement>('p.notice[role=status][id$="-notice"]')!;

    it('resets a ticked auto-renew when the tier or the period changes, and computes the date at that moment', async () => {
        const harness = await open();
        vi.useFakeTimers({ toFake: ['Date'] });
        try {
            vi.setSystemTime(new Date('2026-10-05T09:00:00Z'));
            radios()[1].click();
            harness.detectChanges();
            const box = () => root.querySelector<HTMLInputElement>('.renew input[type=checkbox]')!;
            box().click();
            harness.detectChanges();
            expect(box().checked).toBe(true);
            expect(root.querySelector('.renew .check-row')?.textContent).toContain('следующее списание 4 ноября');

            vi.setSystemTime(new Date('2026-10-20T09:00:00Z'));
            radios()[2].click();
            harness.detectChanges();
            expect(box().checked).toBe(false);
            expect(root.querySelector('.renew .check-row')?.textContent).toContain('следующее списание 19 ноября');

            box().click();
            harness.detectChanges();
            year().click();
            harness.detectChanges();
            expect(box().checked).toBe(false);
            expect(root.querySelector('.renew .check-row')?.textContent).toContain('раз в\u00a0год, следующее списание 20 октября 2027');
        } finally { vi.useRealTimers(); }
    });

    it('does not offer auto-renew for the plan the account is already on', async () => {
        const harness = await open({ current: 'PRO' });
        expect(root.querySelector('.renew')).toBeNull();
        radios()[1].click();
        harness.detectChanges();
        expect(root.querySelector('.renew')).not.toBeNull();
        radios()[2].click();
        harness.detectChanges();
        expect(root.querySelector('.renew')).toBeNull();
    });

    it('keeps the status region in the page from the start, says payments are being connected and never navigates away', async () => {
        const harness = await open();
        const router = TestBed.inject(Router);
        expect(notice().textContent).toBe('');
        radios()[2].click();
        harness.detectChanges();
        cta().click();
        harness.detectChanges();
        expect(notice().textContent).toBe('Оплату подключаем: тариф можно будет оформить здесь же. Пока доступен промокод.');
        expect(cta().getAttribute('aria-describedby')).toBe(notice().id);
        expect(router.url).toBe('/plans');
        radios()[1].click();
        harness.detectChanges();
        expect(notice().textContent).toBe('');
        expect(cta().getAttribute('aria-describedby')).toBeNull();
    });

    it('answers a return to Free with its own text: the plan stays until the paid period ends', async () => {
        const harness = await open({ current: 'PRO' });
        radios()[0].click();
        harness.detectChanges();
        expect(cta().textContent).toBe('Вернуться на Free');
        cta().click();
        harness.detectChanges();
        expect(notice().textContent).toBe('Тариф вернётся на Free после окончания оплаченного периода.');
        expect(notice().textContent).not.toContain('Оплату');
    });

    it('publishes the height of the sticky bar as scroll padding while it exists and removes it afterwards (WCAG 2.4.11)', async () => {
        const harness = await open();
        expect(document.documentElement.style.getPropertyValue('--mn-bulk-bar-height')).toMatch(/^\d+px$/);
        harness.fixture.destroy();
        expect(document.documentElement.style.getPropertyValue('--mn-bulk-bar-height')).toBe('');
    });

    it('leaves for the deck list when the reader stays on Free', async () => {
        const harness = await open();
        cta().click();
        await harness.fixture.whenStable();
        expect(TestBed.inject(Router).url).toBe('/decks');
    });

    it('preselects the account\'s own paid plan and disables its button', async () => {
        await open({ current: 'PRO' });
        expect(radios().map(radio => radio.checked)).toEqual([false, false, true, false]);
        expect(cta().textContent).toBe('Это ваш тариф');
        expect(cta().getAttribute('aria-disabled')).toBe('true');
    });

    it('shows Max only as a teaser that cannot be chosen', async () => {
        await open({ teaser: true });
        const max = root.querySelectorAll('app-plan-option')[3];
        expect(max.querySelector('.stamp')?.textContent).toBe('В работе');
        expect(max.querySelector<HTMLInputElement>('input')!.disabled).toBe(true);
        expect(max.querySelectorAll('.lines li')).toHaveLength(3);
    });

    it('omits Max when the server does not list it', async () => {
        await open({ teaser: false });
        expect(radios()).toHaveLength(3);
        expect(root.textContent).not.toContain('Max');
    });

    it('compares in an accessible, scrollable table', async () => {
        await open();
        const region = root.querySelector('.table-scroll')!;
        expect(region.getAttribute('role')).toBe('region');
        expect(region.getAttribute('tabindex')).toBe('0');
        expect(region.getAttribute('aria-label')).toBe('Сравнение тарифов');
        const table = region.querySelector('table')!;
        expect(table.querySelector('caption')?.textContent).toBe('Что входит в каждый тариф');
        expect([...table.querySelectorAll('thead th')].map(cell => cell.textContent?.trim())).toEqual(['Free', 'Plus', 'Pro', 'Max (в работе)']);
        expect([...table.querySelectorAll('tbody th')].map(cell => cell.getAttribute('scope'))).not.toContain(null);
        expect(table.querySelectorAll('tbody tr')).toHaveLength(9);
    });

    it('keeps a disabled promo slot and the FAQ', async () => {
        await open();
        expect(root.querySelector<HTMLInputElement>('.promo input')!.disabled).toBe(true);
        expect(root.querySelector<HTMLButtonElement>('.promo button')!.disabled).toBe(true);
        expect(root.querySelector('.promo label')?.textContent).toBe('Промокод');
        expect(root.querySelector('.promo .hint')?.textContent).toContain('Скоро');
        const faq = root.querySelector('.faq')!.textContent!;
        expect(faq).toContain('Все колоды остаются');
        expect(faq).toContain('полностью неиспользованные месяцы годовой подписки');
        expect(faq).toContain('автопродление');
        expect(faq).toContain('Когда подключим оплату');
        expect(faq).toContain('голос и проверку ответов');
        expect(root.querySelector('.lede')?.textContent).toContain('голос и проверка ответов ограничены отдельно');
    });

    it('shows where the reader came from as display only', async () => {
        await open({ url: '/plans?from=limit&used=92&plan=PRO' });
        expect(root.querySelector('header .notice')?.textContent).toBe(`Использовано 92${NBSP}% лимита.`);
        // the query names a plan, yet nothing is chosen or granted by it
        expect(radios()[0].checked).toBe(true);
        expect(api.load).toHaveBeenCalledTimes(1);
    });

    it('explains a failed load and retries', async () => {
        api.load.mockReturnValueOnce(throwError(() => new Error('down')));
        const store = TestBed.inject(LearningGoalStore);
        store.state.set('ready');
        const harness = await RouterTestingHarness.create();
        await harness.navigateByUrl('/plans', PlansPageComponent);
        await harness.fixture.whenStable();
        harness.detectChanges();
        root = harness.routeNativeElement as HTMLElement;
        expect(root.querySelector('[role=alert]')?.textContent).toContain('Ваш тариф не изменился');
        api.load.mockReturnValue(of(parsePlans(plansBody())));
        root.querySelector<HTMLButtonElement>('[role=alert] button')!.click();
        await harness.fixture.whenStable();
        harness.detectChanges();
        expect(radios()).toHaveLength(4);
    });

    it('says it is loading while the catalogue is pending', async () => {
        api.load.mockReturnValue(NEVER);
        TestBed.inject(LearningGoalStore).state.set('ready');
        const harness = await RouterTestingHarness.create();
        await harness.navigateByUrl('/plans', PlansPageComponent);
        harness.detectChanges();
        expect((harness.routeNativeElement as HTMLElement).querySelector('[role=status]')?.textContent).toContain('Загружаем тарифы');
    });
});
