import { Component, Output, EventEmitter, OnInit } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { ReactiveFormsModule, FormBuilder, FormGroup, Validators } from '@angular/forms';
import { TemplateApiService } from '../../../core/services/template-api.service';
import { CardApiService } from '../../../core/services/card-api.service';
import { CardTemplateDTO } from '../../../core/models/template.models';
import { DeckWizardStateService, PendingCard } from '../deck-wizard-state.service';
import { ButtonComponent } from '../../../shared/components/button.component';
import { InputComponent } from '../../../shared/components/input.component';
import { TextareaComponent } from '../../../shared/components/textarea.component';

@Component({
    selector: 'app-initial-content-step',
    standalone: true,
    imports: [ReactiveFormsModule, NgFor, NgIf, ButtonComponent, InputComponent, TextareaComponent],
    template: `
    <div class="step">
      <h2>Add Cards</h2>
      <p class="subtitle">Create initial cards for your deck (optional)</p>
      <div *ngIf="loading">Loading template...</div>
      <div *ngIf="!loading">
        <form [formGroup]="cardForm" class="card-form">
          <app-input
            *ngFor="let field of template?.fields"
            [label]="field.label + (field.isRequired ? ' *' : '')"
            type="text"
            [formControlName]="field.name"
            [placeholder]="field.helpText || ''"
            [hasError]="cardForm.get(field.name)?.invalid && cardForm.get(field.name)?.touched || false"
            [errorMessage]="'Required'"
          ></app-input>
          <app-button variant="secondary" [disabled]="cardForm.invalid" (click)="addCard()">Add Card</app-button>
        </form>
        <div *ngIf="pendingCards.length > 0" class="pending-cards">
          <h4>Pending Cards ({{ pendingCards.length }})</h4>
          <div *ngFor="let card of pendingCards; let i = index" class="card-item">
            <span>{{ getCardPreview(card) }}</span>
            <app-button variant="ghost" size="sm" (click)="removeCard(i)">Remove</app-button>
          </div>
        </div>
      </div>
      <div class="step-actions">
        <app-button variant="ghost" (click)="onBack()">Back</app-button>
        <app-button variant="primary" [disabled]="saving" (click)="onNext()">{{ saving ? 'Saving...' : 'Next: Review' }}</app-button>
      </div>
    </div>
  `,
    styles: [`
      .step { display: flex; flex-direction: column; gap: var(--spacing-lg); }
      .card-form { display: flex; flex-direction: column; gap: var(--spacing-md); align-items: flex-start; }
      .pending-cards { margin-top: var(--spacing-lg); }
      .pending-cards h4 { font-size: 1rem; font-weight: 600; margin: 0 0 var(--spacing-sm) 0; }
      .card-item { display: flex; align-items: center; justify-content: space-between; padding: var(--spacing-sm) var(--spacing-md); background: var(--color-background); border: 1px solid var(--border-color); border-radius: var(--border-radius-sm); margin-bottom: var(--spacing-xs); }
      .step-actions { display: flex; justify-content: space-between; padding-top: var(--spacing-lg); border-top: 1px solid var(--border-color); }
    `]
})
export class InitialContentStepComponent implements OnInit {
    @Output() next = new EventEmitter<void>();
    @Output() back = new EventEmitter<void>();
    loading = true;
    saving = false;
    template: CardTemplateDTO | null = null;
    cardForm: FormGroup;
    pendingCards: PendingCard[] = [];

    constructor(private fb: FormBuilder, private templateApi: TemplateApiService, private cardApi: CardApiService, private wizardState: DeckWizardStateService) {
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

    getCardPreview(card: PendingCard): string {
        return Object.values(card.content).filter(v => v).slice(0, 2).join(' - ') || 'Empty card';
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
