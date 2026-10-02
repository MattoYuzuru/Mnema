import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';

import { ExemplarBudget, ItemSummary } from '../../authoring/authoring.models';
import { MaterialSelection } from './material-selection';
import { exercisesText, materialsText } from './deck-hub.text';

let nextList = 0;

/**
 * The materials of a Deck as a `<ul>`: each `<li>` holds a checkbox, a stretched link to the material (a checkbox may not
 * live inside a link), the exercise count, the «Эталон» star and the date. Selection lives in a shared
 * {@link MaterialSelection}; this component only draws it and reports intent. Shift+click selects a range, the header
 * checkbox is tri-state over the loaded rows and offers «Выбрать все N в колоде?» when every loaded row is selected.
 */
@Component({
    selector: 'app-selectable-material-list',
    imports: [DatePipe, RouterLink],
    templateUrl: './selectable-material-list.component.html',
    styleUrl: './selectable-material-list.component.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class SelectableMaterialListComponent {
    readonly deckId = input.required<string>();
    readonly items = input.required<readonly ItemSummary[]>();
    readonly total = input.required<number>();
    readonly selection = input.required<MaterialSelection>();
    readonly exemplars = input.required<ExemplarBudget>();
    /** Materials whose star request is in flight; their star ignores clicks. */
    readonly starPending = input<ReadonlySet<string>>(new Set());
    readonly hasMore = input(false);
    readonly loadingMore = input(false);
    readonly moreError = input(false);
    readonly toggleExemplar = output<ItemSummary>();
    readonly loadMore = output<void>();

    protected readonly uid = `mn-materials-${nextList++}`;
    protected readonly keys = computed(() => this.items().map(item => item.memberKey));
    protected readonly headerState = computed(() => this.selection().headerState(this.keys()));
    protected readonly atLimit = computed(() => this.exemplars().count >= this.exemplars().limit);
    protected readonly remaining = computed(() => Math.max(0, this.total() - this.items().length));
    protected readonly exercisesText = exercisesText;
    protected readonly materialsText = materialsText;

    protected titleOf(item: ItemSummary): string { return item.title || 'Материал без текста'; }

    protected pick(item: ItemSummary, event: MouseEvent): void {
        this.selection().toggle(item.memberKey, this.keys(), event.shiftKey);
    }

    /** At the limit only a starred material can be un-starred; the others explain why through the shared note. */
    protected starBlocked(item: ItemSummary): boolean { return this.atLimit() && !item.exemplar; }

    protected star(item: ItemSummary): void {
        if (this.starBlocked(item) || this.starPending().has(item.memberKey)) return;
        this.toggleExemplar.emit(item);
    }
}
