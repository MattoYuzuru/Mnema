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
    const exemplars = { count: 1, limit: 10 };
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
            deckId, deckRevisionId, deckVersion: '8', total: 5, exemplars,
            items: [{ ...summary, title: 'Первый текст материала', exemplar: true }], nextCursor: null
        }, { headers: { ...headers, ETag: '"8"' } });
        const first = await page;
        expect(first.items).toEqual([{ ...summary, title: 'Первый текст материала', exemplar: true, exerciseCount: null }]);
        expect(first.exemplars).toEqual(exemplars);

        const detail = firstValueFrom(api.read(deckId, memberKey));
        http.expectOne(`/api/decks/${deckId}/items/${memberKey}`).flush({
            ...summary, ordinal: 4, deckId, deckRevisionId, deckVersion: '8', document, exemplar: false
        }, { headers: { ...headers, ETag: '"8"' } });
        expect((await detail).document).toEqual(document);

        const historical = firstValueFrom(api.read(deckId, memberKey, revisionId));
        http.expectOne(`/api/decks/${deckId}/items/${memberKey}?revisionId=${revisionId}`).flush({
            ...summary, ordinal: 4, deckId, deckRevisionId, deckVersion: '7', document, exemplar: false
        }, { headers: { ...headers, ETag: '"7"' } });
        expect((await historical).ordinal).toBe(4);
    });

    it('publishes one exact item with deck and item revision preconditions', async () => {
        const edits = [{ type: 'insert' as const, nodeId: id('78'), parentId: id('79'), childIndex: 1 }];
        const result = firstValueFrom(api.save(deckId, memberKey, '8', deckRevisionId, revisionId, 4, document, commandId, edits));
        const request = http.expectOne(`/api/decks/${deckId}/items/${memberKey}`);
        expect(request.request.method).toBe('PUT');
        expect(request.request.headers.get('If-Match')).toBe('"8"');
        expect(request.request.body.expectedItemRevisionId).toBe(revisionId);
        expect(request.request.body.expectedOrdinal).toBe(4);
        expect(request.request.body.edits).toEqual(edits);
        request.flush({
            commandId, deckId, deckRevisionId: id('76'), deckVersion: '9', memberCount: 5,
            changes: [{ operation: 'save', memberKey, itemRevisionId: id('77'), itemVersion: '1', ordinal: 4 }]
        }, { headers: { ...headers, ETag: '"9"' } });
        expect((await result).acknowledgement.changes[0]?.operation).toBe('save');
    });

    it('rejects a cacheable or shape-drifted item response', async () => {
        const page = firstValueFrom(api.list(deckId));
        http.expectOne(`/api/decks/${deckId}/items?limit=20`).flush({
            deckId, deckRevisionId, deckVersion: '8', total: 0, exemplars, items: [], nextCursor: null, secret: true
        }, { headers: { ETag: '"8"' } });
        await expect(page).rejects.toThrowError(AuthoringProtocolError);
    });

    it('requires a current ordinal but allows nullable historical locations', async () => {
        const detail = firstValueFrom(api.read(deckId, memberKey));
        http.expectOne(`/api/decks/${deckId}/items/${memberKey}`).flush({
            ...summary, ordinal: null, deckId, deckRevisionId, deckVersion: '8', document, exemplar: false
        }, { headers: { ...headers, ETag: '"8"' } });
        await expect(detail).rejects.toThrowError(AuthoringProtocolError);
        const history = firstValueFrom(api.read(deckId, memberKey, revisionId));
        http.expectOne(`/api/decks/${deckId}/items/${memberKey}?revisionId=${revisionId}`).flush({
            ...summary, ordinal: null, deckId, deckRevisionId, deckVersion: '7', document, exemplar: false
        }, { headers: { ...headers, ETag: '"7"' } });
        expect((await history).ordinal).toBeNull();
    });

    it('deletes through one canonical publication and replays the same preconditions without an ETag', async () => {
        const acknowledgement = { commandId, deckId, deckRevisionId: id('76'), deckVersion: '9', memberCount: 4,
            changes: [{ operation: 'delete' as const, memberKey, itemRevisionId: null, itemVersion: '0', ordinal: null }] };
        for (const replayed of [false, true]) {
            const result = firstValueFrom(api.delete(deckId, memberKey, '8', deckRevisionId, revisionId, 4, commandId));
            const request = http.expectOne(`/api/decks/${deckId}/items/publications`);
            expect(request.request.method).toBe('POST');
            expect(request.request.headers.get('If-Match')).toBe('"8"');
            expect(request.request.body).toEqual({ commandId, expectedDeckRevisionId: deckRevisionId,
                changes: [{ operation: 'delete', memberKey, expectedItemRevisionId: revisionId, expectedOrdinal: 4 }] });
            request.flush(acknowledgement, { headers: replayed
                    ? { ...headers, 'Idempotency-Replayed': 'true' } : { ...headers, ETag: '"9"' } });
            expect(await result).toEqual({ acknowledgement, replayed });
        }
    });

    it('rejects malformed deletion acknowledgements and invalid ordinals', async () => {
        expect(() => api.delete(deckId, memberKey, '8', deckRevisionId, revisionId, -1, commandId))
            .toThrowError(AuthoringProtocolError);
        for (const change of [
            { operation: 'save', memberKey, itemRevisionId: revisionId, itemVersion: '0', ordinal: 4 },
            { operation: 'delete', memberKey: id('99'), itemRevisionId: null, itemVersion: '0', ordinal: null },
            { operation: 'delete', memberKey, itemRevisionId: revisionId, itemVersion: '0', ordinal: null }
        ]) {
            const result = firstValueFrom(api.delete(deckId, memberKey, '8', deckRevisionId, revisionId, 4, commandId));
            http.expectOne(`/api/decks/${deckId}/items/publications`).flush({
                commandId, deckId, deckRevisionId: id('76'), deckVersion: '9', memberCount: 4, changes: [change]
            }, { headers: { ...headers, ETag: '"9"' } });
            await expect(result).rejects.toThrowError(AuthoringProtocolError);
        }
    });

    it('passes deletion precondition failures through for snapshot reconciliation', async () => {
        const result = firstValueFrom(api.delete(deckId, memberKey, '8', deckRevisionId, revisionId, 4, commandId));
        http.expectOne(`/api/decks/${deckId}/items/publications`).flush({ code: 'VERSION_CONFLICT' }, { status: 412, statusText: 'Precondition Failed' });
        await expect(result).rejects.toEqual(expect.objectContaining({ status: 412 }));
    });

    it('asks for exercise counts and the exercise-count sort, and requires counts exactly when asked', async () => {
        const counted = { ...summary, title: 'Без упражнений', exemplar: false, exerciseCount: 0 };
        const sorted = firstValueFrom(api.list(deckId, { sort: 'exerciseCount', cursor: 'opaque' }));
        http.expectOne(`/api/decks/${deckId}/items?limit=20&sort=exerciseCount&include=exerciseCount&cursor=opaque`).flush({
            deckId, deckRevisionId, deckVersion: '8', total: 5, exemplars, items: [counted], nextCursor: null
        }, { headers: { ...headers, ETag: '"8"' } });
        expect((await sorted).items).toEqual([counted]);

        const ordered = firstValueFrom(api.list(deckId, { exerciseCount: true }));
        http.expectOne(`/api/decks/${deckId}/items?limit=20&include=exerciseCount`).flush({
            deckId, deckRevisionId, deckVersion: '8', total: 5, exemplars, items: [{ ...counted, exerciseCount: 3 }], nextCursor: null
        }, { headers: { ...headers, ETag: '"8"' } });
        expect((await ordered).items[0]?.exerciseCount).toBe(3);

        // A list requested without counts must not carry them, and one requested with counts must.
        const unexpected = firstValueFrom(api.list(deckId));
        http.expectOne(`/api/decks/${deckId}/items?limit=20`).flush({
            deckId, deckRevisionId, deckVersion: '8', total: 5, exemplars, items: [counted], nextCursor: null
        }, { headers: { ...headers, ETag: '"8"' } });
        await expect(unexpected).rejects.toThrowError(AuthoringProtocolError);
        const missing = firstValueFrom(api.list(deckId, { exerciseCount: true }));
        http.expectOne(`/api/decks/${deckId}/items?limit=20&include=exerciseCount`).flush({
            deckId, deckRevisionId, deckVersion: '8', total: 5, exemplars, items: [{ ...summary, title: 'x', exemplar: false }], nextCursor: null
        }, { headers: { ...headers, ETag: '"8"' } });
        await expect(missing).rejects.toThrowError(AuthoringProtocolError);
    });

    it('rejects an exemplar budget above its limit and a non-boolean exemplar flag', async () => {
        const over = firstValueFrom(api.list(deckId));
        http.expectOne(`/api/decks/${deckId}/items?limit=20`).flush({
            deckId, deckRevisionId, deckVersion: '8', total: 0, exemplars: { count: 11, limit: 10 }, items: [], nextCursor: null
        }, { headers: { ...headers, ETag: '"8"' } });
        await expect(over).rejects.toThrowError(AuthoringProtocolError);
        const flag = firstValueFrom(api.list(deckId));
        http.expectOne(`/api/decks/${deckId}/items?limit=20`).flush({
            deckId, deckRevisionId, deckVersion: '8', total: 1, exemplars, items: [{ ...summary, title: 'x', exemplar: 'yes' }], nextCursor: null
        }, { headers: { ...headers, ETag: '"8"' } });
        await expect(flag).rejects.toThrowError(AuthoringProtocolError);
    });
});
