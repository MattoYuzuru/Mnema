import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';

import contract from '../../../../contracts/moderation/complaints.json';
import { ComplaintApiService } from './complaint-api.service';
import { COMPLAINT_REASONS, COMPLAINT_TEXT_MAX, ComplaintFailure, complaintBody, complaintFailureOf } from './complaint.models';

const CODE = 'Kq7xT3mNpR';

describe('ComplaintApiService (contracts/moderation/complaints.json)', () => {
    let api: ComplaintApiService;
    let http: HttpTestingController;

    beforeEach(() => {
        TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
        api = TestBed.inject(ComplaintApiService);
        http = TestBed.inject(HttpTestingController);
    });
    afterEach(() => http.verify());

    const failure = async (promise: Promise<unknown>): Promise<ComplaintFailure> => {
        try { await promise; } catch (error) { return error as ComplaintFailure; }
        throw new Error('expected a failure');
    };

    it('posts the deck complaint to the public route of its code with the documented body and accepts only 202 RECEIVED', async () => {
        const done = firstValueFrom(api.fileDeck(CODE, contract.filing.request.reason as 'COPYRIGHT', contract.filing.request.text));
        const request = http.expectOne(`/api/public/decks/${CODE}/complaints`);
        expect(request.request.method).toBe('POST');
        expect(request.request.body).toEqual(contract.filing.request);
        request.flush(contract.filing.answer, { status: 202, statusText: 'Accepted' });
        await expect(done).resolves.toBeUndefined();
    });

    it('omits a blank text and trims a typed one', async () => {
        const blank = firstValueFrom(api.fileDeck(CODE, 'SPAM', '   \n '));
        const first = http.expectOne(`/api/public/decks/${CODE}/complaints`);
        expect(first.request.body).toEqual(contract.filing.requestWithoutText);
        first.flush({ status: 'RECEIVED' }, { status: 202, statusText: 'Accepted' });
        await blank;
        const typed = firstValueFrom(api.fileDeck(CODE, 'OTHER', '  мусор  '));
        const second = http.expectOne(`/api/public/decks/${CODE}/complaints`);
        expect(second.request.body).toEqual({ reason: 'OTHER', text: 'мусор' });
        second.flush({ status: 'RECEIVED' }, { status: 202, statusText: 'Accepted' });
        await typed;
    });

    it('files a profile complaint by username, ready for the profile page', async () => {
        const done = firstValueFrom(api.fileProfile('ivan.k', 'PERSONAL_DATA', ''));
        const request = http.expectOne('/api/public/profiles/ivan.k/complaints');
        expect(request.request.body).toEqual({ reason: 'PERSONAL_DATA' });
        request.flush({ status: 'RECEIVED' }, { status: 202, statusText: 'Accepted' });
        await done;
    });

    it('never asks for a code or a username that cannot exist, and refuses a text over the limit before sending', async () => {
        expect((await failure(firstValueFrom(api.fileDeck('short', 'SPAM', '')))).kind).toBe('not-found');
        expect((await failure(firstValueFrom(api.fileDeck('Kq7xT3mNp0', 'SPAM', '')))).kind).toBe('not-found');
        expect((await failure(firstValueFrom(api.fileProfile('a/b', 'SPAM', '')))).kind).toBe('not-found');
        expect((await failure(firstValueFrom(api.fileDeck(CODE, 'SPAM', 'я'.repeat(COMPLAINT_TEXT_MAX + 1))))).kind).toBe('rejected');
    });

    it('counts the limit in characters, not UTF-16 units', async () => {
        const emoji = '🙂'.repeat(COMPLAINT_TEXT_MAX);
        const done = firstValueFrom(api.fileDeck(CODE, 'OTHER', emoji));
        http.expectOne(`/api/public/decks/${CODE}/complaints`).flush({ status: 'RECEIVED' }, { status: 202, statusText: 'Accepted' });
        await done;
    });

    it('treats any other success shape as an unknown outcome', async () => {
        for (const [status, body] of [[200, { status: 'RECEIVED' }], [202, { status: 'DONE' }], [202, { status: 'RECEIVED', id: 'x' }], [202, null]] as const) {
            const done = failure(firstValueFrom(api.fileDeck(CODE, 'SPAM', '')));
            http.expectOne(`/api/public/decks/${CODE}/complaints`).flush(body, { status, statusText: 'x' });
            expect((await done).kind, `${status} ${JSON.stringify(body)}`).toBe('unavailable');
        }
    });

    it('classifies the documented failures: 401, 404, 429 with Retry-After, 400 and 403, and the rest', async () => {
        const expectations: readonly [number, string, number | null][] = [[401, 'sign-in', null], [404, 'not-found', null], [429, 'rate-limited', 120],
            [400, 'rejected', null], [403, 'rejected', null], [500, 'unavailable', null], [503, 'unavailable', null]];
        for (const [status, kind, retryAfter] of expectations) {
            const done = failure(firstValueFrom(api.fileDeck(CODE, 'SPAM', '')));
            http.expectOne(`/api/public/decks/${CODE}/complaints`).flush({ code: 'X' }, { status, statusText: 'x', headers: status === 429 ? { 'Retry-After': '120' } : {} });
            const error = await done;
            expect(error.kind, String(status)).toBe(kind);
            expect(error.retryAfter, String(status)).toBe(retryAfter);
        }
        expect(complaintFailureOf(new Error('x')).kind).toBe('unavailable');
    });

    it('turns a network failure into an unknown outcome', async () => {
        const done = failure(firstValueFrom(api.fileDeck(CODE, 'SPAM', '')));
        http.expectOne(`/api/public/decks/${CODE}/complaints`).error(new ProgressEvent('error'));
        expect((await done).kind).toBe('unavailable');
    });
});

describe('complaint wire vocabulary', () => {
    it('agrees with the contract on reasons, text limit and the optional text rules', () => {
        expect([...COMPLAINT_REASONS]).toEqual(contract.constants.reasons);
        expect(COMPLAINT_TEXT_MAX).toBe(contract.constants.text.maxCodePoints);
    });

    it('drops control characters other than line feed and tab from a pasted text', () => {
        expect(complaintBody('OTHER', 'a\u0000b\tc\r\nd\u007f').text).toBe('ab\tc\nd');
        expect(complaintBody('OTHER', '\u0007').text).toBeUndefined();
    });
});
