import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Title } from '@angular/platform-browser';
import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { Observable, Subject, of, throwError } from 'rxjs';

import contract from '../../../../../contracts/community/catalog.json';
import { AuthService, AuthStatus } from '../../auth.service';
import { AuthorCardsService } from '../../author-cards.service';
import { ComplaintApiService } from '../../shared/complaint-api.service';
import { ShareLinkService } from '../../shared/share-link.service';
import { PublicDeckFailure } from '../public-deck/public-deck.models';
import { CommunityApiService } from './community-api.service';
import { CommunityPageComponent, SORT_DEBOUNCE_MS } from './community-page.component';
import {
    CommunityCard, CommunityDeckPage, CommunityShelf, CommunityShelves, parseCommunityCard, parseCommunityFacets
} from './community.models';

const ME = '11111111-1111-4111-8111-111111111111';
const OTHER = contract.card.ownerId;
const FACETS = parseCommunityFacets(contract.facets.response);

function card(index: number, patch: Partial<CommunityCard> = {}): CommunityCard {
    const code = `Kq7xT3mNp${index}`;
    return { ...parseCommunityCard(contract.card), code, slug: `kolonda-${index}`, title: `Колода ${index}`, ...patch };
}
const cards = (from: number, count: number, patch: Partial<CommunityCard> = {}): CommunityCard[] => Array.from({ length: count }, (_, offset) => card(from + offset, patch));
const shelf = (kind: CommunityShelf['kind'], decks: readonly CommunityCard[], patch: Partial<CommunityShelf> = {}): CommunityShelf =>
    ({ kind, topic: null, decks, more: kind === 'editorial' ? null : { sort: kind === 'learning' ? 'learning' : 'new' }, ...patch });
const SHELVES: CommunityShelves = {
    listedCount: 37,
    shelves: [
        shelf('editorial', cards(1, 4)),
        shelf('learning', cards(1, 8)),
        shelf('new', cards(1, 4)),
        shelf('topic', cards(1, 5), { topic: { id: 'spanish', name: 'Испанский', parentId: 'languages' }, more: { topic: 'spanish', sort: 'popular' } }),
        shelf('topic', cards(1, 3), { topic: { id: 'english', name: 'Английский', parentId: 'languages' }, more: { topic: 'english', sort: 'popular' } })
    ]
};
const page = (decks: readonly CommunityCard[], patch: Partial<CommunityDeckPage> = {}): CommunityDeckPage =>
    ({ decks, total: decks.length, totalCapped: false, nextCursor: null, ...patch });

describe('CommunityPageComponent', () => {
    let api: { facets: ReturnType<typeof vi.fn>; shelves: ReturnType<typeof vi.fn>; decks: ReturnType<typeof vi.fn> };
    let status: ReturnType<typeof signal<AuthStatus>>;
    let user: ReturnType<typeof signal<{ accountId: string } | null>>;
    let authors: { cards: ReturnType<typeof vi.fn>; avatarUrl: ReturnType<typeof vi.fn> };
    let complaints: { fileDeck: ReturnType<typeof vi.fn> };
    let harness: RouterTestingHarness;

    const root = () => harness.routeNativeElement as HTMLElement;
    const router = () => TestBed.inject(Router);
    const all = <T extends Element>(selector: string) => [...root().querySelectorAll<T>(selector)];
    const one = <T extends Element>(selector: string) => root().querySelector<T>(selector);

    beforeEach(() => {
        status = signal<AuthStatus>('anonymous');
        user = signal(null);
        api = {
            facets: vi.fn().mockReturnValue(of(FACETS)),
            shelves: vi.fn().mockReturnValue(of(SHELVES)),
            decks: vi.fn().mockReturnValue(of(page(cards(1, 3))))
        };
        authors = {
            cards: vi.fn().mockImplementation(async (ids: readonly string[]) => new Map(ids.filter(id => id === OTHER)
                .map(id => [id, { accountId: id, profileUsername: 'anna.k', displayName: null, bio: null, avatarPresent: false }]))),
            avatarUrl: vi.fn().mockReturnValue(null)
        };
        complaints = { fileDeck: vi.fn().mockReturnValue(of(undefined)) };
        TestBed.configureTestingModule({ providers: [
            provideRouter([{ path: 'community', component: CommunityPageComponent }, { path: 'login', children: [] }, { path: 'decks', children: [] }, { path: '', children: [] }]),
            { provide: CommunityApiService, useValue: api },
            { provide: AuthService, useValue: { status, user, restore: vi.fn().mockResolvedValue(undefined) } },
            { provide: AuthorCardsService, useValue: authors },
            { provide: ShareLinkService, useValue: { share: vi.fn().mockResolvedValue('copied') } },
            { provide: ComplaintApiService, useValue: complaints }
        ] });
    });
    afterEach(() => vi.useRealTimers());

    async function settle(): Promise<void> {
        for (let round = 0; round < 4; round++) {
            await new Promise(resolve => setTimeout(resolve, 0));
            harness.detectChanges();
        }
    }
    async function open(url = '/community'): Promise<void> {
        harness = await RouterTestingHarness.create();
        await harness.navigateByUrl(url, CommunityPageComponent);
        await settle();
    }
    async function go(url: string): Promise<void> {
        await harness.navigateByUrl(url);
        await settle();
    }
    const lastQuery = () => api.decks.mock.lastCall![0] as { q: string; filters: Record<string, unknown> };
    function type(text: string): void {
        const field = one<HTMLInputElement>('app-book-search input[type=search]')!;
        field.value = text;
        field.dispatchEvent(new Event('input'));
        one<HTMLFormElement>('app-book-search form')!.dispatchEvent(new Event('submit', { cancelable: true }));
    }
    function check(label: string, on = true): void {
        const box = all<HTMLLabelElement>('app-catalog-filters .check-row').find(row => row.textContent?.trim() === label)!.querySelector('input')!;
        box.checked = on;
        box.dispatchEvent(new Event('change'));
    }
    function sort(value: string): void {
        const radio = one<HTMLInputElement>(`app-catalog-filters .sort input[type=radio][value=${value}]`)!;
        radio.checked = true;
        radio.dispatchEvent(new Event('change'));
    }

    describe('the shelves', () => {
        beforeEach(async () => open());

        it('reads the shelves and the facets once, and draws the page with its heading, the open book and the filters', () => {
            expect(api.shelves).toHaveBeenCalledTimes(1);
            expect(api.facets).toHaveBeenCalledTimes(1);
            expect(api.decks).not.toHaveBeenCalled();
            expect(one('h1')?.textContent).toBe('Сообщество');
            expect(one('h1')?.getAttribute('tabindex')).toBe('-1');
            expect(one('app-book-search[data-mode=open] search[aria-label="Поиск колод"]')).not.toBeNull();
            expect(one('app-catalog-filters')).not.toBeNull();
            expect(TestBed.inject(Title).getTitle()).toBe('Сообщество — Mnema');
        });

        it('draws the shelves in server order with the client titles, hides the one with fewer than four decks and caps a row at six cards', () => {
            expect(all('app-deck-shelf h2').map(heading => heading.textContent)).toEqual(['Выбор Мнемы', 'Учат сейчас', 'Новое', 'Испанский']);
            const rows = all('app-deck-shelf').map(shelfElement => shelfElement.querySelectorAll('ul.row > li').length);
            expect(rows).toEqual([4, 6, 4, 5]);
        });

        it('leads «Все» to the community with the query of the shelf, and gives the editorial shelf none', () => {
            const more = all<HTMLAnchorElement>('app-deck-shelf a.more');
            expect(more.map(link => link.getAttribute('href'))).toEqual(['/community?sort=learning', '/community?sort=new', '/community?topic=spanish&sort=popular']);
            expect(more.map(link => link.getAttribute('aria-label'))).toEqual(['Все колоды: Учат сейчас', 'Все колоды: Новое', 'Все колоды: Испанский']);
        });

        it('puts the focus on the heading when «Все» replaces the shelves with the list, and leaves a control that is in use alone', async () => {
            const more = all<HTMLAnchorElement>('app-deck-shelf a.more').find(link => link.getAttribute('aria-label') === 'Все колоды: Новое')!;
            more.focus();
            expect(document.activeElement).toBe(more);
            more.click();
            await settle();
            expect(one('app-deck-shelf')).toBeNull();
            expect(document.activeElement).toBe(one('h1'));
            // A filter chosen in the list keeps the focus where the reader put it.
            const box = all<HTMLLabelElement>('app-catalog-filters .check-row').find(row => row.textContent?.trim() === 'Живая')!.querySelector('input')!;
            box.focus();
            check('Живая');
            await settle();
            expect(document.activeElement).toBe(box);
        });

        it('shows cards that link to the deck page, with the author from Identity and without one for a hidden profile', async () => {
            const first = one('app-deck-shelf app-public-deck-card')!;
            expect(first.querySelector('h3 a')?.getAttribute('href')).toBe('/d/Kq7xT3mNp1/kolonda-1');
            expect(first.querySelector('app-author-chip')?.textContent).toContain('@anna.k');
            expect(authors.cards).toHaveBeenCalledTimes(1);
            expect(authors.cards.mock.calls[0][0]).toEqual([OTHER]);
        });

        it('shows a card whose author is hidden, and does not hold cards back for Identity', async () => {
            TestBed.resetTestingModule();
            const pending = new Promise<never>(() => undefined);
            authors.cards.mockReturnValue(pending);
            TestBed.configureTestingModule({ providers: [
                provideRouter([{ path: 'community', component: CommunityPageComponent }]),
                { provide: CommunityApiService, useValue: api },
                { provide: AuthService, useValue: { status, user, restore: vi.fn().mockResolvedValue(undefined) } },
                { provide: AuthorCardsService, useValue: authors }
            ] });
            await open();
            expect(all('app-deck-shelf app-public-deck-card').length).toBeGreaterThan(0);
            expect(one('app-deck-shelf app-author-chip .login')).toBeNull();
        });
    });

    describe('an empty catalogue', () => {
        beforeEach(() => {
            api.shelves.mockReturnValue(of({ listedCount: 0, shelves: [] }));
            api.facets.mockReturnValue(of(parseCommunityFacets(contract.facets.empty)));
        });

        it('says «Пока здесь пусто.» and shows no filters and no shelves, but keeps the book', async () => {
            await open();
            expect(one('app-community-empty h2')?.textContent).toBe('Пока здесь пусто.');
            expect(one('app-community-empty a.button')?.getAttribute('href')).toBe('/login');
            expect(one('app-catalog-filters')).toBeNull();
            expect(one('app-deck-shelf')).toBeNull();
            expect(one('app-book-search')).not.toBeNull();
        });

        it('leads a signed-in reader to «Мои колоды»', async () => {
            status.set('authenticated');
            await open();
            expect(one('app-community-empty a.button')?.getAttribute('href')).toBe('/decks');
        });

        it('still searches, and says that nothing was found', async () => {
            api.decks.mockReturnValue(of(page([])));
            await open('/community?q=кот');
            expect(one('app-catalog-filters')).toBeNull();
            expect(one('app-deck-grid .count')?.textContent).toContain('Ничего не нашли');
        });
    });

    it('browses when decks are listed but no shelf has the four it needs', async () => {
        api.shelves.mockReturnValue(of({ listedCount: 3, shelves: [shelf('new', cards(1, 3))] }));
        await open();
        expect(one('app-deck-shelf')).toBeNull();
        expect(one('app-community-empty')).toBeNull();
        expect(api.decks).toHaveBeenCalledTimes(1);
        // Too few for a shelf: the newest first, and the control says so.
        expect(lastQuery().filters).toMatchObject({ sort: 'new' });
        expect(one<HTMLInputElement>('app-catalog-filters .sort input[type=radio]:checked')!.value).toBe('new');
        expect(all('app-deck-grid app-public-deck-card')).toHaveLength(3);
    });

    describe('the results', () => {
        it('reads the first page for a search and announces the count once, without a shelf', async () => {
            await open('/community?q=%D0%B8%D1%81%D0%BF%D0%B0%D0%BD%D1%81%D0%BA%D0%B8%D0%B9&topic=spanish&media=audio&media=video');
            expect(api.shelves).not.toHaveBeenCalled();
            expect(lastQuery().q).toBe('испанский');
            expect(lastQuery().filters).toMatchObject({ topic: 'spanish', media: ['audio', 'video'] });
            expect(api.decks.mock.lastCall![1]).toBeNull();
            expect(one('app-deck-grid [role=status] .count')?.textContent).toBe('Нашли 3 колоды');
            expect(all('app-deck-grid ul.grid h2')).toHaveLength(3);
        });

        it('orders a text search by closeness unless the address names a sorting, and offers that only with a text', async () => {
            const labels = () => all('app-catalog-filters .sort label').map(label => label.textContent!.trim());
            await open('/community?q=кот');
            expect(one('app-catalog-filters fieldset.segmented[disabled]')).toBeNull();
            expect(one('app-catalog-filters .sort-note')).toBeNull();
            expect(labels()[0]).toBe('По близости к запросу');
            expect(one<HTMLInputElement>('app-catalog-filters .sort input:checked')!.value).toBe('relevance');
            expect(api.decks.mock.lastCall![0]).toMatchObject({ q: 'кот', filters: { sort: 'relevance' } });
            await go('/community?topic=spanish');
            expect(labels()).not.toContain('По близости к запросу');
            await go('/community?q=кот&sort=new');
            expect(one<HTMLInputElement>('app-catalog-filters .sort input:checked')!.value).toBe('new');
            expect(lastQuery().filters).toMatchObject({ sort: 'new' });
        });

        it('keeps the text when another sorting is chosen, and writes popular too because a missing sort means closeness', async () => {
            await open('/community?q=кот&topic=spanish');
            vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
            sort('new');
            await vi.advanceTimersByTimeAsync(SORT_DEBOUNCE_MS);
            vi.useRealTimers();
            await settle();
            expect(router().url).toBe('/community?q=%D0%BA%D0%BE%D1%82&topic=spanish&sort=new');
            vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
            sort('popular');
            await vi.advanceTimersByTimeAsync(SORT_DEBOUNCE_MS);
            vi.useRealTimers();
            await settle();
            expect(router().url).toBe('/community?q=%D0%BA%D0%BE%D1%82&topic=spanish&sort=popular');
            vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
            sort('relevance');
            await vi.advanceTimersByTimeAsync(SORT_DEBOUNCE_MS);
            vi.useRealTimers();
            await settle();
            expect(router().url).toBe('/community?q=%D0%BA%D0%BE%D1%82&topic=spanish');
            expect(lastQuery().filters).toMatchObject({ sort: 'relevance' });
        });

        it('starts a new text with closeness, keeps a sorting the address names, and drops closeness when the text is cleared', async () => {
            await open('/community?topic=spanish');
            type('кот');
            await settle();
            expect(router().url).toBe('/community?q=%D0%BA%D0%BE%D1%82&topic=spanish');
            await go('/community?q=%D0%BA%D0%BE%D1%82&sort=updated');
            type('пёс');
            await settle();
            expect(router().url).toBe('/community?q=%D0%BF%D1%91%D1%81&sort=updated');
            await go('/community?q=%D0%BA%D0%BE%D1%82');
            type('');
            await settle();
            expect(router().url).toBe('/community');
        });

        it('browses with a named sorting alone, silently, and offers the language names and topics of the facets', async () => {
            await open('/community?sort=new');
            expect(lastQuery().filters).toMatchObject({ sort: 'new' });
            expect(one('app-deck-grid .count')).toBeNull();
            const topics = all('app-catalog-filters app-mnema-select')[0];
            expect(topics.textContent).toContain('Любая');
            expect(all('app-catalog-filters app-mnema-select')[1].textContent).toContain('Любой');
        });

        it('shows the chips of the filters that are in the address', async () => {
            await open('/community?topic=spanish&learn=es&level=B1&fresh=true');
            expect(all('app-catalog-filters .chips button').map(chip => chip.textContent!.replace(/\s+/gu, ' ').trim()))
                .toEqual(['Тема: Испанский×', 'Что учим: Испанский×', 'Уровень: B1×', 'Живая×']);
        });

        it('announces the count again for a new search, and not for the pages that continue it', async () => {
            api.decks.mockReturnValueOnce(of(page(cards(1, 2), { total: 5, nextCursor: 'c2' }))).mockReturnValueOnce(of(page(cards(3, 3))));
            await open('/community?q=a');
            expect(one('app-deck-grid .count')?.textContent).toBe('Нашли 5 колод');
            harness.routeDebugElement!.query(element => element.name === 'app-deck-grid').componentInstance.loadNext.emit();
            await settle();
            expect(api.decks.mock.lastCall![1]).toBe('c2');
            expect(all('app-deck-grid ul.grid h2')).toHaveLength(5);
            expect(all('app-deck-grid .count')).toHaveLength(1);
            expect(one('app-deck-grid .count')?.textContent).toBe('Нашли 3 колоды');
        });

        it('says «больше», when the server counted only up to the total', async () => {
            api.decks.mockReturnValue(of(page(cards(1, 3), { total: 200, totalCapped: true })));
            await open('/community?q=a');
            expect(one('app-deck-grid .count')?.textContent).toBe('Нашли больше 200 колод');
        });

        it('does not repeat a deck that the next page sends again', async () => {
            api.decks.mockReturnValueOnce(of(page(cards(1, 3), { nextCursor: 'c2' }))).mockReturnValueOnce(of(page(cards(3, 3))));
            await open('/community?q=a');
            harness.routeDebugElement!.query(element => element.name === 'app-deck-grid').componentInstance.loadNext.emit();
            await settle();
            expect(all('app-deck-grid ul.grid h2').map(title => title.textContent!.trim())).toEqual(['Колода 1', 'Колода 2', 'Колода 3', 'Колода 4', 'Колода 5']);
        });

        it('starts again from the first page, silently, when the cursor is of an older generation', async () => {
            api.decks.mockReturnValueOnce(of(page(cards(1, 3), { nextCursor: 'c2' })))
                .mockReturnValueOnce(throwError(() => new PublicDeckFailure('stale')))
                .mockReturnValueOnce(of(page(cards(7, 2))));
            await open('/community?q=a');
            harness.routeDebugElement!.query(element => element.name === 'app-deck-grid').componentInstance.loadNext.emit();
            await settle();
            expect(api.decks.mock.calls.map(call => call[1])).toEqual([null, 'c2', null]);
            expect(all('app-deck-grid ul.grid h2').map(title => title.textContent!.trim())).toEqual(['Колода 7', 'Колода 8']);
            expect(one('.notice')).toBeNull();
        });

        it('keeps the cards and waits for the retry when the next page fails', async () => {
            api.decks.mockReturnValueOnce(of(page(cards(1, 3), { nextCursor: 'c2' })))
                .mockReturnValueOnce(throwError(() => new PublicDeckFailure('unavailable')))
                .mockReturnValueOnce(of(page(cards(4, 1))));
            await open('/community?q=a');
            const grid = () => harness.routeDebugElement!.query(element => element.name === 'app-deck-grid').componentInstance;
            grid().loadNext.emit();
            await settle();
            expect(all('app-deck-grid ul.grid h2')).toHaveLength(3);
            expect(one('app-deck-grid app-auto-load .notice')?.textContent).toContain('Не удалось загрузить следующие колоды');
            grid().loadNext.emit();
            await settle();
            expect(all('app-deck-grid ul.grid h2')).toHaveLength(4);
            expect(one('app-deck-grid app-auto-load .notice')).toBeNull();
        });

        it('renders only the latest answer when the address changes while a request is on its way', async () => {
            const first = new Subject<CommunityDeckPage>();
            const second = new Subject<CommunityDeckPage>();
            api.decks.mockReturnValueOnce(first).mockReturnValueOnce(second);
            await open('/community?q=a');
            await go('/community?q=b');
            expect(first.observed).toBe(false);
            second.next(page(cards(5, 1)));
            first.next(page(cards(1, 3)));
            await settle();
            expect(all('app-deck-grid ul.grid h2').map(title => title.textContent!.trim())).toEqual(['Колода 5']);
        });
    });

    describe('the address is the state', () => {
        beforeEach(async () => open());

        it('searches from the book as a new history entry, with the text in the address', async () => {
            type('  испанский ');
            await settle();
            expect(router().url).toBe('/community?q=%D0%B8%D1%81%D0%BF%D0%B0%D0%BD%D1%81%D0%BA%D0%B8%D0%B9');
            expect(lastQuery().q).toBe('испанский');
            expect(one('app-deck-grid')).not.toBeNull();
            // The field shows the text as the address holds it: trimmed.
            expect(one<HTMLInputElement>('app-book-search input[type=search]')!.value).toBe('испанский');
        });

        it('removes the text on an empty search and returns to the shelves; the filters stay', async () => {
            check('Аудио');
            await settle();
            type('кот');
            await settle();
            expect(router().url).toBe('/community?q=%D0%BA%D0%BE%D1%82&media=audio');
            type('');
            await settle();
            expect(router().url).toBe('/community?media=audio');
            await go('/community');
            expect(one('app-deck-shelf')).not.toBeNull();
        });

        it('writes a filter to the address at once, replacing the history entry, and reads the results for it', async () => {
            const replace = vi.spyOn(router(), 'navigate');
            check('Аудио');
            await settle();
            expect(router().url).toBe('/community?media=audio');
            expect(replace.mock.lastCall![1]).toMatchObject({ replaceUrl: true });
            expect(lastQuery().filters).toMatchObject({ media: ['audio'] });
            check('Живая');
            await settle();
            expect(router().url).toBe('/community?media=audio&fresh=true');
        });

        it('waits for the last arrow key before it writes the sorting, so the radios do not fire a request each', async () => {
            vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
            sort('new');
            await vi.advanceTimersByTimeAsync(SORT_DEBOUNCE_MS - 100);
            sort('updated');
            await vi.advanceTimersByTimeAsync(SORT_DEBOUNCE_MS - 1);
            expect(router().url).toBe('/community');
            expect(api.decks).not.toHaveBeenCalled();
            await vi.advanceTimersByTimeAsync(1);
            vi.useRealTimers();
            await settle();
            expect(router().url).toBe('/community?sort=updated');
            expect(api.decks).toHaveBeenCalledTimes(1);
            expect(lastQuery().filters).toMatchObject({ sort: 'updated' });
        });

        it('lets a filter that comes with a pending sorting take both along', async () => {
            vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
            sort('new');
            harness.detectChanges();
            check('Аудио');
            vi.useRealTimers();
            await settle();
            expect(router().url).toBe('/community?media=audio&sort=new');
        });

        it('returns to the shelves when the sorting is the default again and nothing else is on', async () => {
            await go('/community?sort=new');
            expect(one('app-deck-shelf')).toBeNull();
            vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
            sort('popular');
            await vi.advanceTimersByTimeAsync(SORT_DEBOUNCE_MS);
            vi.useRealTimers();
            await settle();
            expect(router().url).toBe('/community');
            expect(one('app-deck-shelf')).not.toBeNull();
        });

        it('follows the back button: the book and the filters take the address again', async () => {
            await go('/community?q=кот&topic=spanish');
            expect(one<HTMLInputElement>('app-book-search input[type=search]')!.value).toBe('кот');
            await go('/community');
            expect(one<HTMLInputElement>('app-book-search input[type=search]')!.value).toBe('');
            expect(all('app-catalog-filters .chips button')).toHaveLength(0);
        });
    });

    describe('the card menu', () => {
        beforeEach(() => {
            api.shelves.mockReturnValue(of({ listedCount: 9, shelves: [shelf('new', [card(1, { ownerId: ME }), ...cards(2, 3)])] }));
        });
        const rows = (index: number) => [...all('app-deck-shelf app-public-deck-card')[index].querySelectorAll<HTMLElement>('app-action-menu [role=menuitem]')];
        const menuRows = (index: number) => {
            all('app-deck-shelf app-public-deck-card')[index].querySelector('app-action-menu button')!.dispatchEvent(new Event('click', { bubbles: true }));
            harness.detectChanges();
            return rows(index).map(item => item.textContent!.trim());
        };

        it('offers «Пожаловаться» for a deck that is not mine, and only «Поделиться» for my own', async () => {
            status.set('authenticated');
            user.set({ accountId: ME });
            await open();
            expect(menuRows(0)).toEqual(['Поделиться']);
            expect(menuRows(1)).toEqual(['Поделиться', 'Пожаловаться']);
        });

        it('offers it to a guest too, who is then sent to sign in and back to this very search', async () => {
            await open('/community');
            expect(menuRows(1)).toEqual(['Поделиться', 'Пожаловаться']);
            rows(1).find(item => item.textContent!.trim() === 'Пожаловаться')!.click();
            await settle();
            expect(router().url).toBe('/login?returnUrl=%2Fcommunity');
            expect(document.querySelector('app-complaint-dialog')).toBeNull();
        });

        it('opens the complaint dialog for a signed-in reader', async () => {
            status.set('authenticated');
            user.set({ accountId: ME });
            await open();
            menuRows(1);
            rows(1).find(item => item.textContent!.trim() === 'Пожаловаться')!.click();
            await settle();
            expect(one('app-complaint-dialog h2')?.textContent).toBe('Пожаловаться на колоду');
            expect(one('app-complaint-dialog .subject')?.textContent).toBe('Колода 2');
        });
    });

    describe('failures', () => {
        const fails = (failure: PublicDeckFailure): Observable<never> => throwError(() => failure);

        it('says calmly that the section opens soon when the catalogue is switched off, and leads a guest to the home page', async () => {
            api.shelves.mockReturnValue(fails(new PublicDeckFailure('not-found')));
            api.facets.mockReturnValue(fails(new PublicDeckFailure('not-found')));
            await open();
            expect(one('.notice')?.textContent).toContain('Раздел «Сообщество» скоро откроется.');
            expect(one('.notice a')?.getAttribute('href')).toBe('/');
            expect(one('app-book-search')).toBeNull();
            expect(one('app-catalog-filters')).toBeNull();
            expect(one('.notice.error')).toBeNull();
        });

        it('leads a signed-in reader to «Мои колоды», for a search too', async () => {
            status.set('authenticated');
            api.decks.mockReturnValue(fails(new PublicDeckFailure('not-found')));
            api.facets.mockReturnValue(fails(new PublicDeckFailure('not-found')));
            await open('/community?q=a');
            expect(one('.notice a')?.getAttribute('href')).toBe('/decks');
        });

        it('says how long to wait after 429 and retries from the button', async () => {
            api.shelves.mockReturnValueOnce(fails(new PublicDeckFailure('rate-limited', 37)));
            await open();
            expect(one('.notice.error')?.textContent).toContain('Слишком много запросов. Подождите 37 с и повторите.');
            one<HTMLButtonElement>('.notice button')!.click();
            // The pressed button is gone with its notice: the focus is on the heading, not lost.
            expect(document.activeElement).toBe(one('h1'));
            await settle();
            expect(api.shelves).toHaveBeenCalledTimes(2);
            expect(document.activeElement).toBe(one('h1'));
            expect(one('.notice')).toBeNull();
            expect(one('app-deck-shelf')).not.toBeNull();
        });

        it('says that readers are many after 503, and keeps the book and the filters', async () => {
            api.decks.mockReturnValue(fails(new PublicDeckFailure('busy', 1)));
            await open('/community?q=a');
            expect(one('.notice.error')?.textContent).toContain('Сейчас много читателей. Повторите через секунду.');
            expect(one('app-book-search')).not.toBeNull();
            expect(one('app-catalog-filters')).not.toBeNull();
        });

        it('says that the decks could not be loaded for anything else, and retries the same search', async () => {
            api.decks.mockReturnValueOnce(fails(new PublicDeckFailure('unavailable'))).mockReturnValueOnce(of(page(cards(1, 2))));
            await open('/community?topic=spanish');
            expect(one('.notice.error')?.textContent).toContain('Не удалось загрузить колоды. Проверьте соединение и повторите.');
            one<HTMLButtonElement>('.notice button')!.click();
            await settle();
            expect(api.decks).toHaveBeenCalledTimes(2);
            expect(lastQuery().filters).toMatchObject({ topic: 'spanish' });
            expect(all('app-deck-grid ul.grid h2')).toHaveLength(2);
        });

        it('works without the facets: the filters are drawn with their fixed lists', async () => {
            api.facets.mockReturnValue(fails(new PublicDeckFailure('unavailable')));
            await open();
            expect(one('app-catalog-filters')).not.toBeNull();
            expect(one('app-deck-shelf')).not.toBeNull();
            expect(one('.notice')).toBeNull();
        });
    });

    describe('shelf sizes', () => {
        it('draws up to twelve decks on the shelf that has no «Все» and one row of six on the others', async () => {
            api.shelves.mockReturnValue(of({ listedCount: 40, shelves: [shelf('editorial', cards(1, 9)), shelf('learning', cards(1, 9))] }));
            await open();
            expect(all('app-deck-shelf').map(element => element.querySelectorAll('ul.row > li').length)).toEqual([9, 6]);
        });

        it('caps the editorial shelf at the twelve the contract allows', async () => {
            api.shelves.mockReturnValue(of({ listedCount: 40, shelves: [shelf('editorial', cards(1, 9).concat(cards(10, 3), cards(13, 3)))] }));
            await open();
            expect(one('app-deck-shelf')!.querySelectorAll('ul.row > li')).toHaveLength(12);
        });
    });

    describe('an address from a stale or shared link', () => {
        it('drops a topic and a language that the facets do not have, replacing the history entry', async () => {
            const navigate = vi.spyOn(TestBed.inject(Router), 'navigate');
            await open('/community?topic=ghost&learn=zz&level=B1&media=audio');
            expect(router().url).toBe('/community?level=B1&media=audio');
            expect(navigate.mock.calls.some(call => (call[1] as { replaceUrl?: boolean }).replaceUrl === true)).toBe(true);
            expect(lastQuery().filters).toMatchObject({ topic: null, learn: null, level: 'B1' });
            expect(all('app-catalog-filters .chips button').map(chip => chip.textContent!.replace(/\s+/gu, ' ').trim())).toEqual(['Уровень: B1×', 'Внутри: аудио×']);
        });

        it('says once, politely, which language was dropped when it has a name, and does not show a raw value', async () => {
            await open('/community?learn=de');
            expect(router().url).toBe('/community');
            expect(one('.stale[role=status]')!.textContent).toBe('Фильтр «Немецкий» больше недоступен, показываем без него.');
            await go('/community?topic=ghost&learn=zz');
            const note = one('.stale[role=status]')!.textContent!;
            expect(note).toBe('Один из фильтров больше недоступен, показываем без него.');
            expect(note).not.toContain('ghost');
            expect(note).not.toContain('zz');
        });

        it('says nothing when no filter was dropped, and clears the note with the next search', async () => {
            await open('/community?topic=spanish');
            expect(one('.stale')!.textContent).toBe('');
            await go('/community?topic=ghost');
            expect(one('.stale')!.textContent).toContain('больше недоступен');
            type('кот');
            await settle();
            expect(one('.stale')!.textContent).toBe('');
        });

        it('keeps a topic and a language that the facets have', async () => {
            await open('/community?topic=spanish&learn=es');
            expect(router().url).toBe('/community?topic=spanish&learn=es');
        });

        it('shows what the facets know only after they are loaded: a failed facets request drops nothing', async () => {
            api.facets.mockReturnValue(throwError(() => new PublicDeckFailure('unavailable')));
            await open('/community?topic=ghost');
            expect(router().url).toBe('/community?topic=ghost');
        });

        it('says that nothing was found for a value the server refuses (an empty page), and keeps the chips so the filters can be removed', async () => {
            api.decks.mockReturnValue(of(page([])));
            await open('/community?level=C2&size=course');
            expect(one('app-deck-grid .count')?.textContent).toContain('Ничего не нашли');
            expect(one('.notice')).toBeNull();
            const chips = all<HTMLButtonElement>('app-catalog-filters .chips button');
            expect(chips).toHaveLength(2);
            chips[0].click();
            await settle();
            expect(router().url).toBe('/community?size=course');
        });
    });

    describe('the book and the filters', () => {
        it('keeps the words that are typed but not yet searched through a change of the filters, and takes the address\'s text when that changes', async () => {
            await open('/community?q=кот');
            const field = one<HTMLInputElement>('app-book-search input[type=search]')!;
            field.value = 'кошка';
            field.dispatchEvent(new Event('input'));
            harness.detectChanges();
            check('Аудио');
            await settle();
            expect(router().url).toBe('/community?q=%D0%BA%D0%BE%D1%82&media=audio');
            expect(one<HTMLInputElement>('app-book-search input[type=search]')!.value).toBe('кошка');
            await go('/community?q=%D0%BF%D1%91%D1%81');
            expect(one<HTMLInputElement>('app-book-search input[type=search]')!.value).toBe('пёс');
        });
    });

    describe('the list restarts', () => {
        it('asks the first page again, once and silently, when its generation is gone (412)', async () => {
            api.decks.mockReturnValueOnce(throwError(() => new PublicDeckFailure('stale'))).mockReturnValueOnce(of(page(cards(1, 2))));
            await open('/community?q=a');
            expect(api.decks.mock.calls.map(call => call[1])).toEqual([null, null]);
            expect(all('app-deck-grid ul.grid h2')).toHaveLength(2);
            expect(one('.notice')).toBeNull();
        });

        it('does not loop on a first page that keeps answering 412', async () => {
            api.decks.mockReturnValue(throwError(() => new PublicDeckFailure('stale')));
            await open('/community?q=a');
            expect(api.decks).toHaveBeenCalledTimes(2);
            expect(one('.notice.error')).not.toBeNull();
        });

        it('forgets a sorting that was still waiting when the address moves by itself (back, forward)', async () => {
            await open('/community?q=a');
            await go('/community?topic=spanish');
            api.decks.mockClear();
            vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
            sort('updated');
            harness.detectChanges();
            await harness.navigateByUrl('/community?q=a');
            await vi.advanceTimersByTimeAsync(SORT_DEBOUNCE_MS * 2);
            expect(router().url).toBe('/community?q=a');
            expect(api.decks.mock.calls.every(call => (call[0] as { q: string }).q === 'a')).toBe(true);
            vi.useRealTimers();
        });
    });

    describe('the catalogue that is not open', () => {
        const off = () => throwError(() => new PublicDeckFailure('not-found'));

        it('offers «Проверить ещё раз», and shows the page once the catalogue answers', async () => {
            api.shelves.mockReturnValueOnce(off());
            api.facets.mockReturnValueOnce(off());
            await open();
            expect(one('.notice')?.textContent).toContain('скоро откроется');
            const again = all<HTMLButtonElement>('.notice button').find(button => button.textContent === 'Проверить ещё раз')!;
            again.click();
            await settle();
            expect(api.shelves).toHaveBeenCalledTimes(2);
            expect(api.facets).toHaveBeenCalledTimes(2);
            expect(one('.notice')).toBeNull();
            expect(one('app-deck-shelf')).not.toBeNull();
        });

        it('asks the facets again with the next read when they failed, and with the retry of the page', async () => {
            api.facets.mockReturnValueOnce(throwError(() => new PublicDeckFailure('busy'))).mockReturnValue(of(FACETS));
            await open();
            expect(api.facets).toHaveBeenCalledTimes(1);
            expect(all('app-catalog-filters app-mnema-select')[0].textContent).not.toContain('Испанский');
            await go('/community?sort=new');
            expect(api.facets).toHaveBeenCalledTimes(2);
            await go('/community?sort=learning');
            expect(api.facets).toHaveBeenCalledTimes(2);
        });
    });

    describe('the complaint', () => {
        it('reads the list again when the dialog finds the deck gone (404)', async () => {
            status.set('authenticated');
            user.set({ accountId: ME });
            await open();
            const card1 = all('app-deck-shelf app-public-deck-card')[0];
            card1.querySelector('app-action-menu button')!.dispatchEvent(new Event('click', { bubbles: true }));
            harness.detectChanges();
            [...card1.querySelectorAll<HTMLElement>('app-action-menu [role=menuitem]')].find(item => item.textContent!.trim() === 'Пожаловаться')!.click();
            await settle();
            const dialog = harness.routeDebugElement!.query(element => element.name === 'app-complaint-dialog');
            expect(api.shelves).toHaveBeenCalledTimes(1);
            dialog.componentInstance.gone.emit();
            await settle();
            expect(one('app-complaint-dialog')).toBeNull();
            expect(api.shelves).toHaveBeenCalledTimes(2);
            expect(document.activeElement).toBe(one('h1'));
        });
    });

    describe('the authors', () => {
        const owner = (number: number) => `00000000-0000-4000-8000-${String(number).padStart(12, '0')}`;
        const named = (id: string) => ({ accountId: id, profileUsername: `user${Number(id.slice(-12))}`, displayName: null, bio: null, avatarPresent: false });
        const chips = () => all('ul.grid app-author-chip .login').map(chip => chip.textContent);

        it('asks only for the owners of the cards that are on the page, not for the rest of a shelf that «Все» holds', async () => {
            api.shelves.mockReturnValue(of({ listedCount: 40, shelves: [shelf('learning', cards(1, 10).map((item, index) => ({ ...item, ownerId: owner(index + 1) })))] }));
            await open();
            const asked = authors.cards.mock.calls.flatMap(call => call[0] as string[]);
            expect(asked).toEqual([1, 2, 3, 4, 5, 6].map(owner));
        });

        it('keeps the cards when Identity refuses, and asks again at the next change of the displayed cards', async () => {
            api.decks.mockReturnValueOnce(of(page(cards(1, 2).map((item, index) => ({ ...item, ownerId: owner(index + 1) })), { nextCursor: 'c2' })))
                .mockReturnValueOnce(of(page(cards(3, 1).map(item => ({ ...item, ownerId: owner(3) })))));
            authors.cards.mockRejectedValueOnce(new Error('offline')).mockImplementation(async (ids: readonly string[]) => new Map(ids.map(id => [id, named(id)])));
            await open('/community?q=a');
            expect(all('ul.grid h2')).toHaveLength(2);
            expect(chips()).toEqual([]);
            harness.routeDebugElement!.query(element => element.name === 'app-deck-grid').componentInstance.loadNext.emit();
            await settle();
            expect(authors.cards.mock.calls.map(call => call[0])).toEqual([[owner(1), owner(2)], [owner(1), owner(2), owner(3)]]);
            expect(chips()).toEqual(['@user1', '@user2', '@user3']);
        });

        it('asks in chunks of 50, and a chunk that fails leaves only its own cards without a name', async () => {
            const many = cards(1, 9).concat(cards(10, 51)).map((item, index) => ({ ...item, code: `Aa${String(index).padStart(8, '0').replace(/0/gu, 'x')}`, ownerId: owner(index + 1) }));
            api.decks.mockReturnValue(of(page(many)));
            authors.cards.mockRejectedValueOnce(new Error('offline')).mockImplementation(async (ids: readonly string[]) => new Map(ids.map(id => [id, named(id)])));
            await open('/community?q=a');
            expect(authors.cards.mock.calls.map(call => (call[0] as string[]).length)).toEqual([50, 10]);
            const names = chips();
            expect(names).toHaveLength(10);
            expect(names[0]).toBe('@user51');
        });
    });

    it('puts the title of the shell back when the page is left', async () => {
        await open();
        expect(TestBed.inject(Title).getTitle()).toBe('Сообщество — Mnema');
        await harness.navigateByUrl('/decks');
        expect(TestBed.inject(Title).getTitle()).toBe('Mnema');
    });
});
