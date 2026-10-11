import { Injectable } from '@angular/core';
import { Observable, delay, of, throwError } from 'rxjs';

import type { AuthorCardsService } from '../author-cards.service';
import { AccessApiService } from '../features/own-decks/access/access-api.service';
import {
    AccessDecision,
    AccessGrant,
    AccessRequest,
    CreateInviteRequest,
    CreatedInvite,
    DeckInvite,
    KeysetPage
} from '../features/own-decks/access/access.models';
import type { AuthorCard } from '../public-profile';

const PERSON_WITH_CARD = 'd7000000-0000-4000-8000-000000000001';
const PERSON_ANOTHER = 'd7000000-0000-4000-8000-000000000002';
const PERSON_HIDDEN = 'd7000000-0000-4000-8000-000000000003';
const PERSON_GRANTED = 'd7000000-0000-4000-8000-000000000004';
const SAMPLE_TOKEN = 'q3Jx0u5QmW8bYk2LzTn7VdPaE1sGcH4rFiOe6NhA9Ug';

const CARDS: readonly AuthorCard[] = [
    { accountId: PERSON_WITH_CARD, profileUsername: 'anna.k', displayName: 'Анна', bio: null, avatarPresent: false },
    { accountId: PERSON_ANOTHER, profileUsername: 'mikhail_s', displayName: null, bio: null, avatarPresent: false },
    { accountId: PERSON_GRANTED, profileUsername: 'olga-v', displayName: null, bio: null, avatarPresent: false }
];

const page = <T>(items: readonly T[]): KeysetPage<T> => ({ items, nextCursor: null });

/**
 * The access panel of the styleguide talks to this instead of the server (the page needs no backend): two open requests (one from a
 * person without a public profile), two invitation links and one person with access. Decisions, revocations and new links change a
 * copy kept for the life of the page; the token shown after «Создать ссылку» is an example and opens nothing.
 */
@Injectable()
export class DemoAccessApi implements Pick<AccessApiService, 'createInvite' | 'invites' | 'revokeInvite' | 'requests' | 'decide' | 'grants' | 'grantByLogin' | 'revokeGrant'> {
    private invitesList: DeckInvite[] = [
        { inviteId: 'd7000000-0000-4000-8000-0000000000a1', status: 'ACTIVE', maxUses: 5, uses: 2, expiresAt: '2026-10-25T09:30:00Z', revokedAt: null, createdAt: '2026-10-11T09:30:00Z' },
        { inviteId: 'd7000000-0000-4000-8000-0000000000a2', status: 'EXHAUSTED', maxUses: 1, uses: 1, expiresAt: null, revokedAt: null, createdAt: '2026-10-09T18:00:00Z' }
    ];
    private requestsList: AccessRequest[] = [
        { requesterId: PERSON_WITH_CARD, createdAt: '2026-10-11T09:45:00Z' },
        { requesterId: PERSON_HIDDEN, createdAt: '2026-10-11T08:10:00Z' }
    ];
    private grantsList: AccessGrant[] = [{ granteeId: PERSON_GRANTED, role: 'VIEWER', grantedAt: '2026-10-10T12:00:00Z' }];
    private made = 0;

    createInvite(_deckId: string, request: CreateInviteRequest): Observable<CreatedInvite> {
        const invite: DeckInvite = {
            inviteId: `d7000000-0000-4000-8000-${(0xb0 + (this.made += 1)).toString(16).padStart(12, '0')}`, status: 'ACTIVE',
            maxUses: request.maxUses, uses: 0, revokedAt: null, createdAt: '2026-10-11T12:00:00Z',
            expiresAt: request.expiresInDays === null ? null : '2026-10-18T12:00:00Z'
        };
        this.invitesList = [invite, ...this.invitesList];
        return of({ invite, token: SAMPLE_TOKEN, link: `/d/Kq7xT3mNpR?invite=${SAMPLE_TOKEN}` }).pipe(delay(250));
    }

    invites(): Observable<KeysetPage<DeckInvite>> { return of(page(this.invitesList)).pipe(delay(150)); }

    revokeInvite(_deckId: string, inviteId: string): Observable<DeckInvite> {
        const revoked = { ...this.invitesList.find(invite => invite.inviteId === inviteId)!, status: 'REVOKED' as const, revokedAt: '2026-10-11T12:05:00Z' };
        this.invitesList = this.invitesList.map(invite => invite.inviteId === inviteId ? revoked : invite);
        return of(revoked).pipe(delay(200));
    }

    requests(): Observable<KeysetPage<AccessRequest>> { return of(page(this.requestsList)).pipe(delay(150)); }

    decide(_deckId: string, requesterId: string, decision: 'approve' | 'reject'): Observable<AccessDecision> {
        this.requestsList = this.requestsList.filter(request => request.requesterId !== requesterId);
        if (decision === 'approve') this.grantsList = [{ granteeId: requesterId, role: 'VIEWER', grantedAt: '2026-10-11T12:10:00Z' }, ...this.grantsList];
        return of({ requesterId, status: decision === 'approve' ? 'APPROVED' as const : 'REJECTED' as const, decidedAt: '2026-10-11T12:10:00Z' }).pipe(delay(200));
    }

    grants(): Observable<KeysetPage<AccessGrant>> { return of(page(this.grantsList)).pipe(delay(150)); }

    grantByLogin(_deckId: string, username: string): Observable<AccessGrant> {
        const card = CARDS.find(candidate => candidate.profileUsername.toLowerCase() === username.toLowerCase());
        if (card === undefined) return throwError(() => ({ status: 404, error: { code: 'RESOURCE_NOT_FOUND' } })).pipe(delay(200));
        const grant: AccessGrant = { granteeId: card.accountId, role: 'VIEWER', grantedAt: '2026-10-11T12:15:00Z' };
        this.grantsList = [grant, ...this.grantsList.filter(existing => existing.granteeId !== card.accountId)];
        return of(grant).pipe(delay(250));
    }

    revokeGrant(_deckId: string, granteeId: string): Observable<void> {
        this.grantsList = this.grantsList.filter(grant => grant.granteeId !== granteeId);
        return of(undefined).pipe(delay(200));
    }
}

/** Public cards for the people of the access specimens: one hidden account has none (it reads «Пользователь без публичного профиля»). */
@Injectable()
export class DemoAuthorCards implements Pick<AuthorCardsService, 'cards' | 'avatarUrl'> {
    cards(ids: readonly string[]): Promise<ReadonlyMap<string, AuthorCard>> {
        return new Promise(resolve => setTimeout(() => resolve(new Map(CARDS.filter(card => ids.includes(card.accountId)).map(card => [card.accountId, card] as const))), 150));
    }

    avatarUrl(): string | null { return null; }
}
