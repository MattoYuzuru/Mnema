import { DOCUMENT } from '@angular/common';
import { ChangeDetectionStrategy, Component, DestroyRef, computed, effect, inject, input, signal } from '@angular/core';

import { seededRandom } from './seeded-random';
import { SEARCH_IDEAS } from './search-ideas';

/** Pause before the first letter, per letter typed, per letter erased, how long a finished idea stays, and the gap before the next one. */
export const TYPEWRITER_TIMING = { startMs: 500, typeMs: 75, eraseMs: 35, holdMs: 1800, gapMs: 450 } as const;

/** WCAG 2.2.2: the hint moves by itself, so it plays one pass through at most this many ideas and then rests on the last one. */
export const TYPEWRITER_MAX_IDEAS = 3;

/** One order of ideas for the whole page load: every hint of the page starts from a different place than the last visit, without a random number in a template. */
const PAGE_SEED = `mnema-${Math.random().toString(36).slice(2)}`;

/**
 * The hint that types an idea («испанский с нуля») over an empty search field, erases it and takes the next. It is only
 * a drawing: the host is `aria-hidden` and `pointer-events: none`, it never writes into the input, takes no focus and
 * Tab does nothing special, so a screen reader never reads it and the field keeps its real accessible name from its label.
 * Put it inside a `position: relative` wrapper that holds the input.
 *
 * It stops and hides when the field has focus or a value (`suppressed`), and it stops while the browser tab is hidden
 * (`visibilitychange`). It never loops: after one pass through at most {@link TYPEWRITER_MAX_IDEAS} ideas it leaves the last one typed and still
 * (WCAG 2.2.2). With «reduce motion» (or `still`) it shows one static idea and starts no timer. Ideas come in a seeded order
 * (`seed`, one per page load by default). Every timer lives in one effect and is cleared when the run ends or the
 * component is destroyed; nothing here needs `NgZone`.
 */
@Component({
    selector: 'app-typewriter-hint',
    host: { 'aria-hidden': 'true', '(document:visibilitychange)': 'syncVisibility()' },
    template: `@if (visible()) { <span class="idea">{{ shown() }}</span> }`,
    styles: [`
      :host { position: absolute; inset: 0; display: flex; align-items: center; padding-inline: calc(.9rem + 1px); overflow: hidden; pointer-events: none; user-select: none; }
      .idea { min-inline-size: 0; overflow: hidden; color: var(--mn-muted); text-overflow: ellipsis; white-space: nowrap; }
      @media (forced-colors: active) { .idea { color: GrayText; } }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class TypewriterHintComponent {
    /** The field has focus or a value: the hint is gone and its timers stop. */
    readonly suppressed = input(false);
    readonly ideas = input<readonly string[]>(SEARCH_IDEAS);
    /** Any text; the same seed gives the same order. */
    readonly seed = input(PAGE_SEED);
    /** One static idea and no timer, as for «reduce motion», whatever the system says (the styleguide shows it so). */
    readonly still = input(false);

    private readonly document = inject(DOCUMENT);
    private readonly typed = signal('');
    private readonly systemReduced = signal(false);
    private readonly tabHidden = signal(false);
    private cursor = 0;
    /** Ideas typed and erased so far in the pass, and the list the pass belongs to. */
    private played = 0;
    private playedFor: readonly string[] | null = null;
    /** The list whose pass is over: its last idea rests on screen. */
    private readonly restedFor = signal<readonly string[] | null>(null);

    /** The ideas in the order of the seed. */
    private readonly order = computed(() => {
        const list = this.ideas();
        const random = seededRandom(this.seed());
        const order = list.map((_, index) => index);
        for (let i = order.length - 1; i > 0; i--) {
            const j = Math.floor(random() * (i + 1));
            [order[i], order[j]] = [order[j], order[i]];
        }
        return order.map(index => list[index]);
    });
    private readonly reduced = computed(() => this.still() || this.systemReduced());
    protected readonly visible = computed(() => !this.suppressed() && this.order().length > 0);
    protected readonly shown = computed(() => this.reduced() ? (this.order()[0] ?? '') : this.typed());
    private readonly running = computed(() => !this.suppressed() && !this.reduced() && !this.tabHidden() && this.order().length > 0 && this.restedFor() !== this.order());

    constructor() {
        this.syncVisibility();
        const media = this.document.defaultView?.matchMedia?.('(prefers-reduced-motion: reduce)');
        if (media) {
            this.systemReduced.set(media.matches);
            const change = (event: MediaQueryListEvent): void => this.systemReduced.set(event.matches);
            media.addEventListener?.('change', change);
            inject(DestroyRef).onDestroy(() => media.removeEventListener?.('change', change));
        }

        effect(onCleanup => {
            if (!this.running()) return;
            const order = this.order();
            if (this.playedFor !== order) { this.playedFor = order; this.played = 0; this.cursor = 0; }
            const limit = Math.min(TYPEWRITER_MAX_IDEAS, order.length);
            let timer: ReturnType<typeof setTimeout> | undefined;
            let length = 0;
            let erasing = false;
            this.typed.set('');
            const tick = (): void => {
                const idea = order[this.cursor % order.length];
                if (!erasing) {
                    length++;
                    this.typed.set(idea.slice(0, length));
                    if (length >= idea.length) {
                        if (this.played + 1 >= limit) { this.restedFor.set(order); return; }
                        erasing = true;
                        timer = setTimeout(tick, TYPEWRITER_TIMING.holdMs);
                    } else timer = setTimeout(tick, TYPEWRITER_TIMING.typeMs);
                    return;
                }
                length--;
                this.typed.set(idea.slice(0, length));
                if (length <= 0) { erasing = false; this.cursor++; this.played++; timer = setTimeout(tick, TYPEWRITER_TIMING.gapMs); }
                else timer = setTimeout(tick, TYPEWRITER_TIMING.eraseMs);
            };
            timer = setTimeout(tick, TYPEWRITER_TIMING.startMs);
            onCleanup(() => { if (timer !== undefined) clearTimeout(timer); });
        });
    }

    protected syncVisibility(): void {
        this.tabHidden.set(this.document.visibilityState === 'hidden');
    }
}
