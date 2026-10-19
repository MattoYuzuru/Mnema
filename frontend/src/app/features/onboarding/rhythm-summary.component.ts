import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { Rhythm } from '../../core/onboarding/onboarding.models';
import { onboardingText } from '../../core/onboarding/onboarding-texts';
import { dayOrdinal, daysLabel } from '../../core/onboarding/rhythm-format';
import { RhythmWeekComponent } from '../../shared/rhythm-week.component';

/**
 * The rhythm on the screen that ends a session (OB-401): «Второй день на этой неделе», the seven days of the week and «Цель — 3 дня в неделю».
 * Presentational: the page that hosts it decides when to show it (only after the journey view was read again once the session was
 * completed, and only when the study date of the session is among the studied days, so the number is the one of this session; never a spinner, never in the way
 * of the summary). Nothing here counts weeks: `weeksMet` is for the profile alone, and no word is negative.
 */
@Component({
    selector: 'app-rhythm-summary',
    imports: [RhythmWeekComponent],
    template: `
      <section class="rhythm" aria-labelledby="rhythm-day-title">
        <h3 id="rhythm-day-title">{{ heading() }}</h3>
        <app-rhythm-week [weekStart]="rhythm().weekStart" [today]="rhythm().today" [days]="rhythm().days" />
        <p class="hint">{{ target() }}</p>
      </section>
    `,
    styles: [`
      :host { display: block; min-inline-size: 0; margin-block: 1.25rem; }
      .rhythm { display: grid; gap: .75rem; justify-items: start; min-inline-size: 0; border-block: 1px solid var(--mn-rule); padding-block: 1rem; }
      h3 { margin: 0; color: var(--mn-ink); font: 500 1.6rem/1.1 var(--mn-font-display); overflow-wrap: anywhere; }
      @media (forced-colors: active) { .rhythm { border-color: CanvasText; } }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class RhythmSummaryComponent {
    readonly rhythm = input.required<Rhythm>();
    protected readonly heading = computed(() => onboardingText('rhythm.day', { ordinal: dayOrdinal(this.rhythm().days.length) }));
    protected readonly target = computed(() => onboardingText('rhythm.target', { days: daysLabel(this.rhythm().target) }));
}
