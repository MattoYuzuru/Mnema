import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import metadataFixture from '../../../../../contracts/decks/metadata.json';
import { OwnDeck } from './own-deck.models';
import { DeckListState, OwnDecksStore } from './own-decks.store';
import { OwnDecksListPageComponent } from './own-decks-list-page.component';

describe('OwnDecksListPageComponent', () => {
    let fixture: ComponentFixture<OwnDecksListPageComponent>;
    let store: jasmine.SpyObj<OwnDecksStore>;
    const state = signal<DeckListState>({
        phase: 'ready', items: [metadataFixture.detail as unknown as OwnDeck], nextCursor: null,
        operation: null, failure: null
    });

    beforeEach(async () => {
        state.set({
            phase: 'ready', items: [metadataFixture.detail as unknown as OwnDeck], nextCursor: null,
            operation: null, failure: null
        });
        store = jasmine.createSpyObj<OwnDecksStore>('OwnDecksStore', [
            'loadList', 'loadMore', 'loadPrevious', 'retryList'
        ]);
        Object.defineProperty(store, 'listState', { value: state.asReadonly() });
        Object.defineProperty(store, 'canGoBack', { value: signal(false).asReadonly() });
        await TestBed.configureTestingModule({
            imports: [OwnDecksListPageComponent],
            providers: [provideRouter([])]
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
    });

    it('keeps loaded rows visible when loading another page fails', () => {
        state.set({
            ...state(), phase: 'error', operation: 'next', nextCursor: 'opaque',
            failure: { kind: 'network', status: 0, code: null }
        });
        fixture.detectChanges();

        expect(fixture.nativeElement.querySelectorAll('.deck-row').length).toBe(1);
        expect(fixture.nativeElement.textContent).toContain('Ответ сервера не получен');
    });
});
