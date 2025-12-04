import { Component, Input, forwardRef } from '@angular/core';
import { ControlValueAccessor, NG_VALUE_ACCESSOR } from '@angular/forms';
import { NgIf, NgClass } from '@angular/common';

@Component({
    selector: 'app-input',
    standalone: true,
    imports: [NgIf, NgClass],
    providers: [
        {
            provide: NG_VALUE_ACCESSOR,
            useExisting: forwardRef(() => InputComponent),
            multi: true
        }
    ],
    template: `
    <div class="input-wrapper">
      <label *ngIf="label" [for]="id" class="input-label">{{ label }}</label>
      <input
        [id]="id"
        [type]="type"
        [placeholder]="placeholder"
        [disabled]="disabled"
        [ngClass]="{
          'input': true,
          'input-error': hasError
        }"
        [value]="value"
        (input)="onInput($event)"
        (blur)="onTouched()"
      />
      <div *ngIf="hasError && errorMessage" class="error-message">
        {{ errorMessage }}
      </div>
    </div>
  `,
    styles: [
        `
      .input-wrapper {
        display: flex;
        flex-direction: column;
        gap: var(--spacing-xs);
      }

      .input-label {
        font-size: 0.9rem;
        font-weight: 500;
        color: var(--color-text-primary);
      }

      .input {
        padding: var(--spacing-sm) var(--spacing-md);
        border: 1px solid var(--border-color);
        border-radius: var(--border-radius-md);
        font-size: 0.9rem;
        font-family: inherit;
        background: var(--color-card-background);
        color: var(--color-text-primary);
        transition: border-color 0.2s ease;
      }

      .input:focus {
        outline: none;
        border-color: var(--color-primary-accent);
      }

      .input:disabled {
        opacity: 0.5;
        cursor: not-allowed;
      }

      .input-error {
        border-color: #dc2626;
      }

      .error-message {
        font-size: 0.85rem;
        color: #dc2626;
      }
    `
    ]
})
export class InputComponent implements ControlValueAccessor {
    @Input() label = '';
    @Input() type: 'text' | 'email' | 'password' | 'url' | 'search' = 'text';
    @Input() placeholder = '';
    @Input() disabled = false;
    @Input() hasError = false;
    @Input() errorMessage = '';
    @Input() id = `input-${Math.random().toString(36).substr(2, 9)}`;

    value = '';
    onChange: (value: string) => void = () => {};
    onTouched: () => void = () => {};

    onInput(event: Event): void {
        const target = event.target as HTMLInputElement;
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
