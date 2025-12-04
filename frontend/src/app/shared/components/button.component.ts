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
        padding: 0.625rem 1.25rem;
        border: 2px solid transparent;
        border-radius: var(--border-radius-md);
        font-size: 0.95rem;
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
        background: var(--color-primary-accent);
        color: #ffffff;
        border-color: var(--color-primary-accent);
      }

      .btn-primary:hover:not(:disabled) {
        opacity: 0.9;
        transform: translateY(-1px);
      }

      .btn-secondary {
        background: var(--color-card-background);
        color: var(--color-text-primary);
        border-color: var(--border-color);
      }

      .btn-secondary:hover:not(:disabled) {
        border-color: var(--color-primary-accent);
        background: var(--color-background);
      }

      .btn-ghost {
        background: transparent;
        color: var(--color-text-primary);
        border-color: var(--border-color);
      }

      .btn-ghost:hover:not(:disabled) {
        background: var(--color-background);
        border-color: var(--border-color-hover);
      }

      .btn-sm {
        padding: 0.5rem 0.875rem;
        font-size: 0.875rem;
      }

      .btn-md {
        padding: 0.625rem 1.25rem;
        font-size: 0.95rem;
      }

      .btn-lg {
        padding: 0.75rem 1.5rem;
        font-size: 1.05rem;
        font-weight: 600;
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
