import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { Params, RouterLink } from '@angular/router';

import { ActionMenuItem } from './action-menu.component';
import { PublicDeckCard } from './public-deck-card';
import { PublicDeckCardComponent } from './public-deck-card.component';
import { SHARE_MENU_ITEM } from './share-link.service';

/** The deck whose «⋯» menu row was chosen, other than «Поделиться» (the card handles that one). */
export interface ShelfAction { readonly id: string; readonly deck: PublicDeckCard }

/**
 * A shelf of the community page: a `section` (deliberately without a name, so a page of many shelves is not a page of many region landmarks; the `h2` is the heading to navigate by) with an `h2`, an optional line under it, an optional «Все» link and a list of deck cards (`h3` titles).
 * On a wide container the cards are a grid; on a narrow one they are a row that scrolls sideways and snaps to the cards,
 * the next card peeks out from the edge to show there is more (no fade over the last card, nothing hides text). The list is
 * not a tab stop: the links and buttons inside the cards are, so the keyboard reaches every card, and a focused card scrolls
 * into view by itself.
 *
 * The page decides whether a shelf is drawn at all: a shelf with fewer than 4 decks is hidden, and it passes at most one row of
 * cards (a wide row holds 4 to 6) with the rest behind «Все».
 */
@Component({
    selector: 'app-deck-shelf',
    imports: [RouterLink, PublicDeckCardComponent],
    template: `
      <section class="shelf">
        <header class="head">
          <div class="titles">
            <h2 class="title">{{ title() }}</h2>
            @if (subtitle(); as text) { <p class="hint">{{ text }}</p> }
          </div>
          @if (moreLink(); as to) {
            <a class="button quiet more" [routerLink]="to" [queryParams]="moreQueryParams()" [attr.aria-label]="'Все колоды: ' + title()">Все</a>
          }
        </header>
        <ul class="row" role="list">
          @for (deck of decks(); track deck.id) {
            <li><app-public-deck-card [deck]="deck" [headingLevel]="3" [link]="deckLink()(deck)" [menuItems]="menuFor()?.(deck) ?? menuItems()" (action)="action.emit({ id: $event, deck })" /></li>
          }
        </ul>
      </section>
    `,
    styleUrl: './deck-shelf.component.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class DeckShelfComponent {
    readonly title = input.required<string>();
    readonly subtitle = input<string | null>(null);
    /** Where «Все» leads (a router link); without it there is no link. */
    readonly moreLink = input<string | readonly string[] | null>(null);
    /** The query of «Все» (the shelf's own browse query), when the link leads to a page that keeps its state in the address. */
    readonly moreQueryParams = input<Params | null>(null);
    readonly decks = input.required<readonly PublicDeckCard[]>();
    /** The route of a deck's page; without one the titles are plain text. */
    readonly deckLink = input<(deck: PublicDeckCard) => string | readonly string[] | null>(() => null);
    readonly menuItems = input<readonly ActionMenuItem[]>([SHARE_MENU_ITEM]);
    /** The rows of one deck's «⋯» menu where they differ from deck to deck (no «Пожаловаться» on one's own deck); wins over `menuItems`. */
    readonly menuFor = input<((deck: PublicDeckCard) => readonly ActionMenuItem[]) | null>(null);
    readonly action = output<ShelfAction>();

}
