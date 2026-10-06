import { ChangeDetectionStrategy, Component, ElementRef, computed, effect, inject, signal, viewChild } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';

import { LearningGoalStore } from '../goal/learning-goal.store';
import { SegmentedChoiceComponent, SegmentedOption } from '../../shared/segmented-choice.component';
import { PlanOptionComponent } from './plan-option.component';
import { PlansApiService } from './plans-api.service';
import {
    COMPARE_ROWS, DOWNGRADE_NOTICE, PAYMENT_NOTICE, PLAN_LABEL, autoRenewText, cta, limitContext, plansHeading, recommendation, yearSwitchHint
} from './plans-view';
import { PlanId, PlanPeriod, PlansCatalog } from './plans.models';

let nextPlansPage = 0;
const BAR_HEIGHT_PROPERTY = '--mn-bulk-bar-height';
/** Room kept above the bar for a focus ring (3px outline + offset) so the ring is not cut by it. */
const FOCUS_RING_ROOM_PX = 8;

/**
 * `/plans`: the paywall. Tiers are native radio cards, the period a native radio switch. Nothing here charges or grants
 * anything: payments are not connected, so a paid choice opens a calm notice and the promo code slot stays disabled until it
 * is. The page reads the entitlement and the catalogue; a query parameter (`?from=limit&used=92`) only adds a sentence of
 * context and never changes a right.
 */
@Component({
    selector: 'app-plans-page',
    imports: [RouterLink, SegmentedChoiceComponent, PlanOptionComponent],
    templateUrl: './plans-page.component.html',
    styleUrl: './plans-page.component.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class PlansPageComponent {
    private readonly api = inject(PlansApiService);
    private readonly goals = inject(LearningGoalStore);
    private readonly router = inject(Router);
    private readonly route = inject(ActivatedRoute);
    private readonly query = toSignal(this.route.queryParamMap, { initialValue: this.route.snapshot.queryParamMap });

    private readonly uid = `mn-plans-${nextPlansPage++}`;
    protected readonly groupName = `${this.uid}-tier`;
    protected readonly renewId = `${this.uid}-renew`;
    protected readonly promoId = `${this.uid}-promo`;
    protected readonly noticeId = `${this.uid}-notice`;
    protected readonly rows = COMPARE_ROWS;
    protected readonly planLabel = PLAN_LABEL;

    protected readonly state = signal<'loading' | 'ready' | 'error'>('loading');
    protected readonly catalog = signal<PlansCatalog | null>(null);
    protected readonly period = signal<PlanPeriod>('MONTH');
    private readonly chosen = signal<PlanId | null>(null);
    protected readonly autoRenew = signal(false);
    /** The text of the always-present status region; empty until an action is activated. */
    protected readonly notice = signal('');
    /** The renewal date follows the clock whenever the period or the tier changes, not the moment the catalogue loaded. */
    private readonly now = signal(new Date());
    private readonly bar = viewChild<ElementRef<HTMLElement>>('ctaBar');

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
        return entry === null || catalog === null ? null : cta(entry, this.period(), catalog.current.plan);
    });
    /** Auto-renew is offered for a paid tier the account is not on yet; the current plan has its own switch in the profile. */
    protected readonly renewable = computed(() => (this.selectedEntry()?.priceRub.month ?? 0) > 0
        && this.selected() !== this.catalog()?.current.plan);
    protected readonly renewText = computed(() => {
        const entry = this.selectedEntry();
        return entry === null || !this.renewable() ? '' : autoRenewText(entry, this.period(), this.now());
    });
    protected readonly periodOptions = computed<readonly SegmentedOption<PlanPeriod>[]>(() => [
        { value: 'MONTH', label: 'Месяц', hint: 'Платите помесячно, отказаться можно в любой момент.' },
        { value: 'YEAR', label: 'Год', hint: yearSwitchHint(this.catalog()?.plans ?? []) }
    ]);

    constructor() {
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
        this.state.set('loading');
        try {
            this.catalog.set(await firstValueFrom(this.api.load()));
            this.now.set(new Date());
            this.state.set('ready');
        } catch {
            this.state.set('error');
        }
    }

    protected setPeriod(period: PlanPeriod | null): void {
        if (period !== null && period !== this.period()) this.changed(() => this.period.set(period));
    }

    protected choose(plan: PlanId): void {
        if (plan !== this.chosen()) this.changed(() => this.chosen.set(plan));
    }

    /** A different tier or period is a different offer: the reader's consent to renew and any notice do not carry over. */
    private changed(update: () => void): void {
        update();
        this.autoRenew.set(false);
        this.notice.set('');
        this.now.set(new Date());
    }

    protected isRecommended(plan: PlanId): boolean { return this.recommended()?.plan === plan; }

    protected activate(): void {
        const action = this.action();
        if (action === null || action.disabled) return;
        if (action.action === 'stay') void this.router.navigateByUrl('/decks');
        else this.notice.set(action.action === 'downgrade' ? DOWNGRADE_NOTICE : PAYMENT_NOTICE);
    }

    protected onAutoRenew(event: Event): void { this.autoRenew.set((event.target as HTMLInputElement).checked); }
}
