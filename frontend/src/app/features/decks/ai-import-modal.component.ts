import { Component, EventEmitter, Input, OnInit, Output, computed, signal } from '@angular/core';
import { NgFor, NgIf, NgClass } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { AiApiService } from '../../core/services/ai-api.service';
import { MediaApiService } from '../../core/services/media-api.service';
import { TemplateApiService } from '../../core/services/template-api.service';
import {
    AiImportPreviewSummary,
    AiJobResponse,
    AiProviderCredential
} from '../../core/models/ai.models';
import { FieldTemplateDTO } from '../../core/models/template.models';
import { ButtonComponent } from '../../shared/components/button.component';
import { InputComponent } from '../../shared/components/input.component';
import { TextareaComponent } from '../../shared/components/textarea.component';
import { TranslatePipe } from '../../shared/pipes/translate.pipe';

interface ImportFileInfo {
    mediaId: string;
    fileName: string;
    sizeBytes: number;
}

interface FieldOption {
    key: string;
    label: string;
    enabled: boolean;
    required: boolean;
}

interface TtsMapping {
    sourceField: string;
    targetField: string;
}

interface EncodingOption {
    label: string;
    value: string;
}

@Component({
    selector: 'app-ai-import-modal',
    standalone: true,
    imports: [NgIf, NgFor, NgClass, FormsModule, ButtonComponent, InputComponent, TextareaComponent, TranslatePipe],
    template: `
    <div class="modal-overlay" (click)="close()">
      <div class="modal-content ai-modal" (click)="$event.stopPropagation()">
        <div class="modal-header">
          <div>
            <h2>{{ 'aiImport.title' | translate }}</h2>
            <p class="subtitle">{{ 'aiImport.subtitle' | translate }}</p>
          </div>
          <button class="close-btn" (click)="close()">&times;</button>
        </div>

        <div class="modal-body">
          <section class="section-card">
            <h3>{{ 'aiImport.providerTitle' | translate }}</h3>
            <div class="form-grid">
              <div class="form-field">
                <label for="ai-import-provider">{{ 'aiImport.providerLabel' | translate }}</label>
                <select
                  id="ai-import-provider"
                  [ngModel]="selectedCredentialId()"
                  (ngModelChange)="onProviderChange($event)"
                  [disabled]="loadingProviders() || providerKeys().length === 0"
                >
                  <option [ngValue]="''">{{ 'aiImport.providerPlaceholder' | translate }}</option>
                  <option *ngFor="let key of providerKeys(); trackBy: trackProvider" [ngValue]="key.id">
                    {{ key.provider }}{{ key.alias ? ' - ' + key.alias : '' }}
                  </option>
                </select>
                <p *ngIf="!loadingProviders() && providerKeys().length === 0" class="field-hint">
                  {{ 'aiImport.noKeys' | translate }}
                </p>
                <p *ngIf="selectedCredentialId() && !importSupported()" class="field-hint">
                  {{ 'aiImport.providerUnsupported' | translate }}
                </p>
              </div>
              <div class="form-field">
                <label for="ai-import-encoding">{{ 'aiImport.encodingLabel' | translate }}</label>
                <select
                  id="ai-import-encoding"
                  [ngModel]="encoding()"
                  (ngModelChange)="encoding.set($event)"
                >
                  <option *ngFor="let opt of encodingOptions" [ngValue]="opt.value">{{ opt.label }}</option>
                </select>
              </div>
              <div class="form-field">
                <label for="ai-import-model">{{ 'aiImport.modelLabel' | translate }}</label>
                <input
                  id="ai-import-model"
                  type="text"
                  [ngModel]="modelName()"
                  (ngModelChange)="onModelChange($event)"
                  [placeholder]="modelPlaceholder()"
                />
              </div>
            </div>
          </section>

          <section class="section-card">
            <h3>{{ 'aiImport.fileTitle' | translate }}</h3>
            <div
              class="dropzone"
              [class.active]="dragActive()"
              (dragover)="onDragOver($event)"
              (dragleave)="onDragLeave($event)"
              (drop)="onDrop($event)"
            >
              <input type="file" accept=".txt,text/plain" (change)="onFileChange($event)" hidden #fileInput />
              <div class="dropzone-content">
                <span class="dropzone-icon">⬆️</span>
                <div class="dropzone-text">
                  <strong>{{ 'aiImport.dropHere' | translate }}</strong>
                  <span>{{ 'aiImport.or' | translate }}</span>
                  <button type="button" class="link-btn" (click)="fileInput.click()">{{ 'aiImport.chooseFile' | translate }}</button>
                </div>
                <span class="dropzone-hint">{{ 'aiImport.formatsHint' | translate }}</span>
              </div>
            </div>

            <div *ngIf="uploading()" class="status-line">
              {{ 'aiImport.uploading' | translate }} {{ uploadProgress() }}%
            </div>
            <div *ngIf="fileInfo() && !uploading()" class="file-info">
              <span class="file-name">{{ fileInfo()?.fileName }}</span>
              <span class="file-size">{{ formatBytes(fileInfo()?.sizeBytes || 0) }}</span>
            </div>
            <p class="field-hint">{{ 'aiImport.sizeHint' | translate }}</p>
          </section>

          <section class="section-card">
            <h3>{{ 'aiImport.previewTitle' | translate }}</h3>
            <div class="preview-actions">
              <app-button
                variant="secondary"
                size="sm"
                (click)="runPreview()"
                [disabled]="!canPreview()"
              >
                {{ previewing() ? ('aiImport.previewing' | translate) : ('aiImport.previewButton' | translate) }}
              </app-button>
            </div>
            <div *ngIf="previewError()" class="error-state" role="alert">
              {{ previewError() }}
            </div>
            <div *ngIf="previewSummary()" class="preview-summary">
              <p class="summary-text">{{ previewSummary()?.summary }}</p>
              <div class="summary-meta">
                <span>{{ 'aiImport.cardsToCreate' | translate }}</span>
                <input
                  type="number"
                  class="count-input"
                  [ngModel]="estimatedCount()"
                  (ngModelChange)="onCountChange($event)"
                  [min]="1"
                  [max]="maxCards"
                />
                <span class="meta-hint">/ {{ maxCards }}</span>
              </div>
              <div class="field-hint">
                {{ 'aiImport.aiEstimate' | translate }} {{ previewSummary()?.estimatedCount }}
              </div>
              <div *ngIf="isLargeEstimate()" class="field-hint warning-hint">
                <span>{{ 'aiImport.tooLargeHint' | translate }}</span>
                <span class="hint-number">{{ previewSummary()?.estimatedCount }}</span>
                <span>{{ 'aiImport.tooLargeHintTail' | translate }}</span>
                <span class="hint-number">{{ maxCards }}</span>
                <span>{{ 'aiImport.tooLargeHintTail2' | translate }}</span>
              </div>
              <div *ngIf="previewSummary()?.truncated" class="field-hint">
                {{ 'aiImport.truncatedHint' | translate }}
              </div>
            </div>
          </section>

          <section class="section-card">
            <h3>{{ 'aiImport.fieldsTitle' | translate }}</h3>
            <p class="field-hint">{{ 'aiImport.fieldsHint' | translate }}</p>
            <div class="field-grid">
              <label
                *ngFor="let option of fieldOptions(); trackBy: trackField"
                class="field-option"
                [class.disabled]="!option.enabled"
                [class.required]="option.required"
              >
                <input
                  class="field-checkbox"
                  type="checkbox"
                  [disabled]="!option.enabled || option.required"
                  [checked]="selectedFields().has(option.key)"
                  (change)="toggleField(option)"
                />
                <span class="field-label">{{ option.label }}</span>
                <span *ngIf="option.required" class="field-required">{{ 'aiImport.requiredLabel' | translate }}</span>
              </label>
            </div>

            <div *ngIf="hasAudioFields()" class="tts-section">
              <label class="tts-toggle">{{ 'aiImport.audioTitle' | translate }}</label>
              <div *ngIf="!ttsSupported()" class="field-hint">{{ 'aiImport.audioUnavailable' | translate }}</div>
              <div *ngIf="ttsSupported() && ttsEnabled()" class="tts-panel">
                <div class="form-grid">
                  <div class="form-field">
                    <label for="ai-tts-model">{{ 'aiImport.ttsModelLabel' | translate }}</label>
                    <input
                      id="ai-tts-model"
                      type="text"
                      [ngModel]="ttsModel()"
                      (ngModelChange)="onTtsModelChange($event)"
                      [placeholder]="ttsModelPlaceholder()"
                    />
                  </div>
                  <div class="form-field">
                    <label for="ai-tts-voice">{{ 'aiImport.ttsVoiceLabel' | translate }}</label>
                    <select
                      id="ai-tts-voice"
                      [ngModel]="ttsVoicePreset()"
                      (ngModelChange)="onTtsVoicePresetChange($event)"
                    >
                      <option *ngFor="let voice of voiceOptions()" [ngValue]="voice">{{ voiceLabel(voice) }}</option>
                    </select>
                    <input
                      *ngIf="ttsVoicePreset() === 'custom'"
                      type="text"
                      [ngModel]="ttsVoiceCustom()"
                      (ngModelChange)="onTtsVoiceCustomChange($event)"
                      placeholder="custom-voice"
                    />
                  </div>
                  <div class="form-field">
                    <label for="ai-tts-format">{{ 'aiImport.ttsFormatLabel' | translate }}</label>
                    <select
                      id="ai-tts-format"
                      [ngModel]="ttsFormat()"
                      (ngModelChange)="onTtsFormatChange($event)"
                    >
                      <option *ngFor="let format of ttsFormatOptions()" [ngValue]="format">{{ format }}</option>
                    </select>
                  </div>
                  <div class="form-field">
                    <label for="ai-tts-max-chars">{{ 'aiImport.ttsMaxCharsLabel' | translate }}</label>
                    <input
                      id="ai-tts-max-chars"
                      type="number"
                      [ngModel]="ttsMaxChars()"
                      (ngModelChange)="onTtsMaxCharsChange($event)"
                      min="50"
                      max="1000"
                    />
                  </div>
                </div>
                <div class="tts-mapping">
                  <div *ngFor="let mapping of ttsMappings(); let i = index" class="mapping-row">
                    <select
                      [ngModel]="mapping.sourceField"
                      (ngModelChange)="onTtsSourceChange(i, $event)"
                    >
                      <option *ngFor="let field of textFields()" [ngValue]="field.name">
                        {{ field.label || field.name }}
                      </option>
                    </select>
                    <span class="mapping-arrow">→</span>
                    <select
                      [ngModel]="mapping.targetField"
                      (ngModelChange)="onTtsTargetChange(i, $event)"
                    >
                      <option *ngFor="let field of audioFields()" [ngValue]="field.name">
                        {{ field.label || field.name }}
                      </option>
                    </select>
                    <button type="button" class="remove-mapping" (click)="removeTtsMapping(i)" [disabled]="ttsMappings().length <= 1">
                      ×
                    </button>
                  </div>
                  <button type="button" class="add-mapping" (click)="addTtsMapping()">
                    {{ 'aiImport.addMapping' | translate }}
                  </button>
                </div>
              </div>
            </div>

            <div *ngIf="selectedImageFields().length > 0" class="tts-section">
              <label class="tts-toggle">{{ 'aiImport.imageTitle' | translate }}</label>
              <div *ngIf="!imageSupported()" class="field-hint">{{ 'aiImport.imageUnavailable' | translate }}</div>
              <div *ngIf="imageSupported()" class="tts-panel">
                <div class="form-grid">
                  <div class="form-field">
                    <label for="ai-image-model">{{ 'aiImport.imageModelLabel' | translate }}</label>
                    <select
                      id="ai-image-model"
                      [ngModel]="imageModel()"
                      (ngModelChange)="onImageModelChange($event)"
                    >
                      <option *ngFor="let model of imageModelOptions()" [ngValue]="model">
                        {{ model === 'custom' ? 'Custom' : model }}
                      </option>
                    </select>
                    <input
                      *ngIf="imageModel() === 'custom'"
                      type="text"
                      [ngModel]="imageModelCustom()"
                      (ngModelChange)="onImageModelCustomChange($event)"
                      placeholder="custom-image-model"
                    />
                  </div>
                  <div class="form-field">
                    <label for="ai-image-size">{{ 'aiImport.imageSizeLabel' | translate }}</label>
                    <input
                      id="ai-image-size"
                      type="text"
                      [ngModel]="imageSize()"
                      (ngModelChange)="onImageSizeChange($event)"
                      placeholder="1024x1024"
                    />
                  </div>
                  <div class="form-field">
                    <label for="ai-image-format">{{ 'aiImport.imageFormatLabel' | translate }}</label>
                    <select
                      id="ai-image-format"
                      [ngModel]="imageFormat()"
                      (ngModelChange)="onImageFormatChange($event)"
                    >
                      <option [ngValue]="'png'">png</option>
                      <option [ngValue]="'jpg'">jpg</option>
                      <option [ngValue]="'webp'">webp</option>
                    </select>
                  </div>
                </div>
              </div>
            </div>

            <div *ngIf="selectedVideoFields().length > 0" class="tts-section">
              <label class="tts-toggle">{{ 'aiImport.videoTitle' | translate }}</label>
              <div *ngIf="!videoSupported()" class="field-hint">{{ 'aiImport.videoUnavailable' | translate }}</div>
              <div *ngIf="videoSupported()" class="tts-panel">
                <div class="form-grid">
                  <div class="form-field">
                    <label for="ai-video-model">{{ 'aiImport.videoModelLabel' | translate }}</label>
                    <select
                      id="ai-video-model"
                      [ngModel]="videoModel()"
                      (ngModelChange)="onVideoModelChange($event)"
                    >
                      <option *ngFor="let model of videoModelOptions()" [ngValue]="model">
                        {{ model === 'custom' ? 'Custom' : model }}
                      </option>
                    </select>
                    <input
                      *ngIf="videoModel() === 'custom'"
                      type="text"
                      [ngModel]="videoModelCustom()"
                      (ngModelChange)="onVideoModelCustomChange($event)"
                      placeholder="custom-video-model"
                    />
                  </div>
                  <div class="form-field">
                    <label for="ai-video-duration">{{ 'aiImport.videoDurationLabel' | translate }}</label>
                    <input
                      id="ai-video-duration"
                      type="number"
                      [ngModel]="videoDurationSeconds()"
                      (ngModelChange)="onVideoDurationChange($event)"
                      min="2"
                      max="10"
                    />
                  </div>
                  <div class="form-field">
                    <label for="ai-video-resolution">{{ 'aiImport.videoResolutionLabel' | translate }}</label>
                    <input
                      id="ai-video-resolution"
                      type="text"
                      [ngModel]="videoResolution()"
                      (ngModelChange)="onVideoResolutionChange($event)"
                      placeholder="720p"
                    />
                  </div>
                  <div class="form-field">
                    <label for="ai-video-format">{{ 'aiImport.videoFormatLabel' | translate }}</label>
                    <select
                      id="ai-video-format"
                      [ngModel]="videoFormat()"
                      (ngModelChange)="onVideoFormatChange($event)"
                    >
                      <option [ngValue]="'mp4'">mp4</option>
                      <option [ngValue]="'gif'">gif</option>
                    </select>
                  </div>
                </div>
              </div>
            </div>
          </section>

          <section class="section-card">
            <h3>{{ 'aiImport.notesTitle' | translate }}</h3>
            <app-textarea
              [label]="'aiImport.notesLabel' | translate"
              [placeholder]="'aiImport.notesPlaceholder' | translate"
              [(ngModel)]="notes"
              [rows]="3"
            ></app-textarea>
          </section>

          <p *ngIf="createError()" class="error-state" role="alert">{{ createError() }}</p>
        </div>

        <div class="modal-footer">
          <app-button variant="ghost" (click)="close()">{{ 'aiImport.close' | translate }}</app-button>
          <app-button
            variant="primary"
            (click)="createCards()"
            [disabled]="!canCreate()"
          >
            {{ creating() ? ('aiImport.creating' | translate) : ('aiImport.createButton' | translate) }}
          </app-button>
        </div>
      </div>
    </div>
    `,
    styles: [`
      .modal-overlay {
        position: fixed;
        inset: 0;
        background: rgba(10, 15, 24, 0.55);
        display: flex;
        align-items: center;
        justify-content: center;
        padding: var(--spacing-lg);
        z-index: 40;
        backdrop-filter: blur(12px) saturate(140%);
      }

      .modal-content {
        width: min(980px, 96vw);
        max-height: 92vh;
        overflow: hidden;
        background: var(--color-surface-solid);
        border-radius: var(--border-radius-lg);
        border: 1px solid var(--glass-border);
        box-shadow: var(--shadow-lg);
        display: flex;
        flex-direction: column;
      }

      .modal-header {
        display: flex;
        align-items: flex-start;
        justify-content: space-between;
        gap: var(--spacing-lg);
        padding: var(--spacing-xl);
        border-bottom: 1px solid var(--glass-border);
      }

      .modal-header h2 { margin: 0; font-size: 1.6rem; }
      .subtitle { margin: var(--spacing-xs) 0 0; color: var(--color-text-secondary); font-size: 0.95rem; }
      .close-btn { background: none; border: none; font-size: 2rem; line-height: 1; cursor: pointer; color: var(--color-text-secondary); }

      .modal-body {
        padding: var(--spacing-xl);
        overflow-y: auto;
        display: flex;
        flex-direction: column;
        gap: var(--spacing-xl);
      }

      .section-card {
        padding: var(--spacing-lg);
        border: 1px solid var(--glass-border);
        border-radius: var(--border-radius-lg);
        background: var(--color-card-background);
        display: grid;
        gap: var(--spacing-md);
      }

      .form-grid {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
        gap: var(--spacing-md);
      }

      .form-field { display: grid; gap: var(--spacing-xs); }
      .field-hint { color: var(--color-text-secondary); font-size: 0.85rem; margin: 0; }

      .dropzone {
        border: 1px dashed var(--glass-border);
        border-radius: var(--border-radius-lg);
        padding: var(--spacing-lg);
        background: var(--color-surface);
        text-align: center;
        transition: border-color 0.2s ease, background 0.2s ease;
      }

      .dropzone.active {
        border-color: var(--color-primary);
        background: rgba(85, 108, 255, 0.12);
      }

      .dropzone-content { display: grid; gap: var(--spacing-xs); justify-items: center; }
      .dropzone-icon { font-size: 1.6rem; }
      .dropzone-text { display: grid; gap: 2px; }
      .dropzone-hint { font-size: 0.85rem; color: var(--color-text-secondary); }
      .link-btn { background: none; border: none; color: var(--color-primary); cursor: pointer; font-weight: 600; }

      .status-line { font-size: 0.9rem; color: var(--color-text-secondary); }
      .file-info { display: flex; gap: var(--spacing-md); font-size: 0.9rem; align-items: center; }
      .file-name { font-weight: 600; }
      .file-size { color: var(--color-text-secondary); }

      .preview-actions { display: flex; align-items: center; gap: var(--spacing-sm); }
      .preview-summary { display: grid; gap: var(--spacing-sm); }
      .summary-text { margin: 0; color: var(--color-text-secondary); }
      .summary-meta { display: flex; align-items: center; gap: var(--spacing-sm); }
      .count-input { width: 80px; }
      .meta-hint { color: var(--color-text-secondary); }
      .warning-hint { display: flex; flex-wrap: wrap; gap: 4px; color: var(--color-text-secondary); }
      .warning-hint .hint-number { font-weight: 600; color: var(--color-text-primary); }

      .field-grid {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
        gap: var(--spacing-sm);
      }

      .field-option {
        display: inline-flex;
        align-items: center;
        gap: var(--spacing-sm);
        padding: 0.55rem 0.75rem;
        border-radius: var(--border-radius-md);
        border: 1px solid var(--glass-border);
        background: linear-gradient(135deg, var(--glass-surface), var(--color-card-background));
        backdrop-filter: blur(calc(var(--glass-blur) * 0.5)) saturate(140%);
        box-shadow: var(--shadow-sm);
        transition: border-color 0.2s ease, transform 0.2s ease, box-shadow 0.2s ease;
        cursor: pointer;
      }

      .field-option:not(.disabled):hover {
        border-color: var(--color-primary-accent);
        box-shadow: var(--accent-shadow);
        transform: translateY(-1px);
      }

      .field-option.required {
        border-color: var(--color-primary-accent);
      }

      .field-option.disabled {
        opacity: 0.55;
        cursor: not-allowed;
        transform: none;
        box-shadow: none;
      }

      .field-checkbox {
        appearance: none;
        width: 20px;
        height: 20px;
        border-radius: 50%;
        border: 1px solid var(--glass-border-strong);
        background: var(--glass-surface-strong);
        display: grid;
        place-items: center;
        box-shadow: inset 0 1px 2px rgba(15, 23, 42, 0.12);
        cursor: pointer;
        padding: 0;
        flex: 0 0 20px;
        min-width: 20px;
        min-height: 20px;
        max-width: 20px;
        max-height: 20px;
        align-self: center;
        box-sizing: border-box;
        line-height: 1;
      }

      .field-checkbox:checked {
        border-color: var(--color-primary-accent);
        background: linear-gradient(135deg, var(--color-primary-accent), var(--color-secondary-accent));
      }

      .field-checkbox:checked::after {
        content: "";
        width: 6px;
        height: 10px;
        border-right: 2px solid rgba(255, 255, 255, 0.95);
        border-bottom: 2px solid rgba(255, 255, 255, 0.95);
        transform: translateY(-1px) rotate(45deg);
        box-sizing: border-box;
      }

      .field-checkbox:focus-visible {
        outline: none;
        box-shadow: var(--focus-ring);
      }

      .field-option:focus-within {
        border-color: var(--color-primary-accent);
      }

      .field-option.disabled .field-checkbox {
        cursor: not-allowed;
        opacity: 0.7;
      }

      .field-label {
        font-weight: 600;
        color: var(--color-text-primary);
      }

      .field-required {
        margin-left: auto;
        font-size: 0.65rem;
        letter-spacing: 0.08em;
        text-transform: uppercase;
        padding: 0.15rem 0.45rem;
        display: inline-flex;
        align-items: center;
        border-radius: 999px;
        border: 1px solid var(--glass-border-strong);
        background: var(--glass-surface);
        color: var(--color-text-secondary);
      }

      .tts-section { margin-top: var(--spacing-md); }
      .tts-toggle { display: inline-flex; align-items: center; gap: var(--spacing-sm); font-weight: 600; }
      .tts-panel { margin-top: var(--spacing-md); padding: var(--spacing-md); border-radius: var(--border-radius-md); border: 1px solid var(--border-color); background: var(--color-background); }
      .tts-mapping { margin-top: var(--spacing-md); display: grid; gap: var(--spacing-sm); }
      .mapping-row { display: grid; grid-template-columns: 1fr auto 1fr auto; align-items: center; gap: var(--spacing-sm); }
      .mapping-arrow { color: var(--color-text-secondary); }
      .remove-mapping { border: none; background: var(--color-surface); border-radius: 50%; width: 28px; height: 28px; cursor: pointer; }
      .add-mapping { justify-self: flex-start; border: 1px dashed var(--glass-border); background: var(--color-surface); padding: 6px 10px; border-radius: var(--border-radius-md); cursor: pointer; font-size: 0.85rem; }

      .error-state { color: var(--color-error); }

      .modal-footer {
        padding: var(--spacing-lg);
        border-top: 1px solid var(--glass-border);
        display: flex;
        justify-content: flex-end;
        gap: var(--spacing-sm);
      }
    `]
})
export class AiImportModalComponent implements OnInit {
    private static readonly MAX_CARDS = 500;
    @Input() userDeckId = '';
    @Input() deckName = '';
    @Input() templateId = '';
    @Input() templateVersion: number | null = null;
    @Output() jobCreated = new EventEmitter<AiJobResponse>();
    @Output() closed = new EventEmitter<void>();

    providerKeys = signal<AiProviderCredential[]>([]);
    loadingProviders = signal(false);
    selectedCredentialId = signal('');

    templateFields = signal<FieldTemplateDTO[]>([]);
    textFields = signal<FieldTemplateDTO[]>([]);
    audioFields = signal<FieldTemplateDTO[]>([]);
    fieldOptions = signal<FieldOption[]>([]);
    selectedFields = signal<Set<string>>(new Set());
    loadingTemplate = signal(false);

    fileInfo = signal<ImportFileInfo | null>(null);
    uploading = signal(false);
    uploadProgress = signal(0);
    dragActive = signal(false);
    previewing = signal(false);
    previewError = signal('');
    previewSummary = signal<AiImportPreviewSummary | null>(null);
    estimatedCount = signal(10);
    createError = signal('');
    creating = signal(false);

    encoding = signal('auto');
    modelName = signal('');
    notes = '';

    ttsEnabled = signal(false);
    ttsModel = signal('');
    ttsVoicePreset = signal('alloy');
    ttsVoiceCustom = signal('');
    ttsFormat = signal('mp3');
    ttsMaxChars = signal(300);
    ttsMappings = signal<TtsMapping[]>([]);

    imageModel = signal('gpt-image-1');
    imageModelCustom = signal('');
    imageSize = signal('1024x1024');
    imageFormat = signal('png');

    videoModel = signal('gpt-video-mini');
    videoModelCustom = signal('');
    videoDurationSeconds = signal(4);
    videoResolution = signal('720p');
    videoFormat = signal('mp4');

    readonly maxCards = AiImportModalComponent.MAX_CARDS;

    readonly encodingOptions: EncodingOption[] = [
        { label: 'Auto (detect)', value: 'auto' },
        { label: 'UTF-8', value: 'utf-8' },
        { label: 'UTF-16 LE', value: 'utf-16le' },
        { label: 'UTF-16 BE', value: 'utf-16be' },
        { label: 'Windows-1251', value: 'windows-1251' },
        { label: 'ISO-8859-1', value: 'iso-8859-1' }
    ];

    readonly formatOptions = ['mp3', 'ogg', 'wav'];
    readonly ttsFormatOptions = computed(() => this.selectedProvider() === 'gemini' ? ['wav'] : this.formatOptions);
    readonly ttsSupported = computed(() => ['openai', 'gemini'].includes(this.selectedProvider()));
    readonly imageSupported = computed(() => ['openai', 'gemini'].includes(this.selectedProvider()));
    readonly videoSupported = computed(() => this.selectedProvider() === 'openai');
    readonly hasAudioFields = computed(() => this.audioFields().length > 0);
    readonly selectedProvider = computed(() => {
        const selectedId = this.selectedCredentialId();
        if (!selectedId) return '';
        const provider = this.providerKeys().find(item => item.id === selectedId)?.provider;
        return this.normalizeProvider(provider);
    });
    readonly importSupported = computed(() => ['openai', 'gemini'].includes(this.selectedProvider()));
    readonly ttsModelPlaceholder = computed(() => this.resolveTtsModelPlaceholder(this.selectedProvider()));
    readonly modelPlaceholder = computed(() => this.resolveModelPlaceholder(this.selectedProvider()));
    readonly selectedImageFields = computed(() =>
        this.templateFields().filter(field => field.fieldType === 'image' && this.selectedFields().has(field.name))
    );
    readonly selectedVideoFields = computed(() =>
        this.templateFields().filter(field => field.fieldType === 'video' && this.selectedFields().has(field.name))
    );
    readonly imageModelOptions = computed(() => this.resolveImageModelOptions(this.selectedProvider()));
    readonly videoModelOptions = computed(() => this.resolveVideoModelOptions(this.selectedProvider()));

    private readonly openAiVoices = ['alloy', 'ash', 'coral', 'echo', 'fable', 'onyx', 'nova', 'sage', 'shimmer'];
    private readonly geminiVoices = [
        'achernar',
        'achird',
        'algenib',
        'algieba',
        'alnilam',
        'aoede',
        'autonoe',
        'callirrhoe',
        'charon',
        'despina',
        'enceladus',
        'erinome',
        'fenrir',
        'gacrux',
        'iapetus',
        'kore',
        'laomedeia',
        'leda',
        'orus',
        'puck',
        'pulcherrima',
        'rasalgethi',
        'sadachbia',
        'sadaltager',
        'schedar',
        'sulafat',
        'umbriel',
        'vindemiatrix',
        'zephyr',
        'zubenelgenubi'
    ];

    constructor(
        private aiApi: AiApiService,
        private mediaApi: MediaApiService,
        private templateApi: TemplateApiService
    ) {}

    ngOnInit(): void {
        this.loadProviders();
        this.loadTemplateFields();
    }

    loadProviders(): void {
        this.loadingProviders.set(true);
        this.aiApi.listProviders().subscribe({
            next: list => {
                const active = list.filter(p => p.status === 'active');
                this.providerKeys.set(active);
                if (!this.selectedCredentialId() && active.length > 0) {
                    this.selectedCredentialId.set(active[0].id);
                }
                this.ensureDefaultMediaModels();
                this.syncVoicePreset();
                this.loadingProviders.set(false);
            },
            error: () => {
                this.loadingProviders.set(false);
            }
        });
    }

    loadTemplateFields(): void {
        if (!this.templateId) {
            return;
        }
        this.loadingTemplate.set(true);
        this.templateApi.getTemplate(this.templateId, this.templateVersion).subscribe({
            next: template => {
                const fields = template.fields || [];
                this.templateFields.set(fields);
                this.textFields.set(fields.filter(field => this.isTextField(field.fieldType)));
                this.audioFields.set(fields.filter(field => field.fieldType === 'audio'));
                this.refreshFieldOptions();
                this.updateTtsEnabled();
                if (this.selectedFields().size === 0) {
                    const initial = this.textFields().slice(0, 2).map(field => field.name);
                    if (initial.length > 0) {
                        this.selectedFields.set(new Set(initial));
                    }
                }
                this.ensureRequiredSelection();
                this.syncTtsMappings();
                this.loadingTemplate.set(false);
            },
            error: () => {
                this.loadingTemplate.set(false);
            }
        });
    }

    onProviderChange(value: string): void {
        this.selectedCredentialId.set(value);
        this.refreshFieldOptions();
        this.ensureDefaultMediaModels();
        this.syncVoicePreset();
        this.updateTtsEnabled();
    }

    onDragOver(event: DragEvent): void {
        event.preventDefault();
        this.dragActive.set(true);
    }

    onDragLeave(event: DragEvent): void {
        event.preventDefault();
        this.dragActive.set(false);
    }

    onDrop(event: DragEvent): void {
        event.preventDefault();
        this.dragActive.set(false);
        const file = event.dataTransfer?.files?.[0];
        if (file) {
            this.uploadFile(file);
        }
    }

    onFileChange(event: Event): void {
        const target = event.target as HTMLInputElement;
        const file = target.files?.[0];
        if (file) {
            this.uploadFile(file);
        }
        if (target) {
            target.value = '';
        }
    }

    async uploadFile(file: File): Promise<void> {
        this.uploading.set(true);
        this.uploadProgress.set(0);
        this.previewError.set('');
        this.previewSummary.set(null);
        try {
            const mediaId = await this.mediaApi.uploadFile(file, 'ai_import', progress => {
                this.uploadProgress.set(progress);
            });
            this.fileInfo.set({
                mediaId,
                fileName: file.name,
                sizeBytes: file.size
            });
        } catch (err) {
            console.error('AI import upload failed', err);
            this.previewError.set('Failed to upload file');
        } finally {
            this.uploading.set(false);
        }
    }

    runPreview(): void {
        if (!this.canPreview()) {
            return;
        }
        const fileInfo = this.fileInfo();
        if (!fileInfo) return;
        this.previewing.set(true);
        this.previewError.set('');
        this.previewSummary.set(null);
        const requestId = this.generateRequestId();
        this.aiApi.createImportPreview({
            requestId,
            deckId: this.userDeckId,
            sourceMediaId: fileInfo.mediaId,
            providerCredentialId: this.selectedCredentialId() || undefined,
            model: this.modelName().trim() || undefined,
            sourceType: 'txt',
            encoding: this.encoding() === 'auto' ? undefined : this.encoding(),
            instructions: this.notes?.trim() || undefined
        }).subscribe({
            next: job => this.pollPreview(job),
            error: err => {
                this.previewing.set(false);
                this.previewError.set(err?.error?.message || 'Failed to start preview');
            }
        });
    }

    private pollPreview(job: AiJobResponse): void {
        const poll = () => {
            this.aiApi.getJob(job.jobId).subscribe({
                next: updated => {
                    if (updated.status === 'completed') {
                        this.aiApi.getJobResult(job.jobId).subscribe({
                            next: result => {
                                this.applyPreviewResult(result?.resultSummary as AiImportPreviewSummary);
                                this.previewing.set(false);
                            },
                            error: () => {
                                this.previewing.set(false);
                                this.previewError.set('Failed to load preview results');
                            }
                        });
                        return;
                    }
                    if (updated.status === 'failed' || updated.status === 'canceled') {
                        this.previewing.set(false);
                        this.previewError.set('Preview failed');
                        return;
                    }
                    setTimeout(poll, 2000);
                },
                error: () => {
                    this.previewing.set(false);
                    this.previewError.set('Preview failed');
                }
            });
        };
        setTimeout(poll, 1500);
    }

    private applyPreviewResult(summary: AiImportPreviewSummary | null): void {
        if (!summary) {
            this.previewError.set('Empty preview response');
            return;
        }
        this.previewSummary.set(summary);
        const estimate = summary.estimatedCount ?? 10;
        this.estimatedCount.set(this.clampCount(estimate));
    }

    createCards(): void {
        if (!this.canCreate()) {
            return;
        }
        const fileInfo = this.fileInfo();
        if (!fileInfo) return;
        this.creating.set(true);
        this.createError.set('');

        const fields = Array.from(this.selectedFields());
        const tts = this.buildTtsParams();
        const image = this.buildImageParams(fields);
        const video = this.buildVideoParams(fields);

        this.aiApi.createImportGenerate({
            requestId: this.generateRequestId(),
            deckId: this.userDeckId,
            sourceMediaId: fileInfo.mediaId,
            fields,
            count: this.clampCount(this.estimatedCount()),
            providerCredentialId: this.selectedCredentialId() || undefined,
            model: this.modelName().trim() || undefined,
            sourceType: 'txt',
            encoding: this.encoding() === 'auto' ? undefined : this.encoding(),
            instructions: this.notes?.trim() || undefined,
            ...(tts ? { tts } : {}),
            ...(image ? { image } : {}),
            ...(video ? { video } : {})
        }).subscribe({
            next: job => {
                this.creating.set(false);
                this.jobCreated.emit(job);
                this.close();
            },
            error: err => {
                this.creating.set(false);
                this.createError.set(err?.error?.message || 'Failed to create AI job');
            }
        });
    }

    canPreview(): boolean {
        return !!this.selectedCredentialId()
            && !!this.fileInfo()
            && this.importSupported()
            && !this.uploading()
            && !this.previewing();
    }

    canCreate(): boolean {
        return !!this.previewSummary()
            && !!this.fileInfo()
            && !!this.selectedCredentialId()
            && this.importSupported()
            && this.selectedFields().size > 0
            && !this.creating();
    }

    onCountChange(value: number): void {
        const numeric = Number(value);
        if (Number.isNaN(numeric)) return;
        this.estimatedCount.set(this.clampCount(numeric));
    }

    onModelChange(value: string): void {
        this.modelName.set(value);
    }

    toggleField(option: FieldOption): void {
        if (!option.enabled || option.required) return;
        const next = new Set(this.selectedFields());
        if (next.has(option.key)) {
            next.delete(option.key);
        } else {
            next.add(option.key);
        }
        this.selectedFields.set(next);
        this.updateTtsEnabled();
        this.syncTtsMappings();
    }

    private refreshFieldOptions(): void {
        const options = this.templateFields().map(field => this.toFieldOption(field));
        this.fieldOptions.set(options);
        const enabledKeys = new Set(options.filter(option => option.enabled).map(option => option.key));
        const requiredKeys = new Set(options.filter(option => option.required).map(option => option.key));
        const merged = new Set([...this.selectedFields(), ...requiredKeys]);
        const filtered = Array.from(merged).filter(key => enabledKeys.has(key) || requiredKeys.has(key));
        if (filtered.length > 0) {
            this.selectedFields.set(new Set(filtered));
        }
    }

    private ensureRequiredSelection(): void {
        const requiredKeys = new Set(this.fieldOptions().filter(option => option.required).map(option => option.key));
        if (requiredKeys.size === 0) {
            return;
        }
        const merged = new Set([...this.selectedFields(), ...requiredKeys]);
        this.selectedFields.set(merged);
    }

    private toFieldOption(field: FieldTemplateDTO): FieldOption {
        const label = field.label || field.name;
        return {
            key: field.name,
            label,
            enabled: this.isFieldOptionEnabled(field.fieldType),
            required: !!field.isRequired
        };
    }

    private isTextField(fieldType: string): boolean {
        return ['text', 'rich_text', 'markdown', 'cloze'].includes(fieldType);
    }

    private isPromptFieldType(fieldType: string): boolean {
        return ['text', 'rich_text', 'markdown', 'cloze', 'image', 'video', 'audio'].includes(fieldType);
    }

    private isFieldOptionEnabled(fieldType: string): boolean {
        if (!this.isPromptFieldType(fieldType)) {
            return false;
        }
        if (fieldType === 'image') {
            return this.imageSupported();
        }
        if (fieldType === 'video') {
            return this.videoSupported();
        }
        return true;
    }

    private updateTtsEnabled(): void {
        if (!this.ttsSupported()) {
            this.ttsEnabled.set(false);
            return;
        }
        const hasSelectedAudio = this.selectedFields().size > 0
            && this.audioFields().some(field => this.selectedFields().has(field.name));
        this.ttsEnabled.set(hasSelectedAudio);
    }

    private syncTtsMappings(): void {
        if (!this.ttsEnabled()) {
            return;
        }
        const validText = new Set(this.textFields().map(field => field.name));
        const validAudio = new Set(this.audioFields().map(field => field.name));
        const filteredMappings = this.ttsMappings().filter(mapping =>
            validText.has(mapping.sourceField) && validAudio.has(mapping.targetField)
        );
        this.ttsMappings.set(filteredMappings);
        if (validText.size > 0 && validAudio.size > 0 && this.ttsMappings().length === 0) {
            const defaultSource = this.resolveDefaultSourceField(this.textFields());
            const defaultTarget = this.audioFields()[0]?.name || '';
            if (defaultSource && defaultTarget) {
                this.ttsMappings.set([{ sourceField: defaultSource, targetField: defaultTarget }]);
            }
        }
    }

    private resolveDefaultSourceField(fields: FieldTemplateDTO[]): string {
        if (!fields || fields.length === 0) return '';
        const sorted = [...fields].sort((a, b) => {
            if (a.isOnFront !== b.isOnFront) {
                return a.isOnFront ? -1 : 1;
            }
            const aOrder = a.orderIndex ?? Number.MAX_SAFE_INTEGER;
            const bOrder = b.orderIndex ?? Number.MAX_SAFE_INTEGER;
            return aOrder - bOrder;
        });
        return sorted[0]?.name || '';
    }

    onTtsModelChange(value: string): void {
        this.ttsModel.set(value);
    }

    onTtsVoicePresetChange(value: string): void {
        this.ttsVoicePreset.set(value);
        if (value !== 'custom') {
            this.ttsVoiceCustom.set('');
        }
    }

    onTtsVoiceCustomChange(value: string): void {
        this.ttsVoiceCustom.set(value);
    }

    onTtsFormatChange(value: string): void {
        this.ttsFormat.set(value);
    }

    onTtsMaxCharsChange(value: number): void {
        const numeric = Number(value);
        this.ttsMaxChars.set(Number.isNaN(numeric) ? 300 : numeric);
    }

    addTtsMapping(): void {
        const source = this.resolveDefaultSourceField(this.textFields());
        const target = this.audioFields()[0]?.name || '';
        if (!source || !target) return;
        this.ttsMappings.update(list => [...list, { sourceField: source, targetField: target }]);
    }

    removeTtsMapping(index: number): void {
        this.ttsMappings.update(list => list.filter((_, i) => i !== index));
    }

    onTtsSourceChange(index: number, value: string): void {
        this.ttsMappings.update(list => list.map((item, i) => i === index ? { ...item, sourceField: value } : item));
    }

    onTtsTargetChange(index: number, value: string): void {
        this.ttsMappings.update(list => list.map((item, i) => i === index ? { ...item, targetField: value } : item));
    }

    onImageModelChange(value: string): void {
        this.imageModel.set(value);
        if (value !== 'custom') {
            this.imageModelCustom.set('');
        }
    }

    onImageModelCustomChange(value: string): void {
        this.imageModelCustom.set(value);
    }

    onImageSizeChange(value: string): void {
        this.imageSize.set(value);
    }

    onImageFormatChange(value: string): void {
        this.imageFormat.set(value);
    }

    onVideoModelChange(value: string): void {
        this.videoModel.set(value);
        if (value !== 'custom') {
            this.videoModelCustom.set('');
        }
    }

    onVideoModelCustomChange(value: string): void {
        this.videoModelCustom.set(value);
    }

    onVideoDurationChange(value: number): void {
        const numeric = Number(value);
        this.videoDurationSeconds.set(Number.isNaN(numeric) ? 4 : numeric);
    }

    onVideoResolutionChange(value: string): void {
        this.videoResolution.set(value);
    }

    onVideoFormatChange(value: string): void {
        this.videoFormat.set(value);
    }

    private buildTtsParams(): Record<string, unknown> | null {
        if (!this.hasAudioFields() || !this.ttsEnabled() || !this.ttsSupported()) {
            return null;
        }
        const mappings = this.ttsMappings()
            .filter(mapping => !!mapping.sourceField && !!mapping.targetField);
        if (mappings.length === 0) {
            return null;
        }
        return {
            enabled: true,
            model: this.ttsModel().trim() || undefined,
            voice: this.resolveVoice(),
            format: this.ttsFormat(),
            maxChars: this.ttsMaxChars(),
            mappings
        };
    }

    private buildImageParams(fields: string[]): Record<string, unknown> | null {
        if (!fields.some(field => this.selectedImageFields().some(item => item.name === field))) {
            return null;
        }
        if (!this.imageSupported()) {
            return null;
        }
        return {
            enabled: true,
            model: this.resolveImageModel(),
            size: this.imageSize().trim() || undefined,
            format: this.imageFormat()
        };
    }

    private buildVideoParams(fields: string[]): Record<string, unknown> | null {
        if (!fields.some(field => this.selectedVideoFields().some(item => item.name === field))) {
            return null;
        }
        if (!this.videoSupported()) {
            return null;
        }
        return {
            enabled: true,
            model: this.resolveVideoModel(),
            duration: this.videoDurationSeconds(),
            resolution: this.videoResolution().trim() || undefined,
            format: this.videoFormat()
        };
    }

    private resolveImageModel(): string | undefined {
        if (this.imageModel() === 'custom') {
            const custom = this.imageModelCustom().trim();
            return custom || undefined;
        }
        return this.imageModel();
    }

    private resolveVideoModel(): string | undefined {
        if (this.videoModel() === 'custom') {
            const custom = this.videoModelCustom().trim();
            return custom || undefined;
        }
        return this.videoModel();
    }

    private resolveVoice(): string {
        if (this.ttsVoicePreset() === 'custom') {
            const custom = this.ttsVoiceCustom().trim();
            return custom || this.ttsVoicePreset();
        }
        return this.ttsVoicePreset();
    }

    private ensureDefaultMediaModels(): void {
        if (!this.imageModel() || this.imageModel() === 'custom') {
            const options = this.imageModelOptions();
            this.imageModel.set(options[0] || 'gpt-image-1');
        }
        if (!this.videoModel() || this.videoModel() === 'custom') {
            const options = this.videoModelOptions();
            this.videoModel.set(options[0] || 'gpt-video-mini');
        }
        const allowedFormats = this.ttsFormatOptions();
        if (!allowedFormats.includes(this.ttsFormat())) {
            this.ttsFormat.set(allowedFormats[0]);
        }
        if (!this.ttsSupported()) {
            this.ttsEnabled.set(false);
        }
    }

    private syncVoicePreset(): void {
        const options = this.voiceOptions();
        if (!options.includes(this.ttsVoicePreset())) {
            const fallback = options[0] || 'custom';
            this.ttsVoicePreset.set(fallback);
            if (fallback !== 'custom') {
                this.ttsVoiceCustom.set('');
            }
        }
    }

    public voiceOptions(): string[] {
        if (this.selectedProvider() === 'gemini') {
            return [...this.geminiVoices, 'custom'];
        }
        if (this.selectedProvider() === 'openai') {
            return [...this.openAiVoices, 'custom'];
        }
        return ['custom'];
    }

    public voiceLabel(voice: string): string {
        if (voice === 'custom') return 'Custom';
        return voice;
    }

    private resolveImageModelOptions(provider: string): string[] {
        if (provider === 'openai') {
            return ['gpt-image-1', 'custom'];
        }
        if (provider === 'gemini') {
            return ['gemini-2.5-flash-image', 'custom'];
        }
        return ['custom'];
    }

    private resolveVideoModelOptions(provider: string): string[] {
        if (provider === 'openai') {
            return ['gpt-video-mini', 'custom'];
        }
        return ['custom'];
    }

    private resolveModelPlaceholder(provider: string): string {
        switch (provider) {
            case 'openai':
                return 'gpt-4.1-mini';
            case 'gemini':
                return 'gemini-2.0-flash';
            case 'anthropic':
                return 'claude-3-5-sonnet-20241022';
            case 'deepseek':
                return 'deepseek-chat';
            case 'gigachat':
                return 'giga-chat';
            default:
                return 'model-name';
        }
    }

    private resolveTtsModelPlaceholder(provider: string): string {
        if (provider === 'openai') {
            return 'gpt-4o-mini-tts';
        }
        if (provider === 'gemini') {
            return 'gemini-2.5-flash-preview-tts';
        }
        return 'tts-model';
    }

    private normalizeProvider(provider?: string | null): string {
        if (!provider) return '';
        const normalized = provider.trim().toLowerCase();
        if (normalized === 'claude') return 'anthropic';
        if (normalized === 'google' || normalized === 'google-gemini') return 'gemini';
        return normalized;
    }

    private clampCount(value: number): number {
        if (value < 1) return 1;
        return Math.min(value, this.maxCards);
    }

    isLargeEstimate(): boolean {
        const estimate = this.previewSummary()?.estimatedCount ?? 0;
        return estimate > this.maxCards;
    }

    formatBytes(bytes: number): string {
        if (!bytes || bytes <= 0) return '0 B';
        const units = ['B', 'KB', 'MB', 'GB'];
        let size = bytes;
        let unit = 0;
        while (size >= 1024 && unit < units.length - 1) {
            size /= 1024;
            unit += 1;
        }
        return `${size.toFixed(size >= 10 || unit === 0 ? 0 : 1)} ${units[unit]}`;
    }

    trackProvider(_: number, key: AiProviderCredential): string {
        return key.id;
    }

    trackField(_: number, option: FieldOption): string {
        return option.key;
    }

    close(): void {
        this.closed.emit();
    }

    private generateRequestId(): string {
        if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
            return crypto.randomUUID();
        }
        return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
            const r = Math.random() * 16 | 0;
            const v = c === 'x' ? r : (r & 0x3 | 0x8);
            return v.toString(16);
        });
    }
}
