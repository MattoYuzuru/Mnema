import { Component, EventEmitter, Input, OnChanges, Output, SimpleChanges } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ButtonComponent } from './button.component';
import { TranslatePipe } from '../pipes/translate.pipe';
import { ReportReason } from '../../core/services/report-api.service';

interface ReasonOption {
    value: ReportReason;
    titleKey: string;
    descriptionKey: string;
}

@Component({
    selector: 'app-report-content-modal',
    standalone: true,
    imports: [NgIf, NgFor, FormsModule, ButtonComponent, TranslatePipe],
    template: `
      <div *ngIf="visible" class="modal-overlay" (click)="dismiss()">
        <div class="modal-shell glass-strong" (click)="$event.stopPropagation()" role="dialog" aria-modal="true">
          <div class="modal-head">
            <div>
              <div class="eyebrow">{{ 'reports.modalEyebrow' | translate }}</div>
              <h2>{{ 'reports.modalTitle' | translate }}</h2>
              <p>{{ subject }}</p>
            </div>
            <button class="close-btn" type="button" (click)="dismiss()" [attr.aria-label]="'reports.cancel' | translate">×</button>
          </div>

          <div class="modal-body mn-scrollbar">
            <p class="modal-hint">{{ 'reports.modalHint' | translate }}</p>

            <div class="reason-grid">
              <button
                *ngFor="let reason of reasonOptions"
                type="button"
                class="reason-card"
                [class.active]="selectedReason === reason.value"
                (click)="selectReason(reason.value)"
              >
                <span class="reason-check" aria-hidden="true">{{ selectedReason === reason.value ? '!' : '' }}</span>
                <span class="reason-copy">
                  <strong>{{ reason.titleKey | translate }}</strong>
                  <small>{{ reason.descriptionKey | translate }}</small>
                </span>
              </button>
            </div>

            <label class="details-block" *ngIf="selectedReason === 'OTHER' || details">
              <span>{{ 'reports.detailsLabel' | translate }}</span>
              <textarea
                class="mn-scrollbar"
                [(ngModel)]="details"
                rows="4"
                [placeholder]="'reports.detailsPlaceholder' | translate"
                [attr.maxlength]="maxDetailsLength"
              ></textarea>
            </label>

            <p *ngIf="errorKey" class="error-text">{{ errorKey | translate }}</p>
          </div>

          <div class="modal-actions">
            <app-button variant="ghost" (click)="dismiss()">{{ 'reports.cancel' | translate }}</app-button>
            <app-button variant="primary" [disabled]="submitting" (click)="submit()">
              {{ submitting ? ('reports.submitting' | translate) : ('reports.submit' | translate) }}
            </app-button>
          </div>
        </div>
      </div>
    `,
    styles: [`
      .modal-overlay {
        position: fixed;
        inset: 0;
        z-index: 1400;
        display: grid;
        place-items: center;
        padding: var(--spacing-md);
        background:
          radial-gradient(circle at top, rgba(56, 189, 248, 0.14), transparent 42%),
          rgba(10, 15, 22, 0.58);
        backdrop-filter: blur(14px);
      }

      .modal-shell {
        width: min(780px, 100%);
        border-radius: calc(var(--border-radius-xl) + 0.25rem);
        overflow: hidden;
        border: 1px solid rgba(255, 255, 255, 0.12);
        background:
          linear-gradient(180deg, rgba(255, 255, 255, 0.14), rgba(255, 255, 255, 0.06)),
          var(--color-card-background);
        box-shadow: 0 22px 72px rgba(15, 23, 42, 0.35);
      }

      .modal-head,
      .modal-actions {
        display: flex;
        justify-content: space-between;
        gap: var(--spacing-md);
        padding: var(--spacing-lg);
      }

      .modal-head {
        align-items: flex-start;
        border-bottom: 1px solid rgba(255, 255, 255, 0.08);
      }

      .eyebrow {
        margin-bottom: var(--spacing-xs);
        font-size: 0.72rem;
        text-transform: uppercase;
        letter-spacing: 0.14em;
        color: var(--color-text-muted);
      }

      .modal-head h2 {
        margin: 0 0 var(--spacing-xs) 0;
      }

      .modal-head p,
      .modal-hint {
        margin: 0;
        color: var(--color-text-secondary);
      }

      .modal-body {
        display: flex;
        flex-direction: column;
        gap: var(--spacing-md);
        padding: var(--spacing-lg);
      }

      .reason-grid {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(210px, 1fr));
        gap: var(--spacing-sm);
      }

      .reason-card {
        display: flex;
        gap: var(--spacing-sm);
        align-items: flex-start;
        text-align: left;
        padding: 1rem;
        border-radius: var(--border-radius-lg);
        border: 1px solid rgba(255, 255, 255, 0.08);
        background: rgba(255, 255, 255, 0.05);
        color: var(--color-text-primary);
        cursor: pointer;
        transition: transform 0.2s ease, border-color 0.2s ease, background 0.2s ease;
      }

      .reason-card:hover {
        transform: translateY(-1px);
        border-color: rgba(125, 211, 252, 0.35);
      }

      .reason-card.active {
        background: linear-gradient(135deg, rgba(56, 189, 248, 0.16), rgba(16, 185, 129, 0.12));
        border-color: rgba(56, 189, 248, 0.5);
      }

      .reason-check {
        width: 1.5rem;
        height: 1.5rem;
        display: inline-grid;
        place-items: center;
        border-radius: 999px;
        background: rgba(15, 23, 42, 0.16);
        border: 1px solid rgba(255, 255, 255, 0.12);
        color: var(--color-primary-accent);
        flex-shrink: 0;
        font-weight: 700;
      }

      .reason-copy {
        display: flex;
        flex-direction: column;
        gap: 0.2rem;
      }

      .reason-copy strong {
        font-size: 0.95rem;
      }

      .reason-copy small {
        color: var(--color-text-secondary);
        font-size: 0.82rem;
        line-height: 1.35;
      }

      .details-block {
        display: flex;
        flex-direction: column;
        gap: var(--spacing-xs);
        color: var(--color-text-secondary);
      }

      .details-block textarea {
        min-height: 6.5rem;
        resize: vertical;
        border: 1px solid rgba(255, 255, 255, 0.1);
        border-radius: var(--border-radius-lg);
        background: rgba(255, 255, 255, 0.06);
        color: var(--color-text-primary);
        padding: 0.9rem 1rem;
      }

      .error-text {
        margin: 0;
        color: #fca5a5;
      }

      .modal-actions {
        align-items: center;
        border-top: 1px solid rgba(255, 255, 255, 0.08);
      }

      .close-btn {
        width: 2.25rem;
        height: 2.25rem;
        border: none;
        border-radius: 999px;
        background: rgba(255, 255, 255, 0.08);
        color: var(--color-text-primary);
        cursor: pointer;
        font-size: 1.35rem;
      }

      @media (max-width: 640px) {
        .reason-grid {
          grid-template-columns: 1fr;
        }

        .modal-head,
        .modal-actions {
          padding: var(--spacing-md);
        }

        .modal-body {
          padding: var(--spacing-md);
        }
      }
    `]
})
export class ReportContentModalComponent implements OnChanges {
    @Input() visible = false;
    @Input() subject = '';
    @Input() submitting = false;
    @Output() close = new EventEmitter<void>();
    @Output() submitted = new EventEmitter<{ reason: ReportReason; details: string | null }>();

    readonly maxDetailsLength = 500;
    readonly reasonOptions: ReasonOption[] = [
        {
            value: 'INAPPROPRIATE_LANGUAGE',
            titleKey: 'reports.reason.inappropriateLanguage',
            descriptionKey: 'reports.reason.inappropriateLanguageHint'
        },
        {
            value: 'OFFENSIVE_CONTENT',
            titleKey: 'reports.reason.offensiveContent',
            descriptionKey: 'reports.reason.offensiveContentHint'
        },
        {
            value: 'FACTUAL_ERROR',
            titleKey: 'reports.reason.factualError',
            descriptionKey: 'reports.reason.factualErrorHint'
        },
        {
            value: 'MISLEADING_METADATA',
            titleKey: 'reports.reason.misleadingMetadata',
            descriptionKey: 'reports.reason.misleadingMetadataHint'
        },
        {
            value: 'SPAM',
            titleKey: 'reports.reason.spam',
            descriptionKey: 'reports.reason.spamHint'
        },
        {
            value: 'BROKEN_FORMATTING',
            titleKey: 'reports.reason.brokenFormatting',
            descriptionKey: 'reports.reason.brokenFormattingHint'
        },
        {
            value: 'OTHER',
            titleKey: 'reports.reason.other',
            descriptionKey: 'reports.reason.otherHint'
        }
    ];

    selectedReason: ReportReason | null = null;
    details = '';
    errorKey = '';

    ngOnChanges(changes: SimpleChanges): void {
        if (changes['visible']?.currentValue && !changes['visible']?.previousValue) {
            this.selectedReason = null;
            this.details = '';
            this.errorKey = '';
        }
    }

    selectReason(reason: ReportReason): void {
        this.selectedReason = reason;
        if (reason !== 'OTHER' && this.errorKey === 'reports.errorOtherRequired') {
            this.errorKey = '';
        }
    }

    dismiss(): void {
        if (this.submitting) {
            return;
        }
        this.close.emit();
    }

    submit(): void {
        if (!this.selectedReason) {
            this.errorKey = 'reports.errorReasonRequired';
            return;
        }

        const normalizedDetails = this.details.trim();
        if (this.selectedReason === 'OTHER' && !normalizedDetails) {
            this.errorKey = 'reports.errorOtherRequired';
            return;
        }

        this.errorKey = '';
        this.submitted.emit({
            reason: this.selectedReason,
            details: normalizedDetails || null
        });
    }
}
