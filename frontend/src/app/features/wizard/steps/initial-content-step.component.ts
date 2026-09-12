import { Component, Output, EventEmitter, OnInit, inject, ChangeDetectionStrategy } from '@angular/core';

import { ReactiveFormsModule, FormBuilder, FormGroup, Validators } from '@angular/forms';
import { TemplateApiService } from '../../../core/services/template-api.service';
import { CardApiService } from '../../../core/services/card-api.service';
import { CardTemplateDTO, FieldTemplateDTO } from '../../../core/models/template.models';
import { CardContentValue } from '../../../core/models/user-card.models';
import { DeckWizardStateService, PendingCard } from '../deck-wizard-state.service';
import { ButtonComponent } from '../../../shared/components/button.component';
import { InputComponent } from '../../../shared/components/input.component';
import { TextareaComponent } from '../../../shared/components/textarea.component';
import { MediaUploadComponent } from '../../../shared/components/media-upload.component';
import { TranslatePipe } from '../../../shared/pipes/translate.pipe';
import { I18nService } from '../../../core/services/i18n.service';

@Component({
    selector: 'app-initial-content-step',
    imports: [ReactiveFormsModule, ButtonComponent, InputComponent, TextareaComponent, MediaUploadComponent, TranslatePipe],
    template: `
    <div class="step">
      <h2>{{ 'wizard.addCards' | translate }}</h2>
      <p class="subtitle">{{ 'wizard.addCardsSubtitle' | translate }}</p>
      @if (loading) {
        <div>{{ 'wizard.loadingTemplate' | translate }}</div>
      }
      @if (!loading) {
        <div>
          <form [formGroup]="cardForm" class="card-form">
            @for (field of template?.fields; track field) {
              @if (isMediaField(field)) {
                <app-media-upload
                  [label]="field.label + (field.isRequired ? ' *' : '')"
                  [fieldType]="getMediaFieldType(field)"
                  [value]="getMediaValue(field.name)"
                  (valueChange)="onMediaChange(field.name, $event)"
                ></app-media-upload>
              }
              @if (!isMediaField(field) && field.fieldType !== 'rich_text' && field.fieldType !== 'markdown') {
                <app-input
                  [label]="field.label + (field.isRequired ? ' *' : '')"
                  type="text"
                  [formControlName]="field.name"
                  [placeholder]="field.helpText || ''"
                  [hasError]="cardForm.get(field.name)?.invalid && cardForm.get(field.name)?.touched || false"
                  [errorMessage]="'wizard.required' | translate"
                ></app-input>
              }
              @if (!isMediaField(field) && (field.fieldType === 'rich_text' || field.fieldType === 'markdown')) {
                <app-textarea
                  [label]="field.label + (field.isRequired ? ' *' : '')"
                  [formControlName]="field.name"
                  [placeholder]="field.helpText || ''"
                  [hasError]="cardForm.get(field.name)?.invalid && cardForm.get(field.name)?.touched || false"
                  [errorMessage]="'wizard.required' | translate"
                ></app-textarea>
              }
            }
            <div class="button-container">
              <app-button variant="secondary" [disabled]="cardForm.invalid" (click)="addCard()">{{ 'wizard.addCard' | translate }}</app-button>
            </div>
          </form>
          @if (pendingCards.length > 0) {
            <div class="pending-cards">
              <h4>{{ 'wizard.pendingCards' | translate }} ({{ pendingCards.length }})</h4>
              @for (card of pendingCards; track card; let i = $index) {
                <div class="card-item">
                  <span>{{ getCardPreview(card) }}</span>
                  <app-button variant="ghost" size="sm" (click)="removeCard(i)">{{ 'wizard.remove' | translate }}</app-button>
                </div>
              }
            </div>
          }
        </div>
      }
      <div class="step-actions">
        <app-button variant="ghost" (click)="onBack()">{{ 'wizard.back' | translate }}</app-button>
        <app-button variant="primary" [disabled]="saving" (click)="onNext()">{{ saving ? ('wizard.saving' | translate) : ('wizard.nextReview' | translate) }}</app-button>
      </div>
    </div>
    `,
    changeDetection: ChangeDetectionStrategy.Eager,
    styles: [`
      .step { display: flex; flex-direction: column; gap: var(--spacing-lg); min-width: 0; }
      .card-form { display: flex; flex-direction: column; gap: var(--spacing-md); }
      .button-container { display: flex; justify-content: flex-end; width: 100%; }
      .pending-cards { margin-top: var(--spacing-lg); }
      .pending-cards h4 { font-size: 1rem; font-weight: 600; margin: 0 0 var(--spacing-sm) 0; }
      .card-item { display: flex; align-items: center; justify-content: space-between; padding: var(--spacing-sm) var(--spacing-md); background: var(--color-background); border: 1px solid var(--border-color); border-radius: var(--border-radius-sm); margin-bottom: var(--spacing-xs); }
      .step-actions { display: flex; justify-content: space-between; flex-wrap: wrap; gap: var(--spacing-sm); padding-top: var(--spacing-lg); border-top: 1px solid var(--border-color); }
      .step-actions app-button { flex: 1 1 14rem; }

      @media (max-width: 900px) {
        .card-item {
          flex-direction: column;
          align-items: flex-start;
          gap: var(--spacing-xs);
        }

        .button-container {
          justify-content: stretch;
        }

        .button-container app-button {
          width: 100%;
        }

        .step-actions {
          flex-direction: column;
        }
      }
    `]
})
export class InitialContentStepComponent implements OnInit {
    private fb = inject(FormBuilder);
    private templateApi = inject(TemplateApiService);
    private cardApi = inject(CardApiService);
    private wizardState = inject(DeckWizardStateService);
    private i18n = inject(I18nService);

    @Output() next = new EventEmitter<void>();
    @Output() back = new EventEmitter<void>();
    loading = true;
    saving = false;
    template: CardTemplateDTO | null = null;
    cardForm: FormGroup;
    pendingCards: PendingCard[] = [];

    constructor() {
        this.cardForm = this.fb.group({});
    }

    ngOnInit(): void {
        const { templateId, pendingCards } = this.wizardState.getCurrentState();
        this.pendingCards = [...pendingCards];
        if (!templateId) return;

        this.templateApi.getTemplate(templateId).subscribe({
            next: template => {
                this.template = template;
                const controls: { [key: string]: any } = {};
                template.fields?.forEach(field => {
                    controls[field.name] = field.isRequired ? ['', Validators.required] : [''];
                });
                this.cardForm = this.fb.group(controls);
                this.loading = false;
            },
            error: () => { this.loading = false; }
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
                if (v && typeof v === 'object' && 'mediaId' in v) return this.i18n.translate('wizard.mediaPlaceholder');
                return '';
            })
            .filter(v => v)
            .slice(0, 2)
            .join(' - ');
        return textValues || this.i18n.translate('wizard.emptyCard');
    }

    onBack(): void {
        this.back.emit();
    }

    onNext(): void {
        const { createdDeck } = this.wizardState.getCurrentState();
        if (!createdDeck) return;

        if (this.pendingCards.length === 0) {
            this.next.emit();
            return;
        }

        this.saving = true;
        const cardsToCreate = this.pendingCards.map((card, index) => ({
            content: card.content,
            tags: card.tags || [],
            orderIndex: index + 1
        }));

        this.cardApi.createCardsBatch(createdDeck.userDeckId, cardsToCreate).subscribe({
            next: () => {
                this.wizardState.clearPendingCards();
                this.saving = false;
                this.next.emit();
            },
            error: () => { this.saving = false; }
        });
    }
}
