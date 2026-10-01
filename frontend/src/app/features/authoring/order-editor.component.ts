import { ChangeDetectionStrategy, Component, ElementRef, Injector, afterNextRender, computed, inject, model, signal } from '@angular/core';

import { AuthoringBlock, LIMITS, SEQUENCE_SLOT, SLOT_PROFILES, isBlank, visibleBlocksKey } from '../../content/exercise/exercise-content.models';
import { OrderDraft, OrderItemDraft, materialText, newOrderItem } from './exercise-draft';
import { ExerciseSlotEditorComponent } from './exercise-slot-editor.component';
import { MechanicEditorBase } from './mechanic-editors';
import { segmentLines, segmentWords, wordSegmentationAvailable } from './order-segmentation';

interface Caret { readonly itemId: string; readonly start: number; }
interface PendingReplace { readonly parts: readonly string[]; readonly replaced: number; }

/** A text-only item: the only kind that can be merged with a neighbour or split at the cursor without losing media. */
function textOf(item: OrderItemDraft): string | null {
    return item.blocks.length === 1 && item.blocks[0].kind === 'TEXT' ? item.blocks[0].text : null;
}

function isEmpty(item: OrderItemDraft): boolean {
    return item.blocks.every(block => block.kind === 'TEXT' && isBlank(block.text));
}

/**
 * ORDER step: the items in their correct order. The list order IS the answer key, so moving, merging, splitting and
 * editing items here is the whole authoring act; the learner gets the same items shuffled. Splitting helpers only
 * propose items and never replace authored content without asking.
 */
@Component({
    selector: 'app-order-editor',
    imports: [ExerciseSlotEditorComponent],
    template: `
      <fieldset class="items">
        <legend class="visually-hidden">Элементы в правильном порядке</legend>
        <p class="hint" [id]="idPrefix() + '-rules'">Элементов: {{ limits.min }}–{{ limits.max }}. Расположите их так, как они должны стоять
          правильно: этот порядок — эталон, ученик получит элементы вперемешку. Каждый сегмент используется ровно один раз, а другие
          порядки, даже осмысленные, Mnema не определяет. Одинаковые элементы взаимозаменяемы. В элементе — текст (до {{ textLimit }} знаков,
          переносы строк сохраняются) и/или одно изображение, аудио или видео.</p>

        <details class="helper" [open]="helperOpen()" (toggle)="helperOpen.set($any($event.target).open)">
          <summary>Разбить текст на части</summary>
          <label [for]="idPrefix() + '-source'">Текст для разбиения</label>
          <textarea [id]="idPrefix() + '-source'" rows="3" spellcheck="true" [value]="source()"
                    (input)="source.set($any($event.target).value)"></textarea>
          <div class="add-row">
            <button type="button" class="button" data-split-words [disabled]="!segmenterAvailable || source().trim() === ''"
                    [attr.aria-describedby]="segmenterAvailable ? null : idPrefix() + '-no-segmenter'" (click)="splitWords()">Разбить на слова</button>
            <button type="button" class="button" data-split-lines [disabled]="source().trim() === ''" (click)="splitLines()">Разбить по строкам</button>
          </div>
          @if (!segmenterAvailable) {
            <p class="hint" data-no-segmenter [id]="idPrefix() + '-no-segmenter'">В этом браузере нет разбиения на слова. Разбейте текст по строкам или добавьте и поправьте элементы вручную.</p>
          }
          <p class="hint">Знаки препинания остаются при слове. Границы всегда можно поправить вручную: объединить, разделить или изменить элементы ниже.</p>
          @if (helperNote(); as note) {
            <p class="hint" data-helper-note [class.field-error]="note.error" [attr.role]="note.error ? 'alert' : 'status'">{{ note.text }}</p>
          }
          @if (pendingReplace(); as pending) {
            <div class="notice warning" role="alertdialog" [attr.aria-labelledby]="idPrefix() + '-replace-title'">
              <p [id]="idPrefix() + '-replace-title'"><strong>Заменить текущие элементы ({{ pending.replaced }})?</strong></p>
              <p>Результат разбиения ({{ pending.parts.length }}) займёт их место. Введённый вами текст и медиа в элементах будут удалены.</p>
              <div class="add-row">
                <button type="button" class="button" data-confirm-replace (click)="confirmReplace()">Заменить</button>
                <button type="button" class="button" data-cancel-replace (click)="pendingReplace.set(null)">Отмена</button>
              </div>
            </div>
          }
        </details>

        @for (item of draft().items; track item.itemId; let index = $index; let first = $first; let last = $last) {
          <section class="card" [attr.data-item]="item.itemId" [attr.aria-label]="'Элемент ' + (index + 1)"
                   (select)="remember(item.itemId, $event)" (keyup)="remember(item.itemId, $event)"
                   (pointerup)="remember(item.itemId, $event)" (focusin)="remember(item.itemId, $event)">
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
            <app-exercise-slot-editor [label]="'Содержимое элемента ' + (index + 1)" [spec]="slot" [blocks]="item.blocks"
              (blocksChange)="setBlocks(item.itemId, $event)" [context]="context()"
              [idPrefix]="idPrefix() + '-item-' + item.itemId" [error]="errors()['item:' + item.itemId] ?? null"
              [showProblems]="showProblems()" />
            <div class="add-row" role="group" [attr.aria-label]="'Границы элемента ' + (index + 1)">
              <button type="button" class="button" data-merge [disabled]="!canMerge(index)" (click)="merge(item.itemId)"
                      [attr.aria-label]="'Объединить элементы ' + (index + 1) + ' и ' + (index + 2)">Объединить со следующим</button>
              <button type="button" class="button" data-split [disabled]="!canSplit(item)" (click)="split(item.itemId)"
                      [attr.aria-label]="'Разделить элемент ' + (index + 1) + ' в позиции курсора'">Разделить по курсору</button>
            </div>
            @if (duplicates().has(item.itemId)) {
              <p class="hint" data-duplicate>Такой же элемент уже есть: одинаковые элементы взаимозаменяемы, ученику не нужно различать их порядок.</p>
            }
          </section>
        }
        <button type="button" class="button" data-add-item [disabled]="draft().items.length >= limits.max" (click)="add()">+ Добавить элемент</button>
        <p class="hint" role="status" data-move-note>{{ moveNote() }}</p>
        @if (errors()['items']; as message) { <p class="field-error" role="alert">{{ message }}</p> }
      </fieldset>
    `,
    styleUrl: './exercise-fields.css',
    styles: [`
      :host { display: grid; gap: 1.5rem; min-inline-size: 0; }
      .items { gap: .9rem; }
      .row-actions { display: flex; flex-wrap: wrap; gap: .35rem; }
      .helper { display: grid; gap: .6rem; border: 1px dashed var(--mn-ink); padding: .8rem; }
      .helper > summary { min-block-size: var(--mn-touch-min, 2.75rem); display: flex; align-items: center; color: var(--mn-ink); font-weight: 650; cursor: pointer; }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class OrderEditorComponent extends MechanicEditorBase {
    readonly draft = model.required<OrderDraft>();
    readonly limits = LIMITS.orderItems;
    readonly slot = SEQUENCE_SLOT;
    readonly textLimit = SLOT_PROFILES.SEQUENCE.textLimit;
    readonly source = signal('');
    readonly helperOpen = signal(false);
    readonly helperNote = signal<{ readonly text: string; readonly error: boolean } | null>(null);
    readonly pendingReplace = signal<PendingReplace | null>(null);
    readonly moveNote = signal('');
    /** Whether `Intl.Segmenter` exists. A missing one only disables the word helper; the form stays whole. */
    readonly segmenterAvailable = wordSegmentationAvailable();
    private readonly caret = signal<Caret | null>(null);
    /** Items whose blocks equal another item's: the contract treats those copies as interchangeable. */
    readonly duplicates = computed<ReadonlySet<string>>(() => {
        const groups = new Map<string, string[]>();
        for (const item of this.draft().items) {
            if (isEmpty(item)) continue;
            const key = visibleBlocksKey(item.blocks, block => materialText(block, this.context()));
            groups.set(key, [...(groups.get(key) ?? []), item.itemId]);
        }
        return new Set([...groups.values()].filter(ids => ids.length > 1).flat());
    });

    private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
    private readonly injector = inject(Injector);

    // ----- splitting helpers --------------------------------------------------------------------------------------

    splitWords(): void {
        const parts = segmentWords(this.source());
        if (parts === null) {
            this.helperNote.set({ text: 'В этом браузере нет разбиения на слова. Разбейте текст по строкам или вручную.', error: true });
            return;
        }
        this.propose(parts);
    }

    splitLines(): void { this.propose(segmentLines(this.source())); }

    /** Applies a proposal, or asks first when it would replace content the author already wrote. Limits are errors, never truncation. */
    private propose(parts: readonly string[]): void {
        this.pendingReplace.set(null);
        if (parts.length < this.limits.min) {
            this.helperNote.set({ text: `Получилось частей: ${parts.length}. Нужно не меньше ${this.limits.min}: добавьте текст или разбейте его иначе.`, error: true });
            return;
        }
        if (parts.length > this.limits.max) {
            this.helperNote.set({ text: `Получилось частей: ${parts.length}, а в упражнении не больше ${this.limits.max}. Сократите текст или разделите его на несколько упражнений. Ничего не изменено.`, error: true });
            return;
        }
        const authored = this.draft().items.filter(item => !isEmpty(item));
        if (authored.length > 0) { this.pendingReplace.set({ parts, replaced: authored.length }); return; }
        this.apply(parts);
    }

    confirmReplace(): void {
        const pending = this.pendingReplace();
        if (pending !== null) this.apply(pending.parts);
    }

    private apply(parts: readonly string[]): void {
        this.draft.set({ ...this.draft(), items: parts.map(part => newOrderItem(part)) });
        this.pendingReplace.set(null);
        this.source.set('');
        this.caret.set(null);
        this.helperNote.set({ text: `Создано элементов: ${parts.length}. Проверьте границы и порядок: при необходимости объедините, разделите или поправьте элементы.`, error: false });
    }

    // ----- manual editing -----------------------------------------------------------------------------------------

    setBlocks(itemId: string, blocks: readonly AuthoringBlock[]): void {
        this.draft.update(draft => ({ ...draft, items: draft.items.map(item => item.itemId === itemId ? { ...item, blocks } : item) }));
    }

    add(): void {
        if (this.draft().items.length >= this.limits.max) return;
        const item = newOrderItem();
        this.draft.update(draft => ({ ...draft, items: [...draft.items, item] }));
        this.focusAfterRender(`[data-item="${item.itemId}"] textarea`);
    }

    remove(itemId: string): void {
        if (this.draft().items.length <= this.limits.min) return;
        const index = this.draft().items.findIndex(item => item.itemId === itemId);
        this.draft.update(draft => ({ ...draft, items: draft.items.filter(item => item.itemId !== itemId) }));
        const neighbour = this.draft().items[Math.min(index, this.draft().items.length - 1)];
        this.moveNote.set(`Элемент ${index + 1} удалён.`);
        this.focusAfterRender(neighbour === undefined ? '[data-add-item]' : `[data-item="${neighbour.itemId}"] [data-remove]`);
    }

    move(itemId: string, delta: -1 | 1): void {
        const items = [...this.draft().items];
        const index = items.findIndex(item => item.itemId === itemId);
        const target = index + delta;
        if (index < 0 || target < 0 || target >= items.length) return;
        [items[index], items[target]] = [items[target], items[index]];
        this.draft.set({ ...this.draft(), items });
        this.moveNote.set(`Элемент перемещён на позицию ${target + 1} из ${items.length}.`);
        // The moved card is re-inserted by the list; return focus to the same arrow, or to the other one at the end.
        const kind = delta === -1 ? (target === 0 ? 'down' : 'up') : (target === items.length - 1 ? 'up' : 'down');
        this.focusAfterRender(`[data-item="${itemId}"] [data-move="${kind}"]`);
    }

    canMerge(index: number): boolean {
        const items = this.draft().items;
        return index < items.length - 1 && items.length > this.limits.min && textOf(items[index]) !== null && textOf(items[index + 1]) !== null;
    }

    /** Joins the text of the next item to this one (a space, or a line break when either text has lines). */
    merge(itemId: string): void {
        const items = this.draft().items;
        const index = items.findIndex(item => item.itemId === itemId);
        if (index < 0 || !this.canMerge(index)) return;
        const first = textOf(items[index])!;
        const second = textOf(items[index + 1])!;
        const joined = first + (first.includes('\n') || second.includes('\n') ? '\n' : ' ') + second;
        this.draft.set({ ...this.draft(), items: [...items.slice(0, index), { ...items[index], blocks: [{ kind: 'TEXT', text: joined }] },
            ...items.slice(index + 2)] });
        this.caret.set(null);
        this.moveNote.set(`Элементы ${index + 1} и ${index + 2} объединены.`);
        this.focusAfterRender(`[data-item="${itemId}"] textarea`);
    }

    /** Whether the remembered cursor of this item sits strictly inside non-blank text, so both halves would be real items. */
    canSplit(item: OrderItemDraft): boolean {
        const text = textOf(item);
        const caret = this.caret();
        if (text === null || caret === null || caret.itemId !== item.itemId || this.draft().items.length >= this.limits.max) return false;
        return !isBlank(text.slice(0, caret.start)) && !isBlank(text.slice(caret.start));
    }

    /** Splits the text at the remembered cursor; the second half becomes the next item, so the authored order is kept. */
    split(itemId: string): void {
        const items = this.draft().items;
        const index = items.findIndex(item => item.itemId === itemId);
        const caret = this.caret();
        if (index < 0 || caret === null || !this.canSplit(items[index])) return;
        const text = textOf(items[index])!;
        const head = text.slice(0, caret.start).trimEnd();
        const tail = text.slice(caret.start).trimStart();
        this.draft.set({ ...this.draft(), items: [...items.slice(0, index), { ...items[index], blocks: [{ kind: 'TEXT', text: head }] },
            newOrderItem(tail), ...items.slice(index + 1)] });
        this.caret.set(null);
        this.moveNote.set(`Элемент ${index + 1} разделён на два.`);
        this.focusAfterRender(`[data-item="${itemId}"] textarea`);
    }

    /** Remembers the cursor of the text area being edited; the split button reads it after the focus moves to the button. */
    remember(itemId: string, event: Event): void {
        const area = event.target;
        if (!(area instanceof HTMLTextAreaElement) || !/-text-\d+$/u.test(area.id)) return;
        this.caret.set({ itemId, start: area.selectionStart });
    }

    private focusAfterRender(selector: string): void {
        afterNextRender({ write: () => this.host.nativeElement.querySelector<HTMLElement>(selector)?.focus() }, { injector: this.injector });
    }
}
