import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, Injector, afterNextRender, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { Observable, Subscription } from 'rxjs';

import { ChoiceListComponent } from '../../content/exercise/choice-list.component';
import { ClozePassageComponent } from '../../content/exercise/cloze-passage.component';
import { ExclusivePlaybackDirective } from '../../content/exercise/exclusive-playback.directive';
import { LearnerBlock, LearnerContent, allLearnerBlocks } from '../../content/exercise/exercise-content.models';
import { LearnerBlocksComponent } from '../../content/exercise/learner-blocks.component';
import { MatchBoardComponent, MatchPair } from '../../content/exercise/match-board.component';
import { SelfRating, StudyResponse } from './study.models';

export type PairChecker = (pair: MatchPair) => Observable<boolean>;

const RATINGS: readonly SelfRating[] = ['NOT_RECALLED', 'HINTED', 'PARTIAL', 'FULL'];
const RATING_LABELS: Readonly<Record<SelfRating, string>> = {
    NOT_RECALLED: 'Не вспомнил', HINTED: 'Вспомнил с подсказкой', PARTIAL: 'Вспомнил частично', FULL: 'Вспомнил полностью'
};
const VOICE_REASON = 'Голосовой ответ пока недоступен: распознавание речи не подключено. Напишите ответ текстом.';

/**
 * The learner answer surface for all five mechanics. It owns the in-progress input only; the host owns
 * server calls (hints, transcript, pair check, submit) so a late response can never overwrite a newer
 * input. Study and the author preview mount the same component with different hosts.
 */
@Component({
    selector: 'app-learner-exercise',
    imports: [LearnerBlocksComponent, ClozePassageComponent, ChoiceListComponent, MatchBoardComponent, ExclusivePlaybackDirective],
    templateUrl: './learner-exercise.component.html',
    styleUrl: './learner-exercise.component.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class LearnerExerciseComponent {
    readonly exercise = input.required<LearnerContent>();
    readonly transcriptRevealed = input(false);
    /** Server-recorded first letters by blank id. */
    readonly hints = input<Readonly<Record<string, string>>>({});
    readonly hintPending = input<string | null>(null);
    readonly transcriptLoading = input(false);
    readonly busy = input(false);
    /**
     * Why the answer cannot be checked yet (an unfinished author preview). While set, every submit and rating
     * control is disabled and the reason is shown next to them.
     */
    readonly blockedReason = input<string | null>(null);
    readonly pairChecker = input<PairChecker | null>(null);
    readonly idPrefix = input('exercise');
    /** A new key (another presentation, another set of items) discards the input and any pending pair check. */
    readonly resetKey = input('');

    readonly answered = output<StudyResponse>();
    readonly hintRequested = output<string>();
    readonly transcriptRequested = output<void>();
    readonly dirtyChange = output<boolean>();

    readonly text = signal('');
    readonly clozeValues = signal<Readonly<Record<string, string>>>({});
    readonly selectedOptionIds = signal<readonly string[]>([]);
    readonly matches = signal<Readonly<Record<string, string>>>({});
    readonly wrongPair = signal<MatchPair | null>(null);
    readonly pairChecking = signal(false);
    readonly pairError = signal(false);
    readonly revealed = signal(false);

    readonly ratings = RATINGS;
    readonly voiceReason = VOICE_REASON;

    readonly selfCheck = computed(() => { const value = this.exercise(); return value.type === 'SELF_CHECK' ? value.content : null; });
    readonly freeResponse = computed(() => { const value = this.exercise(); return value.type === 'FREE_RESPONSE' ? value.content : null; });
    readonly cloze = computed(() => { const value = this.exercise(); return value.type === 'CLOZE' ? value.content : null; });
    readonly choice = computed(() => { const value = this.exercise(); return value.type === 'CHOICE' ? value.content : null; });
    readonly match = computed(() => { const value = this.exercise(); return value.type === 'MATCH' ? value.content : null; });
    readonly prompt = computed(() => this.exercise().content.prompt);
    /**
     * A transcript is offered while one is available and not yet revealed. For a self-check the hidden
     * reference does not count: offering its transcript before the answer is shown would reveal the answer.
     */
    readonly transcriptOffered = computed(() => {
        const value = this.exercise();
        const blocks: readonly LearnerBlock[] = value.type === 'SELF_CHECK' && !this.revealed() ? value.content.prompt : allLearnerBlocks(value);
        return !this.transcriptRevealed()
            && blocks.some(block => (block.kind === 'AUDIO' || block.kind === 'VIDEO') && block.transcriptAvailable);
    });
    readonly matchComplete = computed(() => {
        const content = this.match();
        return content !== null && content.left.every(item => this.matches()[item.itemId] !== undefined);
    });
    readonly dirty = computed(() => this.text().length > 0 || Object.values(this.clozeValues()).some(value => value.length > 0)
        || this.selectedOptionIds().length > 0 || Object.keys(this.matches()).length > 0 || this.revealed());

    private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
    private readonly injector = inject(Injector);
    private readonly destroyRef = inject(DestroyRef);
    private pairCheck: Subscription | null = null;
    private lastKey: string | null = null;

    constructor() {
        effect(() => {
            const dirty = this.dirty();
            if (!this.destroyRef.destroyed) this.dirtyChange.emit(dirty);
        });
        effect(() => {
            const key = this.resetKey();
            untracked(() => {
                if (this.lastKey !== null && this.lastKey !== key) this.reset();
                this.lastKey = key;
            });
        });
        this.destroyRef.onDestroy(() => this.pairCheck?.unsubscribe());
    }

    ratingLabel(rating: SelfRating): string { return RATING_LABELS[rating]; }

    reveal(): void {
        if (this.revealed()) return;
        this.revealed.set(true);
        this.focusAfterRender('[data-first-rating]');
    }

    rate(rating: SelfRating): void {
        if (this.busy() || !this.revealed() || this.blockedReason() !== null) return;
        this.answered.emit({ kind: 'SELF_CHECK', rating });
    }

    setClozeValue(change: { readonly blankId: string; readonly text: string }): void {
        this.clozeValues.update(values => ({ ...values, [change.blankId]: change.text }));
    }

    toggleOption(optionId: string): void {
        const multiple = this.choice()?.selectionMode === 'MULTIPLE';
        this.selectedOptionIds.update(values => multiple
            ? values.includes(optionId) ? values.filter(value => value !== optionId) : [...values, optionId]
            : [optionId]);
    }

    checkPair(pair: MatchPair): void {
        const check = this.pairChecker();
        if (check === null || this.pairChecking() || this.matches()[pair.leftId] !== undefined) return;
        this.pairChecking.set(true);
        this.pairError.set(false);
        this.wrongPair.set(null);
        this.pairCheck?.unsubscribe();
        this.pairCheck = check(pair).subscribe({
            next: correct => {
                this.pairChecking.set(false);
                if (!correct) { this.wrongPair.set(pair); return; }
                this.matches.update(current => ({ ...current, [pair.leftId]: pair.rightId }));
                if (this.matchComplete()) this.focusAfterRender('[data-submit]');
            },
            error: () => { this.pairChecking.set(false); this.pairError.set(true); }
        });
    }

    /** Builds the exact response for the current mechanic and hands it to the host. */
    submit(): void {
        if (this.busy() || this.blockedReason() !== null) return;
        const value = this.exercise();
        switch (value.type) {
            case 'FREE_RESPONSE':
                this.answered.emit({ kind: 'TEXT', text: this.text() });
                return;
            case 'CLOZE':
                this.answered.emit({ kind: 'CLOZE', blanks: value.content.passage.flatMap(segment => segment.kind === 'BLANK'
                    ? [{ blankId: segment.blankId, text: this.clozeValues()[segment.blankId] ?? '' }] : []) });
                return;
            case 'CHOICE':
                if (this.selectedOptionIds().length > 0) {
                    // Keep the issued option order so the same selection always serializes identically.
                    this.answered.emit({ kind: 'CHOICE', optionIds: value.content.options.map(option => option.optionId)
                        .filter(optionId => this.selectedOptionIds().includes(optionId)) });
                }
                return;
            case 'MATCH':
                if (this.matchComplete()) {
                    this.answered.emit({ kind: 'MATCH', pairs: value.content.left.map(item => ({
                        leftId: item.itemId, rightId: this.matches()[item.itemId]! })) });
                }
                return;
            case 'SELF_CHECK':
                return;
        }
    }

    private reset(): void {
        this.pairCheck?.unsubscribe();
        this.pairCheck = null;
        this.text.set('');
        this.clozeValues.set({});
        this.selectedOptionIds.set([]);
        this.matches.set({});
        this.wrongPair.set(null);
        this.pairChecking.set(false);
        this.pairError.set(false);
        this.revealed.set(false);
    }

    private focusAfterRender(selector: string): void {
        afterNextRender({ write: () => this.host.nativeElement.querySelector<HTMLElement>(selector)?.focus() },
            { injector: this.injector });
    }
}
