import { HttpClient, HttpHeaders, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, defer, map } from 'rxjs';

import { appConfig } from '../../../app.config';
import { AuthoringProtocolError, requireCommand, requireEntity } from '../../authoring/authoring.models';
import { expectedEtag } from '../own-deck.models';
import {
    BulkDeleteReceipt,
    DeckInsights,
    DeletionPreview,
    DeletionSelection,
    ExemplarAcknowledgement,
    parseBulkDeleteResult,
    parseDeletionPreview,
    parseExemplarAcknowledgement,
    parseInsights,
    requireSelection
} from './deck-hub.models';

/** The Deck hub's own routes (#285): statistics, the «Эталон» flag and bulk deletion. All are private/no-store. */
@Injectable({ providedIn: 'root' })
export class DeckHubApiService {
    private readonly http = inject(HttpClient);
    private readonly baseUrl = appConfig.learningApiBaseUrl.replace(/\/$/, '');

    insights(deckId: string): Observable<DeckInsights> {
        return defer(() => this.http.get<unknown>(`${this.decks(deckId)}/insights`, { observe: 'response' }))
            .pipe(map(response => {
                requirePrivate(response);
                const insights = parseInsights(response.body);
                if (insights.deckId !== deckId.toLowerCase()) throw new AuthoringProtocolError('Insights deck mismatch.');
                return insights;
            }));
    }

    /**
     * Sets «Эталон» to a desired value (a set, not a toggle, so a retry or double click is harmless). The star
     * endorses the content the user saw, hence the item revision precondition; it is not a Deck revision.
     */
    setExemplar(deckId: string, memberKey: string, expectedItemRevisionId: string, exemplar: boolean,
                commandId: string): Observable<ExemplarAcknowledgement> {
        return defer(() => {
            const member = requireEntity(memberKey);
            const body = { commandId: requireCommand(commandId), expectedItemRevisionId: requireEntity(expectedItemRevisionId), exemplar };
            return this.http.post<unknown>(`${this.decks(deckId)}/items/${encodeURIComponent(member)}/exemplar`, body, { observe: 'response' });
        }).pipe(map(response => {
            requirePrivate(response);
            const acknowledgement = parseExemplarAcknowledgement(response.body);
            if (acknowledgement.commandId !== commandId || acknowledgement.deckId !== deckId.toLowerCase()
                || acknowledgement.memberKey !== memberKey.toLowerCase() || acknowledgement.exemplar !== exemplar) {
                throw new AuthoringProtocolError('Exemplar acknowledgement mismatch.');
            }
            return acknowledgement;
        }));
    }

    /** What a deletion would remove, for the hold-to-delete text. Writes nothing and is repeatable. */
    previewDeletion(deckId: string, expectedDeckRevisionId: string, selection: DeletionSelection): Observable<DeletionPreview> {
        return defer(() => {
            const body = { ...requireSelection(selection), expectedDeckRevisionId: requireEntity(expectedDeckRevisionId) };
            return this.http.post<unknown>(`${this.decks(deckId)}/items/deletions/preview`, body, { observe: 'response' });
        }).pipe(map(response => {
            requirePrivate(response);
            const preview = parseDeletionPreview(response.body);
            if (preview.deckId !== deckId.toLowerCase()) throw new AuthoringProtocolError('Preview deck mismatch.');
            return preview;
        }));
    }

    /**
     * Deletes the selection in server-side chunks of 100 (each chunk is one atomic publication). A foreign publication
     * after the first chunk yields `PARTIAL`; a stale first chunk is a plain 412 and nothing was deleted. An uncertain
     * network outcome is retried with the same `commandId`.
     */
    deleteMany(deckId: string, deckVersion: string, expectedDeckRevisionId: string, selection: DeletionSelection,
               commandId: string): Observable<BulkDeleteReceipt> {
        return defer(() => {
            const body = { commandId: requireCommand(commandId), expectedDeckRevisionId: requireEntity(expectedDeckRevisionId),
                ...requireSelection(selection) };
            return this.http.post<unknown>(`${this.decks(deckId)}/items/deletions`, body, {
                headers: new HttpHeaders({ 'If-Match': expectedEtag(deckVersion) }), observe: 'response'
            });
        }).pipe(map(response => {
            requirePrivate(response);
            const result = parseBulkDeleteResult(response.body);
            if (result.commandId !== commandId || result.deckId !== deckId.toLowerCase()) {
                throw new AuthoringProtocolError('Deletion result mismatch.');
            }
            const replayHeader = response.headers.get('Idempotency-Replayed');
            if (replayHeader !== null && replayHeader !== 'true') throw new AuthoringProtocolError('Invalid replay header.');
            const replayed = replayHeader === 'true';
            const etag = response.headers.get('ETag');
            if (replayed ? etag !== null : etag !== expectedEtag(result.deckVersion)) {
                throw new AuthoringProtocolError('Invalid deletion ETag.');
            }
            return { result, replayed };
        }));
    }

    private decks(deckId: string): string {
        return `${this.baseUrl}/decks/${encodeURIComponent(requireEntity(deckId))}`;
    }
}

function requirePrivate(response: HttpResponse<unknown>): void {
    const cache = (response.headers.get('Cache-Control') ?? '').toLowerCase().split(',').map(value => value.trim());
    if (!cache.includes('private') || !cache.includes('no-store')) throw new AuthoringProtocolError('Private response can be cached.');
}
