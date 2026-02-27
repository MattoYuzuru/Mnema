import { Component, Input, Output, EventEmitter, OnInit } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { ReactiveFormsModule, FormBuilder, FormGroup, Validators } from '@angular/forms';
import { forkJoin } from 'rxjs';
import { PublicDeckApiService } from '../../core/services/public-deck-api.service';
import { TemplateApiService } from '../../core/services/template-api.service';
import { CardApiService } from '../../core/services/card-api.service';
import { CardTemplateDTO, FieldTemplateDTO } from '../../core/models/template.models';
import { CardContentValue } from '../../core/models/user-card.models';
import { ButtonComponent } from '../../shared/components/button.component';
import { InputComponent } from '../../shared/components/input.component';
import { TextareaComponent } from '../../shared/components/textarea.component';
import { MediaUploadComponent } from '../../shared/components/media-upload.component';
import { TranslatePipe } from '../../shared/pipes/translate.pipe';

interface PendingCard {
    content: { [key: string]: CardContentValue };
}

@Component({
    selector: 'app-add-cards-modal',
    standalone: true,
    imports: [ReactiveFormsModule, NgFor, NgIf, ButtonComponent, InputComponent, TextareaComponent, MediaUploadComponent, TranslatePipe],
    template: `
    <div class="modal-overlay" (click)="onCancel()">
      <div class="modal-content" (click)="$event.stopPropagation()">
        <div class="modal-header">
          <h2>{{ 'addCards.title' | translate }}</h2>
          <button class="close-btn" (click)="onCancel()">&times;</button>
        </div>

        <div *ngIf="loading" class="loading-section">{{ 'addCards.loadingTemplate' | translate }}</div>

        <div *ngIf="!loading && template" class="modal-body">
          <form [formGroup]="cardForm" class="card-form">
            <div *ngFor="let field of template.fields" class="field-group">
              <app-media-upload
                *ngIf="isMediaField(field)"
                [label]="field.label + (field.isRequired ? ' *' : '')"
                [fieldType]="getMediaFieldType(field)"
                [value]="getMediaValue(field.name)"
                (valueChange)="onMediaChange(field.name, $event)"
              ></app-media-upload>

              <app-input
                *ngIf="field.fieldType === 'text'"
                [label]="field.label + (field.isRequired ? ' *' : '')"
                type="text"
                [formControlName]="field.name"
                [placeholder]="field.helpText || 'Enter ' + field.label"
                [hasError]="cardForm.get(field.name)?.invalid && cardForm.get(field.name)?.touched || false"
                [errorMessage]="'wizard.required' | translate"
              ></app-input>

              <app-textarea
                *ngIf="field.fieldType === 'rich_text' || field.fieldType === 'markdown'"
                [label]="field.label + (field.isRequired ? ' *' : '')"
                [formControlName]="field.name"
                [placeholder]="field.helpText || (field.fieldType === 'markdown' ? 'Use **bold**, *italic*, inline code' : 'Enter ' + field.label)"
                [rows]="4"
              ></app-textarea>
            </div>

            <div class="form-actions">
              <app-button variant="secondary" type="button" [disabled]="cardForm.invalid" (click)="addCard()">
                {{ 'addCards.addToList' | translate }} ({{ pendingCards.length }})
              </app-button>
            </div>
          </form>

          <div *ngIf="pendingCards.length > 0" class="pending-cards">
            <h3>{{ 'addCards.cardsToAdd' | translate }} ({{ pendingCards.length }})</h3>
            <div class="card-list">
              <div *ngFor="let card of pendingCards; let i = index" class="card-item">
                <span class="card-preview">{{ getCardPreview(card) }}</span>
                <button class="remove-btn" (click)="removeCard(i)">&times;</button>
              </div>
            </div>
          </div>
        </div>

        <div class="modal-footer">
          <app-button variant="ghost" (click)="onCancel()" [disabled]="saving">{{ 'addCards.cancel' | translate }}</app-button>
          <app-button variant="primary" (click)="saveCards()" [disabled]="pendingCards.length === 0 || saving">
            {{ saving ? ('addCards.saving' | translate) : (('addCards.saveCards' | translate).replace('{{count}}', pendingCards.length.toString())) }}
          </app-button>
        </div>
      </div>
    </div>
  `,
    styles: [`
      .modal-overlay { position: fixed; top: 0; left: 0; right: 0; bottom: 0; background: rgba(8, 12, 22, 0.55); display: flex; align-items: center; justify-content: center; z-index: 1000; backdrop-filter: blur(12px) saturate(140%); }
      .modal-content { background: var(--color-surface-solid); border-radius: var(--border-radius-lg); max-width: 800px; width: 90%; max-height: 90vh; display: flex; flex-direction: column; border: 1px solid var(--glass-border); box-shadow: var(--shadow-lg); }
      .modal-header { display: flex; justify-content: space-between; align-items: center; padding: var(--spacing-lg); border-bottom: 1px solid var(--glass-border); flex-shrink: 0; }
      .modal-header h2 { margin: 0; font-size: 1.5rem; font-weight: 600; }
      .close-btn { background: none; border: none; font-size: 2rem; cursor: pointer; color: var(--color-text-secondary); line-height: 1; padding: 0; }
      .modal-body { padding: var(--spacing-lg); overflow-y: auto; flex: 1; scrollbar-width: thin; scrollbar-color: var(--glass-border-strong) transparent; }
      .modal-body::-webkit-scrollbar { width: 8px; }
      .modal-body::-webkit-scrollbar-track { background: transparent; }
      .modal-body::-webkit-scrollbar-thumb { background: var(--glass-border-strong); border-radius: 999px; border: 2px solid transparent; background-clip: padding-box; }
      .modal-body::-webkit-scrollbar-thumb:hover { background: var(--border-color-hover); }
      .loading-section { padding: var(--spacing-xxl); text-align: center; color: var(--color-text-secondary); }
      .card-form { display: flex; flex-direction: column; gap: var(--spacing-md); }
      .field-group { display: flex; flex-direction: column; }
      .form-actions { padding-top: var(--spacing-md); border-top: 1px solid var(--glass-border); display: flex; justify-content: flex-end; }
      .pending-cards { margin-top: var(--spacing-xl); padding-top: var(--spacing-xl); border-top: 2px solid var(--glass-border); }
      .pending-cards h3 { margin: 0 0 var(--spacing-md) 0; font-size: 1.1rem; font-weight: 600; }
      .card-list { display: flex; flex-direction: column; gap: var(--spacing-xs); }
      .card-item { display: flex; align-items: center; gap: var(--spacing-md); padding: var(--spacing-sm) var(--spacing-md); background: var(--color-background); border: 1px solid var(--border-color); border-radius: var(--border-radius-md); }
      .card-preview { flex: 1; font-size: 0.9rem; color: var(--color-text-secondary); }
      .remove-btn { background: none; border: none; font-size: 1.5rem; cursor: pointer; color: var(--color-text-secondary); line-height: 1; padding: 0; width: 24px; height: 24px; }
      .remove-btn:hover { color: var(--color-text-primary); }
      .modal-footer { display: flex; justify-content: flex-end; gap: var(--spacing-md); padding: var(--spacing-lg); border-top: 1px solid var(--glass-border); flex-shrink: 0; }
    `]
})
export class AddCardsModalComponent implements OnInit {
    @Input() userDeckId = '';
    @Input() publicDeckId = '';
    @Input() templateVersion: number | null = null;
    @Output() saved = new EventEmitter<void>();
    @Output() cancelled = new EventEmitter<void>();

    loading = false;
    saving = false;
    template: CardTemplateDTO | null = null;
    cardForm!: FormGroup;
    pendingCards: PendingCard[] = [];

    constructor(
        private fb: FormBuilder,
        private publicDeckApi: PublicDeckApiService,
        private templateApi: TemplateApiService,
        private cardApi: CardApiService
    ) {}

    ngOnInit(): void {
        this.loadTemplate();
    }

    private loadTemplate(): void {
        this.loading = true;
        this.publicDeckApi.getPublicDeck(this.publicDeckId).subscribe({
            next: publicDeck => {
                const templateVersion = this.templateVersion ?? publicDeck.templateVersion ?? null;
                this.templateApi.getTemplate(publicDeck.templateId, templateVersion).subscribe({
                    next: template => {
                        this.template = template;
                        const controls: { [key: string]: any } = {};
                        template.fields?.forEach(field => {
                            controls[field.name] = field.isRequired ? ['', Validators.required] : [''];
                        });
                        this.cardForm = this.fb.group(controls);
                        this.loading = false;
                    },
                    error: () => {
                        this.loading = false;
                    }
                });
            },
            error: () => {
                this.loading = false;
            }
        });
    }

    addCard(): void {
        if (this.cardForm.invalid) {
            Object.keys(this.cardForm.controls).forEach(key => {
                this.cardForm.get(key)?.markAsTouched();
            });
            return;
        }

        const content = this.cardForm.value;
        if (Object.values(content).some(v => v && String(v).trim())) {
            this.pendingCards.push({ content });
            this.cardForm.reset();
        }
    }

    removeCard(index: number): void {
        this.pendingCards.splice(index, 1);
    }

    isMediaField(field: FieldTemplateDTO): boolean {
        return field.fieldType === 'image' || field.fieldType === 'audio' || field.fieldType === 'video';
    }

    getMediaFieldType(field: FieldTemplateDTO): 'image' | 'audio' | 'video' {
        return field.fieldType as 'image' | 'audio' | 'video';
    }

    getMediaValue(fieldName: string): CardContentValue | null {
        const value = this.cardForm.get(fieldName)?.value;
        if (!value) return null;
        return value;
    }

    onMediaChange(fieldName: string, value: CardContentValue | null): void {
        this.cardForm.get(fieldName)?.setValue(value);
        this.cardForm.get(fieldName)?.markAsTouched();
    }

    getCardPreview(card: PendingCard): string {
        const textValues = Object.values(card.content)
            .filter(v => v)
            .map(v => {
                if (typeof v === 'string') return v;
                if (v && typeof v === 'object' && 'mediaId' in v) return '[Media]';
                return '';
            })
            .filter(v => v)
            .slice(0, 2)
            .join(' - ');
        return textValues || 'Empty card';
    }

    saveCards(): void {
        if (this.pendingCards.length === 0) return;

        this.saving = true;
        const cardsToCreate = this.pendingCards.map((card, index) => ({
            content: card.content,
            tags: [],
            orderIndex: index + 1
        }));

        this.cardApi.createCardsBatch(this.userDeckId, cardsToCreate).subscribe({
            next: () => {
                this.saving = false;
                this.saved.emit();
            },
            error: err => {
                console.error('Failed to save cards:', err);
                this.saving = false;
            }
        });
    }

    onCancel(): void {
        this.cancelled.emit();
    }
}
