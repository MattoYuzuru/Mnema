import { Component, EventEmitter, Input, OnInit, Output, computed, signal } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { AiApiService } from '../../core/services/ai-api.service';
import { TemplateApiService } from '../../core/services/template-api.service';
import { AiProviderCredential } from '../../core/models/ai.models';
import { FieldTemplateDTO } from '../../core/models/template.models';
import { ButtonComponent } from '../../shared/components/button.component';

type FieldOption = { key: string; label: string; enabled: boolean };
type TtsMapping = { sourceField: string; targetField: string };

@Component({
    selector: 'app-ai-add-cards-modal',
    standalone: true,
    imports: [NgFor, NgIf, FormsModule, ButtonComponent],
    template: `
    <div class="modal-overlay" (click)="close()">
      <div class="modal-content ai-modal" (click)="$event.stopPropagation()">
        <div class="modal-header">
          <h2>AI Additions</h2>
          <button class="close-btn" (click)="close()">&times;</button>
        </div>

        <div class="modal-body">
          <p class="modal-hint">
            Generate new cards with AI using your provider key. Results will appear in this deck once completed.
          </p>

          <div class="ai-source">
            <div class="source-card active">
              <div class="source-title">Use my key</div>
              <div class="source-meta">Your saved provider keys are used for generation.</div>
            </div>
            <div class="source-card disabled" aria-disabled="true">
              <div class="source-title">Use Mnema subscription</div>
              <div class="source-meta">Coming soon</div>
            </div>
          </div>

          <div class="form-grid">
            <div class="form-field">
              <label for="ai-provider-key">Provider key</label>
              <select
                id="ai-provider-key"
                [ngModel]="selectedCredentialId()"
                (ngModelChange)="onProviderChange($event)"
                [disabled]="loadingProviders() || providerKeys().length === 0"
              >
                <option [ngValue]="''">Select a key</option>
                <option *ngFor="let key of providerKeys(); trackBy: trackProvider" [ngValue]="key.id">
                  {{ key.provider }}{{ key.alias ? ' · ' + key.alias : '' }}
                </option>
              </select>
              <p *ngIf="!loadingProviders() && providerKeys().length === 0" class="field-hint">
                Add a provider key in Settings first.
              </p>
            </div>

            <div class="form-field">
              <label for="ai-card-count">Cards to generate</label>
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
              <label for="ai-model">Model (optional)</label>
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
            <label>Fields to generate</label>
            <div class="chips">
              <label *ngFor="let field of fieldOptions(); trackBy: trackField" class="chip" [class.disabled]="!field.enabled">
                <input
                  type="checkbox"
                  [checked]="selectedFields().has(field.key)"
                  (change)="toggleField(field)"
                  [disabled]="!field.enabled"
                />
                <span>{{ field.label }}</span>
              </label>
            </div>
          </div>

          <div *ngIf="hasAudioFields()" class="tts-section">
            <label class="tts-toggle">
              <input
                type="checkbox"
                [checked]="ttsEnabled()"
                (change)="onTtsEnabledChange($any($event.target).checked)"
              />
              <span>Generate audio (TTS)</span>
            </label>

            <div *ngIf="ttsEnabled()" class="tts-panel">
              <div class="form-grid">
                <div class="form-field">
                  <label for="ai-tts-model">TTS model</label>
                  <input
                    id="ai-tts-model"
                    type="text"
                    [ngModel]="ttsModel()"
                    (ngModelChange)="onTtsModelChange($event)"
                    [placeholder]="ttsModelPlaceholder()"
                  />
                </div>
                <div class="form-field">
                  <label for="ai-tts-voice">Voice</label>
                  <select
                    id="ai-tts-voice"
                    [ngModel]="ttsVoicePreset()"
                    (ngModelChange)="onTtsVoicePresetChange($event)"
                  >
                    <option *ngFor="let voice of voiceOptions" [ngValue]="voice">
                      {{ voice }}
                    </option>
                  </select>
                  <input
                    *ngIf="ttsVoicePreset() === 'custom'"
                    type="text"
                    [ngModel]="ttsVoiceCustom()"
                    (ngModelChange)="onTtsVoiceCustomChange($event)"
                    placeholder="Custom voice"
                  />
                </div>
                <div class="form-field">
                  <label for="ai-tts-format">Format</label>
                  <select
                    id="ai-tts-format"
                    [ngModel]="ttsFormat()"
                    (ngModelChange)="onTtsFormatChange($event)"
                  >
                    <option *ngFor="let format of formatOptions" [ngValue]="format">
                      {{ format }}
                    </option>
                  </select>
                </div>
                <div class="form-field">
                  <label for="ai-tts-max-chars">Max chars</label>
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
                <label>Audio field mapping</label>
                <div class="mapping-list">
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
                </div>
                <button type="button" class="add-mapping" (click)="addTtsMapping()">Add mapping</button>
              </div>
            </div>
          </div>

          <div class="form-field">
            <label for="ai-prompt">Instructions</label>
            <textarea
              id="ai-prompt"
              rows="4"
              [ngModel]="prompt()"
              (ngModelChange)="onPromptChange($event)"
              placeholder="Describe what kind of cards you want to generate..."
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
          <app-button variant="ghost" (click)="close()">Cancel</app-button>
          <app-button
            variant="primary"
            (click)="submit()"
            [disabled]="!canSubmit()"
          >
            {{ creating() ? 'Queueing...' : 'Generate cards' }}
          </app-button>
        </div>
      </div>
    </div>
  `,
    styles: [`
      .modal-overlay { position: fixed; top: 0; left: 0; right: 0; bottom: 0; background: rgba(8, 12, 22, 0.55); display: flex; align-items: center; justify-content: center; z-index: 1000; backdrop-filter: blur(12px) saturate(140%); }
      .modal-content { background: var(--color-surface-solid); border-radius: var(--border-radius-lg); max-height: 90vh; display: flex; flex-direction: column; border: 1px solid var(--glass-border); box-shadow: var(--shadow-lg); }
      .modal-header { display: flex; justify-content: space-between; align-items: center; padding: var(--spacing-lg); border-bottom: 1px solid var(--glass-border); }
      .modal-body { padding: var(--spacing-lg); overflow-y: auto; }
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
      .form-field { display: flex; flex-direction: column; gap: var(--spacing-xs); }
      .form-field input,
      .form-field select,
      .form-field textarea { padding: var(--spacing-sm); border-radius: var(--border-radius-md); border: 1px solid var(--border-color); background: var(--color-background); color: var(--color-text-primary); }
      .field-hint { font-size: 0.85rem; color: var(--color-text-secondary); margin: 0; }
      .field-options { margin-bottom: var(--spacing-lg); }
      .chips { display: flex; flex-wrap: wrap; gap: var(--spacing-sm); margin-top: var(--spacing-sm); }
      .chip { display: inline-flex; align-items: center; gap: var(--spacing-xs); padding: var(--spacing-xs) var(--spacing-sm); border-radius: 999px; border: 1px solid var(--border-color); background: var(--color-background); }
      .chip.disabled { opacity: 0.6; }
      .tts-section { margin-bottom: var(--spacing-lg); }
      .tts-toggle { display: inline-flex; align-items: center; gap: var(--spacing-sm); font-weight: 600; }
      .tts-panel { margin-top: var(--spacing-md); padding: var(--spacing-md); border-radius: var(--border-radius-md); border: 1px solid var(--border-color); background: var(--color-background); }
      .tts-mapping { margin-top: var(--spacing-md); }
      .mapping-list { display: grid; gap: var(--spacing-sm); margin-top: var(--spacing-sm); }
      .mapping-row { display: grid; grid-template-columns: minmax(0, 1fr) auto minmax(0, 1fr) auto; gap: var(--spacing-sm); align-items: center; }
      .mapping-row select { padding: var(--spacing-xs) var(--spacing-sm); }
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

    readonly voiceOptions = ['alloy', 'ash', 'coral', 'echo', 'fable', 'onyx', 'nova', 'sage', 'shimmer', 'custom'];
    readonly formatOptions = ['mp3', 'ogg', 'wav'];
    readonly hasAudioFields = computed(() => this.audioFields().length > 0);
    readonly selectedProvider = computed(() => {
        const selectedId = this.selectedCredentialId();
        if (!selectedId) return '';
        const provider = this.providerKeys().find(item => item.id === selectedId)?.provider;
        return this.normalizeProvider(provider);
    });
    readonly modelPlaceholder = computed(() => this.resolveModelPlaceholder(this.selectedProvider()));
    readonly ttsModelPlaceholder = computed(() => this.resolveTtsModelPlaceholder(this.selectedProvider()));

    constructor(private aiApi: AiApiService, private templateApi: TemplateApiService) {}

    ngOnInit(): void {
        this.storageKey = `mnema_ai_add_cards:${this.userDeckId || 'default'}`;
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
                }
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
        const options = fields.map(field => this.toFieldOption(field));
        const textFields = fields.filter(field => this.isTextField(field.fieldType));
        const audioFields = fields.filter(field => field.fieldType === 'audio');
        this.textFields.set(textFields);
        this.audioFields.set(audioFields);
        if (options.length > 0) {
            this.fieldOptions.set(options);
            if (!this.draftLoaded) {
                const initial = textFields.slice(0, 2).map(field => field.name);
                if (initial.length > 0) {
                    this.selectedFields.set(new Set(initial));
                }
            }
        }
        if (this.draftLoaded) {
            const validKeys = new Set(options.map(option => option.key));
            const filtered = Array.from(this.selectedFields()).filter(key => validKeys.has(key));
            if (filtered.length > 0) {
                this.selectedFields.set(new Set(filtered));
            }
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
        this.persistDraft();
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

        this.aiApi.createJob({
            requestId: this.generateRequestId(),
            deckId: this.userDeckId,
            type: 'enrich',
            params: {
                providerCredentialId: this.selectedCredentialId(),
                model: this.modelName().trim() || undefined,
                input,
                fields,
                count: this.cardsCount(),
                mode: 'generate_cards',
                ...(tts ? { tts } : {})
            }
        }).subscribe({
            next: () => {
                this.creating.set(false);
            },
            error: err => {
                this.creating.set(false);
                this.createError.set(err?.error?.message || 'Failed to create AI job');
            }
        });
        this.clearDraft();
        this.close();
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
            enabled: this.isTextField(field.fieldType)
        };
    }

    private isTextField(fieldType: string): boolean {
        return ['text', 'rich_text', 'markdown', 'cloze'].includes(fieldType);
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
        return normalized;
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
        switch (provider) {
            case 'openai':
                return 'gpt-4o-mini-tts';
            case 'gemini':
                return 'gemini-2.5-flash-preview-tts';
            default:
                return 'tts-model';
        }
    }

    onProviderChange(value: string): void {
        this.selectedCredentialId.set(value);
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

    onTtsEnabledChange(value: boolean): void {
        this.ttsEnabled.set(value);
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
            return this.ttsVoiceCustom().trim();
        }
        return this.ttsVoicePreset();
    }

    private buildTtsParams(): Record<string, unknown> | null {
        if (!this.hasAudioFields() || !this.ttsEnabled()) {
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
            ttsMappings: this.ttsMappings()
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
                if (this.voiceOptions.includes(payload.ttsVoice)) {
                    this.ttsVoicePreset.set(payload.ttsVoice);
                } else {
                    this.ttsVoicePreset.set('custom');
                    this.ttsVoiceCustom.set(payload.ttsVoice);
                }
            }
            if (Array.isArray(payload.ttsMappings)) {
                this.ttsMappings.set(payload.ttsMappings);
            }
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
