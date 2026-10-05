import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { TestBed } from '@angular/core/testing';
import { NEVER, of, throwError } from 'rxjs';

import { spyObj, type SpyObj } from '../../../testing/mocks';
import { LearningGoalStore } from '../goal/learning-goal.store';
import { LearningGoal } from '../goal/goal.models';
import { PromoApiService } from '../promo/promo-api.service';
import { PromoRedemption } from '../promo/promo.models';
import { PlansApiService, parsePlans } from './plans-api.service';
import { PlansPageComponent } from './plans-page.component';
import { plansBody } from './plans-test-data';

const NBSP = '\u00a0';

describe('PlansPageComponent', () => {
    let api: SpyObj<PlansApiService>;
    let root: HTMLElement;

    let promo: SpyObj<PromoApiService>;
    let http: HttpTestingController;

    async function open(options: {
        teaser?: boolean; current?: string; source?: string; goal?: LearningGoal | null; url?: string; experiments?: Record<string, string>;
        pendingDiscount?: Record<string, unknown> | null;
    } = {}): Promise<RouterTestingHarness> {
        api.load.mockReturnValue(of(parsePlans(plansBody({ teaser: options.teaser, current: options.current, source: options.source,
            experiments: options.experiments, pendingDiscount: options.pendingDiscount }))));
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
        promo = spyObj<PromoApiService>({ redeem: vi.fn().mockName('PromoApiService.redeem') });
        TestBed.configureTestingModule({
            providers: [provideRouter([{ path: 'plans', component: PlansPageComponent }, { path: 'decks', component: PlansPageComponent }]),
                provideHttpClient(), provideHttpClientTesting(), { provide: PlansApiService, useValue: api }, { provide: PromoApiService, useValue: promo }]
        });
        http = TestBed.inject(HttpTestingController);
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

    it('keeps the FAQ', async () => {
        await open();
        const faq = root.querySelector('.faq')!.textContent!;
        expect(faq).toContain('Все колоды остаются');
        expect(faq).toContain('полностью неиспользованные месяцы годовой подписки');
        expect(faq).toContain('автопродление');
        expect(faq).toContain('Когда подключим оплату');
        expect(faq).toContain('голос и проверку ответов');
        expect(root.querySelector('.lede')?.textContent).toContain('голос и проверка ответов ограничены отдельно');
    });

    describe('promo code', () => {
        const redemption: PromoRedemption = { type: 'TIER_DAYS', plan: 'PLUS', validUntil: '2026-10-20T09:00:00Z', percent: null,
            message: 'Plus до 20 октября, без автопродления.' };

        it('offers a labelled field that does not autofill and capitalizes as a code', async () => {
            await open();
            const input = root.querySelector<HTMLInputElement>('.promo input')!;
            expect(root.querySelector('.promo label')?.textContent).toBe('Промокод');
            expect(root.querySelector('.promo label')?.getAttribute('for')).toBe(input.id);
            expect(input.disabled).toBe(false);
            expect(input.getAttribute('autocomplete')).toBe('off');
            expect(input.getAttribute('autocapitalize')).toBe('characters');
            expect(root.querySelector('.promo button')?.textContent).toBe('Применить');
            expect(input.value).toBe('');
        });

        it('puts a campaign code into the field from the link and never applies it', async () => {
            await open({ url: '/plans?promo=AUTUMN-26' });
            expect(root.querySelector<HTMLInputElement>('.promo input')!.value).toBe('AUTUMN-26');
            expect(promo.redeem).not.toHaveBeenCalled();
        });

        it('ignores a link code that is not a code', async () => {
            await open({ url: '/plans?promo=%3Cscript%3E' });
            expect(root.querySelector<HTMLInputElement>('.promo input')!.value).toBe('');
        });

        it('reads the plan again after a redemption and shows it as the current plan, keeping the success message', async () => {
            const harness = await open();
            expect(root.querySelector('.plan-status')?.textContent?.trim()).toBe('');
            promo.redeem.mockReturnValue(of(redemption));
            const input = root.querySelector<HTMLInputElement>('.promo input')!;
            input.value = 'plus15';
            input.dispatchEvent(new Event('input'));
            api.load.mockReturnValue(of(parsePlans(plansBody({ current: 'PLUS', source: 'PROMO' }))));
            root.querySelector<HTMLFormElement>('.promo form')!.dispatchEvent(new Event('submit'));
            await harness.fixture.whenStable();
            harness.detectChanges();

            expect(promo.redeem).toHaveBeenCalledWith('plus15', expect.stringMatching(/^[0-9a-f-]{36}$/u));
            expect(root.querySelector('.plan-status .notice.success')?.textContent).toContain('Plus до 1 ноября, без автопродления');
            expect(root.querySelector('.promo .notice.success')?.textContent).toContain('Plus до 20 октября, без автопродления.');
            expect(radios().map(radio => radio.checked)).toEqual([false, true, false, false]);
            expect(root.querySelector('app-plan-option .stamp.solid')?.textContent).toContain('Ваш тариф');
        });

        it('shows a pending discount and a paid tier without renewal', async () => {
            await open({ current: 'PRO', source: 'BILLING', pendingDiscount: { percent: 20, plan: 'PLUS', validUntil: '2026-10-31T20:59:59Z' } });
            const lines = [...root.querySelectorAll('.plan-status .notice')].map(line => line.textContent);
            expect(lines[0]).toContain('Pro до 1 ноября, без автопродления');
            expect(lines[1]).toBe(`Скидка 20${NBSP}% на Plus применится к оплате до 31 октября.`);
        });

        it('says nothing about a plan the account has by default', async () => {
            await open();
            expect(root.querySelector('.plan-status .notice')).toBeNull();
        });
    });

    describe('plans_year_first experiment', () => {
        const periodRadios = () => [...root.querySelectorAll<HTMLInputElement>('app-segmented-choice input[type=radio]')];
        const events = () => http.match('/api/experiment-events').map(request => request.request.body);

        it('opens on «Месяц» for the control and counts one exposure', async () => {
            await open({ experiments: { plans_year_first: 'control' } });
            expect(periodRadios().map(radio => radio.checked)).toEqual([true, false]);
            expect(events()).toEqual([{ key: 'plans_year_first', event: 'EXPOSURE' }]);
        });

        it('opens on «Год» for the variant, with yearly prices', async () => {
            await open({ experiments: { plans_year_first: 'plans_year_first' } });
            expect(periodRadios().map(radio => radio.checked)).toEqual([false, true]);
            expect(root.querySelector('app-plan-option')?.textContent).toContain('0');
            expect(root.querySelectorAll('app-plan-option')[1].textContent).toContain(`5${NBSP}119${NBSP}₽ в${NBSP}год`);
            expect(events()).toEqual([{ key: 'plans_year_first', event: 'EXPOSURE' }]);
        });

        it('never overrides the reader who picked a period, and sends no event when the experiment is not running', async () => {
            await open();
            expect(periodRadios().map(radio => radio.checked)).toEqual([true, false]);
            expect(events()).toEqual([]);
        });

        it('counts a conversion when the paid call to action is pressed, once', async () => {
            const harness = await open({ experiments: { plans_year_first: 'control' } });
            events();
            radios()[1].click();
            harness.detectChanges();
            cta().click();
            cta().click();
            harness.detectChanges();
            expect(events()).toEqual([{ key: 'plans_year_first', event: 'CONVERSION' }]);
        });
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
