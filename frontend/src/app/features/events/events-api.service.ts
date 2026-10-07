import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map, timeout } from 'rxjs';
import { appConfig } from '../../app.config';
import { EventEdit, EventPage, EventsProtocolError, ManagedEvent, ProductEvent, parseEvent, parseEventEnvelope,
    parseEventPage, parseManagedEvent } from './events.models';

@Injectable({ providedIn: 'root' })
export class EventsApiService {
    private readonly http = inject(HttpClient);
    private readonly base = appConfig.learningApiBaseUrl.replace(/\/$/u, '');

    list(cursor: string | null = null): Observable<EventPage<ProductEvent>> {
        return this.http.get<unknown>(`${this.base}/events`, { params: this.params(cursor) })
            .pipe(timeout(10000), map(value => parseEventPage(value, parseEvent)));
    }

    access(): Observable<void> {
        return this.http.get<unknown>(`${this.base}/admin/events/access`).pipe(timeout(10000), map(value => {
            if (typeof value !== 'object' || value === null || Array.isArray(value) ||
                (value as Record<string, unknown>)['allowed'] !== true) throw new EventsProtocolError('Invalid access response');
        }));
    }

    manage(cursor: string | null = null): Observable<EventPage<ManagedEvent>> {
        return this.http.get<unknown>(`${this.base}/admin/events`, { params: this.params(cursor) })
            .pipe(timeout(10000), map(value => parseEventPage(value, parseManagedEvent)));
    }

    save(edit: EventEdit, existing: ManagedEvent | null): Observable<ManagedEvent> {
        const request = existing === null
            ? this.http.post<unknown>(`${this.base}/admin/events`, edit)
            : this.http.put<unknown>(`${this.base}/admin/events/${existing.eventId}`, edit,
                { headers: { 'If-Match': `"${existing.rowVersion}"` } });
        return request.pipe(timeout(10000), map(parseEventEnvelope));
    }

    remove(event: ManagedEvent, commandId: string): Observable<void> {
        return this.http.delete<void>(`${this.base}/admin/events/${event.eventId}`, {
            params: { commandId }, headers: { 'If-Match': `"${event.rowVersion}"` }
        }).pipe(timeout(10000));
    }

    private params(cursor: string | null): HttpParams { return cursor === null ? new HttpParams() : new HttpParams().set('cursor', cursor); }
}
