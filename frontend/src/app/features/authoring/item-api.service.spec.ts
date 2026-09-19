import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';

import { mixedNativeDocumentFixture } from '../../content/rendering/native-renderer.fixtures';
import { AuthoringProtocolError } from './authoring.models';
import { ItemApiService } from './item-api.service';

describe('ItemApiService', () => {
    let api: ItemApiService;
    let http: HttpTestingController;
    const id = (suffix: string) => `00000000-0000-4000-8000-${suffix.padStart(12, '0')}`;
    const deckId = id('71');
    const memberKey = id('72');
    const revisionId = id('73');
    const deckRevisionId = id('74');
    const commandId = id('75');
    const headers = { 'Cache-Control': 'private, no-store' };
    const summary = {
        memberKey, itemRevisionId: revisionId, itemVersion: '0', ordinal: 4, formatVersion: 1,
        createdAt: '2026-09-19T10:00:00Z', updatedAt: '2026-09-19T10:00:00Z'
    } as const;
    const document = mixedNativeDocumentFixture();

    beforeEach(() => {
        TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
        api = TestBed.inject(ItemApiService);
        http = TestBed.inject(HttpTestingController);
    });
    afterEach(() => http.verify());

    it('reads bounded summaries and exact native detail through private no-store responses', async () => {
        const page = firstValueFrom(api.list(deckId));
        http.expectOne(`/api/decks/${deckId}/items?limit=20`).flush({
            deckId, deckRevisionId, deckVersion: '8', total: 5, items: [summary], nextCursor: null
        }, { headers: { ...headers, ETag: '"8"' } });
        expect((await page).items).toEqual([summary]);

        const detail = firstValueFrom(api.read(deckId, memberKey));
        http.expectOne(`/api/decks/${deckId}/items/${memberKey}`).flush({
            ...summary, ordinal: null, deckId, deckRevisionId, deckVersion: '8', document
        }, { headers: { ...headers, ETag: '"8"' } });
        expect((await detail).document).toEqual(document);

        const historical = firstValueFrom(api.read(deckId, memberKey, revisionId));
        http.expectOne(`/api/decks/${deckId}/items/${memberKey}?revisionId=${revisionId}`).flush({
            ...summary, ordinal: 4, deckId, deckRevisionId, deckVersion: '7', document
        }, { headers: { ...headers, ETag: '"7"' } });
        expect((await historical).ordinal).toBe(4);
    });

    it('publishes one exact item with deck and item revision preconditions', async () => {
        const result = firstValueFrom(api.save(deckId, memberKey, '8', deckRevisionId, revisionId, 4, document, commandId));
        const request = http.expectOne(`/api/decks/${deckId}/items/${memberKey}`);
        expect(request.request.method).toBe('PUT');
        expect(request.request.headers.get('If-Match')).toBe('"8"');
        expect(request.request.body.expectedItemRevisionId).toBe(revisionId);
        expect(request.request.body.expectedOrdinal).toBe(4);
        request.flush({
            commandId, deckId, deckRevisionId: id('76'), deckVersion: '9', memberCount: 5,
            changes: [{ operation: 'save', memberKey, itemRevisionId: id('77'), itemVersion: '1', ordinal: 4 }]
        }, { headers: { ...headers, ETag: '"9"' } });
        expect((await result).acknowledgement.changes[0]?.operation).toBe('save');
    });

    it('rejects a cacheable or shape-drifted item response', async () => {
        const page = firstValueFrom(api.list(deckId));
        http.expectOne(`/api/decks/${deckId}/items?limit=20`).flush({
            deckId, deckRevisionId, deckVersion: '8', total: 0, items: [], nextCursor: null, secret: true
        }, { headers: { ETag: '"8"' } });
        await expectAsync(page).toBeRejectedWithError(AuthoringProtocolError);
    });
});
