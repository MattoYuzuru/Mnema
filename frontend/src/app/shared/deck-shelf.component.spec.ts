import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { installPopoverShim, removePopoverShim } from '../../testing/popover-shim';
import { PublicDeckCard } from './public-deck-card';
import { DeckShelfComponent, ShelfAction } from './deck-shelf.component';
import { ShareLinkService } from './share-link.service';

const deck = (id: string, title: string): PublicDeckCard => ({
    id, title, description: 'Описание.', topic: 'Языки', language: 'Испанский', author: { username: 'anna.k', avatarSrc: null },
    materialCount: 40, exerciseCount: 80, media: [], addedCount: 3, learningNowCount: 0, updatedAt: '2026-10-03T09:00:00Z', shareUrl: `https://mnema.app/d/${id}`
});

@Component({
    imports: [DeckShelfComponent],
    template: `<app-deck-shelf [title]="title" [subtitle]="subtitle()" [moreLink]="more()" [decks]="decks" [deckLink]="link" (action)="actions.push($event)" />`
})
class HostComponent {
    title = 'Учат сейчас';
    readonly subtitle = signal<string | null>('Колоды, которые открывали на этой неделе');
    readonly more = signal<string | null>('/community');
    decks = [deck('a', 'Падежи'), deck('b', 'Глаголы'), deck('c', 'Артикли'), deck('d', 'Предлоги')];
    link = (card: PublicDeckCard): string[] => ['/d', card.id];
    readonly actions: ShelfAction[] = [];
}

describe('DeckShelfComponent', () => {
    let fixture: ReturnType<typeof TestBed.createComponent<HostComponent>>;
    let root: HTMLElement;

    beforeEach(() => {
        installPopoverShim();
        TestBed.configureTestingModule({ providers: [provideRouter([]), { provide: ShareLinkService, useValue: { share: vi.fn() } }] });
        fixture = TestBed.createComponent(HostComponent);
        fixture.detectChanges();
        root = fixture.nativeElement as HTMLElement;
    });
    afterEach(() => removePopoverShim());

    it('has an h2 for the shelf with the subtitle under it, and is not a region landmark', () => {
        const section = root.querySelector('section')!;
        const heading = section.querySelector('h2')!;
        expect(heading.textContent).toBe('Учат сейчас');
        expect(section.hasAttribute('aria-labelledby')).toBe(false);
        expect(section.hasAttribute('aria-label')).toBe(false);
        expect(root.querySelector('.titles .hint')?.textContent).toBe('Колоды, которые открывали на этой неделе');
    });

    it('lists the cards with h3 titles that link to the decks', () => {
        const list = root.querySelector('ul.row')!;
        expect(list.getAttribute('role')).toBe('list');
        expect(list.querySelectorAll(':scope > li')).toHaveLength(4);
        expect([...list.querySelectorAll('h3')].map(title => title.textContent?.trim())).toEqual(['Падежи', 'Глаголы', 'Артикли', 'Предлоги']);
        expect(list.querySelector('h3 a')?.getAttribute('href')).toBe('/d/a');
    });

    it('has a «Все» link named by the shelf', () => {
        const more = root.querySelector('a.more')!;
        expect(more.textContent).toBe('Все');
        expect(more.getAttribute('aria-label')).toBe('Все колоды: Учат сейчас');
        expect(more.getAttribute('href')).toBe('/community');
    });

    it('draws no link and no subtitle without them', () => {
        fixture.componentInstance.more.set(null);
        fixture.componentInstance.subtitle.set(null);
        fixture.detectChanges();
        expect(root.querySelector('a.more')).toBeNull();
        expect(root.querySelector('.titles .hint')).toBeNull();
    });

    it('keeps the scrolling list out of the tab order: the cards are the stops', () => {
        expect(root.querySelector('ul.row')!.hasAttribute('tabindex')).toBe(false);
        expect(root.querySelectorAll('ul.row a[href], ul.row button').length).toBeGreaterThanOrEqual(8);
    });

    it('reports a menu choice with the deck it belongs to', () => {
        const card = fixture.debugElement.queryAll(el => el.name === 'app-public-deck-card')[1];
        card.componentInstance.action.emit('report');
        expect(fixture.componentInstance.actions).toEqual([{ id: 'report', deck: fixture.componentInstance.decks[1] }]);
    });
});

@Component({
    imports: [DeckShelfComponent],
    template: `<app-deck-shelf title="Новое" [moreLink]="['/community']" [moreQueryParams]="query" [decks]="decks" [menuFor]="menuFor" />`
})
class MoreHostComponent {
    query = { topic: 'spanish', sort: 'popular' };
    decks = [deck('a', 'Падежи'), deck('b', 'Глаголы'), deck('c', 'Артикли'), deck('d', 'Предлоги')];
    menuFor = (card: PublicDeckCard) => card.id === 'a' ? [{ id: 'share', label: 'Поделиться' }] : [{ id: 'share', label: 'Поделиться' }, { id: 'complain', label: 'Пожаловаться' }];
}

describe('DeckShelfComponent «Все» query and menu per card', () => {
    beforeEach(() => {
        installPopoverShim();
        TestBed.configureTestingModule({ providers: [provideRouter([]), { provide: ShareLinkService, useValue: { share: vi.fn() } }] });
    });
    afterEach(() => removePopoverShim());

    it('leads «Все» to the route with the shelf\'s own query', () => {
        const fixture = TestBed.createComponent(MoreHostComponent);
        fixture.detectChanges();
        expect((fixture.nativeElement as HTMLElement).querySelector('a.more')?.getAttribute('href')).toBe('/community?topic=spanish&sort=popular');
    });

    it('asks for the rows of each card\'s «⋯» menu when `menuFor` is given', () => {
        const fixture = TestBed.createComponent(MoreHostComponent);
        fixture.detectChanges();
        const rows = [...(fixture.nativeElement as HTMLElement).querySelectorAll('ul.row > li')]
            .map(item => [...item.querySelectorAll('[role=menuitem]')].map(entry => entry.textContent!.trim()));
        expect(rows).toEqual([['Поделиться'], ['Поделиться', 'Пожаловаться'], ['Поделиться', 'Пожаловаться'], ['Поделиться', 'Пожаловаться']]);
    });
});
