import { ChangeDetectionStrategy, Component, DestroyRef, effect, inject, input, output, signal } from '@angular/core';

const HOLD_MS = 3_000;

/** A separate activation followed by a continuous hold prevents accidental destructive taps. */
@Component({
    selector: 'app-hold-to-delete-button',
    template: `
      <button type="button" class="hold-button" [class.holding]="holding()" [disabled]="disabled()" [attr.aria-pressed]="armed()"
        [style.--hold-x.px]="waveX()" [style.--hold-y.px]="waveY()" [style.--hold-size.px]="waveSize()"
        (click)="onClick()" (pointerdown)="onPointerDown($event)" (pointerup)="stopHold()"
        (pointercancel)="stopHold()" (pointerleave)="stopHold()" (keydown)="onKeyDown($event)"
        (keyup)="onKeyUp($event)" (blur)="cancel()">
        <span class="hold-wave" aria-hidden="true"></span>
        <span class="hold-label">{{ armed() ? 'Удерживайте ' + remaining() + ' с' : label() }}</span>
      </button>
      @if (armed()) { <span class="hold-hint" role="status">Удерживайте кнопку или клавишу Enter/Пробел. Esc отменяет.</span> }
    `,
    styleUrl: './hold-to-delete-button.component.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class HoldToDeleteButtonComponent {
    readonly label = input.required<string>();
    readonly disabled = input(false);
    readonly confirmed = output<void>();
    readonly armed = signal(false);
    readonly holding = signal(false);
    readonly waveX = signal(0);
    readonly waveY = signal(0);
    readonly waveSize = signal(0);
    readonly remaining = signal(3);

    private readonly destroyRef = inject(DestroyRef);
    private timer: ReturnType<typeof setInterval> | null = null;
    private startedAt = 0;
    private radius = 0;
    private source: 'pointer' | 'keyboard' | null = null;
    private suppressTrailingClick = false;
    private readonly disabledEffect = effect(() => { if (this.disabled()) this.cancel(); });

    constructor() { this.destroyRef.onDestroy(() => this.clearTimer()); }

    onClick(): void {
        if (this.suppressTrailingClick) { this.suppressTrailingClick = false; return; }
        if (!this.disabled() && !this.armed()) this.armed.set(true);
    }

    onPointerDown(event: PointerEvent): void {
        if (!this.armed()) { this.suppressTrailingClick = false; return; }
        if (!this.armed() || this.disabled() || event.button !== 0) return;
        event.preventDefault();
        const button = event.currentTarget as HTMLButtonElement;
        const rect = button.getBoundingClientRect();
        this.begin('pointer', button, event.clientX - rect.left, event.clientY - rect.top);
    }

    onKeyDown(event: KeyboardEvent): void {
        if (!this.armed()) this.suppressTrailingClick = false;
        if (event.key === 'Escape') { event.preventDefault(); this.cancel(); return; }
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
        this.remaining.set(3);
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
        this.timer = setInterval(() => {
            const elapsed = Math.min(HOLD_MS, Date.now() - this.startedAt);
            this.waveSize.set(this.radius * elapsed / HOLD_MS);
            this.remaining.set(Math.max(1, Math.ceil((HOLD_MS - elapsed) / 1_000)));
            if (elapsed === HOLD_MS) this.finish();
        }, 25);
    }

    private finish(): void {
        this.clearTimer();
        this.source = null;
        this.holding.set(false);
        this.armed.set(false);
        this.waveSize.set(0);
        this.suppressTrailingClick = true;
        this.confirmed.emit();
    }

    private clearTimer(): void {
        if (this.timer !== null) clearInterval(this.timer);
        this.timer = null;
    }
}
