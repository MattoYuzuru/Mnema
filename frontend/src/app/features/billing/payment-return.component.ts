import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, Injector, afterNextRender, computed, effect, inject, signal, untracked, viewChild } from '@angular/core';
import { DOCUMENT } from '@angular/common';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';

import { AuthService } from '../../auth.service';
import { BillingApiService, isOrderId } from './billing-api.service';
import { PaymentOutcome, amountText, paymentCopy } from './billing-view';
import { BillingError, Order } from './billing.models';

/** Polling cadence: every 2 s for the first 20 s, then every 5 s, for 90 s in all; «Проверить ещё раз» polls for a shorter 30 s. */
export const POLL_FAST_MS = 2_000;
export const POLL_SLOW_MS = 5_000;
export const POLL_FAST_WINDOW_MS = 20_000;
export const POLL_TOTAL_MS = 90_000;
export const POLL_RETRY_MS = 30_000;

const TERMINAL: readonly PaymentOutcome[] = ['paid', 'failed', 'refunded', 'review', 'not-found'];

/**
 * `/plans/payment/:orderId`, where the bank sends the reader back. It reads only `GET /api/billing/orders/{orderId}`: whatever
 * the bank appended to the address (`Success`, `ErrorCode`, `Message`, …) is never looked at, and nothing on this page confirms
 * a payment. A `PENDING` order is read again on a calm schedule until the server reports an outcome, the time is up, the page
 * is closed or the account changes.
 */
@Component({
    selector: 'app-payment-return',
    imports: [RouterLink],
    templateUrl: './payment-return.component.html',
    styleUrl: './payment-return.component.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class PaymentReturnComponent {
    private readonly api = inject(BillingApiService);
    private readonly auth = inject(AuthService);
    private readonly route = inject(ActivatedRoute);
    private readonly destroyRef = inject(DestroyRef);
    private readonly injector = inject(Injector);
    private readonly document = inject(DOCUMENT);

    protected readonly order = signal<Order | null>(null);
    private readonly missing = signal(false);
    /** The polling time ran out while the order was still pending (or never answered). */
    private readonly expired = signal(false);
    private readonly owner = signal<string | null>(this.auth.user()?.accountId ?? null);
    private readonly heading = viewChild<ElementRef<HTMLElement>>('heading');

    /** «Проверить ещё раз» was pressed: its button is gone once the check starts, so the next change of state takes the focus. */
    private focusAfterRecheck = false;
    private orderId = '';
    private epoch = 0;
    private timer: ReturnType<typeof setTimeout> | null = null;
    private request: Subscription | null = null;

    protected readonly outcome = computed<PaymentOutcome>(() => {
        if (this.missing()) return 'not-found';
        const order = this.order();
        switch (order?.status) {
            case 'PAID': return 'paid';
            case 'FAILED': return 'failed';
            case 'REFUNDED': return 'refunded';
            case 'REVIEW': return 'review';
            default: return this.expired() ? (order === null ? 'unknown' : 'slow') : 'pending';
        }
    });
    protected readonly copy = computed(() => paymentCopy(this.outcome(), this.order()));
    /** The amount is shown while the money is being awaited or has arrived. */
    protected readonly amount = computed(() => {
        const order = this.order();
        return order !== null && ['pending', 'slow', 'paid'].includes(this.outcome()) ? amountText(order.amountKopecks) : null;
    });

    constructor() {
        this.route.paramMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(params => this.begin(params.get('orderId') ?? ''));
        this.destroyRef.onDestroy(() => this.stop());
        // A change of account ends the page's business with this order: stop reading it and do not show it to the next account.
        effect(() => {
            const account = this.auth.user()?.accountId ?? null;
            untracked(() => {
                if (account === this.owner()) return;
                this.owner.set(account);
                this.stop();
                this.order.set(null);
                this.missing.set(true);
            });
        });
        // The heading takes focus after the text has been updated, and only when the change is one the reader needs to be taken to:
        // a final outcome, the first change after a recheck, or any change while focus is lost. A pending page that merely times
        // out (waiting → slow / unknown) never moves focus away from where the reader is.
        let previous: PaymentOutcome | null = null;
        effect(() => {
            const outcome = this.outcome();
            untracked(() => {
                const changed = previous !== null && previous !== outcome;
                const timedOut = previous === 'pending' && (outcome === 'slow' || outcome === 'unknown');
                previous = outcome;
                if (!changed) return;
                const terminal = TERMINAL.includes(outcome);
                if (terminal || this.focusAfterRecheck || (!timedOut && this.focusLost())) {
                    this.focusAfterRecheck = false;
                    afterNextRender(() => this.heading()?.nativeElement.focus(), { injector: this.injector });
                }
            });
        });
    }

    private focusLost(): boolean {
        const active = this.document.activeElement;
        return active === null || active === this.document.body || !active.isConnected;
    }

    /** «Проверить ещё раз»: a short read cycle that may end in an outcome or in the same message again. */
    protected recheck(): void {
        this.expired.set(false);
        this.focusAfterRecheck = true;
        this.poll(POLL_RETRY_MS);
    }

    private begin(orderId: string): void {
        this.stop();
        this.orderId = orderId;
        this.order.set(null);
        this.expired.set(false);
        this.missing.set(!isOrderId(orderId));
        if (!this.missing()) this.poll(POLL_TOTAL_MS);
    }

    private stop(): void {
        this.epoch++;
        if (this.timer !== null) clearTimeout(this.timer);
        this.timer = null;
        this.request?.unsubscribe();
        this.request = null;
    }

    /** Reads now, then on the schedule until `windowMs` has passed since this call. */
    private poll(windowMs: number): void {
        this.stop();
        const epoch = this.epoch;
        const started = Date.now();
        const again = (): void => {
            const elapsed = Date.now() - started;
            if (elapsed >= windowMs) {
                this.expired.set(true);
                return;
            }
            const step = elapsed < POLL_FAST_WINDOW_MS ? POLL_FAST_MS : POLL_SLOW_MS;
            this.timer = setTimeout(read, Math.min(step, windowMs - elapsed));
        };
        const read = (): void => {
            this.request = this.api.getOrder(this.orderId).subscribe({
                next: order => {
                    if (epoch !== this.epoch) return;
                    if (order.orderId === this.orderId) this.order.set(order);
                    // A paid order is read on until its receipt link exists (it is registered a little after the payment) or the time is up.
                    if (order.orderId !== this.orderId || order.status === 'PENDING' || (order.status === 'PAID' && order.receiptUrl === null)) again();
                },
                error: (failure: unknown) => {
                    if (epoch !== this.epoch) return;
                    if (failure instanceof BillingError && failure.code === 'NOT_FOUND') this.missing.set(true);
                    // Anything else (a lost connection, a slow bank) is waited out silently until the time is up.
                    else again();
                }
            });
        };
        read();
    }
}
