import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { Observable, Observer, Subscription, of, throwError } from 'rxjs';

import metadataFixture from '../../../../../contracts/decks/metadata.json';
import { DeckCommand, DeckWriteResult, OwnDeck, OwnDeckPage } from './own-deck.models';
import { OwnDecksApiService } from './own-decks-api.service';
import { OwnDecksStore, mayRetrySameCommand } from './own-decks.store';

describe('OwnDecksStore', () => {
    let store: OwnDecksStore;
    let api: jasmine.SpyObj<OwnDecksApiService>;
    const fixtureDeck = metadataFixture.detail as unknown as OwnDeck;

    beforeEach(() => {
        api = jasmine.createSpyObj<OwnDecksApiService>('OwnDecksApiService', ['list', 'detail', 'create', 'save']);
        TestBed.configureTestingModule({
            providers: [OwnDecksStore, { provide: OwnDecksApiService, useValue: api }]
        });
        store = TestBed.inject(OwnDecksStore);
    });

    it('treats rejected successful-response parsing and server failures as unknown outcomes', () => {
        expect(mayRetrySameCommand({ kind: 'protocol', status: 0, code: null })).toBeTrue();
        expect(mayRetrySameCommand({ kind: 'http', status: 500, code: null })).toBeTrue();
        expect(mayRetrySameCommand({ kind: 'http', status: 400, code: 'INVALID_REQUEST' })).toBeFalse();
    });

    it('ignores a stale list response even when its transport does not honor unsubscribe', () => {
        const observers: Observer<OwnDeckPage>[] = [];
        api.list.and.callFake(() => stubbornObservable(observers));
        const newer = { ...fixtureDeck, deckId: '33333333-3333-4333-8333-333333333333' };

        store.loadList();
        store.loadList();
        observers[1].next({ items: [newer], nextCursor: null });
        observers[0].next({ items: [fixtureDeck], nextCursor: null });

        expect(store.listState().items.map(deck => deck.deckId)).toEqual([newer.deckId]);
    });

    it('keeps only one bounded server page after extended cursor navigation', () => {
        api.list.and.callFake(cursor => {
            const page = cursor == null ? 0 : Number(cursor.replace('page-', ''));
            const items = Array.from({ length: 20 }, (_, offset) => ({
                ...fixtureDeck,
                deckId: `11111111-1111-4111-8111-${(page * 20 + offset).toString(16).padStart(12, '0')}`
            }));
            return of({ items, nextCursor: page === 99 ? null : `page-${page + 1}` });
        });

        store.loadList();
        for (let page = 1; page < 100; page += 1) store.loadMore();

        expect(store.listState().items.length).toBe(20);
    });

    it('navigates back with a retained cursor without accumulating both pages', () => {
        const older = { ...fixtureDeck, deckId: '33333333-3333-4333-8333-333333333333' };
        api.list.and.returnValues(
            of({ items: [fixtureDeck], nextCursor: 'older-page' }),
            of({ items: [older], nextCursor: null }),
            of({ items: [fixtureDeck], nextCursor: 'older-page' })
        );

        store.loadList();
        store.loadMore();
        expect(store.listState().items.map(deck => deck.deckId)).toEqual([older.deckId]);
        expect(store.canGoBack()).toBeTrue();

        store.loadPrevious();
        expect(api.list.calls.argsFor(2)[0]).toBeNull();
        expect(store.listState().items.map(deck => deck.deckId)).toEqual([fixtureDeck.deckId]);
        expect(store.canGoBack()).toBeFalse();
    });

    it('retries an unknown create outcome with the exact same command', () => {
        api.create.and.returnValues(
            throwError(() => new HttpErrorResponse({ status: 0 })),
            of(writeResult(fixtureDeck, false))
        );

        store.startCreate(fixtureDeck.metadata);
        const firstCommand = api.create.calls.argsFor(0)[0];
        expect(store.mutationState().phase).toBe('error');
        store.startCreate({ title: 'Второй щелчок', description: '' });
        expect(api.create).toHaveBeenCalledTimes(1);
        expect(store.mutationState().phase).toBe('error');
        store.retryMutation();

        expect(api.create.calls.argsFor(1)[0]).toEqual(firstCommand);
        const completed = store.mutationState();
        expect(completed.phase).toBe('completed');
        if (completed.phase === 'completed') expect(completed.confirmation).toBe('fresh');
    });

    it('restores an unresolved command without sending it until explicit retry', () => {
        const pending = {
            operation: 'create' as const,
            command: {
                commandId: '123e4567-e89b-42d3-a456-426614174000',
                metadata: { title: 'Восстановленный ввод', description: 'не терять' }
            }
        };
        api.create.and.returnValue(of(writeResult(fixtureDeck, false)));

        store.recoverMutation(pending);
        expect(api.create).not.toHaveBeenCalled();
        store.retryMutation();

        expect(api.create).toHaveBeenCalledOnceWith(pending.command);
    });

    it('admits only one create while its first transport is still pending', () => {
        const observers: Observer<DeckWriteResult>[] = [];
        api.create.and.callFake(() => stubbornObservable(observers));

        store.startCreate({ title: 'Первый замысел', description: '' });
        const firstCommand = api.create.calls.argsFor(0)[0];
        store.startCreate({ title: 'Двойной щелчок', description: '' });

        expect(api.create).toHaveBeenCalledTimes(1);
        const state = store.mutationState();
        expect(state.phase).toBe('pending');
        if (state.phase === 'pending') expect(state.pending.command).toEqual(firstCommand);
    });

    it('keeps a replay-refresh draft owned by its original command until exact GET retry succeeds', () => {
        const current = { ...fixtureDeck, rowVersion: '2', sequence: '2' };
        api.create.and.returnValue(of(writeResult(fixtureDeck, true)));
        api.detail.and.returnValues(
            throwError(() => new HttpErrorResponse({ status: 0 })),
            of(current)
        );

        store.startCreate({ title: 'Исходный ввод', description: 'точный' });
        const failed = store.mutationState();
        expect(failed.phase).toBe('error');
        if (failed.phase === 'error') expect(failed.replayRefreshFailed).toBeTrue();
        store.startCreate({ title: 'Новый ввод', description: '' });
        expect(api.create).toHaveBeenCalledTimes(1);

        store.retryMutation();
        expect(api.create).toHaveBeenCalledTimes(1);
        expect(api.detail).toHaveBeenCalledTimes(2);
        const completed = store.mutationState();
        expect(completed.phase).toBe('completed');
        if (completed.phase === 'completed') expect(completed.confirmation).toBe('refreshed-after-replay');
    });

    it('refreshes a replay before exposing completion and never applies its old acknowledgement', () => {
        const current = { ...fixtureDeck, rowVersion: '2', sequence: '2', metadata: { title: 'Новое', description: '' } };
        api.create.and.returnValue(of(writeResult(fixtureDeck, true)));
        api.detail.and.returnValue(of(current));

        store.startCreate(fixtureDeck.metadata);

        expect(api.detail).toHaveBeenCalledWith(fixtureDeck.deckId);
        const completed = store.mutationState();
        expect(completed.phase).toBe('completed');
        if (completed.phase === 'completed') {
            expect(completed.confirmation).toBe('refreshed-after-replay');
            expect(completed.deck.metadata.title).toBe('Новое');
        }
    });

    it('keeps the draft on 412, loads latest, and reapplies only after explicit choice', () => {
        const latest = { ...fixtureDeck, rowVersion: '1', sequence: '1', metadata: { title: 'Другая вкладка', description: '' } };
        const reapplied = { ...latest, rowVersion: '2', sequence: '2', metadata: { title: 'Мой ввод', description: '  точно  ' } };
        api.detail.and.returnValues(of(fixtureDeck), of(latest));
        api.save.and.returnValues(
            throwError(() => new HttpErrorResponse({
                status: 412,
                error: { code: 'VERSION_CONFLICT' }
            })),
            of(writeResult(reapplied, false))
        );
        store.openDeck(fixtureDeck.deckId);
        const draft = reapplied.metadata;

        store.startSave(fixtureDeck, draft);
        const conflict = store.mutationState();
        expect(conflict.phase).toBe('conflict');
        if (conflict.phase === 'conflict') {
            expect(conflict.pending.command.metadata).toEqual(draft);
            expect(conflict.latest.metadata.title).toBe('Другая вкладка');
        }

        store.reapplyConflict();
        expect(api.save.calls.argsFor(1)[1]).toBe('1');
        expect(api.save.calls.argsFor(1)[2].commandId).not.toBe(api.save.calls.argsFor(0)[2].commandId);
        expect(store.detailState().deck?.metadata).toEqual(draft);
    });

    it('does not replace an unresolved save command with a newer local draft', () => {
        const saved = { ...fixtureDeck, rowVersion: '1', sequence: '1', metadata: { title: 'Первый ввод', description: '' } };
        api.detail.and.returnValue(of(fixtureDeck));
        api.save.and.returnValues(
            throwError(() => new HttpErrorResponse({ status: 0 })),
            of(writeResult(saved, false))
        );
        store.openDeck(fixtureDeck.deckId);

        store.startSave(fixtureDeck, saved.metadata);
        const original = api.save.calls.argsFor(0)[2];
        store.startSave(fixtureDeck, { title: 'Второй ввод', description: '' });
        expect(api.save).toHaveBeenCalledTimes(1);

        store.retryMutation();
        expect(api.save.calls.argsFor(1)[2]).toEqual(original);
        expect(store.detailState().deck?.metadata.title).toBe('Первый ввод');
    });

    it('ignores a save acknowledgement after navigating to another deck context', () => {
        const observers: Observer<DeckWriteResult>[] = [];
        api.detail.and.returnValue(stubbornObservable<OwnDeck>([]));
        api.save.and.callFake(() => stubbornObservable(observers));
        store.openDeck(fixtureDeck.deckId);
        store.startSave(fixtureDeck, { title: 'Поздний ответ', description: '' });
        store.openDeck('33333333-3333-4333-8333-333333333333');

        observers[0].next(writeResult({ ...fixtureDeck, metadata: { title: 'Поздний ответ', description: '' } }, false));

        expect(store.detailState().deckId).toBe('33333333-3333-4333-8333-333333333333');
        expect(store.detailState().deck).toBeNull();
        expect(store.mutationState().phase).toBe('idle');
    });
});

function writeResult(deck: OwnDeck, replayed: boolean): DeckWriteResult {
    const command = metadataFixture.command as unknown as DeckCommand;
    return {
        acknowledgement: { commandId: command.commandId, deck },
        etag: replayed ? null : `"${deck.rowVersion}"`,
        replayed,
        location: null
    };
}

function stubbornObservable<T>(observers: Observer<T>[]): Observable<T> {
    const source = new Observable<T>();
    source.subscribe = ((observer: Partial<Observer<T>>) => {
        observers.push(observer as Observer<T>);
        return new Subscription();
    }) as typeof source.subscribe;
    return source;
}
