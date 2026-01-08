import { Component, Output, EventEmitter, OnInit } from '@angular/core';
import { ReactiveFormsModule, FormsModule, FormBuilder, FormGroup, Validators } from '@angular/forms';
import { NgFor, NgIf } from '@angular/common';
import { DeckApiService } from '../../../core/services/deck-api.service';
import { DeckWizardStateService } from '../deck-wizard-state.service';
import { MediaApiService } from '../../../core/services/media-api.service';
import { ButtonComponent } from '../../../shared/components/button.component';
import { InputComponent } from '../../../shared/components/input.component';
import { TextareaComponent } from '../../../shared/components/textarea.component';
import { MediaUploadComponent } from '../../../shared/components/media-upload.component';
import { CardContentValue } from '../../../core/models/user-card.models';
import { TranslatePipe } from '../../../shared/pipes/translate.pipe';

@Component({
    selector: 'app-deck-metadata-step',
    standalone: true,
    imports: [ReactiveFormsModule, FormsModule, NgFor, NgIf, ButtonComponent, InputComponent, TextareaComponent, MediaUploadComponent, TranslatePipe],
    template: `
    <div class="step">
      <h2>{{ 'wizard.deckInformation' | translate }}</h2>
      <form [formGroup]="form" class="form">
        <app-input [label]="'wizard.deckName' | translate" type="text" formControlName="name" [placeholder]="'wizard.deckNamePlaceholder' | translate" [hasError]="form.get('name')?.invalid && form.get('name')?.touched || false" [errorMessage]="'wizard.required' | translate"></app-input>
        <app-textarea [label]="'wizard.deckDescription' | translate" formControlName="description" [placeholder]="'wizard.deckDescriptionPlaceholder' | translate" [rows]="4"></app-textarea>
        <app-media-upload
          [label]="'wizard.deckIcon' | translate"
          [value]="iconValue"
          [fieldType]="'image'"
          (valueChange)="onIconChange($event)"
        ></app-media-upload>
        <div class="form-group">
          <label>{{ 'wizard.language' | translate }}</label>
          <select formControlName="language" class="language-select">
            <option value="en">{{ 'language.english' | translate }}</option>
            <option value="ru">{{ 'language.russian' | translate }}</option>
            <option value="jp">日本語 (Japanese)</option>
            <option value="sp">Español (Spanish)</option>
          </select>
        </div>
        <div class="form-group">
          <label>{{ 'wizard.tags' | translate }}</label>
          <input type="text" class="tag-input" [(ngModel)]="tagInput" [ngModelOptions]="{standalone: true}" (keydown.enter)="addTag($event)" [placeholder]="'wizard.tagPlaceholder' | translate" />
          <div *ngIf="tags.length > 0" class="tags-list">
            <span *ngFor="let tag of tags; let i = index" class="tag-chip">{{ tag }} <button type="button" (click)="removeTag(i)">×</button></span>
          </div>
        </div>
        <label class="checkbox-label"><input type="checkbox" formControlName="isPublic" /> {{ 'wizard.makePublic' | translate }}</label>
        <label class="checkbox-label"><input type="checkbox" formControlName="isListed" [disabled]="!form.get('isPublic')?.value" /> {{ 'wizard.listInCatalog' | translate }}</label>
      </form>
      <div class="step-actions">
        <app-button variant="ghost" (click)="onBack()">{{ 'wizard.back' | translate }}</app-button>
        <app-button variant="primary" [disabled]="form.invalid || saving" (click)="onNext()">{{ saving ? ('wizard.creating' | translate) : ('wizard.nextAddContent' | translate) }}</app-button>
      </div>
    </div>
  `,
    styles: [`
      .step { display: flex; flex-direction: column; gap: var(--spacing-lg); }
      .form { display: flex; flex-direction: column; gap: var(--spacing-lg); }
      .form-group { display: flex; flex-direction: column; gap: var(--spacing-xs); }
      .form-group label { font-size: 0.875rem; font-weight: 500; color: var(--color-text-primary); }
      .language-select { width: 100%; padding: var(--spacing-sm) var(--spacing-md); border: 1px solid var(--border-color); border-radius: var(--border-radius-md); font-size: 0.9rem; background: var(--color-card-background); cursor: pointer; }
      .tag-input { width: 100%; padding: var(--spacing-sm) var(--spacing-md); border: 1px solid var(--border-color); border-radius: var(--border-radius-md); font-size: 0.9rem; background: var(--color-card-background); }
      .tags-list { display: flex; flex-wrap: wrap; gap: var(--spacing-xs); margin-top: var(--spacing-sm); }
      .tag-chip { display: inline-flex; align-items: center; gap: var(--spacing-xs); padding: var(--spacing-xs) var(--spacing-sm); background: var(--color-background); border: 1px solid var(--border-color); border-radius: var(--border-radius-full); font-size: 0.85rem; }
      .tag-chip button { background: none; border: none; cursor: pointer; font-size: 1.2rem; line-height: 1; padding: 0; }
      .checkbox-label { display: flex; align-items: center; gap: var(--spacing-sm); cursor: pointer; font-size: 0.9rem; }
      .step-actions { display: flex; justify-content: space-between; padding-top: var(--spacing-lg); border-top: 1px solid var(--border-color); }

      @media (max-width: 768px) {
        .step-actions {
          flex-direction: column;
          gap: var(--spacing-sm);
        }
      }
    `]
})
export class DeckMetadataStepComponent implements OnInit {
    @Output() next = new EventEmitter<void>();
    @Output() back = new EventEmitter<void>();
    form: FormGroup;
    tags: string[] = [];
    tagInput = '';
    saving = false;
    iconValue: CardContentValue | null = null;

    constructor(private fb: FormBuilder, private deckApi: DeckApiService, private wizardState: DeckWizardStateService, private mediaApi: MediaApiService) {
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
        if (deckMetadata.iconMediaId) {
            this.iconValue = { mediaId: deckMetadata.iconMediaId, kind: 'image' };
        }
    }

    onIconChange(value: CardContentValue | null): void {
        this.iconValue = value;
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
            forkedFromDeck: null,
            iconMediaId: deckMetadata.iconMediaId || null
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
        const iconMediaId = this.iconValue && typeof this.iconValue !== 'string' ? this.iconValue.mediaId : undefined;
        this.wizardState.setDeckMetadata({
            name: values.name,
            description: values.description,
            language: values.language,
            isPublic: values.isPublic,
            isListed: values.isListed,
            tags: this.tags,
            iconMediaId
        });
    }
}
