import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { timer } from 'rxjs';

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
    SelfRating,
    StudyPresentation,
    StudyResponse,
    StudySession,
    StudyStartIntent
} from './study.models';
import { StudyRecoveryService } from './study-recovery.service';

type Phase = 'loading' | 'preparing' | 'answering' | 'revealed' | 'submitting' | 'feedback'
    | 'unknown' | 'conflict' | 'empty' | 'complete' | 'expired' | 'unavailable' | 'error';

@Component({
    selector: 'app-study-session-page',
    imports: [RouterLink],
    templateUrl: './study-session-page.component.html',
    styleUrl: './study-session-page.component.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class StudySessionPageComponent {
    readonly deck = signal<OwnDeck | null>(null);
    readonly session = signal<ReadyStudySession | null>(null);
    readonly phase = signal<Phase>('loading');
    readonly typedAnswer = signal('');
    readonly selectedOptionId = signal<string | null>(null);
    readonly clozeHintUsed = signal(false);
    readonly feedback = signal<AttemptOutcome | null>(null);
    readonly message = signal<string | null>(null);
    readonly pending = signal<AttemptCommand | null>(null);
    readonly progress = signal<readonly MaterialProgress[]>([]);
    readonly progressNextCursor = signal<string | null>(null);
    readonly progressUnavailable = signal(false);
    readonly replaySources = signal<readonly ReplaySource[]>([]);
    readonly selectedReplayId = signal<string | null>(null);
    readonly includeNewPractice = signal(false);
    readonly practiceOrder = signal<PracticeOrder>('SEEDED');
    readonly supportLoading = signal(true);
    readonly current = computed(() => this.session()?.presentations[0] ?? null);
    readonly position = computed(() => {
        const current = this.current();
        const session = this.session();
        return current === null || session === null ? null : `${current.ordinal + 1} из ${current.ordinal + session.presentations.length}`;
    });

    private readonly route = inject(ActivatedRoute);
    private readonly router = inject(Router);
    private readonly decks = inject(OwnDecksApiService);
    private readonly api = inject(StudyApiService);
    private readonly recovery = inject(StudyRecoveryService);
    private readonly destroyRef = inject(DestroyRef);
    private readonly element: ElementRef<HTMLElement> = inject(ElementRef);
    readonly deckId: string;
    private presentedAt = 0;
    private pollCount = 0;

    constructor() {
        const deckId = this.route.snapshot.paramMap.get('deckId');
        if (deckId === null) throw new Error('Study route requires deckId.');
        this.deckId = deckId.toLowerCase();
        this.decks.detail(this.deckId).pipe(takeUntilDestroyed()).subscribe({
            next: deck => this.deck.set(deck),
            error: () => this.fail('Не удалось подтвердить выбранную колоду.')
        });
        this.loadSupportingState();
        const recovered = this.recovery.restore(this.deckId);
        if (recovered === null) this.start();
        else {
            this.pending.set(recovered.pending);
            if (recovered.pending?.response.kind === 'TEXT') this.typedAnswer.set(recovered.pending.response.text);
            if (recovered.pending?.response.kind === 'CHOICE') {
                this.selectedOptionId.set(recovered.pending.response.optionId);
            }
            this.clozeHintUsed.set(recovered.pending?.hintsUsed.includes('REVEAL_FIRST_GRAPHEME') ?? false);
            this.resume(recovered.sessionId, recovered.pending !== null);
        }
    }

    setTypedAnswer(value: string): void { this.typedAnswer.set(value); }

    reveal(): void {
        if (this.phase() !== 'answering' || this.current()?.type !== 'SELF_CHECK') return;
        this.phase.set('revealed');
        queueMicrotask(() => this.element.nativeElement.querySelector<HTMLElement>('[data-first-rating]')?.focus());
    }

    submitTyped(): void {
        const type = this.current()?.type;
        if (this.phase() !== 'answering' || (type !== 'TYPED' && type !== 'CLOZE_SINGLE')) return;
        this.submit({ kind: 'TEXT', text: this.typedAnswer() },
            type === 'CLOZE_SINGLE' && this.clozeHintUsed() ? ['REVEAL_FIRST_GRAPHEME'] : []);
    }

    showClozeHint(): void {
        if (this.phase() === 'answering' && this.current()?.type === 'CLOZE_SINGLE') this.clozeHintUsed.set(true);
    }

    firstGrapheme(value: string): string {
        const first = new Intl.Segmenter(undefined, { granularity: 'grapheme' }).segment(value)[Symbol.iterator]().next();
        return first.done ? '' : first.value.segment;
    }

    selectOption(optionId: string): void { this.selectedOptionId.set(optionId); }

    submitChoice(): void {
        const optionId = this.selectedOptionId();
        if (this.phase() !== 'answering' || this.current()?.type !== 'SINGLE_CHOICE' || optionId === null) return;
        this.submit({ kind: 'CHOICE', optionId }, []);
    }

    rate(rating: SelfRating): void {
        if (this.phase() !== 'revealed' || this.current()?.type !== 'SELF_CHECK') return;
        this.submit({ kind: 'SELF_CHECK', rating }, ['REVEAL']);
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
        this.typedAnswer.set('');
        this.selectedOptionId.set(null);
        this.clozeHintUsed.set(false);
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

    retryStart(): void { this.recovery.clear(); this.start(); }
    pause(): void { void this.router.navigate(['/decks', this.deckId]); }

    chooseReplay(sessionId: string): void { this.selectedReplayId.set(sessionId); }
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
        if (cursor !== null) this.loadProgress(cursor, true);
    }

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
        if (this.phase() !== 'submitting' && this.phase() !== 'unknown' && this.typedAnswer().length === 0) return true;
        return window.confirm('Сессия сохранена в этой вкладке. Выйти и продолжить её позже?');
    }

    feedbackTitle(outcome: AttemptOutcome): string {
        return ({ CORRECT: 'Верно', PARTIAL: 'Частично', UNSURE: 'Неуверенно', INCORRECT: 'Нужно повторить',
            NOT_ASSESSED: 'Без оценки', UNAVAILABLE: 'Проверка недоступна' })[outcome.feedback.result];
    }

    ruleName(rule: string): string {
        return ({ UNICODE_NFC: 'единая форма Unicode', TRIM: 'пробелы по краям не учитываются',
            CASE_FOLD: 'регистр не учитывается', SINGLE_BLANK: 'проверен один пропуск',
            SERVER_ISSUED_OPTION: 'выбран серверный вариант', SELF_REPORT: 'самооценка после показа ответа'
        } as Record<string, string>)[rule] ?? rule;
    }

    ratingLabel(rating: SelfRating): string {
        return ({ NOT_RECALLED: 'Не вспомнил', HINTED: 'Вспомнил с подсказкой',
            PARTIAL: 'Вспомнил частично', FULL: 'Вспомнил полностью' })[rating];
    }

    private start(intent: StudyStartIntent = { mode: 'SCHEDULED' }): void {
        this.recovery.clear();
        this.pending.set(null);
        this.feedback.set(null);
        this.session.set(null);
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
                    this.recovery.save({ deckId: this.deckId, sessionId, pending: null });
                    this.message.set('Сервер уже принял предыдущую попытку. Результат не восстановлен, но прогресс не будет записан повторно.');
                }
                this.apply(session, reconciling && this.pending() !== null);
            },
            error: error => {
                if (this.errorCode(error) === 'SESSION_EXPIRED') {
                    this.phase.set('expired'); this.recovery.clear();
                } else this.handle(error, 'Не удалось восстановить Study-сессию.');
            }
        });
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
            this.message.set('Текущая порция завершена, но сервер ещё не выдал продолжение. Попробуйте восстановить сессию.');
            return;
        }
        this.recovery.save({ deckId: this.deckId, sessionId: session.sessionId, pending: this.pending() });
        if (pendingUnknown) {
            this.phase.set('unknown');
            this.message.set('Исход предыдущего запроса неизвестен. Повторите ровно ту же попытку или сверьтесь с сервером.');
            return;
        }
        const type = session.presentations[0].type;
        this.phase.set('answering');
        queueMicrotask(() => this.element.nativeElement.querySelector<HTMLElement>('[data-answer-control]')?.focus());
    }

    private submit(response: StudyResponse, hintsUsed: readonly string[]): void {
        const presentation = this.current();
        const session = this.session();
        if (presentation === null || session === null) return;
        const command: AttemptCommand = {
            attemptId: crypto.randomUUID(), presentationId: presentation.presentationId, nonce: presentation.nonce,
            response, hintsUsed, confidence: null,
            durationMs: Math.min(3_600_000, Math.max(0, this.recovery.now() - this.presentedAt))
        };
        this.pending.set(command);
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
                queueMicrotask(() => this.element.nativeElement.querySelector<HTMLElement>('#feedback-title')?.focus());
            },
            error: error => {
                const code = this.errorCode(error);
                if (error instanceof HttpErrorResponse && error.status === 0) {
                    this.phase.set('unknown');
                    this.message.set('Связь оборвалась после отправки. Не меняйте ответ: безопасно повторите ту же попытку.');
                } else if (code === 'IDEMPOTENCY_CONFLICT') {
                    this.phase.set('conflict');
                    this.message.set('Эта карточка уже завершена другой попыткой. Сверьтесь с сервером — новый ответ не отправлен.');
                } else if (code === 'SESSION_EXPIRED' || code === 'PRESENTATION_EXPIRED') {
                    this.phase.set('expired'); this.recovery.clear();
                } else this.handle(error, 'Сервер отклонил попытку. Ответ сохранён в этой вкладке.');
            }
        });
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
        this.api.progress(this.deckId, 100, cursor).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
            next: page => {
                this.progress.set(append ? [...this.progress(), ...page.items] : page.items);
                this.progressNextCursor.set(page.nextCursor);
                this.progressUnavailable.set(false);
            },
            error: () => {
                if (!append) this.progress.set([]);
                this.progressNextCursor.set(null);
                this.progressUnavailable.set(true);
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
