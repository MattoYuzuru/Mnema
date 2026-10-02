import { HttpClient, HttpErrorResponse, HttpHeaders, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, catchError, defer, map, of, throwError } from 'rxjs';

import { appConfig } from '../../app.config';
import {
    AppNotification,
    NOTIFICATION_ROUTES,
    NOTIFICATION_SEVERITIES,
    NotificationListResult,
    NotificationPage,
    NotificationProtocolError,
    NotificationQuery,
    ReadCursorResult
} from './notification.models';

const ITEM_KEYS = ['notificationId', 'seq', 'kind', 'severity', 'params', 'route', 'createdAt', 'expiresAt'];
const PAGE_KEYS = ['items', 'unreadCount', 'readUpto', 'activeWork', 'nextCursor'];
const SEQ = /^(?:0|[1-9][0-9]{0,17})$/u;
const CURSOR = /^[A-Za-z0-9_-]{1,64}$/u;
const KIND = /^[A-Z][A-Z0-9_]{0,63}$/u;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/u;
const INSTANT = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?(?:Z|[+-]\d{2}:\d{2})$/u;

/**
 * HTTP boundary of the notification center. Envelopes are parsed strictly; the `ETag` is an opaque validator that is
 * echoed back as `If-None-Match`, so an unchanged center costs a `304` without a body.
 */
@Injectable({ providedIn: 'root' })
export class NotificationsApiService {
    private readonly http = inject(HttpClient);
    private readonly baseUrl = appConfig.learningApiBaseUrl.replace(/\/$/u, '');

    /** `after` and `cursor` are mutually exclusive on the server (400), so they are refused here first. */
    list(query: NotificationQuery = {}, etag: string | null = null): Observable<NotificationListResult> {
        return defer(() => {
            const params = new URLSearchParams();
            if (query.limit !== undefined) {
                if (!Number.isInteger(query.limit) || query.limit < 1 || query.limit > 100) throw protocol('Invalid limit.');
                params.set('limit', String(query.limit));
            }
            if (query.after !== undefined && query.cursor !== undefined) throw protocol('after and cursor are exclusive.');
            if (query.after !== undefined) params.set('after', seq(query.after));
            if (query.cursor !== undefined) {
                if (!CURSOR.test(query.cursor)) throw protocol('Invalid cursor.');
                params.set('cursor', query.cursor);
            }
            const suffix = params.size === 0 ? '' : `?${params.toString()}`;
            const headers = etag === null ? undefined : new HttpHeaders({ 'If-None-Match': etag });
            return this.http.get<unknown>(`${this.baseUrl}/notifications${suffix}`, { observe: 'response', headers });
        }).pipe(
            map((response): NotificationListResult => {
                if (response.status === 304) return { kind: 'not-modified' };
                if (response.status !== 200) throw protocol('Unexpected notification list status.');
                privateResponse(response);
                const validator = response.headers.get('ETag');
                if (validator === null || validator.length < 1 || validator.length > 256) throw protocol('Missing ETag.');
                return { kind: 'page', page: parsePage(response.body), etag: validator };
            }),
            // Angular surfaces every non-2xx status, 304 included, as an error.
            catchError((error: unknown) => error instanceof HttpErrorResponse && error.status === 304
                ? of<NotificationListResult>({ kind: 'not-modified' }) : throwError(() => error))
        );
    }

    /** Moves the read watermark to `readUpto` (a monotonic maximum on the server; a value past the newest `seq` is 400). */
    setReadCursor(readUpto: string): Observable<ReadCursorResult> {
        return defer(() => this.http.put<unknown>(`${this.baseUrl}/notifications/read-cursor`,
            { readUpto: seq(readUpto) }, { observe: 'response' })).pipe(map(response => {
            if (response.status !== 200) throw protocol('Unexpected read cursor status.');
            privateResponse(response);
            const body = exact(response.body, ['readUpto', 'unreadCount']);
            return { readUpto: seq(body['readUpto']), unreadCount: count(body['unreadCount']) };
        }));
    }

    /** Removes one notification from the center; repeating it is still 204. */
    dismiss(notificationId: string): Observable<void> {
        return defer(() => this.http.delete(`${this.baseUrl}/notifications/${uuid(notificationId)}`,
            { observe: 'response', responseType: 'text' })).pipe(map(response => {
            if (response.status !== 204) throw protocol('Unexpected dismiss status.');
        }));
    }
}

function protocol(message: string): NotificationProtocolError { return new NotificationProtocolError(message); }

function record(value: unknown): Record<string, unknown> {
    if (typeof value !== 'object' || value === null || Array.isArray(value)) throw protocol('Expected object.');
    return value as Record<string, unknown>;
}

function exact(value: unknown, keys: readonly string[]): Record<string, unknown> {
    const object = record(value);
    const actual = Object.keys(object);
    if (actual.length !== keys.length || actual.some(key => !keys.includes(key))) throw protocol('Unexpected shape.');
    return object;
}

function seq(value: unknown): string {
    if (typeof value !== 'string' || !SEQ.test(value)) throw protocol('Invalid seq.');
    return value;
}

function uuid(value: unknown): string {
    if (typeof value !== 'string' || !UUID.test(value)) throw protocol('Invalid notification id.');
    return value;
}

function count(value: unknown): number {
    if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 0) throw protocol('Invalid count.');
    return value;
}

function instant(value: unknown): string {
    if (typeof value !== 'string' || !INSTANT.test(value) || Number.isNaN(Date.parse(value))) throw protocol('Invalid timestamp.');
    return value;
}

function oneOf<T extends string>(value: unknown, allowed: readonly T[]): T {
    if (typeof value !== 'string' || !(allowed as readonly string[]).includes(value)) throw protocol('Invalid enum value.');
    return value as T;
}

function parseItem(value: unknown): AppNotification {
    const item = exact(value, ITEM_KEYS);
    const kind = item['kind'];
    if (typeof kind !== 'string' || !KIND.test(kind)) throw protocol('Invalid kind.');
    return {
        notificationId: uuid(item['notificationId']), seq: seq(item['seq']), kind,
        severity: oneOf(item['severity'], NOTIFICATION_SEVERITIES), params: record(item['params']),
        route: oneOf(item['route'], NOTIFICATION_ROUTES), createdAt: instant(item['createdAt']),
        expiresAt: instant(item['expiresAt'])
    };
}

function parsePage(value: unknown): NotificationPage {
    const body = exact(value, PAGE_KEYS);
    const items = body['items'];
    if (!Array.isArray(items) || items.length > 100) throw protocol('Invalid items.');
    const cursor = body['nextCursor'];
    if (cursor !== null && (typeof cursor !== 'string' || !CURSOR.test(cursor))) throw protocol('Invalid next cursor.');
    const parsed = items.map(parseItem);
    if (new Set(parsed.map(item => item.notificationId)).size !== parsed.length) throw protocol('Duplicate notification.');
    return {
        items: parsed, unreadCount: count(body['unreadCount']), readUpto: seq(body['readUpto']),
        activeWork: count(body['activeWork']), nextCursor: cursor
    };
}

function privateResponse(response: HttpResponse<unknown>): void {
    const directives = (response.headers.get('Cache-Control') ?? '').toLowerCase().split(',').map(value => value.trim());
    if (!directives.includes('private') || !directives.includes('no-store')) throw protocol('Notification response can be cached.');
}
