import {
    ChangeDetectionStrategy, Component, ElementRef, Injector, afterNextRender, computed, inject, input, output, signal
} from '@angular/core';

import { MnemaSelectComponent, MnemaSelectOption } from '../../core/controls/mnema-select.component';
import { LearnerOrderItem } from './exercise-content.models';
import { itemLabel } from './item-label';
import { LearnerBlocksComponent } from './learner-blocks.component';

interface RowView {
    readonly item: LearnerOrderItem;
    readonly position: number;
    /** 1-based place of the item in the issued list; stable while the learner moves things around. */
    readonly ordinal: number;
    readonly name: string;
}

/**
 * The ORDER answer surface: one list the learner rearranges. Every move has a button path (up, down) and a
 * «На позицию N» select; dragging the handle is an optional extra. Players live in the content area, apart from
 * every move control, so pressing play, pause or seek never moves an item. The parent owns the sequence; this
 * component reports the new one and announces each move in a live region. Nothing here scrolls the page.
 */
@Component({
    selector: 'app-order-board',
    imports: [LearnerBlocksComponent, MnemaSelectComponent],
    template: `
      <ol class="order-list" [attr.aria-label]="'Элементы для расстановки, всего ' + rows().length" [attr.aria-busy]="disabled()">
        @for (row of rows(); track row.item.itemId) {
          <li class="order-item" [attr.data-item-id]="row.item.itemId" [class.is-dragging]="dragging() === row.item.itemId"
              [class.is-drop-target]="dropTarget() === row.item.itemId"
              (dragover)="onDragOver($event, row.item.itemId)" (dragleave)="onDragLeave(row.item.itemId)"
              (drop)="onDrop($event, row.position)">
            <span class="order-number" aria-hidden="true">{{ row.position + 1 }}</span>
            <div class="order-content"><app-learner-blocks [blocks]="row.item.blocks" [nameSuffix]="', элемент ' + row.ordinal" /></div>
            <div class="order-controls" role="group" [attr.aria-label]="'Переместить: ' + row.name">
              <button type="button" class="order-move" data-move="up" [disabled]="disabled() || row.position === 0"
                      [attr.aria-label]="'Поднять выше: ' + row.name" (click)="step(row, -1)">↑<span class="move-text"> Выше</span></button>
              <button type="button" class="order-move" data-move="down" [attr.data-answer-control]="row.position === 0 ? '' : null" [disabled]="disabled() || row.position === rows().length - 1"
                      [attr.aria-label]="'Опустить ниже: ' + row.name" (click)="step(row, 1)">↓<span class="move-text"> Ниже</span></button>
              <app-mnema-select class="order-position" [compact]="true" [controlId]="idPrefix() + '-position-' + row.item.itemId"
                [label]="'Позиция элемента ' + row.name" [options]="positionOptions()" [value]="'' + (row.position + 1)"
                [disabled]="disabled()" (valueChange)="goTo(row, +$event - 1)" />
              @if (!disabled()) {
                <span class="drag-handle" draggable="true" title="Перетащить" aria-hidden="true"
                      (dragstart)="onDragStart($event, row)" (dragend)="onDragEnd()">⠿</span>
              }
            </div>
          </li>
        }
      </ol>
      <p class="order-status visually-hidden" role="status" aria-live="polite">{{ announcement() }}</p>
      <p class="order-hint">Меняйте порядок кнопками или выбором позиции. Перетаскивание за значок ⠿ — дополнительный способ.</p>
    `,
    styles: [`
      :host { display: block; min-inline-size: 0; }
      .order-list { display: grid; gap: .6rem; margin: 0; padding: 0; list-style: none; }
      .order-item { display: grid; grid-template-columns: auto minmax(0, 1fr); gap: .6rem .8rem; align-items: start; min-inline-size: 0; border: 1px solid var(--mn-field-border, var(--mn-rule)); padding: .7rem; background: var(--mn-sheet); }
      .order-item.is-dragging { opacity: .55; }
      .order-item.is-drop-target { border-color: var(--mn-ink); box-shadow: inset 0 3px 0 var(--mn-ink); }
      .order-number { display: inline-grid; place-items: center; inline-size: 2rem; block-size: 2rem; border: 1px solid var(--mn-ink); color: var(--mn-ink); font: 650 .9rem/1 var(--mn-font-mono, ui-monospace, monospace); }
      .order-content { min-inline-size: 0; overflow-wrap: anywhere; }
      .order-controls { grid-column: 1 / -1; display: flex; flex-wrap: wrap; gap: .5rem; align-items: center; }
      .order-move { min-block-size: var(--mn-touch-min, 2.75rem); min-inline-size: var(--mn-touch-min, 2.75rem); border: 1px solid var(--mn-ink); border-radius: var(--mn-radius, 2px); padding: .4rem .7rem; color: var(--mn-ink); background: transparent; font: 650 .95rem/1.2 var(--mn-font-body, system-ui, sans-serif); cursor: pointer; }
      .order-move:disabled { cursor: not-allowed; opacity: .5; }
      .order-move:focus-visible { outline: 3px solid var(--mn-focus, var(--mn-ink)); outline-offset: 3px; }
      .order-position { flex: 0 0 auto; }
      .drag-handle { display: inline-grid; place-items: center; min-inline-size: var(--mn-touch-min, 2.75rem); min-block-size: var(--mn-touch-min, 2.75rem); color: var(--mn-muted); font-size: 1.3rem; cursor: grab; user-select: none; touch-action: none; }
      .order-hint { margin: .75rem 0 0; color: var(--mn-muted); font-size: .9rem; line-height: 1.5; }
      .visually-hidden { position: absolute; inline-size: 1px; block-size: 1px; margin: -1px; overflow: hidden; clip-path: inset(50%); white-space: nowrap; }
      @media (min-width: 40rem) { .order-item { grid-template-columns: auto minmax(0, 1fr) auto; } .order-controls { grid-column: auto; } }
      @media (max-width: 40rem) { .move-text { display: none; } }
      @media (forced-colors: active) { .order-item.is-drop-target { outline: 2px solid Highlight; } }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush,
    host: { '(keydown)': 'keyboard = true', '(pointerdown)': 'keyboard = false' }
})
export class OrderBoardComponent {
    /** The issued items; order here is irrelevant, `sequence` is what the learner sees. */
    readonly items = input.required<readonly LearnerOrderItem[]>();
    /** Item ids in their current order. */
    readonly sequence = input.required<readonly string[]>();
    readonly disabled = input(false);
    readonly idPrefix = input('order');
    readonly sequenceChange = output<readonly string[]>();

    readonly announcement = signal('');
    readonly dragging = signal<string | null>(null);
    readonly dropTarget = signal<string | null>(null);

    /** Whether the last interaction came from the keyboard: only then may focus move far enough to scroll. */
    keyboard = false;

    readonly rows = computed<readonly RowView[]>(() => {
        const issued = this.items();
        return this.sequence().flatMap((itemId, position) => {
            const index = issued.findIndex(item => item.itemId === itemId);
            return index < 0 ? [] : [{ item: issued[index], position, ordinal: index + 1, name: itemLabel(issued[index].blocks, index + 1) }];
        });
    });
    readonly positionOptions = computed<readonly MnemaSelectOption[]>(() =>
        this.sequence().map((_, index) => ({ value: '' + (index + 1), label: `На позицию ${index + 1}` })));

    private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
    private readonly injector = inject(Injector);

    step(row: RowView, delta: -1 | 1): void { this.move(row, row.position + delta, delta === -1 ? 'up' : 'down'); }

    goTo(row: RowView, target: number): void { this.move(row, target, 'select'); }

    onDragStart(event: DragEvent, row: RowView): void {
        if (this.disabled()) { event.preventDefault(); return; }
        this.dragging.set(row.item.itemId);
        event.dataTransfer?.setData('text/plain', row.item.itemId);
        if (event.dataTransfer !== null) event.dataTransfer.effectAllowed = 'move';
        const card = (event.target as HTMLElement).closest('li');
        if (card !== null) event.dataTransfer?.setDragImage(card, 16, 16);
    }

    onDragOver(event: DragEvent, itemId: string): void {
        if (this.dragging() === null || this.dragging() === itemId) return;
        event.preventDefault();
        if (event.dataTransfer !== null) event.dataTransfer.dropEffect = 'move';
        this.dropTarget.set(itemId);
    }

    onDragLeave(itemId: string): void { if (this.dropTarget() === itemId) this.dropTarget.set(null); }

    onDrop(event: DragEvent, position: number): void {
        const draggedId = this.dragging();
        this.onDragEnd();
        if (draggedId === null) return;
        event.preventDefault();
        const row = this.rows().find(candidate => candidate.item.itemId === draggedId);
        if (row !== undefined) this.move(row, position, 'drag');
    }

    onDragEnd(): void {
        this.dragging.set(null);
        this.dropTarget.set(null);
    }

    private move(row: RowView, target: number, via: 'up' | 'down' | 'select' | 'drag'): void {
        const order = [...this.sequence()];
        if (this.disabled() || target < 0 || target >= order.length || target === row.position) return;
        order.splice(row.position, 1);
        order.splice(target, 0, row.item.itemId);
        this.announcement.set(`Элемент «${row.name}» перемещён на позицию ${target + 1} из ${order.length}.`);
        this.sequenceChange.emit(order);
        // A moved row is re-inserted by the list, which drops focus; hand it back to the same item's control.
        afterNextRender({ write: () => this.restoreFocus(row.item.itemId, via, target, order.length) }, { injector: this.injector });
    }

    private restoreFocus(itemId: string, via: 'up' | 'down' | 'select' | 'drag', position: number, size: number): void {
        // Dropping with a pointer leaves nothing focused on purpose: no control should steal the view.
        if (via === 'drag') return;
        const card = this.host.nativeElement.querySelector<HTMLElement>(`[data-item-id="${CSS.escape(itemId)}"]`);
        if (card === null) return;
        const up = card.querySelector<HTMLButtonElement>('[data-move="up"]');
        const down = card.querySelector<HTMLButtonElement>('[data-move="down"]');
        const select = card.querySelector<HTMLElement>('[role="combobox"]');
        // At either end the pressed arrow is disabled: continue on the arrow that still works.
        const control = via === 'up' ? (position === 0 ? down : up)
            : via === 'down' ? (position === size - 1 ? up : down) : select;
        control?.focus({ preventScroll: !this.keyboard });
    }
}
