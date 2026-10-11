import { HttpClient, HttpParams, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, defer, map } from 'rxjs';

import { appConfig } from '../../../app.config';
import { AuthoringProtocolError, requireEntity } from '../../authoring/authoring.models';
import {
    AccessDecision,
    AccessGrant,
    AccessRequest,
    CreateInviteRequest,
    CreatedInvite,
    DeckInvite,
    KeysetPage,
    PAGE_LIMIT,
    parseCreatedInvite,
    parseDecision,
    parseGrant,
    parseGrantPage,
    parseInvite,
    parseInvitePage,
    parseRedeemed,
    parseRequestPage,
    parseRequestSent
} from './access.models';

const CODE_PATTERN = /^[A-Za-z0-9_-]{4,64}$/;

/**
 * Invitations, access requests and grants (contracts/decks/access.json). Owner routes live under `/decks/{id}`; redeeming a link and
 * asking for access are the two calls of the signed-in visitor of the public view. Every answer is private and not stored.
 * The invitation token travels only in the body of `redeem` and comes back only from `createInvite`; it never enters a URL.
 * A failure is the raw `HttpErrorResponse`: see `accessFailureOf`.
 */
@Injectable({ providedIn: 'root' })
export class AccessApiService {
    private readonly http = inject(HttpClient);
    private readonly baseUrl = appConfig.learningApiBaseUrl.replace(/\/$/, '');

    createInvite(deckId: string, request: CreateInviteRequest): Observable<CreatedInvite> {
        return defer(() => this.http.post<unknown>(`${this.deck(deckId)}/invites`, { maxUses: request.maxUses, expiresInDays: request.expiresInDays }, { observe: 'response' }))
            .pipe(map(response => parseCreatedInvite(privateBody(response))));
    }

    invites(deckId: string, cursor: string | null): Observable<KeysetPage<DeckInvite>> {
        return this.page(`${this.deck(deckId)}/invites`, cursor, parseInvitePage);
    }

    revokeInvite(deckId: string, inviteId: string): Observable<DeckInvite> {
        return defer(() => this.http.post<unknown>(`${this.deck(deckId)}/invites/${encodeURIComponent(requireEntity(inviteId))}:revoke`, null, { observe: 'response' }))
            .pipe(map(response => parseInvite(privateBody(response))));
    }

    requests(deckId: string, cursor: string | null): Observable<KeysetPage<AccessRequest>> {
        return this.page(`${this.deck(deckId)}/access-requests`, cursor, parseRequestPage);
    }

    decide(deckId: string, requesterId: string, decision: 'approve' | 'reject'): Observable<AccessDecision> {
        const path = `${this.deck(deckId)}/access-requests/${encodeURIComponent(requireEntity(requesterId))}:${decision}`;
        return defer(() => this.http.post<unknown>(path, null, { observe: 'response' })).pipe(map(response => {
            const answer = parseDecision(privateBody(response));
            if (answer.requesterId !== requesterId.toLowerCase()) throw new AuthoringProtocolError('Decision requester mismatch.');
            return answer;
        }));
    }

    grants(deckId: string, cursor: string | null): Observable<KeysetPage<AccessGrant>> {
        return this.page(`${this.deck(deckId)}/grants`, cursor, parseGrantPage);
    }

    grantByLogin(deckId: string, username: string): Observable<AccessGrant> {
        return defer(() => this.http.post<unknown>(`${this.deck(deckId)}/grants`, { username }, { observe: 'response' }))
            .pipe(map(response => parseGrant(privateBody(response))));
    }

    revokeGrant(deckId: string, granteeId: string): Observable<void> {
        return defer(() => this.http.delete<void>(`${this.deck(deckId)}/grants/${encodeURIComponent(requireEntity(granteeId))}`, { observe: 'response' }))
            .pipe(map(() => undefined));
    }

    /** The visitor opened `/d/{code}?invite={token}`: exchanges the token for the CURRENT public code of the deck. */
    redeem(token: string): Observable<string> {
        return defer(() => this.http.post<unknown>(`${this.baseUrl}/invites:redeem`, { token }, { observe: 'response' }))
            .pipe(map(response => parseRedeemed(privateBody(response))));
    }

    /** «Запросить доступ» on the invitation-only screen: always `PENDING`, whether new, repeated or silently refused. */
    requestAccess(code: string): Observable<'PENDING'> {
        if (!CODE_PATTERN.test(code)) throw new AuthoringProtocolError('Invalid public code.');
        return defer(() => this.http.post<unknown>(`${this.baseUrl}/public/decks/${encodeURIComponent(code)}/access-requests`, null, { observe: 'response' }))
            .pipe(map(response => parseRequestSent(privateBody(response))));
    }

    /** The requester takes an open request back (204 whatever its state; an unknown or rotated code is the opaque 404). */
    withdrawRequest(code: string): Observable<void> {
        if (!CODE_PATTERN.test(code)) throw new AuthoringProtocolError('Invalid public code.');
        return defer(() => this.http.post<void>(`${this.baseUrl}/public/decks/${encodeURIComponent(code)}/access-requests:withdraw`, null, { observe: 'response' }))
            .pipe(map(() => undefined));
    }

    private page<T>(url: string, cursor: string | null, parse: (value: unknown) => KeysetPage<T>): Observable<KeysetPage<T>> {
        let params = new HttpParams().set('limit', PAGE_LIMIT);
        if (cursor !== null) params = params.set('cursor', cursor);
        return defer(() => this.http.get<unknown>(url, { params, observe: 'response' })).pipe(map(response => parse(privateBody(response))));
    }

    private deck(deckId: string): string {
        return `${this.baseUrl}/decks/${encodeURIComponent(requireEntity(deckId))}`;
    }
}

/** The body of an answer that must be private and not stored (it may carry a secret or a person). */
function privateBody(response: HttpResponse<unknown>): unknown {
    const cache = (response.headers.get('Cache-Control') ?? '').toLowerCase().split(',').map(value => value.trim());
    if (!cache.includes('private') || !cache.includes('no-store')) throw new AuthoringProtocolError('Private response can be cached.');
    return response.body;
}
