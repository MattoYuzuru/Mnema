import { HttpClient, HttpHeaders, HttpParams, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, defer, map } from 'rxjs';

import { appConfig } from '../../app.config';
import { NativeDocument } from '../../content/native-document';
import { readNativeDocument } from '../../content/native-document-boundary';
import { NativeStructuralEdit } from '../../content/editing/native-structural-edits';
import { expectedEtag } from '../own-decks/own-deck.models';
import {
    AuthoringProtocolError,
    ITEM_PAGE_SIZE,
    ItemAcknowledgement,
    ItemChangeResult,
    ItemDetail,
    ItemPage,
    ItemSummary,
    ItemWriteResult,
    requireCommand,
    requireCount,
    requireCursor,
    requireEntity,
    requireInstant,
    requireObject,
    requireVersion
} from './authoring.models';

@Injectable({ providedIn: 'root' })
export class ItemApiService {
    private readonly http = inject(HttpClient);
    private readonly baseUrl = appConfig.learningApiBaseUrl.replace(/\/$/, '');

    list(deckId: string, cursor: string | null = null): Observable<ItemPage> {
        return defer(() => {
            const deck = requireEntity(deckId);
            let params = new HttpParams().set('limit', ITEM_PAGE_SIZE.toString());
            if (cursor !== null) params = params.set('cursor', requireCursor(cursor)!);
            return this.http.get<unknown>(`${this.baseUrl}/decks/${encodeURIComponent(deck)}/items`, {
                params, observe: 'response'
            });
        }).pipe(map(response => {
            requirePrivate(response);
            const page = parseItemPage(response.body);
            requireEtag(response, page.deckVersion);
            if (page.deckId !== deckId.toLowerCase()) throw new AuthoringProtocolError('Item page deck mismatch.');
            return page;
        }));
    }

    read(deckId: string, memberKey: string, revisionId: string | null = null): Observable<ItemDetail> {
        return defer(() => {
            const deck = requireEntity(deckId);
            const member = requireEntity(memberKey);
            let params = new HttpParams();
            if (revisionId !== null) params = params.set('revisionId', requireEntity(revisionId));
            return this.http.get<unknown>(`${this.baseUrl}/decks/${encodeURIComponent(deck)}/items/${encodeURIComponent(member)}`, {
                params, observe: 'response'
            });
        }).pipe(map(response => {
            requirePrivate(response);
            const detail = parseItemDetail(response.body, revisionId === null);
            requireEtag(response, detail.deckVersion);
            if (detail.deckId !== deckId.toLowerCase() || detail.memberKey !== memberKey.toLowerCase()) {
                throw new AuthoringProtocolError('Item response identity mismatch.');
            }
            return detail;
        }));
    }

    create(deckId: string, deckVersion: string, deckRevisionId: string, document: NativeDocument,
           commandId: string, ordinal?: number): Observable<ItemWriteResult> {
        const body: Record<string, unknown> = {
            commandId: requireCommand(commandId), expectedDeckRevisionId: requireEntity(deckRevisionId),
            document: readNativeDocument(document)
        };
        if (ordinal !== undefined) body['ordinal'] = requireOrdinal(ordinal, true);
        return this.write('POST', deckId, null, deckVersion, body, 201);
    }

    save(deckId: string, memberKey: string, deckVersion: string, deckRevisionId: string,
         itemRevisionId: string, expectedOrdinal: number, document: NativeDocument,
         commandId: string, edits: readonly NativeStructuralEdit[]): Observable<ItemWriteResult> {
        const body: Record<string, unknown> = {
            commandId: requireCommand(commandId), expectedDeckRevisionId: requireEntity(deckRevisionId),
            expectedItemRevisionId: requireEntity(itemRevisionId), expectedOrdinal: requireOrdinal(expectedOrdinal, false),
            document: readNativeDocument(document)
        };
        if (edits.length > 0) body['edits'] = edits;
        return this.write('PUT', deckId, memberKey, deckVersion, body, 200);
    }

    private write(method: 'POST' | 'PUT', deckId: string, memberKey: string | null, deckVersion: string,
                  body: Record<string, unknown>, status: number): Observable<ItemWriteResult> {
        return defer(() => {
            const deck = requireEntity(deckId);
            const suffix = memberKey === null ? '' : `/${encodeURIComponent(requireEntity(memberKey))}`;
            return this.http.request<unknown>(method, `${this.baseUrl}/decks/${encodeURIComponent(deck)}/items${suffix}`, {
                body, headers: new HttpHeaders({ 'If-Match': expectedEtag(deckVersion) }), observe: 'response'
            });
        }).pipe(map(response => parseWrite(response, status, body['commandId'] as string, deckId)));
    }
}

function parseItemPage(value: unknown): ItemPage {
    const object = requireObject(value, ['deckId', 'deckRevisionId', 'deckVersion', 'total', 'items', 'nextCursor']);
    if (!Array.isArray(object['items']) || object['items'].length > ITEM_PAGE_SIZE) {
        throw new AuthoringProtocolError('Invalid item page.');
    }
    const items = object['items'].map(parseSummary);
    if (new Set(items.map(item => item.memberKey)).size !== items.length) throw new AuthoringProtocolError('Duplicate item.');
    return {
        deckId: requireEntity(object['deckId']), deckRevisionId: requireEntity(object['deckRevisionId']),
        deckVersion: requireVersion(object['deckVersion']), total: requireCount(object['total'], 100_000),
        items, nextCursor: requireCursor(object['nextCursor'])
    };
}

function parseItemDetail(value: unknown, current: boolean): ItemDetail {
    const object = requireObject(value, [
        'memberKey', 'itemRevisionId', 'itemVersion', 'ordinal', 'formatVersion', 'createdAt', 'updatedAt',
        'deckId', 'deckRevisionId', 'deckVersion', 'document'
    ]);
    if (current && object['ordinal'] !== null) {
        throw new AuthoringProtocolError('Direct current-item reads must not scan canonical order.');
    }
    const ordinal = object['ordinal'] === null ? null : requireOrdinal(object['ordinal'], false);
    const summary = parseRecordSummary(object);
    return {
        ...summary, ordinal, deckId: requireEntity(object['deckId']), deckRevisionId: requireEntity(object['deckRevisionId']),
        deckVersion: requireVersion(object['deckVersion']),
        document: readNativeDocument(object['document'])
    };
}

function parseSummary(value: unknown): ItemSummary {
    const object = requireObject(value, ['memberKey', 'itemRevisionId', 'itemVersion', 'ordinal', 'formatVersion', 'createdAt', 'updatedAt']);
    return { ...parseRecordSummary(object), ordinal: requireOrdinal(object['ordinal'], false) };
}

function parseRecordSummary(object: Record<string, unknown>): Omit<ItemSummary, 'ordinal'> {
    if (object['formatVersion'] !== 1) throw new AuthoringProtocolError('Unsupported item format.');
    return {
        memberKey: requireEntity(object['memberKey']), itemRevisionId: requireEntity(object['itemRevisionId']),
        itemVersion: requireVersion(object['itemVersion']),
        formatVersion: 1, createdAt: requireInstant(object['createdAt']), updatedAt: requireInstant(object['updatedAt'])
    };
}

function parseWrite(response: HttpResponse<unknown>, status: number, commandId: string, deckId: string): ItemWriteResult {
    if (response.status !== status) throw new AuthoringProtocolError('Unexpected item status.');
    requirePrivate(response);
    const acknowledgement = parseAcknowledgement(response.body);
    if (acknowledgement.commandId !== commandId || acknowledgement.deckId !== deckId.toLowerCase()) {
        throw new AuthoringProtocolError('Item acknowledgement mismatch.');
    }
    const replayed = requireReplayHeader(response);
    const etag = response.headers.get('ETag');
    if (replayed ? etag !== null : etag !== expectedEtag(acknowledgement.deckVersion)) {
        throw new AuthoringProtocolError('Invalid item acknowledgement ETag.');
    }
    return { acknowledgement, replayed };
}

function requireReplayHeader(response: HttpResponse<unknown>): boolean {
    const value = response.headers.get('Idempotency-Replayed');
    if (value !== null && value !== 'true') throw new AuthoringProtocolError('Invalid item replay header.');
    return value === 'true';
}

function parseAcknowledgement(value: unknown): ItemAcknowledgement {
    const object = requireObject(value, ['commandId', 'deckId', 'deckRevisionId', 'deckVersion', 'memberCount', 'changes']);
    if (!Array.isArray(object['changes']) || object['changes'].length === 0 || object['changes'].length > 100) {
        throw new AuthoringProtocolError('Invalid item changes.');
    }
    return {
        commandId: requireCommand(object['commandId']), deckId: requireEntity(object['deckId']),
        deckRevisionId: requireEntity(object['deckRevisionId']), deckVersion: requireVersion(object['deckVersion']),
        memberCount: requireCount(object['memberCount'], 100_000), changes: object['changes'].map(parseChange)
    };
}

function parseChange(value: unknown): ItemChangeResult {
    const object = requireObject(value, ['operation', 'memberKey', 'itemRevisionId', 'itemVersion', 'ordinal']);
    const operation = object['operation'];
    if (operation !== 'create' && operation !== 'save' && operation !== 'delete' && operation !== 'reorder') {
        throw new AuthoringProtocolError('Invalid item operation.');
    }
    const revision = object['itemRevisionId'] === null ? null : requireEntity(object['itemRevisionId']);
    const ordinal = object['ordinal'] === null ? null : requireOrdinal(object['ordinal'], false);
    return {
        operation, memberKey: requireEntity(object['memberKey']), itemRevisionId: revision,
        itemVersion: requireVersion(object['itemVersion']), ordinal
    };
}

function requireOrdinal(value: unknown, allowEnd: boolean): number {
    const maximum = allowEnd ? 100_000 : 99_999;
    return requireCount(value, maximum);
}

function requirePrivate(response: HttpResponse<unknown>): void {
    const cache = (response.headers.get('Cache-Control') ?? '').toLowerCase().split(',').map(value => value.trim());
    if (!cache.includes('private') || !cache.includes('no-store')) throw new AuthoringProtocolError('Private response can be cached.');
}

function requireEtag(response: HttpResponse<unknown>, version: string): void {
    if (response.headers.get('ETag') !== expectedEtag(version)) throw new AuthoringProtocolError('Invalid item ETag.');
}
