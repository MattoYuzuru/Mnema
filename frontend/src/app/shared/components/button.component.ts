import { Component, Input } from '@angular/core';
import { NgClass } from '@angular/common';

@Component({
    selector: 'app-button',
    standalone: true,
    imports: [NgClass],
    template: `
    <button
      [type]="type"
      [disabled]="disabled"
      [ngClass]="{
        'btn': true,
        'btn-primary': variant === 'primary',
        'btn-secondary': variant === 'secondary',
        'btn-ghost': variant === 'ghost',
        'btn-sm': size === 'sm',
        'btn-md': size === 'md',
        'btn-lg': size === 'lg',
        'btn-full': fullWidth
      }"
    >
      <ng-content></ng-content>
    </button>
  `,
    styles: [
        `
      .btn {
        display: inline-flex;
        align-items: center;
        justify-content: center;
        gap: var(--spacing-sm);
        padding: var(--spacing-sm) var(--spacing-md);
        border: 1px solid transparent;
        border-radius: var(--border-radius-full);
        font-size: 0.9rem;
        font-weight: 500;
        cursor: pointer;
        transition: all 0.2s ease;
        white-space: nowrap;
      }

      .btn:disabled {
        opacity: 0.5;
        cursor: not-allowed;
      }

      .btn-primary {
        background: #111827;
        color: #fff;
        border-color: #111827;
      }

      .btn-primary:hover:not(:disabled) {
        background: #000;
        border-color: #000;
      }

      .btn-secondary {
        background: var(--color-card-background);
        color: var(--color-text-primary);
        border-color: var(--border-color);
      }

      .btn-secondary:hover:not(:disabled) {
        border-color: var(--border-color-hover);
        background: var(--color-background);
      }

      .btn-ghost {
        background: transparent;
        color: var(--color-text-primary);
        border-color: var(--border-color);
      }

      .btn-ghost:hover:not(:disabled) {
        background: var(--color-background);
      }

      .btn-sm {
        padding: var(--spacing-xs) var(--spacing-sm);
        font-size: 0.85rem;
      }

      .btn-md {
        padding: var(--spacing-sm) var(--spacing-md);
        font-size: 0.9rem;
      }

      .btn-lg {
        padding: var(--spacing-md) var(--spacing-lg);
        font-size: 1rem;
      }

      .btn-full {
        width: 100%;
      }
    `
    ]
})
export class ButtonComponent {
    @Input() variant: 'primary' | 'secondary' | 'ghost' = 'primary';
    @Input() size: 'sm' | 'md' | 'lg' = 'md';
    @Input() type: 'button' | 'submit' | 'reset' = 'button';
    @Input() disabled = false;
    @Input() fullWidth = false;
}
