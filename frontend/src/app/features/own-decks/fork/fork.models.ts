import { HttpErrorResponse } from '@angular/common/http';

import { OwnDeckProtocolError, isCanonicalCommandId, isCanonicalEntityId } from '../own-deck.models';

/**
 * Wire shapes of «Добавить к себе» (`contracts/decks/forks.json`, Share/10 and Updates/6): the copy command, the retry, detach and
 * auto-update answers and the `fork` member of the owner's deck JSON. Parsing is strict. An absent `fork` is `null` (an ordinary deck); the contract
 * words are mirrored here once, and the texts are owned by `shared/copy-text.ts`.
 */

/** Ten characters of base58 (no `0`, `I`, `O`, `l`): the public code of the source. */
const SOURCE_CODE = /^[1-9A-HJ-NP-Za-km-z]{10}$/u;

/** The state of a copy as the owner sees it. `detached` is independent of the other three. */
export interface DeckFork {
    /** The code the copy was made by, while the source is still readable for the copier; `null` otherwise. */
    readonly upstreamCode: string | null;
    readonly upstreamTitle: string | null;
    readonly sourceAvailable: boolean;
    /** The heads are not complete yet: every mutation and Study answer 409 `DECK_PREPARING`. */
    readonly preparing: boolean;
    /** Implies `preparing`: the job ended without success; «Повторить» or delete the copy. */
    readonly failed: boolean;
    /** The owner pressed «Отвязать от автора»: updates never arrive, the content stays. */
    readonly detached: boolean;
    /**
     * «Есть обновление»: the copy is ready, attached, its source opens, and the author published something the copy does not have yet
     * (or a unit left for later is still on offer). Computed by the server at read; false in every other state.
     */
    readonly updateAvailable: boolean;
    /** The owner's setting «Принимать безопасное автоматически», as stored (false by default), whatever the state of the copy. */
    readonly autoUpdate: boolean;
}

export type ForkProgress = 'preparing' | 'failed' | 'ready';

export function forkProgressOf(fork: DeckFork): ForkProgress {
    return fork.failed ? 'failed' : fork.preparing ? 'preparing' : 'ready';
}

/** `POST /api/decks/forks`: a new copy (201) or the live copy that already exists (200, `existing`). */
export interface ForkCreated {
    readonly commandId: string;
    readonly deckId: string;
    readonly preparing: boolean;
    readonly existing: boolean;
}

/** `POST /api/decks/{id}/fork:retry`. */
export interface ForkRetried {
    readonly deckId: string;
    readonly preparing: boolean;
    readonly failed: boolean;
}

/** `PUT /api/decks/{id}/fork:auto-update`: the stored value. */
export interface ForkAutoUpdated {
    readonly deckId: string;
    readonly autoUpdate: boolean;
}

/** `POST /api/decks/{id}/fork:detach`. */
export interface ForkDetached {
    readonly deckId: string;
    readonly detached: true;
}

/** A fresh command id (UUIDv4) for one «Добавить к себе» attempt. */
export function newForkCommandId(): string {
    const commandId = crypto.randomUUID();
    if (!isCanonicalCommandId(commandId)) throw new OwnDeckProtocolError('Browser did not produce a canonical UUIDv4 command ID.');
    return commandId;
}

export function parseDeckFork(value: unknown): DeckFork | null {
    if (value === null || value === undefined) return null;
    const object = exactObject(value, ['upstreamCode', 'upstreamTitle', 'sourceAvailable', 'preparing', 'failed', 'detached', 'updateAvailable', 'autoUpdate']);
    const upstreamCode = object['upstreamCode'];
    const upstreamTitle = object['upstreamTitle'];
    const sourceAvailable = flag(object['sourceAvailable']);
    const preparing = flag(object['preparing']);
    const failed = flag(object['failed']);
    const detached = flag(object['detached']);
    const updateAvailable = flag(object['updateAvailable']);
    const autoUpdate = flag(object['autoUpdate']);
    if (upstreamCode !== null && (typeof upstreamCode !== 'string' || !SOURCE_CODE.test(upstreamCode))) throw new OwnDeckProtocolError('Invalid fork source code.');
    if (upstreamTitle !== null && (typeof upstreamTitle !== 'string' || upstreamTitle.length === 0 || Array.from(upstreamTitle).length > 400)) {
        throw new OwnDeckProtocolError('Invalid fork source title.');
    }
    // The code and the title are null exactly when the source is no longer available to the copier.
    if (sourceAvailable === (upstreamCode === null || upstreamTitle === null) || (upstreamCode === null) !== (upstreamTitle === null)) {
        throw new OwnDeckProtocolError('Fork source availability disagrees with its identity.');
    }
    if (failed && !preparing) throw new OwnDeckProtocolError('A failed copy is a preparing copy.');
    // The badge is only ever on for a ready, attached copy whose source opens.
    if (updateAvailable && (preparing || detached || !sourceAvailable)) throw new OwnDeckProtocolError('An update is available only to a ready, attached copy with a readable source.');
    return { upstreamCode, upstreamTitle, sourceAvailable, preparing, failed, detached, updateAvailable, autoUpdate };
}

export function parseForkCreated(value: unknown, status: number, commandId: string): ForkCreated {
    const object = exactObject(value, ['commandId', 'deckId', 'preparing', 'existing']);
    const deckId = object['deckId'];
    if (typeof object['commandId'] !== 'string' || object['commandId'].toLowerCase() !== commandId.toLowerCase()) {
        throw new OwnDeckProtocolError('Fork acknowledgement command ID does not match the request.');
    }
    if (typeof deckId !== 'string' || !isCanonicalEntityId(deckId)) throw new OwnDeckProtocolError('Invalid copy identity.');
    const preparing = flag(object['preparing']);
    const existing = flag(object['existing']);
    // 201 is a new copy that is being prepared; 200 is the copy that already existed.
    if (status === 201 ? !preparing || existing : status !== 200 || !existing) throw new OwnDeckProtocolError('Fork acknowledgement disagrees with its status.');
    return { commandId: commandId.toLowerCase(), deckId: deckId.toLowerCase(), preparing, existing };
}

export function parseForkRetried(value: unknown, deckId: string): ForkRetried {
    const object = exactObject(value, ['deckId', 'preparing', 'failed']);
    sameDeck(object['deckId'], deckId);
    const preparing = flag(object['preparing']);
    const failed = flag(object['failed']);
    if (failed && !preparing) throw new OwnDeckProtocolError('A failed copy is a preparing copy.');
    return { deckId: deckId.toLowerCase(), preparing, failed };
}

export function parseForkAutoUpdated(value: unknown, deckId: string): ForkAutoUpdated {
    const object = exactObject(value, ['deckId', 'autoUpdate']);
    sameDeck(object['deckId'], deckId);
    return { deckId: deckId.toLowerCase(), autoUpdate: flag(object['autoUpdate']) };
}

export function parseForkDetached(value: unknown, deckId: string): ForkDetached {
    const object = exactObject(value, ['deckId', 'detached']);
    sameDeck(object['deckId'], deckId);
    if (object['detached'] !== true) throw new OwnDeckProtocolError('Unexpected detach answer.');
    return { deckId: deckId.toLowerCase(), detached: true };
}

/** What a failed fork request means, in the words the screens need. */
export type ForkFailureKind =
    /** 404: unknown, private or rotated code, a deleted deck, or the feature switched off (the same answer). */
    | 'not-found'
    /** 403 `DECK_INVITE_ONLY`: the deck is by invitation and the account has no grant. */
    | 'invite-only'
    /** 409 `FORK_OWN_DECK`. */
    | 'own-deck'
    /** 409 `DECK_PREPARING`: the copy is not ready for this action yet. */
    | 'preparing'
    /** 409 `CAPABILITY_UNAVAILABLE` with reason `DETACHED`: a detached copy gets no updates, so its update setting cannot change. */
    | 'detached'
    /** 422 `RESOURCE_LIMIT_EXCEEDED`: too many copies are being prepared at once. */
    | 'limit'
    | 'rate-limited'
    | 'busy'
    /** 503 `FORKS_BUSY`: too many copies are being prepared across the service; the same command may be sent again later. */
    | 'queue-busy'
    /** No answer from the server, or a 5xx: the outcome is unknown and the same command may be sent again. */
    | 'unknown'
    | 'other';

export interface ForkFailure {
    readonly kind: ForkFailureKind;
    /** `limit` member of a 422. */
    readonly limit: number | null;
    /** Seconds from `Retry-After` of a 429 or 503. */
    readonly retryAfter: number | null;
}

export function forkFailureOf(error: unknown): ForkFailure {
    if (!(error instanceof HttpErrorResponse)) return { kind: 'unknown', limit: null, retryAfter: null };
    const body = isRecord(error.error) ? error.error : {};
    const code = typeof body['code'] === 'string' ? body['code'] : null;
    const limit = typeof body['limit'] === 'number' && Number.isInteger(body['limit']) && body['limit'] > 0 ? body['limit'] : null;
    const retryAfter = retryAfterOf(error, body);
    const failure = (kind: ForkFailureKind): ForkFailure => ({ kind, limit: kind === 'limit' ? limit : null, retryAfter });
    switch (error.status) {
        case 0: return failure('unknown');
        case 403: return failure(code === 'DECK_INVITE_ONLY' ? 'invite-only' : 'other');
        case 404: return failure('not-found');
        case 409:
            return failure(code === 'FORK_OWN_DECK' ? 'own-deck' : code === 'DECK_PREPARING' ? 'preparing'
                : code === 'CAPABILITY_UNAVAILABLE' && body['reason'] === 'DETACHED' ? 'detached' : 'other');
        case 422: return failure(code === 'RESOURCE_LIMIT_EXCEEDED' ? 'limit' : 'other');
        case 429: return failure('rate-limited');
        case 503: return failure(code === 'PUBLIC_READ_BUSY' ? 'busy' : code === 'FORKS_BUSY' ? 'queue-busy' : 'unknown');
        default: return failure(error.status >= 500 ? 'unknown' : 'other');
    }
}

function retryAfterOf(error: HttpErrorResponse, body: Record<string, unknown>): number | null {
    const header = error.headers?.get('Retry-After') ?? null;
    const seconds = header !== null && /^\d{1,6}$/u.test(header) ? Number(header) : body['retryAfter'];
    return typeof seconds === 'number' && Number.isInteger(seconds) && seconds >= 1 && seconds <= 86_400 ? seconds : null;
}

function sameDeck(value: unknown, deckId: string): void {
    if (typeof value !== 'string' || value.toLowerCase() !== deckId.toLowerCase()) throw new OwnDeckProtocolError('Fork answer identity does not match the request.');
}

function flag(value: unknown): boolean {
    if (typeof value !== 'boolean') throw new OwnDeckProtocolError('Invalid fork flag.');
    return value;
}

function isRecord(value: unknown): value is Record<string, unknown> {
    return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function exactObject(value: unknown, keys: readonly string[]): Record<string, unknown> {
    if (!isRecord(value)) throw new OwnDeckProtocolError('Expected an object response.');
    const present = Object.keys(value);
    if (present.length !== keys.length || present.some(key => !keys.includes(key))) throw new OwnDeckProtocolError('Response object has an unexpected shape.');
    return value;
}
