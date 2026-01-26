import { Component, EventEmitter, Input, OnInit, Output, signal } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { AiApiService } from '../../core/services/ai-api.service';
import { AiProviderCredential } from '../../core/models/ai.models';
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
                placeholder="gpt-4.1-mini"
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
      .error-state { margin-top: var(--spacing-md); color: var(--color-error); }
      .success-state { margin-top: var(--spacing-md); color: var(--color-success); }
      .modal-footer { display: flex; justify-content: flex-end; gap: var(--spacing-md); padding: var(--spacing-lg); border-top: 1px solid var(--glass-border); }
    `]
})
export class AiEnhanceDeckModalComponent implements OnInit {
    @Input() userDeckId = '';
    @Input() deckName = '';
    @Output() closed = new EventEmitter<void>();

    private storageKey = '';
    providerKeys = signal<AiProviderCredential[]>([]);
    loadingProviders = signal(false);
    selectedCredentialId = signal('');
    modelName = signal('');
    notes = signal('');
    creating = signal(false);
    createError = signal('');
    createSuccess = signal('');

    options = signal<EnhanceOption[]>([
        { key: 'audit', label: 'Deck audit', description: 'Find inconsistencies and weak cards.', enabled: true },
        { key: 'missing_fields', label: 'Fill missing fields', description: 'Suggest missing translations/examples.', enabled: true },
        { key: 'dedup', label: 'Find duplicates', description: 'Detect near-duplicate cards.', enabled: false },
        { key: 'tts', label: 'Generate missing audio', description: 'Create pronunciation for empty audio fields.', enabled: false }
    ]);
    selectedOptions = signal<Set<string>>(new Set(['audit']));

    constructor(private aiApi: AiApiService) {}

    ngOnInit(): void {
        this.storageKey = `mnema_ai_enhance:${this.userDeckId || 'default'}`;
        this.restoreDraft();
        this.loadProviders();
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
        this.persistDraft();
    }

    canSubmit(): boolean {
        return !this.creating()
            && !!this.selectedCredentialId()
            && this.selectedOptions().size > 0;
    }

    submit(): void {
        if (!this.canSubmit()) return;
        this.creating.set(true);
        this.createError.set('');
        this.createSuccess.set('');

        const actions = Array.from(this.selectedOptions());
        const input = this.buildPrompt(actions);

        this.aiApi.createJob({
            requestId: this.generateRequestId(),
            deckId: this.userDeckId,
            type: 'generic',
            params: {
                providerCredentialId: this.selectedCredentialId(),
                model: this.modelName().trim() || undefined,
                input,
                actions,
                mode: 'enhance_deck'
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
            selectedOptions: Array.from(this.selectedOptions())
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
            if (payload.providerCredentialId) this.selectedCredentialId.set(payload.providerCredentialId);
            if (payload.modelName) this.modelName.set(payload.modelName);
            if (payload.notes) this.notes.set(payload.notes);
            if (Array.isArray(payload.selectedOptions)) {
                this.selectedOptions.set(new Set(payload.selectedOptions));
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
