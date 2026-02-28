import { Component, EventEmitter, Input, OnInit, Output, computed, signal } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { AiApiService } from '../../core/services/ai-api.service';
import { TemplateApiService } from '../../core/services/template-api.service';
import { AiJobResponse, AiProviderCredential, AiRuntimeCapabilities } from '../../core/models/ai.models';
import { FieldTemplateDTO } from '../../core/models/template.models';
import { ButtonComponent } from '../../shared/components/button.component';
import { I18nService } from '../../core/services/i18n.service';
import { TranslatePipe } from '../../shared/pipes/translate.pipe';

type FieldOption = { key: string; label: string; enabled: boolean };
type TtsMapping = { sourceField: string; targetField: string };

@Component({
    selector: 'app-ai-add-cards-modal',
    standalone: true,
    imports: [NgFor, NgIf, FormsModule, ButtonComponent, TranslatePipe],
    template: `
    <div class="modal-overlay" (click)="close()">
      <div class="modal-content ai-modal" (click)="$event.stopPropagation()">
        <div class="modal-header">
          <h2>{{ 'aiAdd.title' | translate }}</h2>
          <button class="close-btn" (click)="close()">&times;</button>
        </div>

        <div class="modal-body">
          <p class="modal-hint">
            {{ 'aiAdd.hint' | translate }}
          </p>

          <div class="ai-source">
            <div class="source-card active">
              <div class="source-title">{{ 'aiAdd.sourceOwnTitle' | translate }}</div>
              <div class="source-meta">{{ 'aiAdd.sourceOwnDesc' | translate }}</div>
            </div>
            <div class="source-card disabled" aria-disabled="true">
              <div class="source-title">{{ 'aiAdd.sourceSubscriptionTitle' | translate }}</div>
              <div class="source-meta">{{ 'aiAdd.sourceSubscriptionDesc' | translate }}</div>
            </div>
          </div>

          <div class="form-grid compact-head-grid">
            <div class="form-field">
              <label for="ai-provider-key">{{ 'aiAdd.providerLabel' | translate }}</label>
              <select
                id="ai-provider-key"
                class="glass-select"
                [ngModel]="selectedCredentialId()"
                (ngModelChange)="onProviderChange($event)"
                [disabled]="loadingProviders() || providerKeys().length === 0"
              >
                <option [ngValue]="''">{{ 'aiAdd.selectKey' | translate }}</option>
                <option *ngFor="let key of providerKeys(); trackBy: trackProvider" [ngValue]="key.id">
                  {{ key.provider }}{{ key.alias ? ' · ' + key.alias : '' }}
                </option>
              </select>
              <p *ngIf="!loadingProviders() && providerKeys().length === 0" class="field-hint">
                {{ 'aiAdd.noKeys' | translate }}
              </p>
            </div>

            <div class="form-field">
              <label for="ai-card-count">{{ 'aiAdd.cardCountLabel' | translate }}</label>
              <input
                id="ai-card-count"
                type="number"
                min="1"
                max="50"
                [ngModel]="cardsCount()"
                (ngModelChange)="onCountChange($event)"
              />
            </div>

            <div class="form-field">
              <label for="ai-model">{{ 'aiAdd.modelLabel' | translate }}</label>
              <input
                id="ai-model"
                type="text"
                [ngModel]="modelName()"
                (ngModelChange)="onModelChange($event)"
                [placeholder]="modelPlaceholder()"
              />
            </div>
          </div>

          <div class="field-options">
            <label>{{ 'aiAdd.fieldsLabel' | translate }}</label>
            <div class="field-grid">
              <label *ngFor="let field of fieldOptions(); trackBy: trackField" class="field-option" [class.disabled]="!field.enabled">
                <input
                  class="field-checkbox"
                  type="checkbox"
                  [checked]="selectedFields().has(field.key)"
                  (change)="toggleField(field)"
                  [disabled]="!field.enabled"
                />
                <span class="field-label">{{ field.label }}</span>
              </label>
            </div>
          </div>

          <div *ngIf="hasAudioFields()" class="tts-section">
            <div *ngIf="!ttsSupported()" class="field-hint">{{ 'aiAdd.ttsUnavailable' | translate }}</div>

            <div *ngIf="ttsEnabled()" class="tts-panel">
              <div class="form-grid">
                <div class="form-field">
                  <label for="ai-tts-model">{{ 'aiAdd.ttsModelLabel' | translate }}</label>
                  <input
                    id="ai-tts-model"
                    type="text"
                    [ngModel]="ttsModel()"
                    (ngModelChange)="onTtsModelChange($event)"
                    [placeholder]="ttsModelPlaceholder()"
                  />
                </div>
                <div class="form-field">
                  <label for="ai-tts-voice">{{ 'aiAdd.voiceLabel' | translate }}</label>
                  <select
                    id="ai-tts-voice"
                    class="glass-select"
                    [ngModel]="ttsVoicePreset()"
                    (ngModelChange)="onTtsVoicePresetChange($event)"
                  >
                    <option *ngFor="let voice of voiceOptions()" [ngValue]="voice">
                      {{ voiceLabel(voice) }}
                    </option>
                  </select>
                  <input
                    *ngIf="ttsVoicePreset() === 'custom'"
                    type="text"
                    [ngModel]="ttsVoiceCustom()"
                    (ngModelChange)="onTtsVoiceCustomChange($event)"
                    [placeholder]="'aiAdd.customVoicePlaceholder' | translate"
                  />
                </div>
                <div class="form-field">
                  <label for="ai-tts-format">{{ 'aiAdd.formatLabel' | translate }}</label>
                  <select
                    id="ai-tts-format"
                    class="glass-select"
                    [ngModel]="ttsFormat()"
                    (ngModelChange)="onTtsFormatChange($event)"
                  >
                    <option *ngFor="let format of ttsFormatOptions()" [ngValue]="format">
                      {{ format }}
                    </option>
                  </select>
                </div>
                <div class="form-field">
                  <label for="ai-tts-max-chars">{{ 'aiAdd.maxCharsLabel' | translate }}</label>
                  <input
                    id="ai-tts-max-chars"
                    type="number"
                    min="1"
                    max="1000"
                    [ngModel]="ttsMaxChars()"
                    (ngModelChange)="onTtsMaxCharsChange($event)"
                  />
                </div>
              </div>

              <div class="tts-mapping">
                <label>{{ 'aiAdd.audioMappingLabel' | translate }}</label>
                <div class="mapping-list">
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
                </div>
                <button type="button" class="add-mapping" (click)="addTtsMapping()">{{ 'aiAdd.addMapping' | translate }}</button>
              </div>
            </div>
          </div>

          <div *ngIf="selectedImageFields().length > 0" class="tts-section">
            <label class="tts-toggle">{{ 'aiAdd.imageTitle' | translate }}</label>
            <div *ngIf="!imageSupported()" class="field-hint">{{ 'aiAdd.imageUnavailable' | translate }}</div>
            <div *ngIf="imageSupported()" class="tts-panel">
              <div class="form-grid">
                <div class="form-field">
                  <label for="ai-image-model">{{ 'aiAdd.imageModelLabel' | translate }}</label>
                  <select
                    id="ai-image-model"
                    class="glass-select"
                    [ngModel]="imageModel()"
                    (ngModelChange)="onImageModelChange($event)"
                  >
                    <option *ngFor="let model of imageModelOptions()" [ngValue]="model">
                      {{ model === 'custom' ? ('aiAdd.customOption' | translate) : model }}
                    </option>
                  </select>
                  <input
                    *ngIf="imageModel() === 'custom'"
                    type="text"
                    [ngModel]="imageModelCustom()"
                    (ngModelChange)="onImageModelCustomChange($event)"
                    [placeholder]="'aiAdd.customImageModelPlaceholder' | translate"
                  />
                </div>
                <div class="form-field">
                  <label for="ai-image-size">{{ 'aiAdd.imageSizeLabel' | translate }}</label>
                  <input
                    id="ai-image-size"
                    type="text"
                    [ngModel]="imageSize()"
                    (ngModelChange)="onImageSizeChange($event)"
                    placeholder="1024x1024"
                  />
                </div>
                <div class="form-field">
                  <label for="ai-image-format">{{ 'aiAdd.imageFormatLabel' | translate }}</label>
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
            <label class="tts-toggle">{{ 'aiAdd.videoTitle' | translate }}</label>
            <div *ngIf="!videoSupported()" class="field-hint">{{ 'aiAdd.videoUnavailable' | translate }}</div>
            <div *ngIf="videoSupported()" class="tts-panel">
              <div class="form-grid">
                <div class="form-field">
                  <label for="ai-video-model">{{ 'aiAdd.videoModelLabel' | translate }}</label>
                  <select
                    id="ai-video-model"
                    class="glass-select"
                    [ngModel]="videoModel()"
                    (ngModelChange)="onVideoModelChange($event)"
                  >
                    <option *ngFor="let model of videoModelOptions()" [ngValue]="model">
                      {{ model === 'custom' ? ('aiAdd.customOption' | translate) : model }}
                    </option>
                  </select>
                  <input
                    *ngIf="videoModel() === 'custom'"
                    type="text"
                    [ngModel]="videoModelCustom()"
                    (ngModelChange)="onVideoModelCustomChange($event)"
                    [placeholder]="'aiAdd.customVideoModelPlaceholder' | translate"
                  />
                </div>
                <div class="form-field">
                  <label for="ai-video-duration">{{ 'aiAdd.videoDurationLabel' | translate }}</label>
                  <input
                    id="ai-video-duration"
                    type="number"
                    min="1"
                    max="20"
                    [ngModel]="videoDurationSeconds()"
                    (ngModelChange)="onVideoDurationChange($event)"
                  />
                </div>
                <div class="form-field">
                  <label for="ai-video-resolution">{{ 'aiAdd.videoResolutionLabel' | translate }}</label>
                  <input
                    id="ai-video-resolution"
                    type="text"
                    [ngModel]="videoResolution()"
                    (ngModelChange)="onVideoResolutionChange($event)"
                    placeholder="1280x720"
                  />
                </div>
                <div class="form-field">
                  <label for="ai-video-format">{{ 'aiAdd.videoFormatLabel' | translate }}</label>
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

          <div class="form-field">
            <label for="ai-prompt">{{ 'aiAdd.instructionsLabel' | translate }}</label>
            <textarea
              id="ai-prompt"
              rows="4"
              [ngModel]="prompt()"
              (ngModelChange)="onPromptChange($event)"
              [placeholder]="'aiAdd.instructionsPlaceholder' | translate"
            ></textarea>
          </div>

          <div *ngIf="createError()" class="error-state" role="alert">
            {{ createError() }}
          </div>
          <div *ngIf="createSuccess()" class="success-state" role="status">
            {{ createSuccess() }}
          </div>
        </div>

        <div class="modal-footer">
          <app-button variant="ghost" (click)="close()">{{ 'aiAdd.cancel' | translate }}</app-button>
          <app-button
            variant="primary"
            (click)="submit()"
            [disabled]="!canSubmit()"
          >
            {{ creating() ? ('aiAdd.queueing' | translate) : ('aiAdd.generate' | translate) }}
          </app-button>
        </div>
      </div>
    </div>
  `,
    styles: [`
      .modal-overlay { position: fixed; top: 0; left: 0; right: 0; bottom: 0; background: rgba(8, 12, 22, 0.55); display: flex; align-items: center; justify-content: center; z-index: 1000; backdrop-filter: blur(12px) saturate(140%); }
      .modal-content { background: var(--color-surface-solid); border-radius: var(--border-radius-lg); max-height: 90vh; display: flex; flex-direction: column; border: 1px solid var(--glass-border); box-shadow: var(--shadow-lg); }
      .modal-header { display: flex; justify-content: space-between; align-items: center; padding: var(--spacing-lg); border-bottom: 1px solid var(--glass-border); }
      .modal-body {
        padding: var(--spacing-lg);
        overflow-y: auto;
        scrollbar-width: thin;
        scrollbar-color: var(--glass-border-strong) transparent;
      }
      .modal-body::-webkit-scrollbar { width: 8px; }
      .modal-body::-webkit-scrollbar-track { background: transparent; }
      .modal-body::-webkit-scrollbar-thumb {
        background: var(--glass-border-strong);
        border-radius: 999px;
        border: 2px solid transparent;
        background-clip: padding-box;
      }
      .modal-body::-webkit-scrollbar-thumb:hover { background: var(--border-color-hover); }
      .close-btn { background: none; border: none; font-size: 2rem; cursor: pointer; color: var(--color-text-secondary); line-height: 1; padding: 0; }
      .ai-modal { max-width: 760px; width: 92%; }
      .modal-hint { color: var(--color-text-muted); margin: 0 0 var(--spacing-lg) 0; }
      .ai-source { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: var(--spacing-md); margin-bottom: var(--spacing-lg); }
      .source-card { border: 1px solid var(--glass-border); border-radius: var(--border-radius-lg); padding: var(--spacing-md); background: var(--color-card-background); }
      .source-card.active { box-shadow: var(--shadow-sm); }
      .source-card.disabled { opacity: 0.6; pointer-events: none; }
      .source-title { font-weight: 600; margin-bottom: var(--spacing-xs); }
      .source-meta { font-size: 0.9rem; color: var(--color-text-secondary); }
      .form-grid { display: grid; gap: var(--spacing-md); grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); margin-bottom: var(--spacing-md); }
      .compact-head-grid { align-items: start; }
      .compact-head-grid .form-field {
        display: grid;
        grid-template-rows: minmax(2.4rem, auto) minmax(40px, auto) auto;
        align-content: start;
      }
      .compact-head-grid .form-field label {
        line-height: 1.2;
        display: flex;
        align-items: flex-end;
      }
      .compact-head-grid .form-field input,
      .compact-head-grid .form-field .glass-select {
        min-height: 40px;
        box-sizing: border-box;
      }
      .form-field { display: flex; flex-direction: column; gap: var(--spacing-xs); }
      .form-field input,
      .form-field textarea { padding: var(--spacing-sm); border-radius: var(--border-radius-md); border: 1px solid var(--border-color); background: var(--color-background); color: var(--color-text-primary); }
      .field-hint { font-size: 0.85rem; color: var(--color-text-secondary); margin: 0; }
      .field-options { margin-bottom: var(--spacing-lg); }
      .field-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: var(--spacing-sm); margin-top: var(--spacing-sm); }
      .field-option {
        display: grid;
        grid-template-columns: auto minmax(0, 1fr);
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
      .field-option.disabled { opacity: 0.6; cursor: not-allowed; box-shadow: none; }
      .field-option:not(.disabled):hover { border-color: var(--color-primary-accent); box-shadow: var(--accent-shadow); transform: translateY(-1px); }
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
      .field-checkbox:focus-visible { outline: none; box-shadow: var(--focus-ring); }
      .field-option:focus-within { border-color: var(--color-primary-accent); }
      .field-option.disabled .field-checkbox { cursor: not-allowed; opacity: 0.7; }
      .field-label {
        font-weight: 600;
        color: var(--color-text-primary);
        min-width: 0;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
      .tts-section { margin-bottom: var(--spacing-lg); }
      .tts-toggle { display: inline-flex; align-items: center; gap: var(--spacing-sm); font-weight: 600; }
      .tts-panel { margin-top: var(--spacing-md); padding: var(--spacing-md); border-radius: var(--border-radius-md); border: 1px solid var(--border-color); background: var(--color-background); }
      .tts-mapping { margin-top: var(--spacing-md); }
      .mapping-list { display: grid; gap: var(--spacing-sm); margin-top: var(--spacing-sm); }
      .mapping-row { display: grid; grid-template-columns: minmax(0, 1fr) auto minmax(0, 1fr) auto; gap: var(--spacing-sm); align-items: center; }
      .mapping-row select:not(.glass-select) { padding: var(--spacing-xs) var(--spacing-sm); }
      .mapping-arrow { font-weight: 600; color: var(--color-text-secondary); }
      .remove-mapping { background: none; border: 1px solid var(--border-color); border-radius: 999px; width: 28px; height: 28px; cursor: pointer; }
      .add-mapping { margin-top: var(--spacing-sm); background: none; border: 1px dashed var(--border-color); border-radius: var(--border-radius-md); padding: var(--spacing-xs) var(--spacing-sm); cursor: pointer; }
      .error-state { margin-top: var(--spacing-md); color: var(--color-error); }
      .success-state { margin-top: var(--spacing-md); color: var(--color-success); }
      .modal-footer { display: flex; justify-content: flex-end; gap: var(--spacing-md); padding: var(--spacing-lg); border-top: 1px solid var(--glass-border); }
    `]
})
export class AiAddCardsModalComponent implements OnInit {
    @Input() userDeckId = '';
    @Input() deckName = '';
    @Input() templateId = '';
    @Input() templateVersion: number | null = null;
    @Output() closed = new EventEmitter<void>();
    @Output() jobCreated = new EventEmitter<AiJobResponse>();

    private storageKey = '';
    private draftLoaded = false;
    providerKeys = signal<AiProviderCredential[]>([]);
    loadingProviders = signal(false);
    loadingTemplate = signal(false);
    selectedCredentialId = signal('');
    cardsCount = signal(10);
    modelName = signal('');
    prompt = signal('');
    creating = signal(false);
    createError = signal('');
    createSuccess = signal('');

    templateFields = signal<FieldTemplateDTO[]>([]);
    textFields = signal<FieldTemplateDTO[]>([]);
    audioFields = signal<FieldTemplateDTO[]>([]);

    fieldOptions = signal<FieldOption[]>([
        { key: 'front', label: 'Front', enabled: true },
        { key: 'back', label: 'Back', enabled: true },
        { key: 'translation', label: 'Translation', enabled: true },
        { key: 'example', label: 'Example', enabled: true },
        { key: 'audio', label: 'Audio', enabled: true },
        { key: 'image', label: 'Image', enabled: false }
    ]);

    selectedFields = signal<Set<string>>(new Set(['front', 'back']));

    ttsEnabled = signal(false);
    ttsModel = signal('');
    ttsVoicePreset = signal('alloy');
    ttsVoiceCustom = signal('');
    ttsFormat = signal('mp3');
    ttsMaxChars = signal(300);
    ttsMappings = signal<TtsMapping[]>([]);
    imageModel = signal('');
    imageModelCustom = signal('');
    imageSize = signal('1024x1024');
    imageFormat = signal('png');
    videoModel = signal('');
    videoModelCustom = signal('');
    videoResolution = signal('1280x720');
    videoDurationSeconds = signal(5);
    videoFormat = signal('mp4');
    runtimeCapabilities = signal<AiRuntimeCapabilities | null>(null);

    readonly voiceOptions = computed(() => {
        const provider = this.selectedProvider();
        if (provider === 'gemini') {
            return [...this.geminiVoices, 'custom'];
        }
        if (provider === 'qwen') {
            return [...this.qwenVoices, 'custom'];
        }
        if (provider === 'openai') {
            return [...this.openAiVoices, 'custom'];
        }
        return ['custom'];
    });
    readonly formatOptions = ['mp3', 'ogg', 'wav'];
    readonly ttsFormatOptions = computed(() => ['gemini', 'qwen'].includes(this.selectedProvider()) ? ['wav'] : this.formatOptions);
    readonly ttsSupported = computed(() => this.supportsCapability(this.selectedProvider(), 'tts', ['openai', 'gemini', 'qwen']));
    readonly hasAudioFields = computed(() => this.audioFields().length > 0);
    readonly imageSupported = computed(() => this.supportsCapability(this.selectedProvider(), 'image', ['openai', 'gemini', 'qwen', 'grok']));
    readonly videoSupported = computed(() => this.supportsCapability(this.selectedProvider(), 'video', ['openai', 'qwen', 'grok']));
    readonly selectedProvider = computed(() => {
        const selectedId = this.selectedCredentialId();
        if (!selectedId) return '';
        const provider = this.providerKeys().find(item => item.id === selectedId)?.provider;
        return this.normalizeProvider(provider);
    });
    readonly modelPlaceholder = computed(() => this.resolveModelPlaceholder(this.selectedProvider()));
    readonly ttsModelPlaceholder = computed(() => this.resolveTtsModelPlaceholder(this.selectedProvider()));
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

    constructor(
        private aiApi: AiApiService,
        private templateApi: TemplateApiService,
        private i18n: I18nService
    ) {}

    ngOnInit(): void {
        this.storageKey = `mnema_ai_add_cards:${this.userDeckId || 'default'}`;
        this.restoreDraft();
        this.loadRuntimeCapabilities();
        this.loadProviders();
        this.loadTemplateFields();
    }

    private loadRuntimeCapabilities(): void {
        this.aiApi.getRuntimeCapabilities().subscribe({
            next: capabilities => this.runtimeCapabilities.set(capabilities),
            error: () => this.runtimeCapabilities.set(null)
        });
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
                const textFields = fields.filter(field => this.isTextField(field.fieldType));
                const audioFields = fields.filter(field => field.fieldType === 'audio');
                this.textFields.set(textFields);
                this.audioFields.set(audioFields);
                this.refreshFieldOptions();
                this.updateTtsEnabled();
                if (!this.draftLoaded) {
                    const initial = textFields.slice(0, 2).map(field => field.name);
                    if (initial.length > 0) {
                        this.selectedFields.set(new Set(initial));
                    }
                }
                if (this.draftLoaded) {
                    const validText = new Set(textFields.map(field => field.name));
                    const validAudio = new Set(audioFields.map(field => field.name));
                    const filteredMappings = this.ttsMappings().filter(mapping =>
                        validText.has(mapping.sourceField) && validAudio.has(mapping.targetField)
                    );
                    this.ttsMappings.set(filteredMappings);
                }
                if (audioFields.length > 0 && this.ttsMappings().length === 0) {
                    const defaultSource = this.resolveDefaultSourceField(textFields);
                    const defaultTarget = audioFields[0]?.name || '';
                    if (defaultSource && defaultTarget) {
                        this.ttsMappings.set([{ sourceField: defaultSource, targetField: defaultTarget }]);
                    }
                }
                this.updateTtsEnabled();
                this.loadingTemplate.set(false);
            },
            error: () => {
                this.loadingTemplate.set(false);
            }
        });
    }

    toggleField(field: FieldOption): void {
        if (!field.enabled) return;
        const next = new Set(this.selectedFields());
        if (next.has(field.key)) {
            next.delete(field.key);
        } else {
            next.add(field.key);
        }
        this.selectedFields.set(next);
        this.updateTtsEnabled();
        this.persistDraft();
    }

    private refreshFieldOptions(): void {
        const options = this.templateFields().map(field => this.toFieldOption(field));
        if (options.length === 0) {
            return;
        }
        this.fieldOptions.set(options);
        const enabledKeys = new Set(options.filter(option => option.enabled).map(option => option.key));
        const filtered = Array.from(this.selectedFields()).filter(key => enabledKeys.has(key));
        if (filtered.length > 0) {
            this.selectedFields.set(new Set(filtered));
        }
    }

    canSubmit(): boolean {
        return !this.creating()
            && !!this.selectedCredentialId()
            && this.cardsCount() > 0
            && this.selectedFields().size > 0
            && this.prompt().trim().length > 0;
    }

    submit(): void {
        if (!this.canSubmit()) return;
        this.creating.set(true);
        this.createError.set('');
        this.createSuccess.set('');

        const fields = Array.from(this.selectedFields());
        const input = this.buildPrompt(fields);
        const tts = this.buildTtsParams();
        const image = this.buildImageParams(fields);
        const video = this.buildVideoParams(fields);

        this.aiApi.createJob({
            requestId: this.generateRequestId(),
            deckId: this.userDeckId,
            type: 'enrich',
            params: {
                providerCredentialId: this.selectedCredentialId(),
                provider: this.selectedProvider() || undefined,
                model: this.modelName().trim() || undefined,
                input,
                fields,
                count: this.cardsCount(),
                mode: 'generate_cards',
                ...(tts ? { tts } : {}),
                ...(image ? { image } : {}),
                ...(video ? { video } : {})
            }
        }).subscribe({
            next: job => {
                this.creating.set(false);
                this.clearDraft();
                this.jobCreated.emit(job);
                this.close();
            },
            error: err => {
                this.creating.set(false);
                this.createError.set(err?.error?.message || this.i18n.translate('aiAdd.createError'));
            }
        });
    }

    close(): void {
        this.closed.emit();
    }

    trackProvider(_: number, key: AiProviderCredential): string {
        return key.id;
    }

    trackField(_: number, field: FieldOption): string {
        return field.key;
    }

    private buildPrompt(fields: string[]): string {
        const count = this.cardsCount();
        const deckLabel = this.deckName ? `Deck: ${this.deckName}.` : '';
        const fieldList = fields.length ? fields.join(', ') : 'front, back';
        return `${deckLabel} Generate ${count} new flashcards. Populate fields: ${fieldList}. ${this.prompt().trim()}`.trim();
    }

    private toFieldOption(field: FieldTemplateDTO): FieldOption {
        const label = field.label || field.name;
        return {
            key: field.name,
            label,
            enabled: this.isFieldOptionEnabled(field.fieldType)
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

    private normalizeProvider(provider?: string | null): string {
        if (!provider) return '';
        const normalized = provider.trim().toLowerCase();
        if (normalized === 'claude') return 'anthropic';
        if (normalized === 'google' || normalized === 'google-gemini') return 'gemini';
        if (normalized === 'xai' || normalized === 'x.ai') return 'grok';
        if (normalized === 'dashscope' || normalized === 'aliyun' || normalized === 'alibaba') return 'qwen';
        return normalized;
    }

    private supportsCapability(provider: string,
                               capability: 'text' | 'stt' | 'tts' | 'image' | 'video' | 'gif',
                               fallbackProviders: string[]): boolean {
        if (!provider) {
            return false;
        }
        const runtime = this.runtimeCapabilities();
        const runtimeCaps = runtime?.providers?.find(item => this.normalizeProvider(item.key) === provider);
        if (runtimeCaps) {
            return Boolean(runtimeCaps[capability]);
        }
        return fallbackProviders.includes(provider);
    }

    private syncVoicePreset(): void {
        const options = this.voiceOptions();
        if (!options.length) return;
        if (options.includes(this.ttsVoicePreset())) return;
        const fallback = options[0];
        this.ttsVoicePreset.set(fallback);
        if (fallback !== 'custom') {
            this.ttsVoiceCustom.set('');
        }
    }

    voiceLabel(voice: string): string {
        if (!voice) return '';
        if (voice === 'custom') return this.i18n.translate('aiAdd.customOption');
        return voice.charAt(0).toUpperCase() + voice.slice(1);
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
        switch (provider) {
            case 'openai':
                return 'gpt-4o-mini-tts';
            case 'gemini':
                return 'gemini-2.5-flash-preview-tts';
            case 'qwen':
                return 'qwen3-tts-flash';
            default:
                return 'tts-model';
        }
    }

    private resolveImageModelOptions(provider: string): string[] {
        if (provider === 'openai') {
            return ['gpt-image-1-mini', 'gpt-image-1', 'custom'];
        }
        if (provider === 'gemini') {
            return ['gemini-2.5-flash-image', 'gemini-3-pro-image-preview', 'custom'];
        }
        if (provider === 'qwen') {
            return ['qwen-image-plus', 'qwen-image', 'qwen-image-max', 'custom'];
        }
        if (provider === 'grok') {
            return ['grok-imagine-image', 'grok-imagine-image-pro', 'grok-2-image-latest', 'custom'];
        }
        return ['custom'];
    }

    private resolveVideoModelOptions(provider: string): string[] {
        if (provider === 'openai') {
            return ['sora-2', 'custom'];
        }
        if (provider === 'qwen') {
            return ['wan2.2-t2v-plus', 'wan2.5-t2v-preview', 'wan2.6-t2v', 'custom'];
        }
        if (provider === 'grok') {
            return ['grok-imagine-video', 'custom'];
        }
        return ['custom'];
    }

    private ensureDefaultMediaModels(): void {
        const imageOptions = this.imageModelOptions();
        if (!imageOptions.includes(this.imageModel())) {
            this.imageModel.set(imageOptions[0]);
        }
        const videoOptions = this.videoModelOptions();
        if (!videoOptions.includes(this.videoModel())) {
            this.videoModel.set(videoOptions[0]);
        }
    }

    onProviderChange(value: string): void {
        this.selectedCredentialId.set(value);
        const allowed = this.ttsFormatOptions();
        if (!allowed.includes(this.ttsFormat())) {
            this.ttsFormat.set(allowed[0]);
        }
        if (!this.ttsSupported()) {
            this.ttsEnabled.set(false);
        }
        this.ensureDefaultMediaModels();
        this.syncVoicePreset();
        this.updateTtsEnabled();
        this.refreshFieldOptions();
        this.persistDraft();
    }

    onCountChange(value: number): void {
        this.cardsCount.set(value);
        this.persistDraft();
    }

    onModelChange(value: string): void {
        this.modelName.set(value);
        this.persistDraft();
    }

    onPromptChange(value: string): void {
        this.prompt.set(value);
        this.persistDraft();
    }

    onTtsModelChange(value: string): void {
        this.ttsModel.set(value);
        this.persistDraft();
    }

    onTtsVoicePresetChange(value: string): void {
        this.ttsVoicePreset.set(value);
        if (value !== 'custom') {
            this.ttsVoiceCustom.set('');
        }
        this.persistDraft();
    }

    onTtsVoiceCustomChange(value: string): void {
        this.ttsVoiceCustom.set(value);
        this.persistDraft();
    }

    onTtsFormatChange(value: string): void {
        this.ttsFormat.set(value);
        this.persistDraft();
    }

    onTtsMaxCharsChange(value: number): void {
        this.ttsMaxChars.set(value);
        this.persistDraft();
    }

    onImageModelChange(value: string): void {
        this.imageModel.set(value);
        if (value !== 'custom') {
            this.imageModelCustom.set('');
        }
        this.persistDraft();
    }

    onImageModelCustomChange(value: string): void {
        this.imageModelCustom.set(value);
        this.persistDraft();
    }

    onImageSizeChange(value: string): void {
        this.imageSize.set(value);
        this.persistDraft();
    }

    onImageFormatChange(value: string): void {
        this.imageFormat.set(value);
        this.persistDraft();
    }

    onVideoModelChange(value: string): void {
        this.videoModel.set(value);
        if (value !== 'custom') {
            this.videoModelCustom.set('');
        }
        this.persistDraft();
    }

    onVideoModelCustomChange(value: string): void {
        this.videoModelCustom.set(value);
        this.persistDraft();
    }

    onVideoDurationChange(value: number): void {
        this.videoDurationSeconds.set(Math.max(1, Math.min(Number(value) || 1, 20)));
        this.persistDraft();
    }

    onVideoResolutionChange(value: string): void {
        this.videoResolution.set(value);
        this.persistDraft();
    }

    onVideoFormatChange(value: string): void {
        this.videoFormat.set(value);
        this.persistDraft();
    }

    addTtsMapping(): void {
        const source = this.resolveDefaultSourceField(this.textFields());
        const target = this.audioFields()[0]?.name || '';
        if (!source || !target) {
            return;
        }
        this.ttsMappings.update(list => [...list, { sourceField: source, targetField: target }]);
        this.persistDraft();
    }

    removeTtsMapping(index: number): void {
        this.ttsMappings.update(list => list.filter((_, i) => i !== index));
        this.persistDraft();
    }

    onTtsSourceChange(index: number, value: string): void {
        this.ttsMappings.update(list => list.map((item, i) => i === index ? { ...item, sourceField: value } : item));
        this.persistDraft();
    }

    onTtsTargetChange(index: number, value: string): void {
        this.ttsMappings.update(list => list.map((item, i) => i === index ? { ...item, targetField: value } : item));
        this.persistDraft();
    }

    private resolveVoice(): string {
        if (this.ttsVoicePreset() === 'custom') {
            const custom = this.ttsVoiceCustom().trim();
            return this.selectedProvider() === 'gemini' ? custom.toLowerCase() : custom;
        }
        const voice = this.ttsVoicePreset();
        return this.selectedProvider() === 'gemini' ? voice.toLowerCase() : voice;
    }

    private buildTtsParams(): Record<string, unknown> | null {
        if (!this.hasAudioFields() || !this.ttsEnabled() || !this.ttsSupported()) {
            return null;
        }
        const mappings = this.ttsMappings()
            .filter(mapping => mapping.sourceField && mapping.targetField);
        if (mappings.length === 0) {
            return null;
        }
        return {
            enabled: true,
            model: this.ttsModel().trim() || undefined,
            voice: this.resolveVoice() || undefined,
            format: this.ttsFormat(),
            maxChars: this.ttsMaxChars(),
            mappings
        };
    }

    private updateTtsEnabled(): void {
        if (!this.ttsSupported()) {
            this.ttsEnabled.set(false);
            return;
        }
        const audioNames = new Set(this.audioFields().map(field => field.name));
        const hasSelectedAudio = Array.from(this.selectedFields()).some(field => audioNames.has(field));
        this.ttsEnabled.set(hasSelectedAudio);
    }

    private buildImageParams(fields: string[]): Record<string, unknown> | null {
        if (!this.imageSupported()) {
            return null;
        }
        const hasImage = this.templateFields().some(field => field.fieldType === 'image' && fields.includes(field.name));
        if (!hasImage) {
            return null;
        }
        const selectedModel = this.imageModel() === 'custom'
            ? this.imageModelCustom().trim()
            : this.imageModel().trim();
        return {
            enabled: true,
            model: selectedModel || undefined,
            size: this.imageSize().trim() || undefined,
            format: this.imageFormat()
        };
    }

    private buildVideoParams(fields: string[]): Record<string, unknown> | null {
        if (!this.videoSupported()) {
            return null;
        }
        const hasVideo = this.templateFields().some(field => field.fieldType === 'video' && fields.includes(field.name));
        if (!hasVideo) {
            return null;
        }
        const selectedModel = this.videoModel() === 'custom'
            ? this.videoModelCustom().trim()
            : this.videoModel().trim();
        return {
            enabled: true,
            model: selectedModel || undefined,
            durationSeconds: this.videoDurationSeconds(),
            resolution: this.videoResolution().trim() || undefined,
            format: this.videoFormat()
        };
    }

    private persistDraft(): void {
        if (!this.storageKey) return;
        const payload = {
            providerCredentialId: this.selectedCredentialId(),
            cardsCount: this.cardsCount(),
            modelName: this.modelName(),
            prompt: this.prompt(),
            selectedFields: Array.from(this.selectedFields()),
            ttsEnabled: this.ttsEnabled(),
            ttsModel: this.ttsModel(),
            ttsVoice: this.resolveVoice(),
            ttsFormat: this.ttsFormat(),
            ttsMaxChars: this.ttsMaxChars(),
            ttsMappings: this.ttsMappings(),
            imageModel: this.imageModel(),
            imageModelCustom: this.imageModelCustom(),
            imageSize: this.imageSize(),
            imageFormat: this.imageFormat(),
            videoModel: this.videoModel(),
            videoModelCustom: this.videoModelCustom(),
            videoResolution: this.videoResolution(),
            videoDurationSeconds: this.videoDurationSeconds(),
            videoFormat: this.videoFormat()
        };
        try {
            localStorage.setItem(this.storageKey, JSON.stringify(payload));
        } catch {
        }
    }

    private restoreDraft(): void {
        if (!this.storageKey) return;
        try {
            const raw = localStorage.getItem(this.storageKey);
            if (!raw) return;
            const payload = JSON.parse(raw);
            this.draftLoaded = true;
            if (payload.providerCredentialId) this.selectedCredentialId.set(payload.providerCredentialId);
            if (payload.cardsCount) this.cardsCount.set(payload.cardsCount);
            if (payload.modelName) this.modelName.set(payload.modelName);
            if (payload.prompt) this.prompt.set(payload.prompt);
            if (Array.isArray(payload.selectedFields)) {
                this.selectedFields.set(new Set(payload.selectedFields));
            }
            if (typeof payload.ttsEnabled === 'boolean') this.ttsEnabled.set(payload.ttsEnabled);
            if (payload.ttsModel) this.ttsModel.set(payload.ttsModel);
            if (payload.ttsFormat) this.ttsFormat.set(payload.ttsFormat);
            if (payload.ttsMaxChars) this.ttsMaxChars.set(payload.ttsMaxChars);
            if (payload.ttsVoice) {
                const options = this.voiceOptions();
                if (options.includes(payload.ttsVoice)) {
                    this.ttsVoicePreset.set(payload.ttsVoice);
                } else {
                    this.ttsVoicePreset.set(options[0] || 'custom');
                    this.ttsVoiceCustom.set('');
                }
            }
            if (Array.isArray(payload.ttsMappings)) {
                this.ttsMappings.set(payload.ttsMappings);
            }
            if (payload.imageModel) this.imageModel.set(payload.imageModel);
            if (payload.imageModelCustom) this.imageModelCustom.set(payload.imageModelCustom);
            if (payload.imageSize) this.imageSize.set(payload.imageSize);
            if (payload.imageFormat) this.imageFormat.set(payload.imageFormat);
            if (payload.videoModel) this.videoModel.set(payload.videoModel);
            if (payload.videoModelCustom) this.videoModelCustom.set(payload.videoModelCustom);
            if (payload.videoResolution) this.videoResolution.set(payload.videoResolution);
            if (payload.videoDurationSeconds) this.videoDurationSeconds.set(payload.videoDurationSeconds);
            if (payload.videoFormat) this.videoFormat.set(payload.videoFormat);
            const allowedFormats = this.ttsFormatOptions();
            if (!allowedFormats.includes(this.ttsFormat())) {
                this.ttsFormat.set(allowedFormats[0]);
            }
            this.ensureDefaultMediaModels();
            this.syncVoicePreset();
            this.updateTtsEnabled();
        } catch {
        }
    }

    private clearDraft(): void {
        if (!this.storageKey) return;
        try {
            localStorage.removeItem(this.storageKey);
        } catch {
        }
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
