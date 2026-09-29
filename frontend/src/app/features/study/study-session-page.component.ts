import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, Injector, afterNextRender, computed, effect, inject, signal, viewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { timer } from 'rxjs';

import { SignedMediaSource } from '../../content/rendering/media-playback.api';
import { NativeMediaPlayerComponent } from '../../content/rendering/native-media-player.component';
import { MnemaSelectComponent, MnemaSelectOption } from '../../core/controls/mnema-select.component';
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
    SelfRating,
    StudyPresentation,
    StudyResponse,
    StudySession,
    StudyStartIntent
} from './study.models';
import { StudyRecoveryService } from './study-recovery.service';
import { MEDIA_PLAYBACK_RESOLVER } from './media-playback-resolver';

type Phase = 'setup' | 'loading' | 'preparing' | 'answering' | 'revealed' | 'submitting' | 'feedback'
    | 'unknown' | 'conflict' | 'empty' | 'complete' | 'expired' | 'unavailable' | 'error';

@Component({
    selector: 'app-study-session-page',
    imports: [RouterLink, NativeMediaPlayerComponent, MnemaSelectComponent],
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
    readonly typedAnswer = signal('');
    readonly selectedOptionId = signal<string | null>(null);
    readonly matchSelections = signal<Readonly<Partial<Record<string, string>>>>({});
    readonly audioUrls = signal<Readonly<Record<string, SignedMediaSource>>>({});
    readonly transcriptLoading = signal(false);
    readonly clozeHintUsed = signal(false);
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
    readonly matchOptions = computed<readonly MnemaSelectOption[]>(() => [
        { value: '', label: 'Выберите вариант' },
        ...(this.current()?.options ?? []).map(option => ({ value: option.optionId, label: option.text }))
    ]);
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
    private readonly playback = inject(MEDIA_PLAYBACK_RESOLVER);
    private readonly destroyRef = inject(DestroyRef);
    private readonly injector = inject(Injector);
    private readonly element: ElementRef<HTMLElement> = inject(ElementRef);
    readonly deckId: string;
    private presentedAt = 0;
    private pollCount = 0;
    private audioTimer: ReturnType<typeof setTimeout> | null = null;
    private audioEpoch = 0;

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
        const recheckAudio = () => {
            if (document.visibilityState === 'visible' && navigator.onLine) this.refreshAudio();
        };
        document.addEventListener('visibilitychange', recheckAudio);
        window.addEventListener('focus', recheckAudio);
        window.addEventListener('online', recheckAudio);
        this.destroyRef.onDestroy(() => {
            document.removeEventListener('visibilitychange', recheckAudio);
            window.removeEventListener('focus', recheckAudio);
            window.removeEventListener('online', recheckAudio);
            this.clearAudioTimer();
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
            if (recovered.pending?.response.kind === 'TEXT') this.typedAnswer.set(recovered.pending.response.text);
            if (recovered.pending?.response.kind === 'CHOICE') {
                this.selectedOptionId.set(recovered.pending.response.optionId);
            }
            if (recovered.pending?.response.kind === 'MATCH') {
                this.matchSelections.set(Object.fromEntries(recovered.pending.response.pairs
                    .map(pair => [pair.cueId, pair.optionId])));
            }
            this.clozeHintUsed.set(recovered.pending?.hintsUsed.includes('REVEAL_FIRST_GRAPHEME') ?? false);
            this.resume(recovered.sessionId, recovered.pending !== null);
        }
    }

    setTypedAnswer(value: string): void { this.typedAnswer.set(value); }

    reveal(): void {
        if (this.phase() !== 'answering' || this.current()?.type !== 'SELF_CHECK') return;
        this.phase.set('revealed');
        this.focusAfterRender('[data-first-rating]');
    }

    submitTyped(): void {
        const type = this.current()?.type;
        if (this.phase() !== 'answering' || (type !== 'TYPED' && type !== 'CLOZE_SINGLE'
            && type !== 'LISTEN_TYPE')) return;
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
        if (this.phase() !== 'answering' || (this.current()?.type !== 'SINGLE_CHOICE'
            && this.current()?.type !== 'LISTEN_CHOICE') || optionId === null) return;
        this.submit({ kind: 'CHOICE', optionId }, []);
    }

    selectMatch(cueId: string, optionId: string): void {
        this.matchSelections.update(current => ({ ...Object.fromEntries(Object.entries(current)
            .filter(([key, value]) => key === cueId || value !== optionId)), [cueId]: optionId }));
    }

    submitMatch(): void {
        const current = this.current();
        if (this.phase() !== 'answering' || current?.type !== 'AUDIO_TEXT_MATCH'
            || current.prompt.kind !== 'AUDIO_MATCH') return;
        const pairs = current.prompt.cues.map(cue => ({ cueId: cue.cueId,
            optionId: this.matchSelections()[cue.cueId] ?? '' }));
        if (pairs.some(pair => !pair.optionId) || new Set(pairs.map(pair => pair.optionId)).size !== pairs.length) return;
        this.submit({ kind: 'MATCH', pairs }, []);
    }

    revealTranscript(): void {
        const current = this.current();
        const session = this.session();
        if (this.phase() !== 'answering' || current === null || session === null
            || current.prompt.kind === 'TEXT' || !current.prompt.transcriptAvailable
            || current.prompt.transcriptRevealed || this.transcriptLoading()) return;
        this.transcriptLoading.set(true);
        this.api.revealTranscript(this.deckId, session.sessionId, current.presentationId, current.nonce)
            .pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
                next: prompt => {
                    this.session.update(value => value === null ? null : { ...value,
                        presentations: value.presentations.map(item => item.presentationId === current.presentationId
                            ? { ...item, prompt } : item) });
                    this.transcriptLoading.set(false);
                },
                error: () => { this.transcriptLoading.set(false);
                    this.message.set('Не удалось открыть транскрипт. Попробуйте ещё раз.'); }
            });
    }

    promptTitle(presentation: StudyPresentation): string {
        return presentation.prompt.kind === 'TEXT' ? presentation.prompt.text : presentation.prompt.instruction;
    }

    audioAssetIds(presentation: StudyPresentation): readonly string[] {
        return presentation.prompt.kind === 'AUDIO_ASSET' ? [presentation.prompt.assetId]
            : presentation.prompt.kind === 'AUDIO_MATCH' ? presentation.prompt.cues.map(cue => cue.assetId) : [];
    }
    audioReady(presentation: StudyPresentation): boolean {
        return this.audioAssetIds(presentation).every(id => !!this.audioUrls()[id]);
    }
    matchComplete(presentation: StudyPresentation): boolean {
        return presentation.prompt.kind === 'AUDIO_MATCH'
            && presentation.prompt.cues.every(cue => !!this.matchSelections()[cue.cueId]);
    }
    optionText(presentation: StudyPresentation, optionId: string): string {
        return presentation.options.find(option => option.optionId === optionId)?.text ?? 'Вариант недоступен';
    }
    cueTitle(presentation: StudyPresentation, cueId: string): string {
        return presentation.prompt.kind === 'AUDIO_MATCH'
            ? presentation.prompt.cues.find(cue => cue.cueId === cueId)?.title ?? 'Запись' : 'Запись';
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
        this.matchSelections.set({});
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
        if (this.phase() !== 'submitting' && this.phase() !== 'unknown' && this.typedAnswer().length === 0
            && Object.keys(this.matchSelections()).length === 0) return true;
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

    private start(intent: StudyStartIntent): void {
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

    private apply(session: ReadyStudySession, pendingUnknown: boolean): void {
        this.session.set(session);
        this.resolveAudio(session.presentations[0] ?? null);
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

    private resolveAudio(presentation: StudyPresentation | null): void {
        this.audioEpoch += 1;
        this.clearAudioTimer();
        this.audioUrls.set({});
        if (presentation !== null) this.refreshAudio();
    }

    retryAudio(assetId: string): void {
        if (!this.audioUrls()[assetId]) return;
        this.audioUrls.update(values => Object.fromEntries(Object.entries(values).filter(([id]) => id !== assetId)));
        this.refreshAudio();
    }

    private refreshAudio(): void {
        const presentation = this.current();
        if (presentation === null || document.visibilityState !== 'visible' || !navigator.onLine) return;
        const epoch = this.audioEpoch;
        this.clearAudioTimer();
        for (const assetId of this.audioAssetIds(presentation)) {
            this.playback.resolve(assetId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
                next: source => {
                    if (epoch !== this.audioEpoch || this.current()?.presentationId !== presentation.presentationId) return;
                    this.audioUrls.update(values => source === null
                        ? Object.fromEntries(Object.entries(values).filter(([id]) => id !== assetId))
                        : { ...values, [assetId]: source });
                    this.scheduleAudioRefresh();
                },
                error: () => {
                    if (epoch !== this.audioEpoch) return;
                    this.audioUrls.update(values => Object.fromEntries(Object.entries(values).filter(([id]) => id !== assetId)));
                    this.scheduleAudioRefresh();
                }
            });
        }
    }

    private scheduleAudioRefresh(): void {
        this.clearAudioTimer();
        const presentation = this.current();
        if (presentation === null || document.visibilityState !== 'visible' || !navigator.onLine) return;
        const ids = this.audioAssetIds(presentation);
        if (ids.length === 0) return;
        const missing = ids.some(id => !this.audioUrls()[id]);
        const expiry = ids.map(id => Date.parse(this.audioUrls()[id]?.expiresAt ?? '') - Date.now() - 60_000)
            .filter(Number.isFinite);
        const delay = missing ? 15_000 : Math.max(1_000, Math.min(15 * 60_000, ...expiry));
        this.audioTimer = setTimeout(() => { this.audioTimer = null; this.refreshAudio(); }, delay);
    }

    private clearAudioTimer(): void {
        if (this.audioTimer) clearTimeout(this.audioTimer);
        this.audioTimer = null;
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
