import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { EventsApiService } from './events-api.service';
import { EventsProtocolError, eventDate, parseEvent, parseEventPage, parseManagedEvent } from './events.models';
import { TEST_EVENT, publicEvent } from './events-test-data';

describe('Events API', () => {
    let api: EventsApiService;
    let http: HttpTestingController;
    beforeEach(() => {
        TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
        api = TestBed.inject(EventsApiService); http = TestBed.inject(HttpTestingController);
    });
    afterEach(() => http.verify());

    it('reads public and private pages and sends opaque cursor unchanged', async () => {
        const publicResult = firstValueFrom(api.list());
        http.expectOne('/api/events').flush({ items: [publicEvent()], nextCursor: null });
        expect((await publicResult).items[0].title).toBe(TEST_EVENT.title);
        const managed = firstValueFrom(api.manage('opaque_cursor'));
        http.expectOne('/api/admin/events?cursor=opaque_cursor').flush({ items: [TEST_EVENT], nextCursor: 'next_cursor' });
        expect((await managed).nextCursor).toBe('next_cursor');
        const access = firstValueFrom(api.access());
        http.expectOne('/api/admin/events/access').flush({ allowed: true });
        await access;
    });

    it('uses version preconditions and the supplied stable command for writes and deletion', async () => {
        const edit = { commandId: crypto.randomUUID(), title: 'Обновление', bodyMarkdown: 'Текст', eventDate: '2026-10-07', published: false };
        const created = firstValueFrom(api.save(edit, null));
        const create = http.expectOne('/api/admin/events');
        expect(create.request.method).toBe('POST'); expect(create.request.body.commandId).toBe(edit.commandId);
        create.flush({ commandId: edit.commandId, event: TEST_EVENT }); await created;
        const updated = firstValueFrom(api.save(edit, TEST_EVENT));
        const update = http.expectOne(`/api/admin/events/${TEST_EVENT.eventId}`);
        expect(update.request.headers.get('If-Match')).toBe('"1"');
        expect(update.request.method).toBe('PUT'); update.flush({ event: TEST_EVENT }); await updated;
        const removed = firstValueFrom(api.remove(TEST_EVENT, edit.commandId));
        const remove = http.expectOne(`/api/admin/events/${TEST_EVENT.eventId}?commandId=${edit.commandId}`);
        expect(remove.request.method).toBe('DELETE'); expect(remove.request.headers.get('If-Match')).toBe('"1"');
        remove.flush(null); await removed;
    });

    it('rejects malformed access, excessive pages, duplicate identities and invalid dates or versions', async () => {
        const access = firstValueFrom(api.access());
        http.expectOne('/api/admin/events/access').flush({ allowed: false });
        await expect(access).rejects.toBeInstanceOf(EventsProtocolError);
        for (const value of [null, [], { items: Array(51).fill(publicEvent()), nextCursor: null },
            { items: [publicEvent(), publicEvent()], nextCursor: null }, { items: [], nextCursor: '' }]) {
            expect(() => parseEventPage(value, parseEvent)).toThrow(EventsProtocolError);
        }
        for (const value of ['2026-02-30', 'bad', '2026-1-1']) expect(() => eventDate(value)).toThrow();
        expect(() => parseEvent({ ...publicEvent(), eventId: 'unsafe', publishedAt: 'yesterday' })).toThrow();
        expect(() => parseEvent({ ...publicEvent(), publishedAt: 'yesterday' })).toThrow();
        expect(parseManagedEvent({ ...TEST_EVENT, rowVersion: '0' }).rowVersion).toBe('0');
        expect(() => parseManagedEvent({ ...TEST_EVENT, rowVersion: '-1' })).toThrow();
        expect(parseManagedEvent({ ...TEST_EVENT, published: false, publishedAt: null }).publishedAt).toBeNull();
    });
});
