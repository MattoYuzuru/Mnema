import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { ChoiceListComponent } from '../../content/exercise/choice-list.component';
import { ClozeBlankVerdict, ClozePassageComponent } from '../../content/exercise/cloze-passage.component';
import { ExclusivePlaybackDirective } from '../../content/exercise/exclusive-playback.directive';
import { LearnerContent } from '../../content/exercise/exercise-content.models';
import { LearnerBlocksComponent } from '../../content/exercise/learner-blocks.component';
import {
    AttemptFeedback, SelfRating, StudyResponse, isChoiceFeedback, isClozeFeedback, isFreeResponseFeedback, isMatchFeedback, isUnassessed
} from './study.models';

const RATING_LABELS: Readonly<Record<SelfRating, string>> = {
    NOT_RECALLED: 'Не вспомнил', HINTED: 'Вспомнил с подсказкой', PARTIAL: 'Вспомнил частично', FULL: 'Вспомнил полностью'
};
const TITLES = { CORRECT: 'Верно', PARTIAL: 'Частично', UNSURE: 'Неуверенно', INCORRECT: 'Нужно повторить',
    NOT_ASSESSED: 'Без оценки', UNAVAILABLE: 'Проверка недоступна' } as const;

/** Heading of one evaluation result, shared by Study and the author preview. */
export function feedbackTitle(feedback: AttemptFeedback): string { return TITLES[feedback.result]; }

/**
 * The evaluated result of one attempt next to the learner's own answer: per-blank verdicts, the correct
 * options, every pair, or the reference. Study and the author preview render the same feedback with it;
 * the host decides the heading, the progress note and what happens next.
 */
@Component({
    selector: 'app-learner-feedback',
    imports: [LearnerBlocksComponent, ClozePassageComponent, ChoiceListComponent, ExclusivePlaybackDirective],
    template: `
      <div class="feedback" appExclusivePlayback>
        @if (unassessed(); as value) {
          @if (value.reasonCodes.includes('MEDIA_NOT_READY')) {
            <p class="notice" role="status">Запись стала недоступна. Ответ не оценён и не изменил расписание; попробуйте упражнение позже.</p>
          } @else {
            <p class="notice" role="status">Проверка сейчас недоступна. Это не ошибка ученика: ответ не изменил расписание.</p>
          }
        }
        @if (retryNote()) {
          <p class="notice">Все пары найдены. Поскольку были ошибки при подборе, стоит ещё раз вернуться к этим парам позже.</p>
        }

        @switch (content().type) {
          @case ('SELF_CHECK') {
            @if (rating(); as value) { <p class="reference-line"><strong>Ваша оценка:</strong> {{ ratingLabel(value) }}</p> }
          }
          @case ('FREE_RESPONSE') {
            @if (freeResponse(); as value) {
              <dl class="comparison">
                <div><dt>Ваш ответ</dt><dd class="answer-text">{{ submittedText() || 'Пустой ответ' }}</dd></div>
                <div><dt>Эталон</dt><dd class="answer-text">{{ value.reference }}</dd></div>
              </dl>
              @if (value.referenceContent.length > 0) {
                <app-learner-blocks [blocks]="value.referenceContent" nameSuffix=" в эталоне" />
              }
            }
          }
          @case ('CLOZE') {
            @if (clozeContent(); as value) {
              <app-cloze-passage [passage]="value.passage" [values]="submittedClozeValues()" [verdicts]="clozeVerdicts()"
                [readOnly]="true" [idPrefix]="idPrefix() + '-blank'" />
            }
          }
          @case ('CHOICE') {
            @if (choiceContent(); as value) {
              <app-choice-list [options]="value.options" [selectionMode]="value.selectionMode"
                [selected]="submittedOptionIds()" [disabled]="true" [idPrefix]="idPrefix() + '-choice'"
                [correctIds]="choice()?.correctOptionIds ?? null" />
            }
          }
          @case ('MATCH') {
            @if (match(); as value) {
              <ul class="pair-feedback">
                @for (pair of value.pairs; track pair.leftId) {
                  <li>
                    <p><strong>{{ pair.correct ? 'Верно' : 'Проверьте' }}</strong></p>
                    @if (matchItem('left', pair.leftId); as item) { <app-learner-blocks [blocks]="item.blocks" nameSuffix=", слева" /> }
                    <p class="pair-arrow" aria-hidden="true">↓</p>
                    @if (matchItem('right', pair.correctRightId); as item) { <app-learner-blocks [blocks]="item.blocks" nameSuffix=", справа" /> }
                  </li>
                }
              </ul>
            }
          }
        }
      </div>
    `,
    styles: [`
      :host { display: block; min-inline-size: 0; }
      .feedback { display: grid; gap: 1rem; min-inline-size: 0; }
      p { margin: 0; }
      .notice { border-inline-start: 4px solid var(--mn-ink); padding: .8rem 1rem; background: var(--mn-soft); }
      .pair-feedback { display: grid; gap: .9rem; margin: 0; padding: 0; list-style: none; }
      .pair-feedback li { min-inline-size: 0; display: grid; gap: .5rem; border-block-start: 1px solid var(--mn-rule); padding-block-start: .75rem; }
      .pair-arrow { color: var(--mn-muted); }
      .answer-text { white-space: pre-wrap; overflow-wrap: anywhere; }
      .comparison { display: grid; grid-template-columns: 1fr 1fr; gap: 1rem; margin: 0; }
      .comparison div { min-inline-size: 0; border-block-start: 1px solid var(--mn-rule); padding-block-start: .75rem; }
      .comparison dt { color: var(--mn-muted); font-size: .8rem; }
      .comparison dd { margin: .4rem 0 0; white-space: pre-wrap; overflow-wrap: anywhere; }
      @media (max-width: 36rem) { .comparison { grid-template-columns: 1fr; } }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class LearnerFeedbackComponent {
    /** The content the learner answered; match items and cloze segments are shown again next to the result. */
    readonly content = input.required<LearnerContent>();
    readonly feedback = input.required<AttemptFeedback>();
    readonly submitted = input<StudyResponse | null>(null);
    readonly idPrefix = input('feedback');

    readonly unassessed = computed(() => { const value = this.feedback(); return isUnassessed(value) ? value : null; });
    readonly cloze = computed(() => { const value = this.feedback(); return isClozeFeedback(value) ? value : null; });
    readonly choice = computed(() => { const value = this.feedback(); return isChoiceFeedback(value) ? value : null; });
    readonly match = computed(() => { const value = this.feedback(); return isMatchFeedback(value) ? value : null; });
    readonly freeResponse = computed(() => { const value = this.feedback(); return isFreeResponseFeedback(value) ? value : null; });
    readonly retryNote = computed(() => {
        const value = this.feedback();
        return !isUnassessed(value) && value.appliedRules.includes('PAIR_RETRY');
    });
    readonly clozeContent = computed(() => { const value = this.content(); return value.type === 'CLOZE' ? value.content : null; });
    readonly choiceContent = computed(() => { const value = this.content(); return value.type === 'CHOICE' ? value.content : null; });
    readonly matchContent = computed(() => { const value = this.content(); return value.type === 'MATCH' ? value.content : null; });
    readonly clozeVerdicts = computed<Readonly<Record<string, ClozeBlankVerdict>> | null>(() => {
        const value = this.cloze();
        return value === null ? null : Object.fromEntries(value.blanks.map(blank => [blank.blankId,
            { correct: blank.correct, hinted: blank.hinted, reference: blank.reference }]));
    });
    readonly submittedText = computed(() => { const value = this.submitted(); return value?.kind === 'TEXT' ? value.text : ''; });
    readonly submittedClozeValues = computed<Readonly<Record<string, string>>>(() => {
        const value = this.submitted();
        return value?.kind === 'CLOZE' ? Object.fromEntries(value.blanks.map(blank => [blank.blankId, blank.text])) : {};
    });
    readonly submittedOptionIds = computed(() => { const value = this.submitted(); return value?.kind === 'CHOICE' ? value.optionIds : []; });
    readonly rating = computed(() => { const value = this.submitted(); return value?.kind === 'SELF_CHECK' ? value.rating : null; });

    ratingLabel(rating: SelfRating): string { return RATING_LABELS[rating]; }

    /** Item of the answered MATCH content, used to show a pair's media again in the result. */
    matchItem(side: 'left' | 'right', itemId: string) {
        const content = this.matchContent();
        return (side === 'left' ? content?.left : content?.right)?.find(item => item.itemId === itemId) ?? null;
    }
}
