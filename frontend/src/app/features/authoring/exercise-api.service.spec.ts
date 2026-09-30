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
        const answer = (await page).exercises[0]?.objective.answerContract;
        expect(answer?.schemaVersion).toBe(1);
        if (answer?.schemaVersion === 1) expect(answer.accepted).toEqual(['memory']);

        const detail = firstValueFrom(api.read(deckId, exerciseId));
        http.expectOne(`/api/decks/${deckId}/exercises/${exerciseId}`).flush({
            ...summary, deckId, deckRevisionId, deckVersion: '3',
            prompt: { kind: 'CUSTOM_TEXT', text: 'Translate' }, evaluatorPolicy: { id: 'deterministic-text', version: '1' },
            bindings: [{ bindingId: id('10'), role: 'ASSESSED', memberKey, itemRevisionId: id('11'),
                nodeIds: [id('12')], display: { kind: 'NODE_TEXT' }, ordinal: 0 }]
        }, { headers });
        expect((await detail).prompt.kind).toBe('CUSTOM_TEXT');
    });

    it('reads a custom answer projection and rejects an inconsistent node binding', async () => {
        const custom = { ...summary, deckId, deckRevisionId, deckVersion: '3',
            prompt: { kind: 'CUSTOM_TEXT', text: 'Translate' }, evaluatorPolicy: { id: 'deterministic-text', version: '1' },
            bindings: [{ bindingId: id('10'), role: 'ASSESSED', memberKey, itemRevisionId: id('11'),
                nodeIds: [], display: { kind: 'CUSTOM_TEXT', text: 'memory' }, ordinal: 0 }] };
        const detail = firstValueFrom(api.read(deckId, exerciseId));
        http.expectOne(`/api/decks/${deckId}/exercises/${exerciseId}`).flush(custom, { headers });
        expect((await detail).bindings[0].display).toEqual({ kind: 'CUSTOM_TEXT', text: 'memory' });

        const malformed = firstValueFrom(api.read(deckId, exerciseId));
        http.expectOne(`/api/decks/${deckId}/exercises/${exerciseId}`).flush({ ...custom,
            bindings: [{ ...custom.bindings[0], nodeIds: [id('12')] }] }, { headers });
        await expectAsync(malformed).toBeRejectedWithError(AuthoringProtocolError);
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

    it('reads a multiple-choice contract and rejects duplicate correct option identifiers', async () => {
        const contract = { schemaVersion: 3, selectionMode: 'MULTIPLE', correctOptionIds: [id('30'), id('31')],
            accepted: ['memory', 'attention'] } as const;
        const detail = { ...summary, type: 'SINGLE_CHOICE', deckId, deckRevisionId, deckVersion: '3',
            objective: { ...objective, answerContract: contract }, prompt: { kind: 'CUSTOM_TEXT', text: 'Choose' },
            evaluatorPolicy: { id: 'deterministic-choice', version: '1' }, bindings: [
                { bindingId: id('29'), role: 'ASSESSED', memberKey, itemRevisionId: id('11'), ordinal: 0,
                    nodeIds: [id('12')], display: { kind: 'NODE_TEXT' } },
                { bindingId: id('30'), role: 'OPTION', memberKey, itemRevisionId: id('11'), ordinal: 24,
                    nodeIds: [id('12')], display: { kind: 'NODE_TEXT' } },
                { bindingId: id('31'), role: 'OPTION', memberKey, itemRevisionId: id('11'), ordinal: 25,
                    nodeIds: [id('13')], display: { kind: 'NODE_TEXT' } }
            ] };
        const reading = firstValueFrom(api.read(deckId, exerciseId));
        http.expectOne(`/api/decks/${deckId}/exercises/${exerciseId}`).flush(detail, { headers });
        expect((await reading).objective.answerContract).toEqual(contract);
        const invalid = firstValueFrom(api.read(deckId, exerciseId));
        http.expectOne(`/api/decks/${deckId}/exercises/${exerciseId}`).flush({ ...detail,
            objective: { ...detail.objective, answerContract: { ...contract, correctOptionIds: [id('30'), id('30')] } } }, { headers });
        await expectAsync(invalid).toBeRejectedWithError(AuthoringProtocolError);
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

    it('accepts cloze blank and soft matching, then deletes with an exact deck precondition', async () => {
        const reading = firstValueFrom(api.read(deckId, exerciseId));
        http.expectOne(`/api/decks/${deckId}/exercises/${exerciseId}`).flush({
            ...summary, deckId, deckRevisionId, deckVersion: '3',
            objective: { ...objective, answerContract: { ...objective.answerContract, matchingMode: 'SOFT' } },
            prompt: { kind: 'CUSTOM_TEXT', text: 'Complete', blank: { mode: 'FIXED', length: 8 } },
            evaluatorPolicy: { id: 'deterministic-text', version: '1' },
            bindings: [{ bindingId: id('10'), role: 'ASSESSED', memberKey, itemRevisionId: id('11'),
                nodeIds: [id('12')], display: { kind: 'NODE_TEXT' }, ordinal: 0 }]
        }, { headers });
        const detail = await reading;
        expect(detail.prompt.kind === 'CUSTOM_TEXT' ? detail.prompt.blank : null).toEqual({ mode: 'FIXED', length: 8 });
        expect(detail.objective.answerContract.schemaVersion === 1 ? detail.objective.answerContract.matchingMode : null).toBe('SOFT');

        const deletion = firstValueFrom(api.delete(deckId, exerciseId, '3'));
        const request = http.expectOne(`/api/decks/${deckId}/exercises/${exerciseId}`);
        expect(request.request.method).toBe('DELETE');
        expect(request.request.headers.get('If-Match')).toBe('"3"');
        request.flush(null, { status: 204, statusText: 'No Content', headers: { 'Cache-Control': 'private, no-store' } });
        await expectAsync(deletion).toBeResolved();
    });
});
