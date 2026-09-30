import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { constellationFor } from './deck-constellation';

@Component({
    selector: 'app-deck-constellation',
    host: { 'aria-hidden': 'true' },
    template: `@for (side of sides; track side) {
      <div class="rail" [class.right]="side === 'right'">
        @for (star of stars(); track $index) { @if (star.side === side) {
          <span [style.left.%]="star.x" [style.top.%]="star.y"
            [style.transform]="'translate(-50%, -50%) rotate(' + star.rotation + 'deg) scale(' + star.scale + ')'">✧</span>
        } }
      </div>
    }`,
    styles: [`
      :host { position: absolute; inset: 0; pointer-events: none; user-select: none; overflow: clip; }
      .rail { position: absolute; top: 5rem; height: 38rem; max-height: calc(100% - 10rem);
        left: calc(5vw + 1.75rem); width: max(0px, calc((100% - var(--mn-decoration-content-width)) / 2 - 10vw - 3.5rem)); }
      .rail.right { left: auto; right: calc(5vw + 1.75rem); }
      span { position: absolute; font: 3rem/1 Georgia, serif; color: var(--mn-ink); opacity: .18; }
      @media (max-width: 93.75rem), (forced-colors: active) { :host { display: none; } }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class DeckConstellationComponent {
    readonly seed = input.required<string>();
    readonly sides = ['left', 'right'] as const;
    readonly stars = computed(() => constellationFor(this.seed()));
}
