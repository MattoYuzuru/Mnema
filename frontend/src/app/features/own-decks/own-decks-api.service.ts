import { HttpClient, HttpHeaders, HttpParams, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, defer, map } from 'rxjs';

import { appConfig } from '../../app.config';
import {
    DeckAcknowledgement,
    DeckCommand,
    DeckMetadata,
    DeckWriteResult,
    OwnDeck,
    OwnDeckPage,
    OwnDeckProtocolError,
    OWN_DECK_PAGE_SIZE,
    expectedEtag,
    isCanonicalCommandId,
    isCanonicalEntityId,
    isCanonicalVersion,
    validateDeckMetadata
} from './own-deck.models';

@Injectable({ providedIn: 'root' })
export class OwnDecksApiService {
    private readonly http = inject(HttpClient);
    private readonly baseUrl = appConfig.learningApiBaseUrl.replace(/\/$/, '');

    list(cursor: string | null = null): Observable<OwnDeckPage> {
        return defer(() => {
            let params = new HttpParams().set('limit', OWN_DECK_PAGE_SIZE.toString());
            if (cursor !== null) {
                if (cursor.length === 0 || cursor.length > 4096) throw new OwnDeckProtocolError('Invalid deck cursor.');
                params = params.set('cursor', cursor);
            }
            return this.http.get<unknown>(`${this.baseUrl}/decks`, { params, observe: 'response' });
        }).pipe(map(response => {
            requireStatus(response, 200);
            requirePrivateNoStore(response.headers);
            return parseDeckPage(response.body);
        }));
    }

    detail(deckId: string): Observable<OwnDeck> {
        return defer(() => {
            const normalizedDeckId = requireEntityId(deckId);
            return this.http.get<unknown>(`${this.baseUrl}/decks/${encodeURIComponent(normalizedDeckId)}`, { observe: 'response' });
        }).pipe(map(response => {
            requireStatus(response, 200);
            requirePrivateNoStore(response.headers);
            const deck = parseOwnDeck(response.body);
            if (deck.deckId !== deckId.toLowerCase()) throw new OwnDeckProtocolError('Deck response identity does not match the request.');
            requireFreshEtag(response.headers, deck.rowVersion);
            return deck;
        }));
    }

    create(command: DeckCommand): Observable<DeckWriteResult> {
        return defer(() => {
            requireCommand(command);
            return this.http.post<unknown>(`${this.baseUrl}/decks`, command, { observe: 'response' });
        }).pipe(map(response => this.parseWrite(response, command, 201, true)));
    }

    save(deckId: string, rowVersion: string, command: DeckCommand): Observable<DeckWriteResult> {
        return defer(() => {
            const normalizedDeckId = requireEntityId(deckId);
            requireCommand(command);
            const ifMatch = expectedEtag(rowVersion);
            return this.http.patch<unknown>(`${this.baseUrl}/decks/${encodeURIComponent(normalizedDeckId)}`, command, {
                headers: new HttpHeaders({ 'If-Match': ifMatch }),
                observe: 'response'
            });
        }).pipe(map(response => {
            const result = this.parseWrite(response, command, 200, false);
            if (result.acknowledgement.deck.deckId !== deckId.toLowerCase()) {
                throw new OwnDeckProtocolError('Saved deck identity does not match the request.');
            }
            return result;
        }));
    }

    private parseWrite(
        response: HttpResponse<unknown>,
        command: DeckCommand,
        expectedStatus: number,
        requireLocation: boolean
    ): DeckWriteResult {
        requireStatus(response, expectedStatus);
        requirePrivateNoStore(response.headers);
        const acknowledgement = parseAcknowledgement(response.body);
        if (acknowledgement.commandId !== command.commandId.toLowerCase()) {
            throw new OwnDeckProtocolError('Acknowledgement command ID does not match the request.');
        }
        const replayHeader = response.headers.get('Idempotency-Replayed');
        if (replayHeader !== null && replayHeader !== 'true') {
            throw new OwnDeckProtocolError('Invalid idempotency replay header.');
        }
        const replayed = replayHeader === 'true';
        const etag = response.headers.get('ETag');
        if (replayed) {
            if (etag !== null) throw new OwnDeckProtocolError('A replay acknowledgement must not carry a current ETag.');
        } else if (etag !== expectedEtag(acknowledgement.deck.rowVersion)) {
            throw new OwnDeckProtocolError('Fresh acknowledgement is missing its exact ETag.');
        }
        const location = response.headers.get('Location');
        if (requireLocation) requireDeckLocation(location, acknowledgement.deck.deckId, this.baseUrl);
        else if (location !== null) throw new OwnDeckProtocolError('Metadata acknowledgement must not change location.');
        return { acknowledgement, etag, replayed, location };
    }
}

function parseDeckPage(value: unknown): OwnDeckPage {
    const object = requireObject(value, ['items', 'nextCursor']);
    if (!Array.isArray(object['items']) || object['items'].length > OWN_DECK_PAGE_SIZE) {
        throw new OwnDeckProtocolError('Invalid deck page items.');
    }
    const items = object['items'].map(parseOwnDeck);
    const ids = new Set(items.map(item => item.deckId));
    if (ids.size !== items.length) throw new OwnDeckProtocolError('Deck page contains duplicate identities.');
    const nextCursor = object['nextCursor'];
    if (nextCursor !== null && (typeof nextCursor !== 'string' || nextCursor.length === 0 || nextCursor.length > 4096)) {
        throw new OwnDeckProtocolError('Invalid next deck cursor.');
    }
    return { items, nextCursor };
}

function parseAcknowledgement(value: unknown): DeckAcknowledgement {
    const object = requireObject(value, ['commandId', 'deck']);
    const commandId = requireString(object['commandId'], 'commandId');
    if (!isCanonicalCommandId(commandId)) throw new OwnDeckProtocolError('Invalid acknowledgement command ID.');
    return { commandId: commandId.toLowerCase(), deck: parseOwnDeck(object['deck']) };
}

function parseOwnDeck(value: unknown): OwnDeck {
    const object = requireObject(value, [
        'deckId', 'revisionId', 'rowVersion', 'sequence', 'metadata', 'visibility',
        'createdAt', 'updatedAt', 'memberCount', 'exerciseCount'
    ]);
    const deckId = requireString(object['deckId'], 'deckId');
    const revisionId = requireString(object['revisionId'], 'revisionId');
    const rowVersion = requireString(object['rowVersion'], 'rowVersion');
    const sequence = requireString(object['sequence'], 'sequence');
    if (!isCanonicalEntityId(deckId) || !isCanonicalEntityId(revisionId)) {
        throw new OwnDeckProtocolError('Invalid deck identity.');
    }
    if (!isCanonicalVersion(rowVersion) || !isCanonicalVersion(sequence) || sequence !== rowVersion) {
        throw new OwnDeckProtocolError('Invalid deck version.');
    }
    const metadataObject = requireObject(object['metadata'], ['title', 'description']);
    const metadata: DeckMetadata = {
        title: requireString(metadataObject['title'], 'title'),
        description: requireString(metadataObject['description'], 'description')
    };
    if (!validateDeckMetadata(metadata).valid) throw new OwnDeckProtocolError('Invalid deck metadata.');
    if (object['visibility'] !== 'private') throw new OwnDeckProtocolError('Invalid deck visibility.');
    const createdAt = requireInstant(object['createdAt'], 'createdAt');
    const updatedAt = requireInstant(object['updatedAt'], 'updatedAt');
    const memberCount = requireCount(object['memberCount'], 'memberCount');
    const exerciseCount = requireCount(object['exerciseCount'], 'exerciseCount');
    return {
        deckId: deckId.toLowerCase(), revisionId: revisionId.toLowerCase(),
        rowVersion, sequence, metadata, visibility: 'private',
        createdAt, updatedAt, memberCount, exerciseCount
    };
}

function requireCommand(command: DeckCommand): void {
    if (!isCanonicalCommandId(command.commandId) || !validateDeckMetadata(command.metadata).valid) {
        throw new OwnDeckProtocolError('Invalid deck command.');
    }
}

function requireStatus(response: HttpResponse<unknown>, expected: number): void {
    if (response.status !== expected) throw new OwnDeckProtocolError(`Unexpected successful status ${response.status}.`);
}

function requirePrivateNoStore(headers: HttpHeaders): void {
    const directives = (headers.get('Cache-Control') ?? '').toLowerCase().split(',').map(value => value.trim());
    if (!directives.includes('private') || !directives.includes('no-store')) {
        throw new OwnDeckProtocolError('Private deck response is missing private/no-store cache controls.');
    }
}

function requireFreshEtag(headers: HttpHeaders, rowVersion: string): void {
    if (headers.get('ETag') !== expectedEtag(rowVersion)) throw new OwnDeckProtocolError('Deck response has an invalid ETag.');
}

function requireDeckLocation(location: string | null, deckId: string, baseUrl: string): void {
    if (location === null) throw new OwnDeckProtocolError('Create acknowledgement is missing Location.');
    const base = new URL(baseUrl, window.location.origin);
    const expectedPath = `${base.pathname.replace(/\/$/, '')}/decks/${deckId}`;
    if (location !== expectedPath) {
        throw new OwnDeckProtocolError('Create acknowledgement has an invalid Location.');
    }
}

function requireEntityId(value: string): string {
    if (!isCanonicalEntityId(value)) throw new OwnDeckProtocolError('Invalid deck ID.');
    return value.toLowerCase();
}

function requireObject(value: unknown, exactKeys: readonly string[]): Record<string, unknown> {
    if (value === null || typeof value !== 'object' || Array.isArray(value)) {
        throw new OwnDeckProtocolError('Expected an object response.');
    }
    const object = value as Record<string, unknown>;
    const keys = Object.keys(object);
    if (keys.length !== exactKeys.length || keys.some(key => !exactKeys.includes(key))) {
        throw new OwnDeckProtocolError('Response object has an unexpected shape.');
    }
    return object;
}

function requireString(value: unknown, field: string): string {
    if (typeof value !== 'string') throw new OwnDeckProtocolError(`Invalid ${field}.`);
    return value;
}

function requireInstant(value: unknown, field: string): string {
    const instant = requireString(value, field);
    if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/.test(instant)
        || !Number.isFinite(Date.parse(instant))) {
        throw new OwnDeckProtocolError(`Invalid ${field}.`);
    }
    return instant;
}

function requireCount(value: unknown, field: string): number {
    if (!Number.isSafeInteger(value) || (value as number) < 0) throw new OwnDeckProtocolError(`Invalid ${field}.`);
    return value as number;
}
