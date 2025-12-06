import { Component, Input, Output, EventEmitter } from '@angular/core';
import { NgIf } from '@angular/common';
import { ButtonComponent } from './button.component';

@Component({
    selector: 'app-empty-state',
    standalone: true,
    imports: [NgIf, ButtonComponent],
    template: `
    <div class="empty-state">
      <div class="empty-icon">{{ icon }}</div>
      <h3 class="empty-title">{{ title }}</h3>
      <p *ngIf="description" class="empty-description">{{ description }}</p>
      <app-button
        *ngIf="actionText"
        [variant]="actionVariant"
        (click)="action.emit()"
      >
        {{ actionText }}
      </app-button>
    </div>
  `,
    styles: [
        `
      .empty-state {
        display: flex;
        flex-direction: column;
        align-items: center;
        justify-content: center;
        gap: var(--spacing-md);
        padding: var(--spacing-2xl);
        text-align: center;
      }

      .empty-icon {
        font-size: 3rem;
        opacity: 0.5;
      }

      .empty-title {
        font-size: 1.25rem;
        font-weight: 600;
        margin: 0;
      }

      .empty-description {
        font-size: 0.95rem;
        color: var(--color-text-muted);
        max-width: 32rem;
        margin: 0;
        line-height: 1.6;
      }
    `
    ]
})
export class EmptyStateComponent {
    @Input() icon = '📭';
    @Input() title = 'Nothing here';
    @Input() description = '';
    @Input() actionText = '';
    @Input() actionVariant: 'primary' | 'secondary' | 'ghost' = 'primary';

    @Output() action = new EventEmitter<void>();
}
