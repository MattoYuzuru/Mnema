import { HttpClient, HttpHeaders, HttpParams, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { EMPTY, Observable, defer, expand, map, reduce, take } from 'rxjs';

import { appConfig } from '../../app.config';
import { NativeDocument } from '../../content/native-document';
import { readNativeDocument } from '../../content/native-document-boundary';
import { expectedEtag } from '../own-decks/own-deck.models';
import {
    AUTHORING_PAGE_SIZE, AuthoringProtocolError, CaptureAcknowledgement, CaptureConversion,
    CaptureConversionAcknowledgement, CaptureNote, CapturePage, CaptureWriteResult, DraftAcknowledgement,
    DraftDetail, DraftPage, DraftSummary, DraftWriteResult, ItemAcknowledgement, ItemChangeResult,
    requireCommand, requireCount, requireCursor, requireEntity, requireInstant, requireObject, requireVersion
} from './authoring.models';

@Injectable({ providedIn: 'root' })
export class AuthoringApiService {
    private readonly http = inject(HttpClient);
    private readonly baseUrl = appConfig.learningApiBaseUrl.replace(/\/$/, '');

    listDrafts(cursor: string | null = null): Observable<DraftPage> {
        return this.list('/editing-drafts', cursor).pipe(map(response => ({
            items: parsePage(response, parseDraftSummary), nextCursor: parseNextCursor(response)
        })));
    }

    listAllDrafts(): Observable<DraftPage> {
        return this.listDrafts().pipe(
            expand(page => page.nextCursor === null ? EMPTY : this.listDrafts(page.nextCursor)),
            take(10),
            reduce((all, page) => ({ items: [...all.items, ...page.items], nextCursor: page.nextCursor }),
                { items: [] as readonly DraftSummary[], nextCursor: null } as DraftPage)
        );
    }

    readDraft(draftId: string): Observable<DraftDetail> {
        return this.http.get<unknown>(`${this.baseUrl}/editing-drafts/${encodeURIComponent(requireEntity(draftId))}`, {
            observe: 'response'
        }).pipe(map(response => {
            requirePrivate(response);
            const draft = parseDraftDetail(response.body);
            requireEtag(response, draft.rowVersion);
            return draft;
        }));
    }

    createDraft(deckId: string, document: NativeDocument, memberKey: string | null,
                baseRevisionId: string | null, commandId: string): Observable<DraftWriteResult> {
        const body: Record<string, unknown> = {
            commandId: requireCommand(commandId), deckId: requireEntity(deckId), document: readNativeDocument(document)
        };
        if (memberKey !== null) body['memberKey'] = requireEntity(memberKey);
        if (baseRevisionId !== null) body['baseRevisionId'] = requireEntity(baseRevisionId);
        return this.http.post<unknown>(`${this.baseUrl}/editing-drafts`, body, { observe: 'response' }).pipe(
            map(response => parseDraftWrite(response, 201, commandId))
        );
    }

    updateDraft(draftId: string, rowVersion: string, document: NativeDocument,
                commandId: string): Observable<DraftWriteResult> {
        return this.http.put<unknown>(`${this.baseUrl}/editing-drafts/${encodeURIComponent(requireEntity(draftId))}`, {
            commandId: requireCommand(commandId), document: readNativeDocument(document)
        }, { headers: ifMatch(rowVersion), observe: 'response' }).pipe(
            map(response => parseDraftWrite(response, 200, commandId))
        );
    }

    deleteDraft(draftId: string, rowVersion: string): Observable<void> {
        return this.http.delete(`${this.baseUrl}/editing-drafts/${encodeURIComponent(requireEntity(draftId))}`, {
            headers: ifMatch(rowVersion), observe: 'response', responseType: 'text'
        }).pipe(map(response => {
            if (response.status !== 204) throw new AuthoringProtocolError('Unexpected draft delete status.');
            requirePrivate(response);
        }));
    }

    listCaptures(cursor: string | null = null): Observable<CapturePage> {
        return this.list('/capture-notes', cursor).pipe(map(response => ({
            items: parsePage(response, parseCapture), nextCursor: parseNextCursor(response)
        })));
    }

    createCapture(deckId: string, source: string, noteText: string, commandId: string): Observable<CaptureWriteResult> {
        return this.http.post<unknown>(`${this.baseUrl}/capture-notes`, {
            commandId: requireCommand(commandId), deckId: requireEntity(deckId),
            source: requireText(source, 2048), text: requireText(noteText, 32_768)
        }, { observe: 'response' }).pipe(map(response => parseCaptureWrite(response, commandId, deckId)));
    }

    convertCapture(note: CaptureNote, deckVersion: string, deckRevisionId: string,
                   document: NativeDocument, commandId: string): Observable<CaptureConversionAcknowledgement> {
        return this.http.post<unknown>(`${this.baseUrl}/capture-notes/${encodeURIComponent(requireEntity(note.noteId))}/conversions`, {
            commandId: requireCommand(commandId), expectedDeckVersion: requireVersion(deckVersion),
            expectedDeckRevisionId: requireEntity(deckRevisionId), document: readNativeDocument(document)
        }, { headers: ifMatch(note.rowVersion), observe: 'response' }).pipe(map(response => {
            if (response.status !== 200) throw new AuthoringProtocolError('Unexpected conversion status.');
            requirePrivate(response);
            const result = parseConversionAcknowledgement(response.body);
            if (result.commandId !== commandId || result.noteId !== note.noteId
                || result.publication.commandId !== commandId || result.publication.deckId !== note.deckId) {
                throw new AuthoringProtocolError('Conversion acknowledgement mismatch.');
            }
            const replayed = requireReplayHeader(response);
            if (replayed ? response.headers.has('ETag') : response.headers.get('ETag') !== expectedEtag(result.noteVersion)) {
                throw new AuthoringProtocolError('Invalid conversion ETag.');
            }
            return result;
        }));
    }

    private list(path: string, cursor: string | null): Observable<HttpResponse<unknown>> {
        let params = new HttpParams().set('limit', AUTHORING_PAGE_SIZE.toString());
        if (cursor !== null) params = params.set('cursor', requireCursor(cursor)!);
        return this.http.get<unknown>(`${this.baseUrl}${path}`, { params, observe: 'response' }).pipe(map(response => {
            requirePrivate(response);
            return response;
        }));
    }
}

function parseDraftSummary(value: unknown): DraftSummary {
    const object = requireObject(value, [
        'draftId', 'deckId', 'rowVersion', 'contentBytes', 'createdAt', 'acknowledgedAt', 'expiresAt',
        'memberKey', 'baseRevisionId'
    ]);
    return {
        draftId: requireEntity(object['draftId']), deckId: requireEntity(object['deckId']),
        memberKey: optionalEntity(object['memberKey']), baseRevisionId: optionalEntity(object['baseRevisionId']),
        rowVersion: requireVersion(object['rowVersion']), contentBytes: requireCount(object['contentBytes'], 1_048_576),
        createdAt: requireInstant(object['createdAt']), acknowledgedAt: requireInstant(object['acknowledgedAt']),
        expiresAt: requireInstant(object['expiresAt'])
    };
}

function parseDraftDetail(value: unknown): DraftDetail {
    const object = requireObject(value, [
        'draftId', 'deckId', 'rowVersion', 'contentBytes', 'createdAt', 'acknowledgedAt', 'expiresAt',
        'memberKey', 'baseRevisionId', 'document'
    ]);
    return { ...parseDraftSummaryWithoutDocument(object), document: readNativeDocument(object['document']) };
}

function parseDraftSummaryWithoutDocument(object: Record<string, unknown>): DraftSummary {
    const { document: _document, ...summary } = object;
    return parseDraftSummary(summary);
}

function parseCapture(value: unknown): CaptureNote {
    const object = requireObject(value, [
        'noteId', 'deckId', 'rowVersion', 'source', 'text', 'contentBytes', 'archived',
        'createdAt', 'updatedAt', 'conversion'
    ]);
    if (typeof object['archived'] !== 'boolean') throw new AuthoringProtocolError('Invalid capture note.');
    return {
        noteId: requireEntity(object['noteId']), deckId: requireEntity(object['deckId']),
        rowVersion: requireVersion(object['rowVersion']), source: requireText(object['source'], 2048),
        text: requireText(object['text'], 32_768),
        contentBytes: requireCount(object['contentBytes'], 65_536), archived: object['archived'],
        createdAt: requireInstant(object['createdAt']), updatedAt: requireInstant(object['updatedAt']),
        conversion: parseConversion(object['conversion'])
    };
}

function parseConversion(value: unknown): CaptureConversion | null {
    if (value === null) return null;
    const object = requireObject(value, ['commandId', 'memberKey', 'itemRevisionId', 'convertedAt']);
    return {
        commandId: requireCommand(object['commandId']), memberKey: requireEntity(object['memberKey']),
        itemRevisionId: requireEntity(object['itemRevisionId']), convertedAt: requireInstant(object['convertedAt'])
    };
}

function parseDraftWrite(response: HttpResponse<unknown>, status: number, commandId: string): DraftWriteResult {
    if (response.status !== status) throw new AuthoringProtocolError('Unexpected draft status.');
    requirePrivate(response);
    const object = requireObject(response.body, ['commandId', 'draft']);
    const acknowledgement: DraftAcknowledgement = {
        commandId: requireCommand(object['commandId']), draft: parseDraftDetail(object['draft'])
    };
    if (acknowledgement.commandId !== commandId) throw new AuthoringProtocolError('Draft acknowledgement mismatch.');
    const replayed = requireReplayHeader(response);
    if (replayed ? response.headers.has('ETag') : response.headers.get('ETag') !== expectedEtag(acknowledgement.draft.rowVersion)) {
        throw new AuthoringProtocolError('Invalid draft ETag.');
    }
    return { acknowledgement, replayed };
}

function parseCaptureWrite(response: HttpResponse<unknown>, commandId: string, deckId: string): CaptureWriteResult {
    if (response.status !== 201) throw new AuthoringProtocolError('Unexpected capture status.');
    requirePrivate(response);
    const object = requireObject(response.body, ['commandId', 'capture']);
    const acknowledgement: CaptureAcknowledgement = {
        commandId: requireCommand(object['commandId']), capture: parseCapture(object['capture'])
    };
    if (acknowledgement.commandId !== commandId || acknowledgement.capture.deckId !== requireEntity(deckId)) {
        throw new AuthoringProtocolError('Capture acknowledgement mismatch.');
    }
    const replayed = requireReplayHeader(response);
    if (replayed ? response.headers.has('ETag') : response.headers.get('ETag') !== expectedEtag(acknowledgement.capture.rowVersion)) {
        throw new AuthoringProtocolError('Invalid capture ETag.');
    }
    return { acknowledgement, replayed };
}

function parseConversionAcknowledgement(value: unknown): CaptureConversionAcknowledgement {
    const object = requireObject(value, ['commandId', 'noteId', 'noteVersion', 'sourcePreserved', 'publication']);
    if (object['sourcePreserved'] !== true) throw new AuthoringProtocolError('Capture source was not preserved.');
    return {
        commandId: requireCommand(object['commandId']), noteId: requireEntity(object['noteId']),
        noteVersion: requireVersion(object['noteVersion']), sourcePreserved: true,
        publication: parsePublication(object['publication'])
    };
}

function parsePublication(value: unknown): ItemAcknowledgement {
    const object = requireObject(value, ['commandId', 'deckId', 'deckRevisionId', 'deckVersion', 'memberCount', 'changes']);
    if (!Array.isArray(object['changes']) || object['changes'].length !== 1) throw new AuthoringProtocolError('Invalid conversion changes.');
    return {
        commandId: requireCommand(object['commandId']), deckId: requireEntity(object['deckId']),
        deckRevisionId: requireEntity(object['deckRevisionId']), deckVersion: requireVersion(object['deckVersion']),
        memberCount: requireCount(object['memberCount'], 100_000), changes: object['changes'].map(parseChange)
    };
}

function parseChange(value: unknown): ItemChangeResult {
    const object = requireObject(value, ['operation', 'memberKey', 'itemRevisionId', 'itemVersion', 'ordinal']);
    if (object['operation'] !== 'create') throw new AuthoringProtocolError('Conversion did not create an item.');
    return {
        operation: 'create', memberKey: requireEntity(object['memberKey']),
        itemRevisionId: requireEntity(object['itemRevisionId']), itemVersion: requireVersion(object['itemVersion']),
        ordinal: requireCount(object['ordinal'], 99_999)
    };
}

function parsePage<T>(response: HttpResponse<unknown>, parser: (value: unknown) => T): readonly T[] {
    const object = requireObject(response.body, ['items', 'nextCursor']);
    if (!Array.isArray(object['items']) || object['items'].length > AUTHORING_PAGE_SIZE) {
        throw new AuthoringProtocolError('Invalid authoring page.');
    }
    return object['items'].map(parser);
}

function parseNextCursor(response: HttpResponse<unknown>): string | null {
    return requireCursor(requireObject(response.body, ['items', 'nextCursor'])['nextCursor']);
}

function optionalEntity(value: unknown): string | null {
    return value === null ? null : requireEntity(value);
}

function requireText(value: unknown, maximumBytes: number): string {
    if (typeof value !== 'string' || value.length === 0 || new TextEncoder().encode(value).byteLength > maximumBytes) {
        throw new AuthoringProtocolError('Invalid authoring text.');
    }
    return value;
}

function ifMatch(version: string): HttpHeaders {
    return new HttpHeaders({ 'If-Match': expectedEtag(requireVersion(version)) });
}

function requirePrivate(response: HttpResponse<unknown>): void {
    const cache = (response.headers.get('Cache-Control') ?? '').toLowerCase().split(',').map(value => value.trim());
    if (!cache.includes('private') || !cache.includes('no-store')) {
        throw new AuthoringProtocolError('Private authoring response can be cached.');
    }
}

function requireEtag(response: HttpResponse<unknown>, version: string): void {
    if (response.headers.get('ETag') !== expectedEtag(version)) throw new AuthoringProtocolError('Invalid authoring ETag.');
}

function requireReplayHeader(response: HttpResponse<unknown>): boolean {
    const value = response.headers.get('Idempotency-Replayed');
    if (value !== null && value !== 'true') throw new AuthoringProtocolError('Invalid authoring replay header.');
    return value === 'true';
}
