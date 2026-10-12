import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, catchError, defer, map, of, throwError } from 'rxjs';

import { appConfig } from '../../app.config';
import { ACCOUNT_ID } from '../../public-profile';
import { CatalogFilters } from '../../shared/catalog-filters';
import { publicFailureOf } from '../public-deck/public-deck.models';
import {
    AuthorDeckPage,
    COMMUNITY_PAGE_SIZE,
    COMMUNITY_QUERY_MAX,
    CommunityDeckPage,
    CommunityFacets,
    CommunityShelves,
    parseAuthorDeckPage,
    parseCommunityDeckPage,
    parseCommunityFacets,
    parseCommunityShelves
} from './community.models';

const NOTHING: CommunityDeckPage = { decks: [], total: 0, totalCapped: false, nextCursor: null };

/** What the reader asked for: the text of the search (trimmed, may be empty) and the filters with the sorting. */
export interface CommunityQuery {
    readonly q: string;
    readonly filters: CatalogFilters;
}

/** The text of a search as it is sent: trimmed and at most 100 code points. */
export function normalizeQuery(text: string | null | undefined): string {
    return Array.from((text ?? '').trim()).slice(0, COMMUNITY_QUERY_MAX).join('').trim();
}

/**
 * The query string of `GET /api/public/community/decks` (`contracts/community/catalog.json`). `media` and `mechanics` are comma-separated lists
 * (the address of the page keeps them as repeated parameters); with a text query the order is relevance, so `sort` is left out.
 */
export function deckParams(query: CommunityQuery, cursor: string | null, limit: number = COMMUNITY_PAGE_SIZE): HttpParams {
    const { filters } = query;
    const q = normalizeQuery(query.q);
    let params = new HttpParams();
    if (q !== '') params = params.set('q', q);
    if (filters.topic !== null) params = params.set('topic', filters.topic);
    if (filters.learn !== null) params = params.set('learn', filters.learn);
    if (filters.level !== null) params = params.set('level', filters.level);
    if (filters.size !== null) params = params.set('size', filters.size);
    if (filters.media.length > 0) params = params.set('media', filters.media.join(','));
    if (filters.mechanics.length > 0) params = params.set('mechanics', filters.mechanics.join(','));
    if (filters.fresh) params = params.set('fresh', 'true');
    if (q === '') params = params.set('sort', filters.sort);
    params = params.set('limit', String(limit));
    return cursor === null ? params : params.set('cursor', cursor);
}

/**
 * The public routes of the community catalogue. Guests work; a signed-in viewer's bearer is attached by the interceptor (the answers
 * are the same, the server only keys its limits by account). Every failure leaves here as a {@link PublicDeckFailure}: 404 is the catalogue
 * being switched off, 429 and 503 carry the delay, 412 is a cursor of an older rank generation (the list starts again).
 */
@Injectable({ providedIn: 'root' })
export class CommunityApiService {
    private readonly http = inject(HttpClient);
    private readonly baseUrl = `${appConfig.learningApiBaseUrl.replace(/\/$/, '')}/public/community`;

    facets(): Observable<CommunityFacets> {
        return this.get('/facets', undefined, parseCommunityFacets);
    }

    shelves(): Observable<CommunityShelves> {
        return this.get('/shelves', undefined, parseCommunityShelves);
    }

    /**
     * A page of browse or search. A 400 means the server does not know a value of the address (a link from an older or shared catalogue: a topic that
     * is gone, a level that is not offered): nothing matches it, so it is an empty page, not a failure the reader could only retry.
     */
    decks(query: CommunityQuery, cursor: string | null = null, limit: number = COMMUNITY_PAGE_SIZE): Observable<CommunityDeckPage> {
        return this.get('/decks', deckParams(query, cursor, limit), body => parseCommunityDeckPage(body, limit), NOTHING);
    }

    /**
     * A page of the decks of an author: his public decks, newest update first, and, for a signed-in viewer with a grant, his invitation-only decks.
     * The page asks Identity for the author's card first (Learning does not know the consent), so an account id that is not an id is a
     * 404 here without a request. 404 is the catalogue being switched off.
     */
    authorDecks(accountId: string, cursor: string | null = null, limit: number = COMMUNITY_PAGE_SIZE): Observable<AuthorDeckPage> {
        const id = accountId.toLowerCase();
        if (!ACCOUNT_ID.test(id)) return throwError(() => publicFailureOf(new HttpErrorResponse({ status: 404 })));
        let params = new HttpParams().set('limit', String(limit));
        if (cursor !== null) params = params.set('cursor', cursor);
        return this.get(`/authors/${id}/decks`, params, body => parseAuthorDeckPage(body, limit));
    }

    private get<T>(path: string, params: HttpParams | undefined, parse: (body: unknown) => T, whenRefused?: T): Observable<T> {
        return defer(() => this.http.get<unknown>(`${this.baseUrl}${path}`, { params })).pipe(
            map(parse),
            catchError((error: unknown) => whenRefused !== undefined && error instanceof HttpErrorResponse && error.status === 400
                ? of(whenRefused) : throwError(() => publicFailureOf(error)))
        );
    }
}
