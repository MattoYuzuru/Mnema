import { ChangeDetectionStrategy, Component } from '@angular/core';

/** Original paper-plane glyph. The adjacent link text supplies its meaning. */
@Component({
    selector: 'app-telegram-glyph',
    host: { 'aria-hidden': 'true' },
    template: `<svg viewBox="0 0 24 24" focusable="false" aria-hidden="true">
      <path d="m21 3-18 7.5 6.6 2.2L12 20l9-17Z" />
      <path d="m9.6 12.7 6.8-5.3" />
    </svg>`,
    styles: [`
      :host { display: inline-flex; flex: none; inline-size: var(--mn-space-5); block-size: var(--mn-space-5); }
      svg { inline-size: 100%; block-size: 100%; fill: none; stroke: currentColor; stroke-width: 1.5; stroke-linecap: round; stroke-linejoin: round; }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class TelegramGlyphComponent {}
