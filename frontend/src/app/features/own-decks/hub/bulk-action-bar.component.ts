import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

import { HoldToDeleteButtonComponent } from '../../../shared/hold-to-delete-button.component';
import { materialsText } from './deck-hub.text';

/**
 * Actions for the selected materials, stuck to the bottom of the viewport while a selection exists. Focus never jumps
 * here: the bar is a labelled region the user reaches in Tab order, and the owner announces the count politely. Deletion
 * is the shared hold-to-delete button with the consequences spelled out; «Упражнения с ИИ» exists only when the server
 * offers generation (fail closed).
 */
@Component({
    selector: 'app-bulk-action-bar',
    imports: [HoldToDeleteButtonComponent],
    template: `
      <section class="bar" role="region" aria-label="Действия с выбранными">
        <p class="count"><strong>Выбрано {{ count() }} {{ noun() }}</strong></p>
        <div class="actions">
          @if (generationAvailable()) {
            <button class="button" type="button" (click)="generate.emit()">Упражнения с ИИ для выбранных</button>
          }
          <app-hold-to-delete-button [label]="'Удалить выбранные · ' + count()" [consequence]="consequence()"
            [disabled]="deleteDisabled()" (confirmed)="deleteConfirmed.emit()" />
          <button class="button quiet" type="button" (click)="clear.emit()">Снять выбор</button>
        </div>
        @if (hint()) { <p class="hint">{{ hint() }}</p> }
      </section>
    `,
    styleUrl: './bulk-action-bar.component.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class BulkActionBarComponent {
    readonly count = input.required<number>();
    /** What deleting removes; shown while the delete button is armed. Empty until it is known. */
    readonly consequence = input('');
    readonly deleteDisabled = input(false);
    /** Why deletion is not available right now (counting consequences, selection too large…). */
    readonly hint = input('');
    /** The server offers AI exercise generation; false (also while unknown) hides the button. */
    readonly generationAvailable = input(false);
    readonly clear = output<void>();
    readonly deleteConfirmed = output<void>();
    /**
     * TODO(AI-13, #291): the exercise builder does not exist yet, so the owner binds nothing to this output and the
     * button above is hidden while `aiGeneration` is unavailable. Wire it to open the builder with the selection
     * (`MaterialSelection.selection()`: explicit ids or all-in-deck minus exceptions) when #291 lands; keep the button
     * fail-closed behind the capability.
     */
    readonly generate = output<void>();

    protected noun(): string { return materialsText(this.count()).replace(/^\d+\s/, ''); }
}
