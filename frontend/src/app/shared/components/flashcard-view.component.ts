import { Component, Input, OnChanges, SimpleChanges } from '@angular/core';
import { NgFor, NgIf, NgClass } from '@angular/common';
import { CardTemplateDTO, FieldTemplateDTO } from '../../core/models/template.models';
import { CardContentValue } from '../../core/models/user-card.models';
import { markdownToHtml } from '../utils/markdown.util';

interface RenderedField {
    field: FieldTemplateDTO;
    value: string | null;
    rawValue: CardContentValue;
}

@Component({
    selector: 'app-flashcard-view',
    standalone: true,
    imports: [NgFor, NgIf, NgClass],
    template: `
    <div class="flashcard-view">
      <div *ngIf="side === 'front'" class="card-side front">
        <div *ngFor="let rf of frontFields" class="field-block">
          <div *ngIf="!hideLabels" class="field-label">{{ rf.field.label }}</div>
          <div class="field-value" [ngClass]="'field-type-' + rf.field.fieldType">
            <ng-container *ngIf="rf.field.fieldType === 'image' && rf.value">
              <img [src]="rf.value" [alt]="rf.field.label" class="field-image" />
            </ng-container>
            <ng-container *ngIf="rf.field.fieldType === 'audio' && rf.value">
              <audio controls [src]="rf.value"></audio>
            </ng-container>
            <ng-container *ngIf="rf.field.fieldType === 'video' && rf.value">
              <video controls [src]="rf.value" class="field-video"></video>
            </ng-container>
            <ng-container *ngIf="rf.field.fieldType !== 'image' && rf.field.fieldType !== 'audio' && rf.field.fieldType !== 'video'">
              <div [innerHTML]="formatValue(rf.value, rf.field.fieldType)"></div>
            </ng-container>
          </div>
        </div>
      </div>

      <div *ngIf="side === 'back'" class="card-side back">
        <div *ngFor="let rf of backFields" class="field-block">
          <div *ngIf="!hideLabels" class="field-label">{{ rf.field.label }}</div>
          <div class="field-value" [ngClass]="'field-type-' + rf.field.fieldType">
            <ng-container *ngIf="rf.field.fieldType === 'image' && rf.value">
              <img [src]="rf.value" [alt]="rf.field.label" class="field-image" />
            </ng-container>
            <ng-container *ngIf="rf.field.fieldType === 'audio' && rf.value">
              <audio controls [src]="rf.value"></audio>
            </ng-container>
            <ng-container *ngIf="rf.field.fieldType === 'video' && rf.value">
              <video controls [src]="rf.value" class="field-video"></video>
            </ng-container>
            <ng-container *ngIf="rf.field.fieldType !== 'image' && rf.field.fieldType !== 'audio' && rf.field.fieldType !== 'video'">
              <div [innerHTML]="formatValue(rf.value, rf.field.fieldType)"></div>
            </ng-container>
          </div>
        </div>
      </div>
    </div>
  `,
    styles: [
        `
      .flashcard-view {
        width: 100%;
      }

      .card-side {
        display: flex;
        flex-direction: column;
        gap: var(--spacing-md);
      }

      .field-block {
        display: flex;
        flex-direction: column;
        gap: var(--spacing-xs);
      }

      .field-label {
        font-size: 0.85rem;
        font-weight: 600;
        color: var(--color-text-secondary);
        text-transform: uppercase;
        letter-spacing: 0.5px;
      }

      .field-value {
        font-size: 1rem;
        color: var(--color-text-primary);
        line-height: 1.5;
      }

      .field-type-rich_text .field-value {
        white-space: pre-wrap;
      }

      .field-image {
        max-width: 100%;
        height: auto;
        border-radius: var(--border-radius-md);
      }

      .field-video {
        max-width: 100%;
        height: auto;
        border-radius: var(--border-radius-md);
      }

      audio {
        width: 100%;
      }
    `
    ]
})
export class FlashcardViewComponent implements OnChanges {
    @Input() template!: CardTemplateDTO;
    @Input() content: Record<string, unknown> = {};
    @Input() side: 'front' | 'back' = 'front';
    @Input() hideLabels = false;

    frontFields: RenderedField[] = [];
    backFields: RenderedField[] = [];

    ngOnChanges(changes: SimpleChanges): void {
        if (changes['template'] || changes['content'] || changes['side']) {
            this.buildFields();
        }
    }

    private buildFields(): void {
        if (!this.template || !this.template.fields) {
            this.frontFields = [];
            this.backFields = [];
            return;
        }

        const fieldsMap = new Map<string, FieldTemplateDTO>();
        this.template.fields.forEach(f => fieldsMap.set(f.name, f));

        this.frontFields = (this.template.layout?.front || [])
            .map(name => fieldsMap.get(name))
            .filter((f): f is FieldTemplateDTO => f !== undefined)
            .sort((a, b) => a.orderIndex - b.orderIndex)
            .map(field => {
                const rawValue = this.content[field.name] as CardContentValue;
                return {
                    field,
                    value: this.extractStringValue(rawValue),
                    rawValue
                };
            });

        this.backFields = (this.template.layout?.back || [])
            .map(name => fieldsMap.get(name))
            .filter((f): f is FieldTemplateDTO => f !== undefined)
            .sort((a, b) => a.orderIndex - b.orderIndex)
            .map(field => {
                const rawValue = this.content[field.name] as CardContentValue;
                return {
                    field,
                    value: this.extractStringValue(rawValue),
                    rawValue
                };
            });
    }

    private extractStringValue(value: CardContentValue): string | null {
        if (value === null || value === undefined) {
            return null;
        }
        if (typeof value === 'string') {
            return value;
        }
        return value.url || null;
    }

    formatValue(value: string | null, fieldType: string = 'text'): string {
        if (!value) {
            return '<span style="color: var(--color-text-tertiary); font-style: italic;">No content</span>';
        }
        if (fieldType === 'markdown') {
            return markdownToHtml(value);
        }
        return value.replace(/\n/g, '<br>');
    }
}
