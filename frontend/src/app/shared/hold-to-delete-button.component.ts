import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, computed, effect, inject, input, output, signal } from '@angular/core';

import { GlyphComponent, GlyphName } from './glyph.component';

/** Deleting takes three seconds of holding; a variant can ask for another duration with `holdMs`. */
export const DEFAULT_HOLD_MS = 3_000;
const STEPS = 4;
const TICK_MS = 25;
let nextHoldId = 0;

/**
 * The shared hold gesture. A separate activation followed by a continuous hold prevents accidental destructive taps. The optional
 * `consequence` states what will disappear: it is the button's accessible description at all times and is shown as
 * text under the button while it is armed, so the learner reads it before holding. Escape is consumed only while the
 * button is armed or held, otherwise it bubbles (a surrounding selection can use it to clear itself).
 *
 * Two uses share one behaviour. The deletion (the defaults): three seconds, a countdown label «Удерживайте N с» while armed, the
 * danger colour. The early finish of the onboarding tour (`holdMs` 2000, `armedGlyph="finger-hold"`, `armedName`, `tone="ink"`):
 * once armed the label is replaced by a glyph with no extra text and the armed accessible name carries the instruction. With a
 * glyph there is no countdown, so under reduced motion (or `steppedFill`) the fill advances in four discrete steps without animation.
 */
@Component({
    selector: 'app-hold-to-delete-button',
    imports: [GlyphComponent],
    host: { '(document:pointerdown)': 'cancelOutside($event)' },
    template: `
      <button type="button" class="hold-button" [class.holding]="holding()" [class.has-glyph]="armedGlyph() !== null" [class.tone-ink]="tone() === 'ink'"
        [class.is-stepped]="stepped()" [disabled]="disabled() && !focusable()" [attr.aria-disabled]="disabled() && focusable() ? 'true' : null" [attr.aria-pressed]="armed()"
        [attr.aria-label]="armed() && armedName() ? armedName() : null"
        [attr.aria-describedby]="consequence() ? consequenceId : null"
        [style.--hold-x.px]="waveX()" [style.--hold-y.px]="waveY()" [style.--hold-size.px]="waveSize()" [style.--hold-step.%]="step() * 100 / steps"
        (click)="onClick()" (pointerdown)="onPointerDown($event)" (pointerup)="stopHold()"
        (pointercancel)="stopHold()" (pointerleave)="stopHold()" (keydown)="onKeyDown($event)"
        (keyup)="onKeyUp($event)" (blur)="cancel()">
        <span class="hold-wave" aria-hidden="true"></span>
        <span class="label-reserve" aria-hidden="true">{{ label() }}</span>
        @if (armedGlyph() === null) { <span class="label-reserve" aria-hidden="true">Удерживайте {{ holdSeconds() }} с</span> }
        @if (armed() && armedGlyph(); as glyph) {
          <app-glyph class="hold-glyph" [name]="glyph" />
        } @else {
          <span class="hold-label">{{ armed() ? 'Удерживайте ' + remaining() + ' с' : label() }}</span>
        }
      </button>
      @if (consequence()) { <span class="consequence" [id]="consequenceId" [hidden]="!armed()">{{ consequence() }}</span> }
    `,
    styleUrl: './hold-to-delete-button.component.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class HoldToDeleteButtonComponent {
    readonly label = input.required<string>();
    readonly disabled = input(false);
    /** While `disabled`, stay in the focus order (`aria-disabled`, every action still refused) instead of dropping out with the `disabled` attribute. */
    readonly focusable = input(false);
    /** What the deletion removes, in words; empty when the label already says it. */
    readonly consequence = input('');
    /** How long the button must be held, in milliseconds. */
    readonly holdMs = input(DEFAULT_HOLD_MS);
    /** A glyph that replaces the label (and the countdown) while armed; `null` keeps the countdown label. */
    readonly armedGlyph = input<GlyphName | null>(null);
    /** The accessible name while armed, e.g. the instruction to hold; empty keeps the visible label as the name. */
    readonly armedName = input('');
    /** `danger` for irreversible deletion; `ink` for a gesture that is not destructive (the tour's early finish). */
    readonly tone = input<'danger' | 'ink'>('danger');
    /** Fill in four steps without animation regardless of the system setting (the styleguide's «Спокойное движение» preview). */
    readonly steppedFill = input(false);
    readonly confirmed = output<void>();
    readonly armed = signal(false);
    readonly holding = signal(false);
    readonly waveX = signal(0);
    readonly waveY = signal(0);
    readonly waveSize = signal(0);
    /** Milliseconds held so far in the current attempt. */
    readonly elapsed = signal(0);
    readonly steps = STEPS;
    readonly holdSeconds = computed(() => Math.ceil(this.holdMs() / 1_000));
    readonly remaining = computed(() => Math.max(1, Math.ceil((this.holdMs() - this.elapsed()) / 1_000)));
    /** Discrete progress 0..4: what a calm fill shows. */
    readonly step = computed(() => Math.min(STEPS, Math.floor(this.elapsed() / this.holdMs() * STEPS)));
    private readonly reducedMotion = signal(false);
    readonly stepped = computed(() => this.armedGlyph() !== null && (this.steppedFill() || this.reducedMotion()));
    protected readonly consequenceId = `mn-hold-consequence-${nextHoldId++}`;

    private readonly element = inject<ElementRef<HTMLElement>>(ElementRef);
    private readonly destroyRef = inject(DestroyRef);
    private timer: ReturnType<typeof setInterval> | null = null;
    private startedAt = 0;
    private radius = 0;
    private source: 'pointer' | 'keyboard' | null = null;
    private suppressTrailingClick = false;
    private readonly disabledEffect = effect(() => { if (this.disabled()) this.cancel(); });

    constructor() {
        const motion = typeof matchMedia === 'function' ? matchMedia('(prefers-reduced-motion: reduce)') : null;
        if (motion !== null) {
            this.reducedMotion.set(motion.matches);
            const changed = (event: MediaQueryListEvent): void => this.reducedMotion.set(event.matches);
            motion.addEventListener('change', changed);
            this.destroyRef.onDestroy(() => motion.removeEventListener('change', changed));
        }
        this.destroyRef.onDestroy(() => this.clearTimer());
    }

    cancelOutside(event: PointerEvent): void {
        if (this.armed() && event.target instanceof Node && !this.element.nativeElement.contains(event.target)) this.cancel();
    }

    onClick(): void {
        if (this.suppressTrailingClick) { this.suppressTrailingClick = false; return; }
        if (!this.disabled() && !this.armed()) this.armed.set(true);
    }

    onPointerDown(event: PointerEvent): void {
        if (!this.armed()) { this.suppressTrailingClick = false; return; }
        if (this.disabled() || event.button !== 0) return;
        event.preventDefault();
        const button = event.currentTarget as HTMLButtonElement;
        const rect = button.getBoundingClientRect();
        this.begin('pointer', button, event.clientX - rect.left, event.clientY - rect.top);
    }

    onKeyDown(event: KeyboardEvent): void {
        if (!this.armed()) this.suppressTrailingClick = false;
        if (event.key === 'Escape') {
            if (this.armed()) { event.preventDefault(); event.stopPropagation(); this.cancel(); }
            return;
        }
        if (!this.armed() || this.disabled() || event.repeat || (event.key !== 'Enter' && event.key !== ' ')) return;
        event.preventDefault();
        const button = event.currentTarget as HTMLButtonElement;
        this.begin('keyboard', button, button.offsetWidth / 2, button.offsetHeight / 2);
    }

    onKeyUp(event: KeyboardEvent): void {
        if (event.key === 'Enter' || event.key === ' ') {
            if (this.source === 'keyboard') event.preventDefault();
            this.stopHold();
        }
    }

    stopHold(): void {
        if (this.timer === null) return;
        this.clearTimer();
        this.waveSize.set(0);
        this.elapsed.set(0);
        this.source = null;
        this.holding.set(false);
    }

    cancel(): void {
        this.stopHold();
        this.armed.set(false);
    }

    private begin(source: 'pointer' | 'keyboard', button: HTMLButtonElement, x: number, y: number): void {
        if (this.timer !== null) return;
        const width = button.offsetWidth;
        const height = button.offsetHeight;
        this.waveX.set(x);
        this.waveY.set(y);
        this.radius = Math.hypot(Math.max(x, width - x), Math.max(y, height - y));
        this.startedAt = Date.now();
        this.source = source;
        this.holding.set(true);
        const duration = this.holdMs();
        this.timer = setInterval(() => {
            const elapsed = Math.min(duration, Date.now() - this.startedAt);
            this.elapsed.set(elapsed);
            this.waveSize.set(this.radius * elapsed / duration);
            if (elapsed === duration) this.finish();
        }, TICK_MS);
    }

    private finish(): void {
        this.clearTimer();
        this.source = null;
        this.holding.set(false);
        this.armed.set(false);
        this.waveSize.set(0);
        this.elapsed.set(0);
        this.suppressTrailingClick = true;
        this.confirmed.emit();
    }

    private clearTimer(): void {
        if (this.timer !== null) clearInterval(this.timer);
        this.timer = null;
    }
}
