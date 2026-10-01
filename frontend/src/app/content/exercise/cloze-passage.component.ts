import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';

import { LearnerClozeBlank, LearnerClozeSegment } from './exercise-content.models';

export interface ClozeBlankVerdict {
    readonly correct: boolean;
    readonly hinted: boolean;
    readonly reference: string;
}

interface SegmentView {
    readonly segment: LearnerClozeSegment;
    /** 1-based position among blanks; 0 for text segments. */
    readonly number: number;
}

/**
 * Passage with inline per-blank inputs. Each blank is addressed by its own id, so repeated words never
 * share an answer. A hint letter shown here is always the one the server returned; nothing is derived here.
 */
@Component({
    selector: 'app-cloze-passage',
    template: `
      <div class="cloze-passage" role="group" [attr.aria-label]="'Текст с пропусками'">
        @for (view of views(); track $index) {
          @if (view.segment.kind === 'TEXT') {
            <span class="cloze-text">{{ view.segment.text }}</span>
          } @else {
            <span class="cloze-blank" [class.is-correct]="verdicts()?.[view.segment.blankId]?.correct === true"
                  [class.is-wrong]="verdicts()?.[view.segment.blankId]?.correct === false">
              <input type="text" autocomplete="off" autocapitalize="off" autocorrect="off" spellcheck="false"
                [id]="idPrefix() + '-' + view.segment.blankId"
                [attr.data-answer-control]="view.number === 1 ? '' : null"
                [attr.aria-label]="'Пропуск ' + view.number + ' из ' + total()"
                [style.inline-size]="width(view.segment)"
                [value]="values()[view.segment.blankId] ?? ''" [readOnly]="readOnly()"
                (input)="valueChange.emit({ blankId: view.segment.blankId, text: $any($event.target).value })" />
              @if (view.segment.firstLetterHint && !readOnly()) {
                @if (hints()[view.segment.blankId]; as letter) {
                  <span class="cloze-letter" role="status">Первая буква: <strong>{{ letter }}</strong></span>
                } @else {
                  <button type="button" class="cloze-hint" [disabled]="hintPending() === view.segment.blankId"
                    [attr.aria-label]="'Первая буква, пропуск ' + view.number"
                    (click)="hintRequested.emit(view.segment.blankId)">Первая буква</button>
                }
              }
              @if (verdicts()?.[view.segment.blankId]; as verdict) {
                <span class="cloze-verdict">
                  {{ verdict.correct ? 'Верно' : 'Неверно' }}@if (!verdict.correct) {, ответ: <strong>{{ verdict.reference }}</strong>}@if (verdict.hinted) {, с подсказкой}
                </span>
              }
            </span>
          }
        }
      </div>
    `,
    styles: [`
      :host { display: block; min-inline-size: 0; }
      .cloze-passage { white-space: pre-wrap; overflow-wrap: anywhere; line-height: 2.6; }
      .cloze-blank { display: inline-flex; flex-wrap: wrap; align-items: center; gap: .35rem; max-inline-size: 100%; white-space: normal; vertical-align: middle; line-height: 1.4; }
      .cloze-blank input { min-inline-size: 4rem; max-inline-size: 100%; min-block-size: var(--mn-touch-min, 2.75rem); box-sizing: border-box;
        border: 0; border-block-end: 2px solid var(--mn-ink); border-radius: 0; padding: .3rem .4rem; color: inherit; background: var(--mn-soft);
        font: 1rem/1.4 var(--mn-font-mono, ui-monospace, monospace); }
      .cloze-blank input:focus-visible, .cloze-hint:focus-visible { outline: 3px solid var(--mn-focus, var(--mn-ink)); outline-offset: 2px; }
      .cloze-hint { min-block-size: var(--mn-touch-min, 2.75rem); border: 1px solid var(--mn-ink); padding: .3rem .7rem; color: var(--mn-ink);
        background: transparent; font: 600 .85rem/1.2 var(--mn-font-body, system-ui, sans-serif); cursor: pointer; }
      .cloze-hint:disabled { opacity: .6; cursor: progress; }
      .cloze-letter, .cloze-verdict { font-size: .9rem; color: var(--mn-muted); }
      .is-correct input { border-block-end-color: var(--mn-ink); }
      .is-wrong input { border-block-end-color: var(--mn-danger); }
      .is-wrong .cloze-verdict { color: var(--mn-danger); }
      @media (forced-colors: active) { .cloze-blank input { border: 1px solid CanvasText; } }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class ClozePassageComponent {
    readonly passage = input.required<readonly LearnerClozeSegment[]>();
    readonly values = input<Readonly<Partial<Record<string, string>>>>({});
    /** Server-recorded first letters by blank id. */
    readonly hints = input<Readonly<Partial<Record<string, string>>>>({});
    readonly hintPending = input<string | null>(null);
    readonly verdicts = input<Readonly<Record<string, ClozeBlankVerdict>> | null>(null);
    readonly readOnly = input(false);
    readonly idPrefix = input('cloze');
    readonly valueChange = output<{ readonly blankId: string; readonly text: string }>();
    readonly hintRequested = output<string>();

    readonly total = computed(() => this.passage().filter(segment => segment.kind === 'BLANK').length);
    readonly views = computed<readonly SegmentView[]>(() => {
        let number = 0;
        return this.passage().map(segment => ({ segment, number: segment.kind === 'BLANK' ? ++number : 0 }));
    });

    width(blank: LearnerClozeBlank): string { return `calc(${Math.max(blank.size.length, 3)}ch + 1.6rem)`; }
}
