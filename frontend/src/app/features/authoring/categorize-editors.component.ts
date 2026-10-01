import { ChangeDetectionStrategy, Component, ElementRef, Injector, afterNextRender, computed, inject, model, signal } from '@angular/core';

import { AuthoringBlock, COMPACT_SLOT, LIMITS, isBlank } from '../../content/exercise/exercise-content.models';
import { MnemaSelectComponent, MnemaSelectOption } from '../../core/controls/mnema-select.component';
import { CategorizeDraft, newCategorizeItem, newCategory } from './exercise-draft';
import { ExerciseSlotEditorComponent } from './exercise-slot-editor.component';
import { MechanicEditorBase } from './mechanic-editors';

/** Name of a group in lists and messages: its label, or a neutral «Группа N» while the label is still empty. */
function groupName(label: string, index: number): string { return isBlank(label) ? `Группа ${index + 1}` : label.trim(); }

interface Removal { readonly categoryId: string; readonly target: string; }
type GroupRemoval =
    | { readonly kind: 'empty' }
    | { readonly kind: 'move'; readonly to: string; readonly count: number }
    | { readonly kind: 'delete'; readonly count: number };

/**
 * CATEGORIZE groups step. Removing a group that still has items never leaves an item pointing at a missing group and
 * never drops items silently: the author either moves them to another group or confirms removing them with the group.
 */
@Component({
    selector: 'app-category-groups-editor',
    imports: [MnemaSelectComponent],
    template: `
      <fieldset class="groups">
        <legend class="visually-hidden">Группы</legend>
        <p class="hint">Групп: {{ limits.min }}–{{ limits.max }}. Названия короткие (до {{ limits.label }} знаков) и разные. Группа может остаться
          пустой: тогда она отвлекающая. Если нужен вариант «Не относится», создайте для него обычную группу.</p>
        @for (group of draft().categories; track group.categoryId; let index = $index; let first = $first; let last = $last) {
          <section class="card" [attr.data-category]="group.categoryId" [attr.aria-label]="'Группа ' + (index + 1)">
            <div class="card-head">
              <h3>Группа {{ index + 1 }}</h3>
              <span class="row-actions">
                <button type="button" class="button small" data-move="up" [disabled]="first" (click)="move(group.categoryId, -1)"
                        [attr.aria-label]="'Поднять группу ' + (index + 1)">↑</button>
                <button type="button" class="button small" data-move="down" [disabled]="last" (click)="move(group.categoryId, 1)"
                        [attr.aria-label]="'Опустить группу ' + (index + 1)">↓</button>
                <button type="button" class="button" data-remove [disabled]="draft().categories.length <= limits.min"
                        (click)="requestRemove(group.categoryId)" [attr.aria-label]="'Удалить группу ' + (index + 1)">Удалить</button>
              </span>
            </div>
            <label [for]="idPrefix() + '-label-' + group.categoryId">Название группы {{ index + 1 }}</label>
            <input type="text" [id]="idPrefix() + '-label-' + group.categoryId" autocomplete="off" [value]="group.label"
                   [attr.aria-invalid]="errors()['category:' + group.categoryId] ? 'true' : null"
                   [attr.aria-describedby]="errors()['category:' + group.categoryId] ? idPrefix() + '-error-' + group.categoryId : null"
                   (input)="setLabel(group.categoryId, $any($event.target).value)" />
            <p class="counter" [class.over]="group.label.length > limits.label">{{ group.label.length }} / {{ limits.label }}</p>
            @if (errors()['category:' + group.categoryId]; as message) {
              <p class="field-error" role="alert" [id]="idPrefix() + '-error-' + group.categoryId">{{ message }}</p>
            }
            <p class="hint">Элементов в группе: {{ countIn(group.categoryId) }}</p>

            @if (removalFor(group.categoryId); as removal) {
              <div class="notice warning removal" role="group" [attr.aria-labelledby]="idPrefix() + '-removal-' + group.categoryId">
                <p [id]="idPrefix() + '-removal-' + group.categoryId"><strong>В группе «{{ nameOf(group.categoryId) }}» элементов: {{ countIn(group.categoryId) }}.</strong>
                  Что с ними сделать?</p>
                <app-mnema-select [controlId]="idPrefix() + '-reassign-' + group.categoryId" label="Перенести элементы в группу"
                  [options]="targetOptions(group.categoryId)" [value]="removal.target" (valueChange)="chooseTarget($event)" />
                <div class="add-row">
                  <button type="button" class="button" data-reassign [disabled]="removal.target === ''" (click)="confirmReassign()">Перенести и удалить группу</button>
                  <button type="button" class="button" data-delete-items (click)="confirmDeleteItems()">Удалить группу и элементы: {{ countIn(group.categoryId) }}</button>
                  <button type="button" class="button" data-cancel-removal (click)="cancelRemoval(group.categoryId)">Отмена</button>
                </div>
              </div>
            }
          </section>
        }
        <button type="button" class="button" data-add-category [disabled]="draft().categories.length >= limits.max" (click)="add()">+ Добавить группу</button>
        <p class="hint" role="status" data-note>{{ note() }}</p>
        @if (errors()['categories']; as message) { <p class="field-error" role="alert">{{ message }}</p> }
      </fieldset>
    `,
    styleUrl: './exercise-fields.css',
    styles: [':host { display: grid; gap: 1.5rem; min-inline-size: 0; } .groups { gap: .9rem; } .row-actions { display: flex; flex-wrap: wrap; gap: .35rem; } .removal { display: grid; gap: .75rem; }'],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class CategoryGroupsEditorComponent extends MechanicEditorBase {
    readonly draft = model.required<CategorizeDraft>();
    readonly limits = LIMITS.categories;
    readonly removal = signal<Removal | null>(null);
    readonly note = signal('');

    private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
    private readonly injector = inject(Injector);

    countIn(categoryId: string): number { return this.draft().items.filter(item => item.categoryId === categoryId).length; }

    nameOf(categoryId: string): string {
        const index = this.draft().categories.findIndex(group => group.categoryId === categoryId);
        return index < 0 ? '' : groupName(this.draft().categories[index].label, index);
    }

    /** The open removal panel of this group, if any; it disappears by itself if the group is gone. */
    removalFor(categoryId: string): Removal | null {
        const current = this.removal();
        return current !== null && current.categoryId === categoryId ? current : null;
    }

    readonly targets = computed(() => this.draft().categories);

    targetOptions(categoryId: string): readonly MnemaSelectOption[] {
        return [{ value: '', label: 'Выберите группу' },
            ...this.targets().flatMap((group, index) => group.categoryId === categoryId ? []
                : [{ value: group.categoryId, label: groupName(group.label, index) }])];
    }

    setLabel(categoryId: string, label: string): void {
        this.draft.update(draft => ({ ...draft, categories: draft.categories.map(group => group.categoryId === categoryId ? { ...group, label } : group) }));
    }

    add(): void {
        if (this.draft().categories.length >= this.limits.max) return;
        const group = newCategory();
        this.draft.update(draft => ({ ...draft, categories: [...draft.categories, group] }));
        this.focusAfterRender(`#${CSS.escape(this.idPrefix() + '-label-' + group.categoryId)}`);
    }

    move(categoryId: string, delta: -1 | 1): void {
        const groups = [...this.draft().categories];
        const index = groups.findIndex(group => group.categoryId === categoryId);
        const target = index + delta;
        if (index < 0 || target < 0 || target >= groups.length) return;
        [groups[index], groups[target]] = [groups[target], groups[index]];
        this.draft.set({ ...this.draft(), categories: groups });
        this.note.set(`Группа перемещена на позицию ${target + 1} из ${groups.length}.`);
        const kind = delta === -1 ? (target === 0 ? 'down' : 'up') : (target === groups.length - 1 ? 'up' : 'down');
        this.focusAfterRender(`[data-category="${categoryId}"] [data-move="${kind}"]`);
    }

    /** An empty group goes at once; a group with items first asks what happens to them. */
    requestRemove(categoryId: string): void {
        if (this.draft().categories.length <= this.limits.min) return;
        if (this.countIn(categoryId) === 0) { this.removeGroup(categoryId, { kind: 'empty' }); return; }
        this.removal.set({ categoryId, target: '' });
        this.focusAfterRender(`[data-category="${categoryId}"] .removal [role="combobox"]`);
    }

    chooseTarget(target: string): void {
        const current = this.removal();
        if (current !== null) this.removal.set({ ...current, target });
    }

    cancelRemoval(categoryId: string): void {
        this.removal.set(null);
        this.focusAfterRender(`[data-category="${categoryId}"] [data-remove]`);
    }

    /** Moves the group's items to the chosen group, then removes the group: no item is lost, none points at a missing group. */
    confirmReassign(): void {
        const current = this.removal();
        if (current === null || current.target === '' || current.target === current.categoryId
            || !this.draft().categories.some(group => group.categoryId === current.target)) return;
        this.removeGroup(current.categoryId, { kind: 'move', to: current.target, count: this.countIn(current.categoryId) });
    }

    /** The author confirmed: the group goes together with the items assigned to it. */
    confirmDeleteItems(): void {
        const current = this.removal();
        if (current === null) return;
        this.removeGroup(current.categoryId, { kind: 'delete', count: this.countIn(current.categoryId) });
    }

    private removeGroup(categoryId: string, outcome: GroupRemoval): void {
        const draft = this.draft();
        if (draft.categories.length <= this.limits.min) return;
        const index = draft.categories.findIndex(group => group.categoryId === categoryId);
        const name = this.nameOf(categoryId);
        const categories = draft.categories.filter(group => group.categoryId !== categoryId);
        const items = outcome.kind === 'delete' ? draft.items.filter(item => item.categoryId !== categoryId)
            : outcome.kind === 'move' ? draft.items.map(item => item.categoryId === categoryId ? { ...item, categoryId: outcome.to } : item)
                : draft.items;
        this.draft.set({ ...draft, categories, items });
        this.removal.set(null);
        this.note.set(outcome.kind === 'empty' ? `Группа «${name}» удалена.`
            : outcome.kind === 'delete' ? `Группа «${name}» и её элементы (${outcome.count}) удалены.`
                : `Группа «${name}» удалена, элементы (${outcome.count}) перенесены в «${this.nameOfIn(categories, outcome.to)}».`);
        const neighbour = categories[Math.min(Math.max(index, 0), categories.length - 1)];
        this.focusAfterRender(neighbour === undefined ? '[data-add-category]' : `[data-category="${neighbour.categoryId}"] [data-remove]`);
    }

    private nameOfIn(categories: readonly { readonly categoryId: string; readonly label: string }[], categoryId: string): string {
        const index = categories.findIndex(group => group.categoryId === categoryId);
        return index < 0 ? '' : groupName(categories[index].label, index);
    }

    private focusAfterRender(selector: string): void {
        afterNextRender({ write: () => this.host.nativeElement.querySelector<HTMLElement>(selector)?.focus() }, { injector: this.injector });
    }
}

/**
 * CATEGORIZE items step: each item gets its content (the compact slot) and exactly one group. Items keep their
 * authored order here only for the author's convenience; the learner gets them shuffled.
 */
@Component({
    selector: 'app-categorize-items-editor',
    imports: [ExerciseSlotEditorComponent, MnemaSelectComponent],
    template: `
      <fieldset class="items">
        <legend class="visually-hidden">Элементы и их группы</legend>
        <p class="hint">Элементов: {{ limits.min }}–{{ limits.max }}. Каждый элемент относится ровно к одной группе; в группе может быть несколько элементов,
          а может не быть ни одного. В элементе — короткий текст (до 300 знаков) и/или одно изображение, аудио или видео. Ученик получит элементы вперемешку.</p>
        @for (item of draft().items; track item.itemId; let index = $index; let first = $first; let last = $last) {
          <section class="card" [attr.data-item]="item.itemId" [attr.aria-label]="'Элемент ' + (index + 1)">
            <div class="card-head">
              <h3>Элемент {{ index + 1 }}</h3>
              <span class="row-actions">
                <button type="button" class="button small" data-move="up" [disabled]="first" (click)="move(item.itemId, -1)"
                        [attr.aria-label]="'Поднять элемент ' + (index + 1)">↑</button>
                <button type="button" class="button small" data-move="down" [disabled]="last" (click)="move(item.itemId, 1)"
                        [attr.aria-label]="'Опустить элемент ' + (index + 1)">↓</button>
                <button type="button" class="button" data-remove [disabled]="draft().items.length <= limits.min" (click)="remove(item.itemId)"
                        [attr.aria-label]="'Удалить элемент ' + (index + 1)">Удалить</button>
              </span>
            </div>
            <label [for]="idPrefix() + '-group-' + item.itemId">Группа элемента {{ index + 1 }}</label>
            <app-mnema-select [controlId]="idPrefix() + '-group-' + item.itemId" [label]="'Группа элемента ' + (index + 1)"
              [options]="groupOptions()" [value]="item.categoryId ?? ''" [invalid]="!!errors()['assignment:' + item.itemId]"
              (valueChange)="assign(item.itemId, $event)" />
            @if (errors()['assignment:' + item.itemId]; as message) { <p class="field-error" role="alert">{{ message }}</p> }
            <app-exercise-slot-editor [label]="'Содержимое элемента ' + (index + 1)" [spec]="slot" [blocks]="item.blocks"
              (blocksChange)="setBlocks(item.itemId, $event)" [context]="context()"
              [idPrefix]="idPrefix() + '-item-' + item.itemId" [error]="errors()['item:' + item.itemId] ?? null"
              [showProblems]="showProblems()" />
          </section>
        }
        <button type="button" class="button" data-add-item [disabled]="draft().items.length >= limits.max" (click)="add()">+ Добавить элемент</button>
        <p class="hint" role="status" data-note>{{ note() }}</p>
        @if (errors()['items']; as message) { <p class="field-error" role="alert">{{ message }}</p> }
      </fieldset>
    `,
    styleUrl: './exercise-fields.css',
    styles: [':host { display: grid; gap: 1.5rem; min-inline-size: 0; } .items { gap: .9rem; } .row-actions { display: flex; flex-wrap: wrap; gap: .35rem; }'],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class CategorizeItemsEditorComponent extends MechanicEditorBase {
    readonly draft = model.required<CategorizeDraft>();
    readonly limits = LIMITS.categorizeItems;
    readonly slot = COMPACT_SLOT;
    readonly note = signal('');
    readonly groupOptions = computed<readonly MnemaSelectOption[]>(() => [{ value: '', label: 'Выберите группу' },
        ...this.draft().categories.map((group, index) => ({ value: group.categoryId, label: groupName(group.label, index) }))]);

    private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
    private readonly injector = inject(Injector);

    assign(itemId: string, categoryId: string): void {
        const known = categoryId === '' || this.draft().categories.some(group => group.categoryId === categoryId);
        if (!known) return;
        this.draft.update(draft => ({ ...draft, items: draft.items.map(item => item.itemId === itemId
            ? { ...item, categoryId: categoryId === '' ? null : categoryId } : item) }));
    }

    setBlocks(itemId: string, blocks: readonly AuthoringBlock[]): void {
        this.draft.update(draft => ({ ...draft, items: draft.items.map(item => item.itemId === itemId ? { ...item, blocks } : item) }));
    }

    add(): void {
        if (this.draft().items.length >= this.limits.max) return;
        const item = newCategorizeItem();
        this.draft.update(draft => ({ ...draft, items: [...draft.items, item] }));
        this.focusAfterRender(`[data-item="${item.itemId}"] textarea`);
    }

    remove(itemId: string): void {
        if (this.draft().items.length <= this.limits.min) return;
        const index = this.draft().items.findIndex(item => item.itemId === itemId);
        this.draft.update(draft => ({ ...draft, items: draft.items.filter(item => item.itemId !== itemId) }));
        const neighbour = this.draft().items[Math.min(index, this.draft().items.length - 1)];
        this.note.set(`Элемент ${index + 1} удалён.`);
        this.focusAfterRender(neighbour === undefined ? '[data-add-item]' : `[data-item="${neighbour.itemId}"] [data-remove]`);
    }

    move(itemId: string, delta: -1 | 1): void {
        const items = [...this.draft().items];
        const index = items.findIndex(item => item.itemId === itemId);
        const target = index + delta;
        if (index < 0 || target < 0 || target >= items.length) return;
        [items[index], items[target]] = [items[target], items[index]];
        this.draft.set({ ...this.draft(), items });
        this.note.set(`Элемент перемещён на позицию ${target + 1} из ${items.length}.`);
        const kind = delta === -1 ? (target === 0 ? 'down' : 'up') : (target === items.length - 1 ? 'up' : 'down');
        this.focusAfterRender(`[data-item="${itemId}"] [data-move="${kind}"]`);
    }

    private focusAfterRender(selector: string): void {
        afterNextRender({ write: () => this.host.nativeElement.querySelector<HTMLElement>(selector)?.focus() }, { injector: this.injector });
    }
}
