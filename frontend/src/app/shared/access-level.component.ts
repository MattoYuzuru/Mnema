import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import type { DeckVisibility } from '../features/own-decks/own-deck.models';

/** The four access levels of a deck in the words of the product (docs/product/community-decks.md, «Доступ»). */
export const ACCESS_LEVEL_LABELS: Readonly<Record<DeckVisibility, string>> = {
    private: 'Приватная',
    invite: 'По приглашению',
    link: 'По ссылке',
    public: 'Публичная'
};

/**
 * The access level of a deck: a small line glyph and the level in words. The glyph only repeats the word (a lock for
 * the closed levels, a chain for «По ссылке», a colonnade for «Публичная») and is hidden from assistive technology; the
 * text is the status. Used by «Мои колоды» and the Deck hub for decks that are not private.
 */
@Component({
    selector: 'app-access-level',
    template: `
      <svg viewBox="0 0 24 24" focusable="false" aria-hidden="true">
        @switch (glyph()) {
          @case ('chain') {
            <g transform="rotate(-45 12 12)">
              <rect x="2" y="9" width="10.5" height="6" rx="3" />
              <rect x="11.5" y="9" width="10.5" height="6" rx="3" />
            </g>
          }
          @case ('colonnade') {
            <path d="M3.5 9 12 4l8.5 5Z" />
            <path d="M6.5 11.5v6M10 11.5v6M14 11.5v6M17.5 11.5v6" />
            <path d="M5 17.5h14M3.5 20.5h17" />
          }
          @default {
            <rect x="5" y="10.5" width="14" height="10" rx="1" />
            <path d="M8 10.5V8a4 4 0 0 1 8 0v2.5M12 14.5v2.5" />
          }
        }
      </svg>
      <span class="label">{{ label() }}</span>
    `,
    styles: [`
      :host { display: inline-flex; align-items: center; gap: .4rem; min-inline-size: 0; color: var(--mn-ink); font: 700 .75rem/1.4 var(--mn-font-mono); letter-spacing: .08em; text-transform: uppercase; }
      svg { flex: none; inline-size: 1.1rem; block-size: 1.1rem; fill: none; stroke: currentColor; stroke-width: 1.5; stroke-linecap: round; stroke-linejoin: round; }
      .label { min-inline-size: 0; overflow-wrap: anywhere; }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class AccessLevelComponent {
    readonly level = input.required<DeckVisibility>();

    protected readonly label = computed(() => ACCESS_LEVEL_LABELS[this.level()]);
    protected readonly glyph = computed(() => this.level() === 'link' ? 'chain' : this.level() === 'public' ? 'colonnade' : 'lock');
}
