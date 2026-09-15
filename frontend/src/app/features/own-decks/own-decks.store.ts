import { DestroyRef, Injectable, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { Observable, Subscription, map, of, switchMap } from 'rxjs';

import {
    DeckCommand,
    DeckFailure,
    DeckMetadata,
    DeckWriteResult,
    OwnDeck,
    OwnDeckPage,
    OwnDeckProtocolError,
    createDeckCommand
} from './own-deck.models';
import { OwnDecksApiService } from './own-decks-api.service';

export interface DeckListState {
    readonly phase: 'idle' | 'loading' | 'ready' | 'error';
    readonly items: readonly OwnDeck[];
    readonly nextCursor: string | null;
    readonly operation: 'replace' | 'next' | 'previous' | null;
    readonly failure: DeckFailure | null;
}

export interface DeckDetailState {
    readonly phase: 'idle' | 'loading' | 'ready' | 'error';
    readonly deckId: string | null;
    readonly deck: OwnDeck | null;
    readonly failure: DeckFailure | null;
}

export type PendingDeckCommand =
    | { readonly operation: 'create'; readonly command: DeckCommand }
    | {
        readonly operation: 'save';
        readonly deckId: string;
        readonly expectedVersion: string;
        readonly command: DeckCommand;
    };

export type DeckMutationState =
    | { readonly phase: 'idle' }
    | { readonly phase: 'pending'; readonly pending: PendingDeckCommand }
    | {
        readonly phase: 'completed';
        readonly operation: 'create' | 'save';
        readonly deck: OwnDeck;
        readonly confirmation: 'fresh' | 'refreshed-after-replay';
    }
    | {
        readonly phase: 'error';
        readonly pending: PendingDeckCommand;
        readonly failure: DeckFailure;
        readonly replayRefreshFailed: boolean;
        readonly replayDeckId: string | null;
        readonly conflictRefreshFailed: boolean;
    }
    | {
        readonly phase: 'conflict-loading';
        readonly pending: Extract<PendingDeckCommand, { operation: 'save' }>;
    }
    | {
        readonly phase: 'conflict';
        readonly pending: Extract<PendingDeckCommand, { operation: 'save' }>;
        readonly latest: OwnDeck;
    };

interface ResolvedWrite {
    readonly result: DeckWriteResult;
    readonly deck: OwnDeck;
    readonly confirmation: 'fresh' | 'refreshed-after-replay';
}

const LIST_CURSOR_WINDOW = 10;

@Injectable()
export class OwnDecksStore {
    private readonly api = inject(OwnDecksApiService);
    private readonly destroyRef = inject(DestroyRef);

    private readonly listSignal = signal<DeckListState>({
        phase: 'idle', items: [], nextCursor: null, operation: null, failure: null
    });
    private readonly detailSignal = signal<DeckDetailState>({
        phase: 'idle', deckId: null, deck: null, failure: null
    });
    private readonly mutationSignal = signal<DeckMutationState>({ phase: 'idle' });

    readonly listState = this.listSignal.asReadonly();
    private readonly canGoBackSignal = signal(false);
    readonly canGoBack = this.canGoBackSignal.asReadonly();
    readonly detailState = this.detailSignal.asReadonly();
    readonly mutationState = this.mutationSignal.asReadonly();

    private listEpoch = 0;
    private listCursors: (string | null)[] = [null];
    private listCursorIndex = 0;
    private failedListCursor: string | null = null;
    private detailEpoch = 0;
    private mutationEpoch = 0;
    private deckContextEpoch = 0;
    private listSubscription: Subscription | null = null;
    private detailSubscription: Subscription | null = null;
    private mutationSubscription: Subscription | null = null;

    constructor() {
        this.destroyRef.onDestroy(() => {
            this.listSubscription?.unsubscribe();
            this.detailSubscription?.unsubscribe();
            this.mutationSubscription?.unsubscribe();
        });
    }

    loadList(): void {
        this.requestList(null, 'replace');
    }

    loadMore(): void {
        const state = this.listSignal();
        if (state.phase !== 'ready' || state.nextCursor === null) return;
        this.requestList(state.nextCursor, 'next');
    }

    loadPrevious(): void {
        const state = this.listSignal();
        if (state.phase !== 'ready' || this.listCursorIndex === 0) return;
        this.requestList(this.listCursors[this.listCursorIndex - 1], 'previous');
    }

    retryList(): void {
        const state = this.listSignal();
        if (state.phase !== 'error' || state.operation === null) return;
        this.requestList(this.failedListCursor, state.operation);
    }

    openDeck(deckId: string): void {
        this.deckContextEpoch += 1;
        this.mutationEpoch += 1;
        this.mutationSubscription?.unsubscribe();
        this.mutationSignal.set({ phase: 'idle' });
        this.requestDetail(deckId);
    }

    retryDetail(): void {
        const state = this.detailSignal();
        if (state.phase === 'error' && state.deckId !== null) this.requestDetail(state.deckId);
    }

    startCreate(metadata: DeckMetadata): void {
        if (!canStartNewMutation(this.mutationSignal())) return;
        this.executeMutation({ operation: 'create', command: createDeckCommand(metadata) });
    }

    startSave(deck: OwnDeck, metadata: DeckMetadata): void {
        if (!canStartNewMutation(this.mutationSignal())) return;
        this.executeMutation({
            operation: 'save',
            deckId: deck.deckId,
            expectedVersion: deck.rowVersion,
            command: createDeckCommand(metadata)
        });
    }

    retryMutation(): void {
        const state = this.mutationSignal();
        if (state.phase !== 'error') return;
        if (state.conflictRefreshFailed) {
            if (state.pending.operation === 'save') this.loadConflictLatest(state.pending);
            return;
        }
        if (state.replayRefreshFailed) {
            if (state.replayDeckId !== null) this.refreshReplay(state.pending, state.replayDeckId);
            return;
        }
        this.executeMutation(state.pending);
    }

    retryAsNewCommand(): void {
        const state = this.mutationSignal();
        if (state.phase !== 'error' || state.failure.status !== 409) return;
        const pending = state.pending;
        const command = createDeckCommand(pending.command.metadata);
        this.executeMutation(pending.operation === 'create'
            ? { operation: 'create', command }
            : { ...pending, command });
    }

    useServerVersion(): OwnDeck | null {
        const state = this.mutationSignal();
        if (state.phase !== 'conflict') return null;
        this.detailSignal.set({ phase: 'ready', deckId: state.latest.deckId, deck: state.latest, failure: null });
        this.mutationSignal.set({ phase: 'idle' });
        return state.latest;
    }

    reapplyConflict(): void {
        const state = this.mutationSignal();
        if (state.phase !== 'conflict') return;
        this.executeMutation({
            operation: 'save',
            deckId: state.latest.deckId,
            expectedVersion: state.latest.rowVersion,
            command: createDeckCommand(state.pending.command.metadata)
        });
    }

    clearMutation(): void {
        const state = this.mutationSignal();
        if (state.phase !== 'completed' && !(state.phase === 'error' && !mutationLocksDraft(state))) return;
        this.mutationEpoch += 1;
        this.mutationSubscription?.unsubscribe();
        this.mutationSignal.set({ phase: 'idle' });
    }

    private requestList(cursor: string | null, operation: 'replace' | 'next' | 'previous'): void {
        const epoch = ++this.listEpoch;
        this.listSubscription?.unsubscribe();
        const previous = this.listSignal();
        this.listSignal.set({
            phase: 'loading',
            items: previous.items,
            nextCursor: previous.nextCursor,
            operation,
            failure: null
        });
        this.listSubscription = this.api.list(cursor).subscribe({
            next: page => {
                if (epoch !== this.listEpoch) return;
                this.failedListCursor = null;
                this.applyPageNavigation(cursor, operation);
                this.listSignal.set({
                    phase: 'ready', items: page.items, nextCursor: page.nextCursor, operation: null, failure: null
                });
            },
            error: error => {
                if (epoch !== this.listEpoch) return;
                this.failedListCursor = cursor;
                this.listSignal.set({
                    phase: 'error', items: previous.items, nextCursor: previous.nextCursor,
                    operation, failure: failureOf(error)
                });
            }
        });
    }

    private applyPageNavigation(cursor: string | null, operation: 'replace' | 'next' | 'previous'): void {
        if (operation === 'replace') {
            this.listCursors = [null];
            this.listCursorIndex = 0;
        } else if (operation === 'next') {
            this.listCursors = this.listCursors.slice(0, this.listCursorIndex + 1);
            this.listCursors.push(cursor);
            if (this.listCursors.length > LIST_CURSOR_WINDOW) this.listCursors.shift();
            else this.listCursorIndex += 1;
        } else {
            this.listCursorIndex -= 1;
        }
        this.canGoBackSignal.set(this.listCursorIndex > 0);
    }

    private requestDetail(deckId: string): void {
        const epoch = ++this.detailEpoch;
        this.detailSubscription?.unsubscribe();
        this.detailSignal.set({ phase: 'loading', deckId, deck: null, failure: null });
        this.detailSubscription = this.api.detail(deckId).subscribe({
            next: deck => {
                if (epoch !== this.detailEpoch || deckId !== this.detailSignal().deckId) return;
                this.detailSignal.set({ phase: 'ready', deckId, deck, failure: null });
            },
            error: error => {
                if (epoch !== this.detailEpoch || deckId !== this.detailSignal().deckId) return;
                this.detailSignal.set({ phase: 'error', deckId, deck: null, failure: failureOf(error) });
            }
        });
    }

    private executeMutation(pending: PendingDeckCommand): void {
        const epoch = ++this.mutationEpoch;
        const contextEpoch = this.deckContextEpoch;
        this.mutationSubscription?.unsubscribe();
        this.mutationSignal.set({ phase: 'pending', pending });
        let replayRefreshStarted = false;
        let replayDeckId: string | null = null;
        const write = pending.operation === 'create'
            ? this.api.create(pending.command)
            : this.api.save(pending.deckId, pending.expectedVersion, pending.command);
        this.mutationSubscription = write.pipe(switchMap(result => {
            if (!result.replayed) {
                return of<ResolvedWrite>({ result, deck: result.acknowledgement.deck, confirmation: 'fresh' });
            }
            replayRefreshStarted = true;
            replayDeckId = result.acknowledgement.deck.deckId;
            return this.api.detail(replayDeckId).pipe(map(deck => ({
                result, deck, confirmation: 'refreshed-after-replay' as const
            })));
        })).subscribe({
            next: resolved => {
                if (epoch !== this.mutationEpoch || contextEpoch !== this.deckContextEpoch) return;
                if (pending.operation === 'save') {
                    const current = this.detailSignal();
                    if (current.deckId !== pending.deckId) {
                        this.mutationSignal.set({ phase: 'idle' });
                        return;
                    }
                    this.detailSignal.set({ phase: 'ready', deckId: resolved.deck.deckId, deck: resolved.deck, failure: null });
                }
                this.mutationSignal.set({
                    phase: 'completed', operation: pending.operation, deck: resolved.deck,
                    confirmation: resolved.confirmation
                });
            },
            error: error => {
                if (epoch !== this.mutationEpoch || contextEpoch !== this.deckContextEpoch) return;
                const failure = failureOf(error);
                if (pending.operation === 'save' && failure.status === 412 && !replayRefreshStarted) {
                    this.loadConflictLatest(pending);
                    return;
                }
                this.mutationSignal.set({
                    phase: 'error', pending, failure,
                    replayRefreshFailed: replayRefreshStarted,
                    replayDeckId,
                    conflictRefreshFailed: false
                });
            }
        });
    }

    private refreshReplay(pending: PendingDeckCommand, deckId: string): void {
        const epoch = ++this.mutationEpoch;
        const contextEpoch = this.deckContextEpoch;
        this.mutationSubscription?.unsubscribe();
        this.mutationSignal.set({ phase: 'pending', pending });
        this.mutationSubscription = this.api.detail(deckId).subscribe({
            next: deck => {
                if (epoch !== this.mutationEpoch || contextEpoch !== this.deckContextEpoch) return;
                if (pending.operation === 'save') {
                    this.detailSignal.set({ phase: 'ready', deckId, deck, failure: null });
                }
                this.mutationSignal.set({
                    phase: 'completed', operation: pending.operation, deck, confirmation: 'refreshed-after-replay'
                });
            },
            error: error => {
                if (epoch !== this.mutationEpoch || contextEpoch !== this.deckContextEpoch) return;
                this.mutationSignal.set({
                    phase: 'error', pending, failure: failureOf(error),
                    replayRefreshFailed: true, replayDeckId: deckId, conflictRefreshFailed: false
                });
            }
        });
    }

    private loadConflictLatest(pending: Extract<PendingDeckCommand, { operation: 'save' }>): void {
        const epoch = ++this.mutationEpoch;
        const contextEpoch = this.deckContextEpoch;
        this.mutationSubscription?.unsubscribe();
        this.mutationSignal.set({ phase: 'conflict-loading', pending });
        this.mutationSubscription = this.api.detail(pending.deckId).subscribe({
            next: latest => {
                if (epoch !== this.mutationEpoch || contextEpoch !== this.deckContextEpoch) return;
                this.mutationSignal.set({ phase: 'conflict', pending, latest });
            },
            error: error => {
                if (epoch !== this.mutationEpoch || contextEpoch !== this.deckContextEpoch) return;
                this.mutationSignal.set({
                    phase: 'error', pending, failure: failureOf(error),
                    replayRefreshFailed: false, replayDeckId: null, conflictRefreshFailed: true
                });
            }
        });
    }
}

export function failureOf(error: unknown): DeckFailure {
    if (error instanceof OwnDeckProtocolError) return { kind: 'protocol', status: 0, code: null };
    if (error instanceof HttpErrorResponse) {
        if (error.status === 0) return { kind: 'network', status: 0, code: null };
        return { kind: 'http', status: error.status, code: problemCode(error.error) };
    }
    return { kind: 'protocol', status: 0, code: null };
}

export function deckFailureMessage(failure: DeckFailure): string {
    if (failure.kind === 'network') return 'Ответ сервера не получен. Введённые данные остаются на этой странице.';
    if (failure.kind === 'protocol') return 'Сервер вернул неожиданный ответ. Мы не применили его к странице.';
    switch (failure.status) {
        case 400: return 'Проверьте название и описание: сервер не принял данные.';
        case 401: return 'Сессия завершилась. Войдите снова, чтобы продолжить.';
        case 403: return 'У этой сессии недостаточно прав для действия.';
        case 404: return 'Колода не найдена или больше недоступна.';
        case 409: return 'Идентификатор команды уже использован для другого действия.';
        case 412: return 'Колода изменилась в другой вкладке.';
        case 428: return 'Не удалось подтвердить версию колоды. Обновите страницу.';
        case 503: return 'Сервис временно недоступен. Попробуйте ещё раз.';
        default: return 'Не удалось выполнить запрос. Введённые данные остаются на этой странице.';
    }
}

export function mayRetrySameCommand(failure: DeckFailure): boolean {
    return failure.kind === 'network' || failure.kind === 'protocol'
        || (failure.status >= 500 && failure.status <= 599);
}

export function mutationLocksDraft(state: DeckMutationState): boolean {
    if (state.phase === 'pending' || state.phase === 'conflict-loading' || state.phase === 'conflict') return true;
    return state.phase === 'error' && (state.replayRefreshFailed || state.conflictRefreshFailed
        || mayRetrySameCommand(state.failure) || state.failure.status === 409);
}

export function canStartNewMutation(state: DeckMutationState): boolean {
    return state.phase === 'idle' || (state.phase === 'error' && !mutationLocksDraft(state));
}

function problemCode(value: unknown): string | null {
    if (value === null || typeof value !== 'object' || Array.isArray(value)) return null;
    const code = (value as Record<string, unknown>)['code'];
    return typeof code === 'string' ? code : null;
}
