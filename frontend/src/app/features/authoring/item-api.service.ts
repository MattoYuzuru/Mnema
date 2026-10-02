import { HttpClient, HttpHeaders, HttpParams, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, defer, map } from 'rxjs';

import { appConfig } from '../../app.config';
import { NativeDocument } from '../../content/native-document';
import { readNativeDocument, readRetainedNativeDocument } from '../../content/native-document-boundary';
import { NativeStructuralEdit } from '../../content/editing/native-structural-edits';
import { expectedEtag } from '../own-decks/own-deck.models';
import {
    AuthoringProtocolError,
    ITEM_PAGE_SIZE,
    ItemAcknowledgement,
    ItemChangeResult,
    ItemDetail,
    ItemPage,
    ItemSort,
    ItemSummary,
    ItemRecordSummary,
    ItemWriteResult,
    requireCommand,
    requireCount,
    requireCursor,
    requireEntity,
    requireInstant,
    requireObject,
    requireVersion
} from './authoring.models';

export interface ItemListOptions {
    readonly cursor?: string | null;
    readonly sort?: ItemSort;
    /** Adds `exerciseCount` to every summary without a per-material request. */
    readonly exerciseCount?: boolean;
}

@Injectable({ providedIn: 'root' })
export class ItemApiService {
    private readonly http = inject(HttpClient);
    private readonly baseUrl = appConfig.learningApiBaseUrl.replace(/\/$/, '');

    /**
     * One Browse page. `sort=exerciseCount` lists materials without exercises first and always carries the counts;
     * a cursor belongs to the sort and deck revision that issued it, so a sort change starts from the first page.
     */
    list(deckId: string, options: ItemListOptions = {}): Observable<ItemPage> {
        const { cursor = null, sort = 'ordinal', exerciseCount = false } = options;
        return defer(() => {
            const deck = requireEntity(deckId);
            let params = new HttpParams().set('limit', ITEM_PAGE_SIZE.toString());
            if (sort !== 'ordinal') params = params.set('sort', sort);
            if (exerciseCount || sort === 'exerciseCount') params = params.set('include', 'exerciseCount');
            if (cursor !== null) params = params.set('cursor', requireCursor(cursor)!);
            return this.http.get<unknown>(`${this.baseUrl}/decks/${encodeURIComponent(deck)}/items`, {
                params, observe: 'response'
            });
        }).pipe(map(response => {
            requirePrivate(response);
            const page = parseItemPage(response.body, exerciseCount || sort === 'exerciseCount');
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
        return this.write('POST', deckId, '', deckVersion, body, 201);
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
        return this.write('PUT', deckId, `/${encodeURIComponent(requireEntity(memberKey))}`, deckVersion, body, 200);
    }

    /** Delete is a versioned publication; retry an uncertain result with the same complete command. */
    delete(deckId: string, memberKey: string, deckVersion: string, deckRevisionId: string,
           itemRevisionId: string, expectedOrdinal: number, commandId: string): Observable<ItemWriteResult> {
        const member = requireEntity(memberKey);
        const body = {
            commandId: requireCommand(commandId), expectedDeckRevisionId: requireEntity(deckRevisionId),
            changes: [{ operation: 'delete', memberKey: member, expectedItemRevisionId: requireEntity(itemRevisionId),
                expectedOrdinal: requireOrdinal(expectedOrdinal, false) }]
        };
        return this.write('POST', deckId, '/publications', deckVersion, body, 200).pipe(map(result => {
            const changes = result.acknowledgement.changes;
            if (changes.length !== 1 || changes[0].operation !== 'delete' || changes[0].memberKey !== member
                || changes[0].itemRevisionId !== null || changes[0].ordinal !== null) {
                throw new AuthoringProtocolError('Invalid item deletion acknowledgement.');
            }
            return result;
        }));
    }

    private write(method: 'POST' | 'PUT', deckId: string, suffix: string, deckVersion: string,
                  body: Record<string, unknown>, status: number): Observable<ItemWriteResult> {
        return defer(() => {
            const deck = requireEntity(deckId);
            return this.http.request<unknown>(method, `${this.baseUrl}/decks/${encodeURIComponent(deck)}/items${suffix}`, {
                body, headers: new HttpHeaders({ 'If-Match': expectedEtag(deckVersion) }), observe: 'response'
            });
        }).pipe(map(response => parseWrite(response, status, body['commandId'] as string, deckId)));
    }
}

function parseItemPage(value: unknown, withCounts: boolean): ItemPage {
    const object = requireObject(value, ['deckId', 'deckRevisionId', 'deckVersion', 'total', 'exemplars', 'items', 'nextCursor']);
    if (!Array.isArray(object['items']) || object['items'].length > ITEM_PAGE_SIZE) {
        throw new AuthoringProtocolError('Invalid item page.');
    }
    const budget = requireObject(object['exemplars'], ['count', 'limit']);
    const exemplars = { count: requireCount(budget['count'], 100_000), limit: requireCount(budget['limit'], 1_000) };
    if (exemplars.count > exemplars.limit) throw new AuthoringProtocolError('Invalid exemplar budget.');
    const items = object['items'].map(entry => parseSummary(entry, withCounts));
    if (new Set(items.map(item => item.memberKey)).size !== items.length) throw new AuthoringProtocolError('Duplicate item.');
    return {
        deckId: requireEntity(object['deckId']), deckRevisionId: requireEntity(object['deckRevisionId']),
        deckVersion: requireVersion(object['deckVersion']), total: requireCount(object['total'], 100_000),
        exemplars, items, nextCursor: requireCursor(object['nextCursor'])
    };
}

function parseItemDetail(value: unknown, current: boolean): ItemDetail {
    const object = requireObject(value, [
        'memberKey', 'itemRevisionId', 'itemVersion', 'ordinal', 'formatVersion', 'createdAt', 'updatedAt',
        'deckId', 'deckRevisionId', 'deckVersion', 'document', 'exemplar'
    ]);
    if (typeof object['exemplar'] !== 'boolean') throw new AuthoringProtocolError('Invalid exemplar flag.');
    if (current && object['ordinal'] === null) {
        throw new AuthoringProtocolError('Current-item reads require a snapshot-bound ordinal.');
    }
    const ordinal = object['ordinal'] === null ? null : requireOrdinal(object['ordinal'], false);
    const summary = parseRecordSummary(object);
    return {
        ...summary, ordinal, exemplar: object['exemplar'], deckId: requireEntity(object['deckId']), deckRevisionId: requireEntity(object['deckRevisionId']),
        deckVersion: requireVersion(object['deckVersion']),
        document: readRetainedNativeDocument(object['document'])
    };
}

function parseSummary(value: unknown, withCount: boolean): ItemSummary {
    const keys = ['memberKey', 'itemRevisionId', 'itemVersion', 'ordinal', 'formatVersion', 'createdAt', 'updatedAt', 'title', 'exemplar'];
    const object = requireObject(value, withCount ? [...keys, 'exerciseCount'] : keys);
    if (typeof object['title'] !== 'string' || Array.from(object['title']).length > 240) {
        throw new AuthoringProtocolError('Invalid material title.');
    }
    if (typeof object['exemplar'] !== 'boolean') throw new AuthoringProtocolError('Invalid exemplar flag.');
    return {
        ...parseRecordSummary(object), ordinal: requireOrdinal(object['ordinal'], false), title: object['title'] as string,
        exerciseCount: withCount ? requireCount(object['exerciseCount'], 100_000) : null, exemplar: object['exemplar']
    };
}

function parseRecordSummary(object: Record<string, unknown>): ItemRecordSummary {
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
