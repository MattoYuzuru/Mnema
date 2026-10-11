import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';

import access from '../../../../../../contracts/decks/access.json';
import notifications from '../../../../../../contracts/notifications/notifications.json';
import { AuthoringProtocolError } from '../../authoring/authoring.models';
import { presentNotification } from '../../../core/notifications/notification-presenter';
import type { AppNotification } from '../../../core/notifications/notification.models';
import { AccessApiService } from './access-api.service';
import {
    ACTIVE_INVITES_LIMIT,
    DECISION_STATUSES,
    EXPIRES_IN_DAYS_RANGE,
    GRANTS_LIMIT,
    INVITE_STATUSES,
    LOGIN_PATTERN,
    MAX_USES_RANGE,
    PAGE_LIMIT,
    absoluteInviteLink,
    accessFailureOf,
    normalizeLogin,
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
import { ACCESS_TEXT, accessProblemText, closeAccessLabel, requestActionLabel, expiryOption, inviteStatusWord, inviteUsesText, levelChangeLines, usesOption } from './access.text';

/**
 * contracts/decks/access.json is the wire contract of invitations, access requests and grants. Every fixture must parse with the
 * production parsers, every request must serialize to its fixture, the token must stay out of every URL, and the constants hold here too.
 */
describe('Access wire contract (contracts/decks/access.json)', () => {
    const deckId = access.invites.create.request.path.split('/')[3];
    const headers = { 'Cache-Control': 'private, no-store' };
    let http: HttpTestingController;
    let api: AccessApiService;

    beforeEach(() => {
        TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
        http = TestBed.inject(HttpTestingController);
        api = TestBed.inject(AccessApiService);
    });
    afterEach(() => http.verify());

    it('shares its constants with the client', () => {
        expect(INVITE_STATUSES).toEqual(access.constants.inviteStatuses);
        expect(DECISION_STATUSES.every(status => access.constants.requestStatuses.includes(status))).toBe(true);
        expect(MAX_USES_RANGE).toEqual({ min: access.constants.maxUses.min, max: access.constants.maxUses.max });
        expect(EXPIRES_IN_DAYS_RANGE).toEqual({ min: access.constants.expiresInDays.min, max: access.constants.expiresInDays.max });
        expect(ACTIVE_INVITES_LIMIT).toBe(access.constants.limits.activeInvitesPerDeck);
        expect(PAGE_LIMIT).toBe(access.constants.limits.page.default);
        expect(LOGIN_PATTERN.test('anna.k')).toBe(true);
        expect(access.constants.usernamePattern.startsWith('^[A-Za-z0-9_.-]{3,50}$')).toBe(true);
        expect(LOGIN_PATTERN.test('ab')).toBe(false);
        expect(LOGIN_PATTERN.test('a b c')).toBe(false);
    });

    it('sends the creation as the fixture and shows the token and the link once', async () => {
        const request = access.invites.create.request;
        const created = firstValueFrom(api.createInvite(deckId, request.body));
        const sent = http.expectOne(request.path);
        expect(sent.request.method).toBe('POST');
        expect(sent.request.body).toEqual(request.body);
        expect(sent.request.urlWithParams).not.toContain('token');
        sent.flush(access.invites.create.answer.body, { status: 201, statusText: 'Created', headers });
        const answer = await created;
        expect(answer).toEqual(access.invites.create.answer.body);
        expect(answer.link).toBe(`/d/Kq7xT3mNpR?invite=${answer.token}`);
        expect(absoluteInviteLink(answer.link, 'https://mnema.app')).toBe(`https://mnema.app${answer.link}`);
    });

    it('reads the invitation list, revokes one, and never finds a token in a list', async () => {
        const reading = firstValueFrom(api.invites(deckId, null));
        http.expectOne(`/api/decks/${deckId}/invites?limit=20`).flush(access.invites.list.answer, { headers });
        const page = await reading;
        expect(page).toEqual(access.invites.list.answer);
        expect(JSON.stringify(page)).not.toContain('token');

        const next = firstValueFrom(api.invites(deckId, 'abc'));
        http.expectOne(`/api/decks/${deckId}/invites?limit=20&cursor=abc`).flush(access.invites.list.answer, { headers });
        await next;

        const inviteId = access.invites.revoke.answer.inviteId;
        const revoking = firstValueFrom(api.revokeInvite(deckId, inviteId));
        const sent = http.expectOne(`/api/decks/${deckId}/invites/${inviteId}:revoke`);
        expect(sent.request.method).toBe('POST');
        sent.flush(access.invites.revoke.answer, { headers });
        expect((await revoking).status).toBe('REVOKED');
    });

    it('redeems with the token in the body only, and asks for access with no body', async () => {
        const redeeming = firstValueFrom(api.redeem(access.invites.redeem.request.body.token));
        const redeem = http.expectOne('/api/invites:redeem');
        expect(redeem.request.body).toEqual(access.invites.redeem.request.body);
        expect(redeem.request.urlWithParams).not.toContain(access.invites.redeem.request.body.token);
        redeem.flush(access.invites.redeem.answer.body, { headers });
        expect(await redeeming).toBe('Kq7xT3mNpR');

        const asking = firstValueFrom(api.requestAccess('Kq7xT3mNpR'));
        const ask = http.expectOne('/api/public/decks/Kq7xT3mNpR/access-requests');
        expect(ask.request.method).toBe('POST');
        expect(ask.request.body).toBeNull();
        ask.flush(access.requests.send.answer.body, { status: 202, statusText: 'Accepted', headers });
        expect(await asking).toBe('PENDING');
        expect(() => api.requestAccess('../x')).toThrowError(AuthoringProtocolError);
    });

    it('withdraws an open request with no body and takes any 204 for success', async () => {
        const fixture = access.requests.withdraw;
        const withdrawing = firstValueFrom(api.withdrawRequest('Kq7xT3mNpR'));
        const sent = http.expectOne('/api/public/decks/Kq7xT3mNpR/access-requests:withdraw');
        expect(fixture.request).toContain(':withdraw');
        expect(sent.request.method).toBe('POST');
        expect(sent.request.body).toBeNull();
        sent.flush(null, { status: 204, statusText: 'No Content', headers });
        await expect(withdrawing).resolves.toBeUndefined();
        expect(() => api.withdrawRequest('../x')).toThrowError(AuthoringProtocolError);
    });

    it('lists the open requests and sends the two decisions', async () => {
        const reading = firstValueFrom(api.requests(deckId, null));
        http.expectOne(`/api/decks/${deckId}/access-requests?limit=20`).flush(access.requests.list.answer, { headers });
        expect(await reading).toEqual(access.requests.list.answer);

        for (const decision of ['approve', 'reject'] as const) {
            const fixture = access.requests[decision];
            const requesterId = fixture.answer.requesterId;
            const deciding = firstValueFrom(api.decide(deckId, requesterId, decision));
            const sent = http.expectOne(`/api/decks/${deckId}/access-requests/${requesterId}:${decision}`);
            expect(sent.request.method).toBe('POST');
            sent.flush(fixture.answer, { headers });
            expect(await deciding).toEqual(fixture.answer);
        }
        const mismatch = firstValueFrom(api.decide(deckId, access.requests.list.answer.items[0].requesterId, 'approve'));
        http.expectOne(request => request.url.endsWith(':approve')).flush({ ...access.requests.approve.answer, requesterId: '0192f3a4-5b6c-7d8e-9f01-23456789abce' }, { headers });
        await expect(mismatch).rejects.toThrowError(AuthoringProtocolError);
    });

    it('lists, adds by login and revokes grants', async () => {
        const reading = firstValueFrom(api.grants(deckId, null));
        http.expectOne(`/api/decks/${deckId}/grants?limit=20`).flush(access.grants.list.answer, { headers });
        expect(await reading).toEqual(access.grants.list.answer);

        const adding = firstValueFrom(api.grantByLogin(deckId, access.grants.add.request.body.username));
        const add = http.expectOne(access.grants.add.request.path);
        expect(add.request.body).toEqual(access.grants.add.request.body);
        add.flush(access.grants.add.answer, { headers });
        expect(await adding).toEqual(access.grants.add.answer);

        const granteeId = access.grants.list.answer.items[0].granteeId;
        const revoking = firstValueFrom(api.revokeGrant(deckId, granteeId));
        const revoke = http.expectOne(`/api/decks/${deckId}/grants/${granteeId}`);
        expect(revoke.request.method).toBe('DELETE');
        revoke.flush(null, { status: 204, statusText: 'No Content' });
        await expect(revoking).resolves.toBeUndefined();
    });

    it('refuses an answer that can be cached', async () => {
        const reading = firstValueFrom(api.invites(deckId, null));
        http.expectOne(`/api/decks/${deckId}/invites?limit=20`).flush(access.invites.list.answer);
        await expect(reading).rejects.toThrowError(AuthoringProtocolError);
    });

    it('parses the fixtures exactly and rejects every broken shape', () => {
        const created = access.invites.create.answer.body;
        expect(parseCreatedInvite(created).invite.status).toBe('ACTIVE');
        expect(parseInvitePage(access.invites.list.answer).items[0].maxUses).toBeNull();
        expect(parseRequestPage(access.requests.list.answer).items).toHaveLength(1);
        expect(parseGrantPage(access.grants.list.answer).items[0].role).toBe('VIEWER');
        expect(parseDecision(access.requests.reject.answer).status).toBe('REJECTED');
        expect(parseGrant(access.grants.add.answer).granteeId).toBe(access.grants.add.answer.granteeId);
        expect(parseRedeemed(access.invites.redeem.answer.body)).toBe('Kq7xT3mNpR');
        expect(parseRequestSent({ status: 'PENDING' })).toBe('PENDING');

        const invite = created.invite;
        const broken: (() => unknown)[] = [
            () => parseCreatedInvite({ ...created, token: 'short' }),
            () => parseCreatedInvite({ ...created, link: '/d/Kq7xT3mNpR?invite=q3Jx0u5QmW8bYk2LzTn7VdPaE1sGcH4rFiOe6NhA9Uh' }),
            () => parseCreatedInvite({ ...created, extra: 1 }),
            () => parseInvite({ ...invite, status: 'PAUSED' }),
            () => parseInvite({ ...invite, status: 'REVOKED' }),
            () => parseInvite({ ...invite, maxUses: 0 }),
            () => parseInvite({ ...invite, uses: 2 }),
            () => parseInvite({ ...invite, expiresAt: 'tomorrow' }),
            () => parseInvitePage({ items: [], nextCursor: '' }),
            () => parseInvitePage({ items: [invite] }),
            () => parseGrant({ ...access.grants.add.answer, role: 'EDITOR' }),
            () => parseDecision({ ...access.requests.approve.answer, status: 'WITHDRAWN' }),
            () => parseRedeemed({ code: '../x' }),
            () => parseRequestSent({ status: 'APPROVED' })
        ];
        for (const parse of broken) expect(parse).toThrowError(AuthoringProtocolError);
    });

    it('maps the problems of the contract to plain words', () => {
        const failure = (problem: { status: number; code: string }) => accessFailureOf(new HttpErrorResponse({ status: problem.status, error: problem }))!;
        expect(failure(access.problems.notFound)).toEqual({ status: 404, code: 'RESOURCE_NOT_FOUND', limit: null });
        expect(failure(access.problems.publicationRequired).code).toBe('PUBLICATION_REQUIRED');
        expect(failure(access.problems.openRequestLimit).status).toBe(422);
        expect(failure(access.problems.grantLimit).limit).toBe(GRANTS_LIMIT);
        expect(GRANTS_LIMIT).toBe(access.constants.limits.grantsPerDeck);
        expect(accessProblemText(failure(access.problems.grantLimit))).toBe(ACCESS_TEXT.grantsFull);
        expect(accessProblemText(failure(access.problems.openRequestLimit))).toBe(ACCESS_TEXT.unknown);
        expect(accessProblemText(failure(access.problems.notFound))).toBe(ACCESS_TEXT.gone);
        expect(accessProblemText(failure(access.problems.requestsOff))).toBe(ACCESS_TEXT.unknown);
        expect(accessProblemText(accessFailureOf(new HttpErrorResponse({ status: 0 })))).toBe(ACCESS_TEXT.network);
        expect(accessProblemText(null)).toBe(ACCESS_TEXT.network);
        expect(accessFailureOf(new Error('x'))).toBeNull();
    });

    it('says the notification kinds of the contract in words and routes them', () => {
        const kinds = notifications.kinds as unknown as Record<string, { example: AppNotification }>;
        const requested = presentNotification(kinds['DECK_ACCESS_REQUESTED'].example)!;
        expect(requested.text).toBe('3 запроса доступа к вашей колоде ждут ответа');
        expect(requested.link).toEqual({ label: 'Открыть доступ', commands: ['/decks', '11111111-1111-4111-8111-111111111111'], fragment: 'access' });
        const granted = presentNotification(kinds['DECK_ACCESS_GRANTED'].example)!;
        expect(granted.text).toContain('колода теперь доступна вам');
        expect(granted.link).toEqual({ label: 'Открыть колоду', commands: ['/d', 'Kq7xT3mNpR'] });
        expect(requested.text + granted.text).not.toMatch(/@|anna/);
    });

    it('words the choices of the create form and the rows of the list', () => {
        expect([null, 1, 5, 21].map(usesOption)).toEqual(['Без ограничения', 'Один раз', '5 раз', '21 раз']);
        expect([null, 1, 7, 30].map(expiryOption)).toEqual(['Без срока', '1 день', '7 дней', '30 дней']);
        expect(INVITE_STATUSES.map(inviteStatusWord)).toEqual(['Действует', 'Срок вышел', 'Использована', 'Отозвана', 'Устарела']);
        const [fresh] = parseInvitePage({ items: [{ ...access.invites.list.answer.items[0], maxUses: 1, uses: 0 }], nextCursor: null }).items;
        expect(inviteUsesText(fresh)).toBe('Одноразовая, ещё не открыта');
        expect(inviteUsesText({ ...fresh, uses: 1 })).toBe('Одноразовая, открыта');
        expect(inviteUsesText({ ...fresh, maxUses: 5, uses: 2 })).toBe('Открыли: 2 из 5');
        expect(inviteUsesText({ ...fresh, maxUses: null, uses: 3 })).toBe('Открыли: 3');
        expect(inviteUsesText({ ...fresh, status: 'STALE' })).toContain('Ссылка устарела');
    });

    it('tells the owner what a level change does before it is applied', () => {
        const lines = (from: Parameters<typeof levelChangeLines>[0], to: Parameters<typeof levelChangeLines>[1]): string => levelChangeLines(from, to).join(' ');
        expect(lines('PRIVATE', 'LINK')).toContain('будет опубликована сейчас');
        expect(lines('PRIVATE', 'INVITE')).toContain('будет опубликована сейчас');
        // Only consequences: who sees the deck is the picker's own live hint.
        for (const [from, to] of [['PRIVATE', 'LINK'], ['LINK', 'INVITE'], ['PUBLIC', 'PRIVATE'], ['INVITE', 'LINK']] as const) {
            expect(lines(from, to)).not.toMatch(/Колоду (откроет|откроют|увидите)/);
        }
        expect(lines('INVITE', 'LINK')).toContain('Ссылка останется прежней');
        expect(lines('PUBLIC', 'LINK')).toContain('пропадёт из каталога');
        expect(lines('PUBLIC', 'LINK')).toContain('старые ссылки перестанут работать');
        expect(lines('LINK', 'INVITE')).toContain('старые ссылки перестанут работать');
        expect(lines('INVITE', 'PRIVATE')).toContain('Выданные доступы сохранятся');
        expect(lines('INVITE', 'PRIVATE')).toContain('Пригласительные ссылки перестанут работать');
        expect(lines('LINK', 'PRIVATE')).not.toContain('Выданные доступы');
        expect(lines('PUBLIC', 'LINK')).toContain('Пригласительные ссылки перестанут работать');
        expect(lines('LINK', 'INVITE')).toContain('Пригласительные ссылки перестанут работать');
        expect(lines('INVITE', 'LINK')).not.toContain('Пригласительные ссылки перестанут работать');
        expect(lines('LINK', 'PUBLIC')).toContain('несколько пунктов');
        expect(requestActionLabel('Принять', { state: 'loading' })).toBeNull();
        expect(closeAccessLabel({ state: 'card', username: 'anna.k', avatarSrc: null })).toBe('Закрыть доступ: @anna.k');
        expect(closeAccessLabel({ state: 'none' })).toBe('Закрыть доступ: пользователь без публичного профиля');
    });

    it('trims the typed login and its leading at-sign', () => {
        expect(normalizeLogin('  @anna.k ')).toBe('anna.k');
        expect(normalizeLogin('anna.k')).toBe('anna.k');
    });
});
