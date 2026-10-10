import { HttpErrorResponse, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import fixture from '../../../contracts/identity/public-profile.json';
import { AUTHOR_CARD_TTL_MS, AuthorCardsService } from './author-cards.service';
import { AUTH_BROWSER, BROWSER_IDENTITY_CONFIG, BrowserIdentityConfig } from './auth-browser';
import { AuthFailure } from './auth-protocol';
import { AuthService } from './auth.service';
import { authInterceptor } from './auth.interceptor';
import { AuthorCard } from './public-profile';

const identity: BrowserIdentityConfig = {
    authServerUrl: 'https://identity.mnema.test', identityRedirectUri: 'https://mnema.test/auth/callback',
    learningApiBaseUrl: '/api', clientId: 'mnema-web'
};
const BASE = 'https://identity.mnema.test/api/accounts';

function accountId(index: number): string {
    return `0192f3a4-5b6c-7d8e-9f01-${index.toString(16).padStart(12, '0')}`;
}
function card(index: number, extra: Partial<AuthorCard> = {}): AuthorCard {
    return { accountId: accountId(index), profileUsername: `author${index}`, displayName: null, bio: null, avatarPresent: false, ...extra };
}
/** Lets the service's own microtask (the batching window) run. */
async function tick(): Promise<void> { await Promise.resolve(); await Promise.resolve(); }

describe('AuthorCardsService', () => {
    let service: AuthorCardsService;
    let http: HttpTestingController;

    beforeEach(() => {
        vi.useFakeTimers();
        TestBed.configureTestingModule({ providers: [
            provideHttpClient(withInterceptors([authInterceptor])), provideHttpClientTesting(),
            { provide: BROWSER_IDENTITY_CONFIG, useValue: identity },
            { provide: AUTH_BROWSER, useValue: { origin: 'https://mnema.test' } },
            { provide: AuthService, useValue: { accessToken: () => 'owner-token', expireSession: () => undefined } }
        ] });
        service = TestBed.inject(AuthorCardsService);
        http = TestBed.inject(HttpTestingController);
    });
    afterEach(() => { http.verify(); vi.useRealTimers(); });

    function expectBatch(ids: readonly string[]) {
        const request = http.expectOne(candidate => candidate.url === `${BASE}/profiles` && candidate.params.get('ids') === ids.join(','));
        expect(request.request.method).toBe('GET');
        expect(request.request.withCredentials).toBe(false);
        // anonymous read: the interceptor must not put the owner's bearer on a public endpoint
        expect(request.request.headers.has('Authorization')).toBe(false);
        return request;
    }

    it('answers the contract batch: found cards by id, a non-public id is absent', async () => {
        const result = service.cards(fixture.batch.request);
        await tick();
        expectBatch(fixture.batch.request).flush(fixture.batch.response);
        const cards = await result;
        expect([...cards.keys()]).toEqual([fixture.card.accountId]);
        expect(cards.get(fixture.card.accountId)).toEqual(fixture.card);
    });

    it('sends the ids asked for within one microtask as one request, each id once and in lower case', async () => {
        const first = service.cards([accountId(1), accountId(2)]);
        const second = service.cards([accountId(2).toUpperCase(), accountId(3)]);
        const single = service.card(accountId(1));
        await tick();
        expectBatch([accountId(1), accountId(2), accountId(3)]).flush({ profiles: [card(1), card(2), card(3)] });
        expect((await first).size).toBe(2);
        expect([...(await second).keys()]).toEqual([accountId(2), accountId(3)]);
        expect((await single)?.profileUsername).toBe('author1');
    });

    it('splits more than 50 ids into requests of at most 50', async () => {
        const ids = Array.from({ length: 120 }, (_, index) => accountId(index + 1));
        const result = service.cards(ids);
        await tick();
        const requests = http.match(request => request.url === `${BASE}/profiles`);
        expect(requests.map(request => request.request.params.get('ids')!.split(',').length)).toEqual([50, 50, 20]);
        requests.forEach(request => {
            const asked = request.request.params.get('ids')!.split(',');
            request.flush({ profiles: asked.map(id => card(Number.parseInt(id.slice(-12), 16))) });
        });
        expect((await result).size).toBe(120);
    });

    it('shares a request that is already on its way instead of asking again', async () => {
        const first = service.card(accountId(1));
        await tick();
        const request = expectBatch([accountId(1)]);
        const again = service.card(accountId(1));
        await tick();
        http.expectNone(`${BASE}/profiles?ids=${accountId(1)}`);
        request.flush({ profiles: [card(1)] });
        expect(await first).toEqual(await again);
    });

    it('remembers cards and misses for 60 seconds, then asks again', async () => {
        const first = service.cards([accountId(1), accountId(2)]);
        await tick();
        expectBatch([accountId(1), accountId(2)]).flush({ profiles: [card(1)] });
        await first;

        vi.advanceTimersByTime(AUTHOR_CARD_TTL_MS - 1);
        const cached = await service.cards([accountId(1), accountId(2)]);
        expect([...cached.keys()]).toEqual([accountId(1)]);
        await tick();
        http.expectNone(request => request.url === `${BASE}/profiles`);

        vi.advanceTimersByTime(2);
        const later = service.cards([accountId(1), accountId(2)]);
        await tick();
        expectBatch([accountId(1), accountId(2)]).flush({ profiles: [card(1, { profileUsername: 'renamed' }), card(2)] });
        expect((await later).get(accountId(1))?.profileUsername).toBe('renamed');
    });

    it('does not remember a failure and reports it to every caller of the batch', async () => {
        const both = Promise.allSettled([service.card(accountId(1)), service.card(accountId(2))]);
        await tick();
        expectBatch([accountId(1), accountId(2)]).flush({ code: 'try_later' }, { status: 429, statusText: 'Too Many Requests' });
        const outcomes = await both;
        expect(outcomes.map(outcome => outcome.status)).toEqual(['rejected', 'rejected']);
        expect((outcomes[0] as PromiseRejectedResult).reason).toBeInstanceOf(HttpErrorResponse);

        const retry = service.card(accountId(1));
        await tick();
        expectBatch([accountId(1)]).flush({ profiles: [card(1)] });
        expect((await retry)?.accountId).toBe(accountId(1));
    });

    it.each([
        ['an unknown field in a card', { profiles: [{ ...card(1), email: 'x@y.test' }] }],
        ['a card that was not asked for', { profiles: [card(9)] }],
        ['a body that is not an object of profiles', [card(1)]]
    ])('rejects %s as a protocol failure and keeps nothing', async (_name, body) => {
        const result = service.card(accountId(1));
        const settled = result.then(() => null, (error: unknown) => error);
        await tick();
        expectBatch([accountId(1)]).flush(body);
        expect(await settled).toBeInstanceOf(AuthFailure);
        const retry = service.card(accountId(1));
        await tick();
        expectBatch([accountId(1)]).flush({ profiles: [] });
        expect(await retry).toBeNull();
    });

    it('does not send an id that is not a canonical account id', async () => {
        expect(await service.card('anna')).toBeNull();
        expect([...(await service.cards(['', 'not-an-id']))]).toEqual([]);
        await tick();
        http.expectNone(request => request.url.startsWith(`${BASE}/profiles`));
    });

    it('looks a card up by login, caches the miss and fills the id cache from a hit', async () => {
        const found = service.byUsername('anna.k');
        const request = http.expectOne(`${BASE}/profiles/by-username/anna.k`);
        expect(request.request.withCredentials).toBe(false);
        expect(request.request.headers.has('Authorization')).toBe(false);
        request.flush(fixture.card);
        expect(await found).toEqual(fixture.card);

        expect(await service.byUsername('anna.k')).toEqual(fixture.card);
        expect((await service.card(fixture.card.accountId))?.profileUsername).toBe('anna.k');
        await tick();
        http.expectNone(request => request.url.startsWith(`${BASE}/profiles`));

        const missing = service.byUsername('nobody');
        const sameMissing = service.byUsername('nobody');
        http.expectOne(`${BASE}/profiles/by-username/nobody`).flush({ code: fixture.notFound.code },
            { status: fixture.notFound.status, statusText: 'Not Found' });
        expect(await missing).toBeNull();
        expect(await sameMissing).toBeNull();
        expect(await service.byUsername('nobody')).toBeNull();
        http.expectNone(`${BASE}/profiles/by-username/nobody`);

        vi.advanceTimersByTime(AUTHOR_CARD_TTL_MS + 1);
        const again = service.byUsername('nobody');
        http.expectOne(`${BASE}/profiles/by-username/nobody`).flush({}, { status: 404, statusText: 'Not Found' });
        expect(await again).toBeNull();
    });

    it('reports a rate limit of the login lookup and tries again next time', async () => {
        const limited = service.byUsername('anna.k').then(() => null, (error: unknown) => error);
        http.expectOne(`${BASE}/profiles/by-username/anna.k`).flush({ code: 'try_later' }, { status: 429, statusText: 'Too Many Requests' });
        expect(await limited).toBeInstanceOf(HttpErrorResponse);
        const retry = service.byUsername('anna.k');
        http.expectOne(`${BASE}/profiles/by-username/anna.k`).flush(fixture.card);
        expect((await retry)?.profileUsername).toBe('anna.k');
    });

    it('does not ask for a login outside the allowed alphabet and rejects a malformed card', async () => {
        expect(await service.byUsername('a/../b')).toBeNull();
        expect(await service.byUsername('x')).toBeNull();
        http.expectNone(request => request.url.includes('by-username'));
        const broken = service.byUsername('anna.k').then(() => null, (error: unknown) => error);
        http.expectOne(`${BASE}/profiles/by-username/anna.k`).flush({ ...fixture.card, extra: 1 });
        expect(await broken).toBeInstanceOf(AuthFailure);
    });

    it('builds the public photo address only for a card that shows a photo', () => {
        expect(service.avatarUrl(card(1))).toBeNull();
        expect(service.avatarUrl(card(1, { avatarPresent: true }))).toBe(`${BASE}/profiles/${accountId(1)}/avatar`);
    });

    it('forgets expired entries when many are remembered', async () => {
        const ids = Array.from({ length: 300 }, (_, index) => accountId(index + 1));
        const first = service.cards(ids);
        await tick();
        http.match(request => request.url === `${BASE}/profiles`).forEach(request => request.flush({ profiles: [] }));
        await first;
        vi.advanceTimersByTime(AUTHOR_CARD_TTL_MS + 1);
        const next = service.cards([accountId(1000)]);
        await tick();
        expectBatch([accountId(1000)]).flush({ profiles: [] });
        await next;
        // a fresh request for an old id proves its entry is gone
        const old = service.card(accountId(1));
        await tick();
        expectBatch([accountId(1)]).flush({ profiles: [] });
        expect(await old).toBeNull();
    });
});
