import { Component, EventEmitter, Input, OnInit, Output, computed, signal } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { AiApiService } from '../../core/services/ai-api.service';
import { CardApiService } from '../../core/services/card-api.service';
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
                class="glass-select"
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
            <div class="missing-toggles">
              <label class="toggle">
                <input
                  type="checkbox"
                  [checked]="ttsEnabled()"
                  (change)="onTtsEnabledChange($any($event.target).checked)"
                  [disabled]="!ttsSupported() || missingAudioFields().length === 0"
                />
                <span>Generate audio</span>
              </label>
              <label class="toggle">
                <input
                  type="checkbox"
                  [checked]="imageEnabled()"
                  (change)="onImageEnabledChange($any($event.target).checked)"
                  [disabled]="!imageSupported() || missingImageFields().length === 0"
                />
                <span>Generate images</span>
              </label>
              <label class="toggle">
                <input
                  type="checkbox"
                  [checked]="videoEnabled()"
                  (change)="onVideoEnabledChange($any($event.target).checked)"
                  [disabled]="!videoSupported() || missingVideoFields().length === 0"
                />
                <span>Generate video / GIF</span>
              </label>
            </div>
            <div *ngIf="missingAudioFields().length > 0 && !ttsSupported()" class="field-hint">
              {{ 'cardEnhance.audioUnavailable' | translate }}
            </div>
            <div class="missing-list">
              <div class="missing-row" *ngFor="let field of missingFields(); trackBy: trackField">
                <span class="missing-label">{{ field.label || field.name }}</span>
                <span class="missing-type">{{ fieldTypeLabel(field.fieldType) }}</span>
              </div>
            </div>
            <div *ngIf="missingAudioFields().length > 0 && ttsEnabled() && ttsSupported()" class="form-grid tts-form">
              <div class="form-field">
                <label for="ai-card-voice">{{ 'cardEnhance.voice' | translate }}</label>
                <select
                  id="ai-card-voice"
                  class="glass-select"
                  [ngModel]="ttsVoicePreset()"
                  (ngModelChange)="onTtsVoicePresetChange($event)"
                >
                  <option *ngFor="let voice of voiceOptions()" [ngValue]="voice">{{ voiceLabel(voice) }}</option>
                </select>
              </div>
              <div class="form-field" *ngIf="ttsVoicePreset() === 'custom'">
                <label for="ai-card-voice-custom">{{ 'cardEnhance.customVoice' | translate }}</label>
                <input
                  id="ai-card-voice-custom"
                  type="text"
                  [ngModel]="ttsVoiceCustom()"
                  (ngModelChange)="onTtsVoiceCustomChange($event)"
                  placeholder="voice-name"
                />
              </div>
            </div>
            <div *ngIf="missingImageFields().length > 0 && imageEnabled() && imageSupported()" class="form-grid tts-form">
              <div class="form-field">
                <label for="ai-card-image-model">Image model</label>
                <select
                  id="ai-card-image-model"
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
                <label for="ai-card-image-size">Size</label>
                <input
                  id="ai-card-image-size"
                  type="text"
                  [ngModel]="imageSize()"
                  (ngModelChange)="onImageSizeChange($event)"
                  placeholder="1024x1024"
                />
              </div>
            </div>
            <div *ngIf="missingVideoFields().length > 0 && videoEnabled() && videoSupported()" class="form-grid tts-form">
              <div class="form-field">
                <label for="ai-card-video-model">Video model</label>
                <select
                  id="ai-card-video-model"
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

      .form-field select:not(.glass-select) {
        padding: var(--spacing-sm);
        border-radius: var(--border-radius-md);
        border: 1px solid var(--border-color);
        background: var(--color-background);
        color: var(--color-text-primary);
      }

      .form-field input {
        padding: var(--spacing-sm);
        border-radius: var(--border-radius-md);
        border: 1px solid var(--border-color);
        background: var(--color-background);
        color: var(--color-text-primary);
      }

      .tts-form {
        margin-top: var(--spacing-xs);
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

      .missing-toggles {
        display: flex;
        flex-wrap: wrap;
        gap: var(--spacing-md);
      }

      .toggle {
        display: inline-flex;
        align-items: center;
        gap: var(--spacing-xs);
        font-weight: 600;
      }

      .missing-row {
        display: flex;
        justify-content: space-between;
        gap: var(--spacing-sm);
        padding: var(--spacing-xs) 0;
      }

      .missing-label {
        font-weight: 600;
      }

      .missing-type {
        font-size: 0.8rem;
        color: var(--color-text-secondary);
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
    @Output() cardUpdated = new EventEmitter<UserCardDTO>();
    @Output() closed = new EventEmitter<void>();

    providerKeys = signal<AiProviderCredential[]>([]);
    selectedCredentialId = signal('');
    loadingProviders = signal(false);

    runningAudit = signal(false);
    runningFill = signal(false);
    auditError = signal('');
    auditSummary = signal<CardAuditSummary | null>(null);
    auditUpdatedAt = signal<string | null>(null);

    ttsEnabled = signal(true);
    ttsVoicePreset = signal('alloy');
    ttsVoiceCustom = signal('');
    imageEnabled = signal(true);
    videoEnabled = signal(false);
    imageModel = signal('');
    imageModelCustom = signal('');
    imageSize = signal('1024x1024');
    imageFormat = signal('png');
    videoModel = signal('');
    videoModelCustom = signal('');
    videoResolution = signal('1280x720');
    videoDurationSeconds = signal(5);
    videoFormat = signal('mp4');

    private storageKey = '';

    readonly selectedProvider = computed(() => {
        const selectedId = this.selectedCredentialId();
        if (!selectedId) return '';
        const provider = this.providerKeys().find(item => item.id === selectedId)?.provider;
        return this.normalizeProvider(provider);
    });
    readonly ttsSupported = computed(() => ['openai', 'gemini'].includes(this.selectedProvider()));
    readonly imageSupported = computed(() => ['openai', 'gemini'].includes(this.selectedProvider()));
    readonly videoSupported = computed(() => this.selectedProvider() === 'openai');
    readonly imageModelOptions = computed(() => this.resolveImageModelOptions(this.selectedProvider()));
    readonly videoModelOptions = computed(() => this.resolveVideoModelOptions(this.selectedProvider()));
    readonly voiceOptions = computed(() => {
        const provider = this.selectedProvider();
        if (provider === 'gemini') {
            return [...this.geminiVoices, 'custom'];
        }
        if (provider === 'openai') {
            return [...this.openAiVoices, 'custom'];
        }
        return ['custom'];
    });

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
        'pulcherrima',
        'puck',
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

    constructor(private aiApi: AiApiService, private cardApi: CardApiService) {}

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
        this.syncVoicePreset();
        this.ensureDefaultMediaModels();
        if (!this.ttsSupported()) {
            this.ttsEnabled.set(false);
        }
        if (!this.imageSupported()) {
            this.imageEnabled.set(false);
        }
        if (!this.videoSupported()) {
            this.videoEnabled.set(false);
        }
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

    onTtsEnabledChange(enabled: boolean): void {
        this.ttsEnabled.set(enabled);
    }

    onImageEnabledChange(enabled: boolean): void {
        this.imageEnabled.set(enabled);
    }

    onVideoEnabledChange(enabled: boolean): void {
        this.videoEnabled.set(enabled);
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

    onVideoModelChange(value: string): void {
        this.videoModel.set(value);
        if (value !== 'custom') {
            this.videoModelCustom.set('');
        }
    }

    onVideoModelCustomChange(value: string): void {
        this.videoModelCustom.set(value);
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
        const missing = this.missingFields()
            .filter(field => this.isFieldSelectedForFill(field))
            .map(field => field.name);
        if (missing.length === 0) return;
        this.runningFill.set(true);
        this.auditError.set('');
        const requestId = this.generateRequestId();
        const tts = this.buildTtsParams(missing);
        const image = this.buildImageParams(missing);
        const video = this.buildVideoParams(missing);
        this.aiApi.createJob({
            requestId,
            deckId: this.userDeckId,
            type: 'generic',
            params: {
                providerCredentialId: this.selectedCredentialId(),
                mode: 'card_missing_fields',
                cardId: this.card.userCardId,
                fields: missing,
                ...(tts ? { tts } : {}),
                ...(image ? { image } : {}),
                ...(video ? { video } : {})
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
                                } else if (action === 'fill') {
                                    this.runningFill.set(false);
                                    this.refreshCard();
                                }
                            },
                            error: () => {
                                if (action === 'audit') {
                                    this.runningAudit.set(false);
                                    this.auditError.set('Failed to load audit results');
                                } else if (action === 'fill') {
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
                        } else if (action === 'fill') {
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
                    } else if (action === 'fill') {
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
                this.ensureDefaultMediaModels();
                this.syncVoicePreset();
                this.loadingProviders.set(false);
            },
            error: () => {
                this.loadingProviders.set(false);
            }
        });
    }

    missingFields(): FieldTemplateDTO[] {
        if (!this.card || !this.template) return [];
        return (this.template.fields || []).filter(field =>
            this.isSupportedFieldType(field.fieldType) && this.isMissingField(field)
        );
    }

    missingAudioFields(): FieldTemplateDTO[] {
        if (!this.card || !this.template) return [];
        return (this.template.fields || []).filter(field =>
            field.fieldType === 'audio' && this.isMissingMediaField(field.name)
        );
    }

    missingImageFields(): FieldTemplateDTO[] {
        if (!this.card || !this.template) return [];
        return (this.template.fields || []).filter(field =>
            field.fieldType === 'image' && this.isMissingMediaField(field.name)
        );
    }

    missingVideoFields(): FieldTemplateDTO[] {
        if (!this.card || !this.template) return [];
        return (this.template.fields || []).filter(field =>
            field.fieldType === 'video' && this.isMissingMediaField(field.name)
        );
    }

    fieldTypeLabel(fieldType: string): string {
        switch (fieldType) {
            case 'audio':
                return 'Audio';
            case 'image':
                return 'Image';
            case 'video':
                return 'Video';
            case 'markdown':
                return 'Markdown';
            case 'rich_text':
                return 'Rich text';
            case 'cloze':
                return 'Cloze';
            default:
                return 'Text';
        }
    }

    voiceLabel(voice: string): string {
        if (!voice) return '';
        if (voice === 'custom') return 'Custom';
        return voice.charAt(0).toUpperCase() + voice.slice(1);
    }

    private isTextField(fieldType: string): boolean {
        return ['text', 'rich_text', 'markdown', 'cloze'].includes(fieldType);
    }

    private isSupportedFieldType(fieldType: string): boolean {
        return ['text', 'rich_text', 'markdown', 'cloze', 'audio', 'image', 'video'].includes(fieldType);
    }

    private isMissingTextField(fieldName: string): boolean {
        if (!this.card || !this.card.effectiveContent) return true;
        const value = this.card.effectiveContent[fieldName];
        if (typeof value === 'string') {
            return value.trim().length === 0;
        }
        return value === null || value === undefined;
    }

    private isMissingField(field: FieldTemplateDTO): boolean {
        if (this.isTextField(field.fieldType)) {
            return this.isMissingTextField(field.name);
        }
        return this.isMissingMediaField(field.name);
    }

    private isMissingMediaField(fieldName: string): boolean {
        if (!this.card || !this.card.effectiveContent) return true;
        return this.isMissingMediaValue(this.card.effectiveContent[fieldName]);
    }

    private isMissingMediaValue(value: unknown): boolean {
        if (value === null || value === undefined) {
            return true;
        }
        if (typeof value === 'string') {
            return value.trim().length === 0;
        }
        if (typeof value === 'object') {
            const mediaId = (value as { mediaId?: string | null }).mediaId;
            return !mediaId || mediaId.trim().length === 0;
        }
        return false;
    }

    private normalizeProvider(provider?: string | null): string {
        if (!provider) return '';
        const normalized = provider.trim().toLowerCase();
        if (normalized === 'claude' || normalized.includes('anthropic')) return 'anthropic';
        if (normalized.includes('openai')) return 'openai';
        if (normalized.includes('gemini') || normalized.includes('google')) return 'gemini';
        return normalized;
    }

    private syncVoicePreset(): void {
        const options = this.voiceOptions();
        if (options.length === 0) return;
        if (options.includes(this.ttsVoicePreset())) return;
        const fallback = options[0];
        this.ttsVoicePreset.set(fallback);
        if (fallback !== 'custom') {
            this.ttsVoiceCustom.set('');
        }
    }

    private resolveVoice(): string {
        if (this.ttsVoicePreset() === 'custom') {
            return this.ttsVoiceCustom().trim();
        }
        return this.ttsVoicePreset();
    }

    private resolveAudioFormat(): string {
        return this.selectedProvider() === 'gemini' ? 'wav' : 'mp3';
    }

    private resolveImageModelOptions(provider: string): string[] {
        if (provider === 'openai') {
            return ['gpt-image-1-mini', 'gpt-image-1', 'custom'];
        }
        if (provider === 'gemini') {
            return ['gemini-2.5-flash-image', 'gemini-3-pro-image-preview', 'custom'];
        }
        return ['custom'];
    }

    private resolveVideoModelOptions(provider: string): string[] {
        if (provider === 'openai') {
            return ['sora-2', 'custom'];
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

    private isFieldSelectedForFill(field: FieldTemplateDTO): boolean {
        if (field.fieldType === 'audio') {
            return this.ttsEnabled() && this.ttsSupported();
        }
        if (field.fieldType === 'image') {
            return this.imageEnabled() && this.imageSupported();
        }
        if (field.fieldType === 'video') {
            return this.videoEnabled() && this.videoSupported();
        }
        return true;
    }

    private buildTtsParams(missingFields: string[]): Record<string, unknown> | null {
        if (!this.ttsEnabled() || !this.ttsSupported()) {
            return null;
        }
        const audioFields = this.missingAudioFields().map(field => field.name);
        if (audioFields.length === 0) {
            return null;
        }
        if (!audioFields.some(field => missingFields.includes(field))) {
            return null;
        }
        return {
            enabled: true,
            voice: this.resolveVoice(),
            format: this.resolveAudioFormat()
        };
    }

    private buildImageParams(missingFields: string[]): Record<string, unknown> | null {
        if (!this.imageEnabled() || !this.imageSupported()) {
            return null;
        }
        const imageFields = this.missingImageFields().map(field => field.name);
        if (!imageFields.some(field => missingFields.includes(field))) {
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

    private buildVideoParams(missingFields: string[]): Record<string, unknown> | null {
        if (!this.videoEnabled() || !this.videoSupported()) {
            return null;
        }
        const videoFields = this.missingVideoFields().map(field => field.name);
        if (!videoFields.some(field => missingFields.includes(field))) {
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

    private refreshCard(): void {
        if (!this.card) return;
        this.cardApi.getUserCard(this.userDeckId, this.card.userCardId).subscribe({
            next: updated => {
                this.card = updated;
                this.cardUpdated.emit(updated);
            },
            error: () => {
            }
        });
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
