import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { firstValueFrom } from 'rxjs';

import catalog from '../../../../../contracts/community/catalog.json';
import { AdminApiService } from './admin-api.service';
import { DECK_CODE, EDITORIAL_MAX, offendingCodes, parseAnomalyPage, parseDeckInput, parseEditorial } from './admin-community.models';
import { AdminProtocolError } from './admin.models';
import { clone } from './admin-test-data';

/** contracts/community/catalog.json (`editorial`, `anomalies`) is the wire contract shared with the backend: every example must parse strictly. */
describe('Community console wire contract (contracts/community/catalog.json)', () => {
    const state = catalog.editorial.state.response;
    const list = catalog.anomalies.list.response;

    it('knows the limits of the contract', () => {
        expect(EDITORIAL_MAX).toBe(catalog.constants.editorialMax);
        expect(catalog.constants.anomaly).toMatchObject({ minForks: 20, windowDays: 7, newAccountHours: 48, newShareAbove: 0.7 });
    });

    it('parses the editorial state with exactly the documented properties', () => {
        const parsed = parseEditorial(state);
        expect(parsed.rowVersion).toBe('3');
        expect(Object.keys(state).sort()).toEqual(['entries', 'rowVersion']);
        expect(Object.keys(parsed.entries[0]).sort()).toEqual(Object.keys(state.entries[0]).sort());
        expect(parsed.entries[0]).toMatchObject({ code: 'Kq7xT3mNpR', listed: true });
    });

    it('parses the anomaly list: an open flag and a dismissed one, with the cursor called next', () => {
        const page = parseAnomalyPage(list);
        expect(page.next).toBeNull();
        expect(page.items).toHaveLength(2);
        for (const item of page.items) expect(Object.keys(item).sort()).toEqual(Object.keys(list.entries[0]).sort());
        expect(page.items[0]).toMatchObject({ windowForks: 42, newShare: 0.83, dismissedAt: null });
        expect(page.items[1].dismissedAt).toBe('2026-10-06T08:15:00Z');
        expect(parseAnomalyPage({ ...list, next: 'opaque' }).next).toBe('opaque');
    });

    it('refuses what the contract does not allow', () => {
        const entry = state.entries[0];
        expect(() => parseEditorial({ ...state, rowVersion: 'x' })).toThrow(AdminProtocolError);
        expect(() => parseEditorial({ ...state, rowVersion: 3 })).toThrow(AdminProtocolError);
        expect(() => parseEditorial({ ...state, entries: [{ ...entry, code: 'short' }] })).toThrow(AdminProtocolError);
        expect(() => parseEditorial({ ...state, entries: [{ ...entry, code: 'Kq7xT3mNp0' }] })).toThrow(AdminProtocolError);
        expect(() => parseEditorial({ ...state, entries: [{ ...entry, listed: 'yes' }] })).toThrow(AdminProtocolError);
        expect(() => parseEditorial({ ...state, entries: [entry, entry] })).toThrow(AdminProtocolError);
        const tooMany = Array.from({ length: EDITORIAL_MAX + 1 }, (_, index) => ({ ...entry, code: `Kq7xT3mN${String(index).padStart(2, '1')}` }));
        expect(() => parseEditorial({ ...state, entries: tooMany })).toThrow(AdminProtocolError);
        const flag = clone(list.entries[0]);
        expect(() => parseAnomalyPage({ ...list, entries: [{ ...flag, newShare: 1.2 }] })).toThrow(AdminProtocolError);
        expect(() => parseAnomalyPage({ ...list, entries: [{ ...flag, windowForks: -1 }] })).toThrow(AdminProtocolError);
        expect(() => parseAnomalyPage({ ...list, entries: [{ ...flag, ownerId: 'x' }] })).toThrow(AdminProtocolError);
        expect(() => parseAnomalyPage({ ...list, entries: [{ ...flag, dismissedAt: 5 }] })).toThrow(AdminProtocolError);
        expect(() => parseAnomalyPage({ ...list, next: 5 })).toThrow(AdminProtocolError);
        expect(() => parseAnomalyPage({ items: list.entries, next: null })).toThrow(AdminProtocolError);
    });

    it('takes a public link or a bare code and nothing else', () => {
        for (const text of ['Kq7xT3mNpR', ' Kq7xT3mNpR ', 'https://mnema.app/d/Kq7xT3mNpR', 'https://mnema.app/d/Kq7xT3mNpR/ispanskii-yazyk', 'http://localhost:4200/d/Kq7xT3mNpR/', 'mnema.app/d/Kq7xT3mNpR'])
            expect(parseDeckInput(text), text).toEqual({ code: 'Kq7xT3mNpR' });
        for (const text of ['', '   ', 'Kq7xT3mNp', 'Kq7xT3mNpR1', 'Kq7xT3mNp0', 'https://mnema.app/community', 'https://mnema.app/d/', 'https://mnema.app/d/Kq7xT3mNpR/a/b', 'javascript:alert(1)', 'ftp://mnema.app/d/Kq7xT3mNpR'])
            expect(parseDeckInput(text), text).toHaveProperty('error');
        expect(DECK_CODE.test('Kq7xT3mNpR')).toBe(true);
    });

    it('reads the codes a 400 names, whatever the member is called, and nothing else', () => {
        const problem = (body: unknown) => new HttpErrorResponse({ status: 400, error: body });
        expect(offendingCodes(problem({ code: 'INVALID_REQUEST', title: 'Zp4WcA8vQe', codes: ['Zp4WcA8vQe', 'not a code'] }))).toEqual(['Zp4WcA8vQe']);
        expect(offendingCodes(problem({ invalidCodes: [{ code: 'Kq7xT3mNpR' }, 'Zp4WcA8vQe'] }))).toEqual(['Kq7xT3mNpR', 'Zp4WcA8vQe']);
        expect(offendingCodes(problem({ detail: 'Zp4WcA8vQe' }))).toEqual([]);
        expect(offendingCodes(problem('text'))).toEqual([]);
        expect(offendingCodes(problem(null))).toEqual([]);
        expect(offendingCodes(new HttpErrorResponse({ status: 500, error: { codes: ['Zp4WcA8vQe'] } }))).toEqual([]);
        expect(offendingCodes(new Error('x'))).toEqual([]);
    });

    describe('routes', () => {
        let api: AdminApiService;
        let http: HttpTestingController;
        beforeEach(() => {
            TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])] });
            api = TestBed.inject(AdminApiService);
            http = TestBed.inject(HttpTestingController);
        });
        afterEach(() => http.verify());

        it('uses the documented paths, headers and bodies', async () => {
            const read = firstValueFrom(api.editorial());
            http.expectOne(request => request.method === 'GET' && request.url === '/api/admin/community/editorial').flush(state);
            expect((await read).entries).toHaveLength(1);

            const body = catalog.editorial.put.body;
            const put = firstValueFrom(api.saveEditorial(body, '3'));
            const sent = http.expectOne('/api/admin/community/editorial');
            expect(sent.request.method).toBe('PUT');
            expect(sent.request.headers.get('If-Match')).toBe('"3"');
            expect(sent.request.body).toEqual(body);
            sent.flush(null, { status: 204, statusText: 'No Content' });
            await put;

            const flags = firstValueFrom(api.anomalies('opaque/+cursor'));
            const listRequest = http.expectOne(request => request.url === '/api/admin/community/anomalies');
            expect(listRequest.request.params.get('cursor')).toBe('opaque/+cursor');
            listRequest.flush(list);
            await flags;
            const first = firstValueFrom(api.anomalies(null));
            const firstRequest = http.expectOne(request => request.url === '/api/admin/community/anomalies');
            expect(firstRequest.request.params.has('cursor')).toBe(false);
            firstRequest.flush({ entries: [], next: null });
            await first;

            const dismissBody = catalog.anomalies.dismiss.body;
            const dismissed = firstValueFrom(api.dismissAnomaly('Kq7xT3mNpR', dismissBody.commandId));
            const dismiss = http.expectOne('/api/admin/community/anomalies/Kq7xT3mNpR:dismiss');
            expect(dismiss.request.method).toBe('POST');
            expect(dismiss.request.body).toEqual(dismissBody);
            dismiss.flush(null, { status: 204, statusText: 'No Content' });
            await dismissed;
        });
    });
});
