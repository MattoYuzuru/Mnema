import type { Mock } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { Subject, of, throwError } from 'rxjs';

import { spyObj, type SpyObj } from '../../../testing/mocks';
import { StudyApiService } from './study-api.service';
import { SELF_CHECK_OFFER_MS, StudyAssessmentFlow } from './study-assessment-flow';
import { AssessingAttempt, AttemptOutcome, AttemptState, SelfCheckAttempt } from './study.models';
import { assessment, clone, ids } from './study-test-data';

describe('StudyAssessmentFlow', () => {
    const attemptId = assessment['accepted'].attemptId as string;
    const assessing = (retryAfterMs = 700): AssessingAttempt => ({ ...clone(assessment['accepted']), retryAfterMs });
    const selfCheckView = (): SelfCheckAttempt => clone(assessment['polledSelfCheck']);
    const result = (): AttemptOutcome => ({ ...clone(assessment['resultComplete']), canonicalEffects: true, disputed: false });
    let api: SpyObj<StudyApiService>;
    let flow: StudyAssessmentFlow;
    let resolved: Mock<(outcome: AttemptOutcome) => void>;

    beforeEach(() => {
        vi.useFakeTimers();
        api = spyObj<StudyApiService>({
            attempt: vi.fn().mockName('attempt'), selfCheck: vi.fn().mockName('selfCheck'), selfRate: vi.fn().mockName('selfRate')
        });
        TestBed.configureTestingModule({ providers: [StudyAssessmentFlow, { provide: StudyApiService, useValue: api }] });
        flow = TestBed.inject(StudyAssessmentFlow);
        resolved = vi.fn<(outcome: AttemptOutcome) => void>();
    });
    afterEach(() => vi.useRealTimers());

    const begin = (state: AssessingAttempt | SelfCheckAttempt = assessing()) => flow.begin(ids.deckId, ids.sessionId, state, resolved);

    it('polls after the delay the server gave, follows its newer delay, and offers «Оценить себя» only after five seconds', () => {
        api.attempt.mockReturnValue(of(assessing(1500) as AttemptState));
        begin();
        expect(flow.stage()).toBe('assessing');
        expect(flow.offered()).toBe(false);
        vi.advanceTimersByTime(699);
        expect(api.attempt).not.toHaveBeenCalled();
        vi.advanceTimersByTime(1);
        expect(api.attempt).toHaveBeenCalledTimes(1);
        expect(api.attempt).toHaveBeenCalledWith(ids.deckId, ids.sessionId, attemptId);
        vi.advanceTimersByTime(1499);
        expect(api.attempt).toHaveBeenCalledTimes(1);
        vi.advanceTimersByTime(1);
        expect(api.attempt).toHaveBeenCalledTimes(2);
        vi.advanceTimersByTime(SELF_CHECK_OFFER_MS - 2200 - 1);
        expect(flow.offered()).toBe(false);
        vi.advanceTimersByTime(1);
        expect(flow.offered()).toBe(true);
        expect(flow.stage()).toBe('assessing');
    });

    it('never waits longer than three seconds between polls whatever the server suggests', () => {
        api.attempt.mockReturnValue(of(assessing(60_000) as AttemptState));
        begin();
        vi.advanceTimersByTime(700);
        expect(api.attempt).toHaveBeenCalledTimes(1);
        vi.advanceTimersByTime(3000);
        expect(api.attempt).toHaveBeenCalledTimes(2);
    });

    it('hands the stored outcome to the host and goes idle', () => {
        api.attempt.mockReturnValue(of(result() as AttemptState));
        begin();
        vi.advanceTimersByTime(700);
        expect(resolved).toHaveBeenCalledWith(expect.objectContaining({ status: 'ASSESSED', attemptId }));
        expect(flow.stage()).toBe('idle');
        vi.advanceTimersByTime(60_000);
        expect(api.attempt).toHaveBeenCalledTimes(1);
        expect(flow.offered()).toBe(false);
    });

    it('shows the self-check view as soon as the server says so, withdraws the offer and stops polling', () => {
        api.attempt.mockReturnValue(of(selfCheckView() as AttemptState));
        begin();
        vi.advanceTimersByTime(700);
        expect(flow.stage()).toBe('self-check');
        expect(flow.view()?.reason).toBe('PROVIDER_UNCERTAIN');
        expect(flow.offered()).toBe(false);
        vi.advanceTimersByTime(60_000);
        expect(api.attempt).toHaveBeenCalledTimes(1);
        expect(flow.offered()).toBe(false);
    });

    it('starts in self-check when the submit already said so, without polling', () => {
        begin(selfCheckView());
        expect(flow.stage()).toBe('self-check');
        vi.advanceTimersByTime(10_000);
        expect(api.attempt).not.toHaveBeenCalled();
    });

    it('resumes a reload by polling at once', () => {
        api.attempt.mockReturnValue(of(selfCheckView() as AttemptState));
        flow.resume(ids.deckId, ids.sessionId, attemptId, resolved);
        expect(flow.stage()).toBe('assessing');
        vi.advanceTimersByTime(0);
        expect(api.attempt).toHaveBeenCalledTimes(1);
        expect(flow.stage()).toBe('self-check');
    });

    it('switches to self-check on request, or shows the outcome when the grade won the race', () => {
        begin();
        const chosen = { ...selfCheckView(), reason: 'LEARNER_CHOICE' } as SelfCheckAttempt;
        api.selfCheck.mockReturnValueOnce(of(chosen as AttemptState));
        flow.selfCheck();
        expect(api.selfCheck).toHaveBeenCalledWith(ids.deckId, ids.sessionId, attemptId);
        expect(flow.stage()).toBe('self-check');
        expect(flow.view()?.reason).toBe('LEARNER_CHOICE');
        expect(flow.busy()).toBe(false);

        flow.stop();
        begin();
        api.selfCheck.mockReturnValueOnce(of(result() as AttemptState));
        flow.selfCheck();
        expect(resolved).toHaveBeenCalledTimes(1);
        expect(flow.stage()).toBe('idle');
    });

    it('keeps waiting and says so when the switch to self-check fails', () => {
        begin();
        api.selfCheck.mockReturnValueOnce(throwError(() => new Error('offline')));
        flow.selfCheck();
        expect(flow.stage()).toBe('assessing');
        expect(flow.busy()).toBe(false);
        expect(flow.problem()).toContain('самооценку');
    });

    it('completes the same attempt with the learner rating, and keeps the view when the rating cannot be saved', () => {
        begin(selfCheckView());
        api.selfRate.mockReturnValueOnce(throwError(() => new Error('offline')));
        flow.rate('PARTIAL');
        expect(api.selfRate).toHaveBeenCalledWith(ids.deckId, ids.sessionId, attemptId, 'PARTIAL');
        expect(flow.stage()).toBe('self-check');
        expect(flow.problem()).toContain('не потерян');
        expect(resolved).not.toHaveBeenCalled();
        api.selfRate.mockReturnValueOnce(of({ value: result(), replayed: false }));
        flow.rate('PARTIAL');
        expect(resolved).toHaveBeenCalledTimes(1);
        expect(flow.stage()).toBe('idle');
    });

    it('retries a failing poll twice, then tells the learner and lets them check again', () => {
        api.attempt.mockReturnValue(throwError(() => new Error('offline')));
        begin();
        vi.advanceTimersByTime(700);
        expect(flow.problem()).toBeNull();
        vi.advanceTimersByTime(1400);
        expect(api.attempt).toHaveBeenCalledTimes(2);
        vi.advanceTimersByTime(1400);
        expect(api.attempt).toHaveBeenCalledTimes(3);
        expect(flow.problem()).toContain('не потерян');
        vi.advanceTimersByTime(30_000);
        expect(api.attempt).toHaveBeenCalledTimes(3);
        api.attempt.mockReturnValue(of(result() as AttemptState));
        flow.retry();
        vi.advanceTimersByTime(0);
        expect(resolved).toHaveBeenCalledTimes(1);
    });

    it('ignores a late answer of an attempt that was abandoned', () => {
        const late = new Subject<AttemptState>();
        api.attempt.mockReturnValue(late);
        begin();
        vi.advanceTimersByTime(700);
        flow.stop();
        late.next(result());
        expect(resolved).not.toHaveBeenCalled();
        expect(flow.stage()).toBe('idle');
    });

    it('ends its timers with the page', () => {
        api.attempt.mockReturnValue(of(assessing() as AttemptState));
        begin();
        TestBed.resetTestingModule();
        vi.advanceTimersByTime(60_000);
        expect(api.attempt).not.toHaveBeenCalled();
    });
});
