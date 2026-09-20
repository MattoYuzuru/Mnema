import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';

import { StudyApiService } from './study-api.service';
import { AttemptCommand, StudyProtocolError } from './study.models';

describe('StudyApiService', () => {
    const id = (suffix: string) => `00000000-0000-4000-8000-${suffix.padStart(12, '0')}`;
    const deckId = id('1');
    const sessionId = id('2');
    const presentationId = id('3');
    const commandId = id('4');
    const privateHeaders = { 'Cache-Control': 'private, no-store' };
    let api: StudyApiService;
    let http: HttpTestingController;

    beforeEach(() => {
        TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
        api = TestBed.inject(StudyApiService);
        http = TestBed.inject(HttpTestingController);
    });
    afterEach(() => http.verify());

    it('starts only a bounded scheduled session and validates its deck-scoped snapshot', async () => {
        const result = firstValueFrom(api.start(deckId, commandId));
        const request = http.expectOne(`/api/decks/${deckId}/study-sessions`);
        expect(request.request.body).toEqual({ commandId, mode: 'SCHEDULED', budget: { maxPresentations: 20 } });
        request.flush(active('TYPED'), { status: 201, statusText: 'Created', headers: {
            ...privateHeaders, Location: `/api/decks/${deckId}/study-sessions/${sessionId}`
        } });
        expect((await result).value.status).toBe('ACTIVE');
    });

    it('submits an exact attempt and accepts a truthful deterministic feedback receipt', async () => {
        const command: AttemptCommand = { attemptId: commandId, presentationId, nonce: 'abcdefghijklmnop',
            response: { kind: 'TEXT', text: ' Memory ' }, hintsUsed: [], confidence: null, durationMs: 1000 };
        const result = firstValueFrom(api.submit(deckId, sessionId, command));
        const request = http.expectOne(`/api/decks/${deckId}/study-sessions/${sessionId}/attempts`);
        expect(request.request.body).toEqual(command);
        request.flush({ attemptId: commandId, presentationId, mode: 'SCHEDULED', status: 'ASSESSED',
            evidence: { objectiveId: id('7'), objectiveRevisionId: id('8'), result: 'CORRECT', evidenceClass: 'HIGH',
                reasonCodes: ['UNHINTED', 'DETERMINISTIC'] }, feedback: { result: 'CORRECT', reference: 'memory',
                appliedRules: ['TRIM', 'CASE_FOLD'] }, transition: { learningEpoch: '0', sequence: '1', beforeLevel: 0,
                afterLevel: 2, acceptedAt: '2026-09-20T10:00:00Z', nextDue: '2026-09-21T10:00:00Z',
                reducerId: 'mnema-baseline', reducerVersion: '1', configId: id('9'),
                configHash: `sha256:${'a'.repeat(64)}` } }, { headers: privateHeaders });
        expect((await result).value.feedback.appliedRules).toEqual(['TRIM', 'CASE_FOLD']);
    });

    it('fails closed on another deck, cacheable data, or an invalid replay marker', async () => {
        const mismatch = firstValueFrom(api.read(deckId, sessionId));
        http.expectOne(`/api/decks/${deckId}/study-sessions/${sessionId}`).flush({ ...active('TYPED'), deckId: id('99') },
            { headers: privateHeaders });
        await expectAsync(mismatch).toBeRejectedWithError(StudyProtocolError);

        const cacheable = firstValueFrom(api.start(deckId, commandId));
        http.expectOne(`/api/decks/${deckId}/study-sessions`).flush(active('TYPED'), { status: 201, statusText: 'Created',
            headers: { Location: `/api/decks/${deckId}/study-sessions/${sessionId}` } });
        await expectAsync(cacheable).toBeRejectedWithError(StudyProtocolError);
    });

    function active(type: 'TYPED' | 'SELF_CHECK') {
        return { sessionId, deckId, mode: 'SCHEDULED', status: 'ACTIVE', timezone: 'Europe/Moscow',
            localStudyDate: '2026-09-20', deckRevisionId: id('5'), exerciseGenerationId: id('6'),
            selectionPolicyVersion: 'deck-due-new-v1', reducer: { id: 'mnema-baseline', version: '1',
                configId: id('9'), configHash: `sha256:${'a'.repeat(64)}` }, seed: '42', nextCursor: null,
            expiresAt: '2026-09-21T10:00:00Z', presentations: [{ presentationId, nonce: 'abcdefghijklmnop', ordinal: 0,
                exerciseRevisionId: id('10'), type, objectiveId: id('7'), objectiveRevisionId: id('8'), learningEpoch: '0',
                reference: 'memory', prompt: { kind: 'TEXT', text: 'What remains?' }, options: [], bindings: [{
                    bindingId: id('11'), role: 'ASSESSED', memberKey: id('12'), itemRevisionId: id('13'), ordinal: 0,
                    nodeIds: [id('14')], display: { kind: 'NODE_TEXT' }
                }], evaluator: { id: type === 'TYPED' ? 'deterministic-text' : 'self-check', version: '1' } }] };
    }
});
