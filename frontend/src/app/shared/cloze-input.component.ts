import { ChangeDetectionStrategy, Component, ElementRef, Injector, afterNextRender, computed, inject, input, output, signal, viewChild } from '@angular/core';

/** One native input keeps text selection, IME and keyboard editing intact while the ruled track follows it. */
@Component({
    selector: 'app-cloze-input',
    template: `
      <label [for]="controlId()">{{ label() }}</label>
      <div class="cloze-viewport" #viewport>
        <div class="cloze-line" [style.--slot-count]="slotCount()" [style.--caret-index]="caretIndex()">
          <span class="cloze-track" aria-hidden="true"></span>
          <span class="cloze-letters" aria-hidden="true"><span [class.hinted]="!!hintPrefix()">{{ hintedText() }}</span>{{ remainingText() }}</span>
          <input class="cloze-field" [id]="controlId()" data-answer-control type="text" autocomplete="off" autocapitalize="off"
            [value]="value()" [readOnly]="readOnly()"
            (input)="onInput($event)" (focus)="updateCaret($event)" (select)="updateCaret($event)" (click)="updateCaret($event)"
            (keyup)="updateCaret($event)" (compositionend)="updateCaret($event)" />
          <span #caret class="cloze-current" aria-hidden="true"></span>
        </div>
      </div>
      <p class="cloze-hint">Черты показывают длину пропуска и не ограничивают ввод.</p>
    `,
    styleUrl: './cloze-input.component.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class ClozeInputComponent {
    private readonly injector = inject(Injector);
    private readonly viewport = viewChild<ElementRef<HTMLElement>>('viewport');
    private readonly caret = viewChild<ElementRef<HTMLElement>>('caret');
    readonly controlId = input.required<string>();
    readonly label = input('Ответ в пропуск');
    readonly blankLength = input(5);
    readonly value = input('');
    readonly readOnly = input(false);
    readonly hintPrefix = input('');
    readonly hintedText = computed(() => this.hintPrefix() && this.value().startsWith(this.hintPrefix()) ? this.hintPrefix() : '');
    readonly remainingText = computed(() => this.value().slice(this.hintedText().length));
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
        afterNextRender(() => {
            const viewport = this.viewport()?.nativeElement;
            const caret = this.caret()?.nativeElement;
            if (!viewport || !caret || document.activeElement !== field) return;
            field.scrollLeft = 0;
            const visible = viewport.getBoundingClientRect();
            const current = caret.getBoundingClientRect();
            if (current.right > visible.right) viewport.scrollLeft += current.right - visible.right;
            else if (current.left < visible.left) viewport.scrollLeft += current.left - visible.left;
        }, { injector: this.injector });
    }
}
