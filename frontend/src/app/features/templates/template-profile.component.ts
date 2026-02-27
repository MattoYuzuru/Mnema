import { Component, OnInit } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { NgIf, NgFor } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { forkJoin } from 'rxjs';
import { TemplateApiService } from '../../core/services/template-api.service';
import { UserApiService } from '../../user-api.service';
import { DeckWizardStateService } from '../wizard/deck-wizard-state.service';
import { CardTemplateDTO, FieldTemplateDTO } from '../../core/models/template.models';
import { ButtonComponent } from '../../shared/components/button.component';
import { MemoryTipLoaderComponent } from '../../shared/components/memory-tip-loader.component';
import { TranslatePipe } from '../../shared/pipes/translate.pipe';
import { I18nService } from '../../core/services/i18n.service';

interface EditableField {
    fieldId?: string;
    name: string;
    label: string;
    fieldType: string;
    isRequired: boolean;
    isOnFront: boolean;
    orderIndex: number;
    helpText?: string | null;
    defaultValue?: string | null;
    isNew?: boolean;
}

type FieldSide = 'front' | 'back';

@Component({
    selector: 'app-template-profile',
    standalone: true,
    imports: [NgIf, NgFor, FormsModule, ButtonComponent, MemoryTipLoaderComponent, TranslatePipe],
    template: `
    <app-memory-tip-loader *ngIf="loading"></app-memory-tip-loader>

    <div *ngIf="!loading && template" class="template-profile">
      <header class="profile-header">
        <div class="title-block">
          <h1 *ngIf="!editing">{{ template.name }}</h1>
          <input
            *ngIf="editing"
            class="title-input"
            [(ngModel)]="draftName"
            [placeholder]="'templateProfile.namePlaceholder' | translate"
            [attr.maxlength]="maxTemplateName"
          />
          <p *ngIf="!editing" class="description">{{ template.description || ('templateProfile.noDescription' | translate) }}</p>
          <textarea
            *ngIf="editing"
            class="description-input"
            rows="3"
            [(ngModel)]="draftDescription"
            [placeholder]="'templateProfile.descriptionPlaceholder' | translate"
            [attr.maxlength]="maxTemplateDescription"
          ></textarea>
        </div>

        <div class="header-actions">
          <app-button *ngIf="fromWizard" variant="primary" (click)="useTemplate()">
            {{ 'templateProfile.useTemplate' | translate }}
          </app-button>

          <app-button *ngIf="isOwner && !editing" variant="secondary" (click)="startEdit()">
            {{ 'templateProfile.edit' | translate }}
          </app-button>
          <app-button *ngIf="editing" variant="ghost" (click)="cancelEdit()" [disabled]="saving">
            {{ 'templateProfile.cancel' | translate }}
          </app-button>
          <app-button *ngIf="editing" variant="primary" (click)="saveChanges()" [disabled]="saving">
            {{ saving ? ('templateProfile.saving' | translate) : ('templateProfile.save' | translate) }}
          </app-button>
        </div>
      </header>

      <div class="meta-row">
        <div class="meta-item">
          <span class="meta-label">{{ 'templateProfile.visibility' | translate }}</span>
          <span *ngIf="!editing" class="meta-value">
            {{ template.isPublic ? ('templateProfile.public' | translate) : ('templateProfile.private' | translate) }}
          </span>
          <label *ngIf="editing" class="visibility-toggle">
            <input type="checkbox" [(ngModel)]="draftPublic" />
            <span>{{ draftPublic ? ('templateProfile.public' | translate) : ('templateProfile.private' | translate) }}</span>
          </label>
        </div>
        <div class="meta-item">
          <span class="meta-label">{{ 'templateProfile.createdAt' | translate }}</span>
          <span class="meta-value">{{ formatDate(template.createdAt) }}</span>
        </div>
        <div class="meta-item">
          <span class="meta-label">{{ 'templateProfile.version' | translate }}</span>
          <span class="meta-value">
            v{{ template.version || template.latestVersion || 1 }}
            <ng-container *ngIf="template.latestVersion && template.version && template.version !== template.latestVersion">
              ({{ 'templateProfile.latestVersion' | translate }} v{{ template.latestVersion }})
            </ng-container>
          </span>
        </div>
      </div>

      <p *ngIf="templateError" class="error-text">{{ templateError }}</p>
      <p *ngIf="!isLatestVersion" class="safe-note">{{ 'templateProfile.versionViewOnly' | translate }}</p>
      <p *ngIf="editing" class="safe-note">{{ 'templateProfile.safeChanges' | translate }}</p>

      <section class="ai-profile-section">
        <div class="section-header">
          <h2>{{ 'templateProfile.aiProfileTitle' | translate }}</h2>
        </div>
        <p class="ai-hint">{{ 'templateProfile.aiProfileHint' | translate }}</p>
        <textarea
          *ngIf="editing"
          class="ai-profile-input"
          rows="4"
          [(ngModel)]="draftAiProfilePrompt"
          [placeholder]="'templateProfile.aiProfilePlaceholder' | translate"
        ></textarea>
        <p *ngIf="!editing" class="ai-profile-preview">
          {{ draftAiProfilePrompt || ('templateProfile.aiProfileEmpty' | translate) }}
        </p>
      </section>

      <section class="preview-section">
        <div class="section-header">
          <h2>{{ 'templateProfile.previewTitle' | translate }}</h2>
        </div>
        <div class="flashcard-container">
          <div class="flashcard" [class.flipped]="isFlipped" (click)="toggleFlip()">
            <div class="flashcard-inner">
              <div class="flashcard-face front">
                <div *ngIf="frontFields.length === 0" class="empty-preview">
                  {{ 'templateProfile.noFrontFields' | translate }}
                </div>
                <div *ngFor="let field of frontFields" class="field-block">
                  <div class="field-label">{{ field.label }}</div>
                  <div class="field-value">{{ sampleValue(field) }}</div>
                </div>
              </div>
              <div class="flashcard-face back">
                <div *ngIf="backFields.length === 0" class="empty-preview">
                  {{ 'templateProfile.noBackFields' | translate }}
                </div>
                <div *ngFor="let field of backFields" class="field-block">
                  <div class="field-label">{{ field.label }}</div>
                  <div class="field-value">{{ sampleValue(field) }}</div>
                </div>
              </div>
            </div>
          </div>
          <div class="flip-hint">{{ 'templateProfile.flipHint' | translate }}</div>
        </div>
      </section>

      <section class="fields-section">
        <div class="section-header">
          <h2>{{ 'templateProfile.fields' | translate }}</h2>
        </div>

        <div class="fields-grid">
          <div class="fields-column">
            <h3>{{ 'templateProfile.front' | translate }}</h3>
            <div *ngFor="let field of frontFields; let i = index" class="field-card">
              <div class="field-header">
                <span class="field-key">{{ field.name }}</span>
                <span class="field-type">{{ field.fieldType }}</span>
              </div>
              <div class="field-body">
                <label>{{ 'templateProfile.fieldLabel' | translate }}</label>
                <input
                  *ngIf="editing"
                  [(ngModel)]="field.label"
                  class="field-input"
                  [attr.maxlength]="maxFieldLabel"
                />
                <span *ngIf="!editing">{{ field.label }}</span>
              </div>
              <div class="field-body">
                <label>{{ 'templateProfile.fieldHelpText' | translate }}</label>
                <input
                  *ngIf="editing"
                  [(ngModel)]="field.helpText"
                  class="field-input"
                  [attr.maxlength]="maxFieldHelpText"
                />
                <span *ngIf="!editing">{{ field.helpText || '-' }}</span>
              </div>
              <div *ngIf="editing" class="field-actions">
                <button class="link-btn" (click)="moveField('front', i, -1)">{{ 'templateProfile.moveUp' | translate }}</button>
                <button class="link-btn" (click)="moveField('front', i, 1)">{{ 'templateProfile.moveDown' | translate }}</button>
                <button class="link-btn" (click)="moveToSide('front', i, 'back')">{{ 'templateProfile.moveToBack' | translate }}</button>
              </div>
            </div>
          </div>

          <div class="fields-column">
            <h3>{{ 'templateProfile.back' | translate }}</h3>
            <div *ngFor="let field of backFields; let i = index" class="field-card">
              <div class="field-header">
                <span class="field-key">{{ field.name }}</span>
                <span class="field-type">{{ field.fieldType }}</span>
              </div>
              <div class="field-body">
                <label>{{ 'templateProfile.fieldLabel' | translate }}</label>
                <input
                  *ngIf="editing"
                  [(ngModel)]="field.label"
                  class="field-input"
                  [attr.maxlength]="maxFieldLabel"
                />
                <span *ngIf="!editing">{{ field.label }}</span>
              </div>
              <div class="field-body">
                <label>{{ 'templateProfile.fieldHelpText' | translate }}</label>
                <input
                  *ngIf="editing"
                  [(ngModel)]="field.helpText"
                  class="field-input"
                  [attr.maxlength]="maxFieldHelpText"
                />
                <span *ngIf="!editing">{{ field.helpText || '-' }}</span>
              </div>
              <div *ngIf="editing" class="field-actions">
                <button class="link-btn" (click)="moveField('back', i, -1)">{{ 'templateProfile.moveUp' | translate }}</button>
                <button class="link-btn" (click)="moveField('back', i, 1)">{{ 'templateProfile.moveDown' | translate }}</button>
                <button class="link-btn" (click)="moveToSide('back', i, 'front')">{{ 'templateProfile.moveToFront' | translate }}</button>
              </div>
            </div>
          </div>
        </div>

        <div *ngIf="editing" class="add-field">
          <h3>{{ 'templateProfile.addField' | translate }}</h3>
          <div class="add-field-grid">
            <div class="form-field">
              <label>{{ 'templateProfile.fieldName' | translate }}</label>
              <input [(ngModel)]="newField.name" class="field-input" [placeholder]="'templateProfile.fieldNamePlaceholder' | translate" />
            </div>
            <div class="form-field">
              <label>{{ 'templateProfile.fieldLabel' | translate }}</label>
              <input [(ngModel)]="newField.label" class="field-input" [placeholder]="'templateProfile.fieldLabelPlaceholder' | translate" [attr.maxlength]="maxFieldLabel" />
            </div>
            <div class="form-field">
              <label>{{ 'templateProfile.fieldType' | translate }}</label>
              <select [(ngModel)]="newField.fieldType" class="field-input">
                <option value="text">{{ 'templateProfile.typeText' | translate }}</option>
                <option value="rich_text">{{ 'templateProfile.typeLongText' | translate }}</option>
                <option value="markdown">{{ 'templateProfile.typeMarkdown' | translate }}</option>
                <option value="image">{{ 'templateProfile.typeImage' | translate }}</option>
                <option value="audio">{{ 'templateProfile.typeAudio' | translate }}</option>
                <option value="video">{{ 'templateProfile.typeVideo' | translate }}</option>
              </select>
            </div>
            <div class="form-field">
              <label>{{ 'templateProfile.fieldSide' | translate }}</label>
              <select [(ngModel)]="newField.side" class="field-input">
                <option value="front">{{ 'templateProfile.front' | translate }}</option>
                <option value="back">{{ 'templateProfile.back' | translate }}</option>
              </select>
            </div>
            <div class="form-field">
              <label>{{ 'templateProfile.fieldHelpText' | translate }}</label>
              <input [(ngModel)]="newField.helpText" class="field-input" [placeholder]="'templateProfile.fieldHelpTextPlaceholder' | translate" [attr.maxlength]="maxFieldHelpText" />
            </div>
          </div>
          <p *ngIf="fieldError" class="error-text">{{ fieldError }}</p>
          <app-button variant="secondary" size="sm" (click)="addField()" [disabled]="saving">
            {{ 'templateProfile.addFieldButton' | translate }}
          </app-button>
        </div>
      </section>
    </div>
  `,
    styles: [`
      .template-profile {
        max-width: 72rem;
        margin: 0 auto;
      }

      .profile-header {
        display: flex;
        justify-content: space-between;
        align-items: flex-start;
        gap: var(--spacing-lg);
        margin-bottom: var(--spacing-lg);
      }

      .title-block h1 {
        font-size: 2.2rem;
        margin: 0 0 var(--spacing-xs) 0;
      }

      .description {
        color: var(--color-text-secondary);
        margin: 0;
        max-width: 40rem;
      }

      .title-input {
        font-size: 2rem;
        font-weight: 600;
        padding: var(--spacing-sm);
        width: min(32rem, 100%);
        border: 1px solid var(--border-color);
        border-radius: var(--border-radius-md);
      }

      .description-input {
        width: min(40rem, 100%);
        padding: var(--spacing-sm);
        border: 1px solid var(--border-color);
        border-radius: var(--border-radius-md);
        resize: vertical;
      }

      .header-actions {
        display: flex;
        gap: var(--spacing-sm);
        flex-wrap: wrap;
        justify-content: flex-end;
      }

      .meta-row {
        display: flex;
        gap: var(--spacing-xl);
        padding: var(--spacing-md);
        border: 1px solid var(--border-color);
        border-radius: var(--border-radius-lg);
        background: var(--color-card-background);
        margin-bottom: var(--spacing-lg);
        flex-wrap: wrap;
      }

      .meta-item {
        display: flex;
        flex-direction: column;
        gap: var(--spacing-xs);
      }

      .meta-label {
        font-size: 0.85rem;
        color: var(--color-text-muted);
        text-transform: uppercase;
        letter-spacing: 0.04em;
      }

      .meta-value {
        font-weight: 600;
      }

      .visibility-toggle {
        display: inline-flex;
        align-items: center;
        gap: var(--spacing-sm);
        font-weight: 600;
      }

      .safe-note {
        color: var(--color-text-secondary);
        margin: 0 0 var(--spacing-lg) 0;
      }

      .ai-profile-section {
        margin-bottom: var(--spacing-2xl);
        border: 1px solid var(--glass-border);
        border-radius: var(--border-radius-lg);
        background: var(--color-card-background);
        padding: var(--spacing-lg);
        box-shadow: var(--shadow-sm);
      }

      .ai-hint {
        margin: 0 0 var(--spacing-md);
        color: var(--color-text-muted);
        font-size: 0.95rem;
      }

      .ai-profile-input {
        width: 100%;
        border-radius: var(--border-radius-md);
        border: 1px solid var(--border-color);
        padding: var(--spacing-md);
        background: var(--color-background);
        color: var(--color-text-primary);
        resize: vertical;
      }

      .ai-profile-preview {
        margin: 0;
        white-space: pre-wrap;
        color: var(--color-text-primary);
        font-size: 0.95rem;
      }

      .preview-section {
        margin-bottom: var(--spacing-2xl);
      }

      .section-header h2 {
        font-size: 1.4rem;
        margin: 0 0 var(--spacing-md) 0;
      }

      .flashcard-container {
        display: flex;
        flex-direction: column;
        align-items: center;
        gap: var(--spacing-sm);
      }

      .flashcard {
        width: 100%;
        max-width: 640px;
        min-height: 360px;
        cursor: pointer;
        perspective: 1000px;
      }

      .flashcard-inner {
        display: grid;
        width: 100%;
        min-height: 360px;
        transition: transform 0.6s;
        transform-style: preserve-3d;
      }

      .flashcard.flipped .flashcard-inner {
        transform: rotateY(180deg);
      }

      .flashcard-face {
        grid-area: 1 / 1;
        position: relative;
        width: 100%;
        min-height: 360px;
        backface-visibility: hidden;
        background: var(--color-card-background);
        border: 1px solid var(--border-color);
        border-radius: var(--border-radius-lg);
        padding: var(--spacing-xl);
        display: flex;
        flex-direction: column;
        gap: var(--spacing-md);
        align-items: center;
        text-align: center;
      }

      .flashcard-face.back {
        transform: rotateY(180deg);
      }

      .field-block {
        display: flex;
        flex-direction: column;
        gap: var(--spacing-xs);
        align-items: center;
        text-align: center;
        width: 100%;
      }

      .field-label {
        font-size: 0.8rem;
        text-transform: uppercase;
        letter-spacing: 0.05em;
        color: var(--color-text-muted);
      }

      .field-value {
        font-size: 1rem;
        color: var(--color-text-primary);
      }

      .empty-preview {
        color: var(--color-text-muted);
        font-style: italic;
        text-align: center;
      }

      .flip-hint {
        font-size: 0.9rem;
        color: var(--color-text-muted);
      }

      .fields-section {
        margin-bottom: var(--spacing-2xl);
      }

      .fields-grid {
        display: grid;
        gap: var(--spacing-lg);
        grid-template-columns: repeat(1, minmax(0, 1fr));
      }

      .fields-column h3 {
        font-size: 1.1rem;
        margin: 0 0 var(--spacing-sm) 0;
      }

      .field-card {
        border: 1px solid var(--border-color);
        border-radius: var(--border-radius-md);
        padding: var(--spacing-md);
        background: var(--color-card-background);
        display: grid;
        gap: var(--spacing-sm);
        margin-bottom: var(--spacing-sm);
      }

      .field-header {
        display: flex;
        justify-content: space-between;
        align-items: center;
        gap: var(--spacing-sm);
      }

      .field-key {
        font-weight: 600;
      }

      .field-type {
        font-size: 0.85rem;
        color: var(--color-text-muted);
      }

      .field-body {
        display: flex;
        flex-direction: column;
        gap: var(--spacing-xs);
        font-size: 0.9rem;
      }

      .field-input {
        padding: var(--spacing-sm);
        border: 1px solid var(--border-color);
        border-radius: var(--border-radius-md);
      }

      .field-actions {
        display: flex;
        flex-wrap: wrap;
        gap: var(--spacing-sm);
      }

      .link-btn {
        background: none;
        border: none;
        color: var(--color-primary-accent);
        cursor: pointer;
        padding: 0;
        font-size: 0.85rem;
      }

      .add-field {
        margin-top: var(--spacing-xl);
        padding: var(--spacing-lg);
        border: 1px dashed var(--border-color);
        border-radius: var(--border-radius-lg);
        display: grid;
        gap: var(--spacing-md);
      }

      .add-field-grid {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
        gap: var(--spacing-md);
      }

      .form-field {
        display: flex;
        flex-direction: column;
        gap: var(--spacing-xs);
      }

      .error-text {
        color: #b91c1c;
        margin: 0;
      }

      @media (min-width: 900px) {
        .fields-grid {
          grid-template-columns: repeat(2, minmax(0, 1fr));
        }
      }

      @media (max-width: 768px) {
        .template-profile {
          padding: 0 var(--spacing-md);
        }

        .profile-header {
          flex-direction: column;
          align-items: flex-start;
        }

        .title-block h1 {
          font-size: 1.6rem;
        }

        .flashcard,
        .flashcard-inner,
        .flashcard-face {
          min-height: 280px;
        }
      }
    `]
})
export class TemplateProfileComponent implements OnInit {
    private static readonly MAX_TEMPLATE_NAME = 50;
    private static readonly MAX_TEMPLATE_DESCRIPTION = 200;
    private static readonly MAX_FIELD_LABEL = 50;
    private static readonly MAX_FIELD_HELP_TEXT = 100;
    readonly maxTemplateName = TemplateProfileComponent.MAX_TEMPLATE_NAME;
    readonly maxTemplateDescription = TemplateProfileComponent.MAX_TEMPLATE_DESCRIPTION;
    readonly maxFieldLabel = TemplateProfileComponent.MAX_FIELD_LABEL;
    readonly maxFieldHelpText = TemplateProfileComponent.MAX_FIELD_HELP_TEXT;
    loading = true;
    saving = false;
    template: CardTemplateDTO | null = null;
    currentUserId: string | null = null;
    isOwner = false;
    fromWizard = false;
    isFlipped = false;
    editing = false;
    templateError = '';
    requestedVersion: number | null = null;
    isLatestVersion = true;

    draftName = '';
    draftDescription = '';
    draftPublic = false;
    draftAiProfilePrompt = '';
    draftAiProfileMapping: Record<string, string> | null = null;

    frontFields: EditableField[] = [];
    backFields: EditableField[] = [];
    originalFields: Map<string, EditableField> = new Map();
    fieldError = '';

    newField: {
        name: string;
        label: string;
        fieldType: string;
        side: FieldSide;
        helpText: string;
    } = {
        name: '',
        label: '',
        fieldType: 'text',
        side: 'front',
        helpText: ''
    };

    constructor(
        private route: ActivatedRoute,
        private router: Router,
        private templateApi: TemplateApiService,
        private userApi: UserApiService,
        private wizardState: DeckWizardStateService,
        private i18n: I18nService
    ) {}

    ngOnInit(): void {
        const templateId = this.route.snapshot.paramMap.get('templateId') || '';
        const versionParam = this.route.snapshot.queryParamMap.get('version');
        this.requestedVersion = versionParam ? Number(versionParam) : null;
        this.fromWizard = this.route.snapshot.queryParamMap.get('from') === 'wizard';
        if (!templateId) {
            this.loading = false;
            return;
        }

        forkJoin({
            template: this.templateApi.getTemplate(templateId, this.requestedVersion),
            user: this.userApi.getMe()
        }).subscribe({
            next: ({ template, user }) => {
                this.template = template;
                this.currentUserId = user.id;
                this.isOwner = template.ownerId === user.id;
                this.isLatestVersion = !template.latestVersion || !template.version || template.version === template.latestVersion;
                this.hydrateDraft(template);
                this.loading = false;
            },
            error: () => {
                this.loading = false;
            }
        });
    }

    startEdit(): void {
        if (!this.template) return;
        if (!this.isLatestVersion) {
            this.templateError = this.i18n.translate('templateProfile.versionEditBlocked');
            return;
        }
        this.editing = true;
        this.fieldError = '';
        this.templateError = '';
    }

    cancelEdit(): void {
        if (!this.template) return;
        this.editing = false;
        this.fieldError = '';
        this.templateError = '';
        this.hydrateDraft(this.template);
    }

    saveChanges(): void {
        if (!this.template || this.saving) return;
        this.saving = true;
        this.fieldError = '';
        this.templateError = '';

        const name = this.draftName.trim();
        if (!name) {
            this.templateError = this.i18n.translate('templateProfile.nameRequiredError');
            this.saving = false;
            return;
        }
        if (name.length > TemplateProfileComponent.MAX_TEMPLATE_NAME) {
            this.templateError = this.i18n.translate('validation.maxLength50');
            this.saving = false;
            return;
        }

        const description = this.draftDescription.trim();
        if (description.length > TemplateProfileComponent.MAX_TEMPLATE_DESCRIPTION) {
            this.templateError = this.i18n.translate('validation.maxLength200');
            this.saving = false;
            return;
        }

        const frontNames = this.frontFields.map(field => field.name);
        const backNames = this.backFields.map(field => field.name);
        const aiProfilePayload = this.buildAiProfilePayload();

        const templateUpdates: Partial<CardTemplateDTO> = {
            name,
            description,
            isPublic: this.draftPublic,
            layout: {
                front: frontNames,
                back: backNames
            },
            aiProfile: aiProfilePayload
        };

        const allFieldsForValidation = this.collectFieldsWithOrder();
        if (allFieldsForValidation.some(field => field.label.length > TemplateProfileComponent.MAX_FIELD_LABEL)) {
            this.templateError = this.i18n.translate('validation.maxLength50');
            this.saving = false;
            return;
        }
        if (allFieldsForValidation.some(field => (field.helpText || '').length > TemplateProfileComponent.MAX_FIELD_HELP_TEXT)) {
            this.templateError = this.i18n.translate('validation.maxLength100');
            this.saving = false;
            return;
        }

        const updateRequests = [];

        if (this.shouldUpdateTemplate(templateUpdates)) {
            updateRequests.push(this.templateApi.patchTemplate(this.template.templateId, templateUpdates));
        }

        const allFields = this.collectFieldsWithOrder();
        for (const field of allFields) {
            if (field.isNew) {
                updateRequests.push(this.templateApi.addField(this.template.templateId, {
                    name: field.name,
                    label: field.label,
                    fieldType: field.fieldType,
                    isRequired: false,
                    isOnFront: field.isOnFront,
                    orderIndex: field.orderIndex,
                    defaultValue: null,
                    helpText: field.helpText || null
                }));
            } else if (this.shouldUpdateField(field)) {
                updateRequests.push(this.templateApi.patchField(this.template.templateId, field.fieldId!, {
                    name: field.name,
                    label: field.label,
                    fieldType: field.fieldType,
                    isRequired: field.isRequired,
                    isOnFront: field.isOnFront,
                    orderIndex: field.orderIndex,
                    defaultValue: field.defaultValue || null,
                    helpText: field.helpText || null
                }));
            }
        }

        if (updateRequests.length === 0) {
            this.saving = false;
            this.editing = false;
            return;
        }

        forkJoin(updateRequests).subscribe({
            next: () => {
                this.templateApi.getTemplate(this.template!.templateId).subscribe({
                    next: template => {
                        this.template = template;
                        this.hydrateDraft(template);
                        this.editing = false;
                        this.saving = false;
                    },
                    error: () => {
                        this.saving = false;
                    }
                });
            },
            error: () => {
                this.saving = false;
            }
        });
    }

    moveField(side: FieldSide, index: number, direction: -1 | 1): void {
        const list = side === 'front' ? this.frontFields : this.backFields;
        const newIndex = index + direction;
        if (newIndex < 0 || newIndex >= list.length) {
            return;
        }
        const [field] = list.splice(index, 1);
        list.splice(newIndex, 0, field);
    }

    moveToSide(fromSide: FieldSide, index: number, toSide: FieldSide): void {
        const fromList = fromSide === 'front' ? this.frontFields : this.backFields;
        const toList = toSide === 'front' ? this.frontFields : this.backFields;
        const [field] = fromList.splice(index, 1);
        toList.push(field);
    }

    addField(): void {
        const name = this.newField.name.trim();
        const label = this.newField.label.trim();
        if (!name || !label) {
            this.fieldError = this.i18n.translate('templateProfile.fieldRequiredError');
            return;
        }
        if (label.length > TemplateProfileComponent.MAX_FIELD_LABEL) {
            this.fieldError = this.i18n.translate('validation.maxLength50');
            return;
        }
        if (this.newField.helpText.trim().length > TemplateProfileComponent.MAX_FIELD_HELP_TEXT) {
            this.fieldError = this.i18n.translate('validation.maxLength100');
            return;
        }

        const existingNames = new Set([...this.frontFields, ...this.backFields].map(f => f.name));
        if (existingNames.has(name)) {
            this.fieldError = this.i18n.translate('templateProfile.fieldDuplicateError');
            return;
        }

        const newEntry: EditableField = {
            name,
            label,
            fieldType: this.newField.fieldType,
            isRequired: false,
            isOnFront: this.newField.side === 'front',
            orderIndex: 0,
            helpText: this.newField.helpText.trim() || null,
            defaultValue: null,
            isNew: true
        };

        if (this.newField.side === 'front') {
            this.frontFields.push(newEntry);
        } else {
            this.backFields.push(newEntry);
        }

        this.newField = {
            name: '',
            label: '',
            fieldType: 'text',
            side: this.newField.side,
            helpText: ''
        };
        this.fieldError = '';
        this.templateError = '';
    }

    toggleFlip(): void {
        this.isFlipped = !this.isFlipped;
    }

    sampleValue(field: EditableField): string {
        switch (field.fieldType) {
            case 'text':
                return this.i18n.translate('visualBuilder.sampleText');
            case 'rich_text':
                return this.i18n.translate('visualBuilder.sampleLongText');
            case 'markdown':
                return this.i18n.translate('visualBuilder.sampleMarkdown');
            case 'image':
                return this.i18n.translate('visualBuilder.sampleImage');
            case 'audio':
                return this.i18n.translate('visualBuilder.sampleAudio');
            case 'video':
                return this.i18n.translate('visualBuilder.sampleVideo');
            default:
                return this.i18n.translate('visualBuilder.sampleText');
        }
    }

    formatDate(value: string): string {
        const date = new Date(value);
        if (Number.isNaN(date.getTime())) {
            return value;
        }
        return date.toLocaleDateString();
    }

    useTemplate(): void {
        if (!this.template) return;
        this.wizardState.setTemplateId(this.template.templateId);
        this.wizardState.setCurrentStep(2);
        void this.router.navigate(['/create-deck']);
    }

    private hydrateDraft(template: CardTemplateDTO): void {
        this.draftName = template.name || '';
        this.draftDescription = template.description || '';
        this.draftPublic = template.isPublic;
        const aiProfile = this.parseAiProfile(template.aiProfile);
        this.draftAiProfilePrompt = aiProfile.prompt;
        this.draftAiProfileMapping = aiProfile.fieldsMapping ?? null;
        this.isFlipped = false;
        this.buildEditableFields(template);
    }

    private buildEditableFields(template: CardTemplateDTO): void {
        this.frontFields = [];
        this.backFields = [];
        this.originalFields = new Map();

        const fields = template.fields || [];
        const fieldsByName = new Map<string, FieldTemplateDTO>();
        fields.forEach(field => fieldsByName.set(field.name, field));

        const usedNames = new Set<string>();
        const layoutFront = template.layout?.front || [];
        const layoutBack = template.layout?.back || [];

        const pushField = (field: FieldTemplateDTO, side: FieldSide) => {
            const editable: EditableField = {
                fieldId: field.fieldId,
                name: field.name,
                label: field.label,
                fieldType: field.fieldType,
                isRequired: field.isRequired,
                isOnFront: side === 'front',
                orderIndex: field.orderIndex,
                helpText: field.helpText ?? null,
                defaultValue: field.defaultValue ?? null
            };
            if (side === 'front') {
                this.frontFields.push(editable);
            } else {
                this.backFields.push(editable);
            }
            this.originalFields.set(field.fieldId, { ...editable });
        };

        layoutFront.forEach(name => {
            const field = fieldsByName.get(name);
            if (field) {
                pushField(field, 'front');
                usedNames.add(name);
            }
        });

        layoutBack.forEach(name => {
            const field = fieldsByName.get(name);
            if (field) {
                pushField(field, 'back');
                usedNames.add(name);
            }
        });

        fields.forEach(field => {
            if (usedNames.has(field.name)) {
                return;
            }
            pushField(field, field.isOnFront ? 'front' : 'back');
        });
    }

    private collectFieldsWithOrder(): EditableField[] {
        return [
            ...this.frontFields.map((field, index) => ({
                ...field,
                isOnFront: true,
                orderIndex: index
            })),
            ...this.backFields.map((field, index) => ({
                ...field,
                isOnFront: false,
                orderIndex: index
            }))
        ];
    }

    private shouldUpdateTemplate(updates: Partial<CardTemplateDTO>): boolean {
        if (!this.template) return false;
        const currentProfile = this.normalizeAiProfile(this.template.aiProfile);
        const nextProfile = this.normalizeAiProfile(updates.aiProfile);
        return updates.name !== this.template.name ||
            updates.description !== this.template.description ||
            updates.isPublic !== this.template.isPublic ||
            JSON.stringify(updates.layout) !== JSON.stringify(this.template.layout) ||
            currentProfile !== nextProfile;
    }

    private parseAiProfile(
        raw: CardTemplateDTO['aiProfile']
    ): { prompt: string; fieldsMapping: Record<string, string> } {
        if (!raw) {
            return { prompt: '', fieldsMapping: {} };
        }
        if (typeof raw === 'string') {
            return { prompt: raw, fieldsMapping: {} };
        }
        const prompt = typeof raw.prompt === 'string' ? raw.prompt : '';
        const fieldsMapping = raw.fieldsMapping || {};
        return { prompt, fieldsMapping };
    }

    private buildAiProfilePayload(): { prompt: string; fieldsMapping: Record<string, string> } | null {
        const prompt = this.draftAiProfilePrompt.trim();
        if (!prompt) {
            return null;
        }
        const fieldsMapping = this.draftAiProfileMapping && Object.keys(this.draftAiProfileMapping).length > 0
            ? this.draftAiProfileMapping
            : {};
        return { prompt, fieldsMapping };
    }

    private normalizeAiProfile(raw: CardTemplateDTO['aiProfile']): string {
        if (!raw) {
            return '';
        }
        if (typeof raw === 'string') {
            return raw.trim();
        }
        try {
            return JSON.stringify(raw);
        } catch {
            return '';
        }
    }

    private shouldUpdateField(field: EditableField): boolean {
        if (!field.fieldId) {
            return false;
        }
        const original = this.originalFields.get(field.fieldId);
        if (!original) {
            return true;
        }
        return field.label !== original.label ||
            field.helpText !== original.helpText ||
            field.isOnFront !== original.isOnFront ||
            field.orderIndex !== original.orderIndex;
    }
}
