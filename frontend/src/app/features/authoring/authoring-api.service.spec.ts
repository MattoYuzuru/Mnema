import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';

import { mixedNativeDocumentFixture } from '../../content/rendering/native-renderer.fixtures';
import { AuthoringProtocolError } from './authoring.models';
import { AuthoringApiService } from './authoring-api.service';

describe('AuthoringApiService', () => {
    let api: AuthoringApiService;
    let http: HttpTestingController;
    const id = (suffix: string) => `00000000-0000-4000-8000-${suffix.padStart(12, '0')}`;
    const deckId = id('1');
    const draftId = id('2');
    const memberKey = id('3');
    const revisionId = id('4');
    const noteId = id('5');
    const commandId = id('6');
    const headers = { 'Cache-Control': 'private, no-store' };
    const draft = {
        draftId, deckId, memberKey, baseRevisionId: revisionId, rowVersion: '2', contentBytes: 700,
        createdAt: '2026-09-19T10:00:00Z', acknowledgedAt: '2026-09-19T10:01:00Z',
        expiresAt: '2026-10-19T10:01:00Z', document: mixedNativeDocumentFixture()
    };
    const capture = {
        noteId, deckId, rowVersion: '0', source: 'manual', text: 'Проверяемая мысль', contentBytes: 34,
        archived: false, createdAt: '2026-09-19T10:00:00Z', updatedAt: '2026-09-19T10:00:00Z', conversion: null
    };

    beforeEach(() => {
        TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
        api = TestBed.inject(AuthoringApiService);
        http = TestBed.inject(HttpTestingController);
    });
    afterEach(() => http.verify());

    it('reads a bounded private draft page and validates detail ETag', async () => {
        const page = firstValueFrom(api.listDrafts());
        http.expectOne('/api/editing-drafts?limit=20').flush({
            items: [{ ...draft, document: undefined }].map(({ document: _document, ...summary }) => summary),
            nextCursor: null
        }, { headers });
        expect((await page).items[0]?.draftId).toBe(draftId);

        const detail = firstValueFrom(api.readDraft(draftId));
        http.expectOne(`/api/editing-drafts/${draftId}`).flush(draft, { headers: { ...headers, ETag: '"2"' } });
        expect((await detail).document).toEqual(draft.document);
    });

    it('collects draft pages beyond the old ten-page ceiling', async () => {
        const result = firstValueFrom(api.listAllDrafts());
        const { document: _document, ...summary } = draft;
        for (let page = 0; page < 11; page++) {
            const cursor = page === 0 ? '' : `&cursor=page-${page}`;
            http.expectOne(`/api/editing-drafts?limit=20${cursor}`).flush({
                items: [{ ...summary, draftId: id(String(page + 100)) }],
                nextCursor: page === 10 ? null : `page-${page + 1}`
            }, { headers });
        }
        expect((await result).items.length).toBe(11);
    });

    it('sends exact draft CAS and accepts a replay only without ETag', async () => {
        const result = firstValueFrom(api.updateDraft(draftId, '2', draft.document, commandId));
        const request = http.expectOne(`/api/editing-drafts/${draftId}`);
        expect(request.request.headers.get('If-Match')).toBe('"2"');
        expect(request.request.body).toEqual({ commandId, document: draft.document });
        request.flush({ commandId, draft: { ...draft, rowVersion: '3' } }, {
            headers: { ...headers, 'Idempotency-Replayed': 'true' }
        });
        expect((await result).replayed).toBe(true);
    });

    it('creates and converts a capture with both note and deck preconditions', async () => {
        const created = firstValueFrom(api.createCapture(deckId, 'manual', capture.text, commandId));
        http.expectOne('/api/capture-notes').flush({ commandId, capture }, {
            status: 201, statusText: 'Created', headers: { ...headers, ETag: '"0"' }
        });
        expect((await created).acknowledgement.capture.text).toBe(capture.text);

        const converted = firstValueFrom(api.convertCapture(capture, '8', revisionId, draft.document, commandId));
        const request = http.expectOne(`/api/capture-notes/${noteId}/conversions`);
        expect(request.request.headers.get('If-Match')).toBe('"0"');
        expect(request.request.body.expectedDeckVersion).toBe('8');
        request.flush({
            commandId, noteId, noteVersion: '1', sourcePreserved: true,
            publication: {
                commandId, deckId, deckRevisionId: revisionId, deckVersion: '9', memberCount: 1,
                changes: [{ operation: 'create', memberKey, itemRevisionId: revisionId, itemVersion: '0', ordinal: 0 }]
            }
        }, { headers: { ...headers, ETag: '"1"' } });
        expect((await converted).publication.changes[0]?.memberKey).toBe(memberKey);
    });

    it('reads one note by id with its ETag, so a pin is the version that is current now (#290)', async () => {
        const note = firstValueFrom(api.readCapture(noteId));
        http.expectOne(`/api/capture-notes/${noteId}`).flush({ ...capture, rowVersion: '3' }, { headers: { ...headers, ETag: '"3"' } });
        expect(await note).toEqual({ ...capture, rowVersion: '3' });
        for (const [flush, label] of [
            [(request: ReturnType<HttpTestingController['expectOne']>) => request.flush(capture, { headers: { ...headers, ETag: '"9"' } }), 'wrong ETag'],
            [(request: ReturnType<HttpTestingController['expectOne']>) => request.flush(capture, { headers: { ETag: '"0"' } }), 'cacheable'],
            [(request: ReturnType<HttpTestingController['expectOne']>) => request.flush({ ...capture, leaked: true }, { headers: { ...headers, ETag: '"0"' } }), 'extra member']
        ] as const) {
            const attempt = firstValueFrom(api.readCapture(noteId));
            flush(http.expectOne(`/api/capture-notes/${noteId}`));
            await expect(attempt, label).rejects.toBeInstanceOf(AuthoringProtocolError);
        }
    });

    it('reads a deck-scoped pending capture count and deletes with the note version', async () => {
        const page = firstValueFrom(api.listDeckCaptures(deckId, null, 1));
        const request = http.expectOne(req => req.url === '/api/capture-notes'
            && req.params.get('deckId') === deckId && req.params.get('limit') === '1');
        expect(request.request.method).toBe('GET');
        request.flush({ items: [capture], nextCursor: 'later', total: 25 }, { headers });
        expect(await page).toEqual({ items: [capture], nextCursor: 'later', total: 25 });

        const removed = firstValueFrom(api.deleteCapture(capture));
        const deletion = http.expectOne(`/api/capture-notes/${noteId}`);
        expect(deletion.request.method).toBe('DELETE');
        expect(deletion.request.headers.get('If-Match')).toBe('"0"');
        deletion.flush('', { status: 204, statusText: 'No Content', headers });
        await removed;
    });

    it('rejects unrelated or converted notes in a deck-scoped page', async () => {
        const page = firstValueFrom(api.listDeckCaptures(deckId));
        http.expectOne(req => req.url === '/api/capture-notes').flush({
            items: [{ ...capture, deckId: id('9') }], nextCursor: null, total: 1
        }, { headers });
        await expect(page).rejects.toThrowError(AuthoringProtocolError);
    });

    it('rejects cacheable private data and unexpected fields', async () => {
        const page = firstValueFrom(api.listCaptures());
        http.expectOne('/api/capture-notes?limit=20').flush({ items: [], nextCursor: null, leaked: true });
        await expect(page).rejects.toThrowError(AuthoringProtocolError);
    });
});
