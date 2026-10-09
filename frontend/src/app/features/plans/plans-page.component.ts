import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, computed, effect, inject, signal, viewChild } from '@angular/core';
import { DOCUMENT } from '@angular/common';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { AuthService } from '../../auth.service';

import { ExperimentService } from '../experiment/experiment.service';
import { LearningGoalStore } from '../goal/learning-goal.store';
import { BillingApiService, isTrustedPaymentUrl } from '../billing/billing-api.service';
import { BillingError, BillingErrorCode, PaidPlan } from '../billing/billing.models';
import { PaymentRedirect } from '../billing/payment-redirect.service';
import { PromoRedeemComponent } from '../promo/promo-redeem.component';
import { SegmentedChoiceComponent, SegmentedOption } from '../../shared/segmented-choice.component';
import { PlanOptionComponent } from './plan-option.component';
import { PlansApiService } from './plans-api.service';
import {
    BANK_NOTICE, BELOW_CURRENT_NOTICE, COMPARE_ROWS, DOWNGRADE_NOTICE, PAYMENT_NOTICE, PLAN_LABEL, RATE_LIMITED_NOTICE, YEAR_NOTICE, autoRenewText,
    cta, discountText, entitlementText, limitContext, oneOffText, plansHeading, recommendation, yearSwitchHint
} from './plans-view';
import { PlanId, PlanPeriod, PlansCatalog } from './plans.models';

let nextPlansPage = 0;
/** The A/B experiment of the period switch: its variant of the same name opens the page on «Год» instead of «Месяц». */
export const YEAR_FIRST_EXPERIMENT = 'plans_year_first';
const BAR_HEIGHT_PROPERTY = '--mn-bulk-bar-height';
/** Room kept above the bar for a focus ring (3px outline + offset) so the ring is not cut by it. */
const FOCUS_RING_ROOM_PX = 8;

/** Said in the status region while the order is created and the page leaves for the bank. */
const CHECKOUT_NOTICE = 'Переходим к оплате…';

/** The calm sentence for a refused checkout; the same text for «the bank did not answer» and for anything unexpected. */
function checkoutNotice(code: BillingErrorCode): string {
    switch (code) {
        case 'CAPABILITY_UNAVAILABLE': return PAYMENT_NOTICE;
        case 'BILLING_PLAN_BELOW_CURRENT': return BELOW_CURRENT_NOTICE;
        case 'RATE_LIMITED': return RATE_LIMITED_NOTICE;
        default: return BANK_NOTICE;
    }
}

/**
 * `/plans`: the paywall. Tiers are native radio cards, the period a native radio switch. Nothing here charges or grants
 * anything by itself: while the server says checkout is unavailable a paid choice opens a calm notice; when it is available the
 * button creates an order (the server prices it) and leaves for the bank's hosted form, and only the return page reads the
 * result. Promo redemption is its own explicit action.
 * The page reads the entitlement and the catalogue; a query parameter (`?from=limit&used=92`) only adds a sentence of
 * context and never changes a right.
 */
@Component({
    selector: 'app-plans-page',
    imports: [RouterLink, SegmentedChoiceComponent, PlanOptionComponent, PromoRedeemComponent],
    templateUrl: './plans-page.component.html',
    styleUrl: './plans-page.component.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class PlansPageComponent {
    private readonly api = inject(PlansApiService);
    private readonly billing = inject(BillingApiService);
    private readonly redirect = inject(PaymentRedirect);
    private readonly document = inject(DOCUMENT);
    private readonly auth = inject(AuthService);
    private readonly destroyRef = inject(DestroyRef);
    private loadEpoch = 0;
    private readonly goals = inject(LearningGoalStore);
    private readonly experiments = inject(ExperimentService);
    private readonly router = inject(Router);
    private readonly route = inject(ActivatedRoute);
    private readonly query = toSignal(this.route.queryParamMap, { initialValue: this.route.snapshot.queryParamMap });

    private readonly uid = `mn-plans-${nextPlansPage++}`;
    protected readonly groupName = `${this.uid}-tier`;
    protected readonly renewId = `${this.uid}-renew`;
    protected readonly noticeId = `${this.uid}-notice`;
    protected readonly oneOffId = `${this.uid}-one-off`;
    protected readonly rows = COMPARE_ROWS;
    protected readonly planLabel = PLAN_LABEL;

    protected readonly state = signal<'loading' | 'ready' | 'error'>('loading');
    protected readonly catalog = signal<PlansCatalog | null>(null);
    protected readonly period = signal<PlanPeriod>('MONTH');
    private readonly chosen = signal<PlanId | null>(null);
    /** A checkout request is in flight (or the page is leaving for the bank): a second activation does nothing. */
    protected readonly busy = signal(false);
    /** One key per tier and period choice: pressing again after a lost answer is the same command, a new choice is a new one. */
    /** Pending «clear, then say it again» of a repeated notice. */
    private announceTimer: ReturnType<typeof setTimeout> | null = null;
    private checkoutKey: { readonly plan: PaidPlan; readonly key: string } | null = null;
    protected readonly autoRenew = signal(false);
    /** The text of the always-present status region; empty until an action is activated. */
    protected readonly notice = signal('');
    /** The renewal date follows the clock whenever the period or the tier changes, not the moment the catalogue loaded. */
    private readonly now = signal(new Date());
    private readonly bar = viewChild<ElementRef<HTMLElement>>('ctaBar');

    /** A campaign link (`?promo=CODE`) fills the promo field; it is never applied for the reader. */
    protected readonly prefill = computed(() => {
        const code = this.query().get('promo') ?? '';
        return /^[A-Za-z0-9 _-]{1,64}$/u.test(code) ? code : '';
    });
    /** «Plus до 20 октября, без автопродления» once a promo or a payment gives a tier; null on the plan the account has by default. */
    protected readonly entitlement = computed(() => {
        const current = this.catalog()?.current;
        return current === undefined ? null : entitlementText(current);
    });
    protected readonly discount = computed(() => {
        const pending = this.catalog()?.pendingDiscount ?? null;
        return pending === null ? null : discountText(pending);
    });
    private periodChosen = false;
    private firstLoad = true;
    protected readonly heading = computed(() => plansHeading(this.goals.goal()));
    protected readonly context = computed(() => limitContext(this.query().get('from'), this.query().get('used')));
    protected readonly recommended = computed(() => {
        const catalog = this.catalog();
        return catalog === null ? null : recommendation(catalog, this.goals.goal());
    });
    /** Free is the default offer; the current tariff is marked independently, and only a deliberate pick changes the selection. */
    protected readonly selected = computed<PlanId>(() => {
        const catalog = this.catalog();
        const pick = this.chosen();
        if (catalog === null) return 'FREE';
        const listed = (plan: PlanId): boolean => catalog.plans.some(entry => entry.plan === plan && entry.availability === 'AVAILABLE');
        if (pick !== null && listed(pick)) return pick;
        return 'FREE';
    });
    protected readonly selectedEntry = computed(() => this.catalog()?.plans.find(entry => entry.plan === this.selected()) ?? null);
    protected readonly action = computed(() => {
        const entry = this.selectedEntry();
        const catalog = this.catalog();
        if (entry === null || catalog === null) return null;
        const offer = catalog.checkout === 'AVAILABLE' ? { discount: catalog.pendingDiscount } : null;
        const base = cta(entry, this.period(), catalog.current.plan, offer);
        return this.busy() && base.action === 'checkout' ? { ...base, text: 'Переходим к оплате…' } : base;
    });
    protected readonly checkoutAvailable = computed(() => this.catalog()?.checkout === 'AVAILABLE');
    /** Auto-renew is offered for a paid tier the account is not on yet; the current plan has its own switch in the profile. */
    protected readonly renewable = computed(() => (this.selectedEntry()?.priceRub.month ?? 0) > 0
        && this.selected() !== this.catalog()?.current.plan);
    /** With checkout on, a paid month is a single payment: the one-off line replaces the auto-renew checkbox. */
    protected readonly oneOff = computed(() => this.checkoutAvailable() && this.renewable() && this.period() === 'MONTH' ? oneOffText(this.now()) : '');
    /** The button is described by the one-off terms line (when shown) and by the notice (when it has text). */
    protected readonly describedBy = computed(() => [this.oneOff() === '' ? null : this.oneOffId, this.notice() === '' ? null : this.noticeId]
        .filter(id => id !== null).join(' ') || null);
    protected readonly renewText = computed(() => {
        const entry = this.selectedEntry();
        return entry === null || !this.renewable() ? '' : autoRenewText(entry, this.period(), this.now());
    });
    protected readonly periodOptions = computed<readonly SegmentedOption<PlanPeriod>[]>(() => [
        { value: 'MONTH', label: 'Месяц', hint: 'Платите помесячно, отказаться можно в любой момент.' },
        { value: 'YEAR', label: 'Год', hint: yearSwitchHint(this.catalog()?.plans ?? []) }
    ]);

    constructor() {
        // Coming back from the bank with the back button may restore this page from the cache while it still says «Переходим к оплате…».
        const view = this.document.defaultView;
        const restored = (event: PageTransitionEvent): void => {
            if (!event.persisted) return;
            this.busy.set(false);
            this.notice.set('');
        };
        view?.addEventListener('pageshow', restored);
        this.destroyRef.onDestroy(() => { view?.removeEventListener('pageshow', restored); this.cancelAnnouncement(); });
        void this.load();
        void this.goals.load();
        // The sticky bar's height becomes scroll padding of the viewport (the scroller), so a focused control is never
        // hidden behind it (WCAG 2.4.11). `.plans-page` is not a scroll container, so the property lives on the root.
        effect(onCleanup => {
            const element = this.bar()?.nativeElement;
            if (element === undefined) return;
            const root = element.ownerDocument.documentElement;
            const apply = (): void => root.style.setProperty(BAR_HEIGHT_PROPERTY, `${element.offsetHeight + FOCUS_RING_ROOM_PX}px`);
            apply();
            const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(apply);
            observer?.observe(element);
            onCleanup(() => { observer?.disconnect(); root.style.removeProperty(BAR_HEIGHT_PROPERTY); });
        });
    }

    protected async load(): Promise<void> {
        const epoch = ++this.loadEpoch;
        const owner = this.auth.user()?.accountId ?? null;
        this.state.set('loading');
        try {
            const catalog = await firstValueFrom(this.api.load().pipe(takeUntilDestroyed(this.destroyRef)));
            if (!this.currentLoad(epoch, owner)) return;
            this.experiments.adopt(catalog.experiments);
            // The experiment decides only which period the page opens on, once; it never overrides a choice the reader made.
            if (this.firstLoad && !this.periodChosen && this.experiments.variant(YEAR_FIRST_EXPERIMENT) === YEAR_FIRST_EXPERIMENT) this.period.set('YEAR');
            this.catalog.set(catalog);
            this.now.set(new Date());
            this.state.set('ready');
            if (this.firstLoad) this.experiments.expose(YEAR_FIRST_EXPERIMENT);
            this.firstLoad = false;
        } catch {
            if (this.currentLoad(epoch, owner)) this.state.set('error');
        }
    }

    /** A promo code was redeemed: read the entitlement again, quietly (the page and the success message stay), so the plan block shows it. */
    protected async redeemed(): Promise<void> {
        const epoch = ++this.loadEpoch;
        const owner = this.auth.user()?.accountId ?? null;
        try {
            const catalog = await firstValueFrom(this.api.load().pipe(takeUntilDestroyed(this.destroyRef)));
            if (!this.currentLoad(epoch, owner)) return;
            this.experiments.adopt(catalog.experiments);
            this.catalog.set(catalog);
            this.chosen.set(null);
            this.autoRenew.set(false);
            this.now.set(new Date());
        } catch {
            // The success message already says what happened; the next visit reads the plan again.
        }
    }

    /** A departed page/account cannot adopt its late catalogue or erase a newer redemption refresh. */
    private currentLoad(epoch: number, owner: string | null): boolean {
        return !this.destroyRef.destroyed && epoch === this.loadEpoch && owner === (this.auth.user()?.accountId ?? null);
    }

    protected setPeriod(period: PlanPeriod | null): void {
        this.periodChosen = true;
        if (period !== null && period !== this.period()) this.changed(() => this.period.set(period));
    }

    protected choose(plan: PlanId): void {
        if (plan !== this.chosen()) this.changed(() => this.chosen.set(plan));
    }

    /** A different tier or period is a different offer: the reader's consent to renew and any notice do not carry over. */
    private changed(update: () => void): void {
        update();
        this.cancelAnnouncement();
        this.checkoutKey = null;
        this.autoRenew.set(false);
        this.notice.set('');
        this.now.set(new Date());
    }

    protected isRecommended(plan: PlanId): boolean { return this.recommended()?.plan === plan; }

    protected activate(): void {
        const action = this.action();
        if (action === null || action.disabled || this.busy()) return;
        switch (action.action) {
            case 'stay':
                void this.router.navigateByUrl('/decks');
                break;
            case 'checkout':
                this.experiments.convert(YEAR_FIRST_EXPERIMENT);
                void this.checkout(this.selected());
                break;
            case 'checkout-year':
                this.experiments.convert(YEAR_FIRST_EXPERIMENT);
                this.announce(YEAR_NOTICE);
                break;
            case 'buy':
                this.experiments.convert(YEAR_FIRST_EXPERIMENT);
                this.announce(PAYMENT_NOTICE);
                break;
            default:
                this.announce(action.action === 'downgrade' ? DOWNGRADE_NOTICE : PAYMENT_NOTICE);
        }
    }

    /**
     * Says a sentence in the status region. The same sentence again (a second press of a button that changes nothing) is cleared
     * and set on the next tick, so assistive technology announces it once more instead of seeing no change.
     */
    private announce(text: string): void {
        this.cancelAnnouncement();
        if (this.notice() !== text) {
            this.notice.set(text);
            return;
        }
        this.notice.set('');
        this.announceTimer = setTimeout(() => {
            this.announceTimer = null;
            if (!this.destroyRef.destroyed) this.notice.set(text);
        }, 0);
    }

    private cancelAnnouncement(): void {
        if (this.announceTimer !== null) clearTimeout(this.announceTimer);
        this.announceTimer = null;
    }

    /** Creates the order and leaves for the bank's form. Only a trusted `https` bank URL is ever navigated to. */
    private async checkout(plan: PlanId): Promise<void> {
        if (plan !== 'PLUS' && plan !== 'PRO') return;
        const owner = this.auth.user()?.accountId ?? null;
        const key = this.checkoutKey?.plan === plan ? this.checkoutKey.key : crypto.randomUUID();
        this.checkoutKey = { plan, key };
        this.cancelAnnouncement();
        this.busy.set(true);
        this.notice.set(CHECKOUT_NOTICE);
        try {
            const order = await firstValueFrom(this.billing.createCheckout(plan, 'MONTH', key).pipe(takeUntilDestroyed(this.destroyRef)));
            if (this.destroyRef.destroyed || owner !== (this.auth.user()?.accountId ?? null)) return;
            if (order.plan !== plan || order.status !== 'PENDING' || order.paymentUrl === null || !isTrustedPaymentUrl(order.paymentUrl)) throw new BillingError('UNKNOWN');
            this.redirect.go(order.paymentUrl);
            // The page is leaving: it stays busy so nothing can start a second payment before the bank's form opens.
        } catch (failure) {
            if (this.destroyRef.destroyed) return;
            this.notice.set(checkoutNotice(failure instanceof BillingError ? failure.code : 'UNKNOWN'));
            this.busy.set(false);
        }
    }

    protected onAutoRenew(event: Event): void { this.autoRenew.set((event.target as HTMLInputElement).checked); }
}
