import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { UsageMeterComponent } from '../../shared/usage-meter.component';
import { UsageApiService } from './usage-api.service';
import { UsageSnapshot } from './usage.models';
import { describeUsage } from './usage-view';

/**
 * The body of the profile's «ИИ-бюджет» block: the shared meter fed from `GET /api/usage`, the plan, and quiet
 * fair-use counters that appear only above 80 %. Fail-soft: any error or a missing endpoint leaves a short neutral
 * message with a retry, never an exception, so the rest of the profile keeps working.
 */
@Component({
    selector: 'app-usage-budget',
    imports: [UsageMeterComponent],
    template: `
      @if (loading()) {
        <p class="hint" role="status">Считаем расход ИИ…</p>
      } @else if (view(); as usage) {
        <p class="plan">Тариф {{ usage.planName }}</p>
        <app-usage-meter [label]="usage.label" [total]="usage.total" [used]="usage.used" [reserved]="usage.reserved"
                         [unlocked]="usage.unlocked" [ticks]="usage.ticks" [remaining]="usage.remaining"
                         [nextUnlock]="usage.nextUnlock" [resetsOn]="usage.resetsOn" />
        @if (usage.burstNote; as note) { <p class="hint">{{ note }}</p> }
        @if (usage.counters.length > 0) {
          <section class="fair-use" aria-labelledby="fair-use-heading">
            <h3 id="fair-use-heading">Скоро упрутся в предел</h3>
            <p class="hint">Эти лимиты не тратят бюджет ИИ, но у них свой потолок на месяц.</p>
            <ul>
              @for (counter of usage.counters; track counter.id) { <li>{{ counter.text }}</li> }
            </ul>
          </section>
        }
      } @else {
        <p class="hint" role="status">Не удалось узнать расход ИИ. Остальной профиль работает как обычно.</p>
        <button type="button" class="button retry" (click)="load()">Повторить</button>
      }
    `,
    styles: [`
      :host { display: block; min-inline-size: 0; }
      .plan { margin: 0 0 1rem; color: var(--mn-muted); font-size: .9rem; }
      .hint { margin-block-start: .75rem; }
      .fair-use { margin-block-start: 1.5rem; border-block-start: 1px solid var(--mn-rule); padding-block-start: 1rem; }
      h3 { margin: 0; color: var(--mn-ink); font: 700 .95rem/1.3 var(--mn-font-body, system-ui, sans-serif); }
      ul { margin: .5rem 0 0; padding-inline-start: 1.1rem; }
      li { margin-block: .25rem; line-height: 1.5; overflow-wrap: anywhere; }
      .retry { margin-block-start: .75rem; }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class UsageBudgetComponent implements OnInit {
    private readonly api = inject(UsageApiService);
    private readonly usage = signal<UsageSnapshot | null>(null);
    readonly loading = signal(true);
    protected readonly view = computed(() => {
        const usage = this.usage();
        return usage === null ? null : describeUsage(usage);
    });

    ngOnInit(): void { void this.load(); }

    async load(): Promise<void> {
        this.loading.set(true);
        try {
            this.usage.set(await firstValueFrom(this.api.load()));
        } catch {
            this.usage.set(null);
        } finally { this.loading.set(false); }
    }
}
