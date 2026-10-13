import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { installPopoverShim, removePopoverShim } from '../../testing/popover-shim';
import { AutoLoadComponent } from '../shared/auto-load.component';
import { rearmBookOpening } from '../shared/book-search.component';
import { ShareLinkService } from '../shared/share-link.service';
import { COMMUNITY_CARDS, DEMO_TOPICS, demoCards } from './sg-community-demo';
import { SgCommunityComponent } from './sg-community.component';

describe('SgCommunityComponent', () => {
    let fixture: ReturnType<typeof TestBed.createComponent<SgCommunityComponent>>;
    let root: HTMLElement;

    beforeEach(() => {
        installPopoverShim();
        rearmBookOpening();
        TestBed.configureTestingModule({ providers: [provideRouter([]), { provide: ShareLinkService, useValue: { share: vi.fn() } }] });
        fixture = TestBed.createComponent(SgCommunityComponent);
        fixture.detectChanges();
        fixture.detectChanges();
        root = fixture.nativeElement as HTMLElement;
    });
    afterEach(() => removePopoverShim());

    const specimen = (title: string): HTMLElement =>
        [...root.querySelectorAll<HTMLElement>('.sg-specimen')].find(item => item.querySelector('h3')?.textContent === title)!;
    const press = (label: string): void => {
        [...root.querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent?.trim() === label)!.click();
        fixture.detectChanges();
    };

    it('is the section «Сообщество» with a «когда использовать» note for every specimen', () => {
        expect(root.querySelector('section#community h2')?.textContent).toBe('Сообщество');
        const items = root.querySelectorAll('.sg-specimen');
        expect(items.length).toBe(10);
        items.forEach(item => expect(item.querySelector('.sg-when')?.textContent).toContain('Когда использовать'));
    });

    it('shows every community element as the real component', () => {
        for (const selector of [
            'app-book-search search[aria-label] form input[type=search]', 'app-book-search button[aria-expanded]', 'app-typewriter-hint[aria-hidden=true]',
            'app-catalog-filters button.disclosure', 'app-catalog-filters .chips', 'app-deck-shelf section h2', 'app-deck-grid ul[role=list]',
            'app-community-empty details', 'app-author-header header', 'app-public-deck-card .stamp.solid'
        ]) expect(root.querySelector(selector), selector).not.toBeNull();
    });

    it('draws the fixtures the brief asks for: with and without an author, small and large counts, every medium, a note', () => {
        expect(COMMUNITY_CARDS.some(card => card.author.username === null)).toBe(true);
        expect(COMMUNITY_CARDS.some(card => card.addedCount === null && card.learningNowCount === null)).toBe(true);
        expect(COMMUNITY_CARDS.some(card => (card.addedCount ?? 0) >= 10_000)).toBe(true);
        expect(new Set(COMMUNITY_CARDS.flatMap(card => card.media))).toEqual(new Set(['audio', 'image', 'video']));
        expect(DEMO_TOPICS.some(topic => topic.parentName !== undefined)).toBe(true);
        expect(root.querySelector('app-public-deck-card .stamp.solid')?.textContent).toBe('Доступна вам');
        expect(new Set(demoCards(0, 40).map(card => card.id)).size).toBe(40);
    });

    it('shows the card without the chip of the author next to the ones with it', () => {
        const cards = [...specimen('Карточка с пометкой').querySelectorAll('app-public-deck-card')];
        expect(cards).toHaveLength(3);
        expect(cards[2].classList.contains('sg-no-author')).toBe(true);
        expect(cards[1].querySelector('app-author-chip .login')).not.toBeNull();
        expect(cards[2].querySelector('app-author-chip')).toBeNull();
    });

    it('plays the opening of the first open book only, and plays it again on request', () => {
        const book = (): HTMLElement => specimen('Книга поиска: раскрытая').querySelector('.spread')!;
        expect(book().classList.contains('is-opening')).toBe(true);
        press('Показать раскрытие ещё раз');
        expect(book().classList.contains('is-opening')).toBe(true);
    });

    it('says what a search started with', () => {
        const stage = specimen('Книга поиска: раскрытая');
        const input = stage.querySelector<HTMLInputElement>('input')!;
        input.value = 'ПДД';
        input.dispatchEvent(new Event('input'));
        stage.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }));
        fixture.detectChanges();
        expect(stage.querySelector('[role=status]')?.textContent).toContain('«ПДД»');
    });

    it('writes the filters into an address the page could keep', () => {
        const stage = specimen('Фильтры и сортировка');
        const addresses = [...stage.querySelectorAll('.sg-address code')].map(code => code.textContent);
        expect(addresses[0]).toBe('/community');
        expect(addresses[1]).toBe('/community?topic=spanish&learn=es&level=B1&size=month&media=audio&mechanics=listening&mechanics=order&fresh=true&sort=new');
        const reset = [...stage.querySelectorAll<HTMLButtonElement>('.active > button')].find(button => button.textContent?.trim() === 'Сбросить всё')!;
        reset.click();
        fixture.detectChanges();
        expect([...stage.querySelectorAll('.sg-address code')].map(code => code.textContent)[1]).toBe('/community?sort=new');
    });

    describe('the grid specimen', () => {
        beforeEach(() => vi.useFakeTimers());
        afterEach(() => vi.useRealTimers());

        const grid = (): HTMLElement => specimen('Выдача плитками');
        const cards = (): number => grid().querySelectorAll('li.cell').length;
        const load = (): AutoLoadComponent => fixture.debugElement.query(el => el.name === 'app-auto-load' && grid().contains(el.nativeElement)).componentInstance;

        it('starts with one page and the announcement of the count', () => {
            expect(cards()).toBe(8);
            expect(grid().querySelector('.status')?.textContent?.trim()).toBe('Нашли 37 колод');
        });

        it('loads the next page when asked, and does not announce again', () => {
            const line = grid().querySelector('.status p');
            load().loadNext.emit();
            fixture.detectChanges();
            vi.advanceTimersByTime(300);
            fixture.detectChanges();
            expect(cards()).toBe(16);
            expect(grid().querySelector('.status p')).toBe(line);
        });

        it('keeps the cards and offers a retry after a failed page', () => {
            press('Сымитировать сбой следующей порции');
            load().loadNext.emit();
            fixture.detectChanges();
            vi.advanceTimersByTime(300);
            fixture.detectChanges();
            expect(cards()).toBe(8);
            expect(grid().querySelector('[role=alert]')?.textContent).toContain('Пример сетевого сбоя');
        });

        it('stops asking at the end of the list', () => {
            for (let page = 0; page < 6; page++) {
                load().loadNext.emit();
                fixture.detectChanges();
                vi.advanceTimersByTime(300);
                fixture.detectChanges();
            }
            expect(cards()).toBe(40);
            expect(load().continuation()).toBeNull();
        });

        it('starts a new search with the count of that search, and an empty one with the sentence', () => {
            press('Нашли больше 200');
            expect(grid().querySelector('.status')?.textContent?.trim()).toBe('Нашли больше 200 колод');
            expect(cards()).toBe(8);
            press('Ничего не нашли');
            expect(grid().querySelector('.status')?.textContent?.trim()).toBe('Ничего не нашли. Попробуйте другое слово или уберите фильтры.');
            expect(cards()).toBe(0);
            press('Новый поиск: нашли 37');
            expect(cards()).toBe(8);
        });
    });
});
