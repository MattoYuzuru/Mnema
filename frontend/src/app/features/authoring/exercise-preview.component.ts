import { ChangeDetectionStrategy, Component, computed, input, signal } from '@angular/core';
import { of } from 'rxjs';

import { Mechanic } from '../../content/exercise/exercise-content.models';
import { MatchPair } from '../../content/exercise/match-board.component';
import { LearnerExerciseComponent, PairChecker } from '../study/learner-exercise.component';
import { ExerciseDrafts, SlotContext, isPreviewPair, previewContent } from './exercise-draft';

/**
 * Local author preview. It mounts the same learner component as Study with the current draft: nothing is
 * written, no evaluation request is made, and pair checks and hints are answered from the draft itself.
 */
@Component({
    selector: 'app-exercise-preview',
    imports: [LearnerExerciseComponent],
    template: `
      <p class="eyebrow">Предпросмотр · так увидит ученик</p>
      <p class="hint">Здесь ничего не сохраняется и не оценивается.</p>
      <app-learner-exercise [exercise]="content()" [transcriptRevealed]="revealed()" [hints]="hints()"
        [resetKey]="previewKey()" [canSubmit]="false" [pairChecker]="pairChecker" idPrefix="preview"
        (hintRequested)="hint($event)" (transcriptRequested)="revealed.set(true)" />
    `,
    styles: [`
      :host { display: grid; gap: .75rem; min-inline-size: 0; }
      .eyebrow { margin: 0; color: var(--mn-ink); font: 600 .75rem/1.4 var(--mn-font-mono, ui-monospace, monospace); letter-spacing: .09em; text-transform: uppercase; }
      .hint { margin: 0; color: var(--mn-muted); font-size: .9rem; }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class ExercisePreviewComponent {
    readonly type = input.required<Mechanic>();
    readonly drafts = input.required<ExerciseDrafts>();
    readonly context = input.required<SlotContext>();
    readonly revealed = signal(false);
    readonly hints = signal<Readonly<Record<string, string>>>({});

    readonly content = computed(() => previewContent(this.type(), this.drafts(), this.context(), this.revealed()));
    /** A new mechanic or a new set of items starts the preview over; typing text keeps it live. */
    readonly previewKey = computed(() => {
        const drafts = this.drafts();
        switch (this.type()) {
            case 'MATCH': return 'MATCH:' + drafts.MATCH.pairs.map(pair => pair.left.itemId + pair.right.itemId).join();
            case 'CLOZE': return 'CLOZE:' + drafts.CLOZE.blanks.map(blank => blank.blankId).join();
            case 'CHOICE': return 'CHOICE:' + drafts.CHOICE.options.map(option => option.optionId).join() + drafts.CHOICE.selectionMode;
            default: return this.type();
        }
    });
    readonly pairChecker: PairChecker = (pair: MatchPair) => of(isPreviewPair(this.drafts(), pair.leftId, pair.rightId));

    /** Preview-only: derives the letter from the author's own draft. Study always asks the server. */
    hint(blankId: string): void {
        const blank = this.drafts().CLOZE.blanks.find(entry => entry.blankId === blankId);
        const first = blank?.answer.rows[0]?.value.normalize('NFC') ?? '';
        const segment = new Intl.Segmenter(undefined, { granularity: 'grapheme' }).segment(first)[Symbol.iterator]().next();
        if (!segment.done) this.hints.update(current => ({ ...current, [blankId]: segment.value.segment }));
    }
}
