import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { HttpHeaders } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';

import contract from '../../../../../contracts/notifications/notifications.json';
import { ECHO_MS, TOAST_MS } from './toast.service';
import { NotificationsApiService } from './notifications-api.service';
import { presentNotification } from './notification-presenter';
import { AppNotification, NotificationListResult } from './notification.models';

/**
 * contracts/notifications/notifications.json is the wire contract shared with the backend: every example must parse
 * strictly, every kind must have a sentence, and the fixed behaviours (durations, endpoints) must agree.
 */
describe('Notifications wire contract (contracts/notifications/notifications.json)', () => {
    const kinds = contract.kinds as unknown as Record<string, {
        severity: string; route: string; params: Record<string, string>; example: AppNotification
    }>;
    let api: NotificationsApiService;
    let http: HttpTestingController;
    const headers = new HttpHeaders({ 'Cache-Control': 'private, no-store', ETag: '"n-42-40-0-1-1761987600"' });

    beforeEach(() => {
        TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
        api = TestBed.inject(NotificationsApiService);
        http = TestBed.inject(HttpTestingController);
    });
    afterEach(() => http.verify());

    async function parse(body: object, query = {}): Promise<NotificationListResult> {
        const result = firstValueFrom(api.list(query));
        http.expectOne(request => request.urlWithParams.startsWith('/api/notifications')).flush(body, { headers });
        return result;
    }

    for (const [kind, definition] of Object.entries(kinds)) {
        it(`parses the ${kind} example and presents it as a sentence`, async () => {
            const example = definition.example;
            const result = await parse({ items: [example], unreadCount: 1, readUpto: '0', activeWork: 0, nextCursor: null });
            expect(result).toMatchObject({ kind: 'page', page: { items: [example] } });
            expect(example.kind).toBe(kind);
            expect(example.severity).toBe(definition.severity);
            if (definition.route !== 'DYNAMIC') expect(example.route).toBe(definition.route);
            expect(Object.keys(example.params).every(key => key in definition.params)).toBe(true);
            const presented = presentNotification(example);
            expect(presented?.text.length ?? 0).toBeGreaterThan(10);
            expect(presented?.severity).toBe(definition.severity);
        });
    }

    it('parses the list, the catch-up list and the read cursor answer', async () => {
        const listing = await parse(contract.listResponse);
        expect(listing).toMatchObject({ kind: 'page', etag: '"n-42-40-0-1-1761987600"', page: { unreadCount: 2, readUpto: '40', activeWork: 1 } });
        if (listing.kind !== 'page') throw new Error('page expected');
        expect(listing.page.items.map(item => item.seq)).toEqual(['42', '41']);

        const { note: _note, ...catchUp } = contract.listResponseAfter;
        const after = await parse(catchUp, { after: '40' });
        if (after.kind !== 'page') throw new Error('page expected');
        expect(after.page.items.map(item => item.seq)).toEqual(['41', '42']);

        const cursor = firstValueFrom(api.setReadCursor(contract.readCursorResponse.readUpto));
        http.expectOne('/api/notifications/read-cursor').flush(contract.readCursorResponse, { headers });
        await expect(cursor).resolves.toEqual(contract.readCursorResponse);
    });

    it('answers the 304 example without a body', async () => {
        const result = firstValueFrom(api.list({ after: '42' }, contract.notModifiedExample.headers.ETag));
        http.expectOne('/api/notifications?after=42').flush(null, { status: contract.notModifiedExample.status, statusText: 'Not Modified' });
        await expect(result).resolves.toEqual({ kind: 'not-modified' });
    });

    it('uses the documented endpoints and the contract toast durations', () => {
        expect(contract.endpoints.map(endpoint => `${endpoint.method} ${endpoint.path}`)).toEqual([
            'GET /api/notifications', 'PUT /api/notifications/read-cursor', 'DELETE /api/notifications/{notificationId}'
        ]);
        expect(TOAST_MS).toBe(6000);
        expect(ECHO_MS).toBe(3000);
        expect(contract.model.toastDurations.INFO).toContain('6 s');
        expect(contract.model.toastDurations.WARNING).toContain('6');
        expect(contract.model.toastDurations.ERROR).toBe('until closed');
        expect(contract.polling.activeWorkSeconds).toBe(10);
    });
});
