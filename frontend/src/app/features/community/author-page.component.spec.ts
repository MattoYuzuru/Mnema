import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Title } from '@angular/platform-browser';
import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { Observable, Subject, of, throwError } from 'rxjs';

import contract from '../../../../../contracts/community/catalog.json';
import { AuthService, AuthStatus } from '../../auth.service';
import { AuthorCardsService } from '../../author-cards.service';
import { AuthorCard } from '../../public-profile';
import { ComplaintApiService } from '../../shared/complaint-api.service';
import { ShareLinkService } from '../../shared/share-link.service';
import { PublicDeckFailure } from '../public-deck/public-deck.models';
import { CommunityApiService } from './community-api.service';
import { AuthorPageComponent } from './author-page.component';
import { AuthorDeckEntry, AuthorDeckPage, parseCommunityCard } from './community.models';

const AUTHOR_ID = contract.card.ownerId;
const OTHER_READER = '11111111-1111-4111-8111-111111111111';
const AUTHOR: AuthorCard = { accountId: AUTHOR_ID, profileUsername: 'anna.k', displayName: 'Анна Ковалёва', bio: 'Учу испанский.\nПишу колоды.', avatarPresent: true };

function entry(index: number, patch: Partial<AuthorDeckEntry> = {}): AuthorDeckEntry {
    return { ...parseCommunityCard(contract.card), access: 'PUBLIC', code: `Kq7xT3mNp${index}`, slug: `kolonda-${index}`, title: `Колода ${index}`, ...patch };
}
function invitation(index: number): AuthorDeckEntry {
    return { ...entry(index), access: 'GRANTEE', slug: null, topic: null, media: [], mechanics: [], addedCount: null, learningNowCount: null, title: `Для группы ${index}` };
}
const list = (from: number, count: number): AuthorDeckEntry[] => Array.from({ length: count }, (_, offset) => entry(from + offset));
const page = (decks: readonly AuthorDeckEntry[], patch: Partial<AuthorDeckPage> = {}): AuthorDeckPage => ({ learningNowTotal: 120, decks, nextCursor: null, ...patch });

describe('AuthorPageComponent', () => {
    let api: { authorDecks: ReturnType<typeof vi.fn> };
    let status: ReturnType<typeof signal<AuthStatus>>;
    let user: ReturnType<typeof signal<{ accountId: string; profileUsername?: string | null; displayName?: string | null } | null>>;
    let authors: { byUsername: ReturnType<typeof vi.fn>; avatarUrl: ReturnType<typeof vi.fn> };
    let complaints: { fileProfile: ReturnType<typeof vi.fn>; fileDeck: ReturnType<typeof vi.fn> };
    let share: ReturnType<typeof vi.fn>;
    let harness: RouterTestingHarness;

    const root = () => harness.routeNativeElement as HTMLElement;
    const router = () => TestBed.inject(Router);
    const all = <T extends Element>(selector: string) => [...root().querySelectorAll<T>(selector)];
    const one = <T extends Element>(selector: string) => root().querySelector<T>(selector);
    const title = () => TestBed.inject(Title).getTitle();

    beforeEach(() => {
        status = signal<AuthStatus>('anonymous');
        user = signal(null);
        api = { authorDecks: vi.fn().mockReturnValue(of(page([entry(1), entry(2)]))) };
        authors = {
            byUsername: vi.fn().mockImplementation(async (name: string) => name === 'anna.k' ? AUTHOR : null),
            avatarUrl: vi.fn().mockReturnValue('https://identity.test/profiles/avatar')
        };
        complaints = { fileProfile: vi.fn().mockReturnValue(of(undefined)), fileDeck: vi.fn().mockReturnValue(of(undefined)) };
        share = vi.fn().mockResolvedValue('copied');
        TestBed.configureTestingModule({ providers: [
            provideRouter([{ path: 'u/:username', component: AuthorPageComponent }, { path: 'login', children: [] }, { path: 'decks', children: [] },
                { path: 'community', children: [] }, { path: '', children: [] }]),
            { provide: CommunityApiService, useValue: api },
            { provide: AuthService, useValue: { status, user, restore: vi.fn().mockResolvedValue(undefined) } },
            { provide: AuthorCardsService, useValue: authors },
            { provide: ShareLinkService, useValue: { share } },
            { provide: ComplaintApiService, useValue: complaints }
        ] });
    });

    async function settle(): Promise<void> {
        for (let round = 0; round < 4; round++) {
            await new Promise(resolve => setTimeout(resolve, 0));
            harness.detectChanges();
        }
    }
    async function open(url = '/u/anna.k'): Promise<void> {
        harness = await RouterTestingHarness.create();
        await harness.navigateByUrl(url, AuthorPageComponent);
        await settle();
    }
    async function go(url: string): Promise<void> {
        await harness.navigateByUrl(url);
        await settle();
    }
    const menuButton = (scope: Element) => scope.querySelector<HTMLButtonElement>('app-action-menu button')!;
    function menuRows(scope: Element): string[] {
        menuButton(scope).dispatchEvent(new Event('click', { bubbles: true }));
        harness.detectChanges();
        return [...scope.querySelectorAll<HTMLElement>('app-action-menu [role=menuitem]')].map(item => item.textContent!.trim());
    }
    const choose = (scope: Element, label: string) =>
        [...scope.querySelectorAll<HTMLElement>('app-action-menu [role=menuitem]')].find(item => item.textContent!.trim() === label)!.click();

    describe('the profile', () => {
        beforeEach(async () => open());

        it('asks Identity for the login, then Learning for the decks of the card\'s account, once', () => {
            expect(authors.byUsername).toHaveBeenCalledOnce();
            expect(authors.byUsername).toHaveBeenCalledWith('anna.k');
            expect(api.authorDecks).toHaveBeenCalledOnce();
            expect(api.authorDecks).toHaveBeenCalledWith(AUTHOR_ID, null);
        });

        it('draws the author header: the one h1 with the name, the login, the bio and «Учат сейчас»', () => {
            expect(all('h1')).toHaveLength(1);
            expect(one('h1')?.textContent).toBe('Анна Ковалёва');
            expect(one('app-author-header .login')?.textContent).toBe('@anna.k');
            expect(one('app-author-header .bio')?.textContent).toBe('Учу испанский.\nПишу колоды.');
            expect(one('app-author-header img.face')?.getAttribute('src')).toBe('https://identity.test/profiles/avatar');
            expect(one('app-author-header .learning')?.textContent).toContain('Учат сейчас: 120');
            expect(one('app-author-header .self')).toBeNull();
        });

        it('titles the page «Имя (@логин) — Mnema» and gives the title back when it is left', async () => {
            expect(title()).toBe('Анна Ковалёва (@anna.k) — Mnema');
            await go('/');
            expect(title()).toBe('Mnema');
        });

        it('moves the focus to the h1 once the profile is there', () => {
            expect(document.activeElement).toBe(one('h1'));
        });

        it('shares the address of the profile', async () => {
            const header = one('app-author-header')!;
            menuRows(header);
            choose(header, 'Поделиться');
            await settle();
            expect(share).toHaveBeenCalledWith(`${document.location.origin}/u/anna.k`, 'Анна Ковалёва');
        });

        it('has the status region in the DOM, empty once the profile is in', () => {
            expect(one('.status[role=status]')?.textContent).toBe('');
        });
    });

    it('titles the page «@логин — Mnema» when the author shows no name', async () => {
        authors.byUsername.mockResolvedValue({ ...AUTHOR, displayName: null });
        await open();
        expect(title()).toBe('@anna.k — Mnema');
        expect(one('h1')?.textContent).toBe('@anna.k');
    });

    describe('the decks', () => {
        it('draws «Колоды автора» as an h2 with the cards under it as h3, with no chip of the author and no count', async () => {
            await open();
            expect(one('section.decks h2')?.textContent).toBe('Колоды автора');
            expect(all('app-deck-grid h3.title').map(heading => heading.textContent?.trim())).toEqual(['Колода 1', 'Колода 2']);
            expect(one('app-deck-grid app-author-chip')).toBeNull();
            expect(one('app-deck-grid .count')).toBeNull();
            expect(one('app-deck-grid [role=status]')?.textContent?.trim()).toBe('');
        });

        it('links a public deck to /d/{code}/{slug} and an invitation-only one to /d/{code} with the mark «Доступна вам»', async () => {
            api.authorDecks.mockReturnValue(of(page([entry(1), invitation(2)])));
            await open();
            const cards = all('app-deck-grid app-public-deck-card');
            expect(cards.map(card => card.querySelector('h3 a')?.getAttribute('href'))).toEqual(['/d/Kq7xT3mNp1/kolonda-1', '/d/Kq7xT3mNp2']);
            expect(cards.map(card => card.querySelector('.stamp.solid')?.textContent ?? null)).toEqual([null, 'Доступна вам']);
            // The topic and the numbers of an invitation-only deck are not published, so its card shows none.
            expect(cards[1].querySelector('.audience')).toBeNull();
            expect(cards[1].querySelector('.media')).toBeNull();
        });

        it('shares the address of an invitation-only deck without a title in it', async () => {
            api.authorDecks.mockReturnValue(of(page([invitation(2)])));
            await open();
            const card = one('app-deck-grid app-public-deck-card')!;
            menuRows(card);
            choose(card, 'Поделиться');
            await settle();
            expect(share).toHaveBeenCalledWith(`${document.location.origin}/d/Kq7xT3mNp2`, 'Для группы 2');
        });

        it('continues through the shared auto-load: the next cursor is asked for and the cards are appended without duplicates', async () => {
            api.authorDecks.mockReturnValueOnce(of(page(list(1, 2), { nextCursor: 'c2' }))).mockReturnValueOnce(of(page([entry(2), entry(3)])));
            await open();
            const grid = harness.routeDebugElement!.query(element => element.name === 'app-deck-grid');
            expect(grid.componentInstance.continuation()).toBe('c2');
            grid.componentInstance.loadNext.emit();
            await settle();
            expect(api.authorDecks).toHaveBeenLastCalledWith(AUTHOR_ID, 'c2');
            expect(all('app-deck-grid h3.title').map(heading => heading.textContent?.trim())).toEqual(['Колода 1', 'Колода 2', 'Колода 3']);
            expect(one('section.decks .status')?.textContent).toBe('');
        });

        it('starts the list again from its first page when the cursor is stale (412)', async () => {
            api.authorDecks.mockReturnValueOnce(of(page(list(1, 2), { nextCursor: 'old' })))
                .mockReturnValueOnce(throwError(() => new PublicDeckFailure('stale')))
                .mockReturnValueOnce(of(page(list(5, 1))));
            await open();
            harness.routeDebugElement!.query(element => element.name === 'app-deck-grid').componentInstance.loadNext.emit();
            await settle();
            expect(api.authorDecks).toHaveBeenLastCalledWith(AUTHOR_ID, null);
            expect(all('app-deck-grid h3.title').map(heading => heading.textContent?.trim())).toEqual(['Колода 5']);
        });

        it('says that the author has no open decks, and sends nobody anywhere', async () => {
            api.authorDecks.mockReturnValue(of(page([], { learningNowTotal: null })));
            await open();
            expect(one('.empty-state')?.textContent?.trim()).toBe('У автора пока нет открытых колод.');
            expect(one('.empty-state a')).toBeNull();
            expect(one('app-deck-grid')).toBeNull();
            expect(one('app-author-header .learning')).toBeNull();
        });

        it('tells the author on their own page that public decks will appear, with a link to «Мои колоды»', async () => {
            status.set('authenticated');
            user.set({ accountId: AUTHOR_ID.toUpperCase() });
            api.authorDecks.mockReturnValue(of(page([])));
            await open();
            expect(one('.empty-state p')?.textContent).toBe('Здесь появятся ваши публичные колоды.');
            expect(one('.empty-state a')?.textContent).toBe('Мои колоды');
            expect(one('.empty-state a')?.getAttribute('href')).toBe('/decks');
        });
    });

    describe('the profile of someone who is not shown', () => {
        it('says «Профиль не найден» for a login Identity has no card for, asks for no decks and links to the Community', async () => {
            await open('/u/nobody.here');
            expect(one('h1')?.textContent).toBe('Профиль не найден');
            expect(one('app-access-screen .message p')?.textContent).toBe('Такого профиля нет, или автор его скрыл.');
            const link = one<HTMLAnchorElement>('app-access-screen a.button')!;
            expect(link.textContent).toBe('В Сообщество');
            expect(link.getAttribute('href')).toBe('/community');
            expect(title()).toBe('Профиль не найден — Mnema');
            expect(api.authorDecks).not.toHaveBeenCalled();
            expect(one('app-author-header')).toBeNull();
            expect(document.activeElement).toBe(one('h1'));
        });

        it('tells the signed-in author that their own profile is hidden, with a way to the settings, instead of «не найден»', async () => {
            status.set('authenticated');
            user.set({ accountId: '30000000-0000-4000-8000-000000000003', profileUsername: 'Hidden.Author', displayName: null });
            await open('/u/hidden.author');
            expect(one('h1')?.textContent).toBe('Ваш профиль скрыт от других');
            expect(one('app-access-screen .message p')?.textContent).toBe('Включите публичный профиль в настройках.');
            const link = one<HTMLAnchorElement>('app-access-screen a.button')!;
            expect(link.getAttribute('href')).toBe('/profile');
            expect(title()).toBe('Профиль скрыт — Mnema');
            expect(api.authorDecks).not.toHaveBeenCalled();
            expect(root().textContent).not.toContain('Профиль не найден');
        });

        it('keeps «Профиль не найден» for another login, for a guest and for another signed-in reader', async () => {
            status.set('authenticated');
            user.set({ accountId: '30000000-0000-4000-8000-000000000003', profileUsername: 'someone.else', displayName: null });
            await open('/u/nobody.here');
            expect(one('h1')?.textContent).toBe('Профиль не найден');
            status.set('anonymous');
            user.set(null);
            await go('/u/hidden.author');
            expect(one('h1')?.textContent).toBe('Профиль не найден');
        });

        it('says the same for a login that cannot exist', async () => {
            authors.byUsername.mockResolvedValue(null);
            await open('/u/%20');
            expect(one('h1')?.textContent).toBe('Профиль не найден');
            expect(api.authorDecks).not.toHaveBeenCalled();
        });

        it('offers «Повторить» when Identity cannot be reached, and reads the card again', async () => {
            authors.byUsername.mockRejectedValueOnce(new Error('offline'));
            await open();
            expect(one('h1')?.textContent).toBe('Не удалось открыть профиль');
            expect(api.authorDecks).not.toHaveBeenCalled();
            one<HTMLButtonElement>('app-access-screen button')!.click();
            await settle();
            expect(one('h1')?.textContent).toBe('Анна Ковалёва');
            expect(api.authorDecks).toHaveBeenCalledOnce();
        });
    });

    describe('failures of the decks', () => {
        const fails = (failure: PublicDeckFailure): Observable<never> => throwError(() => failure);

        it('keeps the header and says calmly that the decks come when the Community opens (the catalogue is switched off, 404)', async () => {
            api.authorDecks.mockReturnValue(fails(new PublicDeckFailure('not-found')));
            await open();
            expect(one('h1')?.textContent).toBe('Анна Ковалёва');
            expect(one('section.decks .calm')?.textContent).toBe('Колоды появятся здесь, когда откроется Сообщество.');
            expect(one('section.decks .notice')).toBeNull();
            expect(one('section.decks h2')?.textContent).toBe('Колоды автора');
        });

        it('asks to wait on 429 and to retry on 503, and «Повторить» reads the list again without touching the card', async () => {
            api.authorDecks.mockReturnValueOnce(fails(new PublicDeckFailure('rate-limited', 37))).mockReturnValueOnce(fails(new PublicDeckFailure('busy', 1)))
                .mockReturnValueOnce(fails(new PublicDeckFailure('unavailable'))).mockReturnValueOnce(of(page(list(1, 1))));
            await open();
            expect(one('section.decks .notice[role=alert] p')?.textContent).toBe('Слишком много запросов. Подождите 37 с и повторите.');
            const retry = () => { one<HTMLButtonElement>('section.decks .notice button')!.click(); return settle(); };
            expect(one('#author-decks-title')?.getAttribute('tabindex')).toBe('-1');
            await retry();
            expect(document.activeElement).toBe(one('#author-decks-title'));
            expect(one('section.decks .notice p')?.textContent).toBe('Сейчас много читателей. Повторите через секунду.');
            await retry();
            expect(one('section.decks .notice p')?.textContent).toBe('Не удалось загрузить колоды. Проверьте соединение и повторите.');
            await retry();
            expect(one('section.decks .notice')).toBeNull();
            expect(all('app-deck-grid app-public-deck-card')).toHaveLength(1);
            expect(authors.byUsername).toHaveBeenCalledOnce();
        });

        it('announces the load in a region that is in the DOM before its text', async () => {
            const pending = new Subject<AuthorDeckPage>();
            api.authorDecks.mockReturnValue(pending);
            await open();
            expect(one('section.decks .status[role=status]')?.textContent).toBe('Загружаем колоды…');
            pending.next(page(list(1, 1)));
            pending.complete();
            await settle();
            expect(one('section.decks .status[role=status]')?.textContent).toBe('');
        });
    });

    describe('the menus', () => {
        it('offers «Пожаловаться» on a profile and on decks that are not mine, and only «Поделиться» on mine', async () => {
            status.set('authenticated');
            user.set({ accountId: OTHER_READER });
            await open();
            expect(menuRows(one('app-author-header')!)).toEqual(['Поделиться', 'Пожаловаться']);
            expect(menuRows(all('app-deck-grid app-public-deck-card')[0])).toEqual(['Поделиться', 'Пожаловаться']);
        });

        it('has no complaint on the own profile or the own decks, and says whose page it is', async () => {
            status.set('authenticated');
            user.set({ accountId: AUTHOR_ID });
            await open();
            expect(menuRows(one('app-author-header')!)).toEqual(['Поделиться']);
            expect(menuRows(all('app-deck-grid app-public-deck-card')[0])).toEqual(['Поделиться']);
            expect(one('app-author-header .self')?.textContent).toContain('Так ваш профиль видят другие');
        });

        it('sends a guest who chooses «Пожаловаться» to sign in and back to this very page', async () => {
            await open();
            const header = one('app-author-header')!;
            menuRows(header);
            choose(header, 'Пожаловаться');
            await settle();
            expect(router().url).toBe('/login?returnUrl=%2Fu%2Fanna.k');
            expect(complaints.fileProfile).not.toHaveBeenCalled();
        });

        it('sends a guest to sign in for a deck too', async () => {
            await open();
            const card = one('app-deck-grid app-public-deck-card')!;
            menuRows(card);
            choose(card, 'Пожаловаться');
            await settle();
            expect(router().url).toBe('/login?returnUrl=%2Fu%2Fanna.k');
        });
    });

    describe('the complaint', () => {
        beforeEach(async () => {
            status.set('authenticated');
            user.set({ accountId: OTHER_READER });
            await open();
        });

        it('opens the dialog for the profile of a signed-in reader, named by the login', async () => {
            const header = one('app-author-header')!;
            menuRows(header);
            choose(header, 'Пожаловаться');
            await settle();
            expect(one('app-complaint-dialog h2')?.textContent).toBe('Пожаловаться на профиль');
            expect(one('app-complaint-dialog .subject')?.textContent).toBe('@anna.k');
        });

        it('opens it for a deck, named by its title', async () => {
            const card = all('app-deck-grid app-public-deck-card')[1];
            menuRows(card);
            choose(card, 'Пожаловаться');
            await settle();
            expect(one('app-complaint-dialog h2')?.textContent).toBe('Пожаловаться на колоду');
            expect(one('app-complaint-dialog .subject')?.textContent).toBe('Колода 2');
        });

        function dialog() { return harness.routeDebugElement!.query(element => element.name === 'app-complaint-dialog'); }
        async function openProfileComplaint(): Promise<void> {
            const header = one('app-author-header')!;
            menuRows(header);
            choose(header, 'Пожаловаться');
            await settle();
        }

        it('closes after the complaint is filed, and reads nothing again', async () => {
            await openProfileComplaint();
            dialog().componentInstance.filed.emit();
            await settle();
            expect(one('app-complaint-dialog')).toBeNull();
            expect(authors.byUsername).toHaveBeenCalledOnce();
            expect(api.authorDecks).toHaveBeenCalledOnce();
        });

        it('closes on dismissal', async () => {
            await openProfileComplaint();
            dialog().componentInstance.dismissed.emit();
            await settle();
            expect(one('app-complaint-dialog')).toBeNull();
        });

        it('reads the card again when the dialog finds the profile gone (404), and shows «Профиль не найден» if it is', async () => {
            await openProfileComplaint();
            authors.byUsername.mockResolvedValue(null);
            dialog().componentInstance.gone.emit();
            await settle();
            expect(one('app-complaint-dialog')).toBeNull();
            expect(authors.byUsername).toHaveBeenCalledTimes(2);
            expect(one('h1')?.textContent).toBe('Профиль не найден');
        });

        it('reads the decks again, and only them, when the dialog finds a deck gone', async () => {
            const card = all('app-deck-grid app-public-deck-card')[0];
            menuRows(card);
            choose(card, 'Пожаловаться');
            await settle();
            dialog().componentInstance.gone.emit();
            await settle();
            expect(authors.byUsername).toHaveBeenCalledOnce();
            expect(api.authorDecks).toHaveBeenCalledTimes(2);
            expect(document.activeElement).toBe(one('#author-decks-title'));
        });
    });

    it('reads the other profile when the address moves from one login to another, and only the latest answer renders', async () => {
        const slow: { resolve: (card: AuthorCard | null) => void } = { resolve: () => undefined };
        authors.byUsername.mockImplementation((name: string) => name === 'slow'
            ? new Promise<AuthorCard | null>(resolve => { slow.resolve = resolve; })
            : Promise.resolve({ ...AUTHOR, profileUsername: name, displayName: null }));
        await open('/u/first');
        expect(one('h1')?.textContent).toBe('@first');
        await go('/u/slow');
        expect(one('.status')?.textContent).toBe('Загружаем профиль…');
        expect(one('h1')).toBeNull();
        await go('/u/second');
        expect(one('h1')?.textContent).toBe('@second');
        slow.resolve({ ...AUTHOR, profileUsername: 'slow', displayName: null });
        await settle();
        expect(one('h1')?.textContent).toBe('@second');
        expect(title()).toBe('@second — Mnema');
        expect(api.authorDecks).toHaveBeenCalledTimes(2);
    });
});
