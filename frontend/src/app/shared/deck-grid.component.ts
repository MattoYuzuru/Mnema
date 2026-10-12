import { ChangeDetectionStrategy, Component, afterNextRender, computed, input, output, signal } from '@angular/core';

import { plural } from '../core/notifications/notification-presenter';
import { ActionMenuItem } from './action-menu.component';
import { AutoLoadComponent } from './auto-load.component';
import { PublicDeckCard } from './public-deck-card';
import { PublicDeckCardComponent } from './public-deck-card.component';
import { SHARE_MENU_ITEM } from './share-link.service';

/** The delay between two cards of a new batch, and how many cards of a batch are delayed at all (the rest arrive with the last of them). */
export const CASCADE = { stepMs: 40, cards: 8 } as const;

/**
 * What a search found, as the grid announces it. `key` names the search: a new key is a new announcement, even with the same
 * numbers, and the pages that are appended to the same search keep the key, so they stay silent. `capped` says the server
 * counted only up to `count`.
 */
export interface DeckResults { readonly key: string | number; readonly count: number; readonly capped: boolean }

/** «Нашли 37 колод», «Нашли больше 200 колод» or the sentence for no result. */
export function resultsText(results: DeckResults): string {
    if (results.count <= 0) return 'Ничего не нашли. Попробуйте другое слово или уберите фильтры.';
    if (results.capped) return `Нашли больше ${results.count} ${plural(results.count, ['колоды', 'колод', 'колод'])}`;
    return `Нашли ${results.count} ${plural(results.count, ['колоду', 'колоды', 'колод'])}`;
}

interface GridRow { readonly deck: PublicDeckCard; readonly delay: number }

/**
 * The result grid of the community page: a list of deck cards that continues through the shared `app-auto-load` (no page and no
 * «Показать ещё» buttons; the owner of the data fetches the pages and appends them by stable id). A card that is new to the grid
 * arrives with the ink reveal `mn-ink-in`, and the cards of one arriving batch follow each other by 40 ms, the first eight of them; a card that
 * is already there is not touched when a page is appended. With reduced motion nothing animates and there is no cascade.
 *
 * The count of a search is announced politely, once per `results.key` (a `role=status` region that stays rendered, empty until the first text, which is set after the region is in the DOM so a grid created with results already in hand still announces; the text is also shown as a line above the grid): pass `results` as soon as
 * the first page of a search is in, and `null` while it is loading or when the reader is just browsing.
 */
@Component({
    selector: 'app-deck-grid',
    imports: [AutoLoadComponent, PublicDeckCardComponent],
    template: `
      <div class="status" role="status">
        @for (message of messages(); track message.key) { <p class="count hint">{{ message.text }}</p> }
      </div>
      <ul #list class="grid" role="list">
        @for (row of rows(); track row.deck.id) {
          <li class="cell" [style.--mn-cascade-delay]="row.delay + 'ms'">
            <app-public-deck-card [deck]="row.deck" [headingLevel]="headingLevel()" [link]="deckLink()(row.deck)" [menuItems]="menuFor()?.(row.deck) ?? menuItems()"
              (action)="action.emit({ id: $event, deck: row.deck })" />
          </li>
        }
      </ul>
      <app-auto-load [content]="list" [context]="context()" [continuation]="continuation()" [loading]="loading()" [error]="error()"
        [enabled]="enabled()" [root]="root()" [loadingText]="loadingText()" (loadNext)="loadNext.emit()" />
    `,
    styleUrl: './deck-grid.component.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class DeckGridComponent {
    readonly decks = input.required<readonly PublicDeckCard[]>();
    readonly results = input<DeckResults | null>(null);
    readonly headingLevel = input<2 | 3 | 4>(3);
    readonly deckLink = input<(deck: PublicDeckCard) => string | readonly string[] | null>(() => null);
    readonly menuItems = input<readonly ActionMenuItem[]>([SHARE_MENU_ITEM]);
    /** The rows of one deck's «⋯» menu where they differ from deck to deck (no «Пожаловаться» on one's own deck); wins over `menuItems`. */
    readonly menuFor = input<((deck: PublicDeckCard) => readonly ActionMenuItem[]) | null>(null);
    /** The next cursor, `null` at the end. Passed to `app-auto-load` together with the next five inputs. */
    readonly continuation = input<string | null>(null);
    readonly loading = input(false);
    readonly error = input<string | null>(null);
    readonly enabled = input(true);
    readonly context = input<string | number>(0);
    readonly root = input<HTMLElement | null>(null);
    readonly loadingText = input('Загружаем следующие колоды…');
    readonly loadNext = output<void>();
    /** The «⋯» row that was chosen, other than «Поделиться», with its deck. */
    readonly action = output<{ readonly id: string; readonly deck: PublicDeckCard }>();

    /** The region is in the DOM, empty, before the first text goes into it: a region that appears with its text is not announced reliably. */
    private readonly regionReady = signal(false);
    protected readonly messages = computed(() => {
        const results = this.results();
        return results === null || !this.regionReady() ? [] : [{ key: results.key, text: resultsText(results) }];
    });

    constructor() { afterNextRender(() => this.regionReady.set(true)); }

    private shown = new Set<string>();
    /** Every deck with its place in the cascade: only the cards that are new to the grid count, in order. */
    protected readonly rows = computed<readonly GridRow[]>(() => {
        const known = this.shown;
        let arriving = 0;
        const rows = this.decks().map(deck => ({
            deck, delay: known.has(deck.id) ? 0 : Math.min(arriving++, CASCADE.cards - 1) * CASCADE.stepMs
        }));
        this.shown = new Set(rows.map(row => row.deck.id));
        return rows;
    });
}
