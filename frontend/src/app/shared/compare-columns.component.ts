import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, input, model } from '@angular/core';

import { UPDATE_TEXT } from './copy-text';
import type { CompareSide } from './document-changes';
import { SegmentedChoiceComponent, SegmentedOption } from './segmented-choice.component';

let nextColumns = 0;

/**
 * «Сейчас у вас» | «Вариант автора»: the two versions of one unit of an update side by side. Each column is a named group with a
 * heading, in reading order mine then author's, and holds what the page projects into `[compare-mine]` and `[compare-author]` (the
 * same renderer Browse uses, see `app-compared-document`, or an exercise view). A side that does not exist says so in words instead:
 * «Нет у вас» (`mineAbsent`) or «Автор удалил» (`authorAbsent`), with one plain sentence.
 *
 * Mobile first: a column container narrower than 44 rem shows one side at a time and a switch made of `app-segmented-choice` (native
 * radios: arrows, Tab and the live «Показана ваша версия» come from it); from 44 rem the two columns stand side by side and the
 * switch is gone (`display: none`, out of the focus order). The width is the container's, so the element behaves the same in a narrow
 * panel of a wide screen. Switching changes nothing but visibility: no slide, no animation, so reduced motion needs no special case.
 *
 * Known edge: if the container widens past 44 rem while a radio of the switch has the focus, the switch disappears (`display: none`)
 * and the browser drops the focus to the page; nothing moves it to a column. A resize with the switch focused is rare and the reading
 * position is not lost, so this is accepted, not handled.
 *
 * Headings: the column headings are at `headingLevel`; an `app-compared-document` inside reads it (the columns are its injector parent)
 * and draws its own headings from the next level down, so the outline neither jumps nor repeats a level.
 */
@Component({
    selector: 'app-compare-columns',
    imports: [NgTemplateOutlet, SegmentedChoiceComponent],
    template: `
      <div class="compare" [attr.data-side]="shown()">
        <div class="switch">
          <app-segmented-choice [legend]="text.switchLegend" [options]="options" [value]="shown()" (valueChange)="pick($event)" />
        </div>
        <div class="columns">
          <div class="column" role="group" data-column="mine" [attr.aria-labelledby]="mineId">
            <ng-template #mineTitle>{{ text.mine }}</ng-template>
            @switch (headingLevel()) {
              @case (3) { <h3 class="eyebrow" [id]="mineId"><ng-container [ngTemplateOutlet]="mineTitle" /></h3> }
              @case (5) { <h5 class="eyebrow" [id]="mineId"><ng-container [ngTemplateOutlet]="mineTitle" /></h5> }
              @default { <h4 class="eyebrow" [id]="mineId"><ng-container [ngTemplateOutlet]="mineTitle" /></h4> }
            }
            @if (mineAbsent()) {
              <div class="absent"><p class="absent-title">{{ text.absentMine }}</p><p class="hint">{{ text.absentMineNote }}</p></div>
            } @else {
              <ng-content select="[compare-mine]" />
            }
          </div>
          <div class="column" role="group" data-column="author" [attr.aria-labelledby]="authorId">
            <ng-template #authorTitle>{{ text.author }}</ng-template>
            @switch (headingLevel()) {
              @case (3) { <h3 class="eyebrow" [id]="authorId"><ng-container [ngTemplateOutlet]="authorTitle" /></h3> }
              @case (5) { <h5 class="eyebrow" [id]="authorId"><ng-container [ngTemplateOutlet]="authorTitle" /></h5> }
              @default { <h4 class="eyebrow" [id]="authorId"><ng-container [ngTemplateOutlet]="authorTitle" /></h4> }
            }
            @if (authorAbsent()) {
              <div class="absent"><p class="absent-title">{{ text.absentAuthor }}</p><p class="hint">{{ text.absentAuthorNote }}</p></div>
            } @else {
              <ng-content select="[compare-author]" />
            }
          </div>
        </div>
      </div>
    `,
    styles: [`
      :host { display: block; container-type: inline-size; min-inline-size: 0; }
      .switch { margin-block-end: 1rem; }
      .columns { display: grid; gap: 1.25rem; min-inline-size: 0; }
      .column { min-inline-size: 0; border-block-start: 2px solid var(--mn-ink); padding-block-start: .75rem; }
      .column .eyebrow { margin-block-end: .75rem; }
      .absent { display: grid; gap: .25rem; border: 1px dashed var(--mn-field-border); padding: .75rem 1rem; }
      .absent-title, .absent .hint { margin: 0; }
      .absent-title { color: var(--mn-ink); font-weight: 700; }
      /* Narrow: one side at a time, chosen by the switch. */
      @container (width < 44rem) {
        .compare[data-side='mine'] .column[data-column='author'],
        .compare[data-side='author'] .column[data-column='mine'] { display: none; }
      }
      /* Wide: both sides, the switch is not needed and leaves the focus order. */
      @container (width >= 44rem) {
        .switch { display: none; }
        .columns { grid-template-columns: repeat(2, minmax(0, 1fr)); gap: clamp(1.25rem, 3vw, 2.5rem); }
      }
      @media (forced-colors: active) {
        .column { border-block-start-color: CanvasText; }
        .absent { border-color: CanvasText; }
      }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class CompareColumnsComponent {
    /**
     * The side shown on a narrow screen; the switch changes it. While the page has not set it, a unit missing on one side starts on the
     * other: on the author's version when `mineAbsent`, on the reader's when `authorAbsent` (so the first thing seen is not the empty side).
     */
    readonly side = model<CompareSide | null>(null);
    /** The unit does not exist in the reader's copy yet: «Нет у вас». */
    readonly mineAbsent = input(false);
    /** The author deleted the unit: «Автор удалил». */
    readonly authorAbsent = input(false);
    /** The level of the two column headings (3 to 5), one below the heading of the unit. */
    readonly headingLevel = input<3 | 4 | 5>(4);

    protected readonly text = UPDATE_TEXT;
    protected readonly shown = computed<CompareSide>(() => this.side() ?? (this.mineAbsent() && !this.authorAbsent() ? 'author' : 'mine'));
    protected readonly mineId = `mn-compare-${nextColumns}-mine`;
    protected readonly authorId = `mn-compare-${nextColumns++}-author`;
    protected readonly options: readonly SegmentedOption<CompareSide>[] = [
        { value: 'mine', label: UPDATE_TEXT.mine, hint: UPDATE_TEXT.showingMine },
        { value: 'author', label: UPDATE_TEXT.author, hint: UPDATE_TEXT.showingAuthor }
    ];

    protected pick(value: CompareSide | null): void { if (value !== null) this.side.set(value); }
}
