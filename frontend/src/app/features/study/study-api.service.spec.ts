import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';

import { StudyApiService } from './study-api.service';
import { AttemptCommand, ReadyStudySession, StudyProtocolError } from './study.models';

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
        const result = firstValueFrom(api.start(deckId, commandId, { mode: 'SCHEDULED', preset: 'STANDARD' }));
        const request = http.expectOne(`/api/decks/${deckId}/study-sessions`);
        expect(request.request.body).toEqual({ commandId, mode: 'SCHEDULED',
            budget: { maxPresentations: 20, maxNewObjectives: 5 } });
        request.flush(active('TYPED'), { status: 201, statusText: 'Created', headers: {
            ...privateHeaders, Location: `/api/decks/${deckId}/study-sessions/${sessionId}`
        } });
        expect((await result).value.status).toBe('ACTIVE');
    });

    it('maps the quick preset to one bounded scheduler budget', () => {
        api.start(deckId, commandId, { mode: 'SCHEDULED', preset: 'QUICK' }).subscribe();
        const request = http.expectOne(`/api/decks/${deckId}/study-sessions`);
        expect(request.request.body).toEqual({ commandId, mode: 'SCHEDULED',
            budget: { maxPresentations: 10, maxNewObjectives: 2 } });
        request.flush(active('TYPED'), { status: 201, statusText: 'Created', headers: {
            ...privateHeaders, Location: `/api/decks/${deckId}/study-sessions/${sessionId}`
        } });
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

    it('accepts only choice options backed by the same server-issued OPTION bindings', async () => {
        const choice = active('SINGLE_CHOICE');
        const result = firstValueFrom(api.read(deckId, sessionId));
        http.expectOne(`/api/decks/${deckId}/study-sessions/${sessionId}`).flush(choice, { headers: privateHeaders });
        const parsed = await result;
        expect(parsed.status).toBe('ACTIVE');
        if (parsed.status === 'PREPARING') fail('Expected an active session.');
        else expect(parsed.presentations[0].options).toHaveSize(2);

        const original = choice.presentations[0];
        const tampered = { ...choice, presentations: [{ ...original,
            options: [original.options[0], { ...original.options[1], optionId: id('99') }] }] };
        const rejected = firstValueFrom(api.read(deckId, sessionId));
        http.expectOne(`/api/decks/${deckId}/study-sessions/${sessionId}`).flush(tampered, { headers: privateHeaders });
        await expectAsync(rejected).toBeRejectedWithError(StudyProtocolError);
    });

    it('sends server-owned replay/practice intents and validates refilled sessions', async () => {
        const replayResult = firstValueFrom(api.start(deckId, commandId,
            { mode: 'REPLAY', sourceSessionId: sessionId }));
        const replay = http.expectOne(`/api/decks/${deckId}/study-sessions`);
        expect(replay.request.body).toEqual({ commandId, mode: 'REPLAY', sourceSessionId: sessionId,
            budget: { maxPresentations: 20 } });
        replay.flush({ ...active('TYPED'), mode: 'REPLAY' }, { status: 201, statusText: 'Created', headers: {
            ...privateHeaders, Location: `/api/decks/${deckId}/study-sessions/${sessionId}`
        } });
        expect((await replayResult).value.mode).toBe('REPLAY');

        const practiceResult = firstValueFrom(api.start(deckId, commandId,
            { mode: 'PRACTICE', includeNew: false, order: 'WEAKEST_FIRST' }));
        const practice = http.expectOne(`/api/decks/${deckId}/study-sessions`);
        expect(practice.request.body).toEqual({ commandId, mode: 'PRACTICE', includeNew: false,
            order: 'WEAKEST_FIRST', budget: { maxPresentations: 20 } });
        practice.flush({ ...active('TYPED'), mode: 'PRACTICE' }, { status: 201, statusText: 'Created', headers: {
            ...privateHeaders, Location: `/api/decks/${deckId}/study-sessions/${sessionId}`
        } });
        expect((await practiceResult).value.mode).toBe('PRACTICE');

        const refillResult = firstValueFrom(api.refill(deckId, sessionId));
        const refill = http.expectOne(`/api/decks/${deckId}/study-sessions/${sessionId}/presentations`);
        expect(refill.request.method).toBe('POST');
        refill.flush(active('TYPED'), { headers: privateHeaders });
        expect((await refillResult).status).toBe('ACTIVE');
    });

    it('validates explainable progress, replay sources and restart acknowledgements', async () => {
        const progressResult = firstValueFrom(api.progress(deckId));
        http.expectOne(`/api/decks/${deckId}/study-progress?limit=100`).flush({
            asOf: '2026-09-20T10:00:00Z', items: [{ memberKey: id('20'), itemRevisionId: id('21'),
                state: 'DUE', objectiveCoverage: { enabled: 2, introduced: 1, assessed: 1 },
                lastAssessedAt: '2026-09-19T10:00:00Z', nextDue: '2026-09-20T09:00:00Z' }], nextCursor: null
        }, { headers: privateHeaders });
        expect((await progressResult).items[0].state).toBe('DUE');

        const sourcesResult = firstValueFrom(api.replaySources(deckId));
        http.expectOne(`/api/decks/${deckId}/study-sessions/replay-sources`).flush({
            asOf: '2026-09-20T10:00:00Z', localStudyDate: '2026-09-20',
            items: [{ sessionId, completedAt: '2026-09-20T09:00:00Z', presentationCount: 4 }]
        }, { headers: privateHeaders });
        expect((await sourcesResult).items[0].presentationCount).toBe(4);

        const restartResult = firstValueFrom(api.restart(deckId, commandId, [id('20')]));
        const restart = http.expectOne(`/api/decks/${deckId}/study-restarts`);
        expect(restart.request.body).toEqual({ commandId, memberKeys: [id('20')] });
        restart.flush({ commandId, restartedAt: '2026-09-20T10:00:00Z', objectiveCount: 1,
            learningEpochs: [{ objectiveId: id('22'), learningEpoch: '1' }] }, { headers: privateHeaders });
        expect((await restartResult).value.objectiveCount).toBe(1);
    });

    function active(type: 'TYPED' | 'SELF_CHECK' | 'CLOZE_SINGLE' | 'SINGLE_CHOICE'): ReadyStudySession {
        const assessed = { bindingId: id('11'), role: 'ASSESSED' as const, memberKey: id('12'),
            itemRevisionId: id('13'), ordinal: 0, nodeIds: [id('14')], display: { kind: 'NODE_TEXT' } };
        const options = type === 'SINGLE_CHOICE'
            ? [{ optionId: id('15'), text: 'memory' }, { optionId: id('16'), text: 'forgetting' }] : [];
        const bindings = type === 'SINGLE_CHOICE' ? [assessed,
            { ...assessed, bindingId: id('15'), role: 'OPTION' as const, ordinal: 1 },
            { ...assessed, bindingId: id('16'), role: 'OPTION' as const, ordinal: 2, nodeIds: [id('17')] }
        ] : [assessed];
        return { sessionId, deckId, mode: 'SCHEDULED', status: 'ACTIVE', timezone: 'Europe/Moscow',
            localStudyDate: '2026-09-20', deckRevisionId: id('5'), exerciseGenerationId: id('6'),
            selectionPolicyVersion: 'deck-due-new-v2', budget: { maxPresentations: 20, maxNewObjectives: 5 },
            issuedCount: 1, reducer: { id: 'mnema-baseline', version: '1',
                configId: id('9'), configHash: `sha256:${'a'.repeat(64)}` }, seed: '42', nextCursor: null,
            expiresAt: '2026-09-21T10:00:00Z', presentations: [{ presentationId, nonce: 'abcdefghijklmnop', ordinal: 0,
                exerciseRevisionId: id('10'), type, objectiveId: id('7'), objectiveRevisionId: id('8'), learningEpoch: '0',
                reference: 'memory', prompt: { kind: 'TEXT', text: 'What remains?' }, options, bindings,
                evaluator: { id: type === 'SELF_CHECK' ? 'self-check'
                    : type === 'SINGLE_CHOICE' ? 'deterministic-choice' : 'deterministic-text', version: '1' } }] };
    }
});
