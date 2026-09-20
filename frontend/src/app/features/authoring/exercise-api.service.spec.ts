import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';

import { AuthoringProtocolError } from './authoring.models';
import { ExerciseApiService } from './exercise-api.service';

describe('ExerciseApiService', () => {
    let api: ExerciseApiService;
    let http: HttpTestingController;
    const id = (suffix: string) => `00000000-0000-4000-8000-${suffix.padStart(12, '0')}`;
    const deckId = id('1');
    const deckRevisionId = id('2');
    const memberKey = id('3');
    const exerciseId = id('4');
    const exerciseRevisionId = id('5');
    const objectiveId = id('6');
    const objectiveRevisionId = id('7');
    const commandId = id('8');
    const headers = { 'Cache-Control': 'private, no-store', ETag: '"3"' };
    const objective = {
        objectiveId, objectiveKey: id('9'), objectiveRevisionId, objectiveVersion: '0', memberKey,
        answerContract: { schemaVersion: 1, normalization: ['UNICODE_NFC', 'TRIM'], accepted: ['memory'] }
    };
    const summary = {
        exerciseId, exerciseRevisionId, exerciseVersion: '0', ordinal: 0, type: 'TYPED', enabled: true,
        schemaVersion: 1, createdAt: '2026-09-20T10:00:00Z', updatedAt: '2026-09-20T10:00:00Z', objective
    };

    beforeEach(() => {
        TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
        api = TestBed.inject(ExerciseApiService);
        http = TestBed.inject(HttpTestingController);
    });
    afterEach(() => http.verify());

    it('reads a material-scoped page and a strict private detail', async () => {
        const page = firstValueFrom(api.list(deckId, memberKey));
        http.expectOne(`/api/decks/${deckId}/exercises?memberKey=${memberKey}&limit=20`).flush({
            deckId, deckRevisionId, deckVersion: '3', total: 1, exercises: [summary], nextCursor: null
        }, { headers });
        expect((await page).exercises[0]?.objective.answerContract.accepted).toEqual(['memory']);

        const detail = firstValueFrom(api.read(deckId, exerciseId));
        http.expectOne(`/api/decks/${deckId}/exercises/${exerciseId}`).flush({
            ...summary, deckId, deckRevisionId, deckVersion: '3',
            prompt: { kind: 'CUSTOM_TEXT', text: 'Translate' }, evaluatorPolicy: { id: 'deterministic-text', version: '1' },
            bindings: [{ bindingId: id('10'), role: 'ASSESSED', memberKey, itemRevisionId: id('11'),
                nodeIds: [id('12')], display: { kind: 'NODE_TEXT' }, ordinal: 0 }]
        }, { headers });
        expect((await detail).prompt.kind).toBe('CUSTOM_TEXT');
    });

    it('writes exact preconditions and accepts replay only without ETag', async () => {
        const result = firstValueFrom(api.update(deckId, exerciseId, '3', deckRevisionId, exerciseRevisionId,
            { operation: 'reuse', objectiveId, objectiveRevisionId }, { type: 'TYPED' }, commandId));
        const request = http.expectOne(`/api/decks/${deckId}/exercises/${exerciseId}`);
        expect(request.request.method).toBe('PUT');
        expect(request.request.headers.get('If-Match')).toBe('"3"');
        expect(request.request.body.expectedExerciseRevisionId).toBe(exerciseRevisionId);
        request.flush({ commandId, deckId, deckRevisionId, deckVersion: '3', objectiveId,
            objectiveKey: id('9'), objectiveRevisionId, exerciseId, exerciseRevisionId, enabled: true }, {
            headers: { 'Cache-Control': 'private, no-store', 'Idempotency-Replayed': 'true' }
        });
        expect((await result).replayed).toBeTrue();
    });

    it('rejects malformed replay acknowledgement headers', async () => {
        const result = firstValueFrom(api.update(deckId, exerciseId, '3', deckRevisionId, exerciseRevisionId,
            { operation: 'reuse', objectiveId, objectiveRevisionId }, { type: 'TYPED' }, commandId));
        http.expectOne(`/api/decks/${deckId}/exercises/${exerciseId}`).flush({
            commandId, deckId, deckRevisionId, deckVersion: '3', objectiveId,
            objectiveKey: id('9'), objectiveRevisionId, exerciseId, exerciseRevisionId, enabled: true
        }, { headers: { ...headers, 'Idempotency-Replayed': 'false' } });
        await expectAsync(result).toBeRejectedWithError(AuthoringProtocolError);
    });

    it('rejects page scope drift and cacheable private data', async () => {
        const page = firstValueFrom(api.list(deckId, memberKey));
        http.expectOne(`/api/decks/${deckId}/exercises?memberKey=${memberKey}&limit=20`).flush({
            deckId, deckRevisionId, deckVersion: '3', total: 1,
            exercises: [{ ...summary, objective: { ...objective, memberKey: id('99') } }], nextCursor: null
        }, { headers: { ETag: '"3"' } });
        await expectAsync(page).toBeRejectedWithError(AuthoringProtocolError);
    });
});
