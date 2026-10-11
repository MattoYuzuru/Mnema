import { HttpClient, HttpHeaders, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, defer, map } from 'rxjs';

import { appConfig } from '../../../app.config';
import { OwnDeckProtocolError, isCanonicalCommandId, isCanonicalEntityId } from '../own-deck.models';
import {
    ForkAutoUpdated,
    ForkCreated,
    ForkDetached,
    ForkRetried,
    parseForkAutoUpdated,
    parseForkCreated,
    parseForkDetached,
    parseForkRetried
} from './fork.models';

/** The public code a copy is made from: ten characters of base58. */
const SOURCE_CODE = /^[1-9A-HJ-NP-Za-km-z]{10}$/u;

/**
 * The commands of a copy (`contracts/decks/forks.json`): «Добавить к себе», «Повторить», «Отвязать от автора» and the setting «Принимать безопасное автоматически». The state of
 * a copy is not read here: it is the `fork` member of the owner's deck JSON. A failure is the raw `HttpErrorResponse` (see
 * `forkFailureOf`); an answer that does not match the contract is an `OwnDeckProtocolError`. Nothing here is logged.
 */
@Injectable({ providedIn: 'root' })
export class ForkApiService {
    private readonly http = inject(HttpClient);
    private readonly baseUrl = appConfig.learningApiBaseUrl.replace(/\/$/, '');

    /**
     * Copies the deck behind `sourceCode` into the caller's library in O(1); the copy is filled by a background job. Idempotent by
     * `commandId` and by (owner, source): a second request answers 200 with the existing copy. A retry after an unknown outcome
     * must send the same `commandId`.
     */
    copy(sourceCode: string, commandId: string): Observable<ForkCreated> {
        return defer(() => {
            if (!SOURCE_CODE.test(sourceCode) || !isCanonicalCommandId(commandId)) throw new OwnDeckProtocolError('Invalid copy command.');
            return this.http.post<unknown>(`${this.baseUrl}/decks/forks`, { commandId, sourceCode }, { observe: 'response' });
        }).pipe(map(response => parseForkCreated(privateBody(response), response.status, commandId)));
    }

    retry(deckId: string): Observable<ForkRetried> {
        return defer(() => this.http.post<unknown>(`${this.copyPath(deckId)}:retry`, null, { observe: 'response' }))
            .pipe(map(response => parseForkRetried(privateBody(response), deckId)));
    }

    detach(deckId: string): Observable<ForkDetached> {
        return defer(() => this.http.post<unknown>(`${this.copyPath(deckId)}:detach`, null, { observe: 'response' }))
            .pipe(map(response => parseForkDetached(privateBody(response), deckId)));
    }

    /**
     * «Принимать безопасное автоматически»: stores the owner's setting and answers the stored value. A state, not a command: the same
     * value again changes nothing, so a retry needs no command id. A preparing copy is 409 `DECK_PREPARING`, a detached one 409
     * `CAPABILITY_UNAVAILABLE` (see `forkFailureOf`).
     */
    setAutoUpdate(deckId: string, autoUpdate: boolean): Observable<ForkAutoUpdated> {
        return defer(() => this.http.put<unknown>(`${this.copyPath(deckId)}:auto-update`, { autoUpdate }, { observe: 'response' }))
            .pipe(map(response => parseForkAutoUpdated(privateBody(response), deckId)));
    }

    private copyPath(deckId: string): string {
        if (!isCanonicalEntityId(deckId)) throw new OwnDeckProtocolError('Invalid deck ID.');
        return `${this.baseUrl}/decks/${encodeURIComponent(deckId.toLowerCase())}/fork`;
    }
}

function privateBody(response: HttpResponse<unknown>): unknown {
    const directives = cacheDirectives(response.headers);
    if (!directives.includes('private') || !directives.includes('no-store')) throw new OwnDeckProtocolError('Private response can be cached.');
    return response.body;
}

function cacheDirectives(headers: HttpHeaders): string[] {
    return (headers.get('Cache-Control') ?? '').toLowerCase().split(',').map(value => value.trim());
}
