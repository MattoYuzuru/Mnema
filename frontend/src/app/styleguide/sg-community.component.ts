import { ChangeDetectionStrategy, Component, DestroyRef, ViewEncapsulation, computed, inject, signal } from '@angular/core';

import { AuthorHeaderComponent } from '../shared/author-header.component';
import { BookSearchComponent, rearmBookOpening } from '../shared/book-search.component';
import { CatalogFilters, NO_FILTERS, toQueryParams } from '../shared/catalog-filters';
import { CatalogFiltersComponent } from '../shared/catalog-filters.component';
import { CommunityEmptyComponent } from '../shared/community-empty.component';
import { DeckGridComponent, DeckResults } from '../shared/deck-grid.component';
import { DeckShelfComponent, ShelfAction } from '../shared/deck-shelf.component';
import { ActionMenuItem } from '../shared/action-menu.component';
import { PublicDeckCard } from '../shared/public-deck-card';
import { PublicDeckCardComponent } from '../shared/public-deck-card.component';
import { SHARE_MENU_ITEM } from '../shared/share-link.service';
import { SgSpecimenComponent } from './sg-specimen.component';
import {
    COMMUNITY_CARDS, DEMO_AUTHOR, DEMO_AUTHOR_BARE, DEMO_LANGUAGES, DEMO_TOPICS, INVITATION_CARD, INVITATION_NOTE, demoCards
} from './sg-community-demo';
import { TypewriterHintComponent } from '../shared/typewriter-hint.component';

const FIRST_PAGE = 8;
const PAGE = 8;
const LAST = 40;

type SearchKind = 'found' | 'capped' | 'none';

/**
 * «Сообщество» (Community/4 to 6, фаза A): the elements of the catalogue page, the search and the author's page, on invented data and with
 * no server. Every specimen is the real component; the page that wires them to the API comes after this section.
 */
@Component({
    selector: 'app-sg-community',
    encapsulation: ViewEncapsulation.None,
    imports: [SgSpecimenComponent, BookSearchComponent, TypewriterHintComponent, CatalogFiltersComponent, DeckShelfComponent, DeckGridComponent,
        CommunityEmptyComponent, AuthorHeaderComponent, PublicDeckCardComponent],
    templateUrl: './sg-community.component.html',
    styleUrl: './sg-community.component.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class SgCommunityComponent {
    private readonly destroyRef = inject(DestroyRef);
    private timer: ReturnType<typeof setTimeout> | null = null;

    protected readonly topics = DEMO_TOPICS;
    protected readonly languages = DEMO_LANGUAGES;
    protected readonly shelfDecks = COMMUNITY_CARDS.slice(0, 4);
    protected readonly author = DEMO_AUTHOR;
    protected readonly authorBare = DEMO_AUTHOR_BARE;
    protected readonly invitationCard = INVITATION_CARD;
    protected readonly invitationNote = INVITATION_NOTE;
    protected readonly plainCard = COMMUNITY_CARDS[3];

    /** The book: the key re-creates it, and `rearmBookOpening` lets the opening play once more. */
    protected readonly bookKeys = signal([0]);
    protected readonly bookText = signal('');
    protected readonly searched = signal<string | null>(null);
    protected readonly headerText = signal('');
    protected readonly headerSearched = signal<string | null>(null);
    protected readonly typewriterText = signal('');
    protected readonly typewriterFocused = signal(false);

    protected replayBook(): void {
        rearmBookOpening();
        this.bookKeys.update(keys => [keys[0] + 1]);
    }

    /** The filters, with the address they would write. */
    protected readonly emptyFilters = signal<CatalogFilters>(NO_FILTERS);
    protected readonly activeFilters = signal<CatalogFilters>({
        topic: 'spanish', learn: 'es', level: 'B1', size: 'month', media: ['audio'], mechanics: ['listening', 'order'], fresh: true, sort: 'new'
    });
    protected readonly activeAddress = computed(() => this.address(this.activeFilters()));
    protected readonly emptyAddress = computed(() => this.address(this.emptyFilters()));

    private address(filters: CatalogFilters): string {
        const query = new URLSearchParams();
        for (const [key, value] of Object.entries(toQueryParams(filters))) for (const item of [value].flat()) query.append(key, item);
        const text = query.toString();
        return text === '' ? '/community' : `/community?${text}`;
    }

    protected readonly link = (deck: PublicDeckCard): string[] => ['/styleguide', deck.id];
    /** The rows of one card's «⋯» menu: «Пожаловаться» is everywhere but on one's own deck (here the first one). */
    protected readonly menuFor = (deck: PublicDeckCard): readonly ActionMenuItem[] =>
        deck.id === COMMUNITY_CARDS[0].id ? [SHARE_MENU_ITEM] : [SHARE_MENU_ITEM, { id: 'complain', label: 'Пожаловаться' }];

    /** The grid: a search gives a count, the first page and a cursor; the rest comes by itself while the specimen is scrolled. */
    protected readonly searchNumber = signal(1);
    protected readonly kind = signal<SearchKind>('found');
    protected readonly gridCount = signal(FIRST_PAGE);
    protected readonly gridLoading = signal(false);
    protected readonly gridError = signal<string | null>(null);
    protected readonly failNextPage = signal(false);
    protected readonly gridDecks = computed(() => this.kind() === 'none' ? [] : demoCards(this.searchNumber() * 100, this.searchNumber() * 100 + this.gridCount()));
    protected readonly gridCursor = computed(() => this.kind() === 'none' || this.gridCount() >= LAST ? null : `after-${this.gridCount()}`);
    protected readonly gridResults = computed<DeckResults | null>(() => {
        const kind = this.kind();
        return { key: this.searchNumber(), count: kind === 'none' ? 0 : kind === 'capped' ? 200 : 37, capped: kind === 'capped' };
    });

    protected search(kind: SearchKind): void {
        this.clearTimer();
        this.gridLoading.set(false);
        this.gridError.set(null);
        this.kind.set(kind);
        this.gridCount.set(FIRST_PAGE);
        this.searchNumber.update(number => number + 1);
    }

    protected loadGridPage(): void {
        if (this.gridLoading() || this.gridCursor() === null) return;
        this.gridError.set(null);
        this.gridLoading.set(true);
        this.timer = setTimeout(() => {
            if (this.failNextPage()) {
                this.gridError.set('Пример сетевого сбоя. Найденные колоды сохранились.');
                this.failNextPage.set(false);
            } else this.gridCount.update(count => Math.min(LAST, count + PAGE));
            this.gridLoading.set(false);
        }, 300);
    }

    /** What the shelf and the cards report. */
    protected readonly lastAction = signal('Выберите пункт меню «⋯» у карточки: здесь будет сказано, что произошло.');
    protected shelfAction(action: ShelfAction): void { this.lastAction.set(`Выбран пункт «${action.id}» у колоды «${action.deck.title}».`); }

    protected readonly complaint = signal('');

    constructor() { this.destroyRef.onDestroy(() => this.clearTimer()); }

    private clearTimer(): void {
        if (this.timer !== null) clearTimeout(this.timer);
        this.timer = null;
    }
}
