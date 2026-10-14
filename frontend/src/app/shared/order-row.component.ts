import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

let nextOrderRowId = 0;

export type OrderRowAction = 'up' | 'down' | 'remove';

/**
 * One row of an ordered list the owner edits by hand: the position, the projected body (a title, a code, a stamp) and the three
 * actions «Выше», «Ниже», «Убрать». No dragging: the order changes by buttons only, so a keyboard, a switch and a screen reader
 * have the same path as a mouse. At the ends «Выше» and «Ниже» stay in the tab order as `aria-disabled` (they never fire), so the
 * focus is not lost when a row reaches the end. Every button name starts with its visible word and ends with the row's name
 * («Выше: Испанский язык»). A row error is a `.field-error` that the three buttons are described by. The list itself, the
 * focus after a move and the live announcement belong to the page: the row only says what was pressed.
 */
@Component({
    selector: 'app-order-row',
    template: `
      <div class="order-row">
        <span class="order-position" aria-hidden="true">{{ position() }}</span>
        <div class="order-body">
          <ng-content />
          @if (error(); as text) { <p class="field-error order-error" [id]="errorId">{{ text }}</p> }
        </div>
        <div class="order-actions">
          <button type="button" class="button small" data-action="up" [attr.aria-label]="'Выше: ' + label()"
            [attr.aria-disabled]="busy() || position() <= 1 ? 'true' : null" [attr.aria-describedby]="error() ? errorId : null"
            (click)="press('up', position() <= 1)">Выше</button>
          <button type="button" class="button small" data-action="down" [attr.aria-label]="'Ниже: ' + label()"
            [attr.aria-disabled]="busy() || position() >= total() ? 'true' : null" [attr.aria-describedby]="error() ? errorId : null"
            (click)="press('down', position() >= total())">Ниже</button>
          <button type="button" class="button small" data-action="remove" [attr.aria-label]="'Убрать: ' + label()"
            [attr.aria-disabled]="busy() ? 'true' : null" [attr.aria-describedby]="error() ? errorId : null"
            (click)="press('remove', false)">Убрать</button>
        </div>
      </div>
    `,
    styles: [`
      :host { display: block; min-inline-size: 0; }
      .order-row { display: flex; flex-wrap: wrap; align-items: flex-start; gap: var(--mn-space-2) var(--mn-space-4); padding-block: var(--mn-space-3); border-block-end: 1px solid var(--mn-rule); }
      .order-position { flex: none; min-inline-size: 2ch; padding-block-start: .15rem; color: var(--mn-muted); font: 700 .85rem/1.5 var(--mn-font-mono); }
      .order-body { flex: 1 1 14rem; display: grid; gap: var(--mn-space-1); min-inline-size: 0; overflow-wrap: anywhere; }
      .order-actions { flex: none; display: flex; flex-wrap: wrap; gap: var(--mn-space-2); }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class OrderRowComponent {
    /** 1-based place in the list. */
    readonly position = input.required<number>();
    readonly total = input.required<number>();
    /** The name of the row for the button names, e.g. the deck title. */
    readonly label = input.required<string>();
    readonly error = input<string | null>(null);
    /** A request is in flight: the three buttons are `aria-disabled`, not `disabled`. */
    readonly busy = input(false);
    readonly moveUp = output<void>();
    readonly moveDown = output<void>();
    readonly remove = output<void>();
    protected readonly errorId = `order-row-error-${nextOrderRowId++}`;

    protected press(action: OrderRowAction, atEnd: boolean): void {
        if (this.busy() || atEnd) return;
        if (action === 'up') this.moveUp.emit();
        else if (action === 'down') this.moveDown.emit();
        else this.remove.emit();
    }
}
