import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, catchError, defer, map, throwError } from 'rxjs';

import { appConfig } from '../../app.config';
import {
    PUBLIC_DECK_CODE,
    PUBLIC_PAGE_SIZE,
    PublicDeck,
    PublicDeckFailure,
    PublicExercisePage,
    PublicMaterialDocument,
    PublicMaterialPage,
    parsePublicDeck,
    parsePublicExercisePage,
    parsePublicMaterialDocument,
    parsePublicMaterialPage,
    publicFailureOf
} from './public-deck.models';
import { isCanonicalEntityId } from '../own-decks/own-deck.models';

/**
 * Read-only routes of someone else's deck behind its public code (`contracts/decks/public-read.json`). A guest sends no
 * credential; a signed-in viewer's bearer is attached by the interceptor so the server can resolve OWNER or GRANTEE.
 * Every failure leaves here as a {@link PublicDeckFailure}; a code that cannot be a code is `not-found` without a request.
 */
@Injectable({ providedIn: 'root' })
export class PublicDeckApiService {
    private readonly http = inject(HttpClient);
    private readonly baseUrl = `${appConfig.learningApiBaseUrl.replace(/\/$/, '')}/public/decks`;

    summary(code: string): Observable<PublicDeck> {
        return this.get(code, '', undefined, body => parsePublicDeck(body, code));
    }

    materials(code: string, cursor: string | null = null, limit = PUBLIC_PAGE_SIZE): Observable<PublicMaterialPage> {
        return this.get(code, '/items', page(cursor, limit), body => parsePublicMaterialPage(body, code, limit));
    }

    material(code: string, memberKey: string): Observable<PublicMaterialDocument> {
        if (!isCanonicalEntityId(memberKey)) return throwError(() => new PublicDeckFailure('not-found'));
        return this.get(code, `/items/${encodeURIComponent(memberKey.toLowerCase())}`, undefined, body => parsePublicMaterialDocument(body, code, memberKey));
    }

    exercises(code: string, cursor: string | null = null, limit = PUBLIC_PAGE_SIZE): Observable<PublicExercisePage> {
        return this.get(code, '/exercises', page(cursor, limit), body => parsePublicExercisePage(body, code, limit));
    }

    private get<T>(code: string, suffix: string, params: HttpParams | undefined, parse: (body: unknown) => T): Observable<T> {
        if (!PUBLIC_DECK_CODE.test(code)) return throwError(() => new PublicDeckFailure('not-found'));
        return defer(() => this.http.get<unknown>(`${this.baseUrl}/${code}${suffix}`, { params })).pipe(
            map(parse),
            catchError((error: unknown) => throwError(() => publicFailureOf(error)))
        );
    }
}

function page(cursor: string | null, limit: number): HttpParams {
    const params = new HttpParams().set('limit', String(limit));
    return cursor === null ? params : params.set('cursor', cursor);
}
