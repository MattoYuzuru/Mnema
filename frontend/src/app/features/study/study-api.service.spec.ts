import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';

import { StudyApiService } from './study-api.service';
import { AttemptCommand, StudyProtocolError } from './study.models';
import { assessedOutcome, clone, ids, mechanics, privateHeaders, readySession } from './study-test-data';

describe('StudyApiService', () => {
    const deckId = ids.deckId;
    const sessionId = ids.sessionId;
    const commandId = ids.commandId;
    const location = `/api/decks/${deckId}/study-sessions/${sessionId}`;
    const created = { status: 201, statusText: 'Created', headers: { ...privateHeaders, Location: location } };
    const selfCheck = mechanics['presentations']['selfCheck'];
    const cloze = mechanics['presentations']['cloze'];
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
        expect(request.request.body).toEqual({ commandId, mode: 'SCHEDULED', budget: { maxPresentations: 20, maxNewObjectives: 5 } });
        request.flush(readySession([selfCheck]), created);
        expect((await result).value.status).toBe('ACTIVE');
    });

    it('maps the quick preset to one bounded scheduler budget', () => {
        api.start(deckId, commandId, { mode: 'SCHEDULED', preset: 'QUICK' }).subscribe();
        const request = http.expectOne(`/api/decks/${deckId}/study-sessions`);
        expect(request.request.body).toEqual({ commandId, mode: 'SCHEDULED', budget: { maxPresentations: 10, maxNewObjectives: 2 } });
        request.flush(readySession([selfCheck]), created);
    });

    it('fails closed on another deck, cacheable data, a wrong location or duplicate presentations', async () => {
        const mismatch = firstValueFrom(api.read(deckId, sessionId));
        http.expectOne(location).flush(readySession([selfCheck], { deckId: '99999999-9999-4999-8999-999999999999' }), { headers: privateHeaders });
        await expect(mismatch).rejects.toThrowError(StudyProtocolError);

        const cacheable = firstValueFrom(api.start(deckId, commandId));
        http.expectOne(`/api/decks/${deckId}/study-sessions`).flush(readySession([selfCheck]), { status: 201, statusText: 'Created', headers: { Location: location } });
        await expect(cacheable).rejects.toThrowError(StudyProtocolError);

        const misplaced = firstValueFrom(api.start(deckId, commandId));
        http.expectOne(`/api/decks/${deckId}/study-sessions`).flush(readySession([selfCheck]), { status: 201, statusText: 'Created', headers: { ...privateHeaders, Location: '/api/elsewhere' } });
        await expect(misplaced).rejects.toThrowError(StudyProtocolError);

        const duplicate = firstValueFrom(api.read(deckId, sessionId));
        http.expectOne(location).flush(readySession([selfCheck, selfCheck]), { headers: privateHeaders });
        await expect(duplicate).rejects.toThrowError(StudyProtocolError);
    });

    it('submits an exact free-response attempt and accepts a deterministic receipt', async () => {
        const command = mechanics['submits']['freeResponse'] as AttemptCommand;
        const result = firstValueFrom(api.submit(deckId, sessionId, command));
        const request = http.expectOne(`${location}/attempts`);
        expect(request.request.body).toEqual(command);
        request.flush(assessedOutcome(command, mechanics['feedback']['freeResponse']), { headers: privateHeaders });
        const outcome = (await result).value;
        expect(outcome.status).toBe('ASSESSED');
        expect(outcome.feedback.result).toBe('CORRECT');
    });

    it('refuses an invalid attempt before any request is made', async () => {
        const base = mechanics['submits'];
        const bad: unknown[] = [
            { ...base['choice'], response: { kind: 'CHOICE', optionIds: [] } },
            { ...base['choice'], response: { kind: 'CHOICE', optionIds: [base['choice'].response.optionIds[0], base['choice'].response.optionIds[0]] } },
            { ...base['cloze'], response: { kind: 'CLOZE', blanks: [base['cloze'].response.blanks[0], base['cloze'].response.blanks[0]] } },
            { ...base['match'], response: { kind: 'MATCH', pairs: [base['match'].response.pairs[0]] } },
            { ...base['selfCheck'], response: { kind: 'SELF_CHECK', rating: 'EXCELLENT' } },
            { ...base['freeResponse'], confidence: 'SURE' },
            { ...base['freeResponse'], durationMs: -1 }
        ];
        for (const command of bad) {
            await expect(firstValueFrom(api.submit(deckId, sessionId, command as AttemptCommand))).rejects.toThrowError(StudyProtocolError);
        }
        http.expectNone(`${location}/attempts`);
    });

    it('rejects feedback that mixes verdict shapes or lists a blank twice', async () => {
        const command = mechanics['submits']['cloze'] as AttemptCommand;
        const outcomeFor = (feedback: unknown) => {
            const result = firstValueFrom(api.submit(deckId, sessionId, command));
            http.expectOne(`${location}/attempts`).flush(assessedOutcome(command, feedback), { headers: privateHeaders });
            return result;
        };
        await expect(outcomeFor({ ...mechanics['feedback']['cloze'], correctOptionIds: [] })).rejects.toThrowError(StudyProtocolError);
        const twice = clone(mechanics['feedback']['cloze']);
        twice.blanks[1].blankId = twice.blanks[0].blankId;
        await expect(outcomeFor(twice)).rejects.toThrowError(StudyProtocolError);
        await expect(outcomeFor({ result: 'CORRECT' })).rejects.toThrowError(StudyProtocolError);
    });

    it('keeps replay and practice outcomes free of canonical effects', async () => {
        const command = mechanics['submits']['choice'] as AttemptCommand;
        const result = firstValueFrom(api.submit(deckId, sessionId, command));
        http.expectOne(`${location}/attempts`).flush({
            attemptId: command.attemptId, presentationId: command.presentationId, mode: 'PRACTICE', status: 'ASSESSED',
            canonicalEffects: false, evidence: null, transition: null, feedback: mechanics['feedback']['choice']
        }, { headers: privateHeaders });
        const outcome = (await result).value;
        expect(outcome.canonicalEffects).toBe(false);
        expect(outcome.transition).toBeNull();
    });

    it('rejects a transcript response whose content has no transcript or that reveals nothing', async () => {
        const presentation = mechanics['presentations']['freeResponse'];
        const call = () => firstValueFrom(api.revealTranscript(deckId, sessionId, presentation.presentationId, 'c3R1ZHktbm9uY2UtMTI', 'FREE_RESPONSE'));
        const missing = call();
        http.expectOne(`${location}/presentations/${presentation.presentationId}/transcript`).flush({ ...mechanics['transcriptRevealResponse'], content: presentation.content }, { headers: privateHeaders });
        await expect(missing).rejects.toThrowError(StudyProtocolError);
        const flag = call();
        http.expectOne(`${location}/presentations/${presentation.presentationId}/transcript`).flush({ ...mechanics['transcriptRevealResponse'], transcriptRevealed: false }, { headers: privateHeaders });
        await expect(flag).rejects.toThrowError(StudyProtocolError);
    });

    it('rejects a hint answer for another blank and a pair check that reveals the key', async () => {
        const hint = firstValueFrom(api.hint(deckId, sessionId, cloze.presentationId, 'c3R1ZHktbm9uY2UtMTM', mechanics['hintCommand'].blankId));
        http.expectOne(`${location}/presentations/${cloze.presentationId}/hints`).flush({ ...mechanics['hintResponse'], blankId: 'b1a00000-0000-4000-8000-000000000003' }, { headers: privateHeaders });
        await expect(hint).rejects.toThrowError(StudyProtocolError);

        const pair = mechanics['pairCheck'];
        const check = firstValueFrom(api.checkPair(deckId, sessionId, pair.presentationId, pair.nonce, pair.leftId, pair.rightId));
        http.expectOne(`${location}/pair-checks`).flush({ correct: true, correctRightId: pair.rightId }, { headers: privateHeaders });
        await expect(check).rejects.toThrowError(StudyProtocolError);
    });

    it('sends server-owned replay and practice intents and validates refilled sessions', async () => {
        const replayResult = firstValueFrom(api.start(deckId, commandId, { mode: 'REPLAY', sourceSessionId: sessionId }));
        const replay = http.expectOne(`/api/decks/${deckId}/study-sessions`);
        expect(replay.request.body).toEqual({ commandId, mode: 'REPLAY', sourceSessionId: sessionId, budget: { maxPresentations: 20 } });
        replay.flush(readySession([selfCheck], { mode: 'REPLAY' }), created);
        expect((await replayResult).value.mode).toBe('REPLAY');

        const practiceResult = firstValueFrom(api.start(deckId, commandId, { mode: 'PRACTICE', includeNew: false, order: 'WEAKEST_FIRST' }));
        const practice = http.expectOne(`/api/decks/${deckId}/study-sessions`);
        expect(practice.request.body).toEqual({ commandId, mode: 'PRACTICE', includeNew: false, order: 'WEAKEST_FIRST',
            budget: { maxPresentations: 20 } });
        practice.flush(readySession([selfCheck], { mode: 'PRACTICE' }), created);
        expect((await practiceResult).value.mode).toBe('PRACTICE');

        const refillResult = firstValueFrom(api.refill(deckId, sessionId));
        const refill = http.expectOne(`${location}/presentations`);
        expect(refill.request.method).toBe('POST');
        refill.flush(readySession([selfCheck]), { headers: privateHeaders });
        expect((await refillResult).status).toBe('ACTIVE');
    });

    it('validates explainable progress, replay sources and restart acknowledgements', async () => {
        const progressResult = firstValueFrom(api.progress(deckId));
        http.expectOne(`/api/decks/${deckId}/study-progress?limit=100`).flush({
            asOf: '2026-10-01T10:00:00Z', items: [{ memberKey: '44444444-4444-4444-8444-444444444444',
                    itemRevisionId: '55555555-5555-4555-8555-555555555555', title: 'Вопрос по истории', state: 'DUE',
                    objectiveCoverage: { enabled: 2, introduced: 1, assessed: 1 }, lastAssessedAt: '2026-09-30T10:00:00Z',
                    nextDue: '2026-10-01T09:00:00Z' }], nextCursor: null
        }, { headers: privateHeaders });
        expect((await progressResult).items[0].state).toBe('DUE');

        const sourcesResult = firstValueFrom(api.replaySources(deckId));
        http.expectOne(`/api/decks/${deckId}/study-sessions/replay-sources`).flush({
            asOf: '2026-10-01T10:00:00Z', localStudyDate: '2026-10-01',
            items: [{ sessionId, completedAt: '2026-10-01T09:00:00Z', presentationCount: 4 }]
        }, { headers: privateHeaders });
        expect((await sourcesResult).items[0].presentationCount).toBe(4);

        const member = '44444444-4444-4444-8444-444444444444';
        const restartResult = firstValueFrom(api.restart(deckId, commandId, [member]));
        const restart = http.expectOne(`/api/decks/${deckId}/study-restarts`);
        expect(restart.request.body).toEqual({ commandId, memberKeys: [member] });
        restart.flush({ commandId, restartedAt: '2026-10-01T10:00:00Z', objectiveCount: 1,
            learningEpochs: [{ objectiveId: '77777777-7777-4777-8777-777777777771', learningEpoch: '1' }] }, { headers: privateHeaders });
        expect((await restartResult).value.objectiveCount).toBe(1);
    });
});
