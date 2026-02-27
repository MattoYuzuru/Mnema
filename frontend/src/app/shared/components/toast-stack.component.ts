import { Component } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { TranslatePipe } from '../pipes/translate.pipe';
import { ToastItem, ToastService } from '../../core/services/toast.service';

@Component({
    selector: 'app-toast-stack',
    standalone: true,
    imports: [NgFor, NgIf, TranslatePipe],
    template: `
    <section class="toast-layer" aria-live="polite" aria-atomic="true">
      <article
        *ngFor="let toast of toastService.items(); trackBy: trackById"
        class="toast-card"
        [class.toast-info]="toast.tone === 'info'"
        [class.toast-success]="toast.tone === 'success'"
        [class.toast-warning]="toast.tone === 'warning'"
        [class.toast-error]="toast.tone === 'error'"
        role="status"
      >
        <div class="toast-mark" aria-hidden="true"></div>
        <p class="toast-message">{{ toast.messageKey | translate }}</p>
        <button
          type="button"
          class="toast-close"
          (click)="toastService.dismiss(toast.id)"
          [attr.aria-label]="'toast.close' | translate"
        >
          ×
        </button>
      </article>
    </section>
  `,
    styles: [
        `
      .toast-layer {
        position: fixed;
        top: calc(var(--spacing-lg) + 64px);
        left: 50%;
        transform: translateX(-50%);
        width: min(92vw, 560px);
        display: flex;
        flex-direction: column;
        gap: 0.65rem;
        z-index: 1400;
        pointer-events: none;
      }

      .toast-card {
        pointer-events: auto;
        display: grid;
        grid-template-columns: auto 1fr auto;
        align-items: center;
        gap: 0.7rem;
        padding: 0.72rem 0.85rem;
        border-radius: var(--border-radius-md);
        background: var(--glass-surface-strong);
        border: 1px solid var(--glass-border-strong);
        backdrop-filter: blur(calc(var(--glass-blur) + 8px)) saturate(180%);
        box-shadow: var(--shadow-md);
        animation: toast-slide-in 220ms ease-out both;
      }

      .toast-mark {
        width: 0.5rem;
        height: 0.5rem;
        border-radius: 999px;
        background: var(--color-primary-accent);
        box-shadow: 0 0 0 6px color-mix(in srgb, var(--color-primary-accent) 22%, transparent);
      }

      .toast-message {
        margin: 0;
        color: var(--color-text-primary);
        font-size: 0.9rem;
        line-height: 1.35;
      }

      .toast-close {
        border: 1px solid var(--glass-border);
        background: rgba(255, 255, 255, 0.2);
        color: var(--color-text-secondary);
        width: 1.6rem;
        height: 1.6rem;
        border-radius: 999px;
        cursor: pointer;
        line-height: 1;
        font-size: 1rem;
        transition: transform 0.2s ease, border-color 0.2s ease;
      }

      .toast-close:hover {
        transform: scale(1.05);
        border-color: var(--border-color-hover);
      }

      .toast-success .toast-mark {
        background: #22c55e;
        box-shadow: 0 0 0 6px rgba(34, 197, 94, 0.2);
      }

      .toast-warning .toast-mark {
        background: #f59e0b;
        box-shadow: 0 0 0 6px rgba(245, 158, 11, 0.2);
      }

      .toast-error .toast-mark {
        background: #ef4444;
        box-shadow: 0 0 0 6px rgba(239, 68, 68, 0.2);
      }

      @keyframes toast-slide-in {
        from {
          opacity: 0;
          transform: translateY(-10px) scale(0.985);
        }
        to {
          opacity: 1;
          transform: translateY(0) scale(1);
        }
      }

      @media (max-width: 768px) {
        .toast-layer {
          top: calc(var(--spacing-md) + 52px);
          width: min(94vw, 520px);
        }
      }

      @media (prefers-reduced-motion: reduce) {
        .toast-card {
          animation: none;
        }
      }
    `
    ]
})
export class ToastStackComponent {
    constructor(public readonly toastService: ToastService) {}

    trackById(_: number, item: ToastItem): number {
        return item.id;
    }
}
