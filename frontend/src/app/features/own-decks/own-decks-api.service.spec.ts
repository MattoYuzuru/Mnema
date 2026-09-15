import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';

import metadataFixture from '../../../../../contracts/decks/metadata.json';
import { DeckCommand, OwnDeck, OwnDeckProtocolError } from './own-deck.models';
import { OwnDecksApiService } from './own-decks-api.service';

describe('OwnDecksApiService', () => {
    let api: OwnDecksApiService;
    let http: HttpTestingController;
    const deck = metadataFixture.detail as unknown as OwnDeck;
    const command = metadataFixture.command as unknown as DeckCommand;
    const acknowledgement = { commandId: command.commandId, deck };
    const privateHeaders = { 'Cache-Control': 'private, no-store' };

    beforeEach(() => {
        TestBed.configureTestingModule({
            providers: [provideHttpClient(), provideHttpClientTesting()]
        });
        api = TestBed.inject(OwnDecksApiService);
        http = TestBed.inject(HttpTestingController);
    });

    afterEach(() => http.verify());

    it('reads one bounded canonical page and preserves the opaque cursor', async () => {
        const result = firstValueFrom(api.list('opaque-cursor'));
        const request = http.expectOne(req => req.url === '/api/decks'
            && req.params.get('limit') === '20' && req.params.get('cursor') === 'opaque-cursor');
        expect(request.request.method).toBe('GET');
        request.flush({ items: [deck], nextCursor: 'next-opaque' }, { headers: privateHeaders });

        expect(await result).toEqual({ items: [deck], nextCursor: 'next-opaque' });
    });

    it('requires the exact strong detail ETag and fixture shape', async () => {
        const result = firstValueFrom(api.detail(deck.deckId));
        const request = http.expectOne(`/api/decks/${deck.deckId}`);
        request.flush(deck, { headers: { ...privateHeaders, ETag: '"0"' } });
        expect(await result).toEqual(deck);

        const invalid = firstValueFrom(api.detail(deck.deckId));
        http.expectOne(`/api/decks/${deck.deckId}`).flush({ ...deck, rowVersion: '01' }, {
            headers: { ...privateHeaders, ETag: '"01"' }
        });
        await expectAsync(invalid).toBeRejectedWithError(OwnDeckProtocolError);
    });

    it('accepts and normalizes canonical uppercase route identities', async () => {
        const result = firstValueFrom(api.detail(deck.deckId.toUpperCase()));
        const request = http.expectOne(`/api/decks/${deck.deckId}`);
        request.flush(deck, { headers: { ...privateHeaders, ETag: '"0"' } });

        expect(await result).toEqual(deck);
    });

    it('sends the exact create command and validates fresh acknowledgement headers', async () => {
        const result = firstValueFrom(api.create(command));
        const request = http.expectOne('/api/decks');
        expect(request.request.method).toBe('POST');
        expect(request.request.body).toEqual(command);
        request.flush(acknowledgement, {
            status: 201,
            statusText: 'Created',
            headers: {
                ...privateHeaders,
                ETag: '"0"',
                Location: `/api/decks/${deck.deckId}`
            }
        });
        expect(await result).toEqual({
            acknowledgement, etag: '"0"', replayed: false, location: `/api/decks/${deck.deckId}`
        });
    });

    it('sends exact If-Match and treats replay acknowledgement as non-current', async () => {
        const result = firstValueFrom(api.save(deck.deckId, deck.rowVersion, command));
        const request = http.expectOne(`/api/decks/${deck.deckId}`);
        expect(request.request.method).toBe('PATCH');
        expect(request.request.headers.get('If-Match')).toBe('"0"');
        request.flush(acknowledgement, {
            headers: { ...privateHeaders, 'Idempotency-Replayed': 'true' }
        });
        expect(await result).toEqual({ acknowledgement, etag: null, replayed: true, location: null });
    });

    it('rejects replay carrying ETag and unexpected response fields', async () => {
        const replay = firstValueFrom(api.create(command));
        http.expectOne('/api/decks').flush(acknowledgement, {
            status: 201,
            statusText: 'Created',
            headers: {
                ...privateHeaders,
                ETag: '"0"',
                Location: `/api/decks/${deck.deckId}`,
                'Idempotency-Replayed': 'true'
            }
        });
        await expectAsync(replay).toBeRejectedWithError(OwnDeckProtocolError);

        const page = firstValueFrom(api.list());
        http.expectOne(req => req.urlWithParams === '/api/decks?limit=20').flush({
            items: [], nextCursor: null, privatePayload: true
        }, { headers: privateHeaders });
        await expectAsync(page).toBeRejectedWithError(OwnDeckProtocolError);
    });
});
