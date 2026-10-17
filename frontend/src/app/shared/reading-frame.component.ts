import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { RouterLink } from '@angular/router';

/** Боковые переходы остаются внутри высоты материала; на телефоне идут после текста. */
@Component({
    selector: 'app-reading-frame',
    imports: [RouterLink],
    template: `
      <div class="reading-content"><ng-content /></div>
      <div class="rail previous">
        @if (previous(); as link) {
          <a [routerLink]="link" aria-label="Предыдущий материал" title="Предыдущий материал" rel="prev">
            <svg viewBox="0 0 24 40" aria-hidden="true" focusable="false"><path d="M18 4.4 9 20 18 35.6" /></svg>
            <span>Предыдущий</span>
          </a>
        }
      </div>
      <div class="rail next">
        @if (next(); as link) {
          <a [routerLink]="link" aria-label="Следующий материал" title="Следующий материал" rel="next">
            <svg viewBox="0 0 24 40" aria-hidden="true" focusable="false"><path d="m6 4.4 9 15.6-9 15.6" /></svg>
            <span>Следующий</span>
          </a>
        }
      </div>
    `,
    styles: [`
      :host { display: grid; grid-template-columns: var(--mn-touch-min) minmax(0, 1fr) var(--mn-touch-min); gap: var(--mn-space-1);
        margin-inline: calc(-1 * (var(--mn-touch-min) + var(--mn-space-1))); }
      .reading-content { min-inline-size: 0; grid-column: 2; grid-row: 1; }
      .rail { min-inline-size: 0; grid-row: 1; }
      .previous { grid-column: 1; } .next { grid-column: 3; }
      a { position: sticky; inset-block-start: calc(50dvh - var(--mn-touch-min) / 2); display: grid; place-items: center;
        min-inline-size: var(--mn-touch-min); min-block-size: var(--mn-touch-min); color: var(--mn-muted); text-decoration: none; }
      a:hover, a:focus-visible { color: var(--mn-ink); }
      a:focus-visible { outline: 3px solid var(--mn-focus); outline-offset: 3px; }
      svg { inline-size: var(--mn-space-5); block-size: var(--mn-space-6); overflow: visible; }
      path { fill: none; stroke: currentColor; stroke-width: 2.5; stroke-linecap: round; stroke-linejoin: round; }
      span { display: none; }
      @media (max-width: 70rem) {
        :host { grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); margin-inline: 0; gap: var(--mn-space-5); }
        .reading-content { grid-column: 1 / -1; grid-row: 1; }
        .rail { grid-row: 2; }
        .next { grid-column: 2; }
        a { position: static; display: flex; gap: var(--mn-space-2); font-weight: 600; }
        .previous a { justify-content: start; } .next a { justify-content: end; flex-direction: row-reverse; }
        svg { inline-size: var(--mn-glyph-size); block-size: var(--mn-space-5); flex: none; }
        span { display: inline; overflow-wrap: anywhere; }
      }
      @media (forced-colors: active) { a { color: LinkText; } a:focus-visible { outline-color: Highlight; } }
      @media print { .rail { display: none; } :host { display: block; margin-inline: 0; } }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class ReadingFrameComponent {
    readonly previous = input<readonly string[] | null>(null);
    readonly next = input<readonly string[] | null>(null);
}
