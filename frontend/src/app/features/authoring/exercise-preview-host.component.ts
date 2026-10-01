import {
    ChangeDetectionStrategy, Component, DestroyRef, ElementRef, Injector, afterNextRender, computed, effect, inject, input, signal, untracked
} from '@angular/core';
import { Subscription, tap } from 'rxjs';

import { demoAwareResolver } from '../../content/exercise/demo/demo-media';
import { LearnerContent } from '../../content/exercise/exercise-content.models';
import { MatchPair } from '../../content/exercise/match-board.component';
import { LearnerExerciseComponent, PairChecker } from '../study/learner-exercise.component';
import { LearnerFeedbackComponent, feedbackTitle } from '../study/learner-feedback.component';
import { MEDIA_PLAYBACK_RESOLVER } from '../study/media-playback-resolver';
import { AttemptFeedback, StudyResponse } from '../study/study.models';
import { ExercisePreviewApiService } from './exercise-preview-api.service';
import { PreviewMode, PreviewPresentation } from './exercise-preview.models';

interface TrialResult {
    readonly feedback: AttemptFeedback;
    readonly response: StudyResponse;
    /** The content the answer was given against, so the result can show it again even if the form changed since. */
    readonly content: LearnerContent;
}

const CAPTIONS: Readonly<Record<PreviewMode, string>> = {
    DEMO: 'Это пример упражнения. Заполните шаги ниже — здесь появится ваше задание.',
    AUTHOR_DRAFT: 'Так ученик увидит ваше упражнение. Проверить ответ можно, когда оно будет заполнено.',
    AUTHOR_READY: 'Пройдите своё упражнение как ученик: ответ проверяет та же логика, что и на занятии. Ничего не сохраняется и не влияет на расписание.'
};
const BADGES: Readonly<Record<PreviewMode, string>> = { DEMO: 'Пример', AUTHOR_DRAFT: 'Ваше задание', AUTHOR_READY: 'Ваше задание' };

/**
 * The preview area of the exercise editor: the real learner components in an isolated host. It shows either a
 * demo, the author's unfinished draft, or the author's finished exercise, and evaluates only through the author
 * preview endpoint. Nothing here reads or writes the draft; any change of what is shown starts a fresh trial
 * and a late answer for an older trial is dropped.
 */
@Component({
    selector: 'app-exercise-preview-host',
    imports: [LearnerExerciseComponent, LearnerFeedbackComponent],
    // Demo assets play from the bundle; every other asset keeps the real, owner-authorized resolver.
    providers: [{ provide: MEDIA_PLAYBACK_RESOLVER, useFactory: () => demoAwareResolver(inject(MEDIA_PLAYBACK_RESOLVER, { skipSelf: true })) }],
    templateUrl: './exercise-preview-host.component.html',
    styleUrl: './exercise-preview-host.component.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class ExercisePreviewHostComponent {
    readonly presentation = input.required<PreviewPresentation>();

    /** Increments on every restart or change of what is shown; also the reset key of the answer surface. */
    readonly attempt = signal(0);
    readonly revealed = signal(false);
    readonly hints = signal<Readonly<Record<string, string>>>({});
    readonly hintPending = signal<string | null>(null);
    readonly busy = signal(false);
    readonly result = signal<TrialResult | null>(null);
    readonly error = signal<string | null>(null);

    readonly mode = computed(() => this.presentation().mode);
    readonly caption = computed(() => CAPTIONS[this.mode()]);
    readonly badge = computed(() => BADGES[this.mode()]);
    readonly content = computed(() => this.presentation().learner(this.revealed()));
    readonly title = computed(() => { const value = this.result(); return value === null ? '' : feedbackTitle(value.feedback); });
    /** Pair checks go to the author preview endpoint; an incomplete draft has nothing to check against. */
    readonly pairChecker = computed<PairChecker | null>(() => {
        const exercise = this.presentation().exercise;
        const attempt = this.attempt();
        if (exercise === null) return null;
        return (pair: MatchPair) => this.api.checkPair(exercise, pair.leftId, pair.rightId).pipe(tap(correct => {
            if (!correct && attempt === this.attempt()) this.pairMistakes = true;
        }));
    });

    private readonly api = inject(ExercisePreviewApiService);
    private readonly destroyRef = inject(DestroyRef);
    private readonly injector = inject(Injector);
    private readonly element = inject<ElementRef<HTMLElement>>(ElementRef);
    private inflight: Subscription | null = null;
    private hintFlight: Subscription | null = null;
    private pairMistakes = false;

    constructor() {
        const key = computed(() => this.presentation().key);
        effect(() => { key(); untracked(() => this.restart()); });
        this.destroyRef.onDestroy(() => { this.inflight?.unsubscribe(); this.hintFlight?.unsubscribe(); });
    }

    /** «Начать заново»: only the trial is reset, never the form or any schedule. */
    restart(explicit = false): void {
        this.inflight?.unsubscribe();
        this.inflight = null;
        this.hintFlight?.unsubscribe();
        this.hintFlight = null;
        this.attempt.update(value => value + 1);
        this.revealed.set(false);
        this.hints.set({});
        this.hintPending.set(null);
        this.busy.set(false);
        this.result.set(null);
        this.error.set(null);
        this.pairMistakes = false;
        if (explicit) this.focusAfterRender('[data-answer-control]');
    }

    answer(response: StudyResponse): void {
        const exercise = this.presentation().exercise;
        if (exercise === null || response.kind === 'CANCEL' || this.busy()) return;
        const attempt = this.attempt();
        const content = this.content();
        this.busy.set(true);
        this.error.set(null);
        this.inflight?.unsubscribe();
        this.inflight = this.api.submit(exercise, { response, hintedBlankIds: Object.keys(this.hints()),
            pairMistakes: this.pairMistakes, transcriptRevealed: this.revealed() }).subscribe({
            next: feedback => {
                if (attempt !== this.attempt()) return;
                this.busy.set(false);
                this.result.set({ feedback, response, content });
                this.focusAfterRender('#preview-result-title');
            },
            error: () => {
                if (attempt !== this.attempt()) return;
                this.busy.set(false);
                this.error.set('Не удалось проверить ответ. Ваше упражнение не изменилось: проверьте соединение и попробуйте ещё раз.');
            }
        });
    }

    requestHint(blankId: string): void {
        const exercise = this.presentation().exercise;
        if (exercise === null || this.hintPending() !== null || this.hints()[blankId] !== undefined) return;
        const attempt = this.attempt();
        this.hintPending.set(blankId);
        this.error.set(null);
        this.hintFlight?.unsubscribe();
        this.hintFlight = this.api.hint(exercise, blankId).subscribe({
            next: letter => {
                if (attempt !== this.attempt()) return;
                this.hintPending.set(null);
                this.hints.update(current => ({ ...current, [blankId]: letter }));
            },
            error: () => {
                if (attempt !== this.attempt()) return;
                this.hintPending.set(null);
                this.error.set('Не удалось получить подсказку. Попробуйте ещё раз.');
            }
        });
    }

    /** Transcripts belong to the author's own draft, so revealing one is local. */
    revealTranscript(): void { this.revealed.set(true); }

    private focusAfterRender(selector: string): void {
        afterNextRender({ write: () => this.element.nativeElement.querySelector<HTMLElement>(selector)?.focus({ preventScroll: true }) },
            { injector: this.injector });
    }
}
