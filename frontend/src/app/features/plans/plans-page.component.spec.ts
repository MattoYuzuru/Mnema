import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { TestBed } from '@angular/core/testing';
import { NEVER, Subject, of, throwError } from 'rxjs';

import billingContract from '../../../../../contracts/billing/billing.json';
import { BillingApiService } from '../billing/billing-api.service';
import { BillingError, BillingErrorCode, Order } from '../billing/billing.models';
import { PaymentRedirect } from '../billing/payment-redirect.service';

import { spyObj, type SpyObj } from '../../../testing/mocks';
import { LearningGoalStore } from '../goal/learning-goal.store';
import { LearningGoal } from '../goal/goal.models';
import { PromoApiService } from '../promo/promo-api.service';
import { PromoRedemption } from '../promo/promo.models';
import { PlansApiService, parsePlans } from './plans-api.service';
import { PlansCatalog } from './plans.models';
import { ExperimentService } from '../experiment/experiment.service';
import { PlansPageComponent } from './plans-page.component';
import { plansBody } from './plans-test-data';

const NBSP = '\u00a0';

describe('PlansPageComponent', () => {
    let api: SpyObj<PlansApiService>;
    let root: HTMLElement;

    let promo: SpyObj<PromoApiService>;
    let billing: SpyObj<BillingApiService>;
    let redirect: SpyObj<PaymentRedirect>;
    let http: HttpTestingController;

    async function open(options: {
        teaser?: boolean; current?: string; source?: string; goal?: LearningGoal | null; url?: string; experiments?: Record<string, string>;
        pendingDiscount?: Record<string, unknown> | null; checkout?: 'AVAILABLE' | 'UNAVAILABLE';
    } = {}): Promise<RouterTestingHarness> {
        api.load.mockReturnValue(of(parsePlans(plansBody({ teaser: options.teaser, current: options.current, source: options.source,
            experiments: options.experiments, pendingDiscount: options.pendingDiscount, checkout: options.checkout }))));
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
        billing = spyObj<BillingApiService>({ createCheckout: vi.fn().mockName('BillingApiService.createCheckout') });
        redirect = spyObj<PaymentRedirect>({ go: vi.fn().mockName('PaymentRedirect.go') });
        TestBed.configureTestingModule({
            providers: [provideRouter([{ path: 'plans', component: PlansPageComponent }, { path: 'decks', component: PlansPageComponent },
                { path: 'terms', component: PlansPageComponent }]),
                provideHttpClient(), provideHttpClientTesting(), { provide: PlansApiService, useValue: api }, { provide: PromoApiService, useValue: promo },
                { provide: BillingApiService, useValue: billing }, { provide: PaymentRedirect, useValue: redirect }]
        });
        http = TestBed.inject(HttpTestingController);
    });

    const radios = () => [...root.querySelectorAll<HTMLInputElement>('app-plan-option input[type=radio]')];
    const cta = () => root.querySelector<HTMLButtonElement>('.cta-bar .button')!;

    it('cancels a departed page catalogue before it can adopt old experiment assignments', async () => {
        const response = new Subject<PlansCatalog>();
        api.load.mockReturnValue(response);
        const fixture = TestBed.createComponent(PlansPageComponent);
        fixture.detectChanges();
        fixture.destroy();
        response.next(parsePlans(plansBody({ experiments: { plans_year_first: 'plans_year_first' } })));
        response.complete();
        await Promise.resolve();
        expect(response.observed).toBe(false);
        expect(TestBed.inject(ExperimentService).variant('plans_year_first')).toBe('control');
        http.expectNone('/api/experiment-events');
    });

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

    it('defaults to Free for a paid account while marking the current plan independently', async () => {
        const harness = await open({ current: 'PRO' });
        expect(radios().map(radio => radio.checked)).toEqual([true, false, false, false]);
        expect(root.querySelectorAll('app-plan-option')[2].textContent).toContain('Ваш тариф');
        expect(cta().textContent).toBe('Вернуться на Free');
        radios()[2].click();
        harness.fixture.detectChanges();
        await harness.fixture.whenStable();
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

    describe('checkout', () => {
        const order = (plan: 'PLUS' | 'PRO' = 'PLUS', patch: Partial<Order> = {}): Order =>
            ({ ...(billingContract.examples.orderPending as unknown as Order), plan, ...patch });
        const periodRadio = (value: string) => [...root.querySelectorAll<HTMLInputElement>('app-segmented-choice input[type=radio]')]
            .find(radio => radio.value === value)!;
        const settle = async (harness: RouterTestingHarness) => { await harness.fixture.whenStable(); harness.detectChanges(); };

        async function choosePlus(options: Parameters<typeof open>[0] = {}): Promise<RouterTestingHarness> {
            const harness = await open({ checkout: 'AVAILABLE', ...options });
            radios()[1].click();
            harness.detectChanges();
            return harness;
        }

        it('offers «Оплатить» with the month price, and the discounted price when a discount applies to that tier', async () => {
            const harness = await choosePlus();
            expect(cta().textContent).toBe(`Оплатить Plus — 449${NBSP}₽`);
            radios()[2].click();
            harness.detectChanges();
            expect(cta().textContent).toBe(`Оплатить Pro — 990${NBSP}₽`);
        });

        it('shows the discounted amount against the list price for the tier the discount names', async () => {
            const harness = await open({ checkout: 'AVAILABLE', pendingDiscount: { percent: 20, plan: 'PRO', validUntil: '2026-10-31T20:59:59Z' } });
            radios()[1].click();
            harness.detectChanges();
            expect(cta().textContent).toBe(`Оплатить Plus — 449${NBSP}₽`);
            radios()[2].click();
            harness.detectChanges();
            expect(cta().textContent).toBe(`Оплатить Pro — 792${NBSP}₽ вместо 990${NBSP}₽`);
        });

        it('applies a discount that names no tier to either paid tier', async () => {
            const anyTier = await open({ checkout: 'AVAILABLE', pendingDiscount: { percent: 15, plan: null, validUntil: '2026-10-31T20:59:59Z' } });
            radios()[1].click();
            anyTier.detectChanges();
            expect(cta().textContent).toBe(`Оплатить Plus — 382${NBSP}₽ вместо 449${NBSP}₽`);
        });

        it('keeps the current tier and Free as before', async () => {
            const harness = await open({ checkout: 'AVAILABLE', current: 'PLUS' });
            radios()[1].click();
            harness.detectChanges();
            expect(cta().textContent).toBe('Это ваш тариф');
            radios()[0].click();
            harness.detectChanges();
            expect(cta().textContent).toBe('Вернуться на Free');
        });

        it('replaces the auto-renew checkbox with a one-off line with the access date and the terms link', async () => {
            vi.useFakeTimers({ toFake: ['Date'] });
            try {
                vi.setSystemTime(new Date('2026-10-09T09:00:00Z'));
                const harness = await choosePlus();
                expect(root.querySelector('.renew input')).toBeNull();
                const hints = [...root.querySelectorAll('.renew .hint')].map(hint => hint.textContent?.trim());
                expect(hints[0]).toBe('Разовая оплата за месяц, без автопродления. Доступ — до 9 ноября.');
                expect(hints[1]).toBe('Нажимая «Оплатить», вы принимаете условия сервиса (откроется в новой вкладке).');
                const terms = root.querySelector<HTMLAnchorElement>('.renew a')!;
                expect(terms.getAttribute('href')).toBe('/terms');
                expect(terms.getAttribute('target')).toBe('_blank');
                expect(terms.getAttribute('rel')).toBe('noopener');
                expect(terms.textContent).toContain('(откроется в новой вкладке)');
                year().click();
                harness.detectChanges();
                expect(root.querySelector('.renew')).toBeNull();
                radios()[0].click();
                harness.detectChanges();
                expect(root.querySelector('.renew')).toBeNull();
            } finally { vi.useRealTimers(); }
        });

        it('answers the auto-renew FAQ for one-off payment (the old answer stays while checkout is unavailable: «keeps the FAQ»)', async () => {
            await open({ checkout: 'AVAILABLE' });
            expect(root.querySelector('.faq')!.textContent).toContain('Сейчас оплата разовая: автопродления нет, тариф просто закончится в конце оплаченного месяца.');
            expect(root.querySelector('.faq')!.textContent).not.toContain('Когда подключим оплату');
        });

        it('goes busy on activation, ignores a second press, and leaves for the trusted bank URL', async () => {
            const response = new Subject<Order>();
            billing.createCheckout.mockReturnValue(response);
            const harness = await choosePlus();
            const button = cta();
            button.focus();
            button.click();
            button.click();
            harness.detectChanges();
            expect(billing.createCheckout).toHaveBeenCalledTimes(1);
            expect(billing.createCheckout).toHaveBeenCalledWith('PLUS', 'MONTH', expect.stringMatching(/^[0-9a-f-]{36}$/u));
            expect(button.textContent).toBe('Переходим к оплате…');
            expect(notice().textContent).toBe('Переходим к оплате…');
            expect(button.closest('.cta-bar')!.contains(notice())).toBe(true);
            expect(button.getAttribute('aria-busy')).toBe('true');
            expect(button.getAttribute('aria-disabled')).toBe('true');
            expect(document.activeElement).toBe(button);
            expect(redirect.go).not.toHaveBeenCalled();
            response.next(order());
            response.complete();
            await settle(harness);
            expect(redirect.go).toHaveBeenCalledExactlyOnceWith('https://pay.tbank-online.com/So6mQeQB');
            expect(cta().getAttribute('aria-busy')).toBe('true');
            button.click();
            expect(billing.createCheckout).toHaveBeenCalledTimes(1);
        });

        it('reuses the key for a retry of the same choice and takes a new one for a new choice', async () => {
            billing.createCheckout.mockReturnValue(throwError(() => new BillingError('PAYMENT_PROVIDER_UNAVAILABLE')));
            const harness = await choosePlus();
            const keys = () => billing.createCheckout.mock.calls.map(call => call[2]);
            cta().click();
            await settle(harness);
            cta().click();
            await settle(harness);
            expect(keys()[0]).toBe(keys()[1]);
            radios()[2].click();
            harness.detectChanges();
            cta().click();
            await settle(harness);
            expect(billing.createCheckout.mock.calls[2].slice(0, 2)).toEqual(['PRO', 'MONTH']);
            expect(keys()[2]).not.toBe(keys()[0]);
            radios()[1].click();
            harness.detectChanges();
            cta().click();
            await settle(harness);
            expect(keys()[3]).not.toBe(keys()[0]);
            expect(keys()[3]).not.toBe(keys()[2]);
        });

        it.each<[BillingErrorCode, string]>([
            ['CAPABILITY_UNAVAILABLE', 'Оплату подключаем: тариф можно будет оформить здесь же. Пока доступен промокод.'],
            ['BILLING_PLAN_BELOW_CURRENT', 'У вас уже тариф выше. Он действует до конца оплаченного периода.'],
            ['RATE_LIMITED', 'Слишком много попыток оплаты. Попробуйте через час.'],
            ['PAYMENT_PROVIDER_UNAVAILABLE', 'Банк не ответил. Попробуйте ещё раз — повторное нажатие не создаст второй платёж.'],
            ['UNKNOWN', 'Банк не ответил. Попробуйте ещё раз — повторное нажатие не создаст второй платёж.']
        ])('says %s calmly in the status region, frees the button and never leaves', async (code, text) => {
            billing.createCheckout.mockReturnValue(throwError(() => new BillingError(code)));
            const harness = await choosePlus();
            cta().click();
            await settle(harness);
            expect(notice().textContent).toBe(text);
            expect(cta().getAttribute('aria-busy')).toBeNull();
            expect(cta().textContent).toBe(`Оплатить Plus — 449${NBSP}₽`);
            const oneOffId = root.querySelector('.renew .hint')!.id;
            expect(oneOffId).not.toBe('');
            expect(cta().getAttribute('aria-describedby')).toBe(`${oneOffId} ${notice().id}`);
            expect(redirect.go).not.toHaveBeenCalled();
        });

        it('describes the button by the one-off line alone while there is no notice', async () => {
            await choosePlus();
            expect(cta().getAttribute('aria-describedby')).toBe(root.querySelector('.renew .hint')!.id);
        });

        it.each<[string, Partial<Order>, 'PLUS' | 'PRO']>([
            ['an untrusted URL', { paymentUrl: 'https://evil.example/pay' }, 'PLUS'],
            ['no URL', { paymentUrl: null }, 'PLUS'],
            ['a different plan', {}, 'PRO'],
            ['an order that is not pending', { status: 'PAID' }, 'PLUS']
        ])('never navigates on %s and says the bank did not answer', async (_name, patch, plan) => {
            billing.createCheckout.mockReturnValue(of(order(plan, patch)));
            const harness = await choosePlus();
            cta().click();
            await settle(harness);
            expect(notice().textContent).toBe('Банк не ответил. Попробуйте ещё раз — повторное нажатие не создаст второй платёж.');
            expect(cta().getAttribute('aria-busy')).toBeNull();
            expect(redirect.go).not.toHaveBeenCalled();
        });

        it('clears the previous notice while the new attempt runs', async () => {
            billing.createCheckout.mockReturnValueOnce(throwError(() => new BillingError('RATE_LIMITED')));
            const harness = await choosePlus();
            cta().click();
            await settle(harness);
            expect(notice().textContent).not.toBe('');
            billing.createCheckout.mockReturnValue(NEVER);
            cta().click();
            harness.detectChanges();
            expect(notice().textContent).toBe('Переходим к оплате…');
        });

        it('keeps the call to action on the year price and answers an activation with a notice, without a request', async () => {
            const harness = await choosePlus();
            year().click();
            harness.detectChanges();
            expect(cta().textContent).toBe(`Перейти на Plus — 5${NBSP}119${NBSP}₽ в${NBSP}год`);
            cta().click();
            harness.detectChanges();
            expect(notice().textContent).toBe('Годовая оплата появится позже. Сейчас можно оплатить месяц.');
            expect(billing.createCheckout).not.toHaveBeenCalled();
            periodRadio('MONTH').click();
            harness.detectChanges();
            expect(notice().textContent).toBe('');
            expect(cta().textContent).toBe(`Оплатить Plus — 449${NBSP}₽`);
        });

        it('clears a repeated notice and says it again on the next tick so a second press is announced', async () => {
            const harness = await choosePlus();
            year().click();
            harness.detectChanges();
            cta().click();
            harness.detectChanges();
            const text = 'Годовая оплата появится позже. Сейчас можно оплатить месяц.';
            expect(notice().textContent).toBe(text);
            vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
            try {
                cta().click();
                harness.detectChanges();
                expect(notice().textContent).toBe('');
                await vi.advanceTimersByTimeAsync(0);
                harness.detectChanges();
                expect(notice().textContent).toBe(text);
            } finally { vi.useRealTimers(); }
        });

        it('says a repeated return-to-Free notice again too, and drops a pending repeat when the choice changes', async () => {
            const harness = await open({ checkout: 'AVAILABLE', current: 'PRO' });
            radios()[0].click();
            harness.detectChanges();
            cta().click();
            harness.detectChanges();
            vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
            try {
                cta().click();
                harness.detectChanges();
                expect(notice().textContent).toBe('');
                radios()[1].click();
                harness.detectChanges();
                await vi.advanceTimersByTimeAsync(10);
                harness.detectChanges();
                expect(notice().textContent).toBe('');
            } finally { vi.useRealTimers(); }
        });

        it('counts one conversion for the paid call to action', async () => {
            billing.createCheckout.mockReturnValue(NEVER);
            const harness = await choosePlus({ experiments: { plans_year_first: 'control' } });
            http.match('/api/experiment-events');
            cta().click();
            harness.detectChanges();
            expect(http.match('/api/experiment-events').map(request => request.request.body)).toEqual([{ key: 'plans_year_first', event: 'CONVERSION' }]);
        });

        it('frees the button when the page comes back from the bank through the back-forward cache', async () => {
            billing.createCheckout.mockReturnValue(NEVER);
            const harness = await choosePlus();
            cta().click();
            harness.detectChanges();
            expect(cta().getAttribute('aria-busy')).toBe('true');
            window.dispatchEvent(Object.assign(new Event('pageshow'), { persisted: true }));
            harness.detectChanges();
            expect(cta().getAttribute('aria-busy')).toBeNull();
            expect(notice().textContent).toBe('');
        });

        it('behaves exactly as before while checkout is unavailable', async () => {
            const harness = await open({ checkout: 'UNAVAILABLE' });
            radios()[1].click();
            harness.detectChanges();
            expect(cta().textContent).toBe(`Перейти на Plus — 449${NBSP}₽ в${NBSP}месяц`);
            expect(root.querySelector('.renew input[type=checkbox]')).not.toBeNull();
            cta().click();
            harness.detectChanges();
            expect(notice().textContent).toBe('Оплату подключаем: тариф можно будет оформить здесь же. Пока доступен промокод.');
            expect(billing.createCheckout).not.toHaveBeenCalled();
            expect(redirect.go).not.toHaveBeenCalled();
        });
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
            expect(radios().map(radio => radio.checked)).toEqual([true, false, false, false]);
            expect(root.querySelectorAll('app-plan-option')[1].querySelector('.stamp.solid')?.textContent).toContain('Ваш тариф');
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
