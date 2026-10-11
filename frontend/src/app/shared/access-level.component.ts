import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { GlyphComponent, GlyphName } from './glyph.component';
import type { DeckVisibility } from '../features/own-decks/own-deck.models';

/** The four access levels of a deck in the words of the product (docs/product/community-decks.md, «Доступ»). */
export const ACCESS_LEVEL_LABELS: Readonly<Record<DeckVisibility, string>> = {
    private: 'Приватная',
    invite: 'По приглашению',
    link: 'По ссылке',
    public: 'Публичная'
};

/**
 * The access level of a deck: a small line glyph (`app-glyph`) and the level in words. The glyph only repeats the word (a lock for
 * the closed levels, a chain for «По ссылке», a colonnade for «Публичная») and is hidden from assistive technology; the
 * text is the status. Used by «Мои колоды» and the Deck hub for decks that are not private.
 */
@Component({
    selector: 'app-access-level',
    imports: [GlyphComponent],
    template: `
      <app-glyph [name]="glyph()" />
      <span class="label">{{ label() }}</span>
    `,
    styles: [`
      :host { display: inline-flex; align-items: center; gap: .4rem; min-inline-size: 0; color: var(--mn-ink); font: 700 .75rem/1.4 var(--mn-font-mono); letter-spacing: .08em; text-transform: uppercase; }
      .label { min-inline-size: 0; overflow-wrap: anywhere; }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class AccessLevelComponent {
    readonly level = input.required<DeckVisibility>();

    protected readonly label = computed(() => ACCESS_LEVEL_LABELS[this.level()]);
    protected readonly glyph = computed<GlyphName>(() => this.level() === 'link' ? 'link' : this.level() === 'public' ? 'colonnade' : 'lock');
}
