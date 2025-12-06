import { Component, Output, EventEmitter } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { ReactiveFormsModule, FormBuilder, FormGroup, FormArray, Validators } from '@angular/forms';
import { forkJoin, of } from 'rxjs';
import { switchMap } from 'rxjs/operators';
import { TemplateApiService } from '../../core/services/template-api.service';
import { CardTemplateDTO } from '../../core/models/template.models';
import { ButtonComponent } from '../../shared/components/button.component';
import { InputComponent } from '../../shared/components/input.component';
import { TextareaComponent } from '../../shared/components/textarea.component';

interface FieldFormValue {
    name: string;
    label: string;
    fieldType: string;
    isOnFront: boolean;
    orderIndex: number;
    helpText: string;
}

@Component({
    selector: 'app-template-creator-modal',
    standalone: true,
    imports: [ReactiveFormsModule, NgFor, NgIf, ButtonComponent, InputComponent, TextareaComponent],
    template: `
    <div class="modal-overlay" (click)="onCancel()">
      <div class="modal-content" (click)="$event.stopPropagation()">
        <div class="modal-header">
          <h2>Create New Template</h2>
          <button class="close-btn" (click)="onCancel()">&times;</button>
        </div>

        <form [formGroup]="form" class="template-form">
          <app-input label="Template Name" type="text" formControlName="name" placeholder="e.g., Language Flashcard" [hasError]="form.get('name')?.invalid && form.get('name')?.touched || false" errorMessage="Required"></app-input>
          <app-textarea label="Description" formControlName="description" placeholder="Describe this template" [rows]="3"></app-textarea>

          <div class="fields-section">
            <div class="fields-header">
              <h3>Fields</h3>
              <app-button variant="secondary" size="sm" type="button" (click)="addField()">+ Add Field</app-button>
            </div>

            <div formArrayName="fields" class="fields-list">
              <div *ngFor="let fieldForm of fieldsArray.controls; let i = index" [formGroupName]="i" class="field-item">
                <div class="field-number">{{ i + 1 }}</div>
                <div class="field-inputs">
                  <app-input label="Name" type="text" formControlName="name" placeholder="e.g., front" [hasError]="fieldForm.get('name')?.invalid && fieldForm.get('name')?.touched || false" errorMessage="Required"></app-input>
                  <app-input label="Label" type="text" formControlName="label" placeholder="e.g., Front Side" [hasError]="fieldForm.get('label')?.invalid && fieldForm.get('label')?.touched || false" errorMessage="Required"></app-input>
                  <div class="field-row">
                    <div class="select-group">
                      <label>Type</label>
                      <select formControlName="fieldType" class="field-select">
                        <option value="text">Text</option>
                        <option value="long_text">Long Text</option>
                        <option value="image">Image</option>
                        <option value="audio">Audio</option>
                        <option value="video">Video</option>
                      </select>
                    </div>
                    <label class="checkbox-label"><input type="checkbox" formControlName="isOnFront" /> Show on front</label>
                  </div>
                  <app-input label="Help Text (optional)" type="text" formControlName="helpText" placeholder="Hint for users"></app-input>
                </div>
                <app-button variant="ghost" size="sm" type="button" (click)="removeField(i)">Remove</app-button>
              </div>
            </div>
          </div>
        </form>

        <div class="modal-actions">
          <app-button variant="ghost" (click)="onCancel()" [disabled]="saving">Cancel</app-button>
          <app-button variant="primary" (click)="onCreate()" [disabled]="form.invalid || saving">{{ saving ? 'Creating...' : 'Create Template' }}</app-button>
        </div>
      </div>
    </div>
  `,
    styles: [`
      .modal-overlay { position: fixed; top: 0; left: 0; right: 0; bottom: 0; background: rgba(0, 0, 0, 0.5); display: flex; align-items: center; justify-content: center; z-index: 1000; }
      .modal-content { background: var(--color-card-background); border-radius: var(--border-radius-lg); max-width: 700px; width: 90%; max-height: 90vh; overflow-y: auto; box-shadow: 0 20px 25px -5px rgba(0, 0, 0, 0.1); }
      .modal-header { display: flex; justify-content: space-between; align-items: center; padding: var(--spacing-lg); border-bottom: 1px solid var(--border-color); }
      .modal-header h2 { margin: 0; font-size: 1.5rem; font-weight: 600; }
      .close-btn { background: none; border: none; font-size: 2rem; cursor: pointer; color: var(--color-text-secondary); line-height: 1; padding: 0; }
      .template-form { padding: var(--spacing-lg); display: flex; flex-direction: column; gap: var(--spacing-lg); }
      .fields-section { display: flex; flex-direction: column; gap: var(--spacing-md); }
      .fields-header { display: flex; justify-content: space-between; align-items: center; }
      .fields-header h3 { margin: 0; font-size: 1.1rem; font-weight: 600; }
      .fields-list { display: flex; flex-direction: column; gap: var(--spacing-md); }
      .field-item { display: flex; gap: var(--spacing-md); padding: var(--spacing-md); background: var(--color-background); border: 1px solid var(--border-color); border-radius: var(--border-radius-md); }
      .field-number { flex-shrink: 0; width: 32px; height: 32px; background: #111827; color: #fff; border-radius: 50%; display: flex; align-items: center; justify-content: center; font-weight: 600; }
      .field-inputs { flex: 1; display: flex; flex-direction: column; gap: var(--spacing-sm); }
      .field-row { display: flex; gap: var(--spacing-md); align-items: end; }
      .select-group { flex: 1; display: flex; flex-direction: column; gap: var(--spacing-xs); }
      .select-group label { font-size: 0.875rem; font-weight: 500; color: var(--color-text-primary); }
      .field-select { width: 100%; padding: var(--spacing-sm) var(--spacing-md); border: 1px solid var(--border-color); border-radius: var(--border-radius-md); background: var(--color-card-background); font-size: 0.9rem; }
      .checkbox-label { display: flex; align-items: center; gap: var(--spacing-sm); cursor: pointer; font-size: 0.9rem; white-space: nowrap; }
      .modal-actions { display: flex; justify-content: flex-end; gap: var(--spacing-md); padding: var(--spacing-lg); border-top: 1px solid var(--border-color); }
    `]
})
export class TemplateCreatorModalComponent {
    @Output() created = new EventEmitter<CardTemplateDTO>();
    @Output() cancelled = new EventEmitter<void>();

    form: FormGroup;
    saving = false;

    constructor(private fb: FormBuilder, private templateApi: TemplateApiService) {
        this.form = this.fb.group({
            name: ['', Validators.required],
            description: [''],
            fields: this.fb.array([])
        });
        this.addField();
    }

    get fieldsArray(): FormArray {
        return this.form.get('fields') as FormArray;
    }

    addField(): void {
        const fieldGroup = this.fb.group({
            name: ['', Validators.required],
            label: ['', Validators.required],
            fieldType: ['text'],
            isOnFront: [true],
            orderIndex: [this.fieldsArray.length],
            helpText: ['']
        });
        this.fieldsArray.push(fieldGroup);
    }

    removeField(index: number): void {
        if (this.fieldsArray.length > 1) {
            this.fieldsArray.removeAt(index);
            this.fieldsArray.controls.forEach((control, i) => {
                control.get('orderIndex')?.setValue(i);
            });
        }
    }

    onCreate(): void {
        if (this.form.invalid) return;

        this.saving = true;
        const formValue = this.form.value;
        const fields: FieldFormValue[] = formValue.fields;

        const frontFields = fields.filter(f => f.isOnFront).sort((a, b) => a.orderIndex - b.orderIndex);
        const backFields = fields.filter(f => !f.isOnFront).sort((a, b) => a.orderIndex - b.orderIndex);

        const templateDto = {
            name: formValue.name,
            description: formValue.description,
            isPublic: false,
            layout: {
                front: frontFields.map(f => f.name),
                back: backFields.map(f => f.name)
            }
        };

        this.templateApi.createTemplate(templateDto).pipe(
            switchMap(template => {
                if (fields.length === 0) {
                    return of(template);
                }

                const fieldRequests = fields.map((field, index) =>
                    this.templateApi.addField(template.templateId, {
                        name: field.name,
                        label: field.label,
                        fieldType: field.fieldType,
                        isRequired: false,
                        isOnFront: field.isOnFront,
                        orderIndex: index,
                        defaultValue: '',
                        helpText: field.helpText || ''
                    })
                );

                return forkJoin(fieldRequests).pipe(
                    switchMap(() => this.templateApi.getTemplate(template.templateId))
                );
            })
        ).subscribe({
            next: template => {
                this.saving = false;
                this.created.emit(template);
            },
            error: err => {
                console.error('Failed to create template:', err);
                this.saving = false;
            }
        });
    }

    onCancel(): void {
        this.cancelled.emit();
    }
}
