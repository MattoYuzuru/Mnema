import { ChangeDetectionStrategy, Component, ElementRef, input, output, viewChild } from '@angular/core';

import { onboardingText } from '../core/onboarding/onboarding-texts';

/**
 * The plaque the tour folds into when the learner presses Escape: «Знакомство на паузе» and «Продолжить знакомство». It sits at the
 * bottom of the window over the unchanged page; focus goes to the button (`focusResume`), and resuming returns to the same step.
 * `inline` draws it in the flow (the styleguide specimen).
 */
@Component({
    selector: 'app-tour-pause',
    host: { '[class.inline]': 'inline()' },
    template: `
      <div class="plaque" role="region" [attr.aria-label]="label">
        <span>{{ label }}</span>
        <button #resume type="button" class="button primary" (click)="resumed.emit()">{{ resumeLabel }}</button>
      </div>
    `,
    styles: [`
      :host { position: fixed; z-index: 1000; inset-inline: var(--mn-page-gutter); inset-block-end: calc(1rem + env(safe-area-inset-bottom, 0px)); display: block; pointer-events: none; }
      :host(.inline) { position: static; }
      .plaque { display: flex; flex-wrap: wrap; gap: .5rem 1rem; align-items: center; justify-content: space-between; max-inline-size: 30rem; margin-inline: auto; border: 1px solid var(--mn-ink);
        padding: .5rem .5rem .5rem 1rem; color: var(--mn-ink); background: var(--mn-sheet); font-weight: 600; pointer-events: auto; }
      @media (forced-colors: active) { .plaque { border: 2px solid CanvasText; } }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class TourPauseComponent {
    readonly inline = input(false);
    readonly resumed = output<void>();
    protected readonly label = onboardingText('tour.pause.label');
    protected readonly resumeLabel = onboardingText('tour.pause.resume');
    private readonly resumeButton = viewChild.required<ElementRef<HTMLButtonElement>>('resume');

    focusResume(): void {
        this.resumeButton().nativeElement.focus();
    }
}
