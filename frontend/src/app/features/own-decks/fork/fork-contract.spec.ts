import { HttpErrorResponse, HttpHeaders, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';

import forks from '../../../../../../contracts/decks/forks.json';
import publicRead from '../../../../../../contracts/decks/public-read.json';
import { COPY_TEXT } from '../../../shared/copy-text';
import { PublicDeckFailure, parsePublicDeck } from '../../public-deck/public-deck.models';
import { OwnDeckProtocolError, isCanonicalCommandId } from '../own-deck.models';
import { OwnDecksApiService } from '../own-decks-api.service';
import { ForkApiService } from './fork-api.service';
import {
    DeckFork,
    forkFailureOf,
    forkProgressOf,
    newForkCommandId,
    parseDeckFork,
    parseForkCreated,
    parseForkDetached,
    parseForkRetried
} from './fork.models';
import { FORK_POLL_CAP_MS, FORK_POLL_LIMIT, forkPollDelay } from './fork-poll';

/**
 * contracts/decks/forks.json is the one wire contract of «Добавить к себе». Every fixture must parse, every request must be the one the
 * contract names, and the contract's problems and its approved sentence must be what the client does and says.
 */
describe('Fork wire contract (contracts/decks/forks.json)', () => {
    const headers = { 'Cache-Control': 'private, no-store' };
    const sourceCode = forks.create.request.body.sourceCode;
    const commandId = forks.create.request.body.commandId;
    const copyId = forks.create.created.body.deckId;
    let http: HttpTestingController;
    let api: ForkApiService;

    beforeEach(() => {
        TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
        http = TestBed.inject(HttpTestingController);
        api = TestBed.inject(ForkApiService);
    });
    afterEach(() => http.verify());

    describe('the fork member', () => {
        it('parses every state of the contract and names its progress', () => {
            const states = forks.fork;
            expect(parseDeckFork(states.ordinaryDeck)).toBeNull();
            expect(forkProgressOf(parseDeckFork(states.preparing)!)).toBe('preparing');
            expect(forkProgressOf(parseDeckFork(states.failed)!)).toBe('failed');
            expect(forkProgressOf(parseDeckFork(states.ready)!)).toBe('ready');
            expect(forkProgressOf(parseDeckFork(states.detached)!)).toBe('ready');
            expect(parseDeckFork(states.detached)!.detached).toBe(true);
            expect(parseDeckFork(states.sourceClosed)).toEqual({ upstreamCode: null, upstreamTitle: null, sourceAvailable: false, preparing: false, failed: false, detached: false });
            expect(parseDeckFork(undefined)).toBeNull();
        });

        it('refuses a member that breaks the invariants of the contract', () => {
            const ready = forks.fork.ready;
            for (const broken of [
                { ...ready, failed: true },
                { ...ready, sourceAvailable: false },
                { ...ready, upstreamCode: null },
                { ...ready, upstreamTitle: '' },
                { ...ready, upstreamCode: 'short' },
                { ...ready, preparing: 'yes' },
                { ...ready, extra: 1 },
                { upstreamCode: ready.upstreamCode },
                'fork', 7, []
            ]) expect(() => parseDeckFork(broken), JSON.stringify(broken)).toThrowError(OwnDeckProtocolError);
        });

        it('reads the deck detail of the contract with its fork, and an answer without the member as an ordinary deck', async () => {
            const own = TestBed.inject(OwnDecksApiService);
            const detail = forks.deckDetail.body;
            const reading = firstValueFrom(own.detail(detail.deckId));
            http.expectOne(`/api/decks/${detail.deckId}`).flush(detail, { headers: { ...headers, ETag: '"0"' } });
            expect((await reading).fork).toEqual(forks.fork.preparing);
            expect((await reading).memberCount).toBe(40);

            const { fork: _fork, ...plain } = detail;
            const ordinary = firstValueFrom(own.detail(detail.deckId));
            http.expectOne(`/api/decks/${detail.deckId}`).flush(plain, { headers: { ...headers, ETag: '"0"' } });
            expect((await ordinary).fork).toBeNull();
        });

        it('reads a list page whose rows carry fork, and refuses a malformed fork inside a row', async () => {
            const own = TestBed.inject(OwnDecksApiService);
            const row = (fork: unknown, deckId = forks.deckDetail.body.deckId) => ({ ...forks.deckDetail.body, deckId, fork });
            const page = firstValueFrom(own.list());
            http.expectOne(request => request.url === '/api/decks').flush({ items: [row(forks.fork.ready), row(null, '55555555-5555-4555-8555-555555555555')], nextCursor: null }, { headers });
            expect((await page).items.map(item => item.fork)).toEqual([forks.fork.ready, null]);

            const broken = firstValueFrom(own.list());
            http.expectOne(request => request.url === '/api/decks').flush({ items: [row({ ...forks.fork.ready, failed: true })], nextCursor: null }, { headers });
            await expect(broken).rejects.toThrowError(OwnDeckProtocolError);
        });

        it('reads myCopy of the public summary: absent, null and both states', () => {
            const summary = publicRead.summary.public;
            expect(parsePublicDeck(summary, summary.code).myCopy).toBeNull();
            expect(parsePublicDeck({ ...summary, myCopy: forks.myCopy.none }, summary.code).myCopy).toBeNull();
            expect(parsePublicDeck({ ...summary, myCopy: forks.myCopy.preparing }, summary.code).myCopy).toEqual(forks.myCopy.preparing);
            expect(parsePublicDeck({ ...summary, myCopy: forks.myCopy.ready }, summary.code).myCopy).toEqual(forks.myCopy.ready);
            for (const broken of [{ deckId: copyId }, { deckId: 'x', preparing: true }, { deckId: copyId, preparing: 'no' }, { ...forks.myCopy.ready, extra: 1 }]) {
                expect(() => parsePublicDeck({ ...summary, myCopy: broken }, summary.code), JSON.stringify(broken)).toThrowError(PublicDeckFailure);
            }
        });
    });

    describe('the commands', () => {
        it('posts the strict body to the create path and parses the created answer (201)', async () => {
            const copying = firstValueFrom(api.copy(sourceCode, commandId));
            const request = http.expectOne(forks.constants.createPath);
            expect(request.request.method).toBe('POST');
            expect(request.request.body).toEqual(forks.create.request.body);
            expect(Object.keys(request.request.body)).toEqual(['commandId', 'sourceCode']);
            request.flush(forks.create.created.body, { status: forks.create.created.status, statusText: 'Created', headers: { ...headers, Location: forks.create.created.headers.Location } });
            expect(await copying).toEqual(forks.create.created.body);
        });

        it('parses the answer for a copy that already exists (200, existing)', async () => {
            const body = forks.create.existing.body;
            const copying = firstValueFrom(api.copy(sourceCode, body.commandId));
            http.expectOne(forks.constants.createPath).flush(body, { status: 200, statusText: 'OK', headers });
            expect(await copying).toEqual(body);
        });

        it('refuses an answer that is cacheable, belongs to another command or disagrees with its status', async () => {
            const bodies: readonly (readonly [object, number, Record<string, string>])[] = [
                [forks.create.created.body, 201, {}],
                [{ ...forks.create.created.body, commandId: forks.create.existing.body.commandId }, 201, headers],
                [{ ...forks.create.created.body, existing: true }, 201, headers],
                [{ ...forks.create.created.body, preparing: false }, 201, headers],
                [{ ...forks.create.existing.body, commandId, existing: false }, 200, headers],
                [{ ...forks.create.created.body, deckId: 'nope' }, 201, headers],
                [{ ...forks.create.created.body, extra: 1 }, 201, headers]
            ];
            for (const [body, status, answerHeaders] of bodies) {
                const copying = firstValueFrom(api.copy(sourceCode, commandId));
                http.expectOne(forks.constants.createPath).flush(body, { status, statusText: 'x', headers: answerHeaders });
                await expect(copying, JSON.stringify(body)).rejects.toThrowError(OwnDeckProtocolError);
            }
        });

        it('refuses to send a source code that cannot be one or a command id that is not a UUIDv4', async () => {
            await expect(firstValueFrom(api.copy('short', commandId))).rejects.toThrowError(OwnDeckProtocolError);
            await expect(firstValueFrom(api.copy(sourceCode, 'not-a-command'))).rejects.toThrowError(OwnDeckProtocolError);
            http.expectNone(forks.constants.createPath);
        });

        it('posts retry and detach without a body to the contract paths and parses their answers', async () => {
            const retrying = firstValueFrom(api.retry(copyId));
            const retry = http.expectOne(forks.constants.retryPath.replace('{deckId}', copyId));
            expect(retry.request.method).toBe('POST');
            expect(retry.request.body).toBeNull();
            retry.flush(forks.retry.body, { headers });
            expect(await retrying).toEqual(forks.retry.body);

            const detaching = firstValueFrom(api.detach(copyId.toUpperCase()));
            const detach = http.expectOne(forks.constants.detachPath.replace('{deckId}', copyId));
            expect(detach.request.method).toBe('POST');
            expect(detach.request.body).toBeNull();
            detach.flush(forks.detach.body, { headers });
            expect(await detaching).toEqual(forks.detach.body);
        });

        it('refuses a retry or detach answer about another deck, a failed copy that is not preparing, or an uncached promise', () => {
            expect(() => parseForkRetried({ ...forks.retry.body, deckId: forks.deckDetail.body.revisionId }, copyId)).toThrowError(OwnDeckProtocolError);
            expect(() => parseForkRetried({ deckId: copyId, preparing: false, failed: true }, copyId)).toThrowError(OwnDeckProtocolError);
            expect(() => parseForkDetached({ ...forks.detach.body, detached: false }, copyId)).toThrowError(OwnDeckProtocolError);
            expect(() => parseForkDetached({ ...forks.detach.body, deckId: forks.deckDetail.body.revisionId }, copyId)).toThrowError(OwnDeckProtocolError);
            expect(() => parseForkCreated({ ...forks.create.created.body }, 202, commandId)).toThrowError(OwnDeckProtocolError);
        });

        it('mints canonical UUIDv4 command ids', () => {
            const first = newForkCommandId();
            expect(isCanonicalCommandId(first)).toBe(true);
            expect(newForkCommandId()).not.toBe(first);
        });
    });

    describe('the problems', () => {
        const failure = (problem: { status: number; body: Record<string, unknown> }, extraHeaders: Record<string, string> = {}) =>
            forkFailureOf(new HttpErrorResponse({ status: problem.status, error: problem.body, headers: new HttpHeaders(extraHeaders) }));

        it('becomes the kind the screens react to', () => {
            expect(failure(forks.problems.notFound).kind).toBe('not-found');
            expect(failure(forks.problems.inviteOnly).kind).toBe('invite-only');
            expect(failure(forks.problems.ownDeck).kind).toBe('own-deck');
            expect(failure(forks.problems.preparing).kind).toBe('preparing');
            expect(failure(forks.problems.limit)).toEqual({ kind: 'limit', limit: forks.problems.limit.body.limit, retryAfter: null });
        });

        it('keeps a 403 without the invitation code, a 429 delay, a 503 and a lost connection apart', () => {
            expect(forkFailureOf(new HttpErrorResponse({ status: 403, error: { code: 'ACCESS_DENIED' } })).kind).toBe('other');
            expect(forkFailureOf(new HttpErrorResponse({ status: 429, error: { code: 'RATE_LIMITED' }, headers: new HttpHeaders({ 'Retry-After': '37' }) }))).toEqual({ kind: 'rate-limited', limit: null, retryAfter: 37 });
            expect(forkFailureOf(new HttpErrorResponse({ status: 503, error: { code: 'PUBLIC_READ_BUSY' } })).kind).toBe('busy');
            expect(forkFailureOf(new HttpErrorResponse({ status: 502 })).kind).toBe('unknown');
            expect(forkFailureOf(new HttpErrorResponse({ status: 0 })).kind).toBe('unknown');
            expect(forkFailureOf(new OwnDeckProtocolError('x')).kind).toBe('unknown');
            expect(forkFailureOf(new HttpErrorResponse({ status: 422, error: { code: 'RESOURCE_LIMIT_EXCEEDED', limit: -3 } })).limit).toBeNull();
        });
    });

    describe('the words', () => {
        it('uses the approved sentence of the contract for a source the author closed', () => {
            expect(COPY_TEXT.sourceClosed).toBe(forks.texts.sourceClosed);
        });

        it('knows the limits of the contract: the copy is prepared by polling at a bounded pace', () => {
            expect(forks.constants.maxPreparingCopies).toBe(10);
            expect([0, 1, 2, 3, 4, 5, 6, 400].map(forkPollDelay)).toEqual([1500, 2000, 3000, 5000, 8000, FORK_POLL_CAP_MS, FORK_POLL_CAP_MS, FORK_POLL_CAP_MS]);
            expect(forkPollDelay(-1)).toBe(1500);
            expect(FORK_POLL_LIMIT * FORK_POLL_CAP_MS).toBeLessThanOrEqual(30 * 60_000);
        });
    });

    it('draws the three states of the contract as three different words', () => {
        const words = (['preparing', 'failed', 'ready'] as const).map(name => forkProgressOf(forks.fork[name] as DeckFork));
        expect(new Set(words).size).toBe(3);
    });
});
