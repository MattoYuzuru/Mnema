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
        'btn-danger': tone === 'danger',
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
        padding: 0.7rem 1.4rem;
        border: 1px solid transparent;
        border-radius: var(--border-radius-full);
        font-size: 0.95rem;
        font-weight: 600;
        cursor: pointer;
        transition: all 0.25s ease;
        white-space: nowrap;
        min-width: 5rem;
        position: relative;
        overflow: hidden;
      }

      .btn:disabled {
        opacity: 0.5;
        cursor: not-allowed;
      }

      .btn-primary {
        background: linear-gradient(135deg, var(--color-primary-accent), var(--color-secondary-accent));
        color: #ffffff;
        border-color: rgba(255, 255, 255, 0.2);
        box-shadow: 0 12px 30px rgba(14, 165, 233, 0.25);
      }

      .btn-primary:hover:not(:disabled) {
        transform: translateY(-2px);
        box-shadow: 0 18px 36px rgba(14, 165, 233, 0.3);
      }

      .btn-secondary {
        background: var(--glass-surface);
        color: var(--color-text-primary);
        border-color: var(--glass-border);
        backdrop-filter: blur(var(--glass-blur));
      }

      .btn-secondary:hover:not(:disabled) {
        border-color: var(--border-color-hover);
        background: var(--glass-surface-strong);
      }

      .btn-ghost {
        background: rgba(255, 255, 255, 0.05);
        color: var(--color-text-primary);
        border-color: var(--glass-border);
        backdrop-filter: blur(var(--glass-blur));
      }

      .btn-ghost:hover:not(:disabled) {
        background: var(--glass-surface);
        border-color: var(--border-color-hover);
      }

      .btn-danger {
        color: #dc2626;
        border-color: #dc2626;
      }

      .btn-danger.btn-primary {
        background: #dc2626;
        color: #ffffff;
        border-color: #dc2626;
      }

      .btn-danger.btn-ghost:hover:not(:disabled),
      .btn-danger.btn-secondary:hover:not(:disabled) {
        background: #dc2626;
        color: #ffffff;
        border-color: #dc2626;
      }

      .btn-sm {
        padding: 0.5rem 1rem;
        font-size: 0.875rem;
      }

      .btn-md {
        padding: 0.625rem 1.25rem;
        font-size: 0.95rem;
      }

      .btn-lg {
        padding: 0.85rem 1.8rem;
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
    @Input() tone: 'default' | 'danger' = 'default';
}
