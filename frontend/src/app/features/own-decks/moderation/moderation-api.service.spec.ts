import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';

import contract from '../../../../../../contracts/moderation/complaints.json';
import { AuthoringProtocolError } from '../../authoring/authoring.models';
import { ModerationApiService, parseDeckModeration } from './moderation-api.service';

describe('ModerationApiService (contracts/moderation/complaints.json ownerNotice)', () => {
    const notice = contract.ownerNotice;
    const deckId = notice.answer.deckId;
    let api: ModerationApiService;
    let http: HttpTestingController;

    beforeEach(() => {
        TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
        api = TestBed.inject(ModerationApiService);
        http = TestBed.inject(HttpTestingController);
    });
    afterEach(() => http.verify());

    it('reads the notice of an own deck with exactly the documented properties', async () => {
        const done = firstValueFrom(api.notice(deckId));
        http.expectOne(`/api/decks/${deckId}/moderation`).flush(notice.answer);
        const parsed = await done;
        expect(Object.keys(parsed).sort()).toEqual([...notice.properties].sort());
        expect(Object.keys(parsed.takenDown).sort()).toEqual([...notice.takenDownProperties].sort());
        expect(parsed).toEqual(notice.answer);
    });

    it('parses the clean notice of a deck nobody complained about', () => {
        expect(parseDeckModeration(notice.clean)).toEqual(notice.clean);
    });

    it('refuses a hidden flag without its time, an extra field, a negative count and the notice of another deck', async () => {
        expect(() => parseDeckModeration({ ...notice.answer, hiddenAt: null })).toThrow(AuthoringProtocolError);
        expect(() => parseDeckModeration({ ...notice.clean, hiddenAt: '2026-10-12T09:30:00Z' })).toThrow(AuthoringProtocolError);
        expect(() => parseDeckModeration({ ...notice.answer, reporter: 'x' })).toThrow(AuthoringProtocolError);
        expect(() => parseDeckModeration({ ...notice.answer, takenDown: { materials: -1, exercises: 0 } })).toThrow(AuthoringProtocolError);
        expect(() => parseDeckModeration({ ...notice.answer, takenDown: { materials: 1 } })).toThrow(AuthoringProtocolError);
        const done = firstValueFrom(api.notice(deckId));
        http.expectOne(`/api/decks/${deckId}/moderation`).flush({ ...notice.answer, deckId: '22222222-2222-4222-8222-222222222222' });
        await expect(done).rejects.toBeInstanceOf(AuthoringProtocolError);
    });

    it('refuses a deck id that is not an id before any request', async () => {
        await expect(firstValueFrom(api.notice('../x'))).rejects.toBeInstanceOf(AuthoringProtocolError);
    });
});
