import { HttpHeaders, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Observable, firstValueFrom } from 'rxjs';

import contract from '../../../../../contracts/community/catalog.json';
import { CATALOG_LEVELS, CATALOG_MECHANICS, CATALOG_MEDIA, CATALOG_SORTS, CatalogFilters, NO_FILTERS } from '../../shared/catalog-filters';
import { PublicDeckFailure } from '../public-deck/public-deck.models';
import { CommunityApiService, CommunityQuery, normalizeQuery } from './community-api.service';
import {
    COMMUNITY_PAGE_MAX,
    COMMUNITY_PAGE_SIZE,
    COMMUNITY_QUERY_MAX,
    parseAuthorDeckPage,
    parseCommunityCard,
    parseCommunityDeckPage,
    parseCommunityFacets,
    parseCommunityShelves
} from './community.models';
import { SHELF_MIN_DECKS, SHELF_ROW_MAX, displayCard } from './community-view';

/** `"<card>"` in the contract stands for an object of the shape of `card`. */
function withCards<T>(value: T): T {
    if (value === '<card>') return structuredClone(contract.card) as T;
    if (Array.isArray(value)) return value.map(withCards) as T;
    if (value !== null && typeof value === 'object') return Object.fromEntries(Object.entries(value).map(([key, entry]) => [key, withCards(entry)])) as T;
    return value;
}

const CARD = contract.card;
const AUTHOR_ID = CARD.ownerId;
const filters = (patch: Partial<CatalogFilters>): CatalogFilters => ({ ...NO_FILTERS, ...patch });
const query = (q: string, patch: Partial<CatalogFilters> = {}): CommunityQuery => ({ q, filters: filters(patch) });

/**
 * contracts/community/catalog.json is the one wire contract of the community catalogue. Every example must parse, the parsers must refuse what the
 * contract forbids, the routes must be the ones the client asks for, and the constants must be the ones the page uses.
 */
describe('Community catalogue wire contract (contracts/community/catalog.json)', () => {
    let http: HttpTestingController;
    let api: CommunityApiService;

    beforeEach(() => {
        TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
        http = TestBed.inject(HttpTestingController);
        api = TestBed.inject(CommunityApiService);
    });
    afterEach(() => http.verify());

    it('uses the constants of the contract', () => {
        expect(COMMUNITY_PAGE_SIZE).toBe(contract.constants.pageDefault);
        expect(COMMUNITY_PAGE_MAX).toBe(contract.constants.pageMax);
        expect(COMMUNITY_QUERY_MAX).toBe(contract.constants.queryMaxLength);
        expect(SHELF_MIN_DECKS).toBe(contract.constants.shelfMinDecks);
        expect(SHELF_ROW_MAX).toBeLessThanOrEqual(contract.constants.shelfMaxDecks);
        expect(CATALOG_LEVELS.map(level => level.value)).toEqual(contract.constants.levels);
        expect(CATALOG_MEDIA.map(item => item.value).sort()).toEqual([...contract.constants.media].sort());
        expect(CATALOG_MECHANICS.map(item => item.value).sort()).toEqual(Object.keys(contract.constants.mechanics).sort());
        // «Для вас» arrives with the recommendations; the contract's sorts are the ones the control draws.
        expect(CATALOG_SORTS.map(item => item.value).sort()).toEqual(Object.keys(contract.constants.sorts).filter(key => key !== 'withQuery').sort());
        // «По близости к запросу» is not a wire sort: with q and no sort the order is relevance (a note key such as `withQuery` is not a sort).
        expect(Object.keys(contract.constants.sizes).sort()).toEqual(['course', 'evening', 'month']);
    });

    it('asks for the facets, shelves, decks and author routes of the contract with a GET and the parameters it lists', () => {
        const listed = contract.routes.filter(route => route.path.startsWith('/api/public/community'));
        expect(listed.map(route => route.method)).toEqual(['GET', 'GET', 'GET', 'GET']);
        expect(listed.map(route => route.answers)).toEqual(['facets', 'shelves', 'decks', 'author']);
        const reads: readonly (readonly [Observable<unknown>, string])[] = [
            [api.facets(), '/api/public/community/facets'],
            [api.shelves(), '/api/public/community/shelves'],
            // The contract's own examples, letter for letter.
            [api.decks(query('', { topic: 'languages', media: ['audio'], sort: 'popular' })), '/api/public/community/decks?topic=languages&media=audio&sort=popular&limit=24'],
            [api.decks(query('испанский', { sort: 'relevance' })), contract.decks.search.request.replace('GET ', '')],
            [api.authorDecks(AUTHOR_ID), contract.author.request.replace('GET ', '')],
            [api.authorDecks(AUTHOR_ID, 'eyJrIjoiMSJ9', 48), `/api/public/community/authors/${AUTHOR_ID}/decks?limit=48&cursor=eyJrIjoiMSJ9`]
        ];
        for (const [observable, url] of reads) {
            observable.subscribe({ error: () => undefined });
            const request = http.expectOne(url);
            expect(request.request.method).toBe('GET');
            expect(request.request.withCredentials).toBe(false);
            request.flush({}, { status: 404, statusText: 'Not Found' });
        }
    });

    it('sends every filter once, lists comma-separated, the text trimmed and cut at 100 code points, and the sorting with a text only when it is chosen', () => {
        const all = filters({ topic: 'spanish', learn: 'es', level: 'B1', size: 'month', media: ['audio', 'video'], mechanics: ['listening', 'order'], fresh: true, sort: 'new' });
        api.decks({ q: '', filters: all }, 'eyJrIjoiMSJ9').subscribe({ error: () => undefined });
        http.expectOne('/api/public/community/decks?topic=spanish&learn=es&level=B1&size=month&media=audio,video&mechanics=listening,order&fresh=true&sort=new&limit=24&cursor=eyJrIjoiMSJ9')
            .flush({}, { status: 404, statusText: 'Not Found' });

        api.decks({ q: '  кот  ', filters: all }, null, 48).subscribe({ error: () => undefined });
        http.expectOne(request => request.url === '/api/public/community/decks' && request.params.get('q') === 'кот' && request.params.get('sort') === 'new'
            && request.params.get('limit') === '48' && request.params.get('topic') === 'spanish').flush({}, { status: 404, statusText: 'Not Found' });

        // «По близости к запросу» is the absence of sort; without a text it cannot be asked for and is popular.
        api.decks({ q: 'кот', filters: filters({ sort: 'relevance' }) }).subscribe({ error: () => undefined });
        http.expectOne(request => request.params.get('q') === 'кот' && !request.params.has('sort')).flush({}, { status: 404, statusText: 'Not Found' });
        api.decks({ q: '', filters: filters({ sort: 'relevance' }) }).subscribe({ error: () => undefined });
        http.expectOne(request => !request.params.has('q') && request.params.get('sort') === 'popular').flush({}, { status: 404, statusText: 'Not Found' });

        const long = '😀'.repeat(COMMUNITY_QUERY_MAX + 20);
        expect(Array.from(normalizeQuery(long))).toHaveLength(COMMUNITY_QUERY_MAX);
        expect(normalizeQuery('   ')).toBe('');
        expect(normalizeQuery(null)).toBe('');
    });

    it('parses the facets and the empty catalogue', async () => {
        const reading = firstValueFrom(api.facets());
        http.expectOne('/api/public/community/facets').flush(contract.facets.response);
        const facets = await reading;
        expect(facets.listedCount).toBe(37);
        expect(facets.topics.map(topic => [topic.id, topic.parentId, topic.count])).toEqual(contract.facets.response.topics.map(topic => [topic.id, topic.parentId, topic.count]));
        expect(facets.languages).toEqual(contract.facets.response.languages);
        expect(facets.levels).toEqual(contract.facets.response.levels);
        expect(parseCommunityFacets(contract.facets.empty)).toEqual({ listedCount: 0, topics: [], languages: [], levels: [] });
    });

    it('parses the shelves in server order, topic shelves with their topic, and the empty catalogue', async () => {
        const reading = firstValueFrom(api.shelves());
        http.expectOne('/api/public/community/shelves').flush(withCards(contract.shelves.response));
        const { listedCount, shelves } = await reading;
        expect(listedCount).toBe(37);
        expect(shelves.map(shelf => shelf.kind)).toEqual(['editorial', 'learning', 'new', 'topic']);
        expect(shelves.map(shelf => shelf.more)).toEqual([null, { sort: 'learning' }, { sort: 'new' }, { topic: 'spanish', sort: 'popular' }]);
        expect(shelves[3].topic).toEqual({ id: 'spanish', name: 'Испанский', parentId: 'languages' });
        expect(shelves.every(shelf => shelf.decks.length >= contract.constants.shelfMinDecks)).toBe(true);
        expect(parseCommunityShelves(contract.shelves.empty)).toEqual({ listedCount: 0, shelves: [] });
    });

    it('parses browse, search and the empty answer, with the cursor that ends', async () => {
        for (const [example, url] of [[contract.decks.browse, '/api/public/community/decks?limit=24'], [contract.decks.search, '/api/public/community/decks?limit=24']] as const) {
            const reading = firstValueFrom(api.decks(query('')));
            http.expectOne(request => request.url === url.split('?')[0]).flush(withCards(example.response));
            const page = await reading;
            expect(page.decks).toHaveLength(1);
            expect(page.total).toBe(example.response.total);
            expect(page.totalCapped).toBe(false);
            expect(page.nextCursor).toBe(example.response.nextCursor);
        }
        expect(parseCommunityDeckPage(contract.decks.nothing)).toEqual({ decks: [], total: 0, totalCapped: false, nextCursor: null });
    });

    it('parses the decks of an author: public cards with their slug, invitation-only ones without, the sum and the cursor that ends', async () => {
        const reading = firstValueFrom(api.authorDecks(AUTHOR_ID));
        http.expectOne(request => request.url === `/api/public/community/authors/${AUTHOR_ID}/decks`).flush(withCards(contract.author.response));
        const { learningNowTotal, decks, nextCursor } = await reading;
        expect(learningNowTotal).toBe(contract.author.response.learningNowTotal);
        expect(nextCursor).toBeNull();
        expect(decks.map(entry => entry.access)).toEqual(['PUBLIC', 'GRANTEE']);
        expect(decks[0].slug).toBe(CARD.slug);
        expect(decks[0].addedCount).toBe(1200);
        expect(decks[1]).toMatchObject({ code: 'Hs9Bd2kLfM', slug: null, topic: null, addedCount: null, learningNowCount: null, media: [], mechanics: [] });
        // «Учат сейчас» of the whole list is null below the server's minimum.
        expect(parseAuthorDeckPage({ ...withCards(contract.author.response), learningNowTotal: null }).learningNowTotal).toBeNull();
        expect(parseAuthorDeckPage({ learningNowTotal: null, decks: [], nextCursor: 'eyJrIjoiMSJ9' })).toEqual({ learningNowTotal: null, decks: [], nextCursor: 'eyJrIjoiMSJ9' });
    });

    it('is strict about the decks of an author: the envelope, the access, and what an invitation-only deck may carry', () => {
        const page = withCards(contract.author.response);
        const grantee = page.decks[1].deck as Record<string, unknown>;
        const entry = (access: string, deck: unknown) => ({ access, deck });
        const refused = (decks: unknown[], patch: Record<string, unknown> = {}, limit?: number) =>
            expect(() => parseAuthorDeckPage({ ...page, ...patch, decks }, limit)).toThrow(PublicDeckFailure);
        expect(() => parseAuthorDeckPage({ ...page, extra: 1 })).toThrow(PublicDeckFailure);
        expect(() => parseAuthorDeckPage({ decks: page.decks, nextCursor: null })).toThrow(PublicDeckFailure);
        expect(() => parseAuthorDeckPage({ ...page, learningNowTotal: '120' })).toThrow(PublicDeckFailure);
        expect(() => parseAuthorDeckPage({ ...page, learningNowTotal: -1 })).toThrow(PublicDeckFailure);
        expect(() => parseAuthorDeckPage({ ...page, nextCursor: '' })).toThrow(PublicDeckFailure);
        expect(() => parseAuthorDeckPage(null)).toThrow(PublicDeckFailure);
        refused([entry('OWNER', CARD)]);
        refused([{ access: 'PUBLIC', deck: CARD, extra: 1 }]);
        refused([{ deck: CARD }]);
        // A public deck has its slug; an invitation-only one has none, no topic, no counts, no media and no mechanics.
        refused([entry('PUBLIC', { ...CARD, slug: null })]);
        refused([entry('GRANTEE', { ...grantee, slug: 'ispanskiy' })]);
        refused([entry('GRANTEE', { ...grantee, slug: 'A/b' })]);
        refused([entry('GRANTEE', { ...grantee, topic: CARD.topic })]);
        refused([entry('GRANTEE', { ...grantee, addedCount: 120 })]);
        refused([entry('GRANTEE', { ...grantee, learningNowCount: 12 })]);
        refused([entry('GRANTEE', { ...grantee, media: ['audio'] })]);
        refused([entry('GRANTEE', { ...grantee, mechanics: ['order'] })]);
        refused([entry('GRANTEE', { ...grantee, deckId: '0198a6c0-1d3e-7a52-9b0c-2f6d8e4a1c35' })]);
        // The catalogue's own card still needs its slug, and a deck is never listed twice in one page or beyond the limit that was asked for.
        expect(() => parseCommunityCard({ ...grantee })).toThrow(PublicDeckFailure);
        refused([entry('PUBLIC', CARD), entry('GRANTEE', { ...grantee, code: CARD.code })]);
        refused([entry('PUBLIC', CARD), entry('GRANTEE', grantee)], {}, 1);
    });

    it('does not ask for an author that is not an account id, and turns the refused answers of the author route into failures the page knows', async () => {
        const gone = firstValueFrom(api.authorDecks('someone')).catch((error: unknown) => error as PublicDeckFailure);
        expect(await gone).toMatchObject({ kind: 'not-found' });
        const failing = async (status: number, headers: Record<string, string> = {}, body: Record<string, unknown> | null = null) => {
            const reading = firstValueFrom(api.authorDecks(AUTHOR_ID)).catch((error: unknown) => error as PublicDeckFailure);
            http.expectOne(request => request.url.startsWith('/api/public/community/authors/')).flush(body, { status, statusText: 'x', headers: new HttpHeaders(headers) });
            return reading;
        };
        expect(await failing(404)).toMatchObject({ kind: 'not-found' });
        expect(await failing(429, { 'Retry-After': '12' })).toMatchObject({ kind: 'rate-limited', retryAfter: 12 });
        expect(await failing(503, { 'Retry-After': '1' }, { code: 'PUBLIC_READ_BUSY' })).toMatchObject({ kind: 'busy' });
        expect(await failing(412, {}, { code: 'VERSION_CONFLICT' })).toMatchObject({ kind: 'stale' });
        // The author's list takes no value from the address: a 400 is a failure, not an empty page. A body that is not the contract is a protocol failure.
        expect(await failing(400, {}, { code: 'INVALID_REQUEST' })).toMatchObject({ kind: 'unavailable' });
        expect(await failing(200, {}, { learningNowTotal: 1 })).toMatchObject({ kind: 'unavailable' });
    });

    it('parses a card exactly as the contract shows it, and it carries nothing the contract keeps from a card', () => {
        const card = parseCommunityCard(CARD);
        expect(Object.keys(card).sort()).toEqual(Object.keys(CARD).sort());
        for (const forbidden of ['id', 'deckId', 'revisionId', 'score', 'rank']) expect(Object.keys(card)).not.toContain(forbidden);
        expect(card.slug).toBe(CARD.slug);
        expect(card.topic).toEqual(CARD.topic);
        expect(card.addedCount).toBe(1200);
        expect(card.media).toEqual(['image', 'audio']);
    });

    it('is strict: an unknown field, a missing one, a wrong type or an unknown value is a protocol failure', () => {
        const refused = (patch: Record<string, unknown>) => expect(() => parseCommunityCard({ ...CARD, ...patch })).toThrow(PublicDeckFailure);
        refused({ deckId: '0198a6c0-1d3e-7a52-9b0c-2f6d8e4a1c35' });
        refused({ score: 0.9 });
        refused({ title: '' });
        refused({ slug: null });
        refused({ slug: 'a/b' });
        refused({ slug: 'Ispanskiy' });
        refused({ slug: 'a--b' });
        refused({ slug: '-a' });
        refused({ slug: 'a'.repeat(81) });
        refused({ code: 'Kq7' });
        refused({ ownerId: 'someone' });
        refused({ level: 'Z9' });
        refused({ media: ['image', 'image'] });
        refused({ media: ['hologram'] });
        refused({ mechanics: ['dancing'] });
        refused({ tags: 'one' });
        refused({ itemCount: -1 });
        refused({ itemCount: 1.5 });
        refused({ addedCount: '12' });
        refused({ updatedAt: 'yesterday' });
        refused({ topic: { id: 'spanish', name: 'Испанский' } });
        refused({ topic: { id: 'spanish', name: 'Испанский', parentId: null, count: 3 } });
        refused({ targetLanguage: 'Spanish' });
        const missing = Object.fromEntries(Object.entries(CARD).filter(([key]) => key !== 'title'));
        expect(() => parseCommunityCard(missing)).toThrow(PublicDeckFailure);
        expect(() => parseCommunityCard(null)).toThrow(PublicDeckFailure);
        expect(() => parseCommunityCard([CARD])).toThrow(PublicDeckFailure);
    });

    it('is strict about the envelopes: facets, shelves and pages', () => {
        const facets = contract.facets.response;
        expect(() => parseCommunityFacets({ ...facets, extra: 1 })).toThrow(PublicDeckFailure);
        expect(() => parseCommunityFacets({ listedCount: 1, topics: [], languages: [] })).toThrow(PublicDeckFailure);
        expect(() => parseCommunityFacets({ ...facets, topics: [{ ...facets.topics[0], count: 0 }] })).toThrow(PublicDeckFailure);
        expect(() => parseCommunityFacets({ ...facets, levels: [{ level: 'Z9', count: 1 }] })).toThrow(PublicDeckFailure);
        expect(() => parseCommunityFacets({ ...facets, languages: [{ code: 'es', count: 1, share: 0.5 }] })).toThrow(PublicDeckFailure);

        const shelves = withCards(contract.shelves.response);
        expect(() => parseCommunityShelves({ ...shelves, extra: 1 })).toThrow(PublicDeckFailure);
        expect(() => parseCommunityShelves({ ...shelves, shelves: [{ kind: 'trending', decks: [] }] })).toThrow(PublicDeckFailure);
        // A topic shelf names its topic; no other shelf does.
        expect(() => parseCommunityShelves({ ...shelves, shelves: [{ kind: 'topic', decks: [], more: { topic: 'spanish' } }] })).toThrow(PublicDeckFailure);
        expect(() => parseCommunityShelves({ ...shelves, shelves: [{ ...shelves.shelves[1], topic: CARD.topic }] })).toThrow(PublicDeckFailure);
        expect(() => parseCommunityShelves({ ...shelves, shelves: [{ ...shelves.shelves[1], more: { sort: 'random' } }] })).toThrow(PublicDeckFailure);
        expect(() => parseCommunityShelves({ ...shelves, shelves: [{ ...shelves.shelves[1], more: { page: 2 } }] })).toThrow(PublicDeckFailure);

        const page = contract.decks.browse.response;
        expect(() => parseCommunityDeckPage({ ...withCards(page), extra: 1 })).toThrow(PublicDeckFailure);
        expect(() => parseCommunityDeckPage({ ...withCards(page), totalCapped: 'no' })).toThrow(PublicDeckFailure);
        expect(() => parseCommunityDeckPage({ ...withCards(page), nextCursor: '' })).toThrow(PublicDeckFailure);
        expect(() => parseCommunityDeckPage({ decks: [], total: 0, totalCapped: false })).toThrow(PublicDeckFailure);
        expect(() => parseCommunityDeckPage({ ...withCards(page), total: 1001 })).toThrow(PublicDeckFailure);
        // More cards than the page asked for is a protocol failure.
        expect(() => parseCommunityDeckPage({ ...withCards(page), decks: [CARD, { ...CARD, code: 'Zp4WcA8vQe' }] }, 1)).toThrow(PublicDeckFailure);
    });

    it('turns a refused answer of the wire into a failure the page knows', async () => {
        const failing = async (observable: Observable<unknown>, status: number, headers: Record<string, string> = {}, body: Record<string, unknown> | null = null) => {
            const reading = firstValueFrom(observable).catch((error: unknown) => error as PublicDeckFailure);
            http.expectOne(request => request.url.startsWith('/api/public/community/')).flush(body, { status, statusText: 'x', headers: new HttpHeaders(headers) });
            return reading;
        };
        expect(await failing(api.facets(), 404)).toMatchObject({ kind: 'not-found' });
        expect(await failing(api.shelves(), 429, { 'Retry-After': '37' })).toMatchObject({ kind: 'rate-limited', retryAfter: 37 });
        expect(await failing(api.decks(query('')), 503, { 'Retry-After': '1' }, { code: 'PUBLIC_READ_BUSY' })).toMatchObject({ kind: 'busy', retryAfter: 1 });
        expect(await failing(api.decks(query('x'), 'old'), 412, {}, { code: 'VERSION_CONFLICT' })).toMatchObject({ kind: 'stale' });
        // A value of the address the server does not know (400) matches nothing: an empty page, not a failure to retry. Facets and shelves take no value.
        const refused = firstValueFrom(api.decks(query('', { topic: 'gone' })));
        http.expectOne(request => request.params.get('topic') === 'gone').flush({ code: 'INVALID_REQUEST' }, { status: 400, statusText: 'Bad Request' });
        expect(await refused).toEqual({ decks: [], total: 0, totalCapped: false, nextCursor: null });
        expect(await failing(api.facets(), 400, {}, { code: 'INVALID_REQUEST' })).toMatchObject({ kind: 'unavailable' });
        // A body that is not the contract is a protocol failure, not a half-drawn page.
        expect(await failing(api.facets(), 200, {}, { listedCount: 1 })).toMatchObject({ kind: 'unavailable' });
    });

    it('never lets a small audience through to a card, whatever the wire says', () => {
        const small = displayCard(parseCommunityCard({ ...CARD, addedCount: 9, learningNowCount: null }), { username: null, avatarSrc: null }, 'https://mnema.app');
        expect([small.addedCount, small.learningNowCount]).toEqual([null, null]);
        const large = displayCard(parseCommunityCard(CARD), { username: 'anna.k', avatarSrc: null }, 'https://mnema.app');
        expect([large.addedCount, large.learningNowCount]).toEqual([1200, 34]);
        expect(large.shareUrl).toBe(`https://mnema.app/d/${CARD.code}/${CARD.slug}`);
        expect(large.id).toBe(CARD.code);
    });
});
