import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import metadataFixture from '../../../../../contracts/decks/metadata.json';
import { OwnDeck } from './own-deck.models';
import { DeckListState, OwnDecksStore } from './own-decks.store';
import { OwnDecksListPageComponent } from './own-decks-list-page.component';
import { spyObj, type SpyObj } from '../../../testing/mocks';
import { PromoPopupService } from '../promo/promo-popup.service';

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
