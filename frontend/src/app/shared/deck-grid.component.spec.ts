import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { installPopoverShim, removePopoverShim } from '../../testing/popover-shim';
import { AutoLoadComponent } from './auto-load.component';
import { CASCADE, DeckGridComponent, DeckResults, resultsText } from './deck-grid.component';
import { PublicDeckCard } from './public-deck-card';
import { ShareLinkService } from './share-link.service';

const deck = (index: number): PublicDeckCard => ({
    id: `deck-${index}`, title: `Колода ${index}`, description: 'Описание.', topic: 'Языки', language: 'Испанский', author: { username: 'anna.k', avatarSrc: null },
    materialCount: 40, exerciseCount: 80, media: [], addedCount: 3, learningNowCount: 0, updatedAt: '2026-10-03T09:00:00Z', shareUrl: `https://mnema.app/d/${index}`
});
const decks = (from: number, to: number): PublicDeckCard[] => Array.from({ length: to - from }, (_, index) => deck(from + index));

@Component({
    imports: [DeckGridComponent],
    template: `<app-deck-grid [decks]="decks()" [results]="results()" [continuation]="cursor()" [loading]="loading()" [error]="error()"
      [deckLink]="link" (loadNext)="pages = pages + 1" (action)="actions.push($event.id)" />`
})
class HostComponent {
    readonly decks = signal<PublicDeckCard[]>(decks(0, 10));
    readonly results = signal<DeckResults | null>({ key: 1, count: 37, capped: false });
    readonly cursor = signal<string | null>('next');
    readonly loading = signal(false);
    readonly error = signal<string | null>(null);
    link = (card: PublicDeckCard): string[] => ['/d', card.id];
    pages = 0;
    readonly actions: string[] = [];
}

describe('resultsText', () => {
    it('says the count with the right Russian form', () => {
        const text = (count: number, capped = false): string => resultsText({ key: 1, count, capped });
        expect(text(1)).toBe('Нашли 1 колоду');
        expect(text(2)).toBe('Нашли 2 колоды');
        expect(text(5)).toBe('Нашли 5 колод');
        expect(text(11)).toBe('Нашли 11 колод');
        expect(text(21)).toBe('Нашли 21 колоду');
        expect(text(37)).toBe('Нашли 37 колод');
        expect(text(112)).toBe('Нашли 112 колод');
    });

    it('says «больше N» when the server counted only up to a cap', () => {
        expect(resultsText({ key: 1, count: 200, capped: true })).toBe('Нашли больше 200 колод');
        expect(resultsText({ key: 1, count: 1000, capped: true })).toBe('Нашли больше 1000 колод');
        expect(resultsText({ key: 1, count: 201, capped: true })).toBe('Нашли больше 201 колоды');
    });

    it('answers an empty result with a sentence that says what to do, whatever the cap', () => {
        const sentence = 'Ничего не нашли. Попробуйте другое слово или уберите фильтры.';
        expect(resultsText({ key: 1, count: 0, capped: false })).toBe(sentence);
        expect(resultsText({ key: 1, count: 0, capped: true })).toBe(sentence);
    });
});

describe('DeckGridComponent', () => {
    let fixture: ReturnType<typeof TestBed.createComponent<HostComponent>>;
    let host: HostComponent;
    let root: HTMLElement;

    const delays = (): string[] => [...root.querySelectorAll<HTMLElement>('li.cell')].map(cell => cell.style.getPropertyValue('--mn-cascade-delay'));

    beforeEach(() => {
        installPopoverShim();
        TestBed.configureTestingModule({ providers: [provideRouter([]), { provide: ShareLinkService, useValue: { share: vi.fn() } }] });
        fixture = TestBed.createComponent(HostComponent);
        host = fixture.componentInstance;
        fixture.detectChanges();
        fixture.detectChanges();
        root = fixture.nativeElement as HTMLElement;
    });
    afterEach(() => removePopoverShim());

    it('is a list of cards with the shared continuation, and no page or «Показать ещё» buttons', () => {
        const list = root.querySelector('ul.grid')!;
        expect(list.getAttribute('role')).toBe('list');
        expect(list.querySelectorAll(':scope > li')).toHaveLength(10);
        expect(list.querySelector('h3 a')?.getAttribute('href')).toBe('/d/deck-0');
        expect(root.querySelector('app-auto-load')).not.toBeNull();
        const words = [...root.querySelectorAll('button')].map(button => button.textContent ?? '').join(' ');
        expect(words).not.toMatch(/Показать ещё|Следующая|Предыдущая|Далее/u);
        expect(root.querySelectorAll('button[aria-haspopup=menu]')).toHaveLength(10);
    });

    it('passes the cursor, the loading state and the error to auto-load and reports the request for the next page', () => {
        const load = fixture.debugElement.query(el => el.name === 'app-auto-load').componentInstance as AutoLoadComponent;
        expect(load.continuation()).toBe('next');
        host.loading.set(true);
        host.error.set('Нет сети');
        fixture.detectChanges();
        expect(load.loading()).toBe(true);
        expect(load.error()).toBe('Нет сети');
        load.loadNext.emit();
        expect(host.pages).toBe(1);
    });

    it('reports a menu choice with its deck', () => {
        const card = fixture.debugElement.queryAll(el => el.name === 'app-public-deck-card')[2];
        card.componentInstance.action.emit('report');
        expect(host.actions).toEqual(['report']);
    });

    describe('the count of a search', () => {
        const region = (): HTMLElement => root.querySelector<HTMLElement>('.status')!;

        it('lives in a polite status region and is shown as a line', () => {
            expect(region().getAttribute('role')).toBe('status');
            expect(region().hasAttribute('aria-live')).toBe(false);
            expect(region().textContent?.trim()).toBe('Нашли 37 колод');
        });

        it('is present and empty from the first render when there is nothing to say yet', () => {
            TestBed.resetTestingModule();
            TestBed.configureTestingModule({ providers: [provideRouter([]), { provide: ShareLinkService, useValue: { share: vi.fn() } }] });
            const other = TestBed.createComponent(HostComponent);
            other.componentInstance.results.set(null);
            other.detectChanges();
            other.detectChanges();
            const status = (other.nativeElement as HTMLElement).querySelector('.status')!;
            expect(status.getAttribute('role')).toBe('status');
            expect(status.textContent?.trim()).toBe('');
        });

        it('puts the text into a region that is already in the DOM, also for a grid created with results in hand', () => {
            TestBed.resetTestingModule();
            TestBed.configureTestingModule({ providers: [provideRouter([]), { provide: ShareLinkService, useValue: { share: vi.fn() } }] });
            const other = TestBed.createComponent(HostComponent);
            other.componentInstance.results.set({ key: 9, count: 5, capped: false });
            const element = other.nativeElement as HTMLElement;
            const observer = new MutationObserver(() => undefined);
            observer.observe(element, { childList: true, subtree: true });
            other.detectChanges();
            other.detectChanges();
            const added = observer.takeRecords().flatMap(record => [...record.addedNodes]);
            observer.disconnect();
            const status = element.querySelector('.status')!;
            expect(status.getAttribute('role')).toBe('status');
            expect(status.textContent?.trim()).toBe('Нашли 5 колод');
            // The text is added into a region that was already there: no added node brings the region along with it.
            expect(added.some(node => node === status || node.contains(status))).toBe(false);
            expect(added.some(node => node.nodeName === 'P' && status.contains(node))).toBe(true);
        });

        it('is announced once per search: appended pages leave the line untouched', () => {
            const before = region().querySelector('p')!;
            host.decks.update(list => [...list, ...decks(10, 20)]);
            host.cursor.set(null);
            fixture.detectChanges();
            expect(region().querySelector('p')).toBe(before);
            expect(region().textContent?.trim()).toBe('Нашли 37 колод');
        });

        it('is announced again for a new search even when the numbers are the same', () => {
            const before = region().querySelector('p')!;
            host.results.set(null);
            fixture.detectChanges();
            expect(region().querySelector('p')).toBeNull();
            host.results.set({ key: 2, count: 37, capped: false });
            fixture.detectChanges();
            expect(region().querySelector('p')).not.toBe(before);
            expect(region().textContent?.trim()).toBe('Нашли 37 колод');
        });

        it('says «Нашли больше 200 колод» and the empty sentence', () => {
            host.results.set({ key: 3, count: 200, capped: true });
            fixture.detectChanges();
            expect(region().textContent?.trim()).toBe('Нашли больше 200 колод');
            host.results.set({ key: 4, count: 0, capped: false });
            host.decks.set([]);
            fixture.detectChanges();
            expect(region().textContent?.trim()).toBe('Ничего не нашли. Попробуйте другое слово или уберите фильтры.');
            expect(root.querySelectorAll('li.cell')).toHaveLength(0);
        });
    });

    describe('the cascade', () => {
        it('delays the new cards by 40 ms each and the first eight at most', () => {
            expect(CASCADE.stepMs).toBeLessThanOrEqual(40);
            expect(CASCADE.cards).toBe(8);
            expect(delays()).toEqual(['0ms', '40ms', '80ms', '120ms', '160ms', '200ms', '240ms', '280ms', '280ms', '280ms']);
        });

        it('starts the cascade again from zero for an appended page and leaves the cards that are there alone', () => {
            const first = root.querySelector('li.cell')!;
            host.decks.update(list => [...list, ...decks(10, 13)]);
            fixture.detectChanges();
            expect(delays().slice(0, 10)).toEqual(['0ms', '0ms', '0ms', '0ms', '0ms', '0ms', '0ms', '0ms', '0ms', '0ms']);
            expect(delays().slice(10)).toEqual(['0ms', '40ms', '80ms']);
            expect(root.querySelector('li.cell')).toBe(first);
        });

        it('cascades a whole new search', () => {
            host.decks.set(decks(100, 103));
            fixture.detectChanges();
            expect(delays()).toEqual(['0ms', '40ms', '80ms']);
        });
    });
});

@Component({
    imports: [DeckGridComponent],
    template: `<app-deck-grid [decks]="decks" [menuFor]="menuFor" />`
})
class MenuHostComponent {
    readonly decks = decks(0, 3);
    menuFor = (card: PublicDeckCard) => card.id === 'deck-1' ? [{ id: 'share', label: 'Поделиться' }] : [{ id: 'share', label: 'Поделиться' }, { id: 'complain', label: 'Пожаловаться' }];
}

describe('DeckGridComponent menu per card', () => {
    it('asks for the rows of each card\'s «⋯» menu when `menuFor` is given, and the shared rows otherwise', () => {
        installPopoverShim();
        TestBed.configureTestingModule({ providers: [provideRouter([]), { provide: ShareLinkService, useValue: { share: vi.fn() } }] });
        const fixture = TestBed.createComponent(MenuHostComponent);
        fixture.detectChanges();
        const rows = [...(fixture.nativeElement as HTMLElement).querySelectorAll('li.cell')]
            .map(cell => [...cell.querySelectorAll('[role=menuitem]')].map(item => item.textContent!.trim()));
        expect(rows).toEqual([['Поделиться', 'Пожаловаться'], ['Поделиться'], ['Поделиться', 'Пожаловаться']]);
        removePopoverShim();
    });
});

@Component({
    imports: [DeckGridComponent],
    template: `<app-deck-grid [decks]="decks" [noteFor]="noteFor" [authorShown]="authorShown" />`
})
class NoteHostComponent {
    readonly decks = decks(0, 3);
    authorShown = false;
    noteFor = (card: PublicDeckCard): string | null => card.id === 'deck-1' ? 'Доступна вам' : null;
}

describe('DeckGridComponent note and author chip per card', () => {
    function mount(): HTMLElement {
        installPopoverShim();
        TestBed.configureTestingModule({ providers: [provideRouter([]), { provide: ShareLinkService, useValue: { share: vi.fn() } }] });
        const fixture = TestBed.createComponent(NoteHostComponent);
        fixture.detectChanges();
        return fixture.nativeElement as HTMLElement;
    }
    afterEach(() => removePopoverShim());

    it('puts the note `noteFor` gives on that card only, and leaves the author chip out when `authorShown` is off', () => {
        const root = mount();
        const notes = [...root.querySelectorAll('li.cell')].map(cell => cell.querySelector('.stamp.solid')?.textContent ?? null);
        expect(notes).toEqual([null, 'Доступна вам', null]);
        expect(root.querySelector('app-author-chip')).toBeNull();
    });
});
