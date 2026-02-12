import { Component, EventEmitter, Injector, Input, OnInit, Output, computed, effect, inject, signal } from '@angular/core';
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

type ImportSourceKind = 'text' | 'pdf' | 'docx' | 'image' | 'audio' | 'unknown';

interface ImportFileInfo {
    mediaId: string;
    fileName: string;
    sizeBytes: number;
    mimeType: string;
    sourceType: ImportSourceKind;
    durationSeconds?: number;
}

interface ImportDraft {
    fileInfo: ImportFileInfo | null;
    selectedCredentialId: string;
    encoding: string;
    modelName: string;
    notes: string;
    selectedFields: string[];
    estimatedCount: number;
    confirmLarge: boolean;
    ttsModel: string;
    ttsVoicePreset: string;
    ttsVoiceCustom: string;
    ttsFormat: string;
    ttsMaxChars: number;
    ttsMappings: TtsMapping[];
    imageModel: string;
    imageModelCustom: string;
    imageSize: string;
    imageFormat: string;
    videoModel: string;
    videoModelCustom: string;
    videoDurationSeconds: number;
    videoResolution: string;
    videoFormat: string;
    sttModel: string;
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
    <div class="modal-overlay" (click)="close(false)">
      <div class="modal-content ai-modal" (click)="$event.stopPropagation()">
        <div class="modal-header">
          <div>
            <h2>{{ 'aiImport.title' | translate }}</h2>
            <p class="subtitle">{{ 'aiImport.subtitle' | translate }}</p>
          </div>
          <button class="close-btn" (click)="close(true)">&times;</button>
        </div>

        <div class="modal-body">
          <section class="section-card">
            <h3>{{ 'aiImport.providerTitle' | translate }}</h3>
            <div class="ai-source">
              <div class="source-card active">
                <div class="source-title">{{ 'aiImport.sourceOwnTitle' | translate }}</div>
                <div class="source-meta">{{ 'aiImport.sourceOwnDesc' | translate }}</div>
              </div>
              <div class="source-card disabled" aria-disabled="true">
                <div class="source-title">{{ 'aiImport.sourceSubscriptionTitle' | translate }}</div>
                <div class="source-meta">{{ 'aiImport.sourceSubscriptionDesc' | translate }}</div>
              </div>
            </div>
            <div class="form-grid">
              <div class="form-field">
                <label for="ai-import-provider">{{ 'aiImport.providerLabel' | translate }}</label>
                <select
                  id="ai-import-provider"
                  class="glass-select"
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
              <div class="form-field" *ngIf="showEncoding()">
                <label for="ai-import-encoding">{{ 'aiImport.encodingLabel' | translate }}</label>
                <select
                  id="ai-import-encoding"
                  class="glass-select"
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
              <input type="file" accept=".txt,.pdf,.docx,.png,.jpg,.jpeg,.webp,.mp3,.wav,.ogg,.m4a,.flac,.webm,text/plain,application/pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document,image/*,audio/*" (change)="onFileChange($event)" hidden #fileInput />
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
              <span class="file-type">{{ sourceTypeLabel(fileInfo()?.sourceType) | translate }}</span>
              <span *ngIf="fileInfo()?.durationSeconds" class="file-duration">
                {{ formatDuration(fileInfo()?.durationSeconds || 0) }}
              </span>
            </div>
            <p class="field-hint">{{ 'aiImport.sizeHint' | translate }}</p>
            <p *ngIf="isAudioSource()" class="field-hint">{{ 'aiImport.audioLimitHint' | translate }}</p>

            <div class="record-panel">
              <div class="record-info">
                <strong>{{ 'aiImport.recordTitle' | translate }}</strong>
                <span class="field-hint">{{ 'aiImport.recordHint' | translate }}</span>
              </div>
              <div class="record-actions">
                <button type="button" class="record-btn" (click)="startRecording()" [disabled]="recording() || !recordingSupported()">
                  {{ 'aiImport.recordStart' | translate }}
                </button>
                <button type="button" class="record-btn stop" (click)="stopRecording()" [disabled]="!recording()">
                  {{ 'aiImport.recordStop' | translate }}
                </button>
              </div>
            </div>
            <div *ngIf="!recordingSupported()" class="field-hint">{{ 'aiImport.recordUnavailable' | translate }}</div>
            <div *ngIf="recording()" class="status-line">
              {{ 'aiImport.recording' | translate }} {{ formatDuration(recordingSeconds()) }}
            </div>
            <div *ngIf="recordingError()" class="error-state" role="alert">{{ recordingError() }}</div>

            <div *ngIf="isAudioSource()" class="stt-panel">
              <div class="form-grid">
                <div class="form-field">
                  <label for="ai-stt-model">{{ 'aiImport.sttModelLabel' | translate }}</label>
                  <input
                    id="ai-stt-model"
                    type="text"
                    [ngModel]="sttModel()"
                    (ngModelChange)="onSttModelChange($event)"
                    [placeholder]="sttModelPlaceholder()"
                  />
                </div>
              </div>
            </div>
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
              <div *ngIf="previewSummary()?.sourceType" class="field-hint">
                {{ 'aiImport.sourceTypeLabel' | translate }} {{ sourceTypeLabel(previewSummary()?.sourceType) | translate }}
              </div>
              <div *ngIf="previewSummary()?.extraction === 'ocr'" class="field-hint">
                {{ 'aiImport.ocrApplied' | translate }}
                <span *ngIf="previewSummary()?.ocrPages">({{ previewSummary()?.ocrPages }}/{{ previewSummary()?.sourcePages || previewSummary()?.ocrPages }})</span>
              </div>
              <div *ngIf="previewSummary()?.audioDurationSeconds" class="field-hint">
                {{ 'aiImport.audioDuration' | translate }} {{ formatDuration(previewSummary()?.audioDurationSeconds || 0) }}
                <span *ngIf="previewSummary()?.audioChunks">· {{ previewSummary()?.audioChunks }} {{ 'aiImport.audioChunks' | translate }}</span>
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
              <label *ngIf="requiresConfirmation()" class="confirmation-line">
                <input type="checkbox" [ngModel]="confirmLarge()" (ngModelChange)="confirmLarge.set($event)" />
                <span>{{ 'aiImport.confirmLarge' | translate }}</span>
              </label>
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
                    class="glass-select"
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
                    class="glass-select"
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
                      class="glass-select"
                      [ngModel]="mapping.sourceField"
                      (ngModelChange)="onTtsSourceChange(i, $event)"
                    >
                      <option *ngFor="let field of textFields()" [ngValue]="field.name">
                        {{ field.label || field.name }}
                      </option>
                    </select>
                    <span class="mapping-arrow">→</span>
                    <select
                      class="glass-select"
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
                    class="glass-select"
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
                    class="glass-select"
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
                    class="glass-select"
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
                    class="glass-select"
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
              [ngModel]="notes()"
              (ngModelChange)="onNotesChange($event)"
              [rows]="3"
            ></app-textarea>
          </section>

          <p *ngIf="createError()" class="error-state" role="alert">{{ createError() }}</p>
        </div>

        <div class="modal-footer">
          <app-button variant="ghost" (click)="close(true)">{{ 'aiImport.close' | translate }}</app-button>
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
        align-items: flex-start;
        justify-content: center;
        padding: calc(var(--spacing-2xl) + 1rem) var(--spacing-lg) var(--spacing-lg);
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
        scrollbar-width: thin;
        scrollbar-color: var(--glass-border-strong) transparent;
      }

      .modal-body::-webkit-scrollbar {
        width: 8px;
      }

      .modal-body::-webkit-scrollbar-track {
        background: transparent;
      }

      .modal-body::-webkit-scrollbar-thumb {
        background: var(--glass-border-strong);
        border-radius: 999px;
        border: 2px solid transparent;
        background-clip: padding-box;
      }

      .modal-body::-webkit-scrollbar-thumb:hover {
        background: var(--border-color-hover);
      }

      .section-card {
        padding: var(--spacing-lg);
        border: 1px solid var(--glass-border);
        border-radius: var(--border-radius-lg);
        background: var(--color-card-background);
        display: grid;
        gap: var(--spacing-md);
      }

      .ai-source {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
        gap: var(--spacing-md);
      }

      .source-card {
        border: 1px solid var(--glass-border);
        border-radius: var(--border-radius-lg);
        padding: var(--spacing-md);
        background: var(--glass-surface);
        box-shadow: var(--shadow-sm);
      }

      .source-card.disabled {
        opacity: 0.6;
        pointer-events: none;
      }

      .source-title {
        font-weight: 600;
        margin-bottom: var(--spacing-xs);
      }

      .source-meta {
        font-size: 0.9rem;
        color: var(--color-text-secondary);
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
      .file-type,
      .file-duration {
        color: var(--color-text-secondary);
        font-size: 0.85rem;
        text-transform: uppercase;
        letter-spacing: 0.04em;
      }

      .record-panel {
        margin-top: var(--spacing-md);
        padding: var(--spacing-md);
        border-radius: var(--border-radius-md);
        border: 1px solid var(--glass-border);
        background: var(--color-surface);
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: var(--spacing-md);
        flex-wrap: wrap;
      }

      .record-info {
        display: grid;
        gap: 2px;
      }

      .record-actions {
        display: flex;
        gap: var(--spacing-sm);
      }

      .record-btn {
        border: 1px solid var(--glass-border);
        background: var(--glass-surface);
        padding: 6px 12px;
        border-radius: 999px;
        cursor: pointer;
        font-weight: 600;
        color: var(--color-text-primary);
      }

      .record-btn.stop {
        border-color: var(--color-error);
        color: var(--color-error);
      }

      .stt-panel {
        margin-top: var(--spacing-md);
        padding: var(--spacing-md);
        border-radius: var(--border-radius-md);
        border: 1px solid var(--border-color);
        background: var(--color-background);
      }

      .preview-actions { display: flex; align-items: center; gap: var(--spacing-sm); }
      .preview-summary { display: grid; gap: var(--spacing-sm); }
      .summary-text { margin: 0; color: var(--color-text-secondary); }
      .summary-meta { display: flex; align-items: center; gap: var(--spacing-sm); }
      .count-input { width: 80px; }
      .meta-hint { color: var(--color-text-secondary); }
      .warning-hint { display: flex; flex-wrap: wrap; gap: 4px; color: var(--color-text-secondary); }
      .warning-hint .hint-number { font-weight: 600; color: var(--color-text-primary); }
      .confirmation-line {
        display: inline-flex;
        align-items: center;
        gap: var(--spacing-sm);
        font-size: 0.9rem;
        color: var(--color-text-secondary);
        margin-top: var(--spacing-xs);
      }

      .field-grid {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
        gap: var(--spacing-sm);
      }

      .field-option {
        display: grid;
        grid-template-columns: auto minmax(0, 1fr) auto;
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
        width: 100%;
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
        min-width: 0;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }

      .field-required {
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
    private static readonly MAX_AUDIO_SECONDS = 300;
    private static readonly CONFIRM_AUDIO_SECONDS = 120;
    private static readonly CONFIRM_OCR_PAGES = 5;
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
    confirmLarge = signal(false);
    estimatedCount = signal(10);
    createError = signal('');
    creating = signal(false);
    private readonly draftReady = signal(false);
    private readonly injector = inject(Injector);
    private storageKey = '';

    encoding = signal('auto');
    modelName = signal('');
    notes = signal('');

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

    sttModel = signal('');
    recording = signal(false);
    recordingSeconds = signal(0);
    recordingError = signal('');

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
    readonly ttsFormatOptions = computed(() => ['gemini', 'qwen'].includes(this.selectedProvider()) ? ['wav'] : this.formatOptions);
    readonly ttsSupported = computed(() => ['openai', 'gemini', 'qwen'].includes(this.selectedProvider()));
    readonly imageSupported = computed(() => ['openai', 'gemini', 'qwen'].includes(this.selectedProvider()));
    readonly videoSupported = computed(() => ['openai', 'qwen'].includes(this.selectedProvider()));
    readonly hasAudioFields = computed(() => this.audioFields().length > 0);
    readonly selectedProvider = computed(() => {
        const selectedId = this.selectedCredentialId();
        if (!selectedId) return '';
        const provider = this.providerKeys().find(item => item.id === selectedId)?.provider;
        return this.normalizeProvider(provider);
    });
    readonly importSupported = computed(() => ['openai', 'gemini', 'qwen'].includes(this.selectedProvider()));
    readonly ttsModelPlaceholder = computed(() => this.resolveTtsModelPlaceholder(this.selectedProvider()));
    readonly sttModelPlaceholder = computed(() => this.resolveSttModelPlaceholder(this.selectedProvider()));
    readonly modelPlaceholder = computed(() => this.resolveModelPlaceholder(this.selectedProvider()));
    readonly selectedSourceType = computed(() => this.fileInfo()?.sourceType || 'unknown');
    readonly showEncoding = computed(() => {
        const sourceType = this.selectedSourceType();
        return sourceType === 'text' || sourceType === 'pdf' || sourceType === 'unknown';
    });
    readonly isAudioSource = computed(() => this.selectedSourceType() === 'audio');
    readonly isImageSource = computed(() => this.selectedSourceType() === 'image');
    readonly requiresConfirmation = computed(() => {
        const summary = this.previewSummary();
        if (!summary) return false;
        if (summary.audioDurationSeconds && summary.audioDurationSeconds >= AiImportModalComponent.CONFIRM_AUDIO_SECONDS) {
            return true;
        }
        if (summary.ocrPages && summary.ocrPages >= AiImportModalComponent.CONFIRM_OCR_PAGES) {
            return true;
        }
        if (summary.sourcePages && summary.sourcePages >= AiImportModalComponent.CONFIRM_OCR_PAGES) {
            return true;
        }
        return false;
    });
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
    private readonly qwenVoices = [
        'Cherry',
        'Serena',
        'Ethan',
        'Chelsie',
        'Momo',
        'Vivian',
        'Moon',
        'Maia',
        'Kai',
        'Nofish',
        'Bella',
        'Jennifer',
        'Ryan',
        'Katerina',
        'Aiden',
        'Eldric Sage',
        'Mia',
        'Mochi',
        'Bellona',
        'Vincent',
        'Bunny',
        'Neil',
        'Elias',
        'Arthur',
        'Nini',
        'Ebona',
        'Seren',
        'Pip',
        'Stella',
        'Bodega',
        'Sonrisa',
        'Alek',
        'Dolce',
        'Sohee',
        'Ono Anna',
        'Lenn',
        'Emilien',
        'Andre',
        'Radio Gol',
        'Jada',
        'Dylan',
        'Li',
        'Marcus',
        'Roy',
        'Peter',
        'Sunny',
        'Eric',
        'Rocky',
        'Kiki'
    ];
    private mediaRecorder: MediaRecorder | null = null;
    private recordingChunks: BlobPart[] = [];
    private recordingTimer: number | null = null;

    constructor(
        private aiApi: AiApiService,
        private mediaApi: MediaApiService,
        private templateApi: TemplateApiService
    ) {}

    ngOnInit(): void {
        this.storageKey = `mnema_ai_import:${this.userDeckId || 'default'}`;
        this.startDraftSync();
        this.restoreDraft();
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
                } else if (this.selectedCredentialId() && !active.some(key => key.id === this.selectedCredentialId())) {
                    this.selectedCredentialId.set(active[0]?.id || '');
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
        this.confirmLarge.set(false);
        this.recordingError.set('');
        try {
            const sourceType = this.resolveSourceType(file);
            if (sourceType === 'unknown') {
                this.previewError.set('Unsupported file type');
                return;
            }
            if (sourceType !== 'audio') {
                this.sttModel.set('');
            }
            let durationSeconds: number | undefined;
            if (sourceType === 'audio') {
                durationSeconds = await this.resolveAudioDuration(file);
                if (durationSeconds && durationSeconds > AiImportModalComponent.MAX_AUDIO_SECONDS) {
                    this.previewError.set('Audio is too long. Please upload a shorter file.');
                    return;
                }
            }
            const mediaId = await this.mediaApi.uploadFile(file, 'ai_import', progress => {
                this.uploadProgress.set(progress);
            });
            this.fileInfo.set({
                mediaId,
                fileName: file.name,
                sizeBytes: file.size,
                mimeType: file.type || '',
                sourceType,
                durationSeconds
            });
        } catch (err) {
            console.error('AI import upload failed', err);
            this.previewError.set('Failed to upload file');
        } finally {
            this.uploading.set(false);
        }
    }

    recordingSupported(): boolean {
        return typeof MediaRecorder !== 'undefined'
            && !!navigator?.mediaDevices?.getUserMedia;
    }

    async startRecording(): Promise<void> {
        if (this.recording()) {
            return;
        }
        if (!this.recordingSupported()) {
            this.recordingError.set('Audio recording is not supported');
            return;
        }
        this.recordingError.set('');
        this.recordingSeconds.set(0);
        this.recordingChunks = [];
        try {
            const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
            const mimeType = this.resolveRecordingMimeType();
            const recorder = new MediaRecorder(stream, mimeType ? { mimeType } : undefined);
            this.mediaRecorder = recorder;
            recorder.ondataavailable = event => {
                if (event.data && event.data.size > 0) {
                    this.recordingChunks.push(event.data);
                }
            };
            recorder.onstop = () => {
                stream.getTracks().forEach(track => track.stop());
                this.handleRecordingStop(mimeType || recorder.mimeType);
            };
            recorder.start();
            this.recording.set(true);
            this.startRecordingTimer();
        } catch (err) {
            console.error('Audio recording failed', err);
            this.recordingError.set('Failed to start recording');
            this.recording.set(false);
        }
    }

    stopRecording(): void {
        if (!this.recording() || !this.mediaRecorder) {
            return;
        }
        this.mediaRecorder.stop();
        this.recording.set(false);
        this.stopRecordingTimer();
    }

    private handleRecordingStop(mimeType: string): void {
        this.stopRecordingTimer();
        this.recording.set(false);
        if (!this.recordingChunks.length) {
            this.recordingError.set('Recording is empty');
            this.mediaRecorder = null;
            return;
        }
        const blobType = mimeType || 'audio/webm';
        const blob = new Blob(this.recordingChunks, { type: blobType });
        this.mediaRecorder = null;
        this.recordingChunks = [];
        if (!blob.size) {
            this.recordingError.set('Recording is empty');
            return;
        }
        const extension = blobType.includes('ogg') ? 'ogg' : 'webm';
        const file = new File([blob], `mnema-recording-${Date.now()}.${extension}`, { type: blobType });
        this.uploadFile(file).catch(() => {});
    }

    private startRecordingTimer(): void {
        this.stopRecordingTimer();
        this.recordingTimer = window.setInterval(() => {
            const next = this.recordingSeconds() + 1;
            this.recordingSeconds.set(next);
            if (next >= AiImportModalComponent.MAX_AUDIO_SECONDS) {
                this.stopRecording();
            }
        }, 1000);
    }

    private stopRecordingTimer(): void {
        if (this.recordingTimer !== null) {
            clearInterval(this.recordingTimer);
            this.recordingTimer = null;
        }
    }

    private resolveRecordingMimeType(): string | null {
        const candidates = [
            'audio/webm;codecs=opus',
            'audio/ogg;codecs=opus',
            'audio/webm'
        ];
        return candidates.find(type => MediaRecorder.isTypeSupported(type)) || null;
    }

    private async resolveAudioDuration(file: File): Promise<number | undefined> {
        return new Promise(resolve => {
            const audio = new Audio();
            const url = URL.createObjectURL(file);
            audio.preload = 'metadata';
            audio.src = url;
            audio.onloadedmetadata = () => {
                const duration = Number.isFinite(audio.duration) ? Math.ceil(audio.duration) : undefined;
                URL.revokeObjectURL(url);
                resolve(duration);
            };
            audio.onerror = () => {
                URL.revokeObjectURL(url);
                resolve(undefined);
            };
        });
    }

    private resolveSourceType(file: File): ImportSourceKind {
        const mimeType = (file.type || '').toLowerCase();
        if (mimeType.startsWith('image/')) return 'image';
        if (mimeType.startsWith('audio/')) return 'audio';
        if (mimeType === 'application/pdf') return 'pdf';
        if (mimeType === 'application/vnd.openxmlformats-officedocument.wordprocessingml.document') return 'docx';
        if (mimeType.startsWith('text/')) return 'text';

        const name = file.name.toLowerCase();
        if (name.endsWith('.pdf')) return 'pdf';
        if (name.endsWith('.docx')) return 'docx';
        if (name.endsWith('.txt')) return 'text';
        if (name.endsWith('.png') || name.endsWith('.jpg') || name.endsWith('.jpeg') || name.endsWith('.webp')) return 'image';
        if (name.endsWith('.mp3') || name.endsWith('.wav') || name.endsWith('.ogg') || name.endsWith('.m4a') || name.endsWith('.flac') || name.endsWith('.webm')) return 'audio';
        return 'unknown';
    }

    runPreview(): void {
        if (!this.canPreview()) {
            return;
        }
        const fileInfo = this.fileInfo();
        if (!fileInfo) return;
        const stt = this.buildSttParams();
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
            sourceType: fileInfo.sourceType,
            encoding: this.showEncoding() && this.encoding() !== 'auto' ? this.encoding() : undefined,
            instructions: this.notes().trim() || undefined,
            ...(stt ? { stt } : {})
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
        this.confirmLarge.set(false);
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
        const stt = this.buildSttParams();

        this.aiApi.createImportGenerate({
            requestId: this.generateRequestId(),
            deckId: this.userDeckId,
            sourceMediaId: fileInfo.mediaId,
            fields,
            count: this.clampCount(this.estimatedCount()),
            providerCredentialId: this.selectedCredentialId() || undefined,
            model: this.modelName().trim() || undefined,
            sourceType: fileInfo.sourceType,
            encoding: this.showEncoding() && this.encoding() !== 'auto' ? this.encoding() : undefined,
            instructions: this.notes().trim() || undefined,
            ...(stt ? { stt } : {}),
            ...(tts ? { tts } : {}),
            ...(image ? { image } : {}),
            ...(video ? { video } : {})
        }).subscribe({
            next: job => {
                this.creating.set(false);
                this.jobCreated.emit(job);
                this.close(true);
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
            && (!this.requiresConfirmation() || this.confirmLarge())
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

    onNotesChange(value: string): void {
        this.notes.set(value);
    }

    onSttModelChange(value: string): void {
        this.sttModel.set(value);
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

    private buildSttParams(): Record<string, unknown> | null {
        if (!this.isAudioSource()) {
            return null;
        }
        const model = this.sttModel().trim();
        if (!model) {
            return null;
        }
        return {
            model
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
            const fallback = custom || this.ttsVoicePreset();
            return this.selectedProvider() === 'gemini' ? fallback.toLowerCase() : fallback;
        }
        const voice = this.ttsVoicePreset();
        return this.selectedProvider() === 'gemini' ? voice.toLowerCase() : voice;
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
        if (this.selectedProvider() === 'qwen') {
            return [...this.qwenVoices, 'custom'];
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
        if (provider === 'qwen') {
            return ['qwen-image-plus', 'qwen-image', 'qwen-image-max', 'custom'];
        }
        return ['custom'];
    }

    private resolveVideoModelOptions(provider: string): string[] {
        if (provider === 'openai') {
            return ['gpt-video-mini', 'custom'];
        }
        if (provider === 'qwen') {
            return ['wan2.2-t2v-plus', 'wan2.5-t2v-preview', 'wan2.6-t2v', 'custom'];
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
            case 'qwen':
                return 'qwen2.5-3b-instruct';
            case 'grok':
                return 'grok-4-fast-non-reasoning';
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
        if (provider === 'qwen') {
            return 'qwen3-tts-flash';
        }
        return 'tts-model';
    }

    private resolveSttModelPlaceholder(provider: string): string {
        if (provider === 'openai') {
            return 'gpt-4o-mini-transcribe';
        }
        if (provider === 'gemini') {
            return 'gemini-2.0-flash';
        }
        if (provider === 'qwen') {
            return 'qwen3-asr-flash';
        }
        return 'stt-model';
    }

    private normalizeProvider(provider?: string | null): string {
        if (!provider) return '';
        const normalized = provider.trim().toLowerCase();
        if (normalized === 'claude') return 'anthropic';
        if (normalized === 'google' || normalized === 'google-gemini') return 'gemini';
        if (normalized === 'xai' || normalized === 'x.ai') return 'grok';
        if (normalized === 'dashscope' || normalized === 'aliyun' || normalized === 'alibaba') return 'qwen';
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

    formatDuration(totalSeconds: number): string {
        if (!totalSeconds || totalSeconds <= 0) return '0:00';
        const minutes = Math.floor(totalSeconds / 60);
        const seconds = Math.floor(totalSeconds % 60);
        return `${minutes}:${seconds.toString().padStart(2, '0')}`;
    }

    sourceTypeLabel(sourceType?: ImportSourceKind | string | null): string {
        switch (sourceType) {
            case 'text':
                return 'aiImport.sourceType.text';
            case 'pdf':
                return 'aiImport.sourceType.pdf';
            case 'docx':
                return 'aiImport.sourceType.docx';
            case 'image':
                return 'aiImport.sourceType.image';
            case 'audio':
                return 'aiImport.sourceType.audio';
            default:
                return 'aiImport.sourceType.unknown';
        }
    }

    private startDraftSync(): void {
        effect(() => {
            if (!this.draftReady()) {
                return;
            }
            const draft = this.buildDraftSnapshot();
            this.persistDraft(draft);
        }, { injector: this.injector });
    }

    private buildDraftSnapshot(): ImportDraft {
        return {
            fileInfo: this.fileInfo(),
            selectedCredentialId: this.selectedCredentialId(),
            encoding: this.encoding(),
            modelName: this.modelName(),
            notes: this.notes(),
            selectedFields: Array.from(this.selectedFields()),
            estimatedCount: this.estimatedCount(),
            confirmLarge: this.confirmLarge(),
            ttsModel: this.ttsModel(),
            ttsVoicePreset: this.ttsVoicePreset(),
            ttsVoiceCustom: this.ttsVoiceCustom(),
            ttsFormat: this.ttsFormat(),
            ttsMaxChars: this.ttsMaxChars(),
            ttsMappings: this.ttsMappings(),
            imageModel: this.imageModel(),
            imageModelCustom: this.imageModelCustom(),
            imageSize: this.imageSize(),
            imageFormat: this.imageFormat(),
            videoModel: this.videoModel(),
            videoModelCustom: this.videoModelCustom(),
            videoDurationSeconds: this.videoDurationSeconds(),
            videoResolution: this.videoResolution(),
            videoFormat: this.videoFormat(),
            sttModel: this.sttModel()
        };
    }

    private persistDraft(draft: ImportDraft): void {
        if (!this.storageKey) {
            return;
        }
        try {
            localStorage.setItem(this.storageKey, JSON.stringify(draft));
        } catch {
        }
    }

    private restoreDraft(): void {
        if (!this.storageKey) {
            this.draftReady.set(true);
            return;
        }
        try {
            const raw = localStorage.getItem(this.storageKey);
            if (!raw) {
                this.draftReady.set(true);
                return;
            }
            const draft = JSON.parse(raw) as ImportDraft;
            if (draft.fileInfo) {
                this.fileInfo.set(draft.fileInfo);
            }
            if (draft.selectedCredentialId) {
                this.selectedCredentialId.set(draft.selectedCredentialId);
            }
            if (draft.encoding) {
                this.encoding.set(draft.encoding);
            }
            if (draft.modelName) {
                this.modelName.set(draft.modelName);
            }
            if (draft.notes) {
                this.notes.set(draft.notes);
            }
            if (draft.selectedFields?.length) {
                this.selectedFields.set(new Set(draft.selectedFields));
            }
            if (Number.isFinite(draft.estimatedCount)) {
                this.estimatedCount.set(this.clampCount(Number(draft.estimatedCount)));
            }
            if (typeof draft.confirmLarge === 'boolean') {
                this.confirmLarge.set(draft.confirmLarge);
            }
            if (draft.ttsModel) {
                this.ttsModel.set(draft.ttsModel);
            }
            if (draft.ttsVoicePreset) {
                this.ttsVoicePreset.set(draft.ttsVoicePreset);
            }
            if (draft.ttsVoiceCustom) {
                this.ttsVoiceCustom.set(draft.ttsVoiceCustom);
            }
            if (draft.ttsFormat) {
                this.ttsFormat.set(draft.ttsFormat);
            }
            if (Number.isFinite(draft.ttsMaxChars)) {
                this.ttsMaxChars.set(Number(draft.ttsMaxChars));
            }
            if (draft.ttsMappings?.length) {
                this.ttsMappings.set(draft.ttsMappings.filter(mapping => !!mapping.sourceField && !!mapping.targetField));
            }
            if (draft.imageModel) {
                this.imageModel.set(draft.imageModel);
            }
            if (draft.imageModelCustom) {
                this.imageModelCustom.set(draft.imageModelCustom);
            }
            if (draft.imageSize) {
                this.imageSize.set(draft.imageSize);
            }
            if (draft.imageFormat) {
                this.imageFormat.set(draft.imageFormat);
            }
            if (draft.videoModel) {
                this.videoModel.set(draft.videoModel);
            }
            if (draft.videoModelCustom) {
                this.videoModelCustom.set(draft.videoModelCustom);
            }
            if (Number.isFinite(draft.videoDurationSeconds)) {
                this.videoDurationSeconds.set(Number(draft.videoDurationSeconds));
            }
            if (draft.videoResolution) {
                this.videoResolution.set(draft.videoResolution);
            }
            if (draft.videoFormat) {
                this.videoFormat.set(draft.videoFormat);
            }
            if (draft.sttModel) {
                this.sttModel.set(draft.sttModel);
            }
        } catch {
        } finally {
            this.draftReady.set(true);
        }
    }

    private clearDraft(): void {
        if (!this.storageKey) {
            return;
        }
        try {
            localStorage.removeItem(this.storageKey);
        } catch {
        }
    }

    trackProvider(_: number, key: AiProviderCredential): string {
        return key.id;
    }

    trackField(_: number, option: FieldOption): string {
        return option.key;
    }

    close(clearDraft: boolean = true): void {
        if (this.recording()) {
            this.stopRecording();
        }
        if (clearDraft) {
            this.clearDraft();
        }
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
