import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { Observable, Subject, of, throwError } from 'rxjs';

import billingContract from '../../../../../contracts/billing/billing.json';
import { AuthService } from '../../auth.service';
import { spyObj, type SpyObj } from '../../../testing/mocks';
import { BillingApiService } from './billing-api.service';
import { BillingError, Order } from './billing.models';
import { PaymentReturnComponent } from './payment-return.component';

const NBSP = ' ';
const examples = billingContract.examples as unknown as Record<string, Order>;
const ORDER_ID = examples['orderPending'].orderId;
const pending = examples['orderPending'];
const paid = examples['orderPaid'];
const withStatus = (status: Order['status']): Order => ({ ...pending, status, paymentUrl: null });

describe('PaymentReturnComponent', () => {
    let billing: SpyObj<BillingApiService>;
    let account: ReturnType<typeof signal<{ accountId: string } | null>>;
    let harness: RouterTestingHarness;
    let root: HTMLElement;

    beforeEach(() => {
        vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'] });
        vi.setSystemTime(new Date('2026-10-09T09:00:00Z'));
        billing = spyObj<BillingApiService>({ getOrder: vi.fn().mockName('BillingApiService.getOrder') });
        account = signal<{ accountId: string } | null>({ accountId: 'a-1' });
        TestBed.configureTestingModule({
            providers: [provideRouter([{ path: 'plans/payment/:orderId', component: PaymentReturnComponent },
                { path: 'plans', component: PaymentReturnComponent }, { path: 'decks', component: PaymentReturnComponent }]),
            { provide: BillingApiService, useValue: billing }, { provide: AuthService, useValue: { user: account.asReadonly() } }]
        });
    });
    afterEach(() => { vi.useRealTimers(); });

    async function open(url = `/plans/payment/${ORDER_ID}`): Promise<void> {
        harness = await RouterTestingHarness.create();
        await harness.navigateByUrl(url, PaymentReturnComponent);
        harness.detectChanges();
        root = harness.routeNativeElement as HTMLElement;
    }

    /** Moves the clock and lets the zoneless view catch up. */
    async function elapse(ms: number): Promise<void> {
        await vi.advanceTimersByTimeAsync(ms);
        harness.detectChanges();
    }

    const heading = () => root.querySelector('h1')!.textContent;
    const status = () => root.querySelector('[role=status]')!.textContent;
    const links = () => [...root.querySelectorAll<HTMLAnchorElement>('.actions a')].map(link => [link.textContent?.trim(), link.getAttribute('href')]);
    const recheck = () => [...root.querySelectorAll<HTMLButtonElement>('.actions button')].find(button => button.textContent === 'Проверить ещё раз');
    const reads = () => billing.getOrder.mock.calls.length;

    describe('pending', () => {
        beforeEach(() => { billing.getOrder.mockReturnValue(of(pending)); });

        it('reads the order at once and says it waits for the bank, with the amount, inside a status region', async () => {
            await open();
            expect(billing.getOrder).toHaveBeenCalledExactlyOnceWith(ORDER_ID);
            expect(heading()).toBe('Ждём подтверждения банка');
            expect(status()).toContain('Обычно это занимает несколько секунд. Страницу можно не обновлять.');
            expect(status()).toContain(`Сумма: 449${NBSP}₽`);
            expect(recheck()).toBeUndefined();
            expect(root.querySelector('h1')!.getAttribute('tabindex')).toBe('-1');
        });

        it('ignores every query parameter the bank appends', async () => {
            await open(`/plans/payment/${ORDER_ID}?Success=true&ErrorCode=0&Message=OK&PaymentId=1&Status=CONFIRMED&OrderId=other`);
            expect(billing.getOrder).toHaveBeenCalledExactlyOnceWith(ORDER_ID);
            expect(heading()).toBe('Ждём подтверждения банка');
        });

        it('reads every 2 s for 20 s, then every 5 s, for 90 s in all, and then says the bank has not confirmed', async () => {
            await open();
            await elapse(1_999);
            expect(reads()).toBe(1);
            await elapse(1);
            expect(reads()).toBe(2);
            await elapse(18_000);
            expect(reads()).toBe(11);
            await elapse(4_999);
            expect(reads()).toBe(11);
            await elapse(1);
            expect(reads()).toBe(12);
            await elapse(65_000);
            expect(reads()).toBe(25);
            expect(heading()).toBe('Банк ещё не подтвердил оплату');
            expect(status()).toContain('Доступ появится сам, как только банк пришлёт подтверждение. Можно закрыть страницу.');
            expect(recheck()).toBeDefined();
            await elapse(60_000);
            expect(reads()).toBe(25);
            expect(vi.getTimerCount()).toBe(0);
        });

        it('restarts a short read cycle on «Проверить ещё раз» that ends with the same message when nothing changes', async () => {
            await open();
            await elapse(90_000);
            expect(heading()).toBe('Банк ещё не подтвердил оплату');
            const before = reads();
            recheck()!.click();
            harness.detectChanges();
            expect(reads()).toBe(before + 1);
            expect(heading()).toBe('Ждём подтверждения банка');
            await elapse(30_000);
            expect(heading()).toBe('Банк ещё не подтвердил оплату');
            expect(reads()).toBe(before + 1 + 11 - 1 + 2);
            await elapse(60_000);
            expect(reads()).toBe(before + 12 + 1);
            expect(vi.getTimerCount()).toBe(0);
        });

        it('shows the paid state when the retry finds the payment', async () => {
            await open();
            await elapse(90_000);
            billing.getOrder.mockReturnValue(of(paid));
            recheck()!.click();
            harness.detectChanges();
            expect(heading()).toBe('Оплата прошла');
            expect(vi.getTimerCount()).toBe(0);
        });

        it('stops reading when the page is closed: its pending read never fires', async () => {
            await open();
            expect(vi.getTimerCount()).toBeGreaterThan(0);
            harness.fixture.destroy();
            await elapse(60_000);
            expect(reads()).toBe(1);
        });

        it('cancels a read that is still in flight when the page is closed', async () => {
            const response = new Subject<Order>();
            billing.getOrder.mockReturnValue(response);
            await open();
            expect(response.observed).toBe(true);
            harness.fixture.destroy();
            expect(response.observed).toBe(false);
        });

        it('stops reading when the account changes and does not show the order to the next account', async () => {
            await open();
            account.set({ accountId: 'a-2' });
            harness.detectChanges();
            await elapse(10_000);
            expect(reads()).toBe(1);
            expect(heading()).toBe('Заказ не найден');
            expect(vi.getTimerCount()).toBe(0);
        });
    });

    it('moves from pending to paid, names the plan and the end of the period, offers the decks, and moves focus to the heading', async () => {
        billing.getOrder.mockReturnValueOnce(of(pending)).mockReturnValue(of(paid));
        await open();
        expect(document.activeElement).not.toBe(root.querySelector('h1'));
        await elapse(2_000);
        expect(heading()).toBe('Оплата прошла');
        expect(status()).toContain('Plus до 9 ноября.');
        expect(status()).toContain(`Сумма: 449${NBSP}₽`);
        expect(links()).toEqual([['К моим колодам', '/decks'], ['Чек', paid.receiptUrl], ['Тарифы', '/plans']]);
        expect(root.querySelector('.actions a[href^="https://lknpd"]')?.getAttribute('rel')).toBe('noopener noreferrer');
        expect(root.querySelector('.actions .button.primary')?.textContent).toBe('К моим колодам');
        expect(document.activeElement).toBe(root.querySelector('h1'));
        expect(reads()).toBe(2);
        await elapse(60_000);
        expect(reads()).toBe(2);
        expect(vi.getTimerCount()).toBe(0);
    });

    it('reads a paid order on until its receipt exists, then offers it and stops', async () => {
        const unreceipted = { ...paid, receiptUrl: null };
        billing.getOrder.mockReturnValueOnce(of(unreceipted)).mockReturnValueOnce(of(unreceipted)).mockReturnValue(of(paid));
        await open();
        expect(heading()).toBe('Оплата прошла');
        expect(links()).toEqual([['К моим колодам', '/decks'], ['Тарифы', '/plans']]);
        await elapse(2_000);
        expect(links()).toEqual([['К моим колодам', '/decks'], ['Тарифы', '/plans']]);
        await elapse(2_000);
        expect(links()).toEqual([['К моим колодам', '/decks'], ['Чек', paid.receiptUrl], ['Тарифы', '/plans']]);
        expect(reads()).toBe(3);
        await elapse(60_000);
        expect(reads()).toBe(3);
        expect(vi.getTimerCount()).toBe(0);
    });

    it('gives up waiting for a receipt that never comes without changing the paid page', async () => {
        billing.getOrder.mockReturnValue(of({ ...paid, receiptUrl: null }));
        await open();
        await elapse(90_000);
        expect(heading()).toBe('Оплата прошла');
        expect(links()).toEqual([['К моим колодам', '/decks'], ['Тарифы', '/plans']]);
        expect(vi.getTimerCount()).toBe(0);
    });

    it('names Pro and its end of period on the Moscow calendar', async () => {
        billing.getOrder.mockReturnValue(of({ ...paid, plan: 'PRO', periodEnd: '2026-11-30T21:30:00Z' }));
        await open();
        expect(status()).toContain('Pro до 1 декабря.');
    });

    it.each<[Order['status'], string, string, [string, string][]]>([
        ['FAILED', 'Оплата не прошла', 'Деньги не списаны или вернутся банком автоматически. Можно попробовать ещё раз.', [['Вернуться к тарифам', '/plans']]],
        ['REFUNDED', 'Платёж возвращён', 'Банк вернул деньги за этот заказ.', [['Тарифы', '/plans']]],
        ['REVIEW', 'Проверяем платёж', 'Сумма платежа не совпала с заказом, мы проверим его вручную. Доступ не потерян: если оплата прошла, мы откроем тариф или вернём деньги.', [['Тарифы', '/plans']]]
    ])('says %s once and stops reading', async (code, title, text, expected) => {
        billing.getOrder.mockReturnValue(of(withStatus(code)));
        await open();
        expect(heading()).toBe(title);
        expect(status()).toContain(text);
        expect(links()).toEqual(expected);
        expect(recheck()).toBeUndefined();
        await elapse(60_000);
        expect(reads()).toBe(1);
        expect(vi.getTimerCount()).toBe(0);
    });

    describe('focus', () => {
        it('leaves focus where it was on identical polls and when pending only times out', async () => {
            billing.getOrder.mockReturnValue(of(pending));
            await open();
            const link = root.querySelector<HTMLAnchorElement>('.actions a')!;
            link.focus();
            await elapse(30_000);
            expect(document.activeElement).toBe(link);
            await elapse(60_000);
            expect(heading()).toBe('Банк ещё не подтвердил оплату');
            expect(document.activeElement).toBe(link);
        });

        it('leaves focus alone when the network keeps failing until the unknown state, even if focus was lost', async () => {
            billing.getOrder.mockReturnValue(throwError(() => new BillingError('UNKNOWN')));
            await open();
            (document.activeElement as HTMLElement | null)?.blur();
            await elapse(90_000);
            expect(heading()).toBe('Не удалось узнать статус оплаты');
            expect(document.activeElement).not.toBe(root.querySelector('h1'));
        });

        it('takes focus to the heading after a final outcome even from another control, after the text has changed', async () => {
            billing.getOrder.mockReturnValueOnce(of(pending)).mockReturnValue(of(paid));
            await open();
            root.querySelector<HTMLAnchorElement>('.actions a')!.focus();
            await elapse(2_000);
            expect(document.activeElement).toBe(root.querySelector('h1'));
            expect(heading()).toBe('Оплата прошла');
        });

        it('takes focus to the heading when «Проверить ещё раз» removes its own button, and not again on the next timeout', async () => {
            billing.getOrder.mockReturnValue(of(pending));
            await open();
            await elapse(90_000);
            recheck()!.focus();
            recheck()!.click();
            harness.detectChanges();
            expect(document.activeElement).toBe(root.querySelector('h1'));
            root.querySelector<HTMLAnchorElement>('.actions a')!.focus();
            const link = document.activeElement;
            await elapse(30_000);
            expect(heading()).toBe('Банк ещё не подтвердил оплату');
            expect(document.activeElement).toBe(link);
        });
    });

    describe('order that cannot be read', () => {
        it('says the order is not found when the server does, and stops', async () => {
            billing.getOrder.mockReturnValue(throwError(() => new BillingError('NOT_FOUND')));
            await open();
            expect(heading()).toBe('Заказ не найден');
            expect(links()).toEqual([['Тарифы', '/plans']]);
            await elapse(60_000);
            expect(reads()).toBe(1);
        });

        it('says the order is not found for an id that is not a UUID, without reading', async () => {
            await open('/plans/payment/not-an-order');
            expect(heading()).toBe('Заказ не найден');
            expect(billing.getOrder).not.toHaveBeenCalled();
            expect(vi.getTimerCount()).toBe(0);
        });

        it('keeps reading silently through network errors, then says the status is unknown with a retry', async () => {
            billing.getOrder.mockReturnValue(throwError(() => new BillingError('UNKNOWN')));
            await open();
            await elapse(30_000);
            expect(heading()).toBe('Ждём подтверждения банка');
            expect(root.querySelector('[role=alert]')).toBeNull();
            await elapse(60_000);
            expect(reads()).toBe(25);
            expect(heading()).toBe('Не удалось узнать статус оплаты');
            expect(recheck()).toBeDefined();
            expect(links()).toEqual([['Тарифы', '/plans']]);
        });

        it('recovers when a later read succeeds', async () => {
            const failing: Observable<Order> = throwError(() => new BillingError('UNKNOWN'));
            billing.getOrder.mockReturnValueOnce(failing).mockReturnValueOnce(failing).mockReturnValue(of(paid));
            await open();
            await elapse(4_000);
            expect(heading()).toBe('Оплата прошла');
            expect(reads()).toBe(3);
        });

        it('ignores an answer for a different order', async () => {
            billing.getOrder.mockReturnValue(of({ ...paid, orderId: '0199c7a2-3b4e-7c1d-9a2b-5e6f7a8b9c0e' }));
            await open();
            expect(heading()).toBe('Ждём подтверждения банка');
            await elapse(2_000);
            expect(reads()).toBe(2);
        });
    });
});
