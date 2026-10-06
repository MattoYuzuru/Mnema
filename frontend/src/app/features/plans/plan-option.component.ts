import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';

import { PLAN_LABEL, priceText } from './plans-view';
import { PlanEntry, PlanPeriod } from './plans.models';

let nextOption = 0;

/**
 * One tier of the paywall as a native radio card. The radio is the control and the plan name its label; the price and the
 * three highlights describe it (`aria-describedby`), so a screen reader hears them with the choice. The whole card is
 * pressable through the label's stretched hit area. A recommendation and «Ваш тариф» are text, never colour alone. A teaser
 * («В работе») is a disabled radio with its lines: it shows what is coming and cannot be chosen.
 */
@Component({
    selector: 'app-plan-option',
    template: `
      <div class="plan" [class.is-selected]="selected()" [class.is-recommended]="badge() !== null" [class.is-teaser]="teaser()">
        <div class="head">
          <input type="radio" [id]="inputId" [name]="name()" [value]="entry().plan" [checked]="selected()" [disabled]="teaser()"
                 [attr.aria-describedby]="describedBy()" (change)="choose.emit(entry().plan)" />
          <label [for]="inputId" class="plan-name">{{ label() }}</label>
        </div>
        @if (teaser()) { <p class="stamp">В работе</p> }
        @else if (current()) { <p class="stamp solid">Ваш тариф</p> }
        @if (badge(); as text) { <p class="stamp solid">{{ text }}</p> }
        <p class="price" [id]="priceId"><strong>{{ price().main }}</strong>
          @if (price().note; as note) { <span class="note">{{ note }}</span> }
          @if (price().saving; as saving) { <span class="saving">{{ saving }}</span> }</p>
        <ul class="lines" [id]="linesId">
          @for (line of entry().highlights; track line) { <li>{{ line }}</li> }
        </ul>
      </div>
    `,
    styleUrl: './plan-option.component.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class PlanOptionComponent {
    readonly entry = input.required<PlanEntry>();
    readonly period = input<PlanPeriod>('MONTH');
    readonly selected = input(false);
    /** The radio group name shared by the tiers. */
    readonly name = input.required<string>();
    /** «Рекомендуем для подготовки к экзаменам»; null when this tier is not the recommended one. */
    readonly badge = input<string | null>(null);
    /** The account's own plan. */
    readonly current = input(false);
    readonly choose = output<PlanEntry['plan']>();

    private readonly uid = `mn-plan-${nextOption++}`;
    protected readonly inputId = `${this.uid}-input`;
    protected readonly priceId = `${this.uid}-price`;
    protected readonly linesId = `${this.uid}-lines`;
    protected readonly teaser = computed(() => this.entry().availability === 'TEASER');
    protected readonly label = computed(() => PLAN_LABEL[this.entry().plan]);
    protected readonly price = computed(() => priceText(this.entry(), this.period()));
    protected readonly describedBy = computed(() => `${this.priceId} ${this.linesId}`);
}
