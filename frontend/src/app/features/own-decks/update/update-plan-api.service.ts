import { HttpClient, HttpHeaders, HttpParams, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { MonoTypeOperatorFunction, Observable, defer, map, retry, throwError, timer } from 'rxjs';

import { appConfig } from '../../../app.config';
import { OwnDeckProtocolError, isCanonicalCommandId, isCanonicalEntityId } from '../own-deck.models';
import {
    ApplyAnswer,
    ApplyCommand,
    PLAN_PAGE_LIMIT,
    UnitSides,
    UpdatePlanPage,
    WIRE_KINDS,
    WireKind,
    parseApplyAnswer,
    parseUnitSides,
    parseUpdatePlan,
    retryAfterSeconds,
    updateFailureOf
} from './update-plan.models';

const PLAN_VERSION = /^sha256:[0-9a-f]{64}$/u;
/** The longest pause before the one automatic retry of a busy server: a longer wish of the server is left to the reader's «Повторить». */
const BUSY_PAUSE_MAX_SECONDS = 10;

/**
 * A plan or unit request answered 503 `UPDATE_PLAN_BUSY` (nothing was computed) is asked once more after the pause `Retry-After` names
 * (one second when it names none, at most ten); a second «busy», or any other failure, reaches the caller unchanged.
 */
function retryOnceWhenBusy<T>(): MonoTypeOperatorFunction<T> {
    return retry({
        count: 1,
        delay: error => updateFailureOf(error) === 'busy'
            ? timer(Math.min(retryAfterSeconds(error) ?? 1, BUSY_PAUSE_MAX_SECONDS) * 1000)
            : throwError(() => error)
    });
}

/**
 * The update plan of a copy (`contracts/decks/updates.json`): a page of the plan, the two sides of one unit, and the apply command.
 * Plan and unit answers must be `Cache-Control: private, no-store` like every private-chain answer. A failure is the raw
 * `HttpErrorResponse` (see `updateFailureOf`); an answer that does not match the contract is an `OwnDeckProtocolError`. Reading
 * writes nothing on the server. Nothing here is logged.
 */
@Injectable({ providedIn: 'root' })
export class UpdatePlanApiService {
    private readonly http = inject(HttpClient);
    private readonly baseUrl = appConfig.learningApiBaseUrl.replace(/\/$/, '');

    /** One page of the plan; `cursor` is the opaque `nextCursor` of the previous page (bound to the plan version it came from). */
    plan(deckId: string, cursor: string | null = null, limit: number = PLAN_PAGE_LIMIT): Observable<UpdatePlanPage> {
        return defer(() => {
            let params = new HttpParams().set('limit', String(limit));
            if (cursor !== null) params = params.set('cursor', cursor);
            return this.http.get<unknown>(`${this.deckPath(deckId)}/update-plan`, { params, observe: 'response' });
        }).pipe(map(response => parseUpdatePlan(privateBody(response), deckId)), retryOnceWhenBusy());
    }

    /** The two sides of exactly one unit of the plan `planVersion` names; another plan version is 409 `UPDATE_PLAN_STALE`. */
    unit(deckId: string, kind: WireKind, key: string, planVersion: string): Observable<UnitSides> {
        return defer(() => {
            if (!WIRE_KINDS.includes(kind) || !isCanonicalEntityId(key) || !PLAN_VERSION.test(planVersion)) throw new OwnDeckProtocolError('Invalid unit request.');
            const params = new HttpParams().set('planVersion', planVersion);
            return this.http.get<unknown>(`${this.deckPath(deckId)}/update-plan/units/${kind}/${encodeURIComponent(key.toLowerCase())}`, { params, observe: 'response' });
        }).pipe(map(response => parseUnitSides(privateBody(response), { kind, key, planVersion })), retryOnceWhenBusy());
    }

    /**
     * Applies the reader's choices. Idempotent by `commandId`: a retry after an unknown outcome must send the same command. 200 is
     * `APPLIED`, 202 the job runs (`APPLYING`).
     */
    apply(deckId: string, command: ApplyCommand): Observable<ApplyAnswer> {
        return defer(() => {
            if (!isCanonicalCommandId(command.commandId) || !PLAN_VERSION.test(command.planVersion)) throw new OwnDeckProtocolError('Invalid apply command.');
            return this.http.post<unknown>(`${this.deckPath(deckId)}/update-plan:apply`, command, { observe: 'response' });
        }).pipe(map(response => parseApplyAnswer(response.body, response.status, command.commandId)));
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
