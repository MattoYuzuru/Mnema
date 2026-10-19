import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { onboardingText } from '../core/onboarding/onboarding-texts';
import { weekDates, weekSummary } from '../core/onboarding/rhythm-format';

type WeekdayShortKey = `rhythm.week.short.${1 | 2 | 3 | 4 | 5 | 6 | 7}`;

/**
 * The seven days of the week of the rhythm (OB-401), Monday first: a circle per day. A day with a completed session is a **filled circle with a
 * check mark** (two signs, never colour alone), today is an **outlined** circle (a shape, not a hue), and a plain day is an empty ring. Nothing
 * is negative: a day without a session is just empty, a day that has not come yet looks the same. A screen reader hears one sentence of the
 * week («Эта неделя: занятия во вторник и в пятницу») from a visually hidden paragraph; the visual letters and marks are `aria-hidden`.
 * Presentational: the dates come in as the contract sends them (`YYYY-MM-DD`, the learner's local days); nothing here reads the clock or
 * motion (nothing animates). In forced colours the filled day keeps its fill with system colours and today keeps its outline.
 */
@Component({
    selector: 'app-rhythm-week',
    template: `
      <div class="rhythm-week">
        <p class="visually-hidden">{{ summary() }}</p>
        <ol class="week" aria-hidden="true">
          @for (day of cells(); track day.date) {
            <li [class.done]="day.studied" [class.today]="day.today">
              <span class="day">{{ day.studied ? '✓' : '' }}</span>
              <span class="name">{{ day.name }}</span>
            </li>
          }
        </ol>
      </div>
    `,
    styles: [`
      :host { display: block; min-inline-size: 0; }
      .week { display: grid; grid-template-columns: repeat(7, minmax(0, 3rem)); gap: .35rem; margin: 0; padding: 0; list-style: none; }
      .week li { display: grid; justify-items: center; gap: .25rem; color: var(--mn-muted); font: 600 .72rem/1.2 var(--mn-font-mono); }
      .day { display: grid; place-items: center; inline-size: 1.6rem; block-size: 1.6rem; border: 1px solid var(--mn-muted); border-radius: 50%; font-size: .85rem; line-height: 1; }
      .done .day { border-color: var(--mn-ink); background: var(--mn-ink); color: var(--mn-on-ink); }
      .today { color: var(--mn-ink); }
      .today .day { outline: 2px solid var(--mn-ink); outline-offset: 2px; }
      @media (forced-colors: active) {
        .day { border-color: CanvasText; }
        .done .day { background: CanvasText; color: Canvas; forced-color-adjust: none; }
        .today .day { outline-color: Highlight; }
      }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class RhythmWeekComponent {
    /** The Monday of the week, `YYYY-MM-DD`. */
    readonly weekStart = input.required<string>();
    /** The learner's local date today, `YYYY-MM-DD`; outside the week no day is marked. */
    readonly today = input.required<string>();
    /** The dates of the week with a completed session. */
    readonly days = input.required<readonly string[]>();

    protected readonly cells = computed(() => {
        const studied = this.days();
        return weekDates(this.weekStart()).map((date, index) => ({
            date, studied: studied.includes(date), today: date === this.today(), name: onboardingText(`rhythm.week.short.${index + 1}` as WeekdayShortKey)
        }));
    });
    protected readonly summary = computed(() => weekSummary(this.weekStart(), this.days()));
}
