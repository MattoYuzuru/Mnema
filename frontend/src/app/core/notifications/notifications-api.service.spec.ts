import { HttpHeaders, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';

import { NotificationProtocolError } from './notification.models';
import { NotificationsApiService } from './notifications-api.service';

const ID = '0a000000-0000-4000-8000-000000000029';
const item = (overrides: Record<string, unknown> = {}): Record<string, unknown> => ({
    notificationId: ID, seq: '41', kind: 'GENERATION_READY', severity: 'INFO',
    params: { deckId: '11111111-1111-4111-8111-111111111111', sessionKind: 'MATERIALS', artifactCount: 5 },
    route: 'DECK', createdAt: '2026-10-02T09:01:00Z', expiresAt: '2026-11-01T09:01:00Z', ...overrides
});
const envelope = (overrides: Record<string, unknown> = {}): Record<string, unknown> => ({
    items: [item()], unreadCount: 1, readUpto: '40', activeWork: 0, nextCursor: null, ...overrides
});
const privateHeaders = (extra: Record<string, string> = {}): HttpHeaders =>
    new HttpHeaders({ 'Cache-Control': 'private, no-store', ETag: '"n-41-40-0-0-1"', ...extra });

describe('NotificationsApiService', () => {
    let api: NotificationsApiService;
    let http: HttpTestingController;

    beforeEach(() => {
        TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
        api = TestBed.inject(NotificationsApiService);
        http = TestBed.inject(HttpTestingController);
    });
    afterEach(() => http.verify());

    async function list(body: object | null, headers = privateHeaders(), query = {}): Promise<unknown> {
        const result = firstValueFrom(api.list(query));
        http.expectOne(request => request.url === '/api/notifications').flush(body, { headers });
        return result;
    }

    it('parses the envelope and keeps the ETag', async () => {
        await expect(list(envelope())).resolves.toEqual({
            kind: 'page', etag: '"n-41-40-0-0-1"',
            page: { items: [item()], unreadCount: 1, readUpto: '40', activeWork: 0, nextCursor: null }
        });
    });

    it('sends only valid query parameters and echoes the ETag as If-None-Match', async () => {
        const result = firstValueFrom(api.list({ limit: 100, after: '40' }, '"opaque-1"'));
        const request = http.expectOne('/api/notifications?limit=100&after=40');
        expect(request.request.headers.get('If-None-Match')).toBe('"opaque-1"');
        request.flush(envelope(), { headers: privateHeaders() });
        await result;
        const next = firstValueFrom(api.list({ cursor: 'RDQw' }));
        const second = http.expectOne('/api/notifications?cursor=RDQw');
        expect(second.request.headers.has('If-None-Match')).toBe(false);
        second.flush(envelope(), { headers: privateHeaders() });
        await next;
    });

    it('refuses after together with cursor, a bad limit, a bad seq and a bad cursor before any request', async () => {
        for (const query of [{ after: '1', cursor: 'RDQw' }, { limit: 0 }, { limit: 101 }, { after: '01' }, { after: '-1' },
            { cursor: 'a/b' }, { cursor: '' }]) {
            await expect(firstValueFrom(api.list(query))).rejects.toBeInstanceOf(NotificationProtocolError);
        }
    });

    it('treats 304 as not modified', async () => {
        const result = firstValueFrom(api.list({ after: '41' }, '"n-41-40-0-0-1"'));
        http.expectOne('/api/notifications?after=41').flush(null, { status: 304, statusText: 'Not Modified' });
        await expect(result).resolves.toEqual({ kind: 'not-modified' });
    });

    it('keeps unknown kinds but rejects every malformed envelope', async () => {
        const unknown = await list(envelope({ items: [item({ kind: 'SOMETHING_NEW', params: { x: 1 } })] }));
        expect(unknown).toMatchObject({ kind: 'page', page: { items: [{ kind: 'SOMETHING_NEW' }] } });
        const bad: (object | null)[] = [
            { ...envelope(), extra: 1 }, envelope({ items: 'x' }), envelope({ unreadCount: -1 }), envelope({ unreadCount: 1.5 }),
            envelope({ readUpto: 40 }), envelope({ readUpto: '040' }), envelope({ activeWork: '0' }),
            envelope({ nextCursor: 'a/b' }), envelope({ nextCursor: 7 }),
            envelope({ items: [item({ seq: 41 })] }), envelope({ items: [item({ severity: 'FATAL' })] }),
            envelope({ items: [item({ route: 'URL' })] }), envelope({ items: [item({ kind: 'lower' })] }),
            envelope({ items: [item({ notificationId: 'nope' })] }), envelope({ items: [item({ createdAt: 'yesterday' })] }),
            envelope({ items: [item({ params: [] })] }), envelope({ items: [{ ...item(), url: '/x' }] }),
            envelope({ items: [item(), item()] }), null, []
        ];
        for (const body of bad) await expect(list(body)).rejects.toBeInstanceOf(NotificationProtocolError);
    });

    it('requires private no-store and an ETag on a 200', async () => {
        await expect(list(envelope(), new HttpHeaders({ ETag: '"x"' }))).rejects.toBeInstanceOf(NotificationProtocolError);
        await expect(list(envelope(), new HttpHeaders({ 'Cache-Control': 'private, no-store' })))
            .rejects.toBeInstanceOf(NotificationProtocolError);
    });

    it('moves the read cursor with a decimal string and parses the answer', async () => {
        const result = firstValueFrom(api.setReadCursor('42'));
        const request = http.expectOne('/api/notifications/read-cursor');
        expect(request.request.method).toBe('PUT');
        expect(request.request.body).toEqual({ readUpto: '42' });
        request.flush({ readUpto: '42', unreadCount: 0 }, { headers: privateHeaders() });
        await expect(result).resolves.toEqual({ readUpto: '42', unreadCount: 0 });
        await expect(firstValueFrom(api.setReadCursor('4.2'))).rejects.toBeInstanceOf(NotificationProtocolError);

        const malformed = firstValueFrom(api.setReadCursor('42'));
        http.expectOne('/api/notifications/read-cursor').flush({ readUpto: 42, unreadCount: 0 }, { headers: privateHeaders() });
        await expect(malformed).rejects.toBeInstanceOf(NotificationProtocolError);
    });

    it('dismisses by id and expects 204', async () => {
        const result = firstValueFrom(api.dismiss(ID));
        const request = http.expectOne(`/api/notifications/${ID}`);
        expect(request.request.method).toBe('DELETE');
        request.flush(null, { status: 204, statusText: 'No Content' });
        await expect(result).resolves.toBeUndefined();
        await expect(firstValueFrom(api.dismiss('../decks'))).rejects.toBeInstanceOf(NotificationProtocolError);
    });
});
