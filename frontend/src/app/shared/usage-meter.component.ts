import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

/** Russian noun forms for 1, 2-4 and 5+ (11-14 take the last form), e.g. `['материал', 'материала', 'материалов']`. */
export type RuPluralForms = readonly [one: string, few: string, many: string];

/** A rough "what is left" figure shown as «хватит на ≈ 12 материалов». */
export interface UsageRemaining {
    readonly count: number;
    readonly forms: RuPluralForms;
}

const NBSP = '\u00a0';

/** Whole percent of `part` in `whole`, rounded half up (the usage contract rounds `percentUsed` the same way), clamped to 0..100. */
function percentOf(part: number, whole: number): number {
    return whole > 0 ? Math.min(100, Math.max(0, Math.floor(part / whole * 100 + 0.5))) : 0;
}

function finiteOrZero(value: number): number { return Number.isFinite(value) ? value : 0; }

function ruPlural(count: number, forms: RuPluralForms): string {
    const lastTwo = count % 100;
    const last = count % 10;
    if (lastTwo >= 11 && lastTwo <= 14) return forms[2];
    if (last === 1) return forms[0];
    return last >= 2 && last <= 4 ? forms[1] : forms[2];
}

/**
 * A usage bar whose text is the source of truth: the sentence under the label says everything the bar shows, and the
 * bar itself is `aria-hidden`. Plain numbers in, no HTTP: inputs map to `GET /api/usage` (`credits.total`, `used`,
 * `reserved`, `unlocked`; `weeklyUnlock.portions` become `ticks` as cumulative fractions of `total`).
 *
 * - solid fill: used; hatched: reserved by running work; dashed boundary and soft remainder: not yet unlocked
 *   (Free weekly portions); thin ticks: next unlock points. Meaning never rests on colour alone: forced colors and
 *   reduced motion need no special handling because nothing animates.
 * - out-of-range input is clamped: negatives become 0, used + reserved never exceeds the bar, `unlocked` stays within
 *   `total`, ticks outside 0..1 are dropped.
 */
@Component({
    selector: 'app-usage-meter',
    template: `
      <p class="label">{{ label() }}</p>
      <div class="bar" aria-hidden="true">
        @if (hasLocked()) { <span class="locked" [style.inset-inline-start.%]="lockedStart()"></span> }
        <span class="used" [style.inline-size.%]="usedPercent()"></span>
        @if (reservedPercent() > 0) {
          <span class="reserved" [style.inset-inline-start.%]="usedPercent()" [style.inline-size.%]="reservedPercent()"></span>
        }
        @for (tick of tickPositions(); track tick) { <span class="tick" [style.inset-inline-start.%]="tick"></span> }
      </div>
      <p class="summary">{{ summary() }}</p>
    `,
    styleUrl: './usage-meter.component.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class UsageMeterComponent {
    /** What is measured, e.g. «ИИ в октябре». */
    readonly label = input.required<string>();
    /** Size of the whole bar in the caller's unit (credits). */
    readonly total = input.required<number>();
    readonly used = input.required<number>();
    /** Held by running work; drawn hatched after the used part and counted in the percentage, like `percentUsed`. */
    readonly reserved = input(0);
    /** How much of `total` is unlocked so far; `null` means all of it. The rest is the locked remainder. */
    readonly unlocked = input<number | null>(null);
    /** Unlock points as fractions of the whole bar (0..1), e.g. the cumulative weekly portions. */
    readonly ticks = input<readonly number[]>([]);
    readonly remaining = input<UsageRemaining | null>(null);
    /** Already formatted date the next portion unlocks, e.g. «4 октября»; shown only while something is locked. */
    readonly nextUnlock = input<string | null>(null);
    /** Already formatted date the bar renews, e.g. «6 октября». */
    readonly resetsOn = input<string | null>(null);

    private readonly size = computed(() => Math.max(0, finiteOrZero(this.total())));
    private readonly usedAmount = computed(() => Math.min(this.size(), Math.max(0, finiteOrZero(this.used()))));
    private readonly reservedAmount = computed(() =>
        Math.min(this.size() - this.usedAmount(), Math.max(0, finiteOrZero(this.reserved()))));
    private readonly unlockedAmount = computed(() => {
        const value = this.unlocked();
        return value === null ? this.size() : Math.min(this.size(), Math.max(0, finiteOrZero(value)));
    });

    protected readonly usedPercent = computed(() => this.fraction(this.usedAmount()));
    protected readonly reservedPercent = computed(() => this.fraction(this.reservedAmount()));
    protected readonly hasLocked = computed(() => this.unlockedAmount() < this.size());
    protected readonly lockedStart = computed(() => this.fraction(this.unlockedAmount()));
    protected readonly tickPositions = computed(() => [...new Set(this.ticks()
        .filter(tick => Number.isFinite(tick) && tick > 0 && tick < 1)
        .map(tick => tick * 100))]);

    protected readonly summary = computed(() => {
        const size = this.size();
        const parts = [`Использовано ${this.percent(this.usedAmount() + this.reservedAmount())}${NBSP}%`];
        if (this.reservedAmount() > 0) parts.push(`из них ${this.percent(this.reservedAmount())}${NBSP}% зарезервировано`);
        const remaining = this.remaining();
        if (remaining !== null) {
            const count = Math.max(0, Math.round(finiteOrZero(remaining.count)));
            parts.push(`хватит на ≈${NBSP}${count}${NBSP}${ruPlural(count, remaining.forms)}`);
        }
        const locked = size - this.unlockedAmount();
        if (locked > 0) {
            const next = this.nextUnlock();
            parts.push(`ещё ${this.percent(locked)}${NBSP}% откроется${next ? ` ${next}` : ' позже'}`);
        }
        const resets = this.resetsOn();
        if (resets) parts.push(`обновится ${resets}`);
        return `${parts.join(', ')}.`;
    });

    private fraction(amount: number): number { return this.size() > 0 ? amount / this.size() * 100 : 0; }
    private percent(amount: number): number { return percentOf(amount, this.size()); }
}
