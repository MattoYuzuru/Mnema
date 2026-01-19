import { Component, EventEmitter, Input, OnDestroy, Output } from '@angular/core';
import { NgFor, NgIf, NgClass } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ImportApiService } from '../../core/services/import-api.service';
import {
    ImportPreviewResponse,
    ImportMode,
    ImportSourceType,
    UploadImportSourceResponse,
    ImportJobResponse
} from '../../core/models/import.models';
import { ButtonComponent } from '../../shared/components/button.component';
import { InputComponent } from '../../shared/components/input.component';
import { TextareaComponent } from '../../shared/components/textarea.component';
import { TranslatePipe } from '../../shared/pipes/translate.pipe';

type ImportModeUI = 'create' | 'merge';

@Component({
    selector: 'app-import-deck-modal',
    standalone: true,
    imports: [NgIf, NgFor, NgClass, FormsModule, ButtonComponent, InputComponent, TextareaComponent, TranslatePipe],
    template: `
    <div class="modal-overlay" (click)="close()">
      <div class="modal-content" (click)="$event.stopPropagation()">
        <div class="modal-header">
          <div>
            <h2>{{ mode === 'merge' ? ('import.mergeTitle' | translate) : ('import.newTitle' | translate) }}</h2>
            <p class="subtitle">
              {{ mode === 'merge'
                ? ('import.mergeSubtitle' | translate)
                : ('import.newSubtitle' | translate)
              }}
            </p>
          </div>
          <button class="close-btn" (click)="close()">&times;</button>
        </div>

        <div class="modal-body">
          <section class="info-block">
            <h3>{{ 'import.howItWorksTitle' | translate }}</h3>
            <ul>
              <li>{{ 'import.howItWorksFormats' | translate }}</li>
              <li>{{ 'import.howItWorksFields' | translate }}</li>
              <li>{{ 'import.howItWorksMedia' | translate }}</li>
              <li *ngIf="mode === 'merge'">{{ 'import.howItWorksMerge' | translate }}</li>
              <li *ngIf="mode === 'create'">{{ 'import.howItWorksPrivate' | translate }}</li>
            </ul>
          </section>

          <section class="upload-block">
            <h3>{{ 'import.uploadTitle' | translate }}</h3>
            <div
              class="dropzone"
              [class.active]="dragActive"
              (dragover)="onDragOver($event)"
              (dragleave)="onDragLeave($event)"
              (drop)="onDrop($event)"
            >
              <input type="file" accept=".apkg,.mnema,.csv,.tsv,.txt" (change)="onFileChange($event)" hidden #fileInput />
              <div class="dropzone-content">
                <span class="dropzone-icon">⬆️</span>
                <div class="dropzone-text">
                  <strong>{{ 'import.dropHere' | translate }}</strong>
                  <span>{{ 'import.or' | translate }}</span>
                  <button type="button" class="link-btn" (click)="fileInput.click()">{{ 'import.chooseFile' | translate }}</button>
                </div>
                <span class="dropzone-hint">{{ 'import.formatsHint' | translate }}</span>
              </div>
            </div>

            <div *ngIf="uploading" class="status-line">{{ 'import.uploading' | translate }}</div>
            <div *ngIf="previewing" class="status-line">{{ 'import.previewing' | translate }}</div>
            <div *ngIf="fileInfo && !uploading" class="file-info">
              <span class="file-name">{{ fileInfo.fileName }}</span>
              <span class="file-size">{{ formatBytes(fileInfo.sizeBytes) }}</span>
              <span class="file-type">{{ fileInfo.sourceType.toUpperCase() }}</span>
            </div>
          </section>

          <section *ngIf="mode === 'create'" class="deck-name-block">
            <app-input
              [label]="'import.deckNameLabel' | translate"
              [placeholder]="'import.deckNamePlaceholder' | translate"
              [(ngModel)]="deckName"
              [hasError]="deckNameTooLong"
              [errorMessage]="'validation.maxLength50' | translate"
              [maxLength]="maxDeckName"
            ></app-input>
            <app-textarea
              [label]="'import.deckDescriptionLabel' | translate"
              [placeholder]="'import.deckDescriptionPlaceholder' | translate"
              [(ngModel)]="deckDescription"
              [rows]="3"
              [hasError]="deckDescriptionTooLong"
              [errorMessage]="'validation.maxLength200' | translate"
              [maxLength]="maxDeckDescription"
            ></app-textarea>
            <div class="form-group">
              <label>{{ 'import.languageLabel' | translate }}</label>
              <select [(ngModel)]="language" class="language-select">
                <option value="en">{{ 'language.english' | translate }}</option>
                <option value="ru">{{ 'language.russian' | translate }}</option>
                <option value="jp">日本語 (Japanese)</option>
                <option value="sp">Español (Spanish)</option>
              </select>
            </div>
            <div class="form-group">
              <label>{{ 'import.tagsLabel' | translate }}</label>
              <input type="text" class="tag-input" [(ngModel)]="tagInput" (keydown.enter)="addTag($event)" [placeholder]="'import.tagsPlaceholder' | translate" [attr.maxlength]="maxTagLength" />
              <div *ngIf="tags.length > 0" class="tags-list">
                <span *ngFor="let tag of tags; let i = index" class="tag-chip">{{ tag }} <button type="button" (click)="removeTag(i)">×</button></span>
              </div>
              <p *ngIf="tagError" class="error-text">{{ tagError | translate }}</p>
            </div>
            <div class="checkbox-group">
              <label>
                <input type="checkbox" [(ngModel)]="isPublic" (change)="onPublicChange()" />
                {{ 'import.makePublic' | translate }}
              </label>
            </div>
            <div class="checkbox-group">
              <label>
                <input type="checkbox" [(ngModel)]="isListed" [disabled]="!isPublic" />
                {{ 'import.listInCatalog' | translate }}
              </label>
            </div>
          </section>

          <section *ngIf="preview" class="preview-block">
            <h3>{{ 'import.previewTitle' | translate }}</h3>
            <div class="field-list">
              <span *ngFor="let field of preview.sourceFields" class="field-chip">{{ field.name }}</span>
            </div>

            <div class="sample-card" *ngIf="sampleEntries.length > 0">
              <h4>{{ 'import.sampleTitle' | translate }}</h4>
              <div *ngFor="let entry of sampleEntries" class="sample-row">
                <span class="sample-label">{{ entry[0] }}</span>
                <span class="sample-value">{{ entry[1] || ('import.emptyValue' | translate) }}</span>
              </div>
            </div>
          </section>

          <section *ngIf="mode === 'merge' && preview" class="mapping-block">
            <h3>{{ 'import.mappingTitle' | translate }}</h3>
            <p class="mapping-subtitle">{{ 'import.mappingSubtitle' | translate }}</p>
            <div class="mapping-grid">
              <div *ngFor="let field of preview.targetFields" class="mapping-row">
                <div class="mapping-target">
                  <span class="target-name">{{ field.name }}</span>
                  <span class="target-type">{{ field.fieldType || 'text' }}</span>
                </div>
                <select
                  class="mapping-select"
                  [value]="mapping[field.name] || ''"
                  (change)="onMappingChange(field.name, $any($event.target).value)"
                >
                  <option value="">{{ 'import.mappingSkip' | translate }}</option>
                  <option *ngFor="let source of preview.sourceFields" [value]="source.name">
                    {{ source.name }}
                  </option>
                </select>
              </div>
            </div>
          </section>

          <section *ngIf="job" class="job-block">
            <h3>{{ 'import.statusTitle' | translate }}</h3>
            <div class="status-card">
              <div class="status-row">
                <span>{{ 'import.statusLabel' | translate }}</span>
                <span>{{ statusLabel(job.status) | translate }}</span>
              </div>
              <div class="status-row" *ngIf="job.totalItems">
                <span>{{ 'import.progressLabel' | translate }}</span>
                <span>{{ job.processedItems || 0 }}/{{ job.totalItems }}</span>
              </div>
              <div class="progress-bar" *ngIf="progressPercent !== null">
                <div class="progress-fill" [style.width.%]="progressPercent"></div>
              </div>
              <p *ngIf="job.status === 'completed'" class="status-message">{{ 'import.completed' | translate }}</p>
              <p *ngIf="job.status === 'failed'" class="status-message error">{{ job.errorMessage || ('import.failed' | translate) }}</p>
            </div>
          </section>

          <p *ngIf="errorMessage" class="error-text">{{ errorMessage | translate }}</p>
        </div>

        <div class="modal-footer">
          <app-button variant="ghost" (click)="close()">{{ 'import.close' | translate }}</app-button>
          <app-button
            *ngIf="showProfileAction && job && (job.status === 'processing' || job.status === 'completed')"
            variant="secondary"
            (click)="goProfile.emit()"
          >
            {{ 'import.goToDecks' | translate }}
          </app-button>
          <app-button
            variant="primary"
            (click)="startImport()"
            [disabled]="!canStartImport || starting"
          >
            {{ starting ? ('import.starting' | translate) : ('import.start' | translate) }}
          </app-button>
        </div>
      </div>
    </div>
  `,
    styles: [`
      .modal-overlay { position: fixed; inset: 0; background: rgba(8, 12, 22, 0.55); display: flex; align-items: center; justify-content: center; z-index: 1000; backdrop-filter: blur(12px) saturate(140%); }
      .modal-content { width: min(920px, 92vw); max-height: 92vh; background: var(--color-surface-solid); border-radius: var(--border-radius-lg); border: 1px solid var(--glass-border); box-shadow: var(--shadow-lg); display: flex; flex-direction: column; overflow: hidden; }
      .modal-header { display: flex; justify-content: space-between; align-items: flex-start; gap: var(--spacing-lg); padding: var(--spacing-xl); border-bottom: 1px solid var(--glass-border); }
      .modal-header h2 { margin: 0; font-size: 1.6rem; }
      .subtitle { margin: var(--spacing-xs) 0 0; color: var(--color-text-secondary); font-size: 0.95rem; }
      .close-btn { background: none; border: none; font-size: 2rem; line-height: 1; cursor: pointer; color: var(--color-text-secondary); }
      .modal-body { padding: var(--spacing-xl); overflow-y: auto; display: flex; flex-direction: column; gap: var(--spacing-xl); }
      .modal-footer { padding: var(--spacing-lg); border-top: 1px solid var(--glass-border); display: flex; justify-content: flex-end; gap: var(--spacing-sm); flex-wrap: wrap; }
      .info-block ul { margin: var(--spacing-sm) 0 0; padding-left: 1.25rem; color: var(--color-text-secondary); display: grid; gap: var(--spacing-xs); }
      .upload-block { display: flex; flex-direction: column; gap: var(--spacing-md); }
      .dropzone { border: 2px dashed var(--border-color); border-radius: var(--border-radius-lg); padding: var(--spacing-xl); background: var(--color-background); transition: border-color 0.2s ease, background 0.2s ease; }
      .dropzone.active { border-color: var(--color-primary-accent); background: rgba(59, 130, 246, 0.08); }
      .dropzone-content { display: grid; justify-items: center; gap: var(--spacing-sm); text-align: center; }
      .dropzone-icon { font-size: 2rem; }
      .dropzone-text { display: grid; gap: var(--spacing-xs); }
      .dropzone-hint { font-size: 0.85rem; color: var(--color-text-muted); }
      .link-btn { background: none; border: none; color: var(--color-primary-accent); cursor: pointer; font-weight: 600; }
      .file-info { display: flex; gap: var(--spacing-md); flex-wrap: wrap; font-size: 0.9rem; color: var(--color-text-secondary); }
      .file-name { font-weight: 600; color: var(--color-text-primary); }
      .status-line { font-size: 0.9rem; color: var(--color-text-secondary); }
      .preview-block { display: flex; flex-direction: column; gap: var(--spacing-md); }
      .field-list { display: flex; flex-wrap: wrap; gap: var(--spacing-xs); }
      .field-chip { padding: 0.3rem 0.6rem; background: rgba(148, 163, 184, 0.2); border-radius: 999px; font-size: 0.8rem; }
      .sample-card { background: var(--color-background); border: 1px solid var(--border-color); border-radius: var(--border-radius-md); padding: var(--spacing-md); }
      .sample-row { display: grid; grid-template-columns: 140px 1fr; gap: var(--spacing-sm); padding: var(--spacing-xs) 0; border-bottom: 1px dashed var(--border-color); }
      .sample-row:last-child { border-bottom: none; }
      .sample-label { font-weight: 600; color: var(--color-text-secondary); }
      .sample-value { color: var(--color-text-primary); word-break: break-word; }
      .mapping-block { display: flex; flex-direction: column; gap: var(--spacing-md); }
      .mapping-subtitle { color: var(--color-text-secondary); margin: 0; }
      .mapping-grid { display: grid; gap: var(--spacing-sm); }
      .mapping-row { display: grid; grid-template-columns: 1fr 1fr; gap: var(--spacing-md); align-items: center; padding: var(--spacing-sm) var(--spacing-md); border: 1px solid var(--border-color); border-radius: var(--border-radius-md); background: var(--color-card-background); }
      .mapping-target { display: grid; gap: 2px; }
      .target-name { font-weight: 600; }
      .target-type { font-size: 0.8rem; color: var(--color-text-secondary); }
      .mapping-select { padding: var(--spacing-sm) var(--spacing-md); border-radius: var(--border-radius-md); border: 1px solid var(--border-color); background: var(--color-background); }
      .job-block { display: flex; flex-direction: column; gap: var(--spacing-sm); }
      .status-card { border: 1px solid var(--border-color); border-radius: var(--border-radius-md); padding: var(--spacing-md); background: var(--color-background); display: grid; gap: var(--spacing-sm); }
      .status-row { display: flex; justify-content: space-between; font-size: 0.9rem; color: var(--color-text-secondary); }
      .progress-bar { width: 100%; height: 8px; background: rgba(148, 163, 184, 0.2); border-radius: 999px; overflow: hidden; }
      .progress-fill { height: 100%; background: #111827; }
      .status-message { margin: 0; font-size: 0.9rem; color: var(--color-text-secondary); }
      .status-message.error { color: #dc2626; }
      .error-text { color: #dc2626; margin: 0; }
      .form-group { display: flex; flex-direction: column; gap: var(--spacing-xs); }
      .language-select { width: 100%; padding: var(--spacing-sm) var(--spacing-md); border: 1px solid var(--border-color); border-radius: var(--border-radius-md); font-size: 0.9rem; background: var(--color-card-background); cursor: pointer; }
      .tag-input { width: 100%; padding: var(--spacing-sm) var(--spacing-md); border: 1px solid var(--border-color); border-radius: var(--border-radius-md); font-size: 0.9rem; background: var(--color-card-background); }
      .tags-list { display: flex; flex-wrap: wrap; gap: var(--spacing-xs); margin-top: var(--spacing-sm); }
      .tag-chip { display: inline-flex; align-items: center; gap: var(--spacing-xs); padding: var(--spacing-xs) var(--spacing-sm); background: var(--color-background); border: 1px solid var(--border-color); border-radius: var(--border-radius-full); font-size: 0.85rem; }
      .tag-chip button { background: none; border: none; cursor: pointer; font-size: 1.2rem; line-height: 1; padding: 0; }
      .checkbox-group { display: flex; align-items: center; gap: var(--spacing-sm); }

      @media (max-width: 720px) {
        .mapping-row { grid-template-columns: 1fr; }
        .sample-row { grid-template-columns: 1fr; }
      }
    `]
})
export class ImportDeckModalComponent implements OnDestroy {
    private static readonly MAX_DECK_NAME = 50;
    private static readonly MAX_DECK_DESCRIPTION = 200;
    private static readonly MAX_TAGS = 5;
    private static readonly MAX_TAG_LENGTH = 25;
    @Input() mode: ImportModeUI = 'create';
    @Input() targetDeckId: string | null = null;
    @Input() showProfileAction = false;

    @Output() closed = new EventEmitter<void>();
    @Output() goProfile = new EventEmitter<void>();

    dragActive = false;
    uploading = false;
    previewing = false;
    starting = false;

    fileInfo: UploadImportSourceResponse | null = null;
    preview: ImportPreviewResponse | null = null;
    deckName = '';
    deckDescription = '';
    language: 'en' | 'ru' | 'jp' | 'sp' = 'en';
    isPublic = false;
    isListed = false;
    tags: string[] = [];
    tagInput = '';
    tagError = '';
    mapping: Record<string, string> = {};
    job: ImportJobResponse | null = null;
    errorMessage = '';
    readonly maxDeckName = ImportDeckModalComponent.MAX_DECK_NAME;
    readonly maxDeckDescription = ImportDeckModalComponent.MAX_DECK_DESCRIPTION;
    readonly maxTagLength = ImportDeckModalComponent.MAX_TAG_LENGTH;

    private pollHandle: ReturnType<typeof setInterval> | null = null;

    constructor(private importApi: ImportApiService) {}

    get canStartImport(): boolean {
        if (!this.fileInfo || !this.preview || this.starting || this.uploading || this.previewing) {
            return false;
        }
        if (this.job && this.job.status !== 'failed' && this.job.status !== 'canceled') {
            return false;
        }
        if (this.mode === 'create') {
            return !this.deckNameTooLong && !this.deckDescriptionTooLong && !this.hasInvalidTags();
        }
        return !!this.targetDeckId;
    }

    get deckNameTooLong(): boolean {
        return this.deckName.length > ImportDeckModalComponent.MAX_DECK_NAME;
    }

    get deckDescriptionTooLong(): boolean {
        return this.deckDescription.length > ImportDeckModalComponent.MAX_DECK_DESCRIPTION;
    }

    get sampleEntries(): [string, string][] {
        if (!this.preview || this.preview.sample.length === 0) {
            return [];
        }
        const sample = this.preview.sample[0];
        return Object.entries(sample);
    }

    get progressPercent(): number | null {
        if (!this.job || !this.job.totalItems || this.job.totalItems <= 0) {
            return null;
        }
        const processed = this.job.processedItems || 0;
        return Math.min(100, Math.round((processed / this.job.totalItems) * 100));
    }

    onDragOver(event: DragEvent): void {
        event.preventDefault();
        this.dragActive = true;
    }

    onDragLeave(event: DragEvent): void {
        event.preventDefault();
        this.dragActive = false;
    }

    onDrop(event: DragEvent): void {
        event.preventDefault();
        this.dragActive = false;
        const file = event.dataTransfer?.files?.[0];
        if (file) {
            this.handleFile(file);
        }
    }

    onFileChange(event: Event): void {
        const input = event.target as HTMLInputElement;
        const file = input.files?.[0];
        if (file) {
            this.handleFile(file);
        }
        input.value = '';
    }

    startImport(): void {
        if (!this.canStartImport || !this.fileInfo) {
            return;
        }
        this.starting = true;
        this.errorMessage = '';
        const mapping = this.mode === 'merge' ? this.cleanedMapping() : null;
        const mode: ImportMode = this.mode === 'merge' ? 'merge_into_existing' : 'create_new';
        const safeIsPublic = this.mode === 'create' ? this.isPublic : false;
        const safeIsListed = safeIsPublic ? this.isListed : false;
        const request = {
            sourceMediaId: this.fileInfo.mediaId,
            sourceType: this.fileInfo.sourceType,
            sourceName: this.fileInfo.fileName,
            sourceSizeBytes: this.fileInfo.sizeBytes,
            targetDeckId: this.mode === 'merge' ? this.targetDeckId : null,
            mode,
            deckName: this.mode === 'create' ? this.deckName || null : null,
            deckDescription: this.mode === 'create' ? this.deckDescription || null : null,
            language: this.mode === 'create' ? this.language : null,
            tags: this.mode === 'create' ? this.tags : null,
            isPublic: safeIsPublic,
            isListed: safeIsListed,
            fieldMapping: mapping
        };

        this.importApi.createImportJob(request).subscribe({
            next: job => {
                this.job = job;
                this.starting = false;
                this.startPolling(job.jobId);
            },
            error: () => {
                this.starting = false;
                this.errorMessage = 'import.startFailed';
            }
        });
    }

    close(): void {
        this.closed.emit();
    }

    ngOnDestroy(): void {
        this.stopPolling();
    }

    private handleFile(file: File): void {
        this.errorMessage = '';
        this.preview = null;
        this.job = null;
        this.fileInfo = null;
        this.deckName = this.defaultDeckName(file.name);
        this.deckDescription = '';
        this.language = 'en';
        this.tags = [];
        this.tagInput = '';
        this.tagError = '';
        this.isPublic = false;
        this.isListed = false;
        const sourceType = this.detectSourceType(file.name);

        this.uploading = true;
        this.importApi.uploadSource(file, sourceType || undefined).subscribe({
            next: response => {
                this.fileInfo = response;
                this.uploading = false;
                this.loadPreview();
            },
            error: () => {
                this.uploading = false;
                this.errorMessage = 'import.uploadFailed';
            }
        });
    }

    private loadPreview(): void {
        if (!this.fileInfo) {
            return;
        }
        this.previewing = true;
        this.importApi.preview({
            sourceMediaId: this.fileInfo.mediaId,
            sourceType: this.fileInfo.sourceType,
            targetDeckId: this.mode === 'merge' ? this.targetDeckId : null,
            sampleSize: 1
        }).subscribe({
            next: preview => {
                this.preview = preview;
                this.previewing = false;
                this.mapping = { ...preview.suggestedMapping };
            },
            error: () => {
                this.previewing = false;
                this.errorMessage = 'import.previewFailed';
            }
        });
    }

    onMappingChange(targetField: string, sourceField: string): void {
        if (!sourceField) {
            delete this.mapping[targetField];
            return;
        }
        this.mapping[targetField] = sourceField;
    }

    private cleanedMapping(): Record<string, string> {
        const result: Record<string, string> = {};
        Object.entries(this.mapping).forEach(([target, source]) => {
            if (source) {
                result[target] = source;
            }
        });
        return result;
    }

    private detectSourceType(fileName: string): ImportSourceType | null {
        const lower = fileName.toLowerCase();
        if (lower.endsWith('.apkg')) return 'apkg';
        if (lower.endsWith('.csv')) return 'csv';
        if (lower.endsWith('.tsv')) return 'tsv';
        if (lower.endsWith('.txt')) return 'txt';
        return null;
    }

    private defaultDeckName(fileName: string): string {
        return fileName.replace(/\.[^/.]+$/, '').slice(0, ImportDeckModalComponent.MAX_DECK_NAME);
    }

    addTag(event: Event): void {
        event.preventDefault();
        const tag = this.tagInput.trim();
        if (this.tags.length >= ImportDeckModalComponent.MAX_TAGS) {
            this.tagError = 'validation.tagsLimit';
            return;
        }
        if (tag.length > ImportDeckModalComponent.MAX_TAG_LENGTH) {
            this.tagError = 'validation.tagTooLong';
            return;
        }
        if (tag && !this.tags.includes(tag)) {
            this.tags.push(tag);
            this.tagInput = '';
            this.tagError = '';
        }
    }

    removeTag(index: number): void {
        this.tags.splice(index, 1);
        this.tagError = '';
    }

    onPublicChange(): void {
        if (!this.isPublic) {
            this.isListed = false;
        }
    }

    private hasInvalidTags(): boolean {
        if (this.tags.length > ImportDeckModalComponent.MAX_TAGS) {
            this.tagError = 'validation.tagsLimit';
            return true;
        }
        if (this.tags.some(tag => tag.length > ImportDeckModalComponent.MAX_TAG_LENGTH)) {
            this.tagError = 'validation.tagTooLong';
            return true;
        }
        this.tagError = '';
        return false;
    }

    private startPolling(jobId: string): void {
        this.stopPolling();
        this.pollHandle = setInterval(() => {
            this.importApi.getJob(jobId).subscribe({
                next: job => {
                    this.job = job;
                    if (job.status === 'completed' || job.status === 'failed' || job.status === 'canceled') {
                        this.stopPolling();
                    }
                }
            });
        }, 2000);
    }

    private stopPolling(): void {
        if (this.pollHandle) {
            clearInterval(this.pollHandle);
            this.pollHandle = null;
        }
    }

    formatBytes(bytes: number | null | undefined): string {
        if (!bytes) {
            return '0 B';
        }
        const sizes = ['B', 'KB', 'MB', 'GB'];
        const i = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), sizes.length - 1);
        const value = bytes / Math.pow(1024, i);
        return `${value.toFixed(1)} ${sizes[i]}`;
    }

    statusLabel(status: string): string {
        switch (status) {
            case 'queued':
                return 'import.statusQueued';
            case 'processing':
                return 'import.statusProcessing';
            case 'completed':
                return 'import.statusCompleted';
            case 'failed':
                return 'import.statusFailed';
            case 'canceled':
                return 'import.statusCanceled';
            default:
                return 'import.statusUnknown';
        }
    }
}
