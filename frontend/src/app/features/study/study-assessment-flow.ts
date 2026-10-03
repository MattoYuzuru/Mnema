import { HttpErrorResponse } from '@angular/common/http';
import { DOCUMENT } from '@angular/common';
import { DestroyRef, Injectable, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { timeout } from 'rxjs';

import { StudyApiService } from './study-api.service';
import {
    AssessingAttempt, AttemptOutcome, AttemptState, SelfCheckAttempt, SelfRating, isAssessing, isSelfCheckAttempt
} from './study.models';

/** From this moment of waiting the learner is offered «Оценить себя» (architecture §11: target p95 is 8 s, the offer is at 5 s). */
export const SELF_CHECK_OFFER_MS = 5000;
const POLL_MIN_MS = 500;
const POLL_MAX_MS = 3000;
const POLL_FALLBACK_MS = 700;
const POLL_FAILURES = 3;
/** One poll never waits longer than this; a hung request counts as a failed poll. */
const POLL_TIMEOUT_MS = 5000;

/**
 * A reply that retrying cannot fix: the session expired, or the attempt is not the account's any more (404), or the sign-in
 * is gone (401). The host decides what to show; the flow stops instead of polling a lost cause.
 */
function isFatal(error: unknown): boolean {
    if (!(error instanceof HttpErrorResponse)) return false;
    const body = typeof error.error === 'object' && error.error !== null ? error.error as Record<string, unknown> : {};
    return error.status === 401 || error.status === 404 || body['code'] === 'SESSION_EXPIRED' || body['code'] === 'PRESENTATION_EXPIRED';
}

export type AssessmentStage = 'idle' | 'assessing' | 'self-check';

interface Target {
    readonly deckId: string;
    readonly sessionId: string;
    readonly attemptId: string;
    readonly resolved: (outcome: AttemptOutcome) => void;
    readonly fatal: (error: unknown) => void;
}

/**
 * Follows one `ai-semantic` answer after it was accepted (202): polls with the server's delay (700 ms, then 1.5 s), offers
 * «Оценить себя» after 5 s, switches to the self-check view when the server says so, and hands the stored outcome to the
 * host. It never decides a grade: a slow, uncertain or unavailable grader only ever leads to the learner's own rating.
 * Provided per host component, so its timers end with the page.
 */
@Injectable()
export class StudyAssessmentFlow {
    private readonly api = inject(StudyApiService);
    private readonly destroyRef = inject(DestroyRef);
    private readonly document = inject(DOCUMENT);

    readonly stage = signal<AssessmentStage>('idle');
    /** «Оценить себя» is visible: the grading has taken longer than the offer delay. */
    readonly offered = signal(false);
    readonly view = signal<SelfCheckAttempt | null>(null);
    /** A self-check or rating request is in flight. */
    readonly busy = signal(false);
    readonly problem = signal<string | null>(null);

    private target: Target | null = null;
    private pollTimer: ReturnType<typeof setTimeout> | null = null;
    private offerTimer: ReturnType<typeof setTimeout> | null = null;
    private failures = 0;
    /** Bumped on every start and stop so a late answer of an abandoned attempt is ignored. */
    private generation = 0;
    /** A poll came due while the tab was hidden; it runs the moment the tab is visible again. */
    private pollWaitingForVisibility = false;
    private readonly onVisibility = (): void => {
        if (this.document.hidden || !this.pollWaitingForVisibility) return;
        this.pollWaitingForVisibility = false;
        this.schedulePoll(0);
    };

    constructor() {
        this.document.addEventListener('visibilitychange', this.onVisibility);
        this.destroyRef.onDestroy(() => {
            this.document.removeEventListener('visibilitychange', this.onVisibility);
            this.clearTimers();
        });
    }

    /** Starts from the state a submit returned (202). */
    begin(deckId: string, sessionId: string, state: AssessingAttempt | SelfCheckAttempt,
          resolved: (outcome: AttemptOutcome) => void, fatal: (error: unknown) => void = () => undefined): void {
        this.start({ deckId, sessionId, attemptId: state.attemptId, resolved, fatal }, 0);
        if (isSelfCheckAttempt(state)) this.showSelfCheck(state);
        else this.schedulePoll(state.retryAfterMs);
    }

    /**
     * Resumes after a reload: the session says this answer is still in assessment. `elapsedMs` is how long ago it was sent when
     * the tab knows (the 5 s offer counts from then); without it the offer comes 5 s after the resume.
     */
    resume(deckId: string, sessionId: string, attemptId: string, resolved: (outcome: AttemptOutcome) => void,
           fatal: (error: unknown) => void = () => undefined, elapsedMs = 0): void {
        this.start({ deckId, sessionId, attemptId, resolved, fatal }, elapsedMs);
        this.schedulePoll(0);
    }

    stop(): void {
        this.generation++;
        this.pollWaitingForVisibility = false;
        this.clearTimers();
        this.target = null;
        this.stage.set('idle');
        this.offered.set(false);
        this.view.set(null);
        this.busy.set(false);
        this.problem.set(null);
    }

    /** After a polling failure the learner asks again. */
    retry(): void {
        if (this.target === null || this.stage() !== 'assessing') return;
        this.problem.set(null);
        this.failures = 0;
        this.schedulePoll(0);
    }

    /** «Оценить себя». The grade may already have won the race; then the outcome is shown instead. */
    selfCheck(): void {
        const target = this.target;
        if (target === null || this.stage() !== 'assessing' || this.busy()) return;
        const generation = this.generation;
        this.busy.set(true);
        this.problem.set(null);
        this.api.selfCheck(target.deckId, target.sessionId, target.attemptId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
            next: state => { if (generation === this.generation) { this.busy.set(false); this.accept(state); } },
            error: error => {
                if (generation !== this.generation) return;
                this.busy.set(false);
                if (isFatal(error)) { this.lose(target, error); return; }
                this.problem.set('Не удалось переключиться на самооценку. Попробуйте ещё раз.');
            }
        });
    }

    /** The learner's own rating completes the same answer. */
    rate(rating: SelfRating): void {
        const target = this.target;
        if (target === null || this.stage() !== 'self-check' || this.busy()) return;
        const generation = this.generation;
        this.busy.set(true);
        this.problem.set(null);
        this.api.selfRate(target.deckId, target.sessionId, target.attemptId, rating).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
            next: result => { if (generation === this.generation) { this.busy.set(false); this.finish(result.value); } },
            error: error => {
                if (generation !== this.generation) return;
                this.busy.set(false);
                if (isFatal(error)) { this.lose(target, error); return; }
                this.problem.set('Не удалось сохранить оценку. Ответ не потерян: попробуйте ещё раз.');
            }
        });
    }

    private start(target: Target, elapsedMs: number): void {
        this.stop();
        this.failures = 0;
        this.target = target;
        this.stage.set('assessing');
        const wait = Math.max(0, SELF_CHECK_OFFER_MS - Math.max(0, elapsedMs));
        const offer = (): void => {
            this.offerTimer = null;
            if (this.stage() === 'assessing') this.offered.set(true);
        };
        if (wait === 0) offer();
        else this.offerTimer = setTimeout(offer, wait);
    }

    private schedulePoll(delay: number): void {
        if (this.pollTimer !== null) clearTimeout(this.pollTimer);
        const generation = this.generation;
        this.pollTimer = setTimeout(() => {
            this.pollTimer = null;
            if (generation !== this.generation) return;
            if (this.document.hidden) this.pollWaitingForVisibility = true;
            else this.poll();
        }, delay);
    }

    private poll(): void {
        const target = this.target;
        if (target === null || this.stage() !== 'assessing') return;
        const generation = this.generation;
        this.api.attempt(target.deckId, target.sessionId, target.attemptId)
            .pipe(timeout({ first: POLL_TIMEOUT_MS }), takeUntilDestroyed(this.destroyRef)).subscribe({
            next: state => {
                if (generation !== this.generation) return;
                this.failures = 0;
                this.accept(state);
            },
            error: error => {
                if (generation !== this.generation) return;
                if (isFatal(error)) { this.lose(target, error); return; }
                if (++this.failures >= POLL_FAILURES) {
                    this.problem.set('Не удалось узнать результат проверки. Ответ не потерян.');
                } else this.schedulePoll(POLL_FALLBACK_MS * 2);
            }
        });
    }

    private lose(target: Target, error: unknown): void {
        this.stop();
        target.fatal(error);
    }

    private accept(state: AttemptState): void {
        if (isAssessing(state)) {
            if (this.stage() === 'assessing') this.schedulePoll(Math.min(POLL_MAX_MS, Math.max(POLL_MIN_MS, state.retryAfterMs)));
        } else if (isSelfCheckAttempt(state)) this.showSelfCheck(state);
        else this.finish(state);
    }

    private showSelfCheck(state: SelfCheckAttempt): void {
        this.clearTimers();
        this.stage.set('self-check');
        this.offered.set(false);
        this.problem.set(null);
        this.view.set(state);
    }

    private finish(outcome: AttemptOutcome): void {
        const target = this.target;
        this.stop();
        target?.resolved(outcome);
    }

    private clearTimers(): void {
        if (this.pollTimer !== null) clearTimeout(this.pollTimer);
        if (this.offerTimer !== null) clearTimeout(this.offerTimer);
        this.pollTimer = null;
        this.offerTimer = null;
    }
}
