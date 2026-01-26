import { Component, EventEmitter, Input, OnInit, Output, signal } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { AiApiService } from '../../core/services/ai-api.service';
import { TemplateApiService } from '../../core/services/template-api.service';
import { AiProviderCredential } from '../../core/models/ai.models';
import { FieldTemplateDTO } from '../../core/models/template.models';
import { ButtonComponent } from '../../shared/components/button.component';

type FieldOption = { key: string; label: string; enabled: boolean };

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
                (ngModelChange)="selectedCredentialId.set($event)"
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
                (ngModelChange)="cardsCount.set($event)"
              />
            </div>

            <div class="form-field">
              <label for="ai-model">Model (optional)</label>
              <input
                id="ai-model"
                type="text"
                [ngModel]="modelName()"
                (ngModelChange)="modelName.set($event)"
                placeholder="gpt-4.1-mini"
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

          <div class="form-field">
            <label for="ai-prompt">Instructions</label>
            <textarea
              id="ai-prompt"
              rows="4"
              [ngModel]="prompt()"
              (ngModelChange)="prompt.set($event)"
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
      .error-state { margin-top: var(--spacing-md); color: var(--color-error); }
      .success-state { margin-top: var(--spacing-md); color: var(--color-success); }
      .modal-footer { display: flex; justify-content: flex-end; gap: var(--spacing-md); padding: var(--spacing-lg); border-top: 1px solid var(--glass-border); }
    `]
})
export class AiAddCardsModalComponent implements OnInit {
    @Input() userDeckId = '';
    @Input() deckName = '';
    @Input() templateId = '';
    @Output() closed = new EventEmitter<void>();

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

    fieldOptions = signal<FieldOption[]>([
        { key: 'front', label: 'Front', enabled: true },
        { key: 'back', label: 'Back', enabled: true },
        { key: 'translation', label: 'Translation', enabled: true },
        { key: 'example', label: 'Example', enabled: true },
        { key: 'audio', label: 'Audio', enabled: true },
        { key: 'image', label: 'Image', enabled: false }
    ]);

    selectedFields = signal<Set<string>>(new Set(['front', 'back']));

    constructor(private aiApi: AiApiService, private templateApi: TemplateApiService) {}

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
        this.templateApi.getTemplate(this.templateId).subscribe({
            next: template => {
                const options = (template.fields || []).map(field => this.toFieldOption(field));
                if (options.length > 0) {
                    this.fieldOptions.set(options);
                    const initial = options.filter(option => option.enabled).slice(0, 2).map(option => option.key);
                    if (initial.length > 0) {
                        this.selectedFields.set(new Set(initial));
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
                mode: 'generate_cards'
            }
        }).subscribe({
            next: () => {
                this.creating.set(false);
                this.createSuccess.set('AI job queued. You can track it in this deck.');
            },
            error: err => {
                this.creating.set(false);
                this.createError.set(err?.error?.message || 'Failed to create AI job');
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
            enabled: this.isTextField(field.fieldType)
        };
    }

    private isTextField(fieldType: string): boolean {
        return ['text', 'rich_text', 'markdown', 'cloze'].includes(fieldType);
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
