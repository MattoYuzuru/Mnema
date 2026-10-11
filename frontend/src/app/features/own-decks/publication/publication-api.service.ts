import { HttpClient, HttpHeaders, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, defer, map } from 'rxjs';

import { appConfig } from '../../../app.config';
import { AuthoringProtocolError, requireCommand, requireEntity } from '../../authoring/authoring.models';
import { expectedEtag } from '../own-deck.models';
import {
    PublicationCommand,
    PublicationState,
    PublicationWriteResult,
    Topic,
    parsePublicationAcknowledgement,
    parsePublicationState,
    parseTopics
} from './publication.models';

/**
 * The owner's publication of a deck and the topic directory (contracts/decks/publication.json). A write carries the
 * `If-Match` of the state it was made from; a stale one, like a stale `expectedHeadRevisionId`, is a plain 412 and the
 * caller re-reads. A failure is the raw `HttpErrorResponse`: see `publicationFailureOf`.
 */
@Injectable({ providedIn: 'root' })
export class PublicationApiService {
    private readonly http = inject(HttpClient);
    private readonly baseUrl = appConfig.learningApiBaseUrl.replace(/\/$/, '');

    read(deckId: string): Observable<PublicationState> {
        return defer(() => this.http.get<unknown>(this.path(deckId), { observe: 'response' })).pipe(map(response => {
            requirePrivate(response);
            const state = parsePublicationState(response.body);
            if (state.deckId !== deckId.toLowerCase()) throw new AuthoringProtocolError('Publication deck mismatch.');
            if (response.headers.get('ETag') !== expectedEtag(state.rowVersion)) throw new AuthoringProtocolError('Publication has an invalid ETag.');
            return state;
        }));
    }

    save(deckId: string, rowVersion: string, command: PublicationCommand): Observable<PublicationWriteResult> {
        return defer(() => {
            const body = {
                commandId: requireCommand(command.commandId),
                visibility: command.visibility,
                metadata: command.metadata,
                requestsEnabled: command.requestsEnabled,
                publish: command.publish === null ? null
                    : { expectedHeadRevisionId: requireEntity(command.publish.expectedHeadRevisionId), releaseNote: command.publish.releaseNote }
            };
            return this.http.put<unknown>(this.path(deckId), body, {
                headers: new HttpHeaders({ 'If-Match': expectedEtag(rowVersion) }), observe: 'response'
            });
        }).pipe(map(response => {
            requirePrivate(response);
            const acknowledgement = parsePublicationAcknowledgement(response.body);
            if (acknowledgement.commandId !== command.commandId.toLowerCase() || acknowledgement.publication.deckId !== deckId.toLowerCase()) {
                throw new AuthoringProtocolError('Publication acknowledgement mismatch.');
            }
            const replayHeader = response.headers.get('Idempotency-Replayed');
            if (replayHeader !== null && replayHeader !== 'true') throw new AuthoringProtocolError('Invalid replay header.');
            const replayed = replayHeader === 'true';
            const etag = response.headers.get('ETag');
            if (replayed ? etag !== null : etag !== expectedEtag(acknowledgement.publication.rowVersion)) {
                throw new AuthoringProtocolError('Invalid publication ETag.');
            }
            return { acknowledgement, etag, replayed };
        }));
    }

    topics(): Observable<readonly Topic[]> {
        return defer(() => this.http.get<unknown>(`${this.baseUrl}/topics`, { observe: 'response' })).pipe(map(response => {
            const cache = (response.headers.get('Cache-Control') ?? '').toLowerCase();
            if (cache.includes('no-store') || !cache.includes('private')) throw new AuthoringProtocolError('Unexpected topic directory caching.');
            return parseTopics(response.body);
        }));
    }

    private path(deckId: string): string {
        return `${this.baseUrl}/decks/${encodeURIComponent(requireEntity(deckId))}/publication`;
    }
}

function requirePrivate(response: HttpResponse<unknown>): void {
    const cache = (response.headers.get('Cache-Control') ?? '').toLowerCase().split(',').map(value => value.trim());
    if (!cache.includes('private') || !cache.includes('no-store')) throw new AuthoringProtocolError('Private response can be cached.');
}
