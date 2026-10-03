import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';

import { clone, mechanics, removed } from '../study/study-test-data';
import { AuthoringProtocolError } from './authoring.models';
import { ExerciseApiService } from './exercise-api.service';

describe('ExerciseApiService', () => {
    let api: ExerciseApiService;
    let http: HttpTestingController;
    const detail = mechanics['exerciseDetail'];
    const deckId = detail.deckId;
    const exerciseId = detail.exerciseId;
    const create = mechanics['createChoiceVideoMultiple'];
    const headers = { 'Cache-Control': 'private, no-store', ETag: '"6"' };
    const summary = {
        exerciseId, exerciseRevisionId: detail.exerciseRevisionId, exerciseVersion: '0', ordinal: 0, type: 'CHOICE',
        enabled: true, schemaVersion: 2, createdAt: detail.createdAt, updatedAt: detail.updatedAt, objective: detail.objective, isNew: false
    };
    const acknowledgement = {
        commandId: create.commandId, deckId, deckRevisionId: '33333333-3333-4333-8333-333333333333', deckVersion: '7',
        objectiveId: detail.objective.objectiveId, objectiveKey: detail.objective.objectiveKey,
        objectiveRevisionId: detail.objective.objectiveRevisionId, exerciseId,
        exerciseRevisionId: detail.exerciseRevisionId, enabled: true
    };

    beforeEach(() => {
        TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
        api = TestBed.inject(ExerciseApiService);
        http = TestBed.inject(HttpTestingController);
    });
    afterEach(() => http.verify());

    it('reads a material-scoped page whose objectives carry a title instead of an answer contract', async () => {
        const memberKey = detail.objective.memberKey;
        const page = firstValueFrom(api.list(deckId, memberKey));
        http.expectOne(`/api/decks/${deckId}/exercises?memberKey=${memberKey}&limit=20`).flush({
            deckId, deckRevisionId: detail.deckRevisionId, deckVersion: '6', total: 1, exercises: [summary], nextCursor: null
        }, { headers });
        const result = await page;
        expect(result.exercises[0].objective.title).toBe('Признаки реакции в опыте');
        expect(result.exercises[0].type).toBe('CHOICE');

        const legacy = firstValueFrom(api.list(deckId, memberKey));
        http.expectOne(`/api/decks/${deckId}/exercises?memberKey=${memberKey}&limit=20`).flush({
            deckId, deckRevisionId: detail.deckRevisionId, deckVersion: '6', total: 1,
            exercises: [{ ...summary, type: removed.listenChoice, schemaVersion: 1 }], nextCursor: null
        }, { headers });
        await expect(legacy).rejects.toThrowError(AuthoringProtocolError);
    });

    it('reads the «Новое» mark of a listed exercise: a boolean is kept, a missing or other value is refused (AI-13)', async () => {
        const memberKey = detail.objective.memberKey;
        const page = (exercises: unknown[]) => {
            const result = firstValueFrom(api.list(deckId, memberKey));
            http.expectOne(`/api/decks/${deckId}/exercises?memberKey=${memberKey}&limit=20`).flush({
                deckId, deckRevisionId: detail.deckRevisionId, deckVersion: '6', total: exercises.length, exercises, nextCursor: null }, { headers });
            return result;
        };
        expect((await page([{ ...summary, isNew: true }])).exercises[0]!.isNew).toBe(true);
        expect((await page([{ ...summary, isNew: false }])).exercises[0]!.isNew).toBe(false);
        const { isNew: _omitted, ...withoutMark } = summary;
        await expect(page([withoutMark])).rejects.toThrowError(AuthoringProtocolError);
        await expect(page([{ ...summary, isNew: 1 }])).rejects.toThrowError(AuthoringProtocolError);
    });

    it('clears the «Новое» mark with a DELETE and accepts only a 204 (AI-13)', async () => {
        const done = firstValueFrom(api.clearNewMark(deckId, exerciseId));
        const request = http.expectOne(`/api/decks/${deckId}/exercises/${exerciseId}/new-mark`);
        expect(request.request.method).toBe('DELETE');
        request.flush('', { status: 204, statusText: 'No Content' });
        await expect(done).resolves.toBeUndefined();

        const odd = firstValueFrom(api.clearNewMark(deckId, exerciseId));
        http.expectOne(`/api/decks/${deckId}/exercises/${exerciseId}/new-mark`).flush('x', { status: 200, statusText: 'OK' });
        await expect(odd).rejects.toThrowError(AuthoringProtocolError);

        const gone = firstValueFrom(api.clearNewMark(deckId, exerciseId));
        http.expectOne(`/api/decks/${deckId}/exercises/${exerciseId}/new-mark`).flush('', { status: 404, statusText: 'Not Found' });
        await expect(gone).rejects.toMatchObject({ status: 404 });
    });

    it('rejects a detail with an unknown field, a legacy projection or a foreign objective member', async () => {
        for (const mutate of [
            (value: Record<string, unknown>) => { value['bindings'] = []; },
            (value: Record<string, unknown>) => { value['prompt'] = { kind: 'CUSTOM_TEXT', text: 'x' }; delete value['content']; },
            (value: Record<string, unknown>) => { value['schemaVersion'] = 1; },
            (value: Record<string, unknown>) => {
                value['objective'] = { ...detail.objective, memberKey: '99999999-9999-4999-8999-999999999999' };
            }
        ]) {
            const broken = clone(detail);
            mutate(broken);
            const reading = firstValueFrom(api.read(deckId, exerciseId));
            http.expectOne(`/api/decks/${deckId}/exercises/${exerciseId}`).flush(broken, { headers });
            await expect(reading).rejects.toThrowError(AuthoringProtocolError);
        }
    });

    it('rejects an exercise response that can be cached or whose ETag differs from its deck version', async () => {
        const cacheable = firstValueFrom(api.read(deckId, exerciseId));
        http.expectOne(`/api/decks/${deckId}/exercises/${exerciseId}`).flush(detail, { headers: { ETag: '"6"' } });
        await expect(cacheable).rejects.toThrowError(AuthoringProtocolError);
        const stale = firstValueFrom(api.read(deckId, exerciseId));
        http.expectOne(`/api/decks/${deckId}/exercises/${exerciseId}`).flush(detail, { headers: { 'Cache-Control': 'private, no-store', ETag: '"5"' } });
        await expect(stale).rejects.toThrowError(AuthoringProtocolError);
    });

    it('writes exact preconditions and accepts replay only without an ETag', async () => {
        const result = firstValueFrom(api.update(deckId, exerciseId, '6', create.expectedDeckRevisionId, detail.exerciseRevisionId, mechanics['reuseObjective'], create.exercise, create.commandId));
        const request = http.expectOne(`/api/decks/${deckId}/exercises/${exerciseId}`);
        expect(request.request.method).toBe('PUT');
        expect(request.request.headers.get('If-Match')).toBe('"6"');
        expect(request.request.body.expectedExerciseRevisionId).toBe(detail.exerciseRevisionId);
        request.flush(acknowledgement, { headers: { 'Cache-Control': 'private, no-store', 'Idempotency-Replayed': 'true' } });
        expect((await result).replayed).toBe(true);
    });

    it('rejects a malformed replay marker and an acknowledgement for another command', async () => {
        const malformed = firstValueFrom(api.create(deckId, '6', create.expectedDeckRevisionId, create.objective, create.exercise, create.commandId));
        http.expectOne(`/api/decks/${deckId}/exercises`).flush(acknowledgement, { status: 201, statusText: 'Created', headers: { ...headers, ETag: '"7"', 'Idempotency-Replayed': 'false' } });
        await expect(malformed).rejects.toThrowError(AuthoringProtocolError);

        const other = firstValueFrom(api.create(deckId, '6', create.expectedDeckRevisionId, create.objective, create.exercise, create.commandId));
        http.expectOne(`/api/decks/${deckId}/exercises`).flush({ ...acknowledgement, commandId: '018f1d98-5c10-7abc-8abc-0123456789ff' }, { status: 201, statusText: 'Created', headers: { ...headers, ETag: '"7"' } });
        await expect(other).rejects.toThrowError(AuthoringProtocolError);
    });

    it('rejects page scope drift and deletes with an exact deck precondition', async () => {
        const page = firstValueFrom(api.list(deckId, detail.objective.memberKey));
        http.expectOne(`/api/decks/${deckId}/exercises?memberKey=${detail.objective.memberKey}&limit=20`).flush({
            deckId, deckRevisionId: detail.deckRevisionId, deckVersion: '6', total: 1,
            exercises: [{ ...summary, objective: { ...detail.objective, memberKey: '99999999-9999-4999-8999-999999999999' } }],
            nextCursor: null
        }, { headers });
        await expect(page).rejects.toThrowError(AuthoringProtocolError);

        const deletion = firstValueFrom(api.delete(deckId, exerciseId, '6'));
        const request = http.expectOne(`/api/decks/${deckId}/exercises/${exerciseId}`);
        expect(request.request.method).toBe('DELETE');
        expect(request.request.headers.get('If-Match')).toBe('"6"');
        request.flush(null, { status: 204, statusText: 'No Content', headers: { 'Cache-Control': 'private, no-store' } });
        await expect(deletion).resolves.not.toThrow();
    });
});
