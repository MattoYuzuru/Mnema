import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { Observable, Observer, Subject, Subscription, of, throwError } from 'rxjs';

import metadataFixture from '../../../../../contracts/decks/metadata.json';
import { DeckCommand, DeckWriteResult, OwnDeck, OwnDeckPage } from './own-deck.models';
import { OwnDecksApiService } from './own-decks-api.service';
import { OwnDecksStore, mayRetrySameCommand } from './own-decks.store';
import { spyObj, type SpyObj } from '../../../testing/mocks';

describe('OwnDecksStore', () => {
    let store: OwnDecksStore;
    let api: SpyObj<OwnDecksApiService>;
    const fixtureDeck = metadataFixture.detail as unknown as OwnDeck;

    beforeEach(() => {
        api = spyObj<OwnDecksApiService>({
            list: vi.fn().mockName("OwnDecksApiService.list"),
            detail: vi.fn().mockName("OwnDecksApiService.detail"),
            create: vi.fn().mockName("OwnDecksApiService.create"),
            save: vi.fn().mockName("OwnDecksApiService.save")
        });
        TestBed.configureTestingModule({
            providers: [OwnDecksStore, { provide: OwnDecksApiService, useValue: api }]
        });
        store = TestBed.inject(OwnDecksStore);
    });

    it('treats rejected successful-response parsing and server failures as unknown outcomes', () => {
        expect(mayRetrySameCommand({ kind: 'protocol', status: 0, code: null })).toBe(true);
        expect(mayRetrySameCommand({ kind: 'http', status: 500, code: null })).toBe(true);
        expect(mayRetrySameCommand({ kind: 'http', status: 400, code: 'INVALID_REQUEST' })).toBe(false);
    });

    it('admits one continuation request and retries the same cursor without losing earlier rows', () => {
        const older = { ...fixtureDeck, deckId: '33333333-3333-4333-8333-333333333333' };
        const pending = new Subject<OwnDeckPage>();
        api.list.mockReturnValueOnce(of({ items: [fixtureDeck], nextCursor: 'older' })).mockReturnValueOnce(pending)
            .mockReturnValueOnce(of({ items: [older], nextCursor: null }));
        store.loadList(); store.loadMore(); store.loadMore();
        expect(api.list).toHaveBeenCalledTimes(2);
        pending.error(new Error('offline'));
        expect(store.listState().items).toEqual([fixtureDeck]);
        store.retryList();
        expect(api.list).toHaveBeenLastCalledWith('older');
        expect(store.listState().items).toEqual([fixtureDeck, older]);
    });

    it('ignores a stale list response even when its transport does not honor unsubscribe', () => {
        const observers: Observer<OwnDeckPage>[] = [];
        api.list.mockImplementation(() => stubbornObservable(observers));
        const newer = { ...fixtureDeck, deckId: '33333333-3333-4333-8333-333333333333' };

        store.loadList();
        store.loadList();
        observers[1].next({ items: [newer], nextCursor: null });
        observers[0].next({ items: [fixtureDeck], nextCursor: null });

        expect(store.listState().items.map(deck => deck.deckId)).toEqual([newer.deckId]);
    });

    it('accumulates unique cursor pages until the end, then stops requesting', () => {
        api.list.mockImplementation(cursor => {
            const page = cursor == null ? 0 : Number(cursor.replace('page-', ''));
            const items = Array.from({ length: 20 }, (_, offset) => ({
                ...fixtureDeck,
                deckId: `11111111-1111-4111-8111-${(page * 20 + offset).toString(16).padStart(12, '0')}`
            }));
            return of({ items, nextCursor: page === 99 ? null : `page-${page + 1}` });
        });

        store.loadList();
        for (let page = 1; page < 100; page += 1)
            store.loadMore();

        expect(store.listState().items.length).toBe(2000);
        store.loadMore();
        expect(api.list).toHaveBeenCalledTimes(100);
    });

    it('keeps earlier pages, drops duplicate ids and preserves the continuation during a first-page refresh', () => {
        const older = { ...fixtureDeck, deckId: '33333333-3333-4333-8333-333333333333' };
        api.list.mockReturnValueOnce(of({ items: [fixtureDeck], nextCursor: 'older-page' }))
            .mockReturnValueOnce(of({ items: [fixtureDeck, older], nextCursor: 'oldest-page' }))
            .mockReturnValueOnce(of({ items: [{ ...fixtureDeck, metadata: { ...fixtureDeck.metadata, title: 'Обновлено' } }], nextCursor: 'older-page' }));

        store.loadList();
        store.loadMore();
        expect(store.listState().items.map(deck => deck.deckId)).toEqual([fixtureDeck.deckId, older.deckId]);
        store.refreshVisibleList();
        expect(vi.mocked(api.list).mock.calls[2][0]).toBeNull();
        expect(store.listState().items.map(deck => deck.deckId)).toEqual([fixtureDeck.deckId, older.deckId]);
        expect(store.listState().items[0].metadata.title).toBe('Обновлено');
        expect(store.listState().nextCursor).toBe('oldest-page');
    });

    it('refreshes the visible page without changing its cursor or discarding confirmed rows on failure', () => {
        const newer = { ...fixtureDeck, deckId: '33333333-3333-4333-8333-333333333333' };
        api.list.mockReturnValueOnce(of({ items: [fixtureDeck], nextCursor: 'older-page' })).mockReturnValueOnce(of({ items: [newer], nextCursor: 'older-page' })).mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 503 })));

        store.loadList();
        store.refreshVisibleList();
        expect(store.listState().items.map(deck => deck.deckId)).toEqual([newer.deckId]);
        store.refreshVisibleList();
        expect(store.listState().phase).toBe('ready');
        expect(store.listState().items.map(deck => deck.deckId)).toEqual([newer.deckId]);
        expect(vi.mocked(api.list).mock.calls).toEqual([[null], [null], [null]]);
    });

    it('retries an unknown create outcome with the exact same command', () => {
        api.create.mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 0 }))).mockReturnValueOnce(of(writeResult(fixtureDeck, false)));

        store.startCreate(fixtureDeck.metadata);
        const firstCommand = vi.mocked(api.create).mock.calls[0][0];
        expect(store.mutationState().phase).toBe('error');
        store.startCreate({ title: 'Второй щелчок', description: '' });
        expect(api.create).toHaveBeenCalledTimes(1);
        expect(store.mutationState().phase).toBe('error');
        store.retryMutation();

        expect(vi.mocked(api.create).mock.calls[1][0]).toEqual(firstCommand);
        const completed = store.mutationState();
        expect(completed.phase).toBe('completed');
        if (completed.phase === 'completed')
            expect(completed.confirmation).toBe('fresh');
    });

    it('restores an unresolved command without sending it until explicit retry', () => {
        const pending = {
            operation: 'create' as const,
            command: {
                commandId: '123e4567-e89b-42d3-a456-426614174000',
                metadata: { title: 'Восстановленный ввод', description: 'не терять' }
            }
        };
        api.create.mockReturnValue(of(writeResult(fixtureDeck, false)));

        store.recoverMutation(pending);
        expect(api.create).not.toHaveBeenCalled();
        store.retryMutation();

        expect(api.create).toHaveBeenCalledTimes(1);

        expect(api.create).toHaveBeenCalledWith(pending.command);
    });

    it('admits only one create while its first transport is still pending', () => {
        const observers: Observer<DeckWriteResult>[] = [];
        api.create.mockImplementation(() => stubbornObservable(observers));

        store.startCreate({ title: 'Первый замысел', description: '' });
        const firstCommand = vi.mocked(api.create).mock.calls[0][0];
        store.startCreate({ title: 'Двойной щелчок', description: '' });

        expect(api.create).toHaveBeenCalledTimes(1);
        const state = store.mutationState();
        expect(state.phase).toBe('pending');
        if (state.phase === 'pending')
            expect(state.pending.command).toEqual(firstCommand);
    });

    it('keeps a replay-refresh draft owned by its original command until exact GET retry succeeds', () => {
        const current = { ...fixtureDeck, rowVersion: '2', sequence: '2' };
        api.create.mockReturnValue(of(writeResult(fixtureDeck, true)));
        api.detail.mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 0 }))).mockReturnValueOnce(of(current));

        store.startCreate({ title: 'Исходный ввод', description: 'точный' });
        const failed = store.mutationState();
        expect(failed.phase).toBe('error');
        if (failed.phase === 'error')
            expect(failed.replayRefreshFailed).toBe(true);
        store.startCreate({ title: 'Новый ввод', description: '' });
        expect(api.create).toHaveBeenCalledTimes(1);

        store.retryMutation();
        expect(api.create).toHaveBeenCalledTimes(1);
        expect(api.detail).toHaveBeenCalledTimes(2);
        const completed = store.mutationState();
        expect(completed.phase).toBe('completed');
        if (completed.phase === 'completed')
            expect(completed.confirmation).toBe('refreshed-after-replay');
    });

    it('refreshes a replay before exposing completion and never applies its old acknowledgement', () => {
        const current = { ...fixtureDeck, rowVersion: '2', sequence: '2', metadata: { title: 'Новое', description: '' } };
        api.create.mockReturnValue(of(writeResult(fixtureDeck, true)));
        api.detail.mockReturnValue(of(current));

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
        api.detail.mockReturnValueOnce(of(fixtureDeck)).mockReturnValueOnce(of(latest));
        api.save.mockReturnValueOnce(throwError(() => new HttpErrorResponse({
            status: 412,
            error: { code: 'VERSION_CONFLICT' }
        }))).mockReturnValueOnce(of(writeResult(reapplied, false)));
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
        expect(vi.mocked(api.save).mock.calls[1][1]).toBe('1');
        expect(vi.mocked(api.save).mock.calls[1][2].commandId).not.toBe(vi.mocked(api.save).mock.calls[0][2].commandId);
        expect(store.detailState().deck?.metadata).toEqual(draft);
    });

    it('does not replace an unresolved save command with a newer local draft', () => {
        const saved = { ...fixtureDeck, rowVersion: '1', sequence: '1', metadata: { title: 'Первый ввод', description: '' } };
        api.detail.mockReturnValue(of(fixtureDeck));
        api.save.mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 0 }))).mockReturnValueOnce(of(writeResult(saved, false)));
        store.openDeck(fixtureDeck.deckId);

        store.startSave(fixtureDeck, saved.metadata);
        const original = vi.mocked(api.save).mock.calls[0][2];
        store.startSave(fixtureDeck, { title: 'Второй ввод', description: '' });
        expect(api.save).toHaveBeenCalledTimes(1);

        store.retryMutation();
        expect(vi.mocked(api.save).mock.calls[1][2]).toEqual(original);
        expect(store.detailState().deck?.metadata.title).toBe('Первый ввод');
    });

    it('ignores a save acknowledgement after navigating to another deck context', () => {
        const observers: Observer<DeckWriteResult>[] = [];
        api.detail.mockReturnValue(stubbornObservable<OwnDeck>([]));
        api.save.mockImplementation(() => stubbornObservable(observers));
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
