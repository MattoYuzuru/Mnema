import { Component, OnInit, OnDestroy } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { Router } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { DragDropModule, CdkDragDrop, moveItemInArray } from '@angular/cdk/drag-drop';
import { forkJoin, of, switchMap } from 'rxjs';
import { TemplateApiService } from '../../core/services/template-api.service';
import { CardTemplateDTO } from '../../core/models/template.models';
import { DeckWizardStateService } from './deck-wizard-state.service';
import { ButtonComponent } from '../../shared/components/button.component';
import { TranslatePipe } from '../../shared/pipes/translate.pipe';
import { I18nService } from '../../core/services/i18n.service';

type FieldType = 'text' | 'rich_text' | 'markdown' | 'image' | 'audio' | 'video';
type CardSide = 'front' | 'back';

interface PaletteField {
    type: FieldType;
    icon: string;
    label: string;
    inDev: boolean;
}

interface BuilderField {
    tempId: string;
    name: string;
    type: FieldType;
    label: string;
    helpText: string;
    required: boolean;
    units: number;
}

interface BuilderState {
    frontFields: BuilderField[];
    backFields: BuilderField[];
    currentSide: CardSide;
    selectedFieldId: string | null;
    templateName: string;
    templateDescription: string;
}

@Component({
    selector: 'app-visual-template-builder',
    standalone: true,
    imports: [NgFor, NgIf, FormsModule, DragDropModule, ButtonComponent, TranslatePipe],
    template: `
    <div class="builder-page">
      <div class="builder-header">
        <h1>{{ 'visualBuilder.title' | translate }}</h1>
        <div class="header-actions">
          <app-button variant="ghost" (click)="cancel()">{{ 'visualBuilder.cancel' | translate }}</app-button>
          <app-button variant="primary" [disabled]="!canSave()" (click)="openSaveDialog()">{{ 'visualBuilder.saveTemplate' | translate }}</app-button>
        </div>
      </div>

      <div class="builder-container">
        <div class="builder-left">
          <div class="palette-column">
            <h3>{{ 'visualBuilder.fieldTypes' | translate }}</h3>
            <div class="palette-list" cdkDropList [cdkDropListData]="paletteFields" [cdkDropListConnectedTo]="['cardDropZone']" [cdkDropListSortingDisabled]="true">
              <div
                *ngFor="let field of paletteFields"
                class="palette-item"
                [class.disabled]="field.inDev"
                cdkDrag
                [cdkDragData]="field"
                [cdkDragDisabled]="field.inDev"
              >
                <span class="palette-icon">{{ field.icon }}</span>
                <span class="palette-label">{{ field.label }}</span>
                <span *ngIf="field.inDev" class="in-dev-badge">{{ 'visualBuilder.inDev' | translate }}</span>
              </div>
            </div>
          </div>

          <div class="config-column">
            <h3>{{ 'visualBuilder.fieldConfiguration' | translate }}</h3>
            <div *ngIf="selectedField" class="config-panel">
              <div class="form-group">
                <label>{{ 'visualBuilder.fieldLabel' | translate }}</label>
                <input type="text" [(ngModel)]="selectedField.label" (ngModelChange)="saveDraft()" class="form-input" />
              </div>
              <div class="form-group">
                <label>{{ 'visualBuilder.fieldHelpText' | translate }}</label>
                <input type="text" [(ngModel)]="selectedField.helpText" (ngModelChange)="saveDraft()" class="form-input" />
              </div>
              <div class="form-group">
                <label>{{ 'visualBuilder.fieldRequired' | translate }}</label>
                <div class="toggle-container" (click)="toggleRequired()">
                  <div class="toggle-switch" [class.active]="selectedField.required">
                    <div class="toggle-slider"></div>
                  </div>
                </div>
              </div>
            </div>
            <div *ngIf="!selectedField" class="config-empty">
              <p>{{ 'visualBuilder.selectFieldToConfig' | translate }}</p>
            </div>
          </div>
        </div>

        <div class="builder-divider"></div>

        <div class="builder-right">
          <div class="preview-controls">
            <h3>{{ currentSide === 'front' ? ('visualBuilder.frontSide' | translate) : ('visualBuilder.backSide' | translate) }}</h3>
            <div class="preview-actions">
              <label class="checkbox-label">
                <input type="checkbox" [(ngModel)]="showLabels" />
                <span>{{ 'visualBuilder.showLabels' | translate }}</span>
              </label>
            </div>
          </div>

          <button
            type="button"
            class="flip-icon-button"
            (click)="flipSide()"
            [attr.title]="('visualBuilder.flipTo' | translate) + ' ' + ((currentSide === 'front' ? 'visualBuilder.back' : 'visualBuilder.front') | translate)"
            [attr.aria-label]="('visualBuilder.flipTo' | translate) + ' ' + ((currentSide === 'front' ? 'visualBuilder.back' : 'visualBuilder.front') | translate)"
          >
            <span class="flip-icon" aria-hidden="true">🔄</span>
          </button>

          <div class="card-preview-container">
            <div
              class="card-preview"
              [style.height]="getCardHeight()"
              cdkDropList
              id="cardDropZone"
              [cdkDropListData]="currentFields"
              [cdkDropListConnectedTo]="[]"
              (cdkDropListDropped)="onDrop($event)"
            >
              <div *ngIf="currentFields.length === 0" class="card-empty">
                {{ 'visualBuilder.dragFieldsHere' | translate }}
              </div>

              <div
                *ngFor="let field of currentFields; let i = index"
                class="field-row"
                [class.selected]="selectedFieldId === field.tempId"
                [style.flex-grow]="field.units"
                (click)="selectField(field.tempId)"
                cdkDrag
              >
                <div class="field-row-content">
                  <span *ngIf="showLabels" class="field-label">{{ getFieldDisplayLabel(field) }}</span>
                  <div class="field-preview">{{ getFieldPreview(field) }}</div>
                </div>
                <div class="field-row-actions">
                  <button
                    type="button"
                    class="icon-button"
                    [disabled]="i === 0"
                    [attr.title]="'visualBuilder.moveUp' | translate"
                    [attr.aria-label]="'visualBuilder.moveUp' | translate"
                    (click)="moveFieldUp(i); $event.stopPropagation()"
                  >↑</button>
                  <button
                    type="button"
                    class="icon-button"
                    [disabled]="i === currentFields.length - 1"
                    [attr.title]="'visualBuilder.moveDown' | translate"
                    [attr.aria-label]="'visualBuilder.moveDown' | translate"
                    (click)="moveFieldDown(i); $event.stopPropagation()"
                  >↓</button>
                  <button
                    type="button"
                    class="icon-button delete"
                    [attr.title]="'visualBuilder.removeField' | translate"
                    [attr.aria-label]="'visualBuilder.removeField' | translate"
                    (click)="removeField(i); $event.stopPropagation()"
                  >×</button>
                </div>
              </div>

              <div *ngIf="currentFields.length >= 10" class="max-fields-warning">
                {{ 'visualBuilder.maxFieldsWarning' | translate }}
              </div>
            </div>
          </div>

          <div class="template-info">
            <div class="form-group">
              <label>{{ 'visualBuilder.templateName' | translate }} *</label>
              <input type="text" [(ngModel)]="templateName" class="form-input" [placeholder]="'visualBuilder.templateNamePlaceholder' | translate" />
            </div>
            <div class="form-group">
              <label>{{ 'visualBuilder.templateDescription' | translate }}</label>
              <input type="text" [(ngModel)]="templateDescription" class="form-input" [placeholder]="'visualBuilder.templateDescPlaceholder' | translate" />
            </div>
          </div>
        </div>
      </div>
    </div>

    <div *ngIf="showSaveDialog" class="modal-overlay" (click)="closeSaveDialog()">
      <div class="modal-dialog" (click)="$event.stopPropagation()">
        <h2>{{ 'visualBuilder.createDialogTitle' | translate }}</h2>
        <p>{{ 'visualBuilder.createDialogMessage' | translate }}</p>
        <div class="modal-checkbox">
          <label class="checkbox-label">
            <input type="checkbox" [(ngModel)]="makePublic" />
            <span>{{ 'visualBuilder.makePublic' | translate }}</span>
          </label>
        </div>
        <div class="modal-actions">
          <app-button variant="ghost" (click)="closeSaveDialog()">{{ 'visualBuilder.cancel' | translate }}</app-button>
          <app-button variant="primary" [disabled]="saving" (click)="saveTemplate()">
            {{ saving ? ('visualBuilder.creating' | translate) : ('visualBuilder.createTemplate' | translate) }}
          </app-button>
        </div>
      </div>
    </div>
  `,
    styles: [`
      .builder-page {
        display: flex;
        flex-direction: column;
        height: 100vh;
        overflow: hidden;
        background: var(--color-background);
      }

      .builder-header {
        display: flex;
        justify-content: space-between;
        align-items: center;
        padding: var(--spacing-lg) var(--spacing-xl);
        background: transparent;
      }

      .builder-header h1 {
        font-size: 1.5rem;
        margin: 0;
      }

      .header-actions {
        display: flex;
        gap: var(--spacing-sm);
      }

      .builder-container {
        display: grid;
        grid-template-columns: 1fr 1px 1fr;
        flex: 1;
        overflow: hidden;
        min-height: 0;
      }

      .builder-left {
        display: grid;
        grid-template-columns: 1fr 1fr;
        gap: var(--spacing-md);
        padding: var(--spacing-lg);
        overflow-y: auto;
      }

      .palette-column h3, .config-column h3 {
        font-size: 1rem;
        font-weight: 600;
        margin: 0 0 var(--spacing-md) 0;
        text-align: center;
      }

      .palette-column {
        display: flex;
        flex-direction: column;
        height: 100%;
      }

      .palette-list {
        display: flex;
        flex-direction: column;
        gap: var(--spacing-md);
        min-height: 100px;
        flex: 1;
        justify-content: flex-start;
      }

      .palette-item {
        display: flex;
        align-items: center;
        justify-content: flex-start;
        gap: var(--spacing-sm);
        padding: var(--spacing-md) var(--spacing-lg);
        background: var(--color-card-background);
        border: 1px solid var(--border-color);
        border-radius: var(--border-radius-md);
        cursor: move;
        transition: all 0.2s;
        text-align: left;
        min-height: 4rem;
      }

      .palette-item:hover:not(.disabled) {
        border-color: #111827;
        box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
      }

      .palette-item.disabled {
        opacity: 0.5;
        cursor: not-allowed;
      }

      .palette-icon {
        font-size: 1.25rem;
      }

      .palette-label {
        flex: 1;
        font-size: 0.9rem;
        font-weight: 500;
      }

      .in-dev-badge {
        font-size: 0.7rem;
        padding: 2px 6px;
        background: var(--color-background);
        border-radius: var(--border-radius-sm);
        color: var(--color-text-muted);
      }

      .config-column {
        display: flex;
        flex-direction: column;
        height: 100%;
      }

      .config-panel {
        display: flex;
        flex-direction: column;
        gap: var(--spacing-md);
        padding: var(--spacing-md);
        background: var(--color-card-background);
        border: 1px solid var(--border-color);
        border-radius: var(--border-radius-md);
        text-align: left;
      }

      .config-empty {
        display: flex;
        align-items: center;
        justify-content: center;
        padding: var(--spacing-xl);
        text-align: center;
        color: var(--color-text-muted);
        font-style: italic;
      }

      .form-group {
        display: flex;
        flex-direction: column;
        gap: var(--spacing-xs);
      }

      .form-group label {
        font-size: 0.9rem;
        font-weight: 500;
      }

      .form-input {
        padding: var(--spacing-sm) var(--spacing-md);
        border: 1px solid var(--border-color);
        border-radius: var(--border-radius-md);
        font-size: 0.95rem;
        font-family: inherit;
      }

      .checkbox-label {
        display: flex;
        align-items: center;
        gap: var(--spacing-xs);
        cursor: pointer;
      }

      .checkbox-label input {
        cursor: pointer;
      }

      .toggle-container {
        display: flex;
        align-items: center;
        justify-content: flex-start;
        gap: var(--spacing-sm);
      }

      .toggle-switch {
        position: relative;
        width: 44px;
        height: 24px;
        background: var(--border-color);
        border-radius: 12px;
        cursor: pointer;
        transition: background 0.3s;
      }

      .toggle-switch.active {
        background: #111827;
      }

      .toggle-slider {
        position: absolute;
        top: 2px;
        left: 2px;
        width: 20px;
        height: 20px;
        background: white;
        border-radius: 50%;
        transition: transform 0.3s;
      }

      .toggle-switch.active .toggle-slider {
        transform: translateX(20px);
      }

      .flip-icon-button {
        display: flex;
        align-items: center;
        justify-content: center;
        padding: var(--spacing-xs);
        background: transparent;
        border: none;
        cursor: pointer;
        transition: transform 0.2s ease, opacity 0.2s ease;
        font-size: 1.4rem;
        margin: var(--spacing-sm) auto;
      }

      .flip-icon-button:hover {
        transform: rotate(12deg);
        opacity: 0.85;
      }

      .flip-icon {
        display: inline-flex;
      }

      .builder-divider {
        background: var(--border-color);
      }

      .builder-right {
        display: flex;
        flex-direction: column;
        padding: var(--spacing-lg);
        overflow-y: auto;
        gap: var(--spacing-lg);
      }

      .preview-controls {
        display: flex;
        justify-content: space-between;
        align-items: center;
        flex-shrink: 0;
      }

      .preview-controls h3 {
        font-size: 1rem;
        font-weight: 600;
        margin: 0;
      }

      .preview-actions {
        display: flex;
        align-items: center;
        gap: var(--spacing-md);
      }

      .card-preview-container {
        display: flex;
        justify-content: center;
        flex-shrink: 0;
      }

      .card-preview {
        width: 100%;
        max-width: 500px;
        background: var(--color-card-background);
        border: 2px solid var(--border-color);
        border-radius: var(--border-radius-lg);
        padding: var(--spacing-lg);
        box-shadow: 0 4px 12px rgba(0, 0, 0, 0.08);
        display: flex;
        flex-direction: column;
        transition: height 0.2s ease;
        position: relative;
      }

      .card-empty {
        position: absolute;
        top: 50%;
        left: 50%;
        transform: translate(-50%, -50%);
        color: var(--color-text-muted);
        font-style: italic;
        pointer-events: none;
      }

      .field-row {
        display: flex;
        align-items: center;
        gap: var(--spacing-sm);
        padding: var(--spacing-md);
        background: var(--color-background);
        border: 1px solid var(--border-color);
        border-radius: var(--border-radius-md);
        margin-bottom: var(--spacing-sm);
        cursor: pointer;
        transition: all 0.15s;
        min-height: 50px;
      }

      .field-row:last-of-type {
        margin-bottom: 0;
      }

      .field-row:hover {
        border-color: #111827;
      }

      .field-row.selected {
        border-color: #111827;
        border-width: 2px;
        background: rgba(17, 24, 39, 0.05);
      }

      .field-row-content {
        flex: 1;
        min-width: 0;
      }

      .field-label {
        display: block;
        font-size: 0.8rem;
        font-weight: 600;
        margin-bottom: var(--spacing-xs);
        color: var(--color-text-secondary);
      }

      .field-preview {
        font-size: 0.9rem;
        color: var(--color-text-primary);
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
      }

      .field-row-actions {
        display: flex;
        gap: 4px;
      }

      .icon-button {
        width: 28px;
        height: 28px;
        padding: 0;
        border: none;
        background: transparent;
        border-radius: var(--border-radius-sm);
        cursor: pointer;
        font-size: 1.05rem;
        display: flex;
        align-items: center;
        justify-content: center;
        transition: all 0.2s;
        color: var(--color-text-secondary);
      }

      .icon-button:hover:not(:disabled) {
        background: rgba(17, 24, 39, 0.08);
        color: var(--color-text-primary);
      }

      .icon-button:disabled {
        opacity: 0.35;
        cursor: not-allowed;
      }

      .icon-button.delete {
        color: #dc2626;
      }

      .icon-button.delete:hover:not(:disabled) {
        background: rgba(220, 38, 38, 0.12);
      }

      .max-fields-warning {
        padding: var(--spacing-md);
        background: #fef3c7;
        border: 1px solid #f59e0b;
        border-radius: var(--border-radius-md);
        color: #92400e;
        font-size: 0.85rem;
        text-align: center;
        margin-top: var(--spacing-sm);
      }

      .template-info {
        display: flex;
        flex-direction: column;
        gap: var(--spacing-md);
        padding-top: var(--spacing-lg);
        border-top: 1px solid var(--border-color);
        flex-shrink: 0;
      }

      .modal-overlay {
        position: fixed;
        inset: 0;
        background: rgba(0, 0, 0, 0.5);
        display: flex;
        align-items: center;
        justify-content: center;
        z-index: 1000;
      }

      .modal-dialog {
        background: var(--color-card-background);
        border-radius: var(--border-radius-lg);
        padding: var(--spacing-xl);
        max-width: 500px;
        width: 90%;
        box-shadow: 0 20px 25px -5px rgba(0, 0, 0, 0.1);
      }

      .modal-dialog h2 {
        margin: 0 0 var(--spacing-md) 0;
        font-size: 1.25rem;
      }

      .modal-dialog p {
        margin: 0 0 var(--spacing-lg) 0;
        color: var(--color-text-secondary);
      }

      .modal-checkbox {
        margin-bottom: var(--spacing-lg);
      }

      .modal-actions {
        display: flex;
        justify-content: flex-end;
        gap: var(--spacing-sm);
      }

      .cdk-drag-preview {
        opacity: 0.9;
        box-sizing: border-box;
        border-radius: var(--border-radius-md);
        box-shadow: 0 5px 15px rgba(0, 0, 0, 0.3);
      }

      .cdk-drag-placeholder {
        opacity: 0.4;
      }

      .cdk-drag-animating {
        transition: transform 200ms cubic-bezier(0, 0, 0.2, 1);
      }

      .cdk-drop-list-dragging .field-row:not(.cdk-drag-placeholder) {
        transition: transform 200ms cubic-bezier(0, 0, 0.2, 1);
      }

      @media (max-width: 1024px) {
        .builder-container {
          grid-template-columns: 1fr;
        }

        .builder-divider {
          display: none;
        }

        .builder-left {
          grid-template-columns: 1fr;
        }

        .builder-right {
          border-top: 1px solid var(--border-color);
        }
      }

      @media (max-width: 768px) {
        .builder-page {
          height: auto;
          min-height: 100vh;
        }

        .builder-header {
          flex-direction: column;
          align-items: flex-start;
          gap: var(--spacing-sm);
        }

        .header-actions {
          width: 100%;
          justify-content: flex-start;
          flex-wrap: wrap;
        }

        .builder-left,
        .builder-right {
          padding: var(--spacing-md);
        }

        .preview-controls {
          flex-direction: column;
          align-items: flex-start;
          gap: var(--spacing-sm);
        }
      }
    `]
})
export class VisualTemplateBuilderComponent implements OnInit, OnDestroy {
    private readonly STORAGE_KEY = 'mnema_visual_builder_draft';
    private readonly BASE_CARD_HEIGHT = 300;
    private tempIdCounter = 0;
    private skipDraftSave = false;

    paletteFields: PaletteField[] = [];

    frontFields: BuilderField[] = [];
    backFields: BuilderField[] = [];
    currentSide: CardSide = 'front';
    selectedFieldId: string | null = null;
    showLabels = true;
    templateName = '';
    templateDescription = '';
    showSaveDialog = false;
    makePublic = false;
    saving = false;

    constructor(
        private router: Router,
        private templateApi: TemplateApiService,
        private wizardState: DeckWizardStateService,
        public i18n: I18nService
    ) {
        this.paletteFields = [
            { type: 'text', icon: '📝', label: this.i18n.translate('visualBuilder.fieldTypeText'), inDev: false },
            { type: 'markdown', icon: '📄', label: this.i18n.translate('visualBuilder.fieldTypeMarkdown'), inDev: false },
            { type: 'rich_text', icon: '📋', label: this.i18n.translate('visualBuilder.fieldTypeLongText'), inDev: false },
            { type: 'audio', icon: '🎵', label: this.i18n.translate('visualBuilder.fieldTypeAudio'), inDev: false },
            { type: 'image', icon: '🖼️', label: this.i18n.translate('visualBuilder.fieldTypeImage'), inDev: false },
            { type: 'video', icon: '🎬', label: this.i18n.translate('visualBuilder.fieldTypeVideo'), inDev: false },
        ];
    }

    ngOnInit(): void {
        this.loadDraft();
    }

    ngOnDestroy(): void {
        if (!this.skipDraftSave) {
            this.saveDraft();
        }
    }

    get currentFields(): BuilderField[] {
        return this.currentSide === 'front' ? this.frontFields : this.backFields;
    }

    get selectedField(): BuilderField | null {
        if (!this.selectedFieldId) return null;
        return this.currentFields.find(f => f.tempId === this.selectedFieldId) || null;
    }

    getCardHeight(): string {
        const fieldCount = this.currentFields.length;
        if (fieldCount === 0) {
            return `${this.BASE_CARD_HEIGHT}px`;
        }
        const capacityUnits = Math.max(4, fieldCount);
        const height = this.BASE_CARD_HEIGHT * (capacityUnits / 4);
        return `${height}px`;
    }

    private generateFieldName(label: string, existingNames: string[]): string {
        let base = label.trim().toLowerCase()
            .replace(/\s+/g, '_')
            .replace(/[^a-z0-9_]/g, '');

        if (!base) {
            base = 'field';
        }

        let name = base;
        let counter = 2;
        while (existingNames.includes(name)) {
            name = `${base}_${counter}`;
            counter++;
        }

        return name;
    }

    onDrop(event: CdkDragDrop<BuilderField[]>): void {
        if (event.previousContainer === event.container) {
            moveItemInArray(this.currentFields, event.previousIndex, event.currentIndex);
            this.recalculateUnits();
            this.saveDraft();
        } else {
            if (this.currentFields.length >= 10) {
                return;
            }

            const draggedItem = (event.previousContainer.data as any)[event.previousIndex];
            const paletteField = draggedItem as PaletteField;

            if (paletteField && !paletteField.inDev) {
                const existingNames = [
                    ...this.frontFields.map(f => f.name),
                    ...this.backFields.map(f => f.name)
                ];
                const fieldName = this.generateFieldName(paletteField.label, existingNames);

                const newField: BuilderField = {
                    tempId: `field-${Date.now()}-${this.tempIdCounter++}`,
                    name: fieldName,
                    type: paletteField.type,
                    label: '',
                    helpText: '',
                    required: false,
                    units: 1
                };
                this.currentFields.splice(event.currentIndex, 0, newField);
                this.recalculateUnits();
                this.selectField(newField.tempId);
                this.saveDraft();
            }
        }
    }

    selectField(tempId: string): void {
        this.selectedFieldId = tempId;
    }

    removeField(index: number): void {
        const removed = this.currentFields[index];
        this.currentFields.splice(index, 1);
        if (this.selectedFieldId === removed.tempId) {
            this.selectedFieldId = null;
        }
        this.recalculateUnits();
        this.saveDraft();
    }

    moveFieldUp(index: number): void {
        if (index > 0) {
            moveItemInArray(this.currentFields, index, index - 1);
            this.saveDraft();
        }
    }

    moveFieldDown(index: number): void {
        if (index < this.currentFields.length - 1) {
            moveItemInArray(this.currentFields, index, index + 1);
            this.saveDraft();
        }
    }

    flipSide(): void {
        this.currentSide = this.currentSide === 'front' ? 'back' : 'front';
        this.selectedFieldId = null;
    }

    toggleRequired(): void {
        if (this.selectedField) {
            this.selectedField.required = !this.selectedField.required;
            this.saveDraft();
        }
    }

    getFieldDisplayLabel(field: BuilderField): string {
        if (field.label.trim()) {
            return field.label;
        }
        const paletteField = this.paletteFields.find(pf => pf.type === field.type);
        return paletteField ? `${paletteField.icon} ${paletteField.label}` : field.type;
    }

    getFieldPreview(field: BuilderField): string {
        switch (field.type) {
            case 'text':
                return this.i18n.translate('visualBuilder.sampleText');
            case 'rich_text':
                return this.i18n.translate('visualBuilder.sampleLongText');
            case 'markdown':
                return this.i18n.translate('visualBuilder.sampleMarkdown');
            case 'image':
                return `🖼️ ${this.i18n.translate('visualBuilder.sampleImage')}`;
            case 'audio':
                return `🎵 ${this.i18n.translate('visualBuilder.sampleAudio')}`;
            case 'video':
                return `🎬 ${this.i18n.translate('visualBuilder.sampleVideo')}`;
            default:
                return '';
        }
    }

    canSave(): boolean {
        return this.templateName.trim().length > 0 &&
               this.frontFields.length > 0 &&
               this.backFields.length > 0;
    }

    openSaveDialog(): void {
        if (this.canSave()) {
            this.showSaveDialog = true;
        }
    }

    closeSaveDialog(): void {
        this.showSaveDialog = false;
    }

    saveTemplate(): void {
        if (!this.canSave() || this.saving) return;

        this.saving = true;

        const templateDto = {
            name: this.templateName.trim(),
            description: this.templateDescription.trim(),
            isPublic: this.makePublic,
            layout: {
                front: this.frontFields.map(f => f.name),
                back: this.backFields.map(f => f.name)
            }
        };

        this.templateApi.createTemplate(templateDto).pipe(
            switchMap(template => {
                const allFields = [
                    ...this.frontFields.map((f, i) => ({ field: f, isOnFront: true, orderIndex: i })),
                    ...this.backFields.map((f, i) => ({ field: f, isOnFront: false, orderIndex: i }))
                ];

                if (allFields.length === 0) {
                    return of(template);
                }

                const fieldRequests = allFields.map(({ field, isOnFront, orderIndex }) => {
                    const fieldLabel = field.label.trim() || this.getFieldDisplayLabel(field);
                    return this.templateApi.addField(template.templateId, {
                        name: field.name,
                        label: fieldLabel,
                        fieldType: field.type,
                        isRequired: field.required,
                        isOnFront,
                        orderIndex,
                        helpText: field.helpText || null,
                        defaultValue: null
                    });
                });

                return forkJoin(fieldRequests).pipe(
                    switchMap(() => this.templateApi.getTemplate(template.templateId))
                );
            })
        ).subscribe({
            next: (template) => {
                this.skipDraftSave = true;
                this.clearDraft();
                this.wizardState.setTemplateId(template.templateId);
                this.wizardState.setCurrentStep(2);
                void this.router.navigate(['/create-deck']);
            },
            error: () => {
                this.saving = false;
            }
        });
    }

    cancel(): void {
        void this.router.navigate(['/create-deck']);
    }

    private recalculateUnits(): void {
        const count = this.currentFields.length;

        if (count === 0) {
            return;
        }

        if (count === 1) {
            this.currentFields[0].units = 4;
        } else if (count === 2) {
            this.currentFields.forEach(f => f.units = 2);
        } else if (count === 3) {
            this.currentFields[0].units = 2;
            this.currentFields[1].units = 1;
            this.currentFields[2].units = 1;
        } else {
            this.currentFields.forEach(f => f.units = 1);
        }
    }

    private loadDraft(): void {
        const draft = localStorage.getItem(this.STORAGE_KEY);
        if (draft) {
            try {
                const state: BuilderState = JSON.parse(draft);
                this.frontFields = state.frontFields || [];
                this.backFields = state.backFields || [];
                this.currentSide = state.currentSide || 'front';
                this.selectedFieldId = state.selectedFieldId || null;
                this.templateName = state.templateName || '';
                this.templateDescription = state.templateDescription || '';

                this.recalculateUnits();
                if (this.currentSide === 'back') {
                    this.backFields.forEach(() => {});
                    this.recalculateUnits();
                }
            } catch {
            }
        }
    }

    saveDraft(): void {
        const state: BuilderState = {
            frontFields: this.frontFields,
            backFields: this.backFields,
            currentSide: this.currentSide,
            selectedFieldId: this.selectedFieldId,
            templateName: this.templateName,
            templateDescription: this.templateDescription
        };
        localStorage.setItem(this.STORAGE_KEY, JSON.stringify(state));
    }

    private clearDraft(): void {
        localStorage.removeItem(this.STORAGE_KEY);
    }
}
