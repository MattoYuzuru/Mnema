import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, input, model } from '@angular/core';

import { UPDATE_TEXT, UpdateChoice, UpdateUnitClass, UpdateUnitKind } from './copy-text';
import { GlyphComponent, GlyphName } from './glyph.component';
import { SegmentedChoiceComponent, SegmentedOption } from './segmented-choice.component';

/** One unit of the update plan as the review screen shows it. */
export interface UpdateUnitView {
    readonly id: string;
    readonly kind: UpdateUnitKind;
    /** A short title: the material's heading, the exercise's prompt, «Цель колоды». It wraps; it is never cut off on a narrow screen. */
    readonly title: string;
    readonly class: UpdateUnitClass;
    /** The title of the material this unit needs and that comes with it; `null` when it needs nothing. */
    readonly dependency?: string | null;
}

const KIND_GLYPHS: Readonly<Record<UpdateUnitKind, GlyphName>> = {
    material: 'book-open', exercise: 'exercise', goal: 'goal', description: 'scroll-seal', order: 'order'
};

/** What the reader can decide for a class of unit, in the order shown; the first of a conflict and of a deletion is the default. */
export function choicesFor(unitClass: UpdateUnitClass): readonly SegmentedOption<UpdateChoice>[] {
    switch (unitClass) {
        case 'conflict': return [
            { value: 'keep-mine', label: UPDATE_TEXT.choiceKeepMine, hint: UPDATE_TEXT.hintKeepMine },
            { value: 'take-author', label: UPDATE_TEXT.choiceTakeAuthor, hint: UPDATE_TEXT.hintTakeAuthor }
        ];
        case 'deleted': return [
            { value: 'keep', label: UPDATE_TEXT.choiceKeep, hint: UPDATE_TEXT.hintKeep },
            { value: 'delete', label: UPDATE_TEXT.choiceDelete, hint: UPDATE_TEXT.hintDelete }
        ];
        default: return [
            { value: 'accept', label: UPDATE_TEXT.choiceAccept, hint: UPDATE_TEXT.hintAccept },
            { value: 'skip', label: UPDATE_TEXT.choiceSkip, hint: UPDATE_TEXT.hintSkip }
        ];
    }
}

let nextRow = 0;

/**
 * One row of the update plan: the kind of the unit (a line glyph and the word), its title (whole below a 40 rem container, two lines when the row is wide and closed; the full text is always in the DOM and shown when the row is open), its class as a text stamp
 * («Новое», «Исправление», «Конфликт», «Удалено автором», «Снова изменено»), the reader's decision, and a disclosure that opens the
 * comparison.
 *
 * The decision is an `app-segmented-choice` for every class, not a checkbox: each class has two answers that both name an action
 * («Принять» / «Не брать», «Оставить моё» / «Взять вариант автора», «Оставить у себя» / «Удалить и у меня»), and a lone checkbox
 * would hide one of them behind «unchecked». Its legend names the unit for assistive technology and is not drawn, except for a
 * deletion, where the legend is the visible question «Удалить «…» и у меня?». A conflict starts on «Оставить моё» and a deletion on
 * «Оставить у себя» (the model is `null` until the reader picks; `null` on new and corrected units means «not decided»).
 *
 * The disclosure is a button with `aria-expanded` and, while open, `aria-controls` of the body. The body is whatever
 * the page projects (an `app-compare-columns`); projected content is created even while the row is closed, so a page guards expensive
 * content with `@if (row.expanded())` through a template reference to the row. Used inside a list: the host is an `article`.
 */
@Component({
    selector: 'app-update-unit-row',
    imports: [NgTemplateOutlet, GlyphComponent, SegmentedChoiceComponent],
    template: `
      <article class="unit" [attr.aria-labelledby]="titleId" [attr.data-class]="unit().class" [class.is-open]="expanded()">
        <div class="meta">
          <span class="kind"><app-glyph [name]="glyph()" /><span>{{ kindLabel() }}</span></span>
          <span class="stamp" [class.solid]="unit().class === 'conflict'">{{ classLabel() }}</span>
        </div>
        <ng-template #titleText>{{ unit().title }}</ng-template>
        @if (headingLevel() === 4) {
          <h4 class="title" [id]="titleId"><ng-container [ngTemplateOutlet]="titleText" /></h4>
        } @else {
          <h3 class="title" [id]="titleId"><ng-container [ngTemplateOutlet]="titleText" /></h3>
        }
        @if (unit().dependency; as needed) { <p class="hint dependency">{{ text.dependency(needed) }}</p> }
        <div class="decision">
          <app-segmented-choice [legend]="legend()" [legendHidden]="unit().class !== 'deleted'" [options]="options()" [value]="shown()"
            (valueChange)="pick($event)" />
        </div>
        <div class="disclosure">
          <button type="button" class="button small" [attr.aria-expanded]="expanded() ? 'true' : 'false'" [attr.aria-controls]="expanded() ? bodyId : null"
            [attr.aria-label]="expanded() ? text.collapseFor(unit().title) : text.compareFor(unit().title)" (click)="expanded.set(!expanded())">
            {{ expanded() ? text.collapse : text.compare }}
          </button>
        </div>
        @if (expanded()) { <div class="body" [id]="bodyId"><ng-content /></div> }
      </article>
    `,
    styles: [`
      :host { display: block; container-type: inline-size; min-inline-size: 0; }
      .unit { display: grid; gap: .5rem .75rem; min-inline-size: 0; border-block-start: 1px solid var(--mn-rule); padding-block: 1rem; }
      .meta { display: flex; flex-wrap: wrap; align-items: center; gap: .35rem .75rem; }
      .kind { display: inline-flex; align-items: center; gap: .4rem; color: var(--mn-muted); font: 700 .75rem/1.4 var(--mn-font-mono); letter-spacing: .08em; text-transform: uppercase; }
      .kind app-glyph { --mn-glyph-size: 1.1rem; color: var(--mn-ink); }
      .title { margin: 0; min-inline-size: 0; color: var(--mn-ink); font: 700 1.05rem/1.4 var(--mn-font-body, system-ui, sans-serif); overflow-wrap: anywhere; }
      .dependency { margin: 0; max-inline-size: 62ch; }
      .decision, .disclosure { min-inline-size: 0; }
      .body { grid-column: 1 / -1; min-inline-size: 0; margin-block-start: .75rem; }
      @container (width >= 40rem) {
        /* Clamped only where the row is wide; an open row and any narrower container show the whole title. */
        .unit:not(.is-open) .title { display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 2; line-clamp: 2; overflow: hidden; }
        .unit { grid-template-columns: minmax(0, 1fr) auto; align-items: start; }
        .meta, .title, .dependency { grid-column: 1; }
        .decision { grid-column: 2; grid-row: 1 / span 3; justify-self: end; }
        .disclosure { grid-column: 1; }
      }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class UpdateUnitRowComponent {
    readonly unit = input.required<UpdateUnitView>();
    /** The reader's decision; `null` until picked (a conflict then stands on «Оставить моё», a deletion on «Оставить у себя»). */
    readonly choice = model<UpdateChoice | null>(null);
    /** The comparison is open. */
    readonly expanded = model(false);
    /** The level of the title: 3 below a section heading, 4 below a tab panel with its own heading. */
    readonly headingLevel = input<3 | 4>(3);

    protected readonly text = UPDATE_TEXT;
    private readonly uid = `mn-update-unit-${nextRow++}`;
    protected readonly titleId = `${this.uid}-title`;
    protected readonly bodyId = `${this.uid}-body`;
    protected readonly glyph = computed(() => KIND_GLYPHS[this.unit().kind]);
    protected readonly kindLabel = computed(() => UPDATE_TEXT.kinds[this.unit().kind]);
    protected readonly classLabel = computed(() => UPDATE_TEXT.classes[this.unit().class]);
    protected readonly options = computed(() => choicesFor(this.unit().class));
    protected readonly legend = computed(() => this.unit().class === 'deleted'
        ? UPDATE_TEXT.deleteQuestion(this.unit().title) : UPDATE_TEXT.choiceFor(this.unit().title));
    /** The picked answer, else the default of the class (the first option of a conflict or a deletion), else nothing. */
    protected readonly shown = computed<UpdateChoice | null>(() => {
        const picked = this.choice();
        if (picked !== null) return picked;
        const unitClass = this.unit().class;
        return unitClass === 'conflict' || unitClass === 'deleted' ? this.options()[0]!.value : null;
    });

    protected pick(value: UpdateChoice | null): void { if (value !== null) this.choice.set(value); }
}
