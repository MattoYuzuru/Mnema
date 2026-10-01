import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, Injector, afterNextRender, computed, effect, inject, signal, viewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { map, timer } from 'rxjs';

import { Mechanic } from '../../content/exercise/exercise-content.models';
import { MnemaSelectComponent, MnemaSelectOption } from '../../core/controls/mnema-select.component';
import { LearnerExerciseComponent, PairChecker } from './learner-exercise.component';
import { LearnerFeedbackComponent, feedbackTitle } from './learner-feedback.component';
import { OwnDecksApiService } from '../own-decks/own-decks-api.service';
import { OwnDeck } from '../own-decks/own-deck.models';
import { StudyApiService } from './study-api.service';
import {
    AttemptCommand,
    AttemptOutcome,
    MaterialProgress,
    PracticeOrder,
    ReadyStudySession,
    ReplaySource,
    ScheduledStudyPreset,
    StudyResponse,
    StudySession,
    StudyStartIntent
} from './study.models';
import { StudyRecoveryService } from './study-recovery.service';

type Phase = 'setup' | 'loading' | 'preparing' | 'answering' | 'revealed' | 'submitting' | 'feedback'
    | 'unknown' | 'conflict' | 'empty' | 'complete' | 'expired' | 'unavailable' | 'error';

@Component({
    selector: 'app-study-session-page',
    imports: [RouterLink, MnemaSelectComponent, LearnerExerciseComponent, LearnerFeedbackComponent],
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
    private readonly destroyRef = inject(DestroyRef);
    private readonly injector = inject(Injector);
    private readonly element: ElementRef<HTMLElement> = inject(ElementRef);
    readonly deckId: string;
    private presentedAt = 0;
    private pollCount = 0;

    constructor() {
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
        this.decks.detail(this.deckId).pipe(takeUntilDestroyed()).subscribe({
            next: deck => this.deck.set(deck),
            error: () => this.fail('Не удалось подтвердить выбранную колоду.')
        });
        this.loadSupportingState();
        const recovered = this.recovery.restore(this.deckId);
        if (recovered === null) this.phase.set('setup');
        else {
            this.pending.set(recovered.pending);
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
            CHOICE: 'Выберите ответ', MATCH: 'Соедините пары' })[type];
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
        if (session === null || this.phase() !== 'feedback') return;
        this.feedback.set(null);
        this.resetAnswer();
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
                    this.pending.set(null);
                    this.resetAnswer();
                    this.recovery.save({ deckId: this.deckId, sessionId, pending: null });
                    this.message.set('Предыдущий ответ уже учтён. Его результат пока недоступен, но прогресс не будет записан повторно.');
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

    private resetAnswer(): void {
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
            this.phase.set('complete'); this.recovery.clear(); this.loadSupportingState(); return;
        }
        if (session.presentations.length === 0) {
            this.phase.set('unavailable');
            this.message.set('Пока не удалось продолжить занятие. Попробуйте восстановить его.');
            return;
        }
        this.recovery.save({ deckId: this.deckId, sessionId: session.sessionId, pending: this.pending() });
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
            next: result => {
                this.pending.set(null);
                this.recovery.save({ deckId: this.deckId, sessionId, pending: null });
                this.feedback.set(result.value);
                this.phase.set('feedback');
                this.focusAfterRender('#feedback-title');
            },
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
