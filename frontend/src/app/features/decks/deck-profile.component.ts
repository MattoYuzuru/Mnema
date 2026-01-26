import { Component, OnInit, OnDestroy, ElementRef, ViewChild } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { DatePipe, NgIf, NgFor } from '@angular/common';
import { ReactiveFormsModule, FormsModule, FormBuilder, FormGroup, Validators } from '@angular/forms';
import { forkJoin } from 'rxjs';
import { map, switchMap } from 'rxjs/operators';
import { DeckApiService } from '../../core/services/deck-api.service';
import { PublicDeckApiService } from '../../core/services/public-deck-api.service';
import { TemplateApiService } from '../../core/services/template-api.service';
import { ReviewApiService } from '../../core/services/review-api.service';
import { ImportApiService } from '../../core/services/import-api.service';
import { MediaApiService } from '../../core/services/media-api.service';
import { AiApiService } from '../../core/services/ai-api.service';
import { UserApiService } from '../../user-api.service';
import { UserDeckDTO } from '../../core/models/user-deck.models';
import { PublicDeckDTO } from '../../core/models/public-deck.models';
import { ReviewDeckAlgorithmResponse } from '../../core/models/review.models';
import { ImportJobResponse, ImportSourceType } from '../../core/models/import.models';
import { AiJobResponse, AiJobStatus, AiJobType } from '../../core/models/ai.models';
import { MemoryTipLoaderComponent } from '../../shared/components/memory-tip-loader.component';
import { ButtonComponent } from '../../shared/components/button.component';
import { AddCardsModalComponent } from './add-cards-modal.component';
import { AiAddCardsModalComponent } from './ai-add-cards-modal.component';
import { AiEnhanceDeckModalComponent } from './ai-enhance-deck-modal.component';
import { ConfirmationDialogComponent } from '../../shared/components/confirmation-dialog.component';
import { InputComponent } from '../../shared/components/input.component';
import { TranslatePipe } from '../../shared/pipes/translate.pipe';
import { ImportDeckModalComponent } from '../import/import-deck-modal.component';
import { markdownToHtml } from '../../shared/utils/markdown.util';
import { I18nService } from '../../core/services/i18n.service';

@Component({
    selector: 'app-deck-profile',
    standalone: true,
    imports: [NgIf, NgFor, DatePipe, ReactiveFormsModule, FormsModule, MemoryTipLoaderComponent, ButtonComponent, AddCardsModalComponent, AiAddCardsModalComponent, AiEnhanceDeckModalComponent, ConfirmationDialogComponent, InputComponent, ImportDeckModalComponent, TranslatePipe],
    template: `
    <app-memory-tip-loader *ngIf="loading"></app-memory-tip-loader>

    <div *ngIf="!loading && deck" class="deck-profile">
      <header class="deck-header">
        <h1>{{ deck.displayName }}</h1>
        <div class="deck-description" [innerHTML]="formatDescription(deck.displayDescription)"></div>
      </header>

      <div class="deck-meta">
        <div class="meta-item">
          <span class="meta-label">{{ 'deckProfile.algorithm' | translate }}:</span>
          <span class="meta-value">{{ deck.algorithmId }}</span>
        </div>
        <div class="meta-item">
          <span class="meta-label">{{ 'deckProfile.autoUpdate' | translate }}:</span>
          <span class="meta-value">{{ deck.autoUpdate ? ('deckProfile.yes' | translate) : ('deckProfile.no' | translate) }}</span>
        </div>
        <div class="meta-item" *ngIf="deck.publicDeckId">
          <span class="meta-label">{{ 'deckProfile.version' | translate }}:</span>
          <span class="meta-value">{{ deck.currentVersion }}<span *ngIf="latestPublicVersion !== null"> / {{ latestPublicVersion }}</span></span>
        </div>
        <div class="meta-item" *ngIf="deck.templateVersion !== null && deck.templateVersion !== undefined">
          <span class="meta-label">{{ 'deckProfile.templateVersion' | translate }}:</span>
          <span class="meta-value">{{ deck.templateVersion }}<span *ngIf="latestTemplateVersion !== null"> / {{ latestTemplateVersion }}</span></span>
        </div>
        <div class="meta-item" *ngIf="!deck.publicDeckId">
          <span class="meta-label">{{ 'deckProfile.version' | translate }}:</span>
          <span class="meta-value">{{ deck.currentVersion }}</span>
        </div>
      </div>

      <div class="deck-actions">
        <app-button variant="primary" size="md" (click)="learn()">
          {{ 'deckProfile.learn' | translate }}
        </app-button>
        <app-button variant="secondary" size="md" (click)="browse()">
          {{ 'deckProfile.browseCards' | translate }}
        </app-button>
        <app-button variant="secondary" (click)="openAddCardsChoice()">
          {{ 'deckProfile.addCards' | translate }}
        </app-button>
        <app-button variant="secondary" (click)="openAiEnhanceModal()">
          ✨ {{ 'deckProfile.aiEnhanceButton' | translate }}
        </app-button>
        <app-button variant="secondary" (click)="openExportConfirm()" [disabled]="exporting">
          {{ exporting ? ('deckProfile.exporting' | translate) : ('deckProfile.export' | translate) }}
        </app-button>
        <app-button variant="ghost" (click)="sync()" *ngIf="needsUpdate()">
          {{ 'deckProfile.sync' | translate }}
        </app-button>
        <app-button variant="ghost" (click)="syncTemplate()" *ngIf="needsTemplateUpdate()">
          {{ 'deckProfile.syncTemplate' | translate }}
        </app-button>
        <app-button variant="secondary" (click)="openEditModal()">
          {{ 'deckProfile.edit' | translate }}
        </app-button>
        <app-button variant="ghost" (click)="openDeleteConfirm()">
          {{ 'deckProfile.delete' | translate }}
        </app-button>
      </div>

      <p *ngIf="exportStatusKey" class="export-status">{{ exportStatusKey | translate }}</p>

      <section class="ai-feature-section">
        <div class="ai-feature-header">
          <h2>{{ 'deckProfile.aiFeaturesTitle' | translate }}</h2>
          <p>{{ 'deckProfile.aiFeaturesDescription' | translate }}</p>
        </div>
        <div class="ai-feature-grid">
          <button class="ai-feature-card" (click)="openAiAddModal()">
            <div class="ai-feature-icon">✨</div>
            <div>
              <h3>{{ 'deckProfile.aiAddCardsTitle' | translate }}</h3>
              <p>{{ 'deckProfile.aiAddCardsDescription' | translate }}</p>
            </div>
          </button>
          <button class="ai-feature-card" (click)="openAiEnhanceModal()">
            <div class="ai-feature-icon">🧠</div>
            <div>
              <h3>{{ 'deckProfile.aiEnhanceTitle' | translate }}</h3>
              <p>{{ 'deckProfile.aiEnhanceDescription' | translate }}</p>
            </div>
          </button>
          <div class="ai-feature-card disabled" aria-disabled="true">
            <div class="ai-feature-icon">📷</div>
            <div>
              <h3>{{ 'deckProfile.aiImportTitle' | translate }}</h3>
              <p>{{ 'deckProfile.aiImportDescription' | translate }}</p>
            </div>
          </div>
        </div>
      </section>

      <section class="ai-jobs-section">
        <div class="ai-jobs-header">
          <div>
            <h2>{{ 'deckProfile.aiJobsTitle' | translate }}</h2>
            <p>{{ 'deckProfile.aiJobsDescription' | translate }}</p>
          </div>
          <app-button variant="ghost" size="sm" (click)="refreshAiJobs()" [disabled]="aiJobsLoading">
            {{ 'deckProfile.aiJobsRefresh' | translate }}
          </app-button>
        </div>

        <div *ngIf="aiJobsLoading" class="loading-state">{{ 'deckProfile.aiJobsLoading' | translate }}</div>
        <div *ngIf="!aiJobsLoading && aiJobsError" class="error-state" role="alert">
          {{ aiJobsError | translate }}
        </div>
        <div *ngIf="!aiJobsLoading && !aiJobsError && aiJobs.length === 0" class="empty-state">
          {{ 'deckProfile.aiJobsEmpty' | translate }}
        </div>

        <div *ngIf="aiJobs.length > 0" class="ai-job-list" aria-live="polite">
          <div *ngFor="let entry of aiJobs; trackBy: trackAiJob" class="ai-job-card">
            <div class="ai-job-header">
              <div>
                <div class="ai-job-title">{{ formatAiJobType(entry.job.type) }}</div>
                <div class="ai-job-meta">
                  <span class="ai-job-status-pill"
                        [class.completed]="entry.job.status === 'completed'"
                        [class.failed]="entry.job.status === 'failed'"
                        [class.canceled]="entry.job.status === 'canceled'">
                    {{ formatStatus(entry.job.status) }}
                  </span>
                  <span class="ai-job-date">{{ entry.job.createdAt | date:'medium' }}</span>
                </div>
              </div>
              <app-button
                variant="ghost"
                size="sm"
                tone="danger"
                [disabled]="!canCancelAiJob(entry.job.status) || cancelingAiJobs.has(entry.job.jobId)"
                (click)="cancelAiJob(entry.job.jobId)"
              >
                {{ cancelingAiJobs.has(entry.job.jobId) ? ('deckProfile.aiJobsCanceling' | translate) : ('deckProfile.aiJobsCancel' | translate) }}
              </app-button>
            </div>

            <div class="ai-job-status-row">
              <span class="ai-job-progress-text">{{ entry.job.progress }}%</span>
              <div class="ai-job-progress" role="progressbar" [attr.aria-valuenow]="entry.job.progress" aria-valuemin="0" aria-valuemax="100">
                <div class="ai-job-progress-bar" [style.width.%]="entry.job.progress"></div>
              </div>
            </div>

            <div *ngIf="entry.job.status === 'failed'" class="ai-job-error" role="alert">
              {{ 'deckProfile.aiJobsFailed' | translate }}
            </div>

            <div class="ai-job-result">
              <div *ngIf="entry.resultLoading" class="loading-state">{{ 'deckProfile.aiJobsResultLoading' | translate }}</div>
              <pre *ngIf="!entry.resultLoading && entry.resultSummary" class="ai-job-result-json">{{ formatJson(entry.resultSummary) }}</pre>
              <div *ngIf="!entry.resultLoading && !entry.resultSummary && entry.job.status !== 'completed'" class="empty-state">
                {{ 'deckProfile.aiJobsResultPending' | translate }}
              </div>
            </div>
          </div>
        </div>
      </section>
    </div>

    <div *ngIf="showAddCardsChoice" class="modal-overlay" (click)="closeAddCardsChoice()">
      <div class="modal-content add-choice-modal" (click)="$event.stopPropagation()">
        <div class="modal-header">
          <h2>{{ 'deckProfile.addCardsChoiceTitle' | translate }}</h2>
          <button class="close-btn" (click)="closeAddCardsChoice()">&times;</button>
        </div>
        <div class="modal-body">
          <div class="choice-grid">
            <div class="choice-card" (click)="startManualAdd()">
              <div class="choice-icon">✍️</div>
              <h3>{{ 'deckProfile.addCardsManual' | translate }}</h3>
              <p>{{ 'deckProfile.addCardsManualDesc' | translate }}</p>
            </div>
            <div class="choice-card" (click)="startImportMerge()">
              <div class="choice-icon">⬆️</div>
              <h3>{{ 'deckProfile.addCardsImport' | translate }}</h3>
              <p>{{ 'deckProfile.addCardsImportDesc' | translate }}</p>
            </div>
            <div class="choice-card ai-choice" (click)="startAiAdd()">
              <div class="choice-icon">✨</div>
              <h3>{{ 'deckProfile.aiAddCardsTitle' | translate }}</h3>
              <p>{{ 'deckProfile.aiAddCardsDescription' | translate }}</p>
            </div>
            <div class="choice-card disabled" aria-disabled="true">
              <div class="choice-icon">📷</div>
              <h3>{{ 'deckProfile.aiImportTitle' | translate }}</h3>
              <p>{{ 'deckProfile.aiImportDescription' | translate }}</p>
            </div>
          </div>
        </div>
      </div>
    </div>

    <app-add-cards-modal
      *ngIf="showAddCards && deck"
      [userDeckId]="deck.userDeckId"
      [publicDeckId]="deck.publicDeckId"
      [templateVersion]="deck.templateVersion || null"
      (saved)="onCardsSaved()"
      (cancelled)="closeAddCards()"
    ></app-add-cards-modal>

    <app-import-deck-modal
      *ngIf="showImportModal && deck"
      mode="merge"
      [targetDeckId]="deck.userDeckId"
      (closed)="closeImportModal()"
    ></app-import-deck-modal>

    <app-ai-add-cards-modal
      *ngIf="showAiAddModal && deck"
      [userDeckId]="deck.userDeckId"
      [deckName]="deck.displayName"
      [templateId]="publicDeck?.templateId || ''"
      [templateVersion]="deck.templateVersion || null"
      (closed)="closeAiAddModal()"
    ></app-ai-add-cards-modal>

    <app-ai-enhance-deck-modal
      *ngIf="showAiEnhanceModal && deck"
      [userDeckId]="deck.userDeckId"
      [deckName]="deck.displayName"
      (closed)="closeAiEnhanceModal()"
    ></app-ai-enhance-deck-modal>

    <div *ngIf="showEditModal && deck" class="modal-overlay" (click)="closeEditModal()">
      <div class="modal-content" (click)="$event.stopPropagation()">
        <div class="modal-header">
          <h2>{{ 'deckProfile.editDeck' | translate }}</h2>
          <button class="close-btn" (click)="closeEditModal()">&times;</button>
        </div>
        <div class="modal-body">
          <form [formGroup]="editForm" class="edit-form">
            <h3 class="section-title">{{ 'deckProfile.yourDeckSettings' | translate }}</h3>
            <app-input
              [label]="('deckProfile.displayName' | translate) + ' *'"
              formControlName="displayName"
              [hasError]="editForm.get('displayName')?.invalid && editForm.get('displayName')?.touched || false"
              [errorMessage]="displayNameErrorMessage()"
              [maxLength]="maxDeckName"
            ></app-input>
            <div class="markdown-field">
              <label class="markdown-label">{{ 'deckProfile.description' | translate }}</label>
              <div class="markdown-toolbar">
                <button type="button" class="toolbar-button" (click)="applyMarkdown('displayDescription', '**', '**')" [attr.title]="'wizard.markdownBold' | translate" [attr.aria-label]="'wizard.markdownBold' | translate">B</button>
                <button type="button" class="toolbar-button" (click)="applyMarkdown('displayDescription', '*', '*')" [attr.title]="'wizard.markdownItalic' | translate" [attr.aria-label]="'wizard.markdownItalic' | translate">I</button>
                <button type="button" class="toolbar-button" (click)="applyMarkdown('displayDescription', codeMarker, codeMarker)" [attr.title]="'wizard.markdownCode' | translate" [attr.aria-label]="'wizard.markdownCode' | translate">code</button>
                <button type="button" class="toolbar-button" (click)="applyMarkdown('displayDescription', '## ', '')" [attr.title]="'wizard.markdownHeading' | translate" [attr.aria-label]="'wizard.markdownHeading' | translate">H2</button>
                <button type="button" class="toolbar-button" (click)="applyMarkdown('displayDescription', '- ', '')" [attr.title]="'wizard.markdownList' | translate" [attr.aria-label]="'wizard.markdownList' | translate">-</button>
              </div>
              <textarea
                #displayDescriptionInput
                formControlName="displayDescription"
                class="textarea"
                rows="4"
                [attr.maxlength]="maxDeckDescription"
              ></textarea>
              <div *ngIf="editForm.get('displayDescription')?.invalid && editForm.get('displayDescription')?.touched" class="error-message">
                {{ displayDescriptionErrorMessage() }}
              </div>
            </div>
            <div class="checkbox-group">
              <label>
                <input type="checkbox" formControlName="autoUpdate" />
                {{ 'deckProfile.autoUpdateLabel' | translate }}
              </label>
            </div>

            <div *ngIf="isAuthor" class="public-deck-section">
              <h3 class="section-title">{{ 'deckProfile.publicDeckSettings' | translate }}</h3>
              <app-input
                [label]="('deckProfile.publicDeckName' | translate) + ' *'"
                formControlName="publicName"
                [hasError]="editForm.get('publicName')?.invalid && editForm.get('publicName')?.touched || false"
                [errorMessage]="publicNameErrorMessage()"
                [maxLength]="maxDeckName"
              ></app-input>
              <div class="markdown-field">
                <label class="markdown-label">{{ 'deckProfile.publicDescription' | translate }}</label>
                <div class="markdown-toolbar">
                  <button type="button" class="toolbar-button" (click)="applyMarkdown('publicDescription', '**', '**')" [attr.title]="'wizard.markdownBold' | translate" [attr.aria-label]="'wizard.markdownBold' | translate">B</button>
                  <button type="button" class="toolbar-button" (click)="applyMarkdown('publicDescription', '*', '*')" [attr.title]="'wizard.markdownItalic' | translate" [attr.aria-label]="'wizard.markdownItalic' | translate">I</button>
                  <button type="button" class="toolbar-button" (click)="applyMarkdown('publicDescription', codeMarker, codeMarker)" [attr.title]="'wizard.markdownCode' | translate" [attr.aria-label]="'wizard.markdownCode' | translate">code</button>
                  <button type="button" class="toolbar-button" (click)="applyMarkdown('publicDescription', '## ', '')" [attr.title]="'wizard.markdownHeading' | translate" [attr.aria-label]="'wizard.markdownHeading' | translate">H2</button>
                  <button type="button" class="toolbar-button" (click)="applyMarkdown('publicDescription', '- ', '')" [attr.title]="'wizard.markdownList' | translate" [attr.aria-label]="'wizard.markdownList' | translate">-</button>
                </div>
                <textarea
                  #publicDescriptionInput
                  formControlName="publicDescription"
                  class="textarea"
                  rows="4"
                  [attr.maxlength]="maxDeckDescription"
                ></textarea>
                <div *ngIf="editForm.get('publicDescription')?.invalid && editForm.get('publicDescription')?.touched" class="error-message">
                  {{ publicDescriptionErrorMessage() }}
                </div>
              </div>
              <div class="form-group">
                <label>{{ 'deckProfile.language' | translate }}</label>
                <select formControlName="language" class="language-select">
                  <option value="en">English</option>
                  <option value="ru">Русский (Russian)</option>
                  <option value="jp">日本語 (Japanese)</option>
                  <option value="sp">Español (Spanish)</option>
                </select>
              </div>
              <div class="form-group">
                <label>{{ 'deckProfile.tags' | translate }}</label>
                <input
                  type="text"
                  class="tag-input"
                  [(ngModel)]="tagInput"
                  [ngModelOptions]="{standalone: true}"
                  (keydown.enter)="addTag($event)"
                  [placeholder]="'deckProfile.tagsPlaceholder' | translate"
                  [attr.maxlength]="maxTagLength"
                />
                <div *ngIf="tags.length > 0" class="tags-list">
                  <span *ngFor="let tag of tags; let i = index" class="tag-chip">{{ tag }} <button type="button" (click)="removeTag(i)">×</button></span>
                </div>
                <p *ngIf="tagError" class="error-message">{{ tagError }}</p>
              </div>
              <div class="checkbox-group">
                <label>
                  <input type="checkbox" formControlName="isPublic" />
                  {{ 'deckProfile.makePublic' | translate }}
                </label>
              </div>
              <div class="checkbox-group">
                <label>
                  <input type="checkbox" formControlName="isListed" />
                  {{ 'deckProfile.listInCatalog' | translate }}
                </label>
              </div>
            </div>

            <div class="review-preferences-section">
              <h3 class="section-title">{{ 'deckProfile.reviewPreferences' | translate }}</h3>
              <div class="form-group">
                <div class="label-with-help">
                  <label>{{ 'deckProfile.schedulerAlgorithm' | translate }}</label>
                  <a
                    class="help-link"
                    href="https://github.com/MattoYuzuru/Mnema/wiki/what-are-scheduling-algorithms"
                    target="_blank"
                    rel="noopener noreferrer"
                    aria-label="Scheduling algorithms guide"
                  >?</a>
                </div>
                <select formControlName="algorithmId" class="algorithm-select">
                  <option value="sm2">SM-2</option>
                  <option value="fsrs_v6">FSRS v6</option>
                  <option value="hlr">HLR</option>
                </select>
                <p *ngIf="currentAlgorithm && currentAlgorithm.pendingMigrationCards > 0" class="migration-info">
                  {{ currentAlgorithm.pendingMigrationCards }} {{ 'deckProfile.pendingMigrationText' | translate }}
                </p>
              </div>
              <div class="form-group">
                <label>{{ 'deckProfile.dailyNewLimit' | translate }}</label>
                <input type="number" formControlName="dailyNewLimit" class="number-input" min="0" />
                <p class="field-help">{{ 'deckProfile.dailyNewLimitHelp' | translate }}</p>
              </div>
              <div class="form-group">
                <label>{{ 'deckProfile.learningHorizonHours' | translate }}</label>
                <input type="number" formControlName="learningHorizonHours" class="number-input" min="1" max="168" />
                <p class="field-help">{{ 'deckProfile.learningHorizonHelp' | translate }}</p>
              </div>
              <div class="form-group">
                <label>{{ 'deckProfile.maxReviewPerDay' | translate }}</label>
                <input type="number" formControlName="maxReviewPerDay" class="number-input" min="0" />
                <p class="field-help">{{ 'deckProfile.maxReviewPerDayHelp' | translate }}</p>
              </div>
              <div class="form-group">
                <label>{{ 'deckProfile.dayCutoffHour' | translate }}</label>
                <input type="number" formControlName="dayCutoffHour" class="number-input" min="0" max="23" />
                <p class="field-help">{{ 'deckProfile.dayCutoffHelp' | translate }}</p>
              </div>
            </div>
          </form>
        </div>
        <div class="modal-footer">
          <app-button variant="ghost" (click)="closeEditModal()" [disabled]="saving">{{ 'deckProfile.cancel' | translate }}</app-button>
          <app-button variant="primary" (click)="saveEdit()" [disabled]="editForm.invalid || saving">
            {{ saving ? ('deckProfile.saving' | translate) : ('deckProfile.save' | translate) }}
          </app-button>
        </div>
      </div>
    </div>

    <app-confirmation-dialog
      [open]="showDeleteConfirm"
      [title]="'deckProfile.deleteDeck' | translate"
      [message]="'deckProfile.deleteDeckMessage' | translate"
      [confirmText]="'deckProfile.confirmDelete' | translate"
      [cancelText]="'deckProfile.cancel' | translate"
      (confirm)="confirmDelete()"
      (cancel)="closeDeleteConfirm()"
    ></app-confirmation-dialog>

    <div *ngIf="showExportChoice" class="modal-overlay" (click)="closeExportChoice()">
      <div class="modal-content export-choice-modal" (click)="$event.stopPropagation()">
        <div class="modal-header">
          <h2>{{ 'deckProfile.exportTitle' | translate }}</h2>
          <button class="close-btn" (click)="closeExportChoice()">&times;</button>
        </div>
        <div class="modal-body">
          <p class="modal-hint">{{ 'deckProfile.exportMessage' | translate }}</p>
          <div class="choice-grid">
            <div class="choice-card" (click)="confirmExport('csv')">
              <div class="choice-icon">CSV</div>
              <h3>{{ 'deckProfile.exportCsvTitle' | translate }}</h3>
              <p>{{ 'deckProfile.exportCsvDesc' | translate }}</p>
            </div>
            <div class="choice-card" (click)="confirmExport('mnema')">
              <div class="choice-icon">📦</div>
              <h3>{{ 'deckProfile.exportMnemaTitle' | translate }}</h3>
              <p>{{ 'deckProfile.exportMnemaDesc' | translate }}</p>
            </div>
          </div>
        </div>
      </div>
    </div>
  `,
    styles: [`
      .deck-profile {
        max-width: 56rem;
        margin: 0 auto;
      }

      .deck-header {
        margin-bottom: var(--spacing-2xl);
      }

      .deck-header h1 {
        font-size: 2.5rem;
        margin: 0 0 var(--spacing-md) 0;
      }

      .deck-description {
        font-size: 1.1rem;
        color: var(--color-text-muted);
        margin: 0;
        line-height: 1.6;
      }

      .deck-description h1,
      .deck-description h2,
      .deck-description h3 {
        font-size: 1.1rem;
        margin: 0 0 var(--spacing-xs) 0;
        font-weight: 600;
      }

      .deck-description ul {
        margin: 0 0 var(--spacing-xs) 0;
        padding-left: 1.2rem;
      }

      .deck-description li {
        margin: 0;
      }

      .deck-description pre {
        margin: 0 0 var(--spacing-xs) 0;
        padding: var(--spacing-sm);
        background: var(--color-background);
        border-radius: var(--border-radius-md);
        white-space: pre-wrap;
      }

      .deck-description code {
        font-family: inherit;
        font-size: 0.95rem;
        background: var(--color-background);
        padding: 0 0.2rem;
        border-radius: var(--border-radius-sm);
      }

      .deck-meta {
        display: flex;
        flex-direction: column;
        gap: var(--spacing-sm);
        padding: var(--spacing-lg);
        background: var(--color-card-background);
        border: 1px solid var(--border-color);
        border-radius: var(--border-radius-lg);
        margin-bottom: var(--spacing-xl);
      }

      .meta-item {
        display: flex;
        gap: var(--spacing-md);
      }

      .meta-label {
        font-weight: 600;
        min-width: 8rem;
      }

      .deck-actions {
        display: flex;
        gap: var(--spacing-md);
        flex-wrap: wrap;
      }

      .loading-state,
      .empty-state {
        color: var(--color-text-secondary);
        font-size: 0.9rem;
      }

      .error-state {
        color: #dc2626;
        font-size: 0.9rem;
      }

      .ai-feature-section {
        margin-top: var(--spacing-2xl);
        padding: var(--spacing-xl);
        background: var(--color-card-background);
        border-radius: var(--border-radius-lg);
        border: 1px solid var(--border-color);
      }

      .ai-feature-header h2 {
        margin: 0 0 var(--spacing-xs) 0;
        font-size: 1.4rem;
      }

      .ai-feature-header p {
        margin: 0 0 var(--spacing-lg) 0;
        color: var(--color-text-muted);
      }

      .ai-feature-grid {
        display: grid;
        gap: var(--spacing-md);
        grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
      }

      .ai-feature-card {
        display: flex;
        align-items: flex-start;
        gap: var(--spacing-md);
        padding: var(--spacing-md);
        border-radius: var(--border-radius-lg);
        border: 1px solid var(--glass-border);
        background: var(--color-background);
        text-align: left;
        cursor: pointer;
        color: var(--color-text-primary);
        transition: transform 0.2s ease, box-shadow 0.2s ease;
      }

      .ai-feature-card h3 {
        margin: 0 0 var(--spacing-xs) 0;
        color: var(--color-text-primary);
      }

      .ai-feature-card p {
        margin: 0;
        color: var(--color-text-secondary);
      }

      .ai-feature-card:hover {
        transform: translateY(-2px);
        box-shadow: var(--shadow-sm);
      }

      .ai-feature-card.disabled {
        opacity: 0.6;
        cursor: not-allowed;
        box-shadow: none;
        transform: none;
      }

      .ai-feature-icon {
        width: 44px;
        height: 44px;
        border-radius: 12px;
        background: var(--color-surface-solid);
        color: var(--color-text-primary);
        display: flex;
        align-items: center;
        justify-content: center;
        font-size: 1.25rem;
        line-height: 1;
        border: 1px solid var(--border-color);
        flex-shrink: 0;
      }

      .ai-jobs-section {
        margin-top: var(--spacing-2xl);
        padding: var(--spacing-xl);
        background: var(--color-card-background);
        border-radius: var(--border-radius-lg);
        border: 1px solid var(--border-color);
      }

      .ai-jobs-header {
        display: flex;
        justify-content: space-between;
        align-items: center;
        gap: var(--spacing-md);
        margin-bottom: var(--spacing-lg);
      }

      .ai-jobs-header h2 {
        margin: 0 0 var(--spacing-xs) 0;
        font-size: 1.3rem;
      }

      .ai-jobs-header p {
        margin: 0;
        color: var(--color-text-muted);
      }

      .ai-job-list {
        display: grid;
        gap: var(--spacing-md);
        min-width: 0;
      }

      .ai-job-card {
        border: 1px solid var(--border-color);
        border-radius: var(--border-radius-md);
        padding: var(--spacing-md);
        background: var(--color-background);
        min-width: 0;
      }

      .ai-job-header {
        display: flex;
        justify-content: space-between;
        gap: var(--spacing-md);
        align-items: flex-start;
      }

      .ai-job-title {
        font-weight: 600;
      }

      .ai-job-meta {
        display: flex;
        flex-wrap: wrap;
        gap: var(--spacing-sm);
        align-items: center;
        font-size: 0.85rem;
        color: var(--color-text-muted);
      }

      .ai-job-status-pill {
        padding: 2px 10px;
        border-radius: 999px;
        text-transform: uppercase;
        letter-spacing: 0.05em;
        font-size: 0.7rem;
        background: rgba(148, 163, 184, 0.2);
        color: #475569;
      }

      .ai-job-status-pill.completed {
        background: rgba(34, 197, 94, 0.12);
        color: #15803d;
      }

      .ai-job-status-pill.failed {
        background: rgba(239, 68, 68, 0.12);
        color: #b91c1c;
      }

      .ai-job-status-pill.canceled {
        background: rgba(148, 163, 184, 0.2);
        color: #475569;
      }

      .ai-job-status-row {
        display: flex;
        align-items: center;
        gap: var(--spacing-md);
        margin-top: var(--spacing-sm);
        font-size: 0.85rem;
      }

      .ai-job-progress {
        flex: 1;
        height: 6px;
        background: rgba(148, 163, 184, 0.2);
        border-radius: 999px;
        overflow: hidden;
      }

      .ai-job-progress-bar {
        height: 100%;
        background: var(--color-primary-accent);
        transition: width 0.3s ease;
      }

      .ai-job-error {
        margin-top: var(--spacing-sm);
        color: #dc2626;
        font-size: 0.85rem;
      }

      .ai-job-result {
        margin-top: var(--spacing-md);
      }

      .ai-job-result-json {
        margin: 0;
        padding: var(--spacing-sm);
        background: rgba(15, 23, 42, 0.04);
        border-radius: var(--border-radius-md);
        font-size: 0.8rem;
        overflow: auto;
        max-height: 200px;
        max-width: 100%;
        white-space: pre-wrap;
        word-break: break-word;
      }

      .choice-card.disabled {
        opacity: 0.6;
        pointer-events: none;
      }

      .modal-overlay {
        position: fixed;
        top: 0;
        left: 0;
        right: 0;
        bottom: 0;
        background: rgba(8, 12, 22, 0.55);
        display: flex;
        align-items: center;
        justify-content: center;
        backdrop-filter: blur(12px) saturate(140%);
        z-index: 1000;
      }

      .modal-content {
        background: var(--color-surface-solid);
        border: 1px solid var(--glass-border);
        border-radius: var(--border-radius-lg);
        max-width: 600px;
        width: 90%;
        max-height: 90vh;
        display: flex;
        flex-direction: column;
        box-shadow: var(--shadow-lg);
      }

      .modal-header {
        display: flex;
        justify-content: space-between;
        align-items: center;
        padding: var(--spacing-lg);
        border-bottom: 1px solid var(--glass-border);
      }

      .modal-header h2 {
        margin: 0;
        font-size: 1.5rem;
        font-weight: 600;
      }

      .close-btn {
        background: none;
        border: none;
        font-size: 2rem;
        cursor: pointer;
        color: var(--color-text-secondary);
        line-height: 1;
        padding: 0;
      }

      .modal-body {
        padding: var(--spacing-lg);
        overflow-y: auto;
      }

      .modal-hint {
        margin: 0 0 var(--spacing-lg) 0;
        color: var(--color-text-muted);
        font-size: 0.95rem;
        line-height: 1.5;
      }

      .edit-form {
        display: flex;
        flex-direction: column;
        gap: var(--spacing-lg);
      }

      .markdown-field {
        display: flex;
        flex-direction: column;
        gap: var(--spacing-xs);
      }

      .markdown-label {
        font-size: 0.9rem;
        font-weight: 500;
        color: var(--color-text-primary);
      }

      .markdown-toolbar {
        display: flex;
        flex-wrap: wrap;
        gap: var(--spacing-xs);
      }

      .toolbar-button {
        border: 1px solid var(--border-color);
        background: var(--color-card-background);
        color: var(--color-text-primary);
        font-size: 0.75rem;
        font-weight: 600;
        border-radius: var(--border-radius-sm);
        padding: 0.2rem 0.5rem;
        cursor: pointer;
        transition: all 0.2s ease;
      }

      .toolbar-button:hover {
        border-color: var(--color-text-primary);
      }

      .textarea {
        padding: var(--spacing-sm) var(--spacing-md);
        border: 1px solid var(--border-color);
        border-radius: var(--border-radius-md);
        font-size: 0.9rem;
        font-family: inherit;
        background: var(--color-card-background);
        color: var(--color-text-primary);
        transition: border-color 0.2s ease;
        resize: vertical;
      }

      .textarea:focus {
        outline: none;
        border-color: var(--color-primary-accent);
      }

      .checkbox-group {
        display: flex;
        align-items: center;
        gap: var(--spacing-sm);
      }

      .checkbox-group label {
        display: flex;
        align-items: center;
        gap: var(--spacing-sm);
        font-size: 0.9rem;
        cursor: pointer;
      }

      .checkbox-group input[type="checkbox"] {
        width: 18px;
        height: 18px;
        cursor: pointer;
      }

      .modal-footer {
        display: flex;
        justify-content: flex-end;
        gap: var(--spacing-md);
        padding: var(--spacing-lg);
        border-top: 1px solid var(--glass-border);
      }

      .public-deck-section {
        margin-top: var(--spacing-xl);
        padding-top: var(--spacing-xl);
        border-top: 1px solid var(--border-color);
        display: flex;
        flex-direction: column;
        gap: var(--spacing-md);
      }

      .section-title {
        font-size: 1.1rem;
        font-weight: 600;
        margin: 0 0 var(--spacing-md) 0;
      }

      .form-group {
        display: flex;
        flex-direction: column;
        gap: var(--spacing-xs);
      }

      .label-with-help {
        display: flex;
        align-items: center;
        gap: var(--spacing-sm);
      }

      .form-group label {
        font-size: 0.875rem;
        font-weight: 500;
        color: var(--color-text-primary);
      }

      .help-link {
        display: inline-flex;
        align-items: center;
        justify-content: center;
        width: 1.1rem;
        height: 1.1rem;
        border-radius: 50%;
        border: 1px solid var(--border-color);
        color: var(--color-text-secondary);
        text-decoration: none;
        font-size: 0.7rem;
        font-weight: 600;
        transition: all 0.2s ease;
      }

      .help-link:hover {
        color: var(--color-text-primary);
        border-color: var(--color-text-primary);
      }

      .language-select, .algorithm-select {
        width: 100%;
        padding: var(--spacing-sm) var(--spacing-md);
        border: 1px solid var(--border-color);
        border-radius: var(--border-radius-md);
        font-size: 0.9rem;
        background: var(--color-card-background);
        cursor: pointer;
      }

      .migration-info {
        font-size: 0.85rem;
        color: var(--color-text-muted);
        margin: 0;
        font-style: italic;
      }

      .review-preferences-section {
        margin-top: var(--spacing-lg);
        padding-top: var(--spacing-lg);
        border-top: 1px solid var(--border-color);
        display: flex;
        flex-direction: column;
        gap: var(--spacing-md);
      }

      .number-input {
        width: 100%;
        padding: var(--spacing-sm) var(--spacing-md);
        border: 1px solid var(--border-color);
        border-radius: var(--border-radius-md);
        font-size: 0.9rem;
        background: var(--color-card-background);
      }

      .field-help {
        font-size: 0.85rem;
        color: var(--color-text-muted);
        margin: var(--spacing-xs) 0 0 0;
      }

      .tag-input {
        width: 100%;
        padding: var(--spacing-sm) var(--spacing-md);
        border: 1px solid var(--border-color);
        border-radius: var(--border-radius-md);
        font-size: 0.9rem;
        background: var(--color-card-background);
      }

      .tags-list {
        display: flex;
        flex-wrap: wrap;
        gap: var(--spacing-xs);
        margin-top: var(--spacing-sm);
      }

      .tag-chip {
        display: inline-flex;
        align-items: center;
        gap: var(--spacing-xs);
        padding: var(--spacing-xs) var(--spacing-sm);
        background: var(--color-background);
        border: 1px solid var(--border-color);
        border-radius: var(--border-radius-full);
        font-size: 0.85rem;
      }

      .tag-chip button {
        background: none;
        border: none;
        cursor: pointer;
        font-size: 1.2rem;
        line-height: 1;
        padding: 0;
      }

      .error-message {
        font-size: 0.85rem;
        color: #dc2626;
      }

      @media (max-width: 768px) {
        .deck-profile {
          padding: 0 var(--spacing-md);
        }

        .deck-header {
          flex-direction: column;
          align-items: flex-start;
          gap: var(--spacing-md);
        }

        .header-content h1 {
          font-size: 1.5rem;
        }

      .header-actions {
        width: 100%;
        flex-direction: column;
      }

        .deck-stats {
          flex-direction: column;
        }
      }

      @media (max-width: 480px) {
        .deck-profile {
          padding: 0 var(--spacing-sm);
        }

        .header-content h1 {
          font-size: 1.25rem;
        }
      }

      .export-status {
        margin-top: var(--spacing-md);
        color: var(--color-text-secondary);
        font-size: 0.9rem;
      }

      .add-choice-modal {
        max-width: 720px;
      }

      .export-choice-modal {
        max-width: 720px;
      }

      .choice-grid {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
        gap: var(--spacing-lg);
      }

      .choice-card {
        padding: var(--spacing-xl);
        border-radius: var(--border-radius-lg);
        border: 1px solid var(--border-color);
        background: var(--color-card-background);
        cursor: pointer;
        display: flex;
        flex-direction: column;
        align-items: center;
        text-align: center;
        gap: var(--spacing-sm);
        transition: transform 0.2s ease, box-shadow 0.2s ease;
      }

      .choice-card:hover {
        transform: translateY(-2px);
        box-shadow: var(--shadow-md);
      }

      .choice-icon {
        width: 64px;
        height: 64px;
        border-radius: 50%;
        background: var(--color-surface-solid);
        color: var(--color-text-primary);
        display: flex;
        align-items: center;
        justify-content: center;
        font-size: 1.8rem;
        line-height: 1;
        border: 1px solid var(--border-color);
      }
    `]
})
export class DeckProfileComponent implements OnInit, OnDestroy {
    private static readonly MAX_DECK_NAME = 50;
    private static readonly MAX_DECK_DESCRIPTION = 200;
    private static readonly MAX_TAGS = 5;
    private static readonly MAX_TAG_LENGTH = 25;
    @ViewChild('displayDescriptionInput') displayDescriptionInput?: ElementRef<HTMLTextAreaElement>;
    @ViewChild('publicDescriptionInput') publicDescriptionInput?: ElementRef<HTMLTextAreaElement>;
    readonly maxDeckName = DeckProfileComponent.MAX_DECK_NAME;
    readonly maxDeckDescription = DeckProfileComponent.MAX_DECK_DESCRIPTION;
    readonly maxTagLength = DeckProfileComponent.MAX_TAG_LENGTH;
    readonly codeMarker = '`';
    loading = true;
    deck: UserDeckDTO | null = null;
    publicDeck: PublicDeckDTO | null = null;
    latestPublicVersion: number | null = null;
    latestTemplateVersion: number | null = null;
    currentUserId: string | null = null;
    isAuthor = false;
    userDeckId = '';
    showAddCards = false;
    showAddCardsChoice = false;
    showAiAddModal = false;
    showAiEnhanceModal = false;
    showImportModal = false;
    showEditModal = false;
    showDeleteConfirm = false;
    showExportChoice = false;
    saving = false;
    exporting = false;
    exportStatusKey: string | null = null;
    exportJob: ImportJobResponse | null = null;
    aiJobs: AiJobEntry[] = [];
    aiJobsLoading = false;
    aiJobsError = '';
    cancelingAiJobs = new Set<string>();
    editForm!: FormGroup;
    tagError = '';
    tagInput = '';
    tags: string[] = [];

    formatDescription(description?: string): string {
        return markdownToHtml((description || '').trim());
    }
    currentAlgorithm: ReviewDeckAlgorithmResponse | null = null;
    originalAlgorithmId = '';
    private readonly editDraftKeyPrefix = 'mnema_edit_deck_draft:';

    private exportPollHandle: ReturnType<typeof setInterval> | null = null;
    private aiJobPollers = new Map<string, number>();

    constructor(
        private route: ActivatedRoute,
        private router: Router,
        private deckApi: DeckApiService,
        private publicDeckApi: PublicDeckApiService,
        private templateApi: TemplateApiService,
        private reviewApi: ReviewApiService,
        private userApi: UserApiService,
        private fb: FormBuilder,
        private importApi: ImportApiService,
        private mediaApi: MediaApiService,
        private aiApi: AiApiService,
        private i18n: I18nService
    ) {}

    ngOnInit(): void {
        this.userDeckId = this.route.snapshot.paramMap.get('userDeckId') || '';
        if (this.userDeckId) {
            this.loadDeck();
            this.loadAiJobs();
        }
    }

    ngOnDestroy(): void {
        this.stopExportPolling();
        this.aiJobPollers.forEach(id => window.clearInterval(id));
        this.aiJobPollers.clear();
    }

    private loadDeck(): void {
        forkJoin({
            deck: this.deckApi.getUserDeck(this.userDeckId),
            user: this.userApi.getMe()
        }).subscribe({
            next: ({ deck, user }) => {
                this.deck = deck;
                this.currentUserId = user.id;

                if (deck.publicDeckId) {
                    this.loadPublicDeck(deck.publicDeckId);
                } else {
                    this.loading = false;
                }
            },
            error: err => {
                console.error('Failed to load deck:', err);
                this.loading = false;
            }
        });
    }

    private loadPublicDeck(publicDeckId: string): void {
        this.publicDeckApi.getPublicDeck(publicDeckId).subscribe({
            next: publicDeck => {
                this.publicDeck = publicDeck;
                this.latestPublicVersion = publicDeck.version;
                this.isAuthor = publicDeck.authorId === this.currentUserId;
                if (publicDeck.templateId) {
                    this.templateApi.getTemplate(publicDeck.templateId).subscribe({
                        next: template => {
                            this.latestTemplateVersion = template.latestVersion ?? template.version ?? null;
                        },
                        error: () => {
                            this.latestTemplateVersion = null;
                        }
                    });
                }
                this.loading = false;
            },
            error: err => {
                console.error('Failed to load public deck:', err);
                this.loading = false;
            }
        });
    }

    needsUpdate(): boolean {
        if (!this.deck || !this.deck.publicDeckId) return false;
        if (this.latestPublicVersion === null) return false;
        return this.deck.currentVersion < this.latestPublicVersion;
    }

    openAddCardsChoice(): void {
        this.showAddCardsChoice = true;
    }

    closeAddCardsChoice(): void {
        this.showAddCardsChoice = false;
    }

    startManualAdd(): void {
        this.showAddCardsChoice = false;
        this.showAddCards = true;
    }

    startImportMerge(): void {
        this.showAddCardsChoice = false;
        this.showImportModal = true;
    }

    startAiAdd(): void {
        this.showAddCardsChoice = false;
        this.showAiAddModal = true;
    }

    closeImportModal(): void {
        this.showImportModal = false;
    }

    openAiAddModal(): void {
        this.showAiAddModal = true;
    }

    closeAiAddModal(): void {
        this.showAiAddModal = false;
    }

    openAiEnhanceModal(): void {
        this.showAiEnhanceModal = true;
    }

    closeAiEnhanceModal(): void {
        this.showAiEnhanceModal = false;
    }

    closeAddCards(): void {
        this.showAddCards = false;
    }

    onCardsSaved(): void {
        this.showAddCards = false;
        console.log('Cards saved successfully');
    }

    refreshAiJobs(): void {
        this.loadAiJobs();
    }

    cancelAiJob(jobId: string): void {
        if (this.cancelingAiJobs.has(jobId)) {
            return;
        }
        this.cancelingAiJobs.add(jobId);
        this.aiApi.cancelJob(jobId).subscribe({
            next: job => {
                this.stopAiPolling(jobId);
                this.upsertAiJob(job);
                this.cancelingAiJobs.delete(jobId);
            },
            error: () => {
                this.cancelingAiJobs.delete(jobId);
            }
        });
    }

    trackAiJob(_: number, entry: AiJobEntry): string {
        return entry.job.jobId;
    }

    formatAiJobType(type: AiJobType): string {
        switch (type) {
            case 'enrich':
                return 'Enrich';
            case 'tts':
                return 'TTS';
            default:
                return 'AI Job';
        }
    }

    canCancelAiJob(status: AiJobStatus): boolean {
        return status === 'queued' || status === 'processing';
    }

    formatStatus(status: string): string {
        if (!status) {
            return 'Unknown';
        }
        return status.charAt(0).toUpperCase() + status.slice(1);
    }

    formatJson(value: unknown): string {
        try {
            return JSON.stringify(value ?? {}, null, 2);
        } catch {
            return '{}';
        }
    }

    private loadAiJobs(): void {
        if (!this.userDeckId) {
            return;
        }
        this.aiJobsLoading = true;
        this.aiJobsError = '';
        this.aiJobPollers.forEach(id => window.clearInterval(id));
        this.aiJobPollers.clear();
        this.aiApi.listJobs(this.userDeckId).subscribe({
            next: jobs => {
                this.aiJobs = jobs.map(job => ({ job, resultSummary: null, resultLoading: false }));
                this.aiJobsLoading = false;
                for (const entry of this.aiJobs) {
                    if (entry.job.status === 'queued' || entry.job.status === 'processing') {
                        this.startAiPolling(entry.job.jobId);
                    } else if (entry.job.status === 'completed') {
                        this.fetchAiResult(entry.job.jobId);
                    }
                }
            },
            error: () => {
                this.aiJobsLoading = false;
                this.aiJobsError = 'deckProfile.aiJobsLoadError';
            }
        });
    }

    private upsertAiJob(job: AiJobResponse): void {
        const index = this.aiJobs.findIndex(entry => entry.job.jobId === job.jobId);
        if (index === -1) {
            this.aiJobs = [{ job, resultSummary: null, resultLoading: false }, ...this.aiJobs];
            return;
        }
        const entry = this.aiJobs[index];
        this.aiJobs[index] = { ...entry, job };
    }

    private startAiPolling(jobId: string): void {
        if (this.aiJobPollers.has(jobId)) {
            return;
        }
        this.refreshAiJob(jobId);
        const id = window.setInterval(() => this.refreshAiJob(jobId), 3000);
        this.aiJobPollers.set(jobId, id);
    }

    private stopAiPolling(jobId: string): void {
        const id = this.aiJobPollers.get(jobId);
        if (id) {
            window.clearInterval(id);
            this.aiJobPollers.delete(jobId);
        }
    }

    private refreshAiJob(jobId: string): void {
        this.aiApi.getJob(jobId).subscribe({
            next: job => {
                this.upsertAiJob(job);
                if (job.status === 'completed' || job.status === 'failed' || job.status === 'canceled') {
                    this.stopAiPolling(jobId);
                    if (job.status === 'completed') {
                        this.fetchAiResult(jobId);
                    }
                }
            }
        });
    }

    private fetchAiResult(jobId: string): void {
        this.aiJobs = this.aiJobs.map(entry => entry.job.jobId === jobId
            ? { ...entry, resultLoading: true }
            : entry
        );
        this.aiApi.getJobResult(jobId).subscribe({
            next: result => {
                this.aiJobs = this.aiJobs.map(entry => entry.job.jobId === jobId
                    ? { ...entry, resultLoading: false, resultSummary: result.resultSummary }
                    : entry
                );
            },
            error: () => {
                this.aiJobs = this.aiJobs.map(entry => entry.job.jobId === jobId
                    ? { ...entry, resultLoading: false }
                    : entry
                );
            }
        });
    }

    learn(): void {
        void this.router.navigate(['/decks', this.userDeckId, 'review']);
    }

    browse(): void {
        void this.router.navigate(['/decks', this.userDeckId, 'browse']);
    }

    sync(): void {
        this.deckApi.syncDeck(this.userDeckId).subscribe({
            next: updatedDeck => {
                this.deck = updatedDeck;
                if (updatedDeck.publicDeckId) {
                    this.loadPublicDeck(updatedDeck.publicDeckId);
                }
                console.log('Deck synced successfully');
            },
            error: err => {
                console.error('Failed to sync deck:', err);
            }
        });
    }

    needsTemplateUpdate(): boolean {
        if (!this.deck) return false;
        if (this.latestTemplateVersion === null) return false;
        if (this.deck.templateVersion === null || this.deck.templateVersion === undefined) return false;
        return this.deck.templateVersion < this.latestTemplateVersion;
    }

    syncTemplate(): void {
        this.deckApi.syncDeckTemplate(this.userDeckId).subscribe({
            next: updatedDeck => {
                this.deck = updatedDeck;
                console.log('Template synced successfully');
            },
            error: err => {
                console.error('Failed to sync template:', err);
            }
        });
    }

    openExportConfirm(): void {
        this.showExportChoice = true;
    }

    closeExportChoice(): void {
        this.showExportChoice = false;
    }

    confirmExport(format: ImportSourceType): void {
        if (!this.deck) {
            return;
        }
        this.showExportChoice = false;
        this.exporting = true;
        this.exportStatusKey = 'deckProfile.exportPreparing';

        this.importApi.createExportJob({ userDeckId: this.deck.userDeckId, format }).subscribe({
            next: job => {
                this.exportJob = job;
                this.exportStatusKey = 'deckProfile.exportRunning';
                this.startExportPolling(job.jobId);
            },
            error: () => {
                this.exporting = false;
                this.exportStatusKey = 'deckProfile.exportFailed';
            }
        });
    }

    private startExportPolling(jobId: string): void {
        this.stopExportPolling();
        this.exportPollHandle = setInterval(() => {
            this.importApi.getJob(jobId).subscribe({
                next: job => {
                    this.exportJob = job;
                    if (job.status === 'completed') {
                        this.exporting = false;
                        this.stopExportPolling();
                        if (job.resultMediaId) {
                            this.downloadExport(job.resultMediaId);
                        } else {
                            this.exportStatusKey = 'deckProfile.exportFailed';
                        }
                    } else if (job.status === 'failed' || job.status === 'canceled') {
                        this.exporting = false;
                        this.stopExportPolling();
                        this.exportStatusKey = 'deckProfile.exportFailed';
                    }
                }
            });
        }, 2000);
    }

    private stopExportPolling(): void {
        if (this.exportPollHandle) {
            clearInterval(this.exportPollHandle);
            this.exportPollHandle = null;
        }
    }

    private downloadExport(mediaId: string): void {
        this.mediaApi.resolve([mediaId]).subscribe({
            next: resolved => {
                const url = resolved[0]?.url;
                if (url) {
                    window.open(url, '_blank');
                    this.exportStatusKey = 'deckProfile.exportReady';
                } else {
                    this.exportStatusKey = 'deckProfile.exportFailed';
                }
            },
            error: () => {
                this.exportStatusKey = 'deckProfile.exportFailed';
            }
        });
    }

    private resolveBrowserTimeZone(): string | null {
        try {
            return Intl.DateTimeFormat().resolvedOptions().timeZone || null;
        } catch {
            return null;
        }
    }

    openEditModal(): void {
        if (this.deck) {
            this.reviewApi.getDeckAlgorithm(this.userDeckId).subscribe({
                next: algorithmData => {
                    this.currentAlgorithm = algorithmData;
                    this.originalAlgorithmId = algorithmData.algorithmId;

                    const preferences = algorithmData.reviewPreferences;
                    const resolvedTimeZone = preferences?.timeZone ?? this.resolveBrowserTimeZone();

                    const formConfig: any = {
                        displayName: [this.deck!.displayName, [Validators.required, Validators.maxLength(DeckProfileComponent.MAX_DECK_NAME)]],
                        displayDescription: [this.deck!.displayDescription, [Validators.maxLength(DeckProfileComponent.MAX_DECK_DESCRIPTION)]],
                        autoUpdate: [this.deck!.autoUpdate],
                        algorithmId: [algorithmData.algorithmId, Validators.required],
                        dailyNewLimit: [preferences?.dailyNewLimit ?? 20],
                        learningHorizonHours: [preferences?.learningHorizonHours ?? 24],
                        maxReviewPerDay: [preferences?.maxReviewPerDay ?? 0],
                        dayCutoffHour: [preferences?.dayCutoffHour ?? 0],
                        timeZone: [resolvedTimeZone]
                    };

                    if (this.isAuthor && this.publicDeck) {
                        formConfig.publicName = [this.publicDeck.name, [Validators.required, Validators.maxLength(DeckProfileComponent.MAX_DECK_NAME)]];
                        formConfig.publicDescription = [this.publicDeck.description, [Validators.maxLength(DeckProfileComponent.MAX_DECK_DESCRIPTION)]];
                        formConfig.isPublic = [this.publicDeck.isPublic];
                        formConfig.isListed = [this.publicDeck.isListed];
                        formConfig.language = [this.publicDeck.language];
                        this.tags = [...(this.publicDeck.tags || [])];
                    } else {
                        this.tags = [];
                    }

                    this.tagInput = '';
                    this.tagError = '';
                    this.editForm = this.fb.group(formConfig);
                    this.applyEditDraft();
                    this.showEditModal = true;
                }
            });
        }
    }

    addTag(event: Event): void {
        event.preventDefault();
        const tag = this.tagInput.trim();
        if (this.tags.length >= DeckProfileComponent.MAX_TAGS) {
            this.tagError = this.i18n.translate('validation.tagsLimit');
            return;
        }
        if (tag.length > DeckProfileComponent.MAX_TAG_LENGTH) {
            this.tagError = this.i18n.translate('validation.tagTooLong');
            return;
        }
        if (tag && !this.tags.includes(tag)) {
            this.tags.push(tag);
            this.tagInput = '';
            this.tagError = '';
        }
    }

    removeTag(index: number): void {
        this.tags.splice(index, 1);
        this.tagError = '';
    }

    displayNameErrorMessage(): string {
        const control = this.editForm?.get('displayName');
        if (control?.hasError('required')) {
            return this.i18n.translate('deckProfile.required');
        }
        if (control?.hasError('maxlength')) {
            return this.i18n.translate('validation.maxLength50');
        }
        return '';
    }

    publicNameErrorMessage(): string {
        const control = this.editForm?.get('publicName');
        if (control?.hasError('required')) {
            return this.i18n.translate('deckProfile.required');
        }
        if (control?.hasError('maxlength')) {
            return this.i18n.translate('validation.maxLength50');
        }
        return '';
    }

    displayDescriptionErrorMessage(): string {
        const control = this.editForm?.get('displayDescription');
        if (control?.hasError('maxlength')) {
            return this.i18n.translate('validation.maxLength200');
        }
        return '';
    }

    publicDescriptionErrorMessage(): string {
        const control = this.editForm?.get('publicDescription');
        if (control?.hasError('maxlength')) {
            return this.i18n.translate('validation.maxLength200');
        }
        return '';
    }

    applyMarkdown(target: 'displayDescription' | 'publicDescription', before: string, after: string, placeholder = ''): void {
        const textarea = target === 'displayDescription'
            ? this.displayDescriptionInput?.nativeElement
            : this.publicDescriptionInput?.nativeElement;
        if (!textarea) {
            return;
        }
        const value = textarea.value;
        const start = textarea.selectionStart ?? 0;
        const end = textarea.selectionEnd ?? 0;
        const hasSelection = start !== end;
        const selectedText = hasSelection ? value.slice(start, end) : placeholder;
        const newValue = value.slice(0, start) + before + selectedText + after + value.slice(end);
        this.editForm.get(target)?.setValue(newValue);

        const cursorStart = start + before.length;
        const cursorEnd = cursorStart + selectedText.length;
        requestAnimationFrame(() => {
            textarea.focus();
            textarea.setSelectionRange(cursorStart, cursorEnd);
        });
    }

    closeEditModal(): void {
        this.saveEditDraft();
        this.showEditModal = false;
    }

    private getEditDraftKey(): string {
        return `${this.editDraftKeyPrefix}${this.userDeckId}`;
    }

    private saveEditDraft(): void {
        if (!this.editForm) {
            return;
        }
        const draft = {
            form: this.editForm.getRawValue(),
            tags: this.tags
        };
        try {
            localStorage.setItem(this.getEditDraftKey(), JSON.stringify(draft));
        } catch {
        }
    }

    private applyEditDraft(): void {
        const raw = localStorage.getItem(this.getEditDraftKey());
        if (!raw) {
            return;
        }
        try {
            const draft = JSON.parse(raw);
            const patch: Record<string, unknown> = {};
            Object.keys(this.editForm.controls).forEach(key => {
                if (draft.form && draft.form[key] !== undefined) {
                    patch[key] = draft.form[key];
                }
            });
            this.editForm.patchValue(patch);
            if (Array.isArray(draft.tags)) {
                this.tags = [...draft.tags];
            }
        } catch {
        }
    }

    private clearEditDraft(): void {
        localStorage.removeItem(this.getEditDraftKey());
    }

    saveEdit(): void {
        if (this.editForm.invalid || this.hasInvalidTags()) return;

        const formValue = this.editForm.value;

        this.saving = true;

        const userDeckUpdates: Partial<UserDeckDTO> = {
            displayName: formValue.displayName,
            displayDescription: formValue.displayDescription,
            autoUpdate: formValue.autoUpdate
        };

        const algorithmChanged = formValue.algorithmId !== this.originalAlgorithmId;
        const hasPreferenceValues = formValue.dailyNewLimit !== undefined ||
            formValue.learningHorizonHours !== undefined ||
            formValue.maxReviewPerDay !== undefined ||
            formValue.dayCutoffHour !== undefined ||
            formValue.timeZone !== undefined;

        const requests: any = {};
        const userDeckRequest = this.deckApi.patchDeck(this.userDeckId, userDeckUpdates);

        if (algorithmChanged || hasPreferenceValues) {
            const toOptionalNumber = (value: any): number | null => {
                if (value === null || value === undefined || value === '') {
                    return null;
                }
                return Number(value);
            };
            const reviewPreferences = {
                dailyNewLimit: toOptionalNumber(formValue.dailyNewLimit),
                learningHorizonHours: toOptionalNumber(formValue.learningHorizonHours),
                maxReviewPerDay: toOptionalNumber(formValue.maxReviewPerDay),
                dayCutoffHour: toOptionalNumber(formValue.dayCutoffHour),
                timeZone: formValue.timeZone ?? this.resolveBrowserTimeZone()
            };

            requests.userDeck = userDeckRequest.pipe(
                switchMap(deck => this.reviewApi.updateDeckAlgorithm(this.userDeckId, {
                    algorithmId: formValue.algorithmId,
                    algorithmParams: null,
                    reviewPreferences: reviewPreferences
                }).pipe(map(() => deck)))
            );
        } else {
            requests.userDeck = userDeckRequest;
        }

        if (this.isAuthor && this.publicDeck && this.hasPublicDeckChanges()) {
            const publicDeckUpdates: Partial<PublicDeckDTO> = {
                name: formValue.publicName,
                description: formValue.publicDescription,
                isPublic: formValue.isPublic,
                isListed: formValue.isListed,
                language: formValue.language,
                tags: this.tags
            };
            requests.publicDeck = this.publicDeckApi.patchPublicDeck(this.publicDeck.deckId, publicDeckUpdates);
        }

        forkJoin(requests).subscribe({
            next: (results: any) => {
                this.deck = results.userDeck;
                if (results.publicDeck) {
                    this.publicDeck = results.publicDeck;
                }
                this.saving = false;
                this.showEditModal = false;
                this.clearEditDraft();
            },
            error: () => {
                this.saving = false;
            }
        });
    }

    hasPublicDeckChanges(): boolean {
        if (!this.publicDeck) return false;
        const formValue = this.editForm.value;

        return formValue.publicName !== this.publicDeck.name ||
               formValue.publicDescription !== this.publicDeck.description ||
               formValue.isPublic !== this.publicDeck.isPublic ||
               formValue.isListed !== this.publicDeck.isListed ||
               formValue.language !== this.publicDeck.language ||
               JSON.stringify(this.tags) !== JSON.stringify(this.publicDeck.tags);
    }

    private hasInvalidTags(): boolean {
        if (this.tags.length > DeckProfileComponent.MAX_TAGS) {
            this.tagError = this.i18n.translate('validation.tagsLimit');
            return true;
        }
        if (this.tags.some(tag => tag.length > DeckProfileComponent.MAX_TAG_LENGTH)) {
            this.tagError = this.i18n.translate('validation.tagTooLong');
            return true;
        }
        this.tagError = '';
        return false;
    }

    openDeleteConfirm(): void {
        this.showDeleteConfirm = true;
    }

    closeDeleteConfirm(): void {
        this.showDeleteConfirm = false;
    }

    confirmDelete(): void {
        if (this.isAuthor && this.publicDeck) {
            forkJoin({
                publicDeck: this.publicDeckApi.deletePublicDeck(this.publicDeck.deckId),
                userDeck: this.deckApi.deleteDeck(this.userDeckId)
            }).subscribe({
                next: () => {
                    this.showDeleteConfirm = false;
                    void this.router.navigate(['/decks']);
                },
                error: err => {
                    console.error('Failed to delete decks:', err);
                    this.showDeleteConfirm = false;
                }
            });
        } else {
            this.deckApi.deleteDeck(this.userDeckId).subscribe({
                next: () => {
                    this.showDeleteConfirm = false;
                    void this.router.navigate(['/decks']);
                },
                error: err => {
                    console.error('Failed to delete deck:', err);
                    this.showDeleteConfirm = false;
                }
            });
        }
    }
}

type AiJobEntry = {
    job: AiJobResponse;
    resultSummary: unknown | null;
    resultLoading: boolean;
};
