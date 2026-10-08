import { ChangeDetectionStrategy, Component } from '@angular/core';

/** Original envelope glyph. The adjacent action text supplies its meaning. */
@Component({
    selector: 'app-mail-glyph',
    host: { 'aria-hidden': 'true' },
    template: `<svg viewBox="0 0 24 24" focusable="false" aria-hidden="true">
      <rect x="3" y="5.5" width="18" height="13" rx="1" />
      <path d="m3.5 7 8.5 6.5L20.5 7" />
    </svg>`,
    styles: [`
      :host { display: inline-flex; flex: none; inline-size: var(--mn-space-5); block-size: var(--mn-space-5); }
      svg { inline-size: 100%; block-size: 100%; fill: none; stroke: currentColor; stroke-width: 1.5; stroke-linecap: round; stroke-linejoin: round; }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class MailGlyphComponent {}
