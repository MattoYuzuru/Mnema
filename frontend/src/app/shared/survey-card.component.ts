import { ChangeDetectionStrategy, Component, ElementRef, computed, input, viewChild } from '@angular/core';

import { onboardingText } from '../core/onboarding/onboarding-texts';

let nextCard = 0;

/**
 * A paper card on the night surface: a question of the first-entry survey, or the choice of a branch. With `question` set it carries
 * the progress of the survey: «Вопрос N из 4» and a `role="progressbar"` of the questions behind the learner; without it the card
 * is only a titled sheet. The heading takes the focus when the card opens (`focusHeading`). Actions and the note under them are
 * projected (`[actions]`, `[note]`); the body is the default slot.
 */
@Component({
    selector: 'app-survey-card',
    template: `
      <section class="sheet" [attr.aria-labelledby]="titleId">
        @if (question(); as number) {
          <div class="progress">
            <p class="eyebrow">{{ counter() }}</p>
            <div class="progress-track" role="progressbar" [attr.aria-label]="progressLabel" aria-valuemin="0" [attr.aria-valuemax]="total" [attr.aria-valuenow]="number - 1"
              [attr.aria-valuetext]="valueText()"><div class="progress-fill" [style.inline-size.%]="(number - 1) * 100 / total"></div></div>
          </div>
        }
        <h2 #heading [id]="titleId" tabindex="-1">{{ title() }}</h2>
        <ng-content />
        <div class="actions"><ng-content select="[actions]" /></div>
        <ng-content select="[note]" />
      </section>
    `,
    styles: [`
      :host { display: block; inline-size: min(100%, 36rem); text-align: start; }
      .sheet { display: grid; gap: 1.1rem; border-block-start: 3px solid var(--mn-star-lavender); padding: clamp(1.25rem, 4vw, 2rem); color: var(--mn-body); background: var(--mn-sheet); }
      h2 { margin: 0; color: var(--mn-ink); font: 600 clamp(1.7rem, 5vw, 2.3rem)/1.1 var(--mn-font-display); }
      h2:focus-visible { outline: none; }
      .progress { display: grid; gap: .35rem; }
      .progress .eyebrow { margin: 0; }
      .progress-track { block-size: .375rem; border: 1px solid var(--mn-rule); background: var(--mn-soft); }
      .progress-fill { block-size: 100%; background: var(--mn-ink); }
      .actions { display: flex; flex-wrap: wrap; gap: .5rem; justify-content: space-between; align-items: center; }
      .actions:empty { display: none; }
      @media (forced-colors: active) { .sheet { border: 2px solid CanvasText; } .progress-track { border-color: CanvasText; } .progress-fill { background: CanvasText; } }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class SurveyCardComponent {
    readonly title = input.required<string>();
    /** The number of the question, 1 to {@link total}; `null` for a card that is not a question. */
    readonly question = input<number | null>(null);
    protected readonly total = 4;
    protected readonly titleId = `mn-survey-card-${nextCard++}-title`;
    protected readonly progressLabel = onboardingText('survey.progress.label');
    protected readonly counter = computed(() => onboardingText('survey.counter', { n: this.question() ?? 1 }));
    protected readonly valueText = computed(() => onboardingText('survey.progress.value', { n: (this.question() ?? 1) - 1 }));
    private readonly heading = viewChild.required<ElementRef<HTMLElement>>('heading');

    focusHeading(): void {
        this.heading().nativeElement.focus({ preventScroll: true });
    }
}
