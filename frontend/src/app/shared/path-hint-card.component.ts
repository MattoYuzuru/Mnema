import { ChangeDetectionStrategy, Component, ElementRef, inject, input, output, viewChild } from '@angular/core';

import { onboardingText } from '../core/onboarding/onboarding-texts';
import { CardSide, Point } from '../core/onboarding/tour-geometry';

let nextCard = 0;

/**
 * The card of the in-place hint of «Путь»: the title of the milestone, one sentence about the place, «Понятно». A non-modal dialog
 * (`role="dialog"`, `aria-modal="false"`) named by its heading and described by the sentence; no veil, the page stays live. The heading
 * takes the focus when the hint opens (`focusHeading`), which reads the name and the sentence out, and «Понятно» (or Escape, which the
 * host listens for) closes it and returns the focus to the thing it points at.
 *
 * `floating`: fixed at `at` (px, the host measures the anchor), with the arrow on the side facing it; `inline`: in the flow, for the
 * top of the page when the anchor never appeared, and for the styleguide.
 */
@Component({
    selector: 'app-path-hint-card',
    host: { '[class.floating]': 'floating()', '[attr.data-side]': 'floating() ? side() : null', '[style.left.px]': 'floating() ? (at()?.left ?? 0) : null',
        '[style.top.px]': 'floating() ? (at()?.top ?? 0) : null', '[style.opacity]': 'floating() && at() === null ? \'0\' : null' },
    template: `
      <section class="card" role="dialog" aria-modal="false" [attr.aria-labelledby]="titleId" [attr.aria-describedby]="textId">
        <h2 #heading [id]="titleId" tabindex="-1">{{ title() }}</h2>
        <p [id]="textId">{{ text() }}</p>
        <button type="button" class="button" (click)="acknowledged.emit()">{{ okLabel }}</button>
      </section>
    `,
    styles: [`
      :host { display: block; box-sizing: border-box; max-inline-size: 24rem; }
      :host(.floating) { position: fixed; z-index: 20; inline-size: min(20rem, calc(100vw - 2 * var(--mn-page-gutter))); }
      .card { position: relative; display: grid; gap: .6rem; justify-items: start; border: 1px solid var(--mn-ink); padding: .9rem 1rem 1rem; color: var(--mn-body); background: var(--mn-sheet); }
      h2 { margin: 0; color: var(--mn-ink); font: 600 1.3rem/1.15 var(--mn-font-display); overflow-wrap: anywhere; }
      /* The heading receives programmatic focus when the hint opens; it is not a control. */
      h2:focus-visible { outline: none; }
      p { margin: 0; }
      /* The arrow points at the anchor from the side the card stands on; with no side (overlay, inline) there is none. */
      :host(.floating) .card::before { content: ''; position: absolute; inline-size: .8rem; block-size: .8rem; border: 1px solid var(--mn-ink); background: var(--mn-sheet); display: none; }
      :host([data-side='bottom']) .card::before { display: block; inset-block-start: -.45rem; inset-inline-start: 1.5rem; border-inline-end: 0; border-block-end: 0; transform: rotate(45deg); }
      :host([data-side='top']) .card::before { display: block; inset-block-end: -.45rem; inset-inline-start: 1.5rem; border-inline-start: 0; border-block-start: 0; transform: rotate(45deg); }
      :host([data-side='start']) .card::before { display: block; inset-inline-end: -.45rem; inset-block-start: 1.25rem; border-inline-start: 0; border-block-end: 0; transform: rotate(45deg); }
      :host([data-side='end']) .card::before { display: block; inset-inline-start: -.45rem; inset-block-start: 1.25rem; border-inline-end: 0; border-block-start: 0; transform: rotate(45deg); }
      @media (forced-colors: active) { .card { border: 2px solid CanvasText; } :host(.floating) .card::before { border-color: CanvasText; } }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class PathHintCardComponent {
    readonly title = input.required<string>();
    readonly text = input.required<string>();
    /** Fixed next to its anchor at `at`; otherwise in the flow. */
    readonly floating = input(false);
    readonly at = input<Point | null>(null);
    /** The side of the anchor the card stands on (the arrow points back at it). */
    readonly side = input<CardSide | 'overlay'>('bottom');
    /** «Понятно»: the learner read it. */
    readonly acknowledged = output<void>();

    protected readonly okLabel = onboardingText('hint.ok');
    private readonly id = nextCard++;
    protected readonly titleId = `mn-path-hint-${this.id}-title`;
    protected readonly textId = `mn-path-hint-${this.id}-text`;
    private readonly heading = viewChild.required<ElementRef<HTMLElement>>('heading');
    private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

    /** The card element, for measuring. */
    get element(): HTMLElement { return this.host.nativeElement; }

    /** Moves the focus to the heading: what a screen reader reads first (the name, then the sentence). */
    focusHeading(): void {
        this.heading().nativeElement.focus({ preventScroll: true });
    }
}
