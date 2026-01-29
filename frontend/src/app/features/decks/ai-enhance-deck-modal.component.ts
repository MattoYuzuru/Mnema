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

    options = signal<EnhanceOption[]>([
        { key: 'audit', label: 'Deck audit', description: 'Find inconsistencies and weak cards.', enabled: true },
        { key: 'missing_fields', label: 'Fill missing fields', description: 'Suggest missing translations/examples.', enabled: true },
        { key: 'dedup', label: 'Find duplicates', description: 'Detect near-duplicate cards.', enabled: true },
        { key: 'tts', label: 'Generate missing audio', description: 'Create pronunciation for empty audio fields.', enabled: false }
    ]);
    selectedOptions = signal<Set<string>>(new Set(['audit']));
    readonly hasMissingFields = computed(() => this.selectedOptions().has('missing_fields'));
    readonly hasDedup = computed(() => this.selectedOptions().has('dedup'));
    readonly hasAiActions = computed(() => {
        const options = this.selectedOptions();
        return options.has('missing_fields') || options.has('audit');
    });
    readonly textFields = computed(() =>
        this.templateFields().filter(field => this.isTextField(field.fieldType))
    );

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
                this.loadingTemplate.set(false);
                this.refreshMissingFields();
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
            && (!this.selectedOptions().has('dedup') || this.selectedDedupFields().size > 0)
            && this.hasAiActions();
    }

    submit(): void {
        if (!this.canSubmit()) return;
        this.creating.set(true);
        this.createError.set('');
        this.createSuccess.set('');

        const actions = Array.from(this.selectedOptions());
        const input = this.buildPrompt(actions);
        const missingSelected = this.selectedOptions().has('missing_fields');
        const dedupSelected = this.selectedOptions().has('dedup');

        this.aiApi.createJob({
            requestId: this.generateRequestId(),
            deckId: this.userDeckId,
            type: 'generic',
            params: {
                providerCredentialId: this.selectedCredentialId(),
                model: this.modelName().trim() || undefined,
                input,
                actions,
                mode: missingSelected ? 'missing_fields' : 'enhance_deck',
                ...(missingSelected ? {
                    fields: Array.from(this.selectedMissingFields()),
                    limit: this.missingLimit()
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

    private persistDraft(): void {
        if (!this.storageKey) return;
        const payload = {
            providerCredentialId: this.selectedCredentialId(),
            modelName: this.modelName(),
            notes: this.notes(),
            selectedOptions: Array.from(this.selectedOptions()),
            missingFields: Array.from(this.selectedMissingFields()),
            missingLimit: this.missingLimit(),
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
