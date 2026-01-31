import { Component, EventEmitter, Input, OnInit, Output, computed, signal } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { AiApiService } from '../../core/services/ai-api.service';
import { CardApiService } from '../../core/services/card-api.service';
import { TemplateApiService } from '../../core/services/template-api.service';
import { AiProviderCredential } from '../../core/models/ai.models';
import { FieldTemplateDTO } from '../../core/models/template.models';
import { MissingFieldStat, DuplicateGroup } from '../../core/models/user-card.models';
import { ButtonComponent } from '../../shared/components/button.component';

type EnhanceOption = { key: string; label: string; description: string; enabled: boolean };
type TtsMapping = { sourceField: string; targetField: string };

@Component({
    selector: 'app-ai-enhance-deck-modal',
    standalone: true,
    imports: [NgFor, NgIf, FormsModule, ButtonComponent],
    template: `
    <div class="modal-overlay" (click)="close()">
      <div class="modal-content ai-modal" (click)="$event.stopPropagation()">
        <div class="modal-header">
          <h2>Deck enhancement</h2>
          <button class="close-btn" (click)="close()">&times;</button>
        </div>

        <div class="modal-body">
          <p class="modal-hint">
            Choose the improvements you want. We will analyze the deck and prepare suggestions.
          </p>

          <div class="form-grid">
            <div class="form-field">
              <label for="ai-enhance-provider">Provider key</label>
              <select
                id="ai-enhance-provider"
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
              <label for="ai-enhance-model">Model (optional)</label>
              <input
                id="ai-enhance-model"
                type="text"
                [ngModel]="modelName()"
                (ngModelChange)="onModelChange($event)"
                [placeholder]="modelPlaceholder()"
              />
            </div>
          </div>

          <div class="enhance-grid">
            <label class="grid-label">Enhancement options</label>
            <div class="enhance-list">
              <label *ngFor="let option of options(); trackBy: trackOption" class="enhance-card" [class.disabled]="!option.enabled">
                <input
                  type="checkbox"
                  [checked]="selectedOptions().has(option.key)"
                  (change)="toggleOption(option)"
                  [disabled]="!option.enabled"
                />
                <div>
                  <div class="enhance-title">{{ option.label }}</div>
                  <div class="enhance-desc">{{ option.description }}</div>
                </div>
                <span *ngIf="!option.enabled" class="chip">Coming soon</span>
              </label>
            </div>
            <p *ngIf="invalidAiCombo()" class="field-hint">
              Run missing fields and audio separately for now.
            </p>
          </div>

          <div *ngIf="hasMissingFields()" class="missing-panel">
            <label class="grid-label">Fields to fill</label>
            <div *ngIf="missingLoading()" class="field-hint">Loading missing fields…</div>
            <div *ngIf="!missingLoading() && missingError()" class="error-state" role="alert">
              {{ missingError() }}
            </div>
            <div *ngIf="!missingLoading() && !missingError()" class="missing-list">
              <label *ngFor="let stat of missingStats(); trackBy: trackMissingField" class="missing-row">
                <input
                  type="checkbox"
                  [checked]="selectedMissingFields().has(stat.field)"
                  (change)="toggleMissingField(stat.field)"
                />
                <span class="missing-label">{{ stat.field }}</span>
                <span class="missing-count">{{ stat.missingCount }} missing</span>
              </label>
            </div>
            <div class="form-field missing-limit">
              <label for="ai-missing-limit">Cards to update</label>
              <input
                id="ai-missing-limit"
                type="number"
                min="1"
                max="200"
                [ngModel]="missingLimit()"
                (ngModelChange)="onMissingLimitChange($event)"
              />
            </div>
          </div>

          <div *ngIf="hasTts()" class="missing-panel">
            <label class="grid-label">Missing audio fields</label>
            <div *ngIf="audioMissingLoading()" class="field-hint">Loading audio fields…</div>
            <div *ngIf="!audioMissingLoading() && audioMissingError()" class="error-state" role="alert">
              {{ audioMissingError() }}
            </div>
            <div *ngIf="!audioMissingLoading() && !audioMissingError()" class="missing-list">
              <label *ngFor="let stat of audioMissingStats(); trackBy: trackMissingField" class="missing-row">
                <input
                  type="checkbox"
                  [checked]="selectedAudioFields().has(stat.field)"
                  (change)="toggleAudioField(stat.field)"
                />
                <span class="missing-label">{{ stat.field }}</span>
                <span class="missing-count">{{ stat.missingCount }} missing</span>
              </label>
              <div *ngIf="audioMissingStats().length === 0" class="field-hint">No audio fields found.</div>
            </div>
            <div class="form-field missing-limit">
              <label for="ai-tts-limit">Cards to update</label>
              <input
                id="ai-tts-limit"
                type="number"
                min="1"
                max="200"
                [ngModel]="ttsLimit()"
                (ngModelChange)="onTtsLimitChange($event)"
              />
            </div>
          </div>

          <div *ngIf="hasTts()" class="tts-section">
            <label class="grid-label">Audio generation</label>
            <div *ngIf="!ttsSupported()" class="field-hint">TTS is supported for OpenAI and Gemini providers.</div>
            <div *ngIf="ttsSupported()" class="tts-panel">
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
                      <option *ngFor="let field of ttsTargetFields()" [ngValue]="field.name">
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

          <div *ngIf="hasDedup()" class="missing-panel">
            <label class="grid-label">Duplicate detection fields</label>
            <div *ngIf="dupesLoading()" class="field-hint">Loading duplicates…</div>
            <div *ngIf="!dupesLoading() && dupesError()" class="error-state" role="alert">
              {{ dupesError() }}
            </div>
            <div *ngIf="!dupesLoading() && !dupesError()" class="missing-list">
              <label *ngFor="let field of textFields(); trackBy: trackField" class="missing-row">
                <input
                  type="checkbox"
                  [checked]="selectedDedupFields().has(field.name)"
                  (change)="toggleDedupField(field.name)"
                />
                <span class="missing-label">{{ field.label || field.name }}</span>
              </label>
              <div *ngIf="duplicateGroups().length === 0" class="field-hint">No obvious duplicates found.</div>
            </div>
            <div class="form-grid">
              <div class="form-field">
                <label for="ai-dedup-groups">Groups to show</label>
                <input
                  id="ai-dedup-groups"
                  type="number"
                  min="1"
                  max="50"
                  [ngModel]="dedupLimitGroups()"
                  (ngModelChange)="onDedupLimitChange($event)"
                />
              </div>
              <div class="form-field">
                <label for="ai-dedup-per-group">Cards per group</label>
                <input
                  id="ai-dedup-per-group"
                  type="number"
                  min="2"
                  max="20"
                  [ngModel]="dedupPerGroup()"
                  (ngModelChange)="onDedupPerGroupChange($event)"
                />
              </div>
            </div>
            <div *ngIf="duplicateGroups().length > 0" class="dup-results">
              <div class="grid-label">Top duplicate groups</div>
              <div class="dup-group" *ngFor="let group of duplicateGroups(); let i = index">
                <div class="dup-title">Group {{ i + 1 }} · {{ group.size }} cards</div>
                <div class="dup-cards">
                  <div class="dup-card" *ngFor="let card of group.cards">
                    <div class="dup-card-field" *ngFor="let field of selectedDedupFields()">
                      <span class="dup-label">{{ field }}</span>
                      <span class="dup-value">{{ (card.effectiveContent || {})[field] || '—' }}</span>
                    </div>
                    <button
                      type="button"
                      class="dup-action"
                      [disabled]="deletingCards().has(card.userCardId)"
                      (click)="deleteDuplicate(card.userCardId)"
                    >
                      {{ deletingCards().has(card.userCardId) ? 'Deleting…' : 'Delete card' }}
                    </button>
                  </div>
                </div>
              </div>
            </div>
          </div>

          <div class="form-field">
            <label for="ai-enhance-notes">Notes (optional)</label>
            <textarea
              id="ai-enhance-notes"
              rows="3"
              [ngModel]="notes()"
              (ngModelChange)="onNotesChange($event)"
              placeholder="Any preferences for the improvement..."
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
            {{ creating() ? 'Queueing...' : 'Start enhancement' }}
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
      .ai-modal { max-width: 720px; width: 92%; }
      .modal-hint { color: var(--color-text-muted); margin: 0 0 var(--spacing-lg) 0; }
      .form-grid { display: grid; gap: var(--spacing-md); grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); margin-bottom: var(--spacing-lg); }
      .form-field { display: flex; flex-direction: column; gap: var(--spacing-xs); }
      .form-field input,
      .form-field select,
      .form-field textarea { padding: var(--spacing-sm); border-radius: var(--border-radius-md); border: 1px solid var(--border-color); background: var(--color-background); color: var(--color-text-primary); }
      .field-hint { font-size: 0.85rem; color: var(--color-text-secondary); margin: 0; }
      .enhance-grid { margin-bottom: var(--spacing-lg); }
      .grid-label { font-weight: 600; }
      .enhance-list { display: grid; gap: var(--spacing-sm); margin-top: var(--spacing-sm); }
      .enhance-card { display: grid; grid-template-columns: auto 1fr auto; gap: var(--spacing-md); align-items: start; padding: var(--spacing-md); border-radius: var(--border-radius-lg); border: 1px solid var(--glass-border); background: var(--color-card-background); }
      .enhance-card.disabled { opacity: 0.6; }
      .enhance-title { font-weight: 600; }
      .enhance-desc { font-size: 0.9rem; color: var(--color-text-secondary); }
      .chip { font-size: 0.75rem; padding: 0.2rem 0.6rem; border-radius: 999px; background: var(--color-background); border: 1px solid var(--border-color); color: var(--color-text-secondary); }
      .missing-panel { margin-bottom: var(--spacing-lg); border: 1px solid var(--glass-border); border-radius: var(--border-radius-lg); padding: var(--spacing-md); background: var(--color-card-background); }
      .missing-list { display: grid; gap: var(--spacing-sm); margin-top: var(--spacing-sm); }
      .missing-row { display: grid; grid-template-columns: auto 1fr auto; gap: var(--spacing-sm); align-items: center; }
      .missing-label { font-weight: 500; }
      .missing-count { font-size: 0.85rem; color: var(--color-text-secondary); }
      .missing-limit { margin-top: var(--spacing-md); max-width: 240px; }
      .tts-section { margin-bottom: var(--spacing-lg); }
      .tts-panel { margin-top: var(--spacing-md); padding: var(--spacing-md); border-radius: var(--border-radius-md); border: 1px solid var(--border-color); background: var(--color-background); }
      .tts-mapping { margin-top: var(--spacing-md); }
      .mapping-list { display: grid; gap: var(--spacing-sm); margin-top: var(--spacing-sm); }
      .mapping-row { display: grid; grid-template-columns: minmax(0, 1fr) auto minmax(0, 1fr) auto; gap: var(--spacing-sm); align-items: center; }
      .mapping-row select { padding: var(--spacing-xs) var(--spacing-sm); }
      .mapping-arrow { font-weight: 600; color: var(--color-text-secondary); }
      .remove-mapping { background: none; border: 1px solid var(--border-color); border-radius: 999px; width: 28px; height: 28px; cursor: pointer; }
      .add-mapping { margin-top: var(--spacing-sm); background: none; border: 1px dashed var(--border-color); border-radius: var(--border-radius-md); padding: var(--spacing-xs) var(--spacing-sm); cursor: pointer; }
      .dup-results { margin-top: var(--spacing-md); display: grid; gap: var(--spacing-md); }
      .dup-group { border: 1px solid var(--glass-border); border-radius: var(--border-radius-md); padding: var(--spacing-sm); background: var(--color-background); }
      .dup-title { font-weight: 600; margin-bottom: var(--spacing-sm); }
      .dup-cards { display: grid; gap: var(--spacing-sm); }
      .dup-card { border: 1px dashed var(--border-color); border-radius: var(--border-radius-md); padding: var(--spacing-sm); }
      .dup-card-field { display: grid; grid-template-columns: 120px 1fr; gap: var(--spacing-sm); }
      .dup-label { font-size: 0.8rem; color: var(--color-text-secondary); }
      .dup-value { font-size: 0.9rem; color: var(--color-text-primary); word-break: break-word; }
      .dup-action { margin-top: var(--spacing-sm); align-self: start; border: 1px solid var(--glass-border); border-radius: 999px; padding: 0.3rem 0.8rem; background: var(--color-card-background); color: var(--color-text-primary); cursor: pointer; }
      .dup-action:disabled { opacity: 0.6; cursor: not-allowed; }
      .error-state { margin-top: var(--spacing-md); color: var(--color-error); }
      .success-state { margin-top: var(--spacing-md); color: var(--color-success); }
      .modal-footer { display: flex; justify-content: flex-end; gap: var(--spacing-md); padding: var(--spacing-lg); border-top: 1px solid var(--glass-border); }
    `]
})
export class AiEnhanceDeckModalComponent implements OnInit {
    @Input() userDeckId = '';
    @Input() deckName = '';
    @Input() templateId = '';
    @Input() templateVersion: number | null = null;
    @Output() closed = new EventEmitter<void>();

    private storageKey = '';
    private draftLoaded = false;
    providerKeys = signal<AiProviderCredential[]>([]);
    loadingProviders = signal(false);
    selectedCredentialId = signal('');
    modelName = signal('');
    notes = signal('');
    creating = signal(false);
    createError = signal('');
    createSuccess = signal('');
    loadingTemplate = signal(false);
    templateFields = signal<FieldTemplateDTO[]>([]);
    missingStats = signal<MissingFieldStat[]>([]);
    missingLoading = signal(false);
    missingError = signal('');
    selectedMissingFields = signal<Set<string>>(new Set());
    missingLimit = signal(50);
    audioMissingStats = signal<MissingFieldStat[]>([]);
    audioMissingLoading = signal(false);
    audioMissingError = signal('');
    selectedAudioFields = signal<Set<string>>(new Set());
    ttsLimit = signal(50);
    ttsModel = signal('');
    ttsVoicePreset = signal('alloy');
    ttsVoiceCustom = signal('');
    ttsFormat = signal('mp3');
    ttsMaxChars = signal(300);
    ttsMappings = signal<TtsMapping[]>([]);
    dupesLoading = signal(false);
    dupesError = signal('');
    duplicateGroups = signal<DuplicateGroup[]>([]);
    selectedDedupFields = signal<Set<string>>(new Set());
    dedupLimitGroups = signal(10);
    dedupPerGroup = signal(5);
    deletingCards = signal<Set<string>>(new Set());
    readonly selectedProvider = computed(() => {
        const selectedId = this.selectedCredentialId();
        if (!selectedId) return '';
        const provider = this.providerKeys().find(item => item.id === selectedId)?.provider;
        return this.normalizeProvider(provider);
    });
    readonly modelPlaceholder = computed(() => this.resolveModelPlaceholder(this.selectedProvider()));
    readonly ttsModelPlaceholder = computed(() => this.resolveTtsModelPlaceholder(this.selectedProvider()));
    readonly ttsSupported = computed(() => ['openai', 'gemini'].includes(this.selectedProvider()));
    readonly voiceOptions = ['alloy', 'ash', 'coral', 'echo', 'fable', 'onyx', 'nova', 'sage', 'shimmer', 'custom'];
    readonly formatOptions = ['mp3', 'ogg', 'wav'];

    options = signal<EnhanceOption[]>([
        { key: 'audit', label: 'Deck audit', description: 'Find inconsistencies and weak cards.', enabled: true },
        { key: 'missing_fields', label: 'Fill missing fields', description: 'Suggest missing translations/examples.', enabled: true },
        { key: 'dedup', label: 'Find duplicates', description: 'Detect near-duplicate cards.', enabled: true },
        { key: 'tts', label: 'Generate missing audio', description: 'Create pronunciation for empty audio fields.', enabled: true }
    ]);
    selectedOptions = signal<Set<string>>(new Set(['audit']));
    readonly hasMissingFields = computed(() => this.selectedOptions().has('missing_fields'));
    readonly hasTts = computed(() => this.selectedOptions().has('tts'));
    readonly hasDedup = computed(() => this.selectedOptions().has('dedup'));
    readonly hasAiActions = computed(() => {
        const options = this.selectedOptions();
        return options.has('missing_fields') || options.has('audit') || options.has('tts');
    });
    readonly invalidAiCombo = computed(() => {
        const options = this.selectedOptions();
        return options.has('missing_fields') && options.has('tts');
    });
    readonly textFields = computed(() =>
        this.templateFields().filter(field => this.isTextField(field.fieldType))
    );
    readonly audioFields = computed(() =>
        this.templateFields().filter(field => field.fieldType === 'audio')
    );
    readonly hasAudioFields = computed(() => this.audioFields().length > 0);
    readonly ttsTargetFields = computed(() => {
        const selected = this.selectedAudioFields();
        if (selected.size === 0) {
            return this.audioFields();
        }
        return this.audioFields().filter(field => selected.has(field.name));
    });

    constructor(
        private aiApi: AiApiService,
        private cardApi: CardApiService,
        private templateApi: TemplateApiService
    ) {}

    ngOnInit(): void {
        this.storageKey = `mnema_ai_enhance:${this.userDeckId || 'default'}`;
        this.restoreDraft();
        this.loadProviders();
        this.loadTemplateFields();
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
                const validAudio = new Set(this.audioFields().map(field => field.name));
                if (validAudio.size > 0) {
                    const filteredAudio = Array.from(this.selectedAudioFields()).filter(name => validAudio.has(name));
                    this.selectedAudioFields.set(new Set(filteredAudio));
                } else {
                    this.selectedAudioFields.set(new Set());
                }
                this.loadingTemplate.set(false);
                this.refreshMissingFields();
                this.reconcileTtsMappings();
                if (this.selectedOptions().has('tts')) {
                    this.refreshMissingAudio();
                }
                if (this.selectedOptions().has('dedup')) {
                    this.refreshDuplicates();
                }
            },
            error: () => {
                this.loadingTemplate.set(false);
            }
        });
    }

    refreshMissingFields(): void {
        const fields = this.textFields().map(field => field.name);
        if (!fields.length) {
            return;
        }
        this.missingLoading.set(true);
        this.missingError.set('');
        this.cardApi.getMissingFieldSummary(this.userDeckId, fields, 3).subscribe({
            next: summary => {
                this.missingStats.set(summary.fields || []);
                if (!this.draftLoaded) {
                    const initial = new Set(
                        (summary.fields || [])
                            .filter(stat => stat.missingCount > 0)
                            .map(stat => stat.field)
                    );
                    this.selectedMissingFields.set(initial);
                }
                this.missingLoading.set(false);
            },
            error: () => {
                this.missingLoading.set(false);
                this.missingError.set('Failed to load missing fields.');
            }
        });
    }

    refreshMissingAudio(): void {
        const fields = this.audioFields().map(field => field.name);
        if (!fields.length) {
            this.audioMissingStats.set([]);
            this.audioMissingLoading.set(false);
            this.audioMissingError.set('');
            return;
        }
        this.audioMissingLoading.set(true);
        this.audioMissingError.set('');
        this.cardApi.getMissingFieldSummary(this.userDeckId, fields, 3).subscribe({
            next: summary => {
                this.audioMissingStats.set(summary.fields || []);
                if (!this.draftLoaded) {
                    const initial = new Set(
                        (summary.fields || [])
                            .filter(stat => stat.missingCount > 0)
                            .map(stat => stat.field)
                    );
                    this.selectedAudioFields.set(initial);
                }
                this.audioMissingLoading.set(false);
                this.reconcileTtsMappings();
            },
            error: () => {
                this.audioMissingLoading.set(false);
                this.audioMissingError.set('Failed to load missing audio fields.');
            }
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
                this.loadingProviders.set(false);
            },
            error: () => {
                this.loadingProviders.set(false);
            }
        });
    }

    toggleOption(option: EnhanceOption): void {
        if (!option.enabled) return;
        const next = new Set(this.selectedOptions());
        if (next.has(option.key)) {
            next.delete(option.key);
        } else {
            next.add(option.key);
        }
        this.selectedOptions.set(next);
        if (next.has('missing_fields')) {
            this.refreshMissingFields();
        }
        if (next.has('tts')) {
            this.refreshMissingAudio();
            this.reconcileTtsMappings();
        }
        if (next.has('dedup')) {
            this.refreshDuplicates();
        }
        this.persistDraft();
    }

    canSubmit(): boolean {
        return !this.creating()
            && !!this.selectedCredentialId()
            && this.selectedOptions().size > 0
            && (!this.selectedOptions().has('missing_fields') || this.selectedMissingFields().size > 0)
            && (!this.selectedOptions().has('tts') || this.isTtsReady())
            && (!this.selectedOptions().has('dedup') || this.selectedDedupFields().size > 0)
            && this.hasAiActions()
            && !this.invalidAiCombo();
    }

    submit(): void {
        if (!this.canSubmit()) return;
        this.creating.set(true);
        this.createError.set('');
        this.createSuccess.set('');

        const actions = Array.from(this.selectedOptions());
        const input = this.buildPrompt(actions);
        const missingSelected = this.selectedOptions().has('missing_fields');
        const ttsSelected = this.selectedOptions().has('tts');
        const tts = ttsSelected ? this.buildTtsParams() : null;

        this.aiApi.createJob({
            requestId: this.generateRequestId(),
            deckId: this.userDeckId,
            type: 'generic',
            params: {
                providerCredentialId: this.selectedCredentialId(),
                model: this.modelName().trim() || undefined,
                input,
                actions,
                mode: missingSelected ? 'missing_fields' : (ttsSelected ? 'missing_audio' : 'enhance_deck'),
                ...(missingSelected ? {
                    fields: Array.from(this.selectedMissingFields()),
                    limit: this.missingLimit()
                } : {}),
                ...(ttsSelected ? {
                    fields: Array.from(this.selectedAudioFields()),
                    limit: this.ttsLimit(),
                    ...(tts ? { tts } : {})
                } : {})
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

    trackOption(_: number, option: EnhanceOption): string {
        return option.key;
    }

    trackField(_: number, field: FieldTemplateDTO): string {
        return field.fieldId;
    }

    trackMissingField(_: number, stat: MissingFieldStat): string {
        return stat.field;
    }

    toggleMissingField(field: string): void {
        const next = new Set(this.selectedMissingFields());
        if (next.has(field)) {
            next.delete(field);
        } else {
            next.add(field);
        }
        this.selectedMissingFields.set(next);
        this.persistDraft();
    }

    onMissingLimitChange(value: number): void {
        this.missingLimit.set(value);
        this.persistDraft();
    }

    toggleAudioField(field: string): void {
        const next = new Set(this.selectedAudioFields());
        if (next.has(field)) {
            next.delete(field);
        } else {
            next.add(field);
        }
        this.selectedAudioFields.set(next);
        this.reconcileTtsMappings();
        this.persistDraft();
    }

    onTtsLimitChange(value: number): void {
        this.ttsLimit.set(value);
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
        const target = this.ttsTargetFields()[0]?.name || '';
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

    refreshDuplicates(): void {
        const selected = this.selectedDedupFields();
        const fields = selected.size > 0
            ? Array.from(selected)
            : this.textFields().map(field => field.name);
        if (!fields.length) {
            return;
        }
        this.dupesLoading.set(true);
        this.dupesError.set('');
        this.cardApi.getDuplicateGroups(this.userDeckId, fields, this.dedupLimitGroups(), this.dedupPerGroup()).subscribe({
            next: groups => {
                this.duplicateGroups.set(groups || []);
                if (!this.draftLoaded) {
                    this.selectedDedupFields.set(new Set(fields));
                }
                this.dupesLoading.set(false);
            },
            error: () => {
                this.dupesLoading.set(false);
                this.dupesError.set('Failed to load duplicates.');
            }
        });
    }

    toggleDedupField(field: string): void {
        const next = new Set(this.selectedDedupFields());
        if (next.has(field)) {
            next.delete(field);
        } else {
            next.add(field);
        }
        this.selectedDedupFields.set(next);
        this.persistDraft();
    }

    onDedupLimitChange(value: number): void {
        this.dedupLimitGroups.set(value);
        this.persistDraft();
    }

    onDedupPerGroupChange(value: number): void {
        this.dedupPerGroup.set(value);
        this.persistDraft();
    }

    deleteDuplicate(cardId: string): void {
        if (!cardId) return;
        if (!confirm('Delete this card?')) return;
        this.deletingCards.update(set => new Set(set).add(cardId));
        this.cardApi.deleteUserCard(this.userDeckId, cardId, 'local').subscribe({
            next: () => {
                this.deletingCards.update(set => {
                    const next = new Set(set);
                    next.delete(cardId);
                    return next;
                });
                this.refreshDuplicates();
            },
            error: () => {
                this.deletingCards.update(set => {
                    const next = new Set(set);
                    next.delete(cardId);
                    return next;
                });
            }
        });
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

    onModelChange(value: string): void {
        this.modelName.set(value);
        this.persistDraft();
    }

    onNotesChange(value: string): void {
        this.notes.set(value);
        this.persistDraft();
    }

    private isTtsReady(): boolean {
        if (!this.hasTts()) {
            return true;
        }
        if (!this.ttsSupported() || !this.hasAudioFields()) {
            return false;
        }
        if (this.selectedAudioFields().size === 0) {
            return false;
        }
        return this.buildTtsParams() !== null;
    }

    private resolveVoice(): string {
        if (this.ttsVoicePreset() === 'custom') {
            return this.ttsVoiceCustom().trim();
        }
        return this.ttsVoicePreset();
    }

    private buildTtsParams(): Record<string, unknown> | null {
        if (!this.hasTts()) {
            return null;
        }
        if (!this.hasAudioFields() || !this.ttsSupported()) {
            return null;
        }
        const targets = new Set(this.ttsTargetFields().map(field => field.name));
        if (targets.size === 0) {
            return null;
        }
        const mappings = this.ttsMappings()
            .filter(mapping => mapping.sourceField && targets.has(mapping.targetField));
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

    private reconcileTtsMappings(): void {
        const textNames = new Set(this.textFields().map(field => field.name));
        const targetNames = new Set(this.ttsTargetFields().map(field => field.name));
        const filtered = this.ttsMappings().filter(mapping =>
            textNames.has(mapping.sourceField) && targetNames.has(mapping.targetField)
        );
        this.ttsMappings.set(filtered);
        if (targetNames.size > 0 && this.ttsMappings().length === 0) {
            const defaultSource = this.resolveDefaultSourceField(this.textFields());
            const defaultTarget = this.ttsTargetFields()[0]?.name || '';
            if (defaultSource && defaultTarget) {
                this.ttsMappings.set([{ sourceField: defaultSource, targetField: defaultTarget }]);
            }
        }
    }

    private persistDraft(): void {
        if (!this.storageKey) return;
        const payload = {
            providerCredentialId: this.selectedCredentialId(),
            modelName: this.modelName(),
            notes: this.notes(),
            selectedOptions: Array.from(this.selectedOptions()),
            missingFields: Array.from(this.selectedMissingFields()),
            missingLimit: this.missingLimit(),
            audioFields: Array.from(this.selectedAudioFields()),
            ttsLimit: this.ttsLimit(),
            ttsModel: this.ttsModel(),
            ttsVoice: this.resolveVoice(),
            ttsFormat: this.ttsFormat(),
            ttsMaxChars: this.ttsMaxChars(),
            ttsMappings: this.ttsMappings(),
            dedupFields: Array.from(this.selectedDedupFields()),
            dedupLimitGroups: this.dedupLimitGroups(),
            dedupPerGroup: this.dedupPerGroup()
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
            if (payload.modelName) this.modelName.set(payload.modelName);
            if (payload.notes) this.notes.set(payload.notes);
            if (Array.isArray(payload.selectedOptions)) {
                this.selectedOptions.set(new Set(payload.selectedOptions));
            }
            if (Array.isArray(payload.missingFields)) {
                this.selectedMissingFields.set(new Set(payload.missingFields));
            }
            if (typeof payload.missingLimit === 'number') {
                this.missingLimit.set(payload.missingLimit);
            }
            if (Array.isArray(payload.audioFields)) {
                this.selectedAudioFields.set(new Set(payload.audioFields));
            }
            if (typeof payload.ttsLimit === 'number') {
                this.ttsLimit.set(payload.ttsLimit);
            }
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
            if (Array.isArray(payload.dedupFields)) {
                this.selectedDedupFields.set(new Set(payload.dedupFields));
            }
            if (typeof payload.dedupLimitGroups === 'number') {
                this.dedupLimitGroups.set(payload.dedupLimitGroups);
            }
            if (typeof payload.dedupPerGroup === 'number') {
                this.dedupPerGroup.set(payload.dedupPerGroup);
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

    private buildPrompt(actions: string[]): string {
        const deckLabel = this.deckName ? `Deck: ${this.deckName}.` : '';
        const tasks = actions.join(', ');
        const notes = this.notes().trim();
        return `${deckLabel} Improve the deck. Actions: ${tasks}.${notes ? ' Notes: ' + notes : ''}`.trim();
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
