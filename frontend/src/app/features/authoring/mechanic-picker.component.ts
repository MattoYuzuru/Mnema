import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';

import { MechanicCatalogEntry } from '../../content/exercise/mechanic-catalog';
import { Mechanic } from '../../content/exercise/exercise-content.models';

/** `deliberate` is false for arrow-key movement inside the group: it selects but must never scroll the page. */
export interface MechanicChoice { readonly mechanic: Mechanic; readonly deliberate: boolean; }

/**
 * Step 1: what the learner will do. Every tile is a native radio inside a large label, so the whole tile is
 * clickable and arrow keys, Space and focus come from the platform. The tiles come from the mechanic catalog.
 */
@Component({
    selector: 'app-mechanic-picker',
    template: `
      <fieldset class="picker">
        <legend>1. Тип упражнения</legend>
        <p class="hint">Выберите, что будет делать ученик. Появится пример, который можно сразу попробовать.</p>
        <div class="tiles">
          @for (entry of entries(); track entry.mechanic) {
            <label class="tile" [class.is-selected]="shown() === entry.mechanic" (pointerdown)="arrow = false">
              <input type="radio" name="mechanic" [value]="entry.mechanic" [checked]="shown() === entry.mechanic"
                     (keydown)="onKey($event)" (click)="onClick(entry.mechanic)" (change)="onChange(entry.mechanic)" />
              <span class="tile-title">{{ entry.title }}</span>
              <span class="tile-description">{{ entry.description }}</span>
            </label>
          }
        </div>
      </fieldset>
    `,
    styles: [`
      :host { display: block; min-inline-size: 0; }
      .picker { min-inline-size: 0; display: grid; gap: .9rem; margin: 0; border: 0; padding: 0; }
      legend { padding: 0; color: var(--mn-ink); font: 500 clamp(1.5rem, 4vw, 2rem)/1.15 var(--mn-font-display, Georgia, serif); }
      .hint { margin: 0; color: var(--mn-muted); line-height: 1.55; }
      .tiles { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 17rem), 1fr)); gap: .85rem; }
      .tile { position: relative; min-inline-size: 0; display: grid; align-content: start; gap: .45rem; border: 1px solid var(--mn-field-border);
        padding: 1rem 1rem 1.1rem 3rem; background: var(--mn-sheet); cursor: pointer; overflow-wrap: anywhere; }
      .tile:hover { border-color: var(--mn-ink); }
      .tile input { position: absolute; inset-block-start: 1.05rem; inset-inline-start: 1rem; margin: 0; }
      .tile.is-selected { border-color: var(--mn-ink); box-shadow: inset 0 0 0 1px var(--mn-ink); background: color-mix(in srgb, var(--mn-soft) 70%, var(--mn-sheet)); }
      .tile:has(input:focus-visible) { outline: 3px solid var(--mn-focus, var(--mn-ink)); outline-offset: 3px; }
      .tile-title { color: var(--mn-ink); font: 500 1.3rem/1.25 var(--mn-font-display, Georgia, serif); }
      .tile-description { color: var(--mn-muted); font-size: .95rem; line-height: 1.5; }
      @media (forced-colors: active) { .tile, .tile.is-selected { border-color: CanvasText; } .tile.is-selected { outline: 2px solid Highlight; } }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class MechanicPickerComponent {
    readonly entries = input.required<readonly MechanicCatalogEntry[]>();
    readonly selected = input<Mechanic | null>(null);
    /** The tile asked for while a confirmation is open; shown as chosen until the author decides. */
    readonly pending = input<Mechanic | null>(null);
    readonly chosen = output<MechanicChoice>();
    readonly shown = computed(() => this.pending() ?? this.selected());

    /** True while the last interaction was an arrow key; plain bookkeeping, not rendered. */
    arrow = false;

    onKey(event: KeyboardEvent): void { this.arrow = event.key.startsWith('Arrow'); }

    /** Activating the tile that is already chosen is a deliberate "show me the example again". */
    onClick(mechanic: Mechanic): void {
        if (mechanic === this.shown() && !this.arrow) this.chosen.emit({ mechanic, deliberate: true });
    }

    onChange(mechanic: Mechanic): void {
        this.chosen.emit({ mechanic, deliberate: !this.arrow });
        this.arrow = false;
    }
}
