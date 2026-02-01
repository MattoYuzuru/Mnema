import { Component, EventEmitter, Input, OnInit, Output, computed, signal } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { AiApiService } from '../../core/services/ai-api.service';
import { AiJobResponse, AiProviderCredential } from '../../core/models/ai.models';
import { ButtonComponent } from '../../shared/components/button.component';
import { UserCardDTO } from '../../core/models/user-card.models';
import { CardTemplateDTO, FieldTemplateDTO } from '../../core/models/template.models';
import { TranslatePipe } from '../../shared/pipes/translate.pipe';

interface CardAuditItem {
    field?: string;
    message?: string;
    suggestion?: string;
    severity?: string;
}

interface CardAuditSummary {
    summary?: string;
    items?: CardAuditItem[];
}

@Component({
    selector: 'app-ai-enhance-card-modal',
    standalone: true,
    imports: [NgIf, NgFor, FormsModule, ButtonComponent, TranslatePipe],
    template: `
    <div class="modal-overlay" (click)="close()">
      <div class="modal-content ai-modal" (click)="$event.stopPropagation()">
        <div class="modal-header">
          <h2>{{ 'cardEnhance.title' | translate }}</h2>
          <button class="close-btn" (click)="close()">&times;</button>
        </div>

        <div class="modal-body">
          <p class="modal-hint">{{ 'cardEnhance.subtitle' | translate }}</p>

          <div class="form-grid">
            <div class="form-field">
              <label for="ai-card-enhance-provider">{{ 'cardEnhance.provider' | translate }}</label>
              <select
                id="ai-card-enhance-provider"
                [ngModel]="selectedCredentialId()"
                (ngModelChange)="onProviderChange($event)"
                [disabled]="loadingProviders() || providerKeys().length === 0"
              >
                <option [ngValue]="''">{{ 'cardEnhance.selectKey' | translate }}</option>
                <option *ngFor="let key of providerKeys(); trackBy: trackProvider" [ngValue]="key.id">
                  {{ key.provider }}{{ key.alias ? ' · ' + key.alias : '' }}
                </option>
              </select>
              <p *ngIf="!loadingProviders() && providerKeys().length === 0" class="field-hint">
                {{ 'cardEnhance.noKeys' | translate }}
              </p>
            </div>
          </div>

          <div class="missing-panel" *ngIf="missingFields().length > 0">
            <label class="grid-label">{{ 'cardEnhance.missingFields' | translate }}</label>
            <div class="missing-list">
              <div class="missing-row" *ngFor="let field of missingFields(); trackBy: trackField">
                <span class="missing-label">{{ field.label || field.name }}</span>
              </div>
            </div>
            <app-button
              variant="secondary"
              size="sm"
              (click)="runFillMissingFields()"
              [disabled]="runningFill() || !selectedCredentialId()"
            >
              {{ runningFill() ? ('cardEnhance.filling' | translate) : ('cardEnhance.fillMissing' | translate) }}
            </app-button>
          </div>

          <div class="audit-panel">
            <label class="grid-label">{{ 'cardEnhance.auditTitle' | translate }}</label>
            <app-button
              variant="primary"
              size="sm"
              (click)="runAudit()"
              [disabled]="runningAudit() || !selectedCredentialId()"
            >
              {{ auditSummary() ? ('cardEnhance.runAgain' | translate) : ('cardEnhance.runAudit' | translate) }}
            </app-button>
            <div *ngIf="auditUpdatedAt()" class="field-hint">
              {{ 'cardEnhance.lastAudit' | translate }} {{ auditUpdatedAt() }}
            </div>
            <div *ngIf="auditError()" class="error-state" role="alert">
              {{ auditError() }}
            </div>
            <div *ngIf="auditSummary()" class="audit-results">
              <p class="audit-summary" *ngIf="auditSummary()?.summary">{{ auditSummary()?.summary }}</p>
              <div class="audit-item" *ngFor="let item of auditSummary()?.items || []">
                <div class="audit-field" *ngIf="item.field">{{ item.field }}</div>
                <div class="audit-message">{{ item.message }}</div>
                <div class="audit-suggestion" *ngIf="item.suggestion">{{ item.suggestion }}</div>
              </div>
            </div>
          </div>
        </div>

        <div class="modal-footer">
          <app-button variant="ghost" (click)="close()">{{ 'cardEnhance.close' | translate }}</app-button>
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
      }

      .modal-content {
        width: min(720px, 96vw);
        max-height: 92vh;
        overflow: auto;
        background: var(--color-card-background);
        border-radius: var(--border-radius-lg);
        border: 1px solid var(--glass-border);
        box-shadow: var(--shadow-soft);
      }

      .modal-header {
        display: flex;
        align-items: center;
        justify-content: space-between;
        padding: var(--spacing-lg);
        border-bottom: 1px solid var(--glass-border);
      }

      .modal-header h2 {
        margin: 0;
      }

      .modal-body {
        padding: var(--spacing-lg);
        display: grid;
        gap: var(--spacing-lg);
      }

      .modal-footer {
        display: flex;
        justify-content: flex-end;
        padding: var(--spacing-lg);
        border-top: 1px solid var(--glass-border);
      }

      .modal-hint {
        margin: 0;
        color: var(--color-text-secondary);
      }

      .form-grid {
        display: grid;
        gap: var(--spacing-md);
      }

      .form-field {
        display: flex;
        flex-direction: column;
        gap: var(--spacing-xs);
      }

      .form-field select {
        padding: var(--spacing-sm);
        border-radius: var(--border-radius-md);
        border: 1px solid var(--border-color);
        background: var(--color-background);
        color: var(--color-text-primary);
      }

      .missing-panel,
      .audit-panel {
        display: grid;
        gap: var(--spacing-sm);
        padding: var(--spacing-md);
        border-radius: var(--border-radius-lg);
        border: 1px solid var(--glass-border);
        background: var(--color-card-background);
      }

      .missing-list {
        display: grid;
        gap: var(--spacing-xs);
      }

      .missing-row {
        display: flex;
        justify-content: space-between;
        padding: var(--spacing-xs) 0;
      }

      .missing-label {
        font-weight: 600;
      }

      .grid-label {
        font-weight: 600;
      }

      .field-hint {
        margin: 0;
        font-size: 0.85rem;
        color: var(--color-text-secondary);
      }

      .audit-results {
        display: grid;
        gap: var(--spacing-sm);
      }

      .audit-summary {
        margin: 0;
        font-weight: 600;
      }

      .audit-item {
        padding: var(--spacing-sm);
        border-radius: var(--border-radius-md);
        border: 1px solid var(--glass-border);
        background: var(--color-background);
      }

      .audit-field {
        font-weight: 600;
        margin-bottom: var(--spacing-xs);
      }

      .audit-message {
        color: var(--color-text-primary);
      }

      .audit-suggestion {
        margin-top: var(--spacing-xs);
        color: var(--color-text-secondary);
      }

      .error-state {
        color: #dc2626;
      }
    `]
})
export class AiEnhanceCardModalComponent implements OnInit {
    @Input() userDeckId = '';
    @Input() deckName = '';
    @Input() deckDescription = '';
    @Input() card: UserCardDTO | null = null;
    @Input() template: CardTemplateDTO | null = null;
    @Output() closed = new EventEmitter<void>();

    providerKeys = signal<AiProviderCredential[]>([]);
    selectedCredentialId = signal('');
    loadingProviders = signal(false);

    runningAudit = signal(false);
    runningFill = signal(false);
    auditError = signal('');
    auditSummary = signal<CardAuditSummary | null>(null);
    auditUpdatedAt = signal<string | null>(null);

    private storageKey = '';

    readonly missingFields = computed(() => {
        if (!this.card || !this.template) return [] as FieldTemplateDTO[];
        return (this.template.fields || []).filter(field =>
            this.isTextField(field.fieldType) && this.isMissingField(field.name)
        );
    });

    constructor(private aiApi: AiApiService) {}

    ngOnInit(): void {
        this.storageKey = this.card?.userCardId
            ? `mnema_ai_card_audit:${this.userDeckId}:${this.card.userCardId}`
            : '';
        this.restoreAudit();
        this.loadProviders();
    }

    close(): void {
        this.closed.emit();
    }

    trackProvider(_: number, key: AiProviderCredential): string {
        return key.id;
    }

    trackField(_: number, field: FieldTemplateDTO): string {
        return field.fieldId;
    }

    onProviderChange(value: string): void {
        this.selectedCredentialId.set(value);
    }

    runAudit(): void {
        if (!this.card || !this.selectedCredentialId()) return;
        this.runningAudit.set(true);
        this.auditError.set('');
        const requestId = this.generateRequestId();
        this.aiApi.createJob({
            requestId,
            deckId: this.userDeckId,
            type: 'generic',
            params: {
                providerCredentialId: this.selectedCredentialId(),
                mode: 'card_audit',
                cardId: this.card.userCardId
            }
        }).subscribe({
            next: job => this.pollJob(job, 'audit'),
            error: err => {
                this.runningAudit.set(false);
                this.auditError.set(err?.error?.message || 'Failed to start audit');
            }
        });
    }

    runFillMissingFields(): void {
        if (!this.card || !this.selectedCredentialId()) return;
        const missing = this.missingFields().map(field => field.name);
        if (missing.length === 0) return;
        this.runningFill.set(true);
        this.auditError.set('');
        const requestId = this.generateRequestId();
        this.aiApi.createJob({
            requestId,
            deckId: this.userDeckId,
            type: 'generic',
            params: {
                providerCredentialId: this.selectedCredentialId(),
                mode: 'card_missing_fields',
                cardId: this.card.userCardId,
                fields: missing
            }
        }).subscribe({
            next: job => this.pollJob(job, 'fill'),
            error: err => {
                this.runningFill.set(false);
                this.auditError.set(err?.error?.message || 'Failed to start fill');
            }
        });
    }

    private pollJob(job: AiJobResponse, action: 'audit' | 'fill'): void {
        const poll = () => {
            this.aiApi.getJob(job.jobId).subscribe({
                next: updated => {
                    if (updated.status === 'completed') {
                        this.aiApi.getJobResult(job.jobId).subscribe({
                            next: result => {
                                if (action === 'audit') {
                                    this.applyAuditResult(result?.resultSummary);
                                    this.runningAudit.set(false);
                                } else {
                                    this.runningFill.set(false);
                                }
                            },
                            error: () => {
                                if (action === 'audit') {
                                    this.runningAudit.set(false);
                                    this.auditError.set('Failed to load audit results');
                                } else {
                                    this.runningFill.set(false);
                                }
                            }
                        });
                        return;
                    }
                    if (updated.status === 'failed' || updated.status === 'canceled') {
                        if (action === 'audit') {
                            this.runningAudit.set(false);
                            this.auditError.set('Audit failed');
                        } else {
                            this.runningFill.set(false);
                            this.auditError.set('Fill missing fields failed');
                        }
                        return;
                    }
                    setTimeout(poll, 2000);
                },
                error: () => {
                    if (action === 'audit') {
                        this.runningAudit.set(false);
                        this.auditError.set('Audit failed');
                    } else {
                        this.runningFill.set(false);
                        this.auditError.set('Fill missing fields failed');
                    }
                }
            });
        };
        setTimeout(poll, 1500);
    }

    private applyAuditResult(raw: unknown): void {
        const result = raw && typeof raw === 'object' ? raw as Record<string, unknown> : {};
        const aiSummary = (result['aiSummary'] as Record<string, unknown> | undefined) || result;
        const summary: CardAuditSummary = {
            summary: typeof aiSummary['summary'] === 'string' ? aiSummary['summary'] as string : undefined,
            items: Array.isArray(aiSummary['items']) ? aiSummary['items'] as CardAuditItem[] : []
        };
        this.auditSummary.set(summary);
        const now = new Date();
        this.auditUpdatedAt.set(now.toLocaleString());
        this.persistAudit();
    }

    private persistAudit(): void {
        if (!this.storageKey) return;
        try {
            const payload = {
                updatedAt: this.auditUpdatedAt(),
                summary: this.auditSummary()
            };
            localStorage.setItem(this.storageKey, JSON.stringify(payload));
        } catch {
        }
    }

    private restoreAudit(): void {
        if (!this.storageKey) return;
        try {
            const raw = localStorage.getItem(this.storageKey);
            if (!raw) return;
            const payload = JSON.parse(raw);
            if (payload?.summary) {
                this.auditSummary.set(payload.summary as CardAuditSummary);
            }
            if (payload?.updatedAt) {
                this.auditUpdatedAt.set(payload.updatedAt as string);
            }
        } catch {
        }
    }

    private loadProviders(): void {
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

    private isTextField(fieldType: string): boolean {
        return ['text', 'rich_text', 'markdown', 'cloze'].includes(fieldType);
    }

    private isMissingField(fieldName: string): boolean {
        if (!this.card || !this.card.effectiveContent) return true;
        const value = this.card.effectiveContent[fieldName];
        if (typeof value === 'string') {
            return value.trim().length === 0;
        }
        return value === null || value === undefined;
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
