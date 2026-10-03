import { HttpHeaders, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';

import { StudyApiService } from './study-api.service';
import { AttemptCommand, AttemptOutcome, StudyProtocolError, isAssessing, isAttemptOutcome, isFreeResponseFeedback, isSelfCheckAttempt } from './study.models';
import { assessment, clone, ids, mechanics, privateHeaders, readySession } from './study-test-data';

/** The AI assessment wire contract (contracts/study/assessment.json): submit 202, poll, self-check, self-rating, dispute. */
describe('StudyApiService, AI assessment', () => {
    const deckId = ids.deckId;
    const sessionId = ids.sessionId;
    const attemptsUrl = `/api/decks/${deckId}/study-sessions/${sessionId}/attempts`;
    const attemptId = assessment['submitText'].attemptId as string;
    const command = assessment['submitText'] as AttemptCommand;
    const attemptUrl = `${attemptsUrl}/${attemptId}`;
    let api: StudyApiService;
    let http: HttpTestingController;

    beforeEach(() => {
        TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
        api = TestBed.inject(StudyApiService);
        http = TestBed.inject(HttpTestingController);
    });
    afterEach(() => http.verify());

    const flush = (request: ReturnType<HttpTestingController["expectOne"]>, body: object, status = 200, headers: Record<string, string> = {}) =>
        request.flush(body, { status, statusText: status === 202 ? 'Accepted' : 'OK', headers: new HttpHeaders({ ...privateHeaders, ...headers }) });

    it('submits a text answer unchanged and follows the 202 grading state', async () => {
        const result = firstValueFrom(api.submit(deckId, sessionId, command));
        const request = http.expectOne(attemptsUrl);
        expect(request.request.body).toEqual(command);
        expect(Object.keys(request.request.body.response)).toEqual(['kind', 'text']);
        flush(request, assessment['accepted'], 202);
        const state = (await result).value;
        expect(isAssessing(state)).toBe(true);
        expect(state).toMatchObject({ status: 'ASSESSING', retryAfterMs: 700 });
    });

    it('accepts a retry of the same submit as a replay, with the current state or the stored outcome', async () => {
        const first = firstValueFrom(api.submit(deckId, sessionId, command));
        flush(http.expectOne(attemptsUrl), assessment['polledAssessing'], 202, { 'Idempotency-Replayed': 'true' });
        expect(await first).toMatchObject({ replayed: true, value: { status: 'ASSESSING', retryAfterMs: 1500 } });
        const second = firstValueFrom(api.submit(deckId, sessionId, command));
        flush(http.expectOne(attemptsUrl), assessment['resultComplete'], 200, { 'Idempotency-Replayed': 'true' });
        const stored = await second;
        expect(stored.replayed).toBe(true);
        expect(isAttemptOutcome(stored.value)).toBe(true);
    });

    it('goes straight to self-check when the submit already says so (fair-use spent)', async () => {
        const result = firstValueFrom(api.submit(deckId, sessionId, command));
        flush(http.expectOne(attemptsUrl), assessment['selfCheckUsageLimit'], 202);
        const state = (await result).value;
        expect(isSelfCheckAttempt(state) && state.reason).toBe('USAGE_LIMIT');
        expect(isSelfCheckAttempt(state) && state.selfCheck.criteria.length).toBe(4);
    });

    it('refuses a 202 that is not a grading state, a mismatching attempt, and a cacheable answer', async () => {
        const submit = () => firstValueFrom(api.submit(deckId, sessionId, command));
        let result = submit();
        flush(http.expectOne(attemptsUrl), assessment['resultComplete'], 202);
        await expect(result).rejects.toThrowError(StudyProtocolError);
        result = submit();
        flush(http.expectOne(attemptsUrl), { ...assessment['accepted'], attemptId: ids.commandId }, 202);
        await expect(result).rejects.toThrowError(StudyProtocolError);
        result = submit();
        http.expectOne(attemptsUrl).flush(assessment['accepted'], { status: 202, statusText: 'Accepted' });
        await expect(result).rejects.toThrowError(StudyProtocolError);
        result = submit();
        flush(http.expectOne(attemptsUrl), { ...assessment['accepted'], retryAfterMs: 10 }, 202);
        await expect(result).rejects.toThrowError(StudyProtocolError);
        result = submit();
        flush(http.expectOne(attemptsUrl), { ...assessment['accepted'], provider: 'x' }, 202);
        await expect(result).rejects.toThrowError(StudyProtocolError);
    });

    it('polls an attempt and reads each of its states', async () => {
        for (const [name, check] of [['polledAssessing', isAssessing], ['polledSelfCheck', isSelfCheckAttempt], ['resultComplete', isAttemptOutcome]] as const) {
            const result = firstValueFrom(api.attempt(deckId, sessionId, attemptId));
            const request = http.expectOne(attemptUrl);
            expect(request.request.method).toBe('GET');
            flush(request, assessment[name]);
            expect(check(await result)).toBe(true);
        }
    });

    it('rejects a self-check view with a reason or a criterion it does not know, and a poll of another attempt', async () => {
        const bad = [
            { ...assessment['polledSelfCheck'], reason: 'MODEL_ERROR' },
            { ...assessment['polledSelfCheck'], selfCheck: { ...assessment['polledSelfCheck'].selfCheck, criteria: assessment['polledSelfCheck'].selfCheck.criteria.slice(0, 2) } },
            { ...assessment['polledSelfCheck'], selfCheck: { ...assessment['polledSelfCheck'].selfCheck, verdicts: [] } },
            { ...assessment['polledSelfCheck'], attemptId: ids.commandId }
        ];
        for (const body of bad) {
            const result = firstValueFrom(api.attempt(deckId, sessionId, attemptId));
            flush(http.expectOne(attemptUrl), body);
            await expect(result).rejects.toThrowError(StudyProtocolError);
        }
    });

    it('turns to self-check with an empty body and returns whatever the attempt is now, including a grade that won the race', async () => {
        let result = firstValueFrom(api.selfCheck(deckId, sessionId, attemptId));
        let request = http.expectOne(`${attemptUrl}/self-check`);
        expect(request.request.method).toBe('POST');
        expect(request.request.body).toEqual({});
        const view = clone(assessment['polledSelfCheck']);
        view.reason = 'LEARNER_CHOICE';
        flush(request, view);
        expect(await result).toMatchObject({ status: 'SELF_CHECK', reason: 'LEARNER_CHOICE' });
        result = firstValueFrom(api.selfCheck(deckId, sessionId, attemptId));
        request = http.expectOne(`${attemptUrl}/self-check`);
        flush(request, assessment['resultComplete']);
        expect(isAttemptOutcome(await result)).toBe(true);
    });

    it('sends the learner rating and reads the self-rated outcome, which carries the reference but no assessment', async () => {
        const result = firstValueFrom(api.selfRate(deckId, sessionId, attemptId, 'PARTIAL'));
        const request = http.expectOne(`${attemptUrl}/self-rating`);
        expect(request.request.body).toEqual(assessment['selfRatingCommand']);
        flush(request, assessment['selfRatingOutcome']);
        const outcome = (await result).value;
        expect(outcome.feedback).toMatchObject({ result: 'PARTIAL' });
        expect(isFreeResponseFeedback(outcome.feedback) && outcome.feedback.assessment).toBeUndefined();
        await expect(firstValueFrom(api.selfRate(deckId, sessionId, attemptId, 'EXCELLENT' as never))).rejects.toThrowError(StudyProtocolError);
        http.expectNone(`${attemptUrl}/self-rating`);
    });

    it('disputes with a command id and only accepts a disputed outcome that kept the reference', async () => {
        const commandId = assessment['disputeCommand'].commandId as string;
        const result = firstValueFrom(api.dispute(deckId, sessionId, attemptId, commandId));
        const request = http.expectOne(`${attemptUrl}/dispute`);
        expect(request.request.body).toEqual({ commandId });
        flush(request, assessment['disputeOutcome']);
        const outcome = (await result).value;
        expect(outcome).toMatchObject({ status: 'NOT_ASSESSED', disputed: true, transition: null });
        expect(outcome.feedback).toMatchObject({ result: 'NOT_ASSESSED', reasonCodes: ['AI_DISPUTED'] });
        const replay = firstValueFrom(api.dispute(deckId, sessionId, attemptId, commandId));
        flush(http.expectOne(`${attemptUrl}/dispute`), assessment['resultComplete']);
        await expect(replay).rejects.toThrowError(StudyProtocolError);
    });

    describe('graded feedback', () => {
        const outcomeOf = async (body: object): Promise<AttemptOutcome> => {
            const result = firstValueFrom(api.attempt(deckId, sessionId, attemptId));
            flush(http.expectOne(attemptUrl), body);
            return await result as AttemptOutcome;
        };

        it('parses the covered, missing and contradicted points of every fixture result', async () => {
            for (const name of ['resultComplete', 'resultPartialStrict', 'resultOffTopic', 'resultPractice']) {
                const outcome = await outcomeOf(assessment[name]);
                const feedback = outcome.feedback;
                expect(isFreeResponseFeedback(feedback) && feedback.assessment).toEqual(assessment[name].feedback.assessment);
            }
        });

        it('refuses an assessment whose judgement disagrees with the result or that carries extra fields', async () => {
            const wrong = clone(assessment['resultComplete']);
            wrong.feedback.result = 'INCORRECT';
            await expect(outcomeOf(wrong)).rejects.toThrowError(StudyProtocolError);
            const extra = clone(assessment['resultComplete']);
            extra.feedback.assessment.verdicts = [];
            await expect(outcomeOf(extra)).rejects.toThrowError(StudyProtocolError);
            const note = clone(assessment['resultComplete']);
            note.feedback.assessment.covered[0].note = 'модель думает';
            await expect(outcomeOf(note)).rejects.toThrowError(StudyProtocolError);
            const strictness = clone(assessment['resultComplete']);
            strictness.feedback.assessment.strictness = 'S4';
            await expect(outcomeOf(strictness)).rejects.toThrowError(StudyProtocolError);
            const partial = clone(assessment['resultComplete']);
            delete partial.feedback.assessment.covered[0].partial;
            await expect(outcomeOf(partial)).rejects.toThrowError(StudyProtocolError);
        });
    });

    describe('presentation in flight', () => {
        const read = async (presentation: unknown) => {
            const result = firstValueFrom(api.read(deckId, sessionId));
            flush(http.expectOne(`/api/decks/${deckId}/study-sessions/${sessionId}`), readySession([presentation]));
            return result;
        };

        it('reads the optional assessment member so a reload can resume the answer', async () => {
            const session = await read(assessment['presentationInFlight']);
            expect(session).toMatchObject({ presentations: [{ assessment: { attemptId, status: 'ASSESSING' } }] });
            expect(JSON.stringify(session)).not.toContain('rubric');
            const plain = clone(assessment['presentationInFlight']);
            delete plain.assessment;
            expect((await read(plain) as unknown as { presentations: { assessment?: unknown }[] }).presentations[0].assessment).toBeUndefined();
        });

        it('refuses a malformed assessment member and one on another mechanic', async () => {
            await expect(read({ ...assessment['presentationInFlight'], assessment: { attemptId, status: 'DONE' } })).rejects.toThrowError(StudyProtocolError);
            await expect(read({ ...assessment['presentationInFlight'], assessment: { attemptId, status: 'ASSESSING', rubric: {} } })).rejects.toThrowError(StudyProtocolError);
            await expect(read({ ...mechanics['presentations']['choice'], assessment: { attemptId, status: 'ASSESSING' } })).rejects.toThrowError(StudyProtocolError);
        });
    });
});
