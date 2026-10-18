import { DOCUMENT, NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, afterRenderEffect, computed, effect, inject, input, linkedSignal, output, signal, untracked, viewChild } from '@angular/core';

import { onboardingText } from '../core/onboarding/onboarding-texts';
import { Point } from '../core/onboarding/tour-geometry';
import { TourBody, TourGate, isActionGate } from '../core/onboarding/tour.models';
import { HoldToDeleteButtonComponent } from './hold-to-delete-button.component';

/** How long «Завершить знакомство» is held: the `--mn-tour-hold` token (2s), in milliseconds. A spec keeps the two equal. */
export const TOUR_HOLD_MS = 2_000;
let nextCard = 0;

/** `normal`: the step; `missing`: the anchor never appeared, the text says so; `preparing`: the app is still preparing the page. */
export type TourCardMode = 'normal' | 'missing' | 'preparing';

/**
 * The card of one tour step, on a native `<dialog>` with `role="dialog"` named by its heading. An informational step is opened with
 * `showModal()` (the page behind is inert, Tab stays in the card); a step that waits for an action on the target is opened with
 * `show()` so the target stays reachable. Escape asks to pause (`pauseRequest`), it never closes the card by itself. Up to 600 px wide the
 * card is a bottom sheet. «Завершить знакомство» is the shared hold gesture: a first press arms it, two seconds of holding finish.
 * The card owns only presentation and its mini-slider; the tour engine owns the step.
 */
@Component({
    selector: 'app-tour-card',
    imports: [HoldToDeleteButtonComponent, NgTemplateOutlet],
    template: `
      @if (inline()) {
        <!-- A specimen is drawn in the flow and is not an open dialog: an open dialog holds back toasts for the whole page. -->
        <div class="tour-card inline" [class.sheet]="sheet()" role="group" [attr.aria-labelledby]="titleId" [attr.aria-describedby]="counterId + ' ' + bodyId">
          <ng-container *ngTemplateOutlet="content" />
        </div>
      } @else {
        <dialog #surface class="tour-card" role="dialog" [class.sheet]="sheet()" [attr.aria-labelledby]="titleId" [attr.aria-describedby]="counterId + ' ' + bodyId"
          [style.left.px]="floating() ? (at()?.left ?? null) : null" [style.top.px]="floating() ? (at()?.top ?? null) : null" (cancel)="onCancel($event)">
          <ng-container *ngTemplateOutlet="content" />
        </dialog>
      }
      <ng-template #content>
        <div class="tour-head">
          <p class="eyebrow" [id]="counterId">{{ counter() }}</p>
          <div class="dots" aria-hidden="true">
            @for (dot of dots(); track $index) { <span [class.on]="dot"></span> }
          </div>
        </div>
        <!-- Inside the card, so it is heard on a modal step too (the page outside a modal dialog is inert). Empty when the card opens, because the
             dialog is described by the counter then; it says «Шаг N из M» only when the step changes under an open card (the heading says the rest). -->
        <div class="visually-hidden" role="status" aria-live="polite" aria-atomic="true">{{ announcement() }}</div>
        <h2 #heading [id]="titleId" tabindex="-1">{{ title() }}</h2>
        <div [id]="bodyId" class="tour-body">
          @if (mode() === 'missing') {
            <p>{{ missing }}</p>
          } @else if (mode() === 'preparing') {
            <p>{{ preparing }}</p>
          } @else if (slideTexts(); as slideList) {
            <div class="slider" role="group" [attr.aria-label]="sliderLabel">
              <p aria-live="polite">
                @for (segment of slideList[slideIndex()]; track $index) { <span [class.key]="segment.key">{{ segment.text }}</span> }
              </p>
              <div class="slider-controls">
                <button type="button" class="button" [attr.aria-label]="sliderPrevious" (click)="moveSlide(-1)"><span aria-hidden="true">‹</span></button>
                <span class="hint">{{ slideIndex() + 1 }} / {{ slideList.length }}</span>
                <button type="button" class="button" [attr.aria-label]="sliderNext" (click)="moveSlide(1)"><span aria-hidden="true">›</span></button>
              </div>
            </div>
          } @else {
            @for (paragraph of paragraphList(); track $index) {
              <p>@for (segment of paragraph; track $index) { <span [class.key]="segment.key">{{ segment.text }}</span> }</p>
            }
          }
        </div>
        @if (actionLabel(); as label) {
          <p class="gate"><span aria-hidden="true">→</span> {{ gateText(label) }}</p>
        }
        <div class="tour-nav">
          <div class="steps">
            @if (canBack()) { <button type="button" class="button quiet" (click)="back.emit()">{{ backLabel }}</button> }
            @if (showNext()) { <button type="button" class="button primary" (click)="next.emit()">{{ nextLabel }}</button> }
          </div>
          @if (leaveLabel(); as leaving) {
            <button type="button" class="button quiet leave" (click)="leave.emit()">{{ leaving }}</button>
          } @else {
            <app-hold-to-delete-button [label]="finishLabel" [holdMs]="holdMs" armedGlyph="finger-hold" [armedName]="finishArmedName" tone="ink"
              [steppedFill]="calm()" (confirmed)="finish.emit()" />
          }
        </div>
      </ng-template>
    `,
    styleUrl: './tour-card.component.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class TourCardComponent {
    /** «Шаг N из M»: the 1-based number of the step, and how many there are. */
    readonly position = input.required<number>();
    readonly total = input.required<number>();
    readonly title = input.required<string>();
    readonly body = input.required<TourBody>();
    readonly gate = input<TourGate>('next');
    readonly mode = input<TourCardMode>('normal');
    readonly canBack = input(true);
    /** `true`: `showModal()`. An informational step is modal; a step that needs the target is not. */
    readonly modal = input(true);
    /** Bottom sheet (a phone); `at` is ignored. */
    readonly sheet = input(false);
    /** Where the card goes in the window, in px; `null` leaves it where the browser puts a dialog. */
    readonly at = input<Point | null>(null);
    /** Drawn in the flow, always non-modal (the styleguide specimen). */
    readonly inline = input(false);
    /** The calm fill of the finish gesture regardless of the system setting (the styleguide preview switch). */
    readonly calm = input(false);
    /** A side quest leaves with a plain button of this name instead of the hold gesture of «Завершить знакомство»; `null` for the main tour. */
    readonly leaveLabel = input<string | null>(null);
    readonly back = output<void>();
    readonly next = output<void>();
    readonly finish = output<void>();
    readonly leave = output<void>();
    /** Escape was pressed on the card. */
    readonly pauseRequest = output<void>();

    protected readonly holdMs = TOUR_HOLD_MS;
    protected readonly backLabel = onboardingText('tour.back');
    protected readonly nextLabel = onboardingText('tour.next');
    protected readonly finishLabel = onboardingText('tour.finish');
    protected readonly finishArmedName = onboardingText('tour.finish.armed');
    protected readonly missing = onboardingText('tour.missing');
    protected readonly preparing = onboardingText('tour.preparing');
    protected readonly sliderLabel = onboardingText('tour.slider.label');
    protected readonly sliderPrevious = onboardingText('tour.slider.prev');
    protected readonly sliderNext = onboardingText('tour.slider.next');
    private readonly id = nextCard++;
    protected readonly titleId = `mn-tour-card-${this.id}-title`;
    protected readonly bodyId = `mn-tour-card-${this.id}-body`;
    protected readonly counterId = `mn-tour-card-${this.id}-counter`;

    protected readonly counter = computed(() => onboardingText('tour.counter', { n: this.position(), m: this.total() }));
    protected readonly announcement = signal('');
    private announced: string | null = null;
    /** `null` before the first look at the mode: a card that opens already preparing is described by its body instead. */
    private wasPreparing: boolean | null = null;
    protected readonly dots = computed(() => Array.from({ length: this.total() }, (_, index) => index < this.position()));
    protected readonly floating = computed(() => !this.sheet() && !this.inline());
    protected readonly actionLabel = computed(() => {
        const gate = this.gate();
        return this.mode() === 'normal' && isActionGate(gate) ? gate.label : null;
    });
    /** «Дальше» is hidden while the step waits for an action, and while the page is still being prepared; a missing target always lets the learner on. */
    protected readonly showNext = computed(() => this.mode() === 'missing' || (this.mode() === 'normal' && this.gate() === 'next'));
    protected readonly slideTexts = computed(() => {
        const body = this.body();
        return body.kind === 'slides' ? body.slides : null;
    });
    protected readonly paragraphList = computed(() => {
        const body = this.body();
        return body.kind === 'paragraphs' ? body.paragraphs : [];
    });
    /** The slide shown; a new body starts at the first slide. */
    protected readonly slideIndex = linkedSignal<TourBody, number>({ source: this.body, computation: () => 0 });

    private readonly document = inject(DOCUMENT);
    private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
    private readonly surface = viewChild<ElementRef<HTMLDialogElement>>('surface');
    private readonly heading = viewChild.required<ElementRef<HTMLElement>>('heading');
    private opened: 'modal' | 'modeless' | null = null;

    constructor() {
        effect(() => {
            const counter = this.counter();
            untracked(() => {
                const first = this.announced === null;
                this.announced = counter;
                if (!first) this.announcement.set(counter);
            });
        });
        // The page being prepared under an open card (a copied deck) is said in the same status region: the body below is read with the
        // dialog when it opens, but a body that changes under an open card is not announced by itself.
        effect(() => {
            const preparing = this.mode() === 'preparing';
            untracked(() => {
                const was = this.wasPreparing;
                this.wasPreparing = preparing;
                if (preparing && was === false) this.announcement.set(this.preparing);
            });
        });
        afterRenderEffect(() => {
            const modal = this.modal();
            const dialog = this.surface()?.nativeElement;
            if (dialog !== undefined) untracked(() => this.open(dialog, modal ? 'modal' : 'modeless'));
        });
        inject(DestroyRef).onDestroy(() => { if (this.surface()?.nativeElement.open) this.surface()?.nativeElement.close(); });
    }

    /** The card element, for measuring. */
    get element(): HTMLElement {
        return this.surface()?.nativeElement ?? this.host.nativeElement;
    }

    /** Moves focus to the step heading: what a screen reader announces first when the step changes. */
    focusHeading(): void {
        this.heading().nativeElement.focus({ preventScroll: true });
    }

    protected moveSlide(step: -1 | 1): void {
        const count = this.slideTexts()?.length ?? 1;
        this.slideIndex.update(index => (index + step + count) % count);
    }

    protected gateText(label: string): string {
        return onboardingText('tour.gate', { action: label });
    }

    protected onCancel(event: Event): void {
        // The browser would close the dialog; the tour pauses instead and the engine decides what the screen shows.
        event.preventDefault();
        this.pauseRequest.emit();
    }

    /** (Re)opens the dialog in the requested way; a step that changes between modal and not keeps the focus inside the card. */
    private open(dialog: HTMLDialogElement, kind: 'modal' | 'modeless'): void {
        if (dialog.open && this.opened === kind) return;
        const focusInside = dialog.open && dialog.contains(this.document.activeElement);
        if (dialog.open) dialog.close();
        if (kind === 'modal') dialog.showModal(); else dialog.show();
        this.opened = kind;
        if (focusInside) this.focusHeading();
    }
}
