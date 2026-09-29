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

    it('accepts a cloze session whose assessed binding uses server-issued custom text', async () => {
        const value = active('CLOZE_SINGLE');
        const presentation = value.presentations[0];
        const custom = { ...value, presentations: [{ ...presentation, bindings: [
            { ...presentation.bindings[0], nodeIds: [], display: { kind: 'CUSTOM_TEXT', text: 'Париж' } }
        ], prompt: { kind: 'TEXT', text: 'Столица Франции — _____.',
            blank: { mode: 'FIXED', length: 6 } } }] };
        const result = firstValueFrom(api.start(deckId, commandId));
        http.expectOne(`/api/decks/${deckId}/study-sessions`).flush(custom, { status: 201, statusText: 'Created',
            headers: { ...privateHeaders, Location: `/api/decks/${deckId}/study-sessions/${sessionId}` } });
        const session = (await result).value;
        if (session.status === 'PREPARING') fail('Expected an active session.');
        else expect(session.presentations[0].bindings[0].display).toEqual({ kind: 'CUSTOM_TEXT', text: 'Париж' });

        const resumed = firstValueFrom(api.read(deckId, sessionId));
        http.expectOne(`/api/decks/${deckId}/study-sessions/${sessionId}`).flush(custom,
            { headers: privateHeaders });
        expect((await resumed).status).toBe('ACTIVE');

        const malformed = { ...custom, presentations: [{ ...custom.presentations[0], bindings: [
            { ...custom.presentations[0].bindings[0], display: { kind: 'CUSTOM_TEXT' } }
        ] }] };
        const rejected = firstValueFrom(api.read(deckId, sessionId));
        http.expectOne(`/api/decks/${deckId}/study-sessions/${sessionId}`).flush(malformed,
            { headers: privateHeaders });
        await expectAsync(rejected).toBeRejectedWithError(StudyProtocolError);
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

    it('keeps listening transcripts hidden until the explicit accommodation response', async () => {
        const pending = active('TYPED');
        const presentation = pending.presentations[0];
        const listening = { ...pending, presentations: [{ ...presentation, type: 'LISTEN_TYPE', reference: null,
            bindings: [],
            prompt: { kind: 'AUDIO_ASSET', assetId: id('30'), title: 'Example', instruction: 'Listen',
                transcriptAvailable: true, transcriptRevealed: false } }] };
        const result = firstValueFrom(api.read(deckId, sessionId));
        http.expectOne(`/api/decks/${deckId}/study-sessions/${sessionId}`).flush(listening,
            { headers: privateHeaders });
        const parsed = await result;
        expect(parsed.status).toBe('ACTIVE');
        if (parsed.status !== 'PREPARING') expect(parsed.presentations[0].prompt).not.toEqual(jasmine.objectContaining({
            transcript: jasmine.any(String) }));

        const revealed = firstValueFrom(api.revealTranscript(deckId, sessionId, presentationId, 'abcdefghijklmnop'));
        const request = http.expectOne(`/api/decks/${deckId}/study-sessions/${sessionId}`
            + `/presentations/${presentationId}/transcript`);
        expect(request.request.body).toEqual({ nonce: 'abcdefghijklmnop' });
        request.flush({ presentationId, prompt: { kind: 'AUDIO_ASSET', assetId: id('30'), title: 'Example',
            instruction: 'Listen', transcriptAvailable: true, transcriptRevealed: true, transcript: 'memory' } },
        { headers: privateHeaders });
        expect((await revealed).kind).toBe('AUDIO_ASSET');
    });

    it('accepts a bounded matching map and pair-specific feedback', async () => {
        const command: AttemptCommand = { attemptId: commandId, presentationId, nonce: 'abcdefghijklmnop',
            response: { kind: 'MATCH', pairs: [{ cueId: id('30'), optionId: id('15') },
                { cueId: id('31'), optionId: id('16') }] }, hintsUsed: [], confidence: null, durationMs: 1000 };
        const result = firstValueFrom(api.submit(deckId, sessionId, command));
        http.expectOne(`/api/decks/${deckId}/study-sessions/${sessionId}/attempts`).flush({
            attemptId: commandId, presentationId, mode: 'REPLAY', status: 'ASSESSED', canonicalEffects: false,
            evidence: null, transition: null,
            feedback: { result: 'CORRECT', appliedRules: ['SERVER_ISSUED_PAIR_MAP'], pairResults: [
                { cueId: id('30'), selectedOptionId: id('15'), correctOptionId: id('15'), correct: true },
                { cueId: id('31'), selectedOptionId: id('16'), correctOptionId: id('16'), correct: true }
            ] }
        }, { headers: privateHeaders });
        expect((await result).value.feedback.pairResults).toHaveSize(2);
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

    it('keeps the server-projected cloze length without exposing an answer contract', async () => {
        const value = active('CLOZE_SINGLE');
        const reading = firstValueFrom(api.read(deckId, sessionId));
        http.expectOne(`/api/decks/${deckId}/study-sessions/${sessionId}`).flush({
            ...value, presentations: value.presentations.map(presentation => ({
                ...presentation, prompt: { kind: 'TEXT', text: 'What remains?',
                    blank: { mode: 'ANSWER_LENGTH', length: 6 } }
            }))
        }, { headers: privateHeaders });
        const result = await reading;
        expect(result.status).toBe('ACTIVE');
        if (result.status !== 'PREPARING') {
            expect(result.presentations[0].prompt).toEqual({ kind: 'TEXT', text: 'What remains?',
                blank: { mode: 'ANSWER_LENGTH', length: 6 } });
        }
    });

    function active(type: 'TYPED' | 'SELF_CHECK' | 'CLOZE_SINGLE' | 'SINGLE_CHOICE'): ReadyStudySession {
        const assessed = { bindingId: id('11'), role: 'ASSESSED' as const, memberKey: id('12'),
            itemRevisionId: id('13'), ordinal: 0, nodeIds: [id('14')], display: { kind: 'NODE_TEXT' as const } };
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
