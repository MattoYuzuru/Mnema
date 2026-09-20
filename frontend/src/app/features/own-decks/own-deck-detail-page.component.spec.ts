import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';

import metadataFixture from '../../../../../contracts/decks/metadata.json';
import { OwnDeck } from './own-deck.models';
import { DeckDetailState, DeckMutationState, OwnDecksStore } from './own-decks.store';
import { OwnDeckDetailPageComponent } from './own-deck-detail-page.component';
import { OwnDeckRecoveryService } from './own-deck-recovery.service';

describe('OwnDeckDetailPageComponent', () => {
    let fixture: ComponentFixture<OwnDeckDetailPageComponent>;
    let store: jasmine.SpyObj<OwnDecksStore>;
    let recovery: jasmine.SpyObj<OwnDeckRecoveryService>;
    const deck = metadataFixture.detail as unknown as OwnDeck;
    const detail = signal<DeckDetailState>({ phase: 'ready', deckId: deck.deckId, deck, failure: null });
    const mutation = signal<DeckMutationState>({ phase: 'idle' });

    beforeEach(async () => {
        detail.set({ phase: 'ready', deckId: deck.deckId, deck, failure: null });
        mutation.set({ phase: 'idle' });
        store = jasmine.createSpyObj<OwnDecksStore>('OwnDecksStore', [
            'openDeck', 'retryDetail', 'startSave', 'retryMutation', 'retryAsNewCommand',
            'useServerVersion', 'reapplyConflict', 'clearMutation', 'recoverMutation'
        ]);
        recovery = jasmine.createSpyObj<OwnDeckRecoveryService>('OwnDeckRecoveryService', ['restore', 'save', 'clear']);
        recovery.restore.and.returnValue(null);
        Object.defineProperty(store, 'detailState', { value: detail.asReadonly() });
        Object.defineProperty(store, 'mutationState', { value: mutation.asReadonly() });
        await TestBed.configureTestingModule({
            imports: [OwnDeckDetailPageComponent],
            providers: [
                provideRouter([]),
                { provide: OwnDeckRecoveryService, useValue: recovery },
                { provide: ActivatedRoute, useValue: { paramMap: of(convertToParamMap({ deckId: deck.deckId })) } }
            ]
        }).overrideComponent(OwnDeckDetailPageComponent, {
            set: { providers: [{ provide: OwnDecksStore, useValue: store }] }
        }).compileComponents();
        fixture = TestBed.createComponent(OwnDeckDetailPageComponent);
        fixture.detectChanges();
    });

    it('opens the route identity and saves exact edited metadata against the loaded deck', () => {
        expect(store.openDeck).toHaveBeenCalledOnceWith(deck.deckId);
        fixture.componentInstance.form.setValue({ title: '  Точное имя  ', description: 'строка 1\nстрока 2' });
        fixture.componentInstance.save(deck);

        expect(store.startSave).toHaveBeenCalledOnceWith(deck, {
            title: '  Точное имя  ', description: 'строка 1\nстрока 2'
        });
        expect(recovery.save).toHaveBeenCalledWith(
            { operation: 'save', deckId: deck.deckId },
            { title: '  Точное имя  ', description: 'строка 1\nстрока 2' },
            null
        );
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
        expect(root.textContent).toContain('Использовать версию сервера');
        expect(root.textContent).toContain('Применить мой прежний ввод поверх неё');
        expect(fixture.componentInstance.form.getRawValue()).toEqual({ title: 'Мой ввод', description: 'мой текст' });
        expect(root.querySelector<HTMLTextAreaElement>('#detail-title')?.readOnly).toBeTrue();
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
            .querySelector<HTMLTextAreaElement>('#detail-title')?.readOnly).toBeTrue();
    });

    it('makes Study the primary action inside the selected deck', () => {
        const root = fixture.nativeElement as HTMLElement;
        const study = [...root.querySelectorAll<HTMLAnchorElement>('a')].find(link => link.textContent?.trim() === 'Учить');
        expect(study).toBeDefined();
        expect(study?.classList).toContain('primary');
        expect(study?.getAttribute('href')).toBe(`/decks/${deck.deckId}/study`);
    });
});
