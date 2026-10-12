import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';

import forksFixture from '../../../../../contracts/decks/forks.json';
import metadataFixture from '../../../../../contracts/decks/metadata.json';
import type { DeckFork } from './fork/fork.models';
import { OwnDeck } from './own-deck.models';
import { DeckListState, OwnDecksStore } from './own-decks.store';
import { OwnDecksListPageComponent } from './own-decks-list-page.component';
import { spyObj, type SpyObj } from '../../../testing/mocks';
import { PromoPopupService } from '../promo/promo-popup.service';
import { COMMUNITY_LAUNCH } from '../../shared/community-launch';
import { CommunityApiService } from '../community/community-api.service';

describe('OwnDecksListPageComponent', () => {
    beforeEach(() => {
        vi.useFakeTimers();
    });
    afterEach(() => {
        vi.useRealTimers();
    });
    let fixture: ComponentFixture<OwnDecksListPageComponent>;
    let store: SpyObj<OwnDecksStore>;
    let promoPopup: SpyObj<PromoPopupService>;
    const state = signal<DeckListState>({
        phase: 'ready', items: [metadataFixture.detail as unknown as OwnDeck], nextCursor: null,
        operation: null, failure: null
    });

    beforeEach(async () => {
        state.set({
            phase: 'ready', items: [metadataFixture.detail as unknown as OwnDeck], nextCursor: null,
            operation: null, failure: null
        });
        store = spyObj<OwnDecksStore>({
            loadList: vi.fn().mockName("OwnDecksStore.loadList"),
            loadMore: vi.fn().mockName("OwnDecksStore.loadMore"),
            retryList: vi.fn().mockName("OwnDecksStore.retryList"),
            refreshVisibleList: vi.fn().mockName("OwnDecksStore.refreshVisibleList")
        });
        Object.defineProperty(store, 'listState', { value: state.asReadonly() });
        promoPopup = spyObj<PromoPopupService>({ request: vi.fn().mockName('PromoPopupService.request').mockResolvedValue(undefined) });
        await TestBed.configureTestingModule({
            imports: [OwnDecksListPageComponent],
            providers: [provideRouter([]), { provide: PromoPopupService, useValue: promoPopup }]
        }).overrideComponent(OwnDecksListPageComponent, {
            set: { providers: [{ provide: OwnDecksStore, useValue: store }] }
        }).compileComponents();
        fixture = TestBed.createComponent(OwnDecksListPageComponent);
        fixture.detectChanges();
    });

    it('marks a deck that is not private with its real level, in words, and leaves a private one unmarked', () => {
        const root = fixture.nativeElement as HTMLElement;
        expect(root.querySelector('app-access-level')).toBeNull();
        const base = metadataFixture.detail as unknown as OwnDeck;
        state.set({
            phase: 'ready', nextCursor: null, operation: null, failure: null,
            items: [{ ...base, visibility: 'link' }, { ...base, deckId: '33333333-3333-4333-8333-333333333333', visibility: 'public' }, { ...base, deckId: '44444444-4444-4444-8444-444444444444' }]
        });
        fixture.detectChanges();
        expect([...root.querySelectorAll('app-access-level')].map(mark => mark.textContent!.trim())).toEqual(['По ссылке', 'Публичная']);
    });

    it('marks a copy with «Копия» and says its state in words: preparing, failed, source closed; an ordinary row has no mark', () => {
        const root = fixture.nativeElement as HTMLElement;
        const base = metadataFixture.detail as unknown as OwnDeck;
        const fork = forksFixture.fork as unknown as Record<'preparing' | 'failed' | 'ready' | 'sourceClosed' | 'detached', DeckFork>;
        const ids = ['33333333-3333-4333-8333-333333333333', '44444444-4444-4444-8444-444444444444', '55555555-5555-4555-8555-555555555555',
            '66666666-6666-4666-8666-666666666666', '77777777-7777-4777-8777-777777777777'];
        state.set({
            phase: 'ready', nextCursor: null, operation: null, failure: null,
            items: [base, { ...base, deckId: ids[0], fork: fork.preparing }, { ...base, deckId: ids[1], fork: fork.failed }, { ...base, deckId: ids[2], fork: fork.ready },
                { ...base, deckId: ids[3], fork: fork.sourceClosed }, { ...base, deckId: ids[4], fork: fork.detached }]
        });
        fixture.detectChanges();
        const words = (mark: Element) => {
            const copy = mark.cloneNode(true) as HTMLElement;
            copy.querySelectorAll('[aria-hidden=true]').forEach(hidden => hidden.remove());
            return copy.textContent!.replace(/\s+/gu, ' ').trim();
        };
        const rows = [...root.querySelectorAll('.deck-list li')];
        expect(rows.map(row => row.querySelector('app-copy-mark')).map(mark => mark === null ? null : words(mark))).toEqual([
            null, 'Копия: Готовим колоду…', 'Копия: Не удалось подготовить колоду', 'Копия', 'Копия: Источник больше недоступен', 'Копия'
        ]);
        // A copy still being prepared is a link to its hub like any other row (the hub explains), never to Study.
        expect(rows[1].querySelector('a')!.getAttribute('href')).toBe(`/decks/${ids[0]}`);
        expect(root.textContent).not.toContain('Учить');
    });

    it('puts «Есть обновление» on the card of a copy whose author published something, in words and inside the link to its hub', () => {
        const root = fixture.nativeElement as HTMLElement;
        const base = metadataFixture.detail as unknown as OwnDeck;
        const fork = forksFixture.fork as unknown as Record<'ready' | 'updateAvailable' | 'detached' | 'sourceClosed', DeckFork>;
        const ids = ['33333333-3333-4333-8333-333333333333', '44444444-4444-4444-8444-444444444444', '55555555-5555-4555-8555-555555555555'];
        state.set({
            phase: 'ready', nextCursor: null, operation: null, failure: null,
            items: [base, { ...base, deckId: ids[0], fork: fork.ready }, { ...base, deckId: ids[1], fork: fork.updateAvailable },
                { ...base, deckId: ids[2], fork: { ...fork.detached, updateAvailable: false } }]
        });
        fixture.detectChanges();
        const rows = [...root.querySelectorAll('.deck-list li')];
        expect(rows.map(row => row.querySelector('app-update-badge')?.textContent?.trim() ?? null)).toEqual([null, null, 'Есть обновление', null]);
        // Words inside the card's own link: read in place with the title, never a bare dot.
        expect(rows[2]!.querySelector('a.deck-row app-update-badge .stamp')!.textContent!.trim()).toBe('Есть обновление');
        expect(rows[2]!.querySelector('a')!.getAttribute('href')).toBe(`/decks/${ids[1]}`);
        expect(root.querySelectorAll('app-update-badge')).toHaveLength(1);
    });

    it('loads once and exposes an exact private deck without invented actions', () => {
        const root = fixture.nativeElement as HTMLElement;

        expect(store.loadList).toHaveBeenCalledTimes(1);
        expect(root.querySelector('h1')?.textContent).toBe('Мои колоды');
        const expected = metadataFixture.detail as unknown as OwnDeck;
        expect(root.querySelector('.deck-copy strong')?.textContent).toBe(expected.metadata.title);
        expect(root.textContent).not.toContain('Учиться');
        expect(root.querySelector<HTMLAnchorElement>('.deck-row')?.getAttribute('href'))
            .toBe(`/decks/${expected.deckId}`);
        expect(root.textContent).not.toContain('Обновить список');
    });

    it('asks for the promo popup once the list has loaded, and not while it is loading or failed', async () => {
        expect(promoPopup.request).toHaveBeenCalledTimes(1);

        state.set({ phase: 'loading', items: [], nextCursor: null, operation: 'replace', failure: null });
        fixture.detectChanges();
        await fixture.whenStable();
        state.set({ phase: 'error', items: [], nextCursor: null, operation: 'replace', failure: { kind: 'network', status: 0, code: null } });
        fixture.detectChanges();
        await fixture.whenStable();
        expect(promoPopup.request).toHaveBeenCalledTimes(1);
    });

    it('keeps loaded rows visible when loading another page fails', () => {
        state.set({
            ...state(), phase: 'error', operation: 'next', nextCursor: 'opaque',
            failure: { kind: 'network', status: 0, code: null }
        });
        fixture.detectChanges();

        expect(fixture.nativeElement.querySelectorAll('.deck-row').length).toBe(1);
        expect(fixture.nativeElement.textContent).toContain('Нет связи');
    });

    it('rechecks a visible library on focus and on its bounded timer, then stops on teardown', async () => {
        window.dispatchEvent(new Event('focus'));
        expect(store.refreshVisibleList).toHaveBeenCalledTimes(1);
        await vi.advanceTimersByTimeAsync(10000);
        expect(store.refreshVisibleList).toHaveBeenCalledTimes(2);
        fixture.destroy();
        await vi.advanceTimersByTimeAsync(20000);
        expect(store.refreshVisibleList).toHaveBeenCalledTimes(2);
    });

    it('pauses background checks and rechecks as soon as the library becomes visible', async () => {
        const visibility = vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('hidden');
        document.dispatchEvent(new Event('visibilitychange'));
        await vi.advanceTimersByTimeAsync(20000);
        expect(store.refreshVisibleList).not.toHaveBeenCalled();

        visibility.mockReturnValue('visible');
        document.dispatchEvent(new Event('visibilitychange'));
        expect(store.refreshVisibleList).toHaveBeenCalledTimes(1);
        await vi.advanceTimersByTimeAsync(10000);
        expect(store.refreshVisibleList).toHaveBeenCalledTimes(2);
    });
});

describe('OwnDecksListPageComponent link to the community', () => {
    const empty: DeckListState = { phase: 'ready', items: [], nextCursor: null, operation: null, failure: null };
    let facets: ReturnType<typeof vi.fn>;
    let state: ReturnType<typeof signal<DeckListState>>;

    function create(launched: boolean, items: readonly OwnDeck[] = []): ComponentFixture<OwnDecksListPageComponent> {
        state = signal<DeckListState>({ ...empty, items });
        const store = spyObj<OwnDecksStore>({ loadList: vi.fn(), loadMore: vi.fn(), retryList: vi.fn(), refreshVisibleList: vi.fn() });
        Object.defineProperty(store, 'listState', { value: state.asReadonly() });
        TestBed.configureTestingModule({
            imports: [OwnDecksListPageComponent],
            providers: [
                provideRouter([]),
                { provide: PromoPopupService, useValue: { request: vi.fn().mockResolvedValue(undefined) } },
                { provide: COMMUNITY_LAUNCH, useValue: launched },
                { provide: CommunityApiService, useValue: { facets } }
            ]
        }).overrideComponent(OwnDecksListPageComponent, { set: { providers: [{ provide: OwnDecksStore, useValue: store }] } });
        const fixture = TestBed.createComponent(OwnDecksListPageComponent);
        fixture.detectChanges();
        return fixture;
    }
    const link = (fixture: ComponentFixture<OwnDecksListPageComponent>) =>
        (fixture.nativeElement as HTMLElement).querySelector<HTMLAnchorElement>('a[href="/community"]');

    beforeEach(() => {
        vi.useFakeTimers();
        facets = vi.fn().mockReturnValue(of({ listedCount: 3, topics: [], languages: [], levels: [] }));
    });
    afterEach(() => vi.useRealTimers());

    it('asks nothing and shows no link while the catalogue is closed to readers', async () => {
        const fixture = create(false);
        await fixture.whenStable();
        fixture.detectChanges();
        expect(facets).not.toHaveBeenCalled();
        expect(link(fixture)).toBeNull();
        expect(fixture.nativeElement.textContent).toContain('Первая страница пока чиста');
    });

    it('points the empty list to the catalogue when it is open and has decks, after one look', async () => {
        const fixture = create(true);
        await fixture.whenStable();
        fixture.detectChanges();
        expect(facets).toHaveBeenCalledTimes(1);
        expect(link(fixture)?.textContent).toBe('Посмотреть колоды сообщества');
    });

    it('leaves the link out when the open catalogue is empty, when the look fails, and when the list is not empty', async () => {
        facets.mockReturnValue(of({ listedCount: 0, topics: [], languages: [], levels: [] }));
        let fixture = create(true);
        await fixture.whenStable();
        fixture.detectChanges();
        expect(link(fixture)).toBeNull();
        TestBed.resetTestingModule();

        facets.mockReturnValue(throwError(() => new Error('offline')));
        fixture = create(true);
        await fixture.whenStable();
        fixture.detectChanges();
        expect(link(fixture)).toBeNull();
        TestBed.resetTestingModule();

        facets.mockReturnValue(of({ listedCount: 3, topics: [], languages: [], levels: [] }));
        facets.mockClear();
        fixture = create(true, [metadataFixture.detail as unknown as OwnDeck]);
        await fixture.whenStable();
        fixture.detectChanges();
        expect(facets).not.toHaveBeenCalled();
        expect(link(fixture)).toBeNull();
    });
});
