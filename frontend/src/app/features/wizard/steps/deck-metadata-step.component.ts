import { Component, Output, EventEmitter, OnInit } from '@angular/core';
import { ReactiveFormsModule, FormsModule, FormBuilder, FormGroup, Validators } from '@angular/forms';
import { NgFor, NgIf } from '@angular/common';
import { DeckApiService } from '../../../core/services/deck-api.service';
import { DeckWizardStateService } from '../deck-wizard-state.service';
import { ButtonComponent } from '../../../shared/components/button.component';
import { InputComponent } from '../../../shared/components/input.component';
import { TextareaComponent } from '../../../shared/components/textarea.component';

@Component({
    selector: 'app-deck-metadata-step',
    standalone: true,
    imports: [ReactiveFormsModule, FormsModule, NgFor, NgIf, ButtonComponent, InputComponent, TextareaComponent],
    template: `
    <div class="step">
      <h2>Deck Information</h2>
      <form [formGroup]="form" class="form">
        <app-input label="Deck Name" type="text" formControlName="name" placeholder="e.g., Spanish Vocabulary" [hasError]="form.get('name')?.invalid && form.get('name')?.touched || false" errorMessage="Required"></app-input>
        <app-textarea label="Description" formControlName="description" placeholder="Describe what this deck is about" [rows]="4"></app-textarea>
        <app-input label="Language" type="text" formControlName="language" placeholder="e.g., en, es, fr"></app-input>
        <div class="form-group">
          <label>Tags</label>
          <input type="text" class="tag-input" [(ngModel)]="tagInput" [ngModelOptions]="{standalone: true}" (keydown.enter)="addTag($event)" placeholder="Type and press Enter" />
          <div *ngIf="tags.length > 0" class="tags-list">
            <span *ngFor="let tag of tags; let i = index" class="tag-chip">{{ tag }} <button type="button" (click)="removeTag(i)">×</button></span>
          </div>
        </div>
        <label class="checkbox-label"><input type="checkbox" formControlName="isPublic" /> Make this deck public</label>
        <label class="checkbox-label"><input type="checkbox" formControlName="isListed" [disabled]="!form.get('isPublic')?.value" /> List in public catalog</label>
      </form>
      <div class="step-actions">
        <app-button variant="ghost" (click)="onBack()">Back</app-button>
        <app-button variant="primary" [disabled]="form.invalid || saving" (click)="onNext()">{{ saving ? 'Creating...' : 'Next: Add Content' }}</app-button>
      </div>
    </div>
  `,
    styles: [`
      .step { display: flex; flex-direction: column; gap: var(--spacing-lg); }
      .form { display: flex; flex-direction: column; gap: var(--spacing-lg); }
      .form-group { display: flex; flex-direction: column; gap: var(--spacing-xs); }
      .tag-input { width: 100%; padding: var(--spacing-sm) var(--spacing-md); border: 1px solid var(--border-color); border-radius: var(--border-radius-md); font-size: 0.9rem; background: var(--color-card-background); }
      .tags-list { display: flex; flex-wrap: wrap; gap: var(--spacing-xs); margin-top: var(--spacing-sm); }
      .tag-chip { display: inline-flex; align-items: center; gap: var(--spacing-xs); padding: var(--spacing-xs) var(--spacing-sm); background: var(--color-background); border: 1px solid var(--border-color); border-radius: var(--border-radius-full); font-size: 0.85rem; }
      .tag-chip button { background: none; border: none; cursor: pointer; font-size: 1.2rem; line-height: 1; padding: 0; }
      .checkbox-label { display: flex; align-items: center; gap: var(--spacing-sm); cursor: pointer; font-size: 0.9rem; }
      .step-actions { display: flex; justify-content: space-between; padding-top: var(--spacing-lg); border-top: 1px solid var(--border-color); }
    `]
})
export class DeckMetadataStepComponent implements OnInit {
    @Output() next = new EventEmitter<void>();
    @Output() back = new EventEmitter<void>();
    form: FormGroup;
    tags: string[] = [];
    tagInput = '';
    saving = false;

    constructor(private fb: FormBuilder, private deckApi: DeckApiService, private wizardState: DeckWizardStateService) {
        this.form = this.fb.group({
            name: ['', Validators.required],
            description: [''],
            language: ['en'],
            isPublic: [false],
            isListed: [false]
        });
    }

    ngOnInit(): void {
        const { deckMetadata } = this.wizardState.getCurrentState();
        this.form.patchValue(deckMetadata);
        this.tags = [...deckMetadata.tags];
    }

    addTag(event: Event): void {
        event.preventDefault();
        const tag = this.tagInput.trim();
        if (tag && !this.tags.includes(tag)) {
            this.tags.push(tag);
            this.tagInput = '';
        }
    }

    removeTag(index: number): void {
        this.tags.splice(index, 1);
    }

    onBack(): void {
        this.saveFormData();
        this.back.emit();
    }

    onNext(): void {
        if (this.form.invalid) return;
        this.saving = true;
        this.saveFormData();

        const { templateId, deckMetadata } = this.wizardState.getCurrentState();
        if (!templateId) return;

        this.deckApi.createDeck({
            name: deckMetadata.name,
            description: deckMetadata.description,
            templateId,
            isPublic: deckMetadata.isPublic,
            isListed: deckMetadata.isListed,
            language: deckMetadata.language,
            tags: deckMetadata.tags,
            forkedFromDeck: null
        }).subscribe({
            next: deck => {
                this.wizardState.setCreatedDeck(deck);
                this.saving = false;
                this.next.emit();
            },
            error: () => { this.saving = false; }
        });
    }

    private saveFormData(): void {
        const values = this.form.value;
        this.wizardState.setDeckMetadata({
            name: values.name,
            description: values.description,
            language: values.language,
            isPublic: values.isPublic,
            isListed: values.isListed,
            tags: this.tags
        });
    }
}
