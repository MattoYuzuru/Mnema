import { Component, Input, Output, EventEmitter } from '@angular/core';
import { NgIf } from '@angular/common';
import { CardTemplateDTO } from '../../core/models/template.models';
import { ButtonComponent } from './button.component';

@Component({
    selector: 'app-template-card',
    standalone: true,
    imports: [NgIf, ButtonComponent],
    template: `
    <div class="template-card" [class.template-card-selected]="selected">
      <div class="template-card-header">
        <div class="template-icon">
          <img *ngIf="template.iconUrl" [src]="template.iconUrl" [alt]="template.name" />
          <span *ngIf="!template.iconUrl" class="template-icon-placeholder">T</span>
        </div>
        <div class="template-info">
          <h4 class="template-name">{{ template.name }}</h4>
          <p class="template-description">{{ template.description }}</p>
        </div>
      </div>

    <div class="template-meta">
        <span class="template-field-count">
          {{ template.fields?.length || 0 }} fields
        </span>
        <span *ngIf="showVisibility" class="template-visibility" [class.public]="template.isPublic">
          {{ template.isPublic ? publicLabel : privateLabel }}
        </span>
      </div>

      <div *ngIf="showActions" class="template-actions">
        <app-button
          *ngIf="showViewButton"
          variant="ghost"
          size="sm"
          (click)="onView($event)"
        >
          {{ viewLabel }}
        </app-button>
        <app-button
          *ngIf="showSelectButton"
          variant="secondary"
          size="sm"
          (click)="onSelect($event)"
        >
          {{ selected ? selectedLabel : selectLabel }}
        </app-button>
      </div>
    </div>
  `,
    styles: [
        `
      .template-card {
        display: flex;
        flex-direction: column;
        gap: var(--spacing-md);
        padding: var(--spacing-md);
        background: var(--color-card-background);
        border: 2px solid var(--border-color);
        border-radius: var(--border-radius-lg);
        box-shadow: var(--shadow-sm);
        transition: all 0.2s ease;
        cursor: pointer;
      }

      .template-card:hover {
        border-color: var(--border-color-hover);
        box-shadow: var(--shadow-md);
      }

      .template-card-selected {
        border-color: var(--color-primary-accent);
        box-shadow: 0 0 0 2px rgba(0, 255, 255, 0.1);
      }

      .template-card-header {
        display: flex;
        gap: var(--spacing-md);
      }

      .template-icon {
        flex-shrink: 0;
        width: 2.5rem;
        height: 2.5rem;
        display: flex;
        align-items: center;
        justify-content: center;
        background: var(--color-background);
        border-radius: var(--border-radius-md);
      }

      .template-icon img {
        width: 100%;
        height: 100%;
        object-fit: cover;
        border-radius: var(--border-radius-md);
      }

      .template-icon-placeholder {
        font-size: 1.25rem;
      }

      .template-info {
        flex: 1;
        min-width: 0;
      }

      .template-name {
        font-size: 1rem;
        font-weight: 600;
        margin: 0 0 var(--spacing-xs) 0;
      }

      .template-description {
        font-size: 0.85rem;
        color: var(--color-text-muted);
        margin: 0;
        line-height: 1.4;
        display: -webkit-box;
        -webkit-line-clamp: 2;
        -webkit-box-orient: vertical;
        overflow: hidden;
      }

      .template-meta {
        display: flex;
        align-items: center;
        justify-content: space-between;
        font-size: 0.85rem;
        color: var(--color-text-muted);
      }

      .template-visibility {
        padding: 0.15rem 0.5rem;
        border-radius: var(--border-radius-full);
        background: var(--color-background);
        border: 1px solid var(--border-color);
        font-size: 0.75rem;
      }

      .template-visibility.public {
        border-color: var(--color-primary-accent);
        color: var(--color-text-primary);
      }

      .template-actions {
        padding-top: var(--spacing-xs);
        border-top: 1px solid var(--border-color);
        display: flex;
        gap: var(--spacing-xs);
        justify-content: flex-end;
      }
    `
    ]
})
export class TemplateCardComponent {
    @Input({ required: true }) template!: CardTemplateDTO;
    @Input() selected = false;
    @Input() showActions = true;
    @Input() showSelectButton = true;
    @Input() showViewButton = false;
    @Input() showVisibility = false;
    @Input() selectLabel = 'Select';
    @Input() selectedLabel = 'Selected';
    @Input() viewLabel = 'View';
    @Input() publicLabel = 'Public';
    @Input() privateLabel = 'Private';
    @Output() select = new EventEmitter<void>();
    @Output() view = new EventEmitter<void>();

    onSelect(event: Event): void {
        event.stopPropagation();
        this.select.emit();
    }

    onView(event: Event): void {
        event.stopPropagation();
        this.view.emit();
    }
}
