import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, Injector, afterNextRender, computed, effect, inject, input, signal } from '@angular/core';
import { constellationFor } from './deck-constellation';

/** Measures the active page once per layout change; decoration never affects its dimensions. */
@Component({
    selector: 'app-deck-constellation',
    host: { 'aria-hidden': 'true' },
    template: `@if (rails(); as layout) { @for (side of sides; track side) {
      <div class="rail" [style.left.px]="side === 'left' ? layout.left : layout.right" [style.width.px]="layout.width">
        @for (star of stars(); track $index) { @if (star.side === side) {
          <span [style.left.%]="star.x" [style.top.%]="star.y"
            [style.transform]="'translate(-50%, -50%) rotate(' + star.rotation + 'deg) scale(' + star.scale + ')'">✧</span>
        } }
      </div>
    } }`,
    styles: [`
      :host { position: absolute; inset: 0; pointer-events: none; user-select: none; overflow: clip; }
      .rail { position: absolute; top: calc(5% + 2.25rem); bottom: calc(5% + 2.25rem); }
      span { position: absolute; inline-size: 3rem; block-size: 3rem; text-align: center;
        font: 3rem/1 Georgia, serif; color: var(--mn-ink); opacity: .18; }
      @media (forced-colors: active) { :host { display: none; } }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class DeckConstellationComponent {
    readonly seed = input.required<string>();
    readonly layoutKey = input('');
    readonly sides = ['left', 'right'] as const;
    readonly stars = computed(() => constellationFor(this.seed()));
    readonly rails = signal<{ left: number; right: number; width: number } | null>(null);
    private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
    private readonly destroyRef = inject(DestroyRef);
    private readonly injector = inject(Injector);
    private observer: ResizeObserver | null = null;

    constructor() {
        effect(() => {
            this.layoutKey();
            afterNextRender(() => this.observePage(), { injector: this.injector });
        });
        this.destroyRef.onDestroy(() => this.observer?.disconnect());
    }

    private observePage(): void {
        if (this.destroyRef.destroyed) return;
        const main = this.host.nativeElement.parentElement;
        const page = main?.lastElementChild?.firstElementChild;
        if (!(main instanceof HTMLElement) || !(page instanceof HTMLElement)) { this.rails.set(null); return; }
        this.observer?.disconnect();
        const measure = () => {
            const bounds = main.getBoundingClientRect();
            const content = page.getBoundingClientRect();
            const padding = getComputedStyle(page);
            const rootRem = parseFloat(getComputedStyle(document.documentElement).fontSize);
            const halfStar = 2.25 * rootRem;
            const edge = bounds.width * .05;
            const leftEdge = content.left - bounds.left + parseFloat(padding.paddingLeft);
            const rightEdge = bounds.right - content.right + parseFloat(padding.paddingRight);
            const width = Math.min(leftEdge, rightEdge) - edge * 2 - halfStar * 2;
            // A thin rail cannot look scattered; omit decoration rather than crowding content.
            this.rails.set(width >= 3 * rootRem && bounds.height * .9 - halfStar * 2 >= 38 * rootRem
                ? { left: edge + halfStar, right: bounds.width - edge - halfStar - width, width } : null);
        };
        this.observer = new ResizeObserver(measure);
        this.observer.observe(main);
        this.observer.observe(page);
        measure();
    }
}
