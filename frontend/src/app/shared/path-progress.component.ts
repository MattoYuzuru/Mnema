import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

/**
 * A thin progress bar of «Путь»: `role="progressbar"` with an accessible name, `aria-valuenow` and `aria-valuemax`, and a text value
 * (`valueText`, «2 из 3») that the page shows next to it, so the progress is never only a length or a colour. A bar with a name and a
 * text is what a screen reader reads; the fill is ink, in forced colours `CanvasText`.
 */
@Component({
    selector: 'app-path-progress',
    template: `
      <div class="track" role="progressbar" [attr.aria-label]="label()" aria-valuemin="0" [attr.aria-valuemax]="max()" [attr.aria-valuenow]="value()"
        [attr.aria-valuetext]="valueText()"><div class="fill" [style.inline-size.%]="percent()"></div></div>
    `,
    styles: [`
      :host { display: block; min-inline-size: 0; }
      .track { block-size: .375rem; border: 1px solid var(--mn-rule); background: var(--mn-soft); }
      .fill { block-size: 100%; background: var(--mn-ink); transition: inline-size .4s ease; }
      @media (prefers-reduced-motion: reduce) { .fill { transition: none; } }
      @media (forced-colors: active) { .track { border-color: CanvasText; } .fill { background: CanvasText; } }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class PathProgressComponent {
    /** The accessible name: what is being counted. */
    readonly label = input.required<string>();
    readonly value = input.required<number>();
    readonly max = input.required<number>();
    /** The value as text («2 из 3»), read instead of the bare number. */
    readonly valueText = input.required<string>();
    protected readonly percent = computed(() => this.max() > 0 ? Math.min(100, Math.max(0, this.value() * 100 / this.max())) : 0);
}
