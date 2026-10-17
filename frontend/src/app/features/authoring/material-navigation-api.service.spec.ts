import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import hub from '../../../../../contracts/decks/hub.json';
import { MaterialNavigationApiService } from './material-navigation-api.service';

describe('MaterialNavigationApiService', () => {
    const deckId = '11111111-1111-4111-8111-111111111111';
    const memberKey = '22222222-2222-4222-8222-222222222222';
    const other = '33333333-3333-4333-8333-333333333333';
    const response = hub.navigation.first;
    const headers = { ETag: '"3"', 'Cache-Control': 'private, no-store' };
    let api: MaterialNavigationApiService; let http: HttpTestingController;
    beforeEach(() => {
        TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
        api = TestBed.inject(MaterialNavigationApiService); http = TestBed.inject(HttpTestingController);
    });
    afterEach(() => http.verify());
    it.each(Object.values(hub.navigation))('reads shared neighbours fixtures with exact validators and boundaries', async response => {
        const pending = firstValueFrom(api.read(deckId, memberKey));
        http.expectOne(`/api/decks/${deckId}/items/${memberKey}/navigation`).flush(response, { headers });
        expect(await pending).toEqual(response);
    });
    it.each([
        { ...response, previousMemberKey: other }, { ...response, nextMemberKey: null },
        { ...response, nextMemberKey: memberKey }, { ...response, deckId: other },
        { ...response, ordinal: 2 }, { ...response, ordinal: 1, previousMemberKey: other, nextMemberKey: other }
    ])('rejects impossible boundaries or identities', async invalid => {
        const pending = firstValueFrom(api.read(deckId, memberKey));
        const rejected = expect(pending).rejects.toThrow('Invalid material navigation');
        http.expectOne(`/api/decks/${deckId}/items/${memberKey}/navigation`).flush(invalid, { headers });
        await rejected;
    });
    it('rejects cacheable representations', async () => {
        const pending = firstValueFrom(api.read(deckId, memberKey));
        const rejected = expect(pending).rejects.toThrow('Invalid material navigation');
        http.expectOne(`/api/decks/${deckId}/items/${memberKey}/navigation`).flush(response, { headers: { ETag: '"3"' } });
        await rejected;
    });
});
