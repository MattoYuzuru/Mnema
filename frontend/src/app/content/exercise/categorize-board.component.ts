import { NgTemplateOutlet } from '@angular/common';
import {
    ChangeDetectionStrategy, Component, ElementRef, Injector, afterNextRender, computed, effect, inject, input, output, signal
} from '@angular/core';

import { LearnerCategorizeItem, LearnerCategory } from './exercise-content.models';
import { itemLabel } from './item-label';
import { LearnerBlocksComponent } from './learner-blocks.component';

/** `categoryId: null` returns the item to the unassigned list. `keyboard` tells the host whether focus may travel. */
export interface CategoryAssignment { readonly itemId: string; readonly categoryId: string | null; readonly keyboard: boolean; }

interface ItemView { readonly item: LearnerCategorizeItem; readonly ordinal: number; readonly name: string; }

/**
 * The CATEGORIZE answer surface: pick an item, then pick the group it belongs to. Item selection buttons, group
 * buttons and media players are separate siblings, so a player never selects or assigns anything. Every group shows
 * a counter; the groups stack vertically whenever the screen is narrow. The parent owns the assignments, and the
 * learner can change any of them until the answer is submitted.
 */
@Component({
    selector: 'app-categorize-board',
    imports: [LearnerBlocksComponent, NgTemplateOutlet],
    template: `
      <div class="board" role="group" aria-label="Распределение по группам" [attr.aria-busy]="disabled()">
        <section class="pool" data-pool [attr.aria-labelledby]="idPrefix() + '-pool'">
          <div class="pool-head">
            <h3 [id]="idPrefix() + '-pool'">Осталось распределить: {{ pool().length }}</h3>
            @if (selectedAssigned()) {
              <button type="button" class="action" data-unassign [disabled]="disabled()" (click)="assign(null)">Вернуть в список</button>
            }
          </div>
          @if (pool().length === 0) {
            <p class="empty">Все элементы распределены. Решение можно изменить: выберите элемент в группе.</p>
          }
          <ul class="items">
            @for (view of pool(); track view.item.itemId) { <ng-container [ngTemplateOutlet]="entry" [ngTemplateOutletContext]="{ view }" /> }
          </ul>
        </section>

        <div class="groups">
          @for (group of categories(); track group.categoryId) {
            <section class="group" [attr.data-category]="group.categoryId" [attr.aria-labelledby]="idPrefix() + '-group-' + group.categoryId">
              <div class="group-head">
                <h3 [id]="idPrefix() + '-group-' + group.categoryId">{{ group.label }}</h3>
                <p class="counter">Элементов: {{ inGroup(group.categoryId).length }}</p>
              </div>
              <button type="button" class="action place" data-place [disabled]="disabled() || selected() === null || selectedIn() === group.categoryId"
                      [attr.aria-label]="placeName(group)" (click)="assign(group.categoryId)">Поместить сюда</button>
              <ul class="items">
                @for (view of inGroup(group.categoryId); track view.item.itemId) { <ng-container [ngTemplateOutlet]="entry" [ngTemplateOutletContext]="{ view }" /> }
              </ul>
            </section>
          }
        </div>
      </div>
      <p class="board-status" role="status" aria-live="polite">{{ status() }}</p>

      <ng-template #entry let-view="view">
        <li class="item" [class.is-selected]="selected() === view.item.itemId" [attr.data-item-id]="view.item.itemId">
          <div class="item-content"><app-learner-blocks [blocks]="view.item.blocks" [nameSuffix]="', элемент ' + view.ordinal" /></div>
          <button type="button" class="select" data-select [attr.data-answer-control]="view.ordinal === 1 ? '' : null" [attr.aria-pressed]="selected() === view.item.itemId" [disabled]="disabled()"
                  [attr.aria-label]="'Выбрать: ' + view.name" (click)="choose(view.item.itemId)">
            {{ selected() === view.item.itemId ? 'Выбрано' : 'Выбрать' }}</button>
        </li>
      </ng-template>
    `,
    styles: [`
      :host { display: block; min-inline-size: 0; }
      .board { display: grid; gap: 1.25rem; min-inline-size: 0; }
      .pool, .group { min-inline-size: 0; display: grid; gap: .7rem; align-content: start; border: 1px solid var(--mn-rule); padding: .85rem; }
      .group { border-color: var(--mn-field-border, var(--mn-rule)); background: color-mix(in srgb, var(--mn-soft) 40%, var(--mn-sheet)); }
      .groups { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 16rem), 1fr)); gap: .9rem; min-inline-size: 0; }
      .pool-head, .group-head { display: flex; flex-wrap: wrap; gap: .5rem; align-items: baseline; justify-content: space-between; }
      h3 { margin: 0; color: var(--mn-ink); font: 500 1.2rem/1.25 var(--mn-font-display, Georgia, serif); overflow-wrap: anywhere; }
      .counter { margin: 0; color: var(--mn-muted); font: .8rem var(--mn-font-mono, ui-monospace, monospace); }
      .empty { margin: 0; color: var(--mn-muted); font-size: .9rem; line-height: 1.5; }
      .items { display: grid; gap: .55rem; margin: 0; padding: 0; list-style: none; }
      .item { display: grid; gap: .55rem; min-inline-size: 0; border: 1px solid var(--mn-field-border, var(--mn-rule)); padding: .6rem; background: var(--mn-sheet); }
      .item.is-selected { border-color: var(--mn-ink); box-shadow: inset 0 0 0 2px var(--mn-ink); }
      .item-content { min-inline-size: 0; overflow-wrap: anywhere; }
      .select, .action { min-block-size: var(--mn-touch-min, 2.75rem); border: 1px solid var(--mn-ink); border-radius: var(--mn-radius, 2px); padding: .5rem .8rem; color: var(--mn-ink); background: transparent; font: 650 .95rem/1.2 var(--mn-font-body, system-ui, sans-serif); cursor: pointer; }
      .select[aria-pressed='true'] { color: var(--mn-on-ink); background: var(--mn-ink); }
      .action:disabled, .select:disabled { cursor: not-allowed; opacity: .5; }
      .select:focus-visible, .action:focus-visible { outline: 3px solid var(--mn-focus, var(--mn-ink)); outline-offset: 3px; }
      .board-status { margin: .75rem 0 0; color: var(--mn-muted); font-size: .9rem; }
      @media (forced-colors: active) { .item.is-selected { outline: 2px solid Highlight; } }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush,
    host: { '(keydown)': 'keyboard = true', '(pointerdown)': 'keyboard = false' }
})
export class CategorizeBoardComponent {
    readonly categories = input.required<readonly LearnerCategory[]>();
    readonly items = input.required<readonly LearnerCategorizeItem[]>();
    /** Item id to category id; items without an entry are still unassigned. */
    readonly assignments = input<Readonly<Record<string, string>>>({});
    readonly disabled = input(false);
    readonly idPrefix = input('categorize');
    readonly assigned = output<CategoryAssignment>();

    readonly selected = signal<string | null>(null);
    private readonly announced = signal('');
    keyboard = false;

    readonly views = computed<readonly ItemView[]>(() => this.items().map((item, index) => ({
        item, ordinal: index + 1, name: itemLabel(item.blocks, index + 1) })));
    readonly pool = computed(() => this.views().filter(view => this.assignments()[view.item.itemId] === undefined));
    readonly selectedIn = computed(() => { const id = this.selected(); return id === null ? null : this.assignments()[id] ?? null; });
    readonly selectedAssigned = computed(() => this.selectedIn() !== null);
    readonly status = computed(() => {
        const note = this.announced();
        const picked = this.views().find(view => view.item.itemId === this.selected());
        if (picked !== undefined) return `Выбран элемент «${picked.name}». Теперь выберите группу.`;
        if (note !== '') return note;
        return this.pool().length === 0 ? 'Все элементы распределены.' : 'Выберите элемент, затем группу, в которую он входит.';
    });

    private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
    private readonly injector = inject(Injector);

    constructor() {
        // A new set of items (another presentation, an edited draft) must not keep a selection that no longer exists.
        effect(() => {
            const picked = this.selected();
            if (picked !== null && !this.items().some(item => item.itemId === picked)) this.selected.set(null);
        });
    }

    inGroup(categoryId: string): readonly ItemView[] {
        return this.views().filter(view => this.assignments()[view.item.itemId] === categoryId);
    }

    placeName(group: LearnerCategory): string {
        const picked = this.views().find(view => view.item.itemId === this.selected());
        return picked === undefined ? `Поместить в группу «${group.label}»` : `Поместить «${picked.name}» в группу «${group.label}»`;
    }

    choose(itemId: string): void {
        if (this.disabled()) return;
        this.announced.set('');
        this.selected.set(this.selected() === itemId ? null : itemId);
    }

    assign(categoryId: string | null): void {
        const itemId = this.selected();
        if (this.disabled() || itemId === null) return;
        const view = this.views().find(candidate => candidate.item.itemId === itemId);
        if (view === undefined) return;
        const wasAssigned = this.assignments()[itemId] !== undefined;
        const left = this.pool().length + (wasAssigned && categoryId === null ? 1 : 0) - (!wasAssigned && categoryId !== null ? 1 : 0);
        const group = this.categories().find(candidate => candidate.categoryId === categoryId);
        this.selected.set(null);
        this.announced.set((group === undefined ? `«${view.name}» возвращён в список.` : `«${view.name}» помещён в группу «${group.label}».`)
            + (left === 0 ? ' Все элементы распределены.' : ` Осталось распределить: ${left}.`));
        this.assigned.emit({ itemId, categoryId, keyboard: this.keyboard });
        if (this.keyboard) afterNextRender({ write: () => this.focusNext(itemId, wasAssigned || categoryId === null) }, { injector: this.injector });
    }

    /** Keyboard flow: continue with the next unassigned item; a changed decision keeps focus on that item. */
    private focusNext(itemId: string, stayOnItem: boolean): void {
        const root = this.host.nativeElement;
        const own = root.querySelector<HTMLElement>(`[data-item-id="${CSS.escape(itemId)}"] [data-select]`);
        const next = stayOnItem ? own : root.querySelector<HTMLElement>('[data-pool] [data-select]');
        (next ?? own)?.focus();
    }
}
