import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core';

/** One native input keeps text selection, IME and keyboard editing intact while the ruled track follows it. */
@Component({
    selector: 'app-cloze-input',
    template: `
      <label [for]="controlId()">{{ label() }}</label>
      <div class="cloze-viewport">
        <div class="cloze-line" [style.--slot-count]="slotCount()" [style.--caret-index]="caretIndex()">
          <span class="cloze-track" aria-hidden="true"></span>
          <input [id]="controlId()" data-answer-control type="text" autocomplete="off" autocapitalize="off"
            [value]="value()" [readOnly]="readOnly()"
            (input)="onInput($event)" (select)="updateCaret($event)" (click)="updateCaret($event)"
            (keyup)="updateCaret($event)" (compositionend)="updateCaret($event)" />
          <span class="cloze-current" aria-hidden="true"></span>
        </div>
      </div>
      <p class="cloze-hint">Черты показывают длину пропуска и не ограничивают ввод.</p>
    `,
    styleUrl: './cloze-input.component.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class ClozeInputComponent {
    readonly controlId = input.required<string>();
    readonly label = input('Ответ в пропуск');
    readonly blankLength = input(5);
    readonly value = input('');
    readonly readOnly = input(false);
    readonly valueChange = output<string>();
    readonly caretIndex = signal(0);
    readonly slotCount = computed(() => Math.max(this.blankLength(), Array.from(this.value()).length + 1));

    onInput(event: Event): void {
        const field = event.target as HTMLInputElement;
        this.valueChange.emit(field.value);
        this.updateCaret(event);
    }

    updateCaret(event: Event): void {
        const field = event.target as HTMLInputElement;
        this.caretIndex.set(Array.from(field.value.slice(0, field.selectionStart ?? field.value.length)).length);
    }
}
