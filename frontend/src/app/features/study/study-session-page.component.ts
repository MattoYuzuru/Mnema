import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, Injector, afterNextRender, computed, effect, inject, signal, untracked, viewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { catchError, map, of, timer } from 'rxjs';

import { LearnerContent, Mechanic } from '../../content/exercise/exercise-content.models';
import { QuietZone } from '../../core/notifications/quiet-zone';
import { MnemaSelectComponent, MnemaSelectOption } from '../../core/controls/mnema-select.component';
import { NewBadgeComponent } from '../../shared/new-badge.component';
import { AssessmentSelfCheckComponent, AssessmentWaitingComponent } from './assessment-views.component';
import { LearnerExerciseComponent, PairChecker } from './learner-exercise.component';
import { LearnerFeedbackComponent, feedbackTitle } from './learner-feedback.component';
import { CAPABILITIES_UNAVAILABLE, CapabilitiesApiService } from '../authoring/capabilities-api.service';
import { OwnDecksApiService } from '../own-decks/own-decks-api.service';
import { PromoPopupService } from '../promo/promo-popup.service';
import { OwnDeck } from '../own-decks/own-deck.models';
import { StudyApiService } from './study-api.service';
import {
    AttemptCommand,
    AttemptOutcome,
    AttemptState,
    AssessingAttempt,
    MaterialProgress,
    PracticeOrder,
    ReadyStudySession,
    ReplaySource,
    ScheduledStudyPreset,
    SelfCheckAttempt,
    StudyResponse,
    StudySession,
    StudyStartIntent,
    isAssessing,
    isAttemptOutcome,
    isFreeResponseFeedback
} from './study.models';
import { StudyAssessmentFlow } from './study-assessment-flow';
import { StudyRecoveryService } from './study-recovery.service';

type Phase = 'setup' | 'loading' | 'preparing' | 'answering' | 'revealed' | 'submitting' | 'assessing' | 'self-check' | 'feedback'
    | 'unknown' | 'conflict' | 'empty' | 'complete' | 'expired' | 'unavailable' | 'error';
type DisputeStep = 'idle' | 'confirm' | 'sending';

const TASK_OPEN: readonly Phase[] = ['answering', 'revealed', 'submitting', 'self-check'];

@Component({
    selector: 'app-study-session-page',
    imports: [RouterLink, MnemaSelectComponent, LearnerExerciseComponent, LearnerFeedbackComponent, NewBadgeComponent,
        AssessmentWaitingComponent, AssessmentSelfCheckComponent],
    providers: [StudyAssessmentFlow],
    templateUrl: './study-session-page.component.html',
    styleUrl: './study-session-page.component.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class StudySessionPageComponent {
    readonly practiceOrderOptions: readonly MnemaSelectOption[] = [
        { value: 'SEEDED', label: 'Случайно' }, { value: 'WEAKEST_FIRST', label: 'Сначала трудные' }
    ];
    readonly deck = signal<OwnDeck | null>(null);
    readonly session = signal<ReadyStudySession | null>(null);
    readonly phase = signal<Phase>('loading');
    /** The response committed for the current presentation; feedback is shown next to it. */
    readonly submitted = signal<StudyResponse | null>(null);
    /** True while the answer surface holds input that would be lost by leaving. */
    readonly answerDirty = signal(false);
    readonly transcriptLoading = signal(false);
    readonly hintPending = signal<string | null>(null);
    readonly feedback = signal<AttemptOutcome | null>(null);
    /** «Оспорить оценку»: asked, confirmed inline (not a modal), sent. */
    readonly disputeStep = signal<DisputeStep>('idle');
    readonly disputeProblem = signal<string | null>(null);
    /** The server refused to take the grade back (a later answer exists); the offer is withdrawn. */
    readonly disputeBlocked = signal(false);
    readonly message = signal<string | null>(null);
    readonly pending = signal<AttemptCommand | null>(null);
    readonly progress = signal<readonly MaterialProgress[]>([]);
    readonly progressNextCursor = signal<string | null>(null);
    readonly progressUnavailable = signal(false);
    readonly progressLoading = signal(false);
    readonly progressMoreError = signal(false);
    readonly progressSentinel = viewChild<ElementRef<HTMLElement>>('progressSentinel');
    readonly replaySources = signal<readonly ReplaySource[]>([]);
    readonly replayOptions = computed<readonly MnemaSelectOption[]>(() => this.replaySources().map(source => ({
        value: source.sessionId,
        label: `${this.formatDate(source.completedAt)} · ${source.presentationCount} заданий`
    })));
    readonly selectedReplayId = signal<string | null>(null);
    readonly includeNewPractice = signal(false);
    readonly practiceOrder = signal<PracticeOrder>('SEEDED');
    readonly scheduledPreset = signal<ScheduledStudyPreset>('STANDARD');
    readonly supportLoading = signal(true);
    readonly current = computed(() => this.session()?.presentations[0] ?? null);
    /** The text of the answer in assessment, `null` when this tab no longer holds it (a reload after it was sent). */
    readonly submittedText = computed(() => { const value = this.submitted(); return value?.kind === 'TEXT' ? value.text : null; });
    /**
     * After a reload that found the answer already graded while the queue had moved on: the content of the answered card is
     * gone from the session, so the result is shown over a minimal free-response shell and «Продолжить» keeps the whole queue.
     */
    readonly recoveredContent = signal<LearnerContent | null>(null);
    readonly feedbackContent = computed<LearnerContent | null>(() => this.recoveredContent() ?? this.current());
    /** The outcome is an AI grade that can be taken back. */
    readonly disputable = computed(() => {
        const outcome = this.feedback();
        return outcome !== null && !outcome.disputed && isFreeResponseFeedback(outcome.feedback) && outcome.feedback.assessment !== undefined;
    });
    readonly hints = computed<Readonly<Record<string, string>>>(() => Object.fromEntries(
        (this.current()?.hints ?? []).map(hint => [hint.blankId, hint.firstLetter])));
    /** Pair checks go to the server; the board never decides correctness itself. */
    readonly pairChecker = computed<PairChecker | null>(() => {
        const presentation = this.current();
        const session = this.session();
        if (presentation === null || session === null) return null;
        return pair => this.api.checkPair(this.deckId, session.sessionId, presentation.presentationId,
            presentation.nonce, pair.leftId, pair.rightId).pipe(map(result => result.correct));
    });
    readonly position = computed(() => {
        const current = this.current();
        const session = this.session();
        return current === null || session === null ? null : `Задание ${current.ordinal + 1}`;
    });

    private readonly route = inject(ActivatedRoute);
    private readonly router = inject(Router);
    private readonly decks = inject(OwnDecksApiService);
    private readonly api = inject(StudyApiService);
    private readonly recovery = inject(StudyRecoveryService);
    private readonly quietZone = inject(QuietZone);
    private readonly promoPopup = inject(PromoPopupService);
    readonly flow = inject(StudyAssessmentFlow);
    private readonly destroyRef = inject(DestroyRef);
    private readonly injector = inject(Injector);
    private readonly element: ElementRef<HTMLElement> = inject(ElementRef);
    readonly deckId: string;
    /** The server offers speech-to-text: a free response that takes speech shows «Ответить голосом». Fails closed. */
    readonly speechAvailable = signal(false);
    private presentedAt = 0;
    private disputeCommandId = '';
    /** The last dispute request may have reached the server (no answer, a 5xx): a retry must reuse its command id. */
    private disputeOutcomeUnknown = false;
    private assessmentStartedAt: number | null = null;
    private pollCount = 0;

    constructor() {
        // A task is open (answering, revealing, sending): new toasts wait. Feedback, the end of the session and
        // leaving the page are the natural pauses where they may show.
        effect(() => {
            const phase = this.phase();
            this.quietZone.set(TASK_OPEN.includes(phase));
            // Ask only after the completion phase has released the quiet zone, including an immediately answered request.
            if (phase === 'complete') untracked(() => void this.promoPopup.request());
        });
        // The grader may hand the learner to self-check while the waiting card is on screen.
        effect(() => {
            const stage = this.flow.stage();
            untracked(() => {
                if (stage === 'self-check' && this.phase() === 'assessing') {
                    this.phase.set('self-check');
                    this.focusAfterRender('#self-check-title');
                }
            });
        });
        this.destroyRef.onDestroy(() => this.quietZone.set(false));
        effect(onCleanup => {
            const sentinel = this.progressSentinel()?.nativeElement;
            const cursor = this.progressNextCursor();
            if (!sentinel || !cursor || this.progressLoading() || this.progressMoreError()) return;
            const observer = new IntersectionObserver(entries => {
                if (entries.some(entry => entry.isIntersecting)) this.loadMoreProgress();
            }, { rootMargin: '0px 0px 700px 0px' });
            observer.observe(sentinel);
            onCleanup(() => observer.disconnect());
        });
        const deckId = this.route.snapshot.paramMap.get('deckId');
        if (deckId === null) throw new Error('Study route requires deckId.');
        this.deckId = deckId.toLowerCase();
        inject(CapabilitiesApiService).read().pipe(catchError(() => of(CAPABILITIES_UNAVAILABLE)), takeUntilDestroyed())
            .subscribe(result => this.speechAvailable.set(result.speechToText.available));
        this.decks.detail(this.deckId).pipe(takeUntilDestroyed()).subscribe({
            next: deck => this.deck.set(deck),
            error: () => this.fail('Не удалось подтвердить выбранную колоду.')
        });
        this.loadSupportingState();
        const recovered = this.recovery.restore(this.deckId);
        if (recovered === null) this.phase.set('setup');
        else {
            this.pending.set(recovered.pending);
            this.assessmentStartedAt = recovered.assessmentStartedAt ?? null;
            this.submitted.set(recovered.pending?.response ?? null);
            this.resume(recovered.sessionId, recovered.pending !== null);
        }
    }

    /** Commits the learner's response for the current presentation. */
    answer(response: StudyResponse): void {
        if (this.phase() !== 'answering') return;
        this.submit(response);
    }

    setAnswerDirty(value: boolean): void { this.answerDirty.set(value); }

    /** Asks the server for one blank's first letter; the letter is never derived in the browser. */
    requestHint(blankId: string): void {
        const current = this.current();
        const session = this.session();
        if (this.phase() !== 'answering' || current?.type !== 'CLOZE' || session === null || this.hintPending() !== null
            || this.hints()[blankId] !== undefined) return;
        this.hintPending.set(blankId);
        this.api.hint(this.deckId, session.sessionId, current.presentationId, current.nonce, blankId)
            .pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
                next: result => {
                    this.hintPending.set(null);
                    this.session.update(value => value === null ? null : { ...value,
                        presentations: value.presentations.map(item => item.presentationId === result.presentationId
                            ? { ...item, hints: [...item.hints.filter(hint => hint.blankId !== result.blankId),
                                { blankId: result.blankId, firstLetter: result.firstLetter }] } : item) });
                },
                error: () => {
                    this.hintPending.set(null);
                    this.message.set('Не удалось получить подсказку. Попробуйте ещё раз.');
                }
            });
    }

    revealTranscript(): void {
        const current = this.current();
        const session = this.session();
        if (this.phase() !== 'answering' || current === null || session === null
            || current.transcriptRevealed || this.transcriptLoading()) return;
        this.transcriptLoading.set(true);
        this.api.revealTranscript(this.deckId, session.sessionId, current.presentationId, current.nonce, current.type)
            .pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
                next: reveal => {
                    this.session.update(value => value === null ? null : { ...value,
                        presentations: value.presentations.map(item => item.presentationId === reveal.presentationId
                            ? { ...item, ...reveal.content, transcriptRevealed: true } : item) });
                    this.transcriptLoading.set(false);
                },
                error: () => { this.transcriptLoading.set(false);
                    this.message.set('Не удалось открыть транскрипт. Попробуйте ещё раз.'); }
            });
    }

    heading(type: Mechanic): string {
        return ({ SELF_CHECK: 'Вспомните, затем сверьтесь', FREE_RESPONSE: 'Напишите ответ', CLOZE: 'Заполните пропуски',
            CHOICE: 'Выберите ответ', MATCH: 'Соедините пары', ORDER: 'Восстановите порядок', CATEGORIZE: 'Распределите по группам' })[type];
    }

    retryPending(): void {
        const command = this.pending();
        const session = this.session();
        if (command === null || session === null) return;
        this.send(session.sessionId, command);
    }

    reconcile(): void {
        const session = this.session();
        if (session !== null) this.resume(session.sessionId, true);
    }

    next(): void {
        const session = this.session();
        if (session === null || this.phase() !== 'feedback' || this.disputeStep() === 'sending') return;
        const recovered = this.recoveredContent() !== null;
        this.feedback.set(null);
        this.resetAnswer();
        if (recovered) {
            // The answered card was already gone from the queue: nothing to drop.
            this.apply(session, false);
            return;
        }
        const remaining = session.presentations.slice(1);
        if (remaining.length === 0) {
            this.phase.set('loading');
            this.api.refill(this.deckId, session.sessionId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
                next: refilled => refilled.status === 'PREPARING' ? this.poll(session.sessionId)
                    : this.apply(refilled, false),
                error: error => this.handle(error, 'Не удалось получить следующую порцию заданий.')
            });
            return;
        }
        this.apply({ ...session, presentations: remaining }, false);
    }

    retryStart(): void { this.recovery.clear(); this.startScheduled(this.scheduledPreset()); }
    pause(): void { void this.router.navigate(['/decks', this.deckId]); }

    chooseReplay(sessionId: string): void { this.selectedReplayId.set(sessionId); }
    startScheduled(preset: ScheduledStudyPreset): void {
        this.scheduledPreset.set(preset);
        this.start({ mode: 'SCHEDULED', preset });
    }
    setIncludeNewPractice(value: boolean): void { this.includeNewPractice.set(value); }
    setPracticeOrder(value: string): void {
        if (value === 'SEEDED' || value === 'WEAKEST_FIRST') this.practiceOrder.set(value);
    }

    startReplay(): void {
        const sourceSessionId = this.selectedReplayId();
        if (sourceSessionId !== null) this.start({ mode: 'REPLAY', sourceSessionId });
    }

    startPractice(): void {
        this.start({ mode: 'PRACTICE', includeNew: this.includeNewPractice(), order: this.practiceOrder() });
    }

    restartMaterial(material: MaterialProgress): void {
        const affected = material.objectiveCoverage.enabled;
        if (!window.confirm(`Начать заново этот материал и ${affected} ${this.objectiveWord(affected)}? `
            + 'История останется, а текущее расписание начнётся с нового этапа.')) return;
        this.message.set(null);
        this.api.restart(this.deckId, crypto.randomUUID(), [material.memberKey])
            .pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
                next: result => {
                    this.message.set(`Материал начат заново: ${result.value.objectiveCount} ${this.objectiveWord(result.value.objectiveCount)}.`);
                    this.loadProgress();
                },
                error: error => this.handleSupportError(error, 'Не удалось начать материал заново.')
            });
    }

    loadMoreProgress(): void {
        const cursor = this.progressNextCursor();
        if (cursor !== null && !this.progressLoading()) this.loadProgress(cursor, true);
    }

    loadMoreProgressRetry(): void { this.loadProgress(); }

    progressLabel(state: MaterialProgress['state']): string {
        return ({ NOT_STARTED: 'Не начат', LEARNING: 'В изучении', DUE: 'Пора повторить',
            ON_TRACK: 'По плану' })[state];
    }

    formatDate(value: string | null): string {
        return value === null ? 'нет данных' : new Intl.DateTimeFormat('ru-RU', {
            dateStyle: 'medium', timeStyle: 'short'
        }).format(new Date(value));
    }

    canLeave(): boolean {
        if (this.phase() !== 'submitting' && this.phase() !== 'unknown' && !this.answerDirty()) return true;
        return window.confirm('Сессия сохранена в этой вкладке. Выйти и продолжить её позже?');
    }

    feedbackTitle(outcome: AttemptOutcome): string { return feedbackTitle(outcome.feedback); }

    progressMessage(outcome: AttemptOutcome): string {
        if (outcome.disputed) return 'Оценка снята, прогресс не изменился.';
        const transition = outcome.transition;
        if (!transition) return 'Дополнительная практика помогает закрепить материал.';
        const delta = transition.afterLevel - transition.beforeLevel;
        if (delta < 0) return 'К этому материалу стоит вернуться: повторение поможет его закрепить.';
        if (delta > 1) return 'Заметный прогресс — вы всё увереннее вспоминаете этот материал.';
        if (delta > 0) return 'Есть прогресс — материал запоминается лучше.';
        return outcome.feedback.result === 'CORRECT'
            ? 'Вы закрепили материал. Уровень пока не изменился.'
            : 'Уровень пока не изменился. Дайте себе время и повторите материал.';
    }

    private start(intent: StudyStartIntent): void {
        this.recovery.clear();
        this.pending.set(null);
        this.feedback.set(null);
        this.session.set(null);
        this.resetAnswer();
        this.pollCount = 0;
        this.phase.set('loading');
        this.message.set(null);
        const commandId = crypto.randomUUID();
        this.api.start(this.deckId, commandId, intent).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
            next: result => this.acceptStarted(result.value),
            error: error => this.handle(error, 'Не удалось начать Study-сессию.')
        });
    }

    private acceptStarted(session: StudySession): void {
        this.recovery.save({ deckId: this.deckId, sessionId: session.sessionId, pending: null });
        if (session.status === 'PREPARING') this.poll(session.sessionId);
        else this.apply(session, false);
    }

    private poll(sessionId: string): void {
        this.phase.set('preparing');
        if (this.pollCount++ >= 20) {
            this.fail('Подготовка занимает дольше обычного. Сессию можно продолжить позже.');
            return;
        }
        timer(500).pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => this.api.read(this.deckId, sessionId)
            .pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
                next: session => session.status === 'PREPARING' ? this.poll(sessionId) : this.apply(session, false),
                error: error => this.handle(error, 'Не удалось проверить подготовку сессии.')
            }));
    }

    private resume(sessionId: string, reconciling: boolean): void {
        this.phase.set('loading');
        this.api.read(this.deckId, sessionId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
            next: session => {
                if (session.status === 'PREPARING') { this.poll(sessionId); return; }
                const pending = this.pending();
                if (reconciling && pending !== null
                    && !session.presentations.some(item => item.presentationId === pending.presentationId)) {
                    if (pending.response.kind === 'TEXT') { this.recoverGradedAttempt(session, sessionId, pending); return; }
                    this.forgetAccountedAnswer(sessionId);
                }
                this.apply(session, reconciling && this.pending() !== null);
            },
            error: error => {
                if (this.errorCode(error) === 'SESSION_EXPIRED') {
                    this.phase.set('expired'); this.recovery.clear();
                } else this.handle(error, 'Не удалось вернуться к занятию.');
            }
        });
    }

    /** The answer was counted but its card is gone from the queue: say so, as before. */
    private forgetAccountedAnswer(sessionId: string): void {
        this.pending.set(null);
        this.resetAnswer();
        this.recovery.save({ deckId: this.deckId, sessionId, pending: null });
        this.message.set('Предыдущий ответ уже учтён. Его результат пока недоступен, но прогресс не будет записан повторно.');
    }

    /**
     * A text answer whose card left the queue may be an explanation that was graded while the page was closed: read the attempt
     * and show its result (with «Оспорить оценку») or its self-check view. Only when it cannot be read does the old note appear.
     */
    private recoverGradedAttempt(session: ReadyStudySession, sessionId: string, pending: AttemptCommand): void {
        this.api.attempt(this.deckId, sessionId, pending.attemptId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
            next: state => {
                if (isAttemptOutcome(state) && !isFreeResponseFeedback(state.feedback)) {
                    this.forgetAccountedAnswer(sessionId);
                    this.apply(session, false);
                    return;
                }
                this.session.set(session);
                this.recoveredContent.set({ type: 'FREE_RESPONSE', content: { prompt: [], responseInput: 'TEXT' } });
                if (isAttemptOutcome(state)) this.showOutcome(sessionId, state);
                else this.enterAssessment(sessionId, state);
            },
            error: () => {
                this.forgetAccountedAnswer(sessionId);
                this.apply(session, false);
            }
        });
    }

    private resetAnswer(): void {
        this.flow.stop();
        this.recoveredContent.set(null);
        this.disputeOutcomeUnknown = false;
        this.disputeStep.set('idle');
        this.disputeProblem.set(null);
        this.disputeBlocked.set(false);
        this.submitted.set(null);
        this.answerDirty.set(false);
        this.hintPending.set(null);
        this.transcriptLoading.set(false);
    }

    private apply(session: ReadyStudySession, pendingUnknown: boolean): void {
        this.session.set(session);
        this.presentedAt = this.recovery.now();
        if (session.status === 'EMPTY') {
            this.phase.set('empty'); this.recovery.clear(); this.loadSupportingState(); return;
        }
        if (session.status === 'COMPLETE') {
            this.phase.set('complete'); this.recovery.clear(); this.loadSupportingState();
            return;
        }
        if (session.presentations.length === 0) {
            this.phase.set('unavailable');
            this.message.set('Пока не удалось продолжить занятие. Попробуйте восстановить его.');
            return;
        }
        this.recovery.save({ deckId: this.deckId, sessionId: session.sessionId, pending: this.pending(),
            assessmentStartedAt: this.assessmentStartedAt });
        const graded = session.presentations[0].assessment;
        if (graded !== undefined) {
            this.resumeAssessment(session.sessionId, graded.attemptId);
            return;
        }
        if (pendingUnknown) {
            this.phase.set('unknown');
            this.message.set('Результат предыдущего ответа пока неизвестен. Повторите отправку того же ответа или проверьте занятие.');
            return;
        }
        this.phase.set('answering');
        this.focusAfterRender('[data-answer-control]');
    }

    private submit(response: StudyResponse): void {
        const presentation = this.current();
        const session = this.session();
        if (presentation === null || session === null) return;
        const command: AttemptCommand = {
            attemptId: crypto.randomUUID(), presentationId: presentation.presentationId, nonce: presentation.nonce,
            response, confidence: null,
            durationMs: Math.min(3_600_000, Math.max(0, this.recovery.now() - this.presentedAt))
        };
        this.pending.set(command);
        this.submitted.set(response);
        this.recovery.save({ deckId: this.deckId, sessionId: session.sessionId, pending: command });
        this.send(session.sessionId, command);
    }

    private send(sessionId: string, command: AttemptCommand): void {
        this.phase.set('submitting');
        this.message.set(null);
        this.api.submit(this.deckId, sessionId, command).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
            next: result => this.accept(sessionId, result.value),
            error: error => {
                const code = this.errorCode(error);
                if (error instanceof HttpErrorResponse && error.status === 0) {
                    this.phase.set('unknown');
                    this.message.set('Связь оборвалась после отправки. Повторите отправку того же ответа.');
                } else if (code === 'IDEMPOTENCY_CONFLICT') {
                    this.phase.set('conflict');
                    this.message.set('На это задание уже ответили. Проверьте занятие: новый ответ не отправлен.');
                } else if (code === 'SESSION_EXPIRED' || code === 'PRESENTATION_EXPIRED') {
                    this.phase.set('expired'); this.recovery.clear();
                } else this.handle(error, 'Не удалось принять ответ. Он остался в этой вкладке.');
            }
        });
    }

    /** A stored outcome ends the answer; an answer still in assessment keeps its command so a reload resumes it. */
    private accept(sessionId: string, state: AttemptState): void {
        if (isAttemptOutcome(state)) {
            this.showOutcome(sessionId, state);
            return;
        }
        this.assessmentStartedAt = this.recovery.now();
        this.recovery.save({ deckId: this.deckId, sessionId, pending: this.pending(), assessmentStartedAt: this.assessmentStartedAt });
        this.enterAssessment(sessionId, state);
    }

    private enterAssessment(sessionId: string, state: AssessingAttempt | SelfCheckAttempt): void {
        this.flow.begin(this.deckId, sessionId, state, outcome => this.showOutcome(sessionId, outcome), error => this.assessmentLost(error));
        this.phase.set(isAssessing(state) ? 'assessing' : 'self-check');
        this.focusAfterRender(isAssessing(state) ? '#assessing-title' : '#self-check-title');
    }

    /** Polling cannot continue: an expired session is the same page as everywhere, anything else keeps the answer in the tab. */
    private assessmentLost(error: unknown): void {
        const code = this.errorCode(error);
        if (code === 'SESSION_EXPIRED' || code === 'PRESENTATION_EXPIRED') {
            this.phase.set('expired'); this.recovery.clear();
        } else this.handle(error, 'Не удалось узнать результат проверки. Ответ принят и остался в этой вкладке: проверьте занятие.');
    }

    private resumeAssessment(sessionId: string, attemptId: string): void {
        const pending = this.pending();
        if (pending !== null && pending.attemptId !== attemptId) {
            this.pending.set(null);
            this.submitted.set(null);
            this.assessmentStartedAt = null;
            this.recovery.save({ deckId: this.deckId, sessionId, pending: null });
        }
        const elapsed = this.assessmentStartedAt === null ? 0 : this.recovery.now() - this.assessmentStartedAt;
        this.flow.resume(this.deckId, sessionId, attemptId, outcome => this.showOutcome(sessionId, outcome),
            error => this.assessmentLost(error), elapsed);
        this.phase.set('assessing');
        this.focusAfterRender('#assessing-title');
    }

    private showOutcome(sessionId: string, outcome: AttemptOutcome): void {
        this.pending.set(null);
        this.assessmentStartedAt = null;
        this.recovery.save({ deckId: this.deckId, sessionId, pending: null });
        this.disputeStep.set('idle');
        this.disputeProblem.set(null);
        this.disputeBlocked.set(false);
        this.feedback.set(outcome);
        this.phase.set('feedback');
        this.focusAfterRender('#feedback-title');
    }

    /** «Оспорить оценку», step 1: ask in place (not a modal) what taking the grade back means. */
    askDispute(): void {
        if (this.phase() !== 'feedback' || !this.disputable() || this.disputeStep() !== 'idle') return;
        if (!this.disputeOutcomeUnknown) this.disputeCommandId = crypto.randomUUID();
        this.disputeProblem.set(null);
        this.disputeStep.set('confirm');
        this.focusAfterRender('[data-dispute-confirm]');
    }

    cancelDispute(): void {
        if (this.disputeStep() !== 'confirm') return;
        this.disputeStep.set('idle');
        this.focusAfterRender('[data-dispute-open]');
    }

    /** Step 2: the same command id is reused on a retry, so a lost reply can never take two grades back. */
    confirmDispute(): void {
        const outcome = this.feedback();
        const session = this.session();
        if (this.disputeStep() !== 'confirm' || outcome === null || session === null) return;
        this.disputeStep.set('sending');
        this.disputeProblem.set(null);
        this.api.dispute(this.deckId, session.sessionId, outcome.attemptId, this.disputeCommandId)
            .pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
                next: result => this.disputed(result.value),
                error: error => {
                    const unknown = !(error instanceof HttpErrorResponse) || error.status === 0 || error.status >= 500;
                    if (this.errorCode(error) === 'DISPUTE_NOT_ALLOWED') {
                        // After a request whose answer was lost the grade may already be taken back: read it instead of guessing.
                        if (this.disputeOutcomeUnknown) this.rereadDisputed(session.sessionId, outcome.attemptId);
                        else this.disputeRefused();
                        return;
                    }
                    if (unknown) this.disputeOutcomeUnknown = true;
                    this.disputeStep.set('confirm');
                    this.disputeProblem.set('Не удалось снять оценку. Прогресс не изменился; попробуйте ещё раз.');
                    this.focusAfterRender('[data-dispute-confirm]');
                }
            });
    }

    private disputed(outcome: AttemptOutcome): void {
        this.disputeOutcomeUnknown = false;
        this.disputeStep.set('idle');
        this.disputeProblem.set(null);
        this.feedback.set(outcome);
        this.focusAfterRender('#feedback-title');
    }

    private disputeRefused(): void {
        this.disputeStep.set('idle');
        this.disputeBlocked.set(true);
        this.disputeProblem.set('Эту оценку уже нельзя снять: после неё прогресс изменился.');
        this.focusAfterRender('#feedback-title');
    }

    private rereadDisputed(sessionId: string, attemptId: string): void {
        this.api.attempt(this.deckId, sessionId, attemptId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
            next: state => { if (isAttemptOutcome(state) && state.disputed) this.disputed(state); else this.disputeRefused(); },
            error: () => {
                this.disputeStep.set('confirm');
                this.disputeProblem.set('Не удалось проверить, снята ли оценка. Попробуйте ещё раз.');
                this.focusAfterRender('[data-dispute-confirm]');
            }
        });
    }

    private focusAfterRender(selector: string): void {
        afterNextRender({ write: () => this.element.nativeElement.querySelector<HTMLElement>(selector)?.focus() },
            { injector: this.injector });
    }

    private handle(error: unknown, fallback: string): void {
        this.phase.set('error');
        const code = this.errorCode(error);
        this.message.set(code === 'RESOURCE_NOT_FOUND' ? 'Колода или сессия недоступна этому аккаунту.' : fallback);
    }

    private loadSupportingState(): void {
        this.supportLoading.set(true);
        this.loadProgress();
        this.api.replaySources(this.deckId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
            next: sources => {
                this.replaySources.set(sources.items);
                const current = this.selectedReplayId();
                this.selectedReplayId.set(sources.items.some(item => item.sessionId === current)
                    ? current : sources.items[0]?.sessionId ?? null);
                this.supportLoading.set(false);
            },
            error: () => { this.replaySources.set([]); this.supportLoading.set(false); }
        });
    }

    private loadProgress(cursor: string | null = null, append = false): void {
        this.progressLoading.set(true);
        this.progressMoreError.set(false);
        this.api.progress(this.deckId, 20, cursor).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
            next: page => {
                this.progress.set(append ? [...this.progress(), ...page.items] : page.items);
                this.progressNextCursor.set(page.nextCursor);
                this.progressUnavailable.set(false);
                this.progressLoading.set(false);
            },
            error: () => {
                if (!append) this.progress.set([]);
                if (append) this.progressMoreError.set(true);
                else this.progressUnavailable.set(true);
                this.progressLoading.set(false);
            }
        });
    }

    private handleSupportError(error: unknown, fallback: string): void {
        const code = this.errorCode(error);
        this.message.set(code === 'RESOURCE_NOT_FOUND' ? 'Материал больше не доступен в этой колоде.' : fallback);
    }

    private objectiveWord(value: number): string {
        const mod10 = value % 10;
        const mod100 = value % 100;
        if (mod10 === 1 && mod100 !== 11) return 'цель';
        if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) return 'цели';
        return 'целей';
    }

    private errorCode(error: unknown): string | null {
        if (!(error instanceof HttpErrorResponse) || typeof error.error !== 'object' || error.error === null) return null;
        const code = (error.error as Record<string, unknown>)['code'];
        return typeof code === 'string' ? code : null;
    }

    private fail(message: string): void { this.phase.set('error'); this.message.set(message); }
}

export function canLeaveStudySession(component: StudySessionPageComponent): boolean { return component.canLeave(); }
