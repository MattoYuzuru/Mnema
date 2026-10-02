import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';

import metadataFixture from '../../../../../contracts/decks/metadata.json';
import { OwnDeck } from './own-deck.models';
import { DeckDetailState, DeckMutationState, OwnDecksStore } from './own-decks.store';
import { OwnDeckDetailPageComponent } from './own-deck-detail-page.component';
import { OwnDeckRecoveryService } from './own-deck-recovery.service';
import { AuthoringApiService } from '../authoring/authoring-api.service';
import { OwnDecksApiService } from './own-decks-api.service';
import { spyObj, type SpyObj } from '../../../testing/mocks';

describe('OwnDeckDetailPageComponent', () => {
    let fixture: ComponentFixture<OwnDeckDetailPageComponent>;
    let store: SpyObj<OwnDecksStore>;
    let recovery: SpyObj<OwnDeckRecoveryService>;
    let decksApi: SpyObj<OwnDecksApiService>;
    const deck = metadataFixture.detail as unknown as OwnDeck;
    const detail = signal<DeckDetailState>({ phase: 'ready', deckId: deck.deckId, deck, failure: null });
    const mutation = signal<DeckMutationState>({ phase: 'idle' });

    beforeEach(async () => {
        detail.set({ phase: 'ready', deckId: deck.deckId, deck, failure: null });
        mutation.set({ phase: 'idle' });
        store = spyObj<OwnDecksStore>({
            openDeck: vi.fn().mockName("OwnDecksStore.openDeck"),
            retryDetail: vi.fn().mockName("OwnDecksStore.retryDetail"),
            startSave: vi.fn().mockName("OwnDecksStore.startSave"),
            retryMutation: vi.fn().mockName("OwnDecksStore.retryMutation"),
            retryAsNewCommand: vi.fn().mockName("OwnDecksStore.retryAsNewCommand"),
            useServerVersion: vi.fn().mockName("OwnDecksStore.useServerVersion"),
            reapplyConflict: vi.fn().mockName("OwnDecksStore.reapplyConflict"),
            clearMutation: vi.fn().mockName("OwnDecksStore.clearMutation"),
            recoverMutation: vi.fn().mockName("OwnDecksStore.recoverMutation")
        });
        recovery = spyObj<OwnDeckRecoveryService>({
            restore: vi.fn().mockName("OwnDeckRecoveryService.restore"),
            save: vi.fn().mockName("OwnDeckRecoveryService.save"),
            clear: vi.fn().mockName("OwnDeckRecoveryService.clear")
        });
        recovery.restore.mockReturnValue(null);
        const authoring = {
            listDeckCaptures: vi.fn().mockName("AuthoringApiService.listDeckCaptures")
        };
        authoring.listDeckCaptures.mockReturnValue(of({ items: [], nextCursor: null, total: 2 }));
        decksApi = spyObj<OwnDecksApiService>({
            delete: vi.fn().mockName("OwnDecksApiService.delete")
        });
        Object.defineProperty(store, 'detailState', { value: detail.asReadonly() });
        Object.defineProperty(store, 'mutationState', { value: mutation.asReadonly() });
        await TestBed.configureTestingModule({
            imports: [OwnDeckDetailPageComponent],
            providers: [
                provideRouter([{ path: '**', children: [] }]),
                { provide: OwnDeckRecoveryService, useValue: recovery },
                { provide: AuthoringApiService, useValue: authoring },
                { provide: OwnDecksApiService, useValue: decksApi },
                { provide: ActivatedRoute, useValue: { paramMap: of(convertToParamMap({ deckId: deck.deckId })) } }
            ]
        }).overrideComponent(OwnDeckDetailPageComponent, {
            set: { providers: [{ provide: OwnDecksStore, useValue: store }] }
        }).compileComponents();
        fixture = TestBed.createComponent(OwnDeckDetailPageComponent);
        fixture.detectChanges();
    });

    it('opens the route identity and saves exact edited metadata against the loaded deck', () => {
        expect(store.openDeck).toHaveBeenCalledTimes(1);
        expect(store.openDeck).toHaveBeenCalledWith(deck.deckId);
        fixture.componentInstance.form.setValue({ title: '  Точное имя  ', description: 'строка 1\nстрока 2' });
        fixture.componentInstance.save(deck);

        expect(store.startSave).toHaveBeenCalledTimes(1);

        expect(store.startSave).toHaveBeenCalledWith(deck, {
            title: '  Точное имя  ', description: 'строка 1\nстрока 2'
        });
        expect(recovery.save).toHaveBeenCalledWith({ operation: 'save', deckId: deck.deckId }, { title: '  Точное имя  ', description: 'строка 1\nстрока 2' }, null);
    });

    it('shows both explicit 412 choices and keeps the local draft in the form', () => {
        fixture.componentInstance.form.setValue({ title: 'Мой ввод', description: 'мой текст' });
        fixture.componentInstance.syncDraft();
        mutation.set({
            phase: 'conflict',
            pending: {
                operation: 'save', deckId: deck.deckId, expectedVersion: deck.rowVersion,
                command: { commandId: '123e4567-e89b-42d3-a456-426614174000', metadata: { title: 'Мой ввод', description: 'мой текст' } }
            },
            latest: { ...deck, metadata: { title: 'Версия сервера', description: 'другой текст' } }
        });
        fixture.detectChanges();

        const root = fixture.nativeElement as HTMLElement;
        expect(root.textContent).toContain('Оставить текущую версию');
        expect(root.textContent).toContain('Применить мой прежний ввод поверх неё');
        expect(fixture.componentInstance.form.getRawValue()).toEqual({ title: 'Мой ввод', description: 'мой текст' });
        expect(root.querySelector<HTMLTextAreaElement>('#detail-title')?.readOnly).toBe(true);
    });

    it('makes an unknown-outcome save draft read-only until exact-command reconciliation', () => {
        mutation.set({
            phase: 'error',
            pending: {
                operation: 'save', deckId: deck.deckId, expectedVersion: deck.rowVersion,
                command: {
                    commandId: '123e4567-e89b-42d3-a456-426614174000',
                    metadata: { title: 'Первый ввод', description: 'не терять' }
                }
            },
            failure: { kind: 'network', status: 0, code: null },
            replayRefreshFailed: false,
            replayDeckId: null,
            conflictRefreshFailed: false
        });
        fixture.detectChanges();

        expect((fixture.nativeElement as HTMLElement)
            .querySelector<HTMLTextAreaElement>('#detail-title')?.readOnly).toBe(true);
    });

    it('makes Study the primary action inside the selected deck', () => {
        const root = fixture.nativeElement as HTMLElement;
        const study = [...root.querySelectorAll<HTMLAnchorElement>('a')].find(link => link.textContent?.trim() === 'Учить');
        expect(study).toBeDefined();
        expect(study?.classList).toContain('primary');
        expect(study?.getAttribute('href')).toBe(`/decks/${deck.deckId}/study`);
        expect(root.querySelector('.capture-badge')?.textContent?.trim()).toBe('2');
    });

    it('deletes only the opened deck after the hold control confirms', () => {
        decksApi.delete.mockReturnValue(of(void 0));
        fixture.componentInstance.deleteDeck(deck);

        expect(decksApi.delete).toHaveBeenCalledTimes(1);

        expect(decksApi.delete).toHaveBeenCalledWith(deck);
        expect(recovery.clear).toHaveBeenCalledWith({ operation: 'save', deckId: deck.deckId });
    });

    it('keeps the deck open when its delete request fails', () => {
        decksApi.delete.mockReturnValue(throwError(() => new Error('offline')));
        fixture.componentInstance.deleteDeck(deck);
        fixture.detectChanges();

        expect(fixture.componentInstance.deleting()).toBe(false);
        expect(fixture.componentInstance.deleteError()).toContain('Не удалось удалить колоду');
        expect((fixture.nativeElement as HTMLElement).textContent).toContain(deck.metadata.title);
    });
});
