import { DOCUMENT } from '@angular/common';
import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, Injector, afterNextRender, computed, effect, inject, linkedSignal, signal, untracked } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { Title } from '@angular/platform-browser';
import { ActivatedRoute, Params, Router, RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';

import { learnerOrigin } from '../../app.config';
import { AuthService } from '../../auth.service';
import { safeReturnUrl } from '../../auth-protocol';
import { AuthorCardsService } from '../../author-cards.service';
import { AUTHOR_CARD_BATCH_MAX } from '../../public-profile';
import { ActionMenuItem } from '../../shared/action-menu.component';
import { BookSearchComponent } from '../../shared/book-search.component';
import { CatalogFilters, DEFAULT_SORT, activeCount, sameFilters } from '../../shared/catalog-filters';
import { CatalogFiltersComponent } from '../../shared/catalog-filters.component';
import { CommunityEmptyComponent } from '../../shared/community-empty.component';
import { ComplaintDialogComponent } from '../../shared/complaint-dialog.component';
import { DeckGridComponent, DeckResults } from '../../shared/deck-grid.component';
import { DeckShelfComponent, ShelfAction } from '../../shared/deck-shelf.component';
import { PublicDeckAuthor, PublicDeckCard } from '../../shared/public-deck-card';
import { SHARE_MENU_ITEM } from '../../shared/share-link.service';
import { PublicDeckFailure, publicFailureOf } from '../public-deck/public-deck.models';
import { SHELL_TITLE } from '../public-deck/public-page-meta';
import { waitText } from '../public-deck/public-deck.text';
import { CommunityApiService, CommunityQuery } from './community-api.service';
import { CommunityPager } from './community-pager';
import { CommunityCard, CommunityFacets, CommunityShelves } from './community.models';
import {
    SHELF_MIN_DECKS, addressOf, addressParams, displayCard, isShelvesView, languageOptions, moreParams, requestKey, shelfKey, shelfLimit, shelfTitle, topicOptions
} from './community-view';

/** How long the sorting waits for the next arrow key before the address (and the request) follows. */
export const SORT_DEBOUNCE_MS = 300;

const COMPLAINT_MENU_ITEM: ActionMenuItem = { id: 'complain', label: 'Пожаловаться' };
/** Share for everybody; «Пожаловаться» for everybody but the owner of the deck (the server refuses a complaint about one's own deck). */
const OWN_MENU: readonly ActionMenuItem[] = [SHARE_MENU_ITEM];
const READER_MENU: readonly ActionMenuItem[] = [SHARE_MENU_ITEM, COMPLAINT_MENU_ITEM];
const NO_AUTHOR: PublicDeckAuthor = { username: null, avatarSrc: null };
const COMMUNITY_LINK: readonly string[] = ['/community'];

type Phase = 'idle' | 'loading' | 'ready' | 'error';
type Status = 'loading' | 'ready' | 'closed' | 'rate-limited' | 'busy' | 'error';

interface ShelfView {
    readonly key: string;
    readonly title: string;
    readonly decks: readonly PublicDeckCard[];
    readonly more: Params | null;
}

/**
 * `/community`: the catalogue of public decks, for guests and any account. The address is the state: `q` is the text of the search and the
 * other parameters are the filters (`shared/catalog-filters.ts`), so a search can be shared and survives a reload. With nothing in the address
 * the page is the shelves («Выбор Мнемы», «Учат сейчас», «Новое», the topics); with a search, a filter or a sorting it is the result grid
 * with the shared auto-load (no page buttons). Only the latest request renders: a new address abandons the one on its way.
 *
 * A filter changes the address with `replaceUrl`; the sorting waits {@link SORT_DEBOUNCE_MS} so the arrow keys over its radios do not fire a request
 * each. A search from the book is a new history entry. Failures are notices with «Повторить»; a catalogue that is switched off answers 404 and the page
 * says so calmly. An empty catalogue says «Пока здесь пусто.» and shows no filters and no shelves (the book stays). Authors come from Identity in
 * batches and never hold the cards back.
 */
@Component({
    selector: 'app-community-page',
    imports: [RouterLink, BookSearchComponent, CatalogFiltersComponent, CommunityEmptyComponent, ComplaintDialogComponent, DeckGridComponent, DeckShelfComponent],
    templateUrl: './community-page.component.html',
    styleUrl: './community-page.component.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class CommunityPageComponent {
    private readonly route = inject(ActivatedRoute);
    private readonly router = inject(Router);
    private readonly api = inject(CommunityApiService);
    private readonly auth = inject(AuthService);
    private readonly authors = inject(AuthorCardsService);
    private readonly titles = inject(Title);
    private readonly document = inject(DOCUMENT);
    private readonly destroyRef = inject(DestroyRef);
    private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
    private readonly injector = inject(Injector);

    protected readonly moreLink = COMMUNITY_LINK;
    protected readonly signedIn = computed(() => this.auth.status() === 'authenticated');

    private readonly queries = toSignal(this.route.queryParamMap, { initialValue: this.route.snapshot.queryParamMap });
    protected readonly address = computed(() => addressOf(this.queries()));
    /** Changes exactly when the request to the server changes. */
    private readonly key = computed(() => requestKey(this.address()));
    protected readonly shelvesView = computed(() => isShelvesView(this.address()));
    /** The text of the search, as a primitive: what depends on it changes only when the text itself does. */
    private readonly q = computed(() => this.address().q);
    protected readonly searching = computed(() => this.q() !== '');
    /** The filters as the control shows them; the address follows (the sorting after a short wait). */
    protected readonly draft = linkedSignal<CatalogFilters>(() => {
        const { q, filters } = this.address();
        return q === '' ? filters : { ...filters, sort: DEFAULT_SORT };
    });
    /** The text in the book: the address's text, and typed-but-unsubmitted words survive a change of the filters. */
    protected readonly bookText = linkedSignal(() => this.q());

    protected readonly facets = signal<CommunityFacets | null>(null);
    protected readonly topics = computed(() => topicOptions(this.facets()));
    protected readonly languages = computed(() => languageOptions(this.facets()));
    protected readonly shelves = signal<CommunityShelves | null>(null);
    private readonly shelvesPhase = signal<Phase>('idle');
    private readonly shelvesFailure = signal<PublicDeckFailure | null>(null);
    /** The catalogue is off (the facets said 404). */
    private readonly closed = signal(false);
    /** The shelves view found no shelf to draw although decks are listed: the page browses instead. */
    private readonly browsing = signal(false);
    private readonly booting = signal(true);

    protected readonly pager = new CommunityPager(cursor => this.api.decks(this.query, cursor), this.destroyRef);
    protected readonly complaint = signal<{ readonly code: string; readonly title: string } | null>(null);

    /** Shelves are the page; with no shelf to draw the page browses. */
    protected readonly showShelves = computed(() => this.shelvesView() && !this.browsing());

    /** The decks listed, as the last answer of the server knows it; zero is the empty catalogue. */
    protected readonly listedCount = computed(() => {
        const shelves = this.shelves()?.listedCount;
        const facets = this.facets()?.listedCount;
        return (this.showShelves() ? shelves ?? facets : facets ?? shelves) ?? null;
    });
    protected readonly catalogueEmpty = computed(() => this.listedCount() === 0);

    private readonly failure = computed(() => this.showShelves() ? this.shelvesFailure() : this.pager.failure());
    private readonly phase = computed<Phase>(() => {
        if (this.booting()) return 'loading';
        const phase = this.showShelves() ? this.shelvesPhase() : this.pager.phase();
        return phase === 'idle' ? 'loading' : phase;
    });
    protected readonly status = computed<Status>(() => {
        const failure = this.failure();
        if (this.closed() || (this.phase() === 'error' && failure?.kind === 'not-found')) return 'closed';
        if (this.phase() === 'error') return failure?.kind === 'rate-limited' ? 'rate-limited' : failure?.kind === 'busy' ? 'busy' : 'error';
        return this.phase() === 'ready' ? 'ready' : 'loading';
    });
    protected readonly waiting = computed(() => `Слишком много запросов. ${waitText(this.failure()?.retryAfter ?? null)}`);

    protected readonly filtersShown = computed(() => this.status() !== 'closed' && !this.catalogueEmpty());

    private readonly authorsById = signal<ReadonlyMap<string, PublicDeckAuthor>>(new Map());
    private readonly asked = new Set<string>();
    private readonly origin = learnerOrigin(this.document.location.hostname, this.document.location.origin);

    /** The shelves that are drawn, with the cards that are drawn on them: a row where «Все» holds the rest, all of them where there is no «Все». */
    private readonly visibleShelves = computed(() => this.showShelves() && this.status() === 'ready'
        ? (this.shelves()?.shelves ?? []).filter(shelf => shelf.decks.length >= SHELF_MIN_DECKS).map(shelf => ({ shelf, cards: shelf.decks.slice(0, shelfLimit(shelf)) }))
        : []);
    protected readonly shelfViews = computed<readonly ShelfView[]>(() => this.visibleShelves().map(({ shelf, cards }) => ({
        key: shelfKey(shelf), title: shelfTitle(shelf), decks: cards.map(card => this.display(card)), more: moreParams(shelf.more)
    })));
    protected readonly rowCards = computed(() => this.pager.rows().map(card => this.display(card)));
    protected readonly results = computed<DeckResults | null>(() => {
        if (this.pager.phase() !== 'ready') return null;
        const { q, filters } = this.address();
        // Just browsing says nothing, unless there is nothing to browse.
        return q !== '' || activeCount(filters) > 0 || this.pager.total() === 0 ? { key: this.key(), count: this.pager.total(), capped: this.pager.capped() } : null;
    });
    protected readonly moreError = computed(() => this.pager.moreFailed() ? 'Не удалось загрузить следующие колоды. Проверьте соединение и повторите.' : null);

    /** The cards that are on the page now: those of the shelves that are drawn, or the rows of the result list. */
    private readonly displayed = computed<readonly CommunityCard[]>(() => this.showShelves() ? this.visibleShelves().flatMap(({ cards }) => cards) : this.pager.rows());
    /** Every card on the page, by code: the address of a deck, and whose it is. */
    private readonly cardsByCode = computed(() => new Map<string, CommunityCard>(this.displayed().map(card => [card.code, card])));
    private readonly me = computed(() => this.auth.user()?.accountId.toLowerCase() ?? null);

    protected readonly deckLink = (deck: PublicDeckCard): readonly string[] | null => {
        const card = this.cardsByCode().get(deck.id);
        return card === undefined ? null : ['/d', card.code, card.slug];
    };
    protected readonly menuFor = (deck: PublicDeckCard): readonly ActionMenuItem[] => {
        const owner = this.cardsByCode().get(deck.id)?.ownerId;
        return owner !== undefined && owner === this.me() ? OWN_MENU : READER_MENU;
    };

    private query: CommunityQuery = { q: '', filters: this.draft() };
    private sequence = 0;
    private shelvesSubscription: Subscription | null = null;
    private facetsSubscription: Subscription | null = null;
    private sortTimer: ReturnType<typeof setTimeout> | null = null;
    /** False until the first address has been read: the page that has just been opened is the router's to focus, not ours. */
    private addressChanged = false;

    constructor() {
        this.titles.setTitle(`Сообщество — ${SHELL_TITLE}`);
        this.destroyRef.onDestroy(() => {
            this.titles.setTitle(SHELL_TITLE);
            this.shelvesSubscription?.unsubscribe();
            this.facetsSubscription?.unsubscribe();
            this.clearSortTimer();
        });
        effect(() => {
            this.key();
            untracked(() => {
                // The address moved by itself (back, forward, a link): a sorting that was still waiting belongs to the address that is gone.
                this.clearSortTimer();
                void this.load();
                // The shelves give way to the list when «Все» is pressed, and the link that had the focus goes with them: the focus is on the body then, and the heading takes it.
                // A control the reader is using (the book, a filter, a chip) keeps it.
                if (this.addressChanged) afterNextRender(() => this.keepFocus(), { injector: this.injector });
                this.addressChanged = true;
            });
        });
        // Authors of the cards that are on the page, in chunks; a hidden author is a card without a name, and never holds a card back.
        effect(() => {
            const owners = this.displayed().map(card => card.ownerId);
            untracked(() => this.askAuthors(owners));
        });
        // A link from a stale or shared catalogue may name a topic or a language the facets no longer have: it is dropped from the address.
        effect(() => {
            const facets = this.facets();
            const { q, filters } = this.address();
            if (facets === null) return;
            const topic = filters.topic !== null && !facets.topics.some(entry => entry.id === filters.topic) ? null : filters.topic;
            const learn = filters.learn !== null && !facets.languages.some(entry => entry.code === filters.learn) ? null : filters.learn;
            if (topic === filters.topic && learn === filters.learn) return;
            untracked(() => void this.router.navigate([], { relativeTo: this.route, queryParams: addressParams(q, { ...filters, topic, learn }), replaceUrl: true }));
        });
    }

    protected retry(): void {
        this.closed.set(false);
        void this.load();
        // The notice with the pressed button is replaced by the loading page: the focus goes to the heading, not to the body.
        this.focusHeading();
    }

    /** A search from the book: a new entry in the history. The text is the address's; the filters stay. */
    protected search(text: string): void {
        this.clearSortTimer();
        void this.router.navigate([], { relativeTo: this.route, queryParams: addressParams(text, this.draft()) });
    }

    protected filtersChanged(next: CatalogFilters): void {
        const previous = this.draft();
        this.draft.set(next);
        this.clearSortTimer();
        // Only the sorting moved (arrow keys over the radios): wait for the last key before the address follows.
        if (next.sort !== previous.sort && sameFilters({ ...next, sort: previous.sort }, previous)) {
            this.sortTimer = setTimeout(() => this.applyFilters(), SORT_DEBOUNCE_MS);
        } else {
            this.applyFilters();
        }
    }

    protected actionChosen(event: ShelfAction): void {
        if (event.id !== COMPLAINT_MENU_ITEM.id) return;
        // A guest signs in first and comes back to this very search; a complaint always has a signed-in reporter.
        if (this.signedIn()) this.complaint.set({ code: event.deck.id, title: event.deck.title });
        else void this.router.navigate(['/login'], { queryParams: { returnUrl: safeReturnUrl(this.router.url) } });
    }

    /** The complaint dialog found the deck gone for this reader (404): the list is read again. */
    protected complaintGone(): void {
        this.complaint.set(null);
        void this.load();
        // The dialog is removed in the next render and may hand the focus to its opener on the way out: the heading takes it after that.
        afterNextRender(() => this.focusHeading(), { injector: this.injector });
    }

    /** The focus was lost with an element that this change removed: the heading takes it. */
    private keepFocus(): void {
        const active = this.document.activeElement;
        if (active === null || active === this.document.body) this.focusHeading();
    }

    private focusHeading(): void {
        this.host.nativeElement.querySelector<HTMLElement>('h1')?.focus();
    }

    private applyFilters(): void {
        this.sortTimer = null;
        void this.router.navigate([], { relativeTo: this.route, queryParams: addressParams(this.address().q, this.draft()), replaceUrl: true });
    }

    private clearSortTimer(): void {
        if (this.sortTimer !== null) clearTimeout(this.sortTimer);
        this.sortTimer = null;
    }

    /** Reads what the address asks for; a read that is still on its way is abandoned. */
    private async load(): Promise<void> {
        const sequence = ++this.sequence;
        const address = this.address();
        this.shelvesSubscription?.unsubscribe();
        this.pager.cancel();
        this.browsing.set(false);
        this.shelvesFailure.set(null);
        this.shelvesPhase.set('idle');
        this.booting.set(true);
        // A signed-in viewer's bearer must be on the first request, so the limits are keyed by the account.
        await this.auth.restore();
        if (sequence !== this.sequence || this.destroyRef.destroyed) return;
        this.booting.set(false);
        if (this.facets() === null) this.loadFacets();
        if (isShelvesView(address)) {
            this.loadShelves(sequence);
        } else {
            this.query = { q: address.q, filters: address.filters };
            this.pager.start();
        }
    }

    private loadShelves(sequence: number): void {
        this.shelvesPhase.set('loading');
        this.shelvesSubscription = this.api.shelves().subscribe({
            next: shelves => {
                if (sequence !== this.sequence) return;
                this.shelves.set(shelves);
                this.shelvesPhase.set('ready');
                // A few decks make no shelf (a shelf needs four): the page then shows them as a list rather than nothing.
                if (shelves.listedCount > 0 && shelves.shelves.every(shelf => shelf.decks.length < SHELF_MIN_DECKS)) {
                    this.browsing.set(true);
                    this.query = { q: '', filters: this.draft() };
                    this.pager.start();
                }
            },
            error: (error: unknown) => {
                if (sequence !== this.sequence) return;
                this.shelvesFailure.set(publicFailureOf(error));
                this.shelvesPhase.set('error');
            }
        });
    }

    private loadFacets(): void {
        this.facetsSubscription?.unsubscribe();
        this.facetsSubscription = this.api.facets().subscribe({
            next: facets => this.facets.set(facets),
            // Without the facets the filters still work with their fixed lists (the next read of the page asks again); only a switched-off catalogue changes the page.
            error: (error: unknown) => { if (publicFailureOf(error).kind === 'not-found') this.closed.set(true); }
        });
    }

    /**
     * Asks Identity for the owners who are not known yet, one request per chunk of 50: a chunk that fails leaves only its own cards without a name
     * and is asked again the next time the displayed cards change.
     */
    private askAuthors(owners: readonly string[]): void {
        const wanted = [...new Set(owners)].filter(id => !this.asked.has(id));
        for (let from = 0; from < wanted.length; from += AUTHOR_CARD_BATCH_MAX) {
            const chunk = wanted.slice(from, from + AUTHOR_CARD_BATCH_MAX);
            chunk.forEach(id => this.asked.add(id));
            this.authors.cards(chunk).then(cards => {
                if (this.destroyRef.destroyed) return;
                const next = new Map(this.authorsById());
                for (const id of chunk) {
                    const card = cards.get(id);
                    next.set(id, card === undefined ? NO_AUTHOR : { username: card.profileUsername, avatarSrc: this.authors.avatarUrl(card) });
                }
                this.authorsById.set(next);
            }, () => chunk.forEach(id => this.asked.delete(id)));
        }
    }

    private display(card: CommunityCard): PublicDeckCard {
        return displayCard(card, this.authorsById().get(card.ownerId) ?? NO_AUTHOR, this.origin);
    }
}
