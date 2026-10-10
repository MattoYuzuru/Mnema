import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/** Every drawing of {@link GlyphComponent}; the names are the whole public vocabulary. */
export const GLYPH_NAMES = [
    'book-search', 'book-open', 'share', 'copy-branch', 'lock', 'link', 'scroll-seal', 'colonnade', 'more', 'image', 'audio', 'video'
] as const;
export type GlyphName = typeof GLYPH_NAMES[number];

/**
 * Original line glyphs of the community catalogue, drawn in the engraved spirit of the brand and kept simple enough to
 * read at 20 px: a 24 × 24 grid, `currentColor`, a 1.5 stroke, round caps and joins. The mark is decorative and hidden
 * from assistive technology; the text next to it, or the accessible name of the button that holds it, carries the meaning.
 * The size follows `--mn-glyph-size` (20 px by default). The older single-purpose glyphs (`app-mail-glyph`,
 * `app-telegram-glyph`, `app-notification-glyph`) keep their own components.
 */
@Component({
    selector: 'app-glyph',
    host: { 'aria-hidden': 'true', '[attr.data-glyph]': 'name()' },
    template: `<svg viewBox="0 0 24 24" focusable="false" aria-hidden="true">
      @switch (name()) {
        @case ('book-search') {
          <path d="M4.5 18.5V5.5A2 2 0 0 1 6.5 3.5h7.5a1 1 0 0 1 1 1V9.5" />
          <path d="M4.5 18.5a2 2 0 0 0 2 2h4" />
          <path d="M8 3.5v17" />
          <circle cx="16" cy="15.5" r="3.5" />
          <path d="m18.6 18.1 2.4 2.4" />
        }
        @case ('book-open') {
          <path d="M12 6.5C10 5 7 4.5 3.5 5v13c3.5-.5 6.5 0 8.5 1.5 2-1.5 5-2 8.5-1.5V5C17 4.5 14 5 12 6.5Z" />
          <path d="M12 6.5v13" />
        }
        @case ('share') {
          <circle cx="18" cy="5.5" r="2.5" />
          <circle cx="6" cy="12" r="2.5" />
          <circle cx="18" cy="18.5" r="2.5" />
          <path d="m8.2 10.8 7.6-4.1M8.2 13.2l7.6 4.1" />
        }
        @case ('copy-branch') {
          <circle cx="6" cy="5.5" r="2" />
          <circle cx="6" cy="18.5" r="2" />
          <circle cx="18" cy="8.5" r="2" />
          <path d="M6 7.5v9M18 10.5v.5a3 3 0 0 1-3 3H9a3 3 0 0 0-3 3" />
        }
        @case ('lock') {
          <rect x="5" y="10.5" width="14" height="10" rx="1" />
          <path d="M8 10.5V8a4 4 0 0 1 8 0v2.5M12 14.5v2.5" />
        }
        @case ('link') {
          <g transform="rotate(-45 12 12)">
            <rect x="2" y="9" width="10.5" height="6" rx="3" />
            <rect x="11.5" y="9" width="10.5" height="6" rx="3" />
          </g>
        }
        @case ('scroll-seal') {
          <path d="M6 3.5h12v10.5M6 3.5v10.5M6 14h4.5" />
          <path d="M9 7.5h6M9 10.5h4" />
          <circle cx="15" cy="17" r="3" />
          <path d="m13.4 19.5-1 3 2.6-1 2.6 1-1-3" />
        }
        @case ('colonnade') {
          <path d="M3.5 9 12 4l8.5 5Z" />
          <path d="M6.5 11.5v6M10 11.5v6M14 11.5v6M17.5 11.5v6" />
          <path d="M5 17.5h14M3.5 20.5h17" />
        }
        @case ('more') {
          <circle cx="5" cy="12" r="1.6" fill="currentColor" stroke="none" />
          <circle cx="12" cy="12" r="1.6" fill="currentColor" stroke="none" />
          <circle cx="19" cy="12" r="1.6" fill="currentColor" stroke="none" />
        }
        @case ('image') {
          <rect x="3.5" y="5" width="17" height="14" rx="1" />
          <circle cx="9" cy="10" r="1.6" />
          <path d="m3.5 17 5-4.5 3.5 3 3-2.5 5 4" />
        }
        @case ('audio') {
          <path d="M4 9.5h3.5L12 5.5v13l-4.5-4H4Z" />
          <path d="M15.5 9.5a3.5 3.5 0 0 1 0 5M18 7a7 7 0 0 1 0 10" />
        }
        @case ('video') {
          <rect x="3.5" y="5" width="17" height="14" rx="1" />
          <path d="m10 9 5 3-5 3Z" />
        }
      }
    </svg>`,
    styles: [`
      :host { display: inline-flex; flex: none; inline-size: var(--mn-glyph-size, 1.25rem); block-size: var(--mn-glyph-size, 1.25rem); }
      svg { inline-size: 100%; block-size: 100%; fill: none; stroke: currentColor; stroke-width: 1.5; stroke-linecap: round; stroke-linejoin: round; }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class GlyphComponent {
    readonly name = input.required<GlyphName>();
}
