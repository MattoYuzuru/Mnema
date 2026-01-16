import { Component, Input, forwardRef } from '@angular/core';
import { ControlValueAccessor, NG_VALUE_ACCESSOR } from '@angular/forms';
import { NgIf, NgClass } from '@angular/common';

@Component({
    selector: 'app-textarea',
    standalone: true,
    imports: [NgIf, NgClass],
    providers: [
        {
            provide: NG_VALUE_ACCESSOR,
            useExisting: forwardRef(() => TextareaComponent),
            multi: true
        }
    ],
    template: `
    <div class="textarea-wrapper">
      <label *ngIf="label" [for]="id" class="textarea-label">{{ label }}</label>
      <textarea
        [id]="id"
        [placeholder]="placeholder"
        [disabled]="disabled"
        [rows]="rows"
        [attr.maxlength]="maxLength || null"
        [ngClass]="{
          'textarea': true,
          'textarea-error': hasError
        }"
        [value]="value"
        (input)="onInput($event)"
        (blur)="onTouched()"
      ></textarea>
      <div *ngIf="hasError && errorMessage" class="error-message">
        {{ errorMessage }}
      </div>
    </div>
  `,
    styles: [
        `
      .textarea-wrapper {
        display: flex;
        flex-direction: column;
        gap: var(--spacing-xs);
      }

      .textarea-label {
        font-size: 0.9rem;
        font-weight: 500;
        color: var(--color-text-primary);
      }

      .textarea {
        padding: var(--spacing-sm) var(--spacing-md);
        border: 1px solid var(--border-color);
        border-radius: var(--border-radius-md);
        font-size: 0.9rem;
        font-family: inherit;
        background: var(--color-card-background);
        color: var(--color-text-primary);
        transition: border-color 0.2s ease;
        resize: vertical;
      }

      .textarea:focus {
        outline: none;
        border-color: var(--color-primary-accent);
      }

      .textarea:disabled {
        opacity: 0.5;
        cursor: not-allowed;
      }

      .textarea-error {
        border-color: #dc2626;
      }

      .error-message {
        font-size: 0.85rem;
        color: #dc2626;
      }
    `
    ]
})
export class TextareaComponent implements ControlValueAccessor {
    @Input() label = '';
    @Input() placeholder = '';
    @Input() disabled = false;
    @Input() rows = 4;
    @Input() hasError = false;
    @Input() errorMessage = '';
    @Input() maxLength?: number;
    @Input() id = `textarea-${Math.random().toString(36).substr(2, 9)}`;

    value = '';
    onChange: (value: string) => void = () => {};
    onTouched: () => void = () => {};

    onInput(event: Event): void {
        const target = event.target as HTMLTextAreaElement;
        this.value = target.value;
        this.onChange(this.value);
    }

    writeValue(value: string): void {
        this.value = value || '';
    }

    registerOnChange(fn: (value: string) => void): void {
        this.onChange = fn;
    }

    registerOnTouched(fn: () => void): void {
        this.onTouched = fn;
    }

    setDisabledState(isDisabled: boolean): void {
        this.disabled = isDisabled;
    }
}
