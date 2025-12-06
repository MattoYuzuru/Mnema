import { Component, Input, Output, EventEmitter } from '@angular/core';
import { NgIf } from '@angular/common';
import { ButtonComponent } from './button.component';

@Component({
    selector: 'app-confirmation-dialog',
    standalone: true,
    imports: [NgIf, ButtonComponent],
    template: `
    <div *ngIf="open" class="dialog-backdrop" (click)="onCancel()">
      <div class="dialog-content" (click)="$event.stopPropagation()">
        <h3 class="dialog-title">{{ title }}</h3>
        <p class="dialog-message">{{ message }}</p>

        <div class="dialog-actions">
          <app-button
            variant="ghost"
            (click)="onCancel()"
          >
            {{ cancelText }}
          </app-button>
          <app-button
            [variant]="confirmVariant"
            (click)="onConfirm()"
          >
            {{ confirmText }}
          </app-button>
        </div>
      </div>
    </div>
  `,
    styles: [
        `
      .dialog-backdrop {
        position: fixed;
        top: 0;
        left: 0;
        right: 0;
        bottom: 0;
        display: flex;
        align-items: center;
        justify-content: center;
        background: rgba(0, 0, 0, 0.5);
        backdrop-filter: blur(2px);
        z-index: 10000;
      }

      .dialog-content {
        width: 100%;
        max-width: 28rem;
        padding: var(--spacing-xl);
        background: var(--color-card-background);
        border-radius: var(--border-radius-lg);
        box-shadow: var(--shadow-lg);
      }

      .dialog-title {
        font-size: 1.25rem;
        font-weight: 600;
        margin: 0 0 var(--spacing-md) 0;
      }

      .dialog-message {
        font-size: 0.95rem;
        line-height: 1.6;
        color: var(--color-text-muted);
        margin: 0 0 var(--spacing-xl) 0;
      }

      .dialog-actions {
        display: flex;
        justify-content: flex-end;
        gap: var(--spacing-sm);
      }
    `
    ]
})
export class ConfirmationDialogComponent {
    @Input() open = false;
    @Input() title = 'Confirm';
    @Input() message = 'Are you sure?';
    @Input() confirmText = 'Confirm';
    @Input() cancelText = 'Cancel';
    @Input() confirmVariant: 'primary' | 'secondary' | 'ghost' = 'primary';

    @Output() confirm = new EventEmitter<void>();
    @Output() cancel = new EventEmitter<void>();

    onConfirm(): void {
        this.confirm.emit();
    }

    onCancel(): void {
        this.cancel.emit();
    }
}
