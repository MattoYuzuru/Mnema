import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, Injector, OnInit, afterNextRender, afterRenderEffect, computed, inject, signal, untracked, viewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { distinctUntilChanged, firstValueFrom, map } from 'rxjs';

import { daysLabel } from '../../core/onboarding/rhythm-format';
import { AutoLoadComponent } from '../../shared/auto-load.component';
import { AdminApiService } from './admin-api.service';
import { AdminCampaign, AdminIssuance, CampaignCreate, CampaignOfferType } from './admin.models';
import { CursorList } from './cursor-list';
import { dateTime, errorText, isForbidden, unknownOutcome } from './admin-presenters';

type Mutation = {
    readonly kind: 'create';
    readonly commandId: string;
    readonly body: CampaignCreate;
} | {
    readonly kind: 'switch';
    readonly campaignId: string;
    readonly enabled: boolean;
};

const PLAN_NAME = { PLUS: 'Plus', PRO: 'Pro' } as const;

/**
 * The owner's console for the reward of a finished «Путь» (OB-402): the campaigns (offer, whether it is enabled, how many codes it issued, how many were
 * redeemed, how many in the last 24 hours), a form to create one (a discount, optionally for one plan, or a plan for some days), the kill switch with a
 * confirmation built into the page (no `confirm()`), and the codes each campaign issued, paged by the shared auto-load. A campaign is created disabled and
 * its offer never changes; enabling is refused while another one is enabled (409 `PROMO_CAMPAIGN_ACTIVE`), which the page explains. A create is a command with
 * an `Idempotency-Key`: after an unknown outcome the very same command is retried. All strings of this console section await owner approval (S4).
 */
@Component({
    selector: 'app-admin-reward-campaigns',
    host: { '(window:beforeunload)': 'protectBeforeUnload($event)' },
    imports: [ReactiveFormsModule, AutoLoadComponent],
    templateUrl: './admin-reward-campaigns.component.html',
    styleUrl: './admin-page.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class AdminRewardCampaignsComponent implements OnInit {
    private readonly api = inject(AdminApiService);
    private readonly destroy = inject(DestroyRef);
    private readonly injector = inject(Injector);
    private readonly confirmBox = viewChild<ElementRef<HTMLElement>>('confirmBox');

    protected readonly campaigns = signal<readonly AdminCampaign[]>([]);
    protected readonly loading = signal(false);
    protected readonly error = signal('');
    protected readonly notice = signal('');
    protected readonly actionError = signal('');
    protected readonly busy = signal(false);
    protected readonly uncertain = signal(false);
    protected readonly mutationKind = signal<Mutation['kind'] | null>(null);
    /** The switch the owner is being asked to confirm. */
    protected readonly confirming = signal<{ readonly campaignId: string; readonly enable: boolean } | null>(null);
    /** The campaign whose issued codes are listed, and the list. */
    protected readonly opened = signal<string | null>(null);
    protected readonly issuances = signal<CursorList<AdminIssuance> | null>(null);
    protected readonly date = dateTime;
    protected readonly form = new FormGroup({
        offerType: new FormControl<CampaignOfferType>('DISCOUNT_PERCENT', { nonNullable: true }),
        percent: new FormControl(10, { nonNullable: true }),
        plan: new FormControl('', { nonNullable: true }),
        days: new FormControl(30, { nonNullable: true })
    });
    protected readonly offerType = signal<CampaignOfferType>('DISCOUNT_PERCENT');
    private readonly pending = computed(() => this.busy() || this.uncertain());
    private mutation: Mutation | null = null;
    private epoch = 0;

    constructor() {
        // The confirmation takes the focus when it appears: a keyboard or screen-reader user lands on the question.
        afterRenderEffect(() => {
            const box = this.confirmBox()?.nativeElement;
            if (box !== undefined) untracked(() => box.focus({ preventScroll: false }));
        });
    }

    ngOnInit(): void {
        this.form.controls.offerType.valueChanges.pipe(distinctUntilChanged(), takeUntilDestroyed(this.destroy)).subscribe(type => {
            this.offerType.set(type);
            // A tier names its plan, a discount may leave it open.
            this.form.controls.plan.setValue(type === 'TIER_DAYS' ? 'PLUS' : '');
        });
        void this.reload();
    }

    /** «Скидка 10 % на любой тариф», «Plus на 30 дней». */
    protected offer(campaign: AdminCampaign): string {
        if (campaign.offerType === 'TIER_DAYS') return `${PLAN_NAME[campaign.plan ?? 'PLUS']} на ${daysLabel(campaign.days ?? 0)}`;
        return `Скидка ${campaign.percent}\u00a0% ${campaign.plan === null ? 'на любой тариф' : `на ${PLAN_NAME[campaign.plan]}`}`;
    }

    protected async reload(): Promise<void> {
        const epoch = ++this.epoch;
        this.loading.set(true);
        this.error.set('');
        try {
            const page = await firstValueFrom(this.api.campaigns().pipe(takeUntilDestroyed(this.destroy)));
            if (epoch === this.epoch) this.campaigns.set(page.campaigns);
        } catch (failure) {
            if (epoch !== this.epoch) return;
            if (isForbidden(failure)) this.forget();
            this.error.set(errorText(failure, 'Список кампаний не загрузился. Поля формы сохранены.'));
        } finally {
            if (epoch === this.epoch) this.loading.set(false);
        }
    }

    protected async create(): Promise<void> {
        if (this.pending()) return;
        this.actionError.set('');
        this.notice.set('');
        try {
            const v = this.form.getRawValue();
            const type = v.offerType;
            const plan = v.plan === 'PLUS' || v.plan === 'PRO' ? v.plan : null;
            if (type === 'DISCOUNT_PERCENT') {
                if (!Number.isInteger(v.percent) || v.percent < 1 || v.percent > 90) throw new Error('Скидка: целое число от 1 до 90.');
            } else {
                if (plan === null) throw new Error('Выберите Plus или Pro.');
                if (!Number.isInteger(v.days) || v.days < 1 || v.days > 366) throw new Error('Дни: целое число от 1 до 366.');
            }
            this.mutation = {
                kind: 'create',
                commandId: crypto.randomUUID(),
                body: { type: 'PATH_REWARD', offerType: type, percent: type === 'DISCOUNT_PERCENT' ? v.percent : null, plan, days: type === 'TIER_DAYS' ? v.days : null }
            };
            await this.submit();
        } catch (failure) {
            this.actionError.set(failure instanceof Error ? failure.message : 'Проверьте поля.');
        }
    }

    /** The owner asked to switch a campaign: nothing happens until the question is answered. */
    protected ask(campaign: AdminCampaign): void {
        if (this.pending()) return;
        this.actionError.set('');
        this.notice.set('');
        this.confirming.set({ campaignId: campaign.campaignId, enable: !campaign.enabled });
    }

    protected cancel(): void {
        const asked = this.confirming();
        this.confirming.set(null);
        if (asked !== null) this.focusSwitch(asked.campaignId);
    }

    protected async confirm(): Promise<void> {
        const asked = this.confirming();
        if (asked === null || this.pending()) return;
        this.mutation = { kind: 'switch', campaignId: asked.campaignId, enabled: asked.enable };
        await this.submit();
        this.confirming.set(null);
        this.focusSwitch(asked.campaignId);
    }

    protected async submit(): Promise<void> {
        const mutation = this.mutation;
        if (mutation === null || this.busy()) return;
        this.busy.set(true);
        this.mutationKind.set(mutation.kind);
        this.uncertain.set(false);
        this.actionError.set('');
        this.notice.set('');
        // Without events: disabling and enabling re-emit every control's value, which must not run the offer-type reset of the plan.
        this.form.disable({ emitEvent: false });
        try {
            if (mutation.kind === 'create') {
                await firstValueFrom(this.api.createCampaign(mutation.body, mutation.commandId).pipe(takeUntilDestroyed(this.destroy)));
                this.form.reset({ offerType: 'DISCOUNT_PERCENT', percent: 10, plan: '', days: 30 });
                this.form.markAsPristine();
                this.notice.set('Кампания создана выключенной. Включите её, когда будете готовы выдавать награду.');
                this.mutation = null;
                await this.reload();
            } else {
                const changed = await firstValueFrom(this.api.switchCampaign(mutation.campaignId, mutation.enabled).pipe(takeUntilDestroyed(this.destroy)));
                this.campaigns.update(list => list.map(item => item.campaignId === changed.campaignId ? changed : item));
                this.notice.set(changed.enabled ? 'Кампания включена. Награду получат аккаунты, прошедшие «Путь» с этого момента.' : 'Кампания выключена. Выданные коды действуют до своего срока.');
                this.mutation = null;
            }
        } catch (failure) {
            const refused = this.mutation;
            if (isForbidden(failure)) {
                this.forget();
                this.mutation = null;
            } else if (unknownOutcome(failure)) {
                this.uncertain.set(true);
            } else {
                this.mutation = null;
            }
            this.actionError.set(this.uncertain() ? 'Результат не подтверждён. Повторите ту же команду; поля зафиксированы.' : this.failureText(failure, refused));
            // A refused enable shows which campaign holds the switch: read the list again so that the page tells the truth.
            if (this.isActiveConflict(failure)) void this.reload();
        } finally {
            this.busy.set(false);
            if (!this.uncertain()) this.form.enable({ emitEvent: false });
        }
    }

    protected toggleIssuances(campaign: AdminCampaign): void {
        if (this.opened() === campaign.campaignId) {
            this.opened.set(null);
            this.issuances.set(null);
            return;
        }
        const list = new CursorList<AdminIssuance>({
            fetch: cursor => this.api.issuances(campaign.campaignId, cursor).pipe(map(page => ({ items: page.issuances, next: page.next }))),
            key: item => `${item.issuedAt}|${item.codeHint}`,
            destroy: this.destroy,
            firstError: 'Выдачи не загрузились.',
            moreError: 'Следующие выдачи не загрузились.',
            onForbidden: () => this.forget()
        });
        this.opened.set(campaign.campaignId);
        this.issuances.set(list);
        void list.reload();
    }

    protected protectBeforeUnload(event: BeforeUnloadEvent): void {
        if (this.busy() || this.uncertain() || this.form.dirty) event.preventDefault();
    }

    /** A 401/403 ends every private view of the console. */
    private forget(): void {
        this.campaigns.set([]);
        this.confirming.set(null);
        this.opened.set(null);
        this.issuances.set(null);
        this.form.reset({ offerType: 'DISCOUNT_PERCENT', percent: 10, plan: '', days: 30 });
    }

    private isActiveConflict(failure: unknown): boolean {
        return failure instanceof HttpErrorResponse && failure.status === 409 && (failure.error as { code?: unknown } | null)?.code === 'PROMO_CAMPAIGN_ACTIVE';
    }

    private failureText(failure: unknown, mutation: Mutation | null): string {
        if (this.isActiveConflict(failure)) {
            const holder = this.campaigns().find(item => item.enabled && (mutation?.kind !== 'switch' || item.campaignId !== mutation.campaignId));
            return `Уже включена другая кампания${holder === undefined ? '' : `: ${this.offer(holder)}`}. Одновременно может быть включена только одна: сначала выключите её, затем включите эту.`;
        }
        return errorText(failure, mutation?.kind === 'create' ? 'Кампания не создана. Проверьте поля и права.' : 'Команда отклонена. Обновите список и повторите.');
    }

    private focusSwitch(campaignId: string): void {
        afterNextRender(() => document.getElementById(`reward-campaign-switch-${campaignId}`)?.focus(), { injector: this.injector });
    }
}
