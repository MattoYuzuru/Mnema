import { HttpClient, HttpHeaders, HttpParams, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, defer, map } from 'rxjs';

import { appConfig } from '../../../app.config';
import { OwnDeckProtocolError, isCanonicalCommandId, isCanonicalEntityId } from '../own-deck.models';
import {
    HISTORY_DEFAULT_LIMIT,
    HISTORY_MAX_LIMIT,
    REVERT_MAX_WITH,
    RevertCommand,
    RevertibleKind,
    Reverted,
    UpdateHistory,
    isRevertibleKind,
    parseReverted,
    parseSeen,
    parseUpdateHistory,
    splitUnitId
} from './update-history.models';

/**
 * «Что нового» of a copy (`contracts/decks/updates.json`, Updates/6 part B): the applies that made a revision of the copy, marking one seen,
 * and taking a unit back. Every answer must be `Cache-Control: private, no-store`. A failure is the raw `HttpErrorResponse` (see
 * `revertFailureOf`); an answer that does not match the contract is an `OwnDeckProtocolError`. Reading writes nothing on the server.
 * Nothing here is logged.
 */
@Injectable({ providedIn: 'root' })
export class UpdateHistoryApiService {
    private readonly http = inject(HttpClient);
    private readonly baseUrl = appConfig.learningApiBaseUrl.replace(/\/$/, '');

    /** The newest `limit` entries (1..20), newest first. */
    history(deckId: string, limit: number = HISTORY_DEFAULT_LIMIT): Observable<UpdateHistory> {
        return defer(() => {
            if (!Number.isInteger(limit) || limit < 1 || limit > HISTORY_MAX_LIMIT) throw new OwnDeckProtocolError('Invalid history limit.');
            return this.http.get<unknown>(`${this.deckPath(deckId)}/update-history`, { params: new HttpParams().set('limit', String(limit)), observe: 'response' });
        }).pipe(map(response => parseUpdateHistory(privateBody(response), deckId, limit)));
    }

    /** Marks the entry seen: the notice `fork.lastUpdate` of the deck stops showing it. Idempotent. */
    markSeen(deckId: string, deckRevisionId: string): Observable<true> {
        return defer(() => {
            if (!isCanonicalEntityId(deckRevisionId)) throw new OwnDeckProtocolError('Invalid history entry.');
            return this.http.post<unknown>(`${this.deckPath(deckId)}/update-history/${encodeURIComponent(deckRevisionId.toLowerCase())}:seen`, null, { observe: 'response' });
        }).pipe(map(response => parseSeen(privateBody(response), deckId, deckRevisionId)));
    }

    /**
     * Takes the unit `unitId` (`KIND:key`, a material or an exercise) of the entry back to what it was before the apply, with the units of `command.with`.
     * Idempotent by `commandId`: a retry after an unknown outcome must send the same command, the same `with` included.
     */
    revert(deckId: string, deckRevisionId: string, unitId: string, command: RevertCommand): Observable<Reverted> {
        return defer(() => {
            const unit = splitUnitId(unitId);
            if (!isCanonicalEntityId(deckRevisionId) || unit === null || !isRevertibleKind(unit.kind) || !isCanonicalCommandId(command.commandId)
                || command.with.length > REVERT_MAX_WITH || command.with.some(item => splitUnitId(item) === null)) throw new OwnDeckProtocolError('Invalid revert command.');
            const kind: RevertibleKind = unit.kind;
            const path = `${this.deckPath(deckId)}/update-history/${encodeURIComponent(deckRevisionId.toLowerCase())}/units/${kind}/${encodeURIComponent(unit.key)}:revert`;
            const body = command.with.length === 0 ? { commandId: command.commandId } : { commandId: command.commandId, with: command.with };
            return this.http.post<unknown>(path, body, { observe: 'response' });
        }).pipe(map(response => parseReverted(privateBody(response), response.status, command.commandId, unitId)));
    }

    private deckPath(deckId: string): string {
        if (!isCanonicalEntityId(deckId)) throw new OwnDeckProtocolError('Invalid deck ID.');
        return `${this.baseUrl}/decks/${encodeURIComponent(deckId.toLowerCase())}`;
    }
}

function privateBody(response: HttpResponse<unknown>): unknown {
    const directives = cacheDirectives(response.headers);
    if (response.status !== 200 || !directives.includes('private') || !directives.includes('no-store')) throw new OwnDeckProtocolError('Private response can be cached.');
    return response.body;
}

function cacheDirectives(headers: HttpHeaders): string[] {
    return (headers.get('Cache-Control') ?? '').toLowerCase().split(',').map(value => value.trim());
}
