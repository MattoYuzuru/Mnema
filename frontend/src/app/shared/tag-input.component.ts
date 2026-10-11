import { ChangeDetectionStrategy, Component, ElementRef, computed, input, model, signal, viewChild } from '@angular/core';

import { MAX_TAGS, TAG_PROBLEM_TEXT, TagProblem, normalizeTag, tagProblem } from './tags';

let nextTagInput = 0;

/**
 * A short list of free-form tags in one field. A native text input with the chosen tags listed above it, each with its own
 * labelled remove button. Enter or a comma adds the typed tag (a pasted list with commas adds each), Backspace in the empty
 * field removes the last tag, leaving the field adds a valid tag that was typed but not confirmed. The server's rules apply
 * here too (see `tags.ts`): normalized form, at most five, 1 to 32 characters, no duplicates. A refusal is a sentence under
 * the field (`aria-invalid` + `aria-describedby`); every change is announced in a polite status: «Тег добавлен: …. Теги: 2 из 5».
 * At the limit the field stays focusable (`aria-disabled`), so focus is never lost.
 */
@Component({
    selector: 'app-tag-input',
    template: `
      <div class="field tag-input">
        <label [for]="inputId">{{ label() }}</label>
        @if (tags().length > 0) {
          <ul class="tag-list" [attr.aria-label]="label() + ': выбранные теги'">
            @for (tag of tags(); track tag) {
              <li class="tag">
                <span class="tag-text">{{ tag }}</span>
                <button type="button" class="tag-remove" [attr.aria-label]="'Убрать тег ' + tag" (click)="remove(tag)"><span aria-hidden="true">×</span></button>
              </li>
            }
          </ul>
        }
        <input #field type="text" [id]="inputId" autocomplete="off" autocapitalize="off" spellcheck="false" enterkeyhint="done"
          [attr.aria-invalid]="problem() ? 'true' : null" [attr.aria-disabled]="full() ? 'true' : null"
          [attr.aria-describedby]="problem() ? hintId + ' ' + errorId : hintId" [readOnly]="full()"
          (input)="onInput($event)" (keydown)="onKeydown($event)" (blur)="commit(false)" />
        <p class="hint" [id]="hintId">{{ hint() }} Теги: {{ tags().length }} из {{ max() }}.</p>
        <p class="field-error" [id]="errorId" [hidden]="!problem()">{{ problem() }}</p>
        <p class="visually-hidden" role="status" aria-live="polite">{{ announcement() }}</p>
      </div>
    `,
    styleUrl: './tag-input.component.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class TagInputComponent {
    readonly label = input.required<string>();
    readonly tags = model<readonly string[]>([]);
    readonly max = input(MAX_TAGS);
    readonly hint = input('Введите тег и нажмите Enter или поставьте запятую.');

    protected readonly uid = `mn-tags-${nextTagInput++}`;
    protected readonly inputId = `${this.uid}-field`;
    protected readonly hintId = `${this.uid}-hint`;
    protected readonly errorId = `${this.uid}-error`;
    protected readonly problem = signal('');
    protected readonly announcement = signal('');
    protected readonly full = computed(() => this.tags().length >= this.max());
    private readonly field = viewChild.required<ElementRef<HTMLInputElement>>('field');

    protected onInput(event: Event): void {
        const input = event.target as HTMLInputElement;
        this.problem.set('');
        // While an IME composes, the text is not final: a comma or Enter belongs to the composition.
        if ((event as InputEvent).isComposing || !input.value.includes(',')) return;
        const parts = input.value.split(',');
        const rest = parts.pop() ?? '';
        for (let index = 0; index < parts.length; index++) {
            if (parts[index].trim().length === 0) continue;
            if (!this.add(parts[index])) {
                // The refusal is shown; the unfit text and what follows it stay in the field to be corrected.
                input.value = [...parts.slice(index), rest].join(' ');
                return;
            }
        }
        input.value = rest;
    }

    protected onKeydown(event: KeyboardEvent): void {
        if (event.isComposing) return;
        const input = this.field().nativeElement;
        if (event.key === 'Enter') {
            event.preventDefault();
            this.commit(true);
        } else if (event.key === 'Backspace' && input.value.length === 0 && this.tags().length > 0) {
            event.preventDefault();
            this.remove(this.tags()[this.tags().length - 1], false);
        }
    }

    /** Adds what is typed; `loud` shows the refusal, a silent blur leaves an unfit text in the field untouched. */
    protected commit(loud: boolean): void {
        const input = this.field().nativeElement;
        if (input.value.trim().length === 0) { if (loud) this.problem.set(''); return; }
        const refusal = tagProblem(normalizeTag(input.value), this.tags(), this.max());
        if (refusal === null) {
            this.add(input.value);
            input.value = '';
        } else if (loud) {
            this.refuse(refusal);
        }
    }

    protected remove(tag: string, moveFocus = true): void {
        const next = this.tags().filter(item => item !== tag);
        this.tags.set(next);
        this.problem.set('');
        this.announce(`Тег убран: ${tag}. Теги: ${next.length} из ${this.max()}.`);
        if (moveFocus) this.field().nativeElement.focus();
    }

    /** `true` when the tag was added; otherwise the refusal is shown. */
    private add(raw: string): boolean {
        const tag = normalizeTag(raw);
        const refusal = tagProblem(tag, this.tags(), this.max());
        if (refusal !== null) {
            this.refuse(refusal);
            return false;
        }
        const next = [...this.tags(), tag];
        this.tags.set(next);
        this.problem.set('');
        this.announce(`Тег добавлен: ${tag}. Теги: ${next.length} из ${this.max()}.`);
        return true;
    }

    /** The refusal is both the sentence under the field (linked by `aria-describedby`) and a spoken message in the same polite status. */
    private refuse(refusal: TagProblem): void {
        this.problem.set(TAG_PROBLEM_TEXT[refusal]);
        this.announce(TAG_PROBLEM_TEXT[refusal]);
    }

    /** A message equal to the previous one is still spoken: a trailing no-break space makes the live region change. */
    private announce(message: string): void {
        this.announcement.set(this.announcement() === message ? `${message}\u00a0` : message);
    }
}
