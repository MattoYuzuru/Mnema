import { ChangeDetectionStrategy, Component, ElementRef, input, output, viewChild } from '@angular/core';

import { onboardingText } from '../core/onboarding/onboarding-texts';

let nextNudge = 0;

/**
 * An offer the tour makes in its own words, without the veil: «Не нашли подходящую? — Создать свою». A named region with a heading,
 * one primary action and «Назад» (stay where you are). It never takes the focus by itself and is never modal: the learner goes on
 * with the page. `inline` draws it in the flow (the styleguide specimen); otherwise it stands at the lower edge of the window. The
 * onboarding host draws it and is a tour surface (`data-tour-surface`), so it stays live while the page behind the tour is inert.
 */
@Component({
    selector: 'app-tour-nudge',
    host: { '[class.inline]': 'inline()' },
    template: `
      <section class="nudge" [attr.aria-labelledby]="titleId">
        <h2 #heading [id]="titleId" tabindex="-1">{{ title() }}</h2>
        <p>{{ body() }}</p>
        <div class="actions">
          <button type="button" class="button quiet" (click)="dismissed.emit()">{{ dismissLabel }}</button>
          <button type="button" class="button primary" (click)="accepted.emit()">{{ action() }}</button>
        </div>
      </section>
    `,
    styles: [`
      :host { position: fixed; z-index: 1001; inset-inline-end: var(--mn-page-gutter); inset-block-end: calc(1rem + env(safe-area-inset-bottom, 0px)); inline-size: min(24rem, calc(100vw - 2 * var(--mn-page-gutter))); }
      :host(.inline) { position: static; inline-size: auto; max-inline-size: 24rem; }
      .nudge { display: grid; gap: .8rem; border: 1px solid var(--mn-rule); border-block-start: 3px solid var(--mn-ink); padding: 1.1rem 1.2rem 1rem; color: var(--mn-body); background: var(--mn-sheet); }
      h2 { margin: 0; color: var(--mn-ink); font: 600 1.6rem/1.1 var(--mn-font-display); }
      h2:focus-visible { outline: none; }
      p { margin: 0; }
      .actions { display: flex; flex-wrap: wrap; gap: .5rem; justify-content: space-between; align-items: center; border-block-start: 1px solid var(--mn-rule); padding-block-start: .75rem; }
      @media (forced-colors: active) { .nudge { border: 2px solid CanvasText; } }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class TourNudgeComponent {
    readonly title = input.required<string>();
    readonly body = input.required<string>();
    readonly action = input.required<string>();
    readonly inline = input(false);
    readonly accepted = output<void>();
    readonly dismissed = output<void>();
    protected readonly dismissLabel = onboardingText('tour.back');
    protected readonly titleId = `mn-tour-nudge-${nextNudge++}-title`;
    private readonly heading = viewChild.required<ElementRef<HTMLElement>>('heading');

    focusHeading(): void {
        this.heading().nativeElement.focus({ preventScroll: true });
    }
}
