import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { UPDATE_TEXT, UpdateUnitKind } from './copy-text';
import { GlyphComponent } from './glyph.component';
import { KIND_GLYPHS } from './update-unit-row.component';

/** One unit an update changed in the copy, as the history lists it. */
export interface HistoryUnitView {
    readonly id: string;
    readonly kind: UpdateUnitKind;
    /** What the apply did to it. */
    readonly change: 'ADDED' | 'CHANGED' | 'REMOVED' | 'REORDERED';
    readonly title: string;
    /** Where the unit stands now when it is no longer what the update made it; `null` while it is. */
    readonly state: 'RETURNED' | 'CHANGED_SINCE' | null;
}

let nextRow = 0;

/**
 * One row of the history of updates: the kind of the unit (a line glyph and the word), what the update did to it as a text stamp
 * («Добавлено», «Исправлено», «Удалено», «Порядок»), its title (whole, wrapped), and, when the unit is no longer what the update made it, a
 * sentence that says so («Вы уже вернули», «Вы изменили после обновления»). The change is always a word: colour and line style only support it.
 * The page projects the action of the row (`Вернуть как было` and its inline confirmation) after the sentence. The host is an `article`
 * named by its title.
 */
@Component({
    selector: 'app-update-history-unit',
    imports: [GlyphComponent],
    template: `
      <article class="unit" [attr.aria-labelledby]="titleId" [attr.data-change]="unit().change">
        <div class="meta">
          <span class="kind"><app-glyph [name]="glyph()" /><span>{{ kindLabel() }}</span></span>
          <span class="stamp" [class.solid]="unit().change === 'REMOVED'">{{ changeLabel() }}</span>
        </div>
        <p class="title" [id]="titleId">{{ unit().title }}</p>
        @if (stateLabel(); as note) { <p class="hint state">{{ note }}</p> }
        <div class="action"><ng-content /></div>
      </article>
    `,
    styles: [`
      :host { display: block; min-inline-size: 0; }
      .unit { display: grid; gap: .5rem; min-inline-size: 0; border-block-start: 1px solid var(--mn-rule); padding-block: .85rem; }
      .meta { display: flex; flex-wrap: wrap; align-items: center; gap: .35rem .75rem; }
      .kind { display: inline-flex; align-items: center; gap: .4rem; color: var(--mn-muted); font: 700 .75rem/1.4 var(--mn-font-mono); letter-spacing: .08em; text-transform: uppercase; }
      .kind app-glyph { --mn-glyph-size: 1.1rem; color: var(--mn-ink); }
      .title { margin: 0; min-inline-size: 0; color: var(--mn-ink); font: 700 1.05rem/1.4 var(--mn-font-body, system-ui, sans-serif); overflow-wrap: anywhere; }
      .state { margin: 0; max-inline-size: 62ch; }
      .action { min-inline-size: 0; }
      .action:empty { display: none; }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class UpdateHistoryUnitComponent {
    readonly unit = input.required<HistoryUnitView>();

    protected readonly titleId = `history-unit-${nextRow++}`;
    protected readonly glyph = computed(() => KIND_GLYPHS[this.unit().kind]);
    protected readonly kindLabel = computed(() => UPDATE_TEXT.kinds[this.unit().kind]);
    protected readonly changeLabel = computed(() => UPDATE_TEXT.changes[this.unit().change]);
    protected readonly stateLabel = computed(() => {
        const state = this.unit().state;
        return state === null ? null : UPDATE_TEXT.unitStates[state];
    });
}
