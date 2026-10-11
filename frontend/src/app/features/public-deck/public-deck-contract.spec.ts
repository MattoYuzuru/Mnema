import { HttpErrorResponse, HttpHeaders, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Observable, firstValueFrom } from 'rxjs';

import contract from '../../../../../contracts/decks/public-read.json';
import { PublicDeckApiService } from './public-deck-api.service';
import {
    PUBLIC_DECK_CODE,
    PUBLIC_PAGE_SIZE,
    PublicDeckFailure,
    canonicalPath,
    parsePublicDeck,
    parsePublicExercisePage,
    parsePublicMaterialDocument,
    parsePublicMaterialPage,
    publicFailureOf
} from './public-deck.models';

/**
 * contracts/decks/public-read.json is the one wire contract of the public read. Every fixture must parse, every route must be
 * the one the client asks for, and the contract's problems must become the screens the page shows.
 */
describe('Public deck wire contract (contracts/decks/public-read.json)', () => {
    const code = contract.summary.public.code;
    let http: HttpTestingController;
    let api: PublicDeckApiService;

    beforeEach(() => {
        TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
        http = TestBed.inject(HttpTestingController);
        api = TestBed.inject(PublicDeckApiService);
    });
    afterEach(() => http.verify());

    it('uses the constants of the contract', () => {
        expect([...contract.constants.codeAlphabet].every(letter => PUBLIC_DECK_CODE.test(`${letter}`.repeat(10)))).toBe(true);
        for (const letter of '0IOl') expect(PUBLIC_DECK_CODE.test(letter.repeat(10))).toBe(false);
        expect(contract.constants.codeLength).toBe(10);
        for (const fixture of Object.values(contract.summary).filter(entry => typeof entry === 'object')) {
            expect(PUBLIC_DECK_CODE.test((fixture as { code: string }).code)).toBe(true);
        }
        expect(PUBLIC_PAGE_SIZE).toBeLessThanOrEqual(contract.constants.pageMax);
        expect(contract.constants.accessLevels).toEqual(['OWNER', 'GRANTEE', 'PUBLIC', 'LINK']);
    });

    it('asks for exactly the four routes of the contract', async () => {
        expect(contract.routes.map(route => route.method)).toEqual(['GET', 'GET', 'GET', 'GET']);
        const memberKey = contract.items.document.response.memberKey;
        const reads: readonly (readonly [Observable<unknown>, string])[] = [
            [api.summary(code), `/api/public/decks/${code}`],
            [api.materials(code), `/api/public/decks/${code}/items?limit=50`],
            [api.materials(code, 'aS8w'), `/api/public/decks/${code}/items?limit=50&cursor=aS8w`],
            [api.material(code, memberKey), `/api/public/decks/${code}/items/${memberKey}`],
            [api.exercises(code), `/api/public/decks/${code}/exercises?limit=50`]
        ];
        for (const [observable, url] of reads) {
            observable.subscribe({ error: () => undefined });
            const request = http.expectOne(url);
            expect(request.request.method).toBe('GET');
            request.flush({}, { status: 404, statusText: 'Not Found' });
        }
    });

    it('parses the summary of a public deck, a link deck and an invited grantee', async () => {
        for (const fixture of [contract.summary.public, contract.summary.link, contract.summary.invitedGrantee]) {
            const reading = firstValueFrom(api.summary(fixture.code));
            http.expectOne(`/api/public/decks/${fixture.code}`).flush(fixture);
            const deck = await reading;
            expect(deck.title).toBe(fixture.title);
            expect(deck.access).toBe(fixture.access);
            expect(deck.ownerId).toBe(fixture.ownerId);
        }
    });

    it('shows a slug only for PUBLIC and refuses one on any other level', () => {
        const deck = parsePublicDeck(contract.summary.public, code);
        expect(deck.slug).toBe(contract.summary.public.slug);
        expect(canonicalPath(deck)).toEqual(['/d', code, contract.summary.public.slug]);
        for (const fixture of [contract.summary.link, contract.summary.invitedGrantee]) {
            const other = parsePublicDeck(fixture, fixture.code);
            expect(other.slug).toBeNull();
            expect(canonicalPath(other)).toEqual(['/d', fixture.code]);
            expect(() => parsePublicDeck({ ...fixture, slug: 'leak' }, fixture.code)).toThrow(PublicDeckFailure);
        }
    });

    it('is strict: an unknown field, a wrong code, a PRIVATE level or a bad owner is a protocol failure', () => {
        const base = contract.summary.public;
        expect(() => parsePublicDeck({ ...base, deckId: 'x' }, code)).toThrow(PublicDeckFailure);
        expect(() => parsePublicDeck(base, 'Zp4WcA8vQe')).toThrow(PublicDeckFailure);
        expect(() => parsePublicDeck({ ...base, visibility: 'PRIVATE' }, code)).toThrow(PublicDeckFailure);
        expect(() => parsePublicDeck({ ...base, ownerId: 'someone' }, code)).toThrow(PublicDeckFailure);
        expect(() => parsePublicDeck({ ...base, memberCount: -1 }, code)).toThrow(PublicDeckFailure);
        expect(() => parsePublicDeck({ ...base, slug: 'a/b' }, code)).toThrow(PublicDeckFailure);
        expect(() => parsePublicDeck({ ...base, title: '' }, code)).toThrow(PublicDeckFailure);
    });

    it('parses material pages in manifest order with a continuing ordinal and a cursor that ends', () => {
        const first = parsePublicMaterialPage(contract.items.page.response, code, 50);
        const last = parsePublicMaterialPage(contract.items.page.lastPage, code, 50);
        expect(first.nextCursor).not.toBeNull();
        expect(last.nextCursor).toBeNull();
        expect(last.items[0].ordinal).toBe(first.items[0].ordinal + first.items.length);
        expect(first.total).toBe(last.total);
        expect(() => parsePublicMaterialPage(contract.items.page.response, code, 0)).toThrow(PublicDeckFailure);
        expect(() => parsePublicMaterialPage({ ...contract.items.page.response, items: [{ ...first.items[0], deckId: 'x' }] }, code, 50)).toThrow(PublicDeckFailure);
    });

    it('parses one material as a native document and refuses another member', () => {
        const fixture = contract.items.document.response;
        const material = parsePublicMaterialDocument(fixture, code, fixture.memberKey);
        expect(material.document.formatVersion).toBe(1);
        expect(material.ordinal).toBe(0);
        expect(() => parsePublicMaterialDocument(fixture, code, '8a2d3e4f-5b6c-4d7e-9f80-0b1c2d3e4f50')).toThrow(PublicDeckFailure);
        expect(() => parsePublicMaterialDocument({ ...fixture, document: { formatVersion: 1, root: {} } }, code, fixture.memberKey)).toThrow(PublicDeckFailure);
    });

    it('parses exercise summaries that carry no answers, options or authoring titles', () => {
        const page = parsePublicExercisePage(contract.exercises.page.response, code, 50);
        expect(page.exercises[0]).toEqual({ ...contract.exercises.page.response.exercises[0] });
        expect(Object.keys(page.exercises[0]).sort()).toEqual(['enabled', 'exerciseId', 'exerciseRevisionId', 'ordinal', 'prompt', 'type']);
        expect(page.exercises[0].prompt!.length).toBeLessThanOrEqual(contract.constants.promptMaxCodePoints + 1);
        const withKey = { ...contract.exercises.page.response, exercises: [{ ...contract.exercises.page.response.exercises[0], answer: 'x' }] };
        expect(() => parsePublicExercisePage(withKey, code, 50)).toThrow(PublicDeckFailure);
        const textless = { ...contract.exercises.page.response, exercises: [{ ...contract.exercises.page.response.exercises[0], prompt: null }] };
        expect(parsePublicExercisePage(textless, code, 50).exercises[0].prompt).toBeNull();
    });

    it('turns the problems of the contract into the failures the screens know', () => {
        const problem = (entry: { status: number; body?: unknown; headers?: Record<string, string> }) =>
            publicFailureOf(new HttpErrorResponse({ status: entry.status, error: entry.body, headers: new HttpHeaders(entry.headers) }));
        const { problems } = contract;
        expect(problem(problems.notFound).kind).toBe('not-found');
        expect(problem(problems.inviteOnly).kind).toBe('invite-only');
        expect(problem(problems.staleCursor).kind).toBe('stale');
        const limited = problem(problems.rateLimited);
        expect([limited.kind, limited.retryAfter]).toEqual(['rate-limited', 37]);
        const busy = problem(problems.busy);
        expect([busy.kind, busy.retryAfter]).toEqual(['busy', 1]);
        // A valid token without scope is not an invitation screen; a bad request is not a missing deck.
        expect(problem({ status: 403, body: { code: problems.missingScope.code } }).kind).toBe('unavailable');
        expect(problem(problems.invalidRequest).kind).toBe('unavailable');
        expect(publicFailureOf(new Error('offline')).kind).toBe('unavailable');
    });

    it('classifies failures of a real request, 404 first, without leaking which of the five causes it was', async () => {
        const failing = firstValueFrom(api.summary(code));
        http.expectOne(`/api/public/decks/${code}`).flush(contract.problems.notFound.body, { status: 404, statusText: 'Not Found' });
        await expect(failing).rejects.toMatchObject({ kind: 'not-found' });
        // A code that cannot be a code never reaches the network.
        await expect(firstValueFrom(api.summary('abc'))).rejects.toMatchObject({ kind: 'not-found' });
        await expect(firstValueFrom(api.material(code, 'not-an-id'))).rejects.toMatchObject({ kind: 'not-found' });
    });

    it('refuses an answer for another deck than the one asked for', async () => {
        const reading = firstValueFrom(api.summary(code));
        http.expectOne(`/api/public/decks/${code}`).flush(contract.summary.link);
        await expect(reading).rejects.toMatchObject({ kind: 'unavailable' });
    });
});
