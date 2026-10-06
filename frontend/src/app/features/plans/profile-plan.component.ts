import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';

import { PromoRedeemComponent } from '../promo/promo-redeem.component';
import { calendarDay } from '../usage/usage-view';
import { PlansApiService } from './plans-api.service';
import { PLAN_LABEL, discountText } from './plans-view';
import { PendingDiscount, PlansCurrent } from './plans.models';

/**
 * The profile's «Тариф» block: the current plan, the way to change it, and two rows that exist only as places for what
 * payments (#79) will bring: the auto-renew switch and the payment method. They are disabled and say so; no fake data.
 * Fail-soft like the budget block: an error leaves a short message and a retry, never an exception.
 */
@Component({
    selector: 'app-profile-plan',
    imports: [RouterLink, PromoRedeemComponent],
    template: `
      @if (loading()) {
        <p class="hint" role="status">Узнаём ваш тариф…</p>
      } @else if (current(); as plan) {
        <p class="plan-line">Тариф {{ label(plan) }}@if (plan.source !== 'CONFIG') { <span class="until">, действует до {{ until(plan) }}{{ plan.autoRenew ? '' : ', без автопродления' }}</span> }</p>
        @if (discount(); as pending) { <p class="notice">{{ discountLine(pending) }}.</p> }
        <p><a class="button" routerLink="/plans">Изменить тариф</a></p>
        <div class="promo"><app-promo-redeem (redeemed)="load(true)" /></div>
        <div class="placeholders">
          <label class="settings-row is-switch">
            <input type="checkbox" role="switch" disabled [checked]="false" aria-describedby="profile-plan-later" />
            <span>Автопродление</span>
          </label>
          <div class="settings-row">
            <span>Способ оплаты</span>
            <button type="button" class="button small" disabled aria-describedby="profile-plan-later">Добавить</button>
          </div>
          <p class="hint" id="profile-plan-later">Появится вместе с оплатой.</p>
        </div>
      } @else {
        <p class="hint" role="status">Не удалось узнать тариф. Остальной профиль работает как обычно.</p>
        <button type="button" class="button" (click)="load()">Повторить</button>
      }
    `,
    styles: [`
      :host { display: block; min-inline-size: 0; }
      .plan-line { margin: 0 0 1rem; color: var(--mn-ink); font-weight: 600; }
      .until { color: var(--mn-muted); font-weight: 400; }
      .promo { max-inline-size: 28rem; margin-block-start: 1.25rem; }
      .placeholders { display: grid; gap: .5rem; margin-block-start: 1.25rem; border-block-start: 1px solid var(--mn-rule); padding-block-start: 1rem; }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class ProfilePlanComponent implements OnInit {
    private readonly api = inject(PlansApiService);
    protected readonly current = signal<PlansCurrent | null>(null);
    protected readonly discount = signal<PendingDiscount | null>(null);
    protected readonly loading = signal(true);

    ngOnInit(): void { void this.load(); }

    protected label(current: PlansCurrent): string { return PLAN_LABEL[current.plan]; }
    protected until(current: PlansCurrent): string { return calendarDay(current.validUntil); }
    protected discountLine(pending: PendingDiscount): string { return discountText(pending); }

    /** @param quiet read again without the «Узнаём…» state, so the promo field and its success message stay where they are */
    async load(quiet = false): Promise<void> {
        if (!quiet) this.loading.set(true);
        try {
            const catalog = await firstValueFrom(this.api.load());
            this.current.set(catalog.current);
            this.discount.set(catalog.pendingDiscount);
        } catch {
            if (!quiet) this.current.set(null);
        } finally { this.loading.set(false); }
    }
}
