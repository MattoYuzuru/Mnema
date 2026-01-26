import { Component, OnDestroy, OnInit, computed, signal } from '@angular/core';
import { Router } from '@angular/router';
import { DatePipe, NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ThemeService } from '../../core/services/theme.service';
import { I18nService, Language } from '../../core/services/i18n.service';
import { PreferencesService } from '../../core/services/preferences.service';
import { DeckApiService } from '../../core/services/deck-api.service';
import { AiApiService } from '../../core/services/ai-api.service';
import { UserApiService } from '../../user-api.service';
import { AuthService } from '../../auth.service';
import { UserDeckDTO } from '../../core/models/user-deck.models';
import { AiJobResponse, AiJobStatus, AiJobType, AiProviderCredential } from '../../core/models/ai.models';
import { ButtonComponent } from '../../shared/components/button.component';
import { ConfirmationDialogComponent } from '../../shared/components/confirmation-dialog.component';
import { TranslatePipe } from '../../shared/pipes/translate.pipe';

@Component({
    selector: 'app-settings',
    standalone: true,
    imports: [NgFor, NgIf, FormsModule, ButtonComponent, ConfirmationDialogComponent, TranslatePipe, DatePipe],
    template: `
    <div class="settings-page">
      <h1>{{ 'settings.title' | translate }}</h1>

      <section class="settings-section">
        <h2>{{ 'settings.theme' | translate }}</h2>
        <p class="section-description">{{ 'settings.themeDescription' | translate }}</p>
        <div class="theme-controls">
          <div class="control-group">
            <label class="control-label">Mode</label>
            <div class="button-group">
              <button
                class="option-btn"
                [class.active]="theme.mode() === 'light'"
                (click)="theme.setMode('light')"
              >
                {{ 'theme.light' | translate }}
              </button>
              <button
                class="option-btn"
                [class.active]="theme.mode() === 'dark'"
                (click)="theme.setMode('dark')"
              >
                {{ 'theme.dark' | translate }}
              </button>
            </div>
          </div>

          <div class="control-group">
            <label class="control-label">Accent</label>
            <div class="button-group">
              <button
                class="option-btn"
                [class.active]="theme.accent() === 'neo'"
                (click)="theme.setAccent('neo')"
              >
                Neo
              </button>
              <button
                class="option-btn"
                [class.active]="theme.accent() === 'vintage'"
                (click)="theme.setAccent('vintage')"
              >
                Vintage
              </button>
            </div>
          </div>
        </div>
      </section>

      <section class="settings-section">
        <h2>{{ 'settings.language' | translate }}</h2>
        <p class="section-description">{{ 'settings.languageDescription' | translate }}</p>
        <div class="control-group">
          <div class="button-group">
            <button
              class="option-btn"
              [class.active]="i18n.currentLanguage === 'en'"
              (click)="i18n.setLanguage('en')"
            >
              {{ 'language.english' | translate }}
            </button>
            <button
              class="option-btn"
              [class.active]="i18n.currentLanguage === 'ru'"
              (click)="i18n.setLanguage('ru')"
            >
              {{ 'language.russian' | translate }}
            </button>
          </div>
        </div>
      </section>

      <section class="settings-section">
        <h2>{{ 'settings.cardAppearance' | translate }}</h2>
        <p class="section-description">{{ 'settings.cardAppearanceDescription' | translate }}</p>
        <div class="checkbox-group">
          <label class="checkbox-label">
            <input
              type="checkbox"
              [checked]="preferences.hideFieldLabels"
              (change)="preferences.setHideFieldLabels($any($event.target).checked)"
            />
            <span>{{ 'settings.hideFieldLabels' | translate }}</span>
          </label>
          <label class="checkbox-label">
            <input
              type="checkbox"
              [checked]="preferences.showFrontSideAfterFlip"
              (change)="preferences.setShowFrontSideAfterFlip($any($event.target).checked)"
            />
            <span>{{ 'settings.showFrontSideAfterFlip' | translate }}</span>
          </label>
        </div>
      </section>

      <section class="settings-section ai-settings">
        <h2>AI Provider Keys</h2>
        <p class="section-description">
          Manage your AI provider keys. Secrets are stored encrypted and shown only once.
        </p>

        <div class="ai-settings-grid">
          <div class="ai-keys-panel" [attr.aria-busy]="providersLoading()">
            <div class="ai-panel-header">
              <h3>Saved keys</h3>
              <app-button variant="ghost" size="sm" (click)="loadProviders()" [disabled]="providersLoading()">
                Refresh
              </app-button>
            </div>

            <div *ngIf="providersLoading()" class="loading-state">Loading keys...</div>
            <div *ngIf="!providersLoading() && providerError()" class="error-state" role="alert">
              {{ providerError() }}
            </div>
            <div *ngIf="!providersLoading() && !providerError() && providerKeys().length === 0" class="empty-state">
              No provider keys yet.
            </div>

            <div *ngIf="!providersLoading() && providerKeys().length > 0" class="ai-keys-list">
              <div *ngFor="let key of providerKeys(); trackBy: trackProvider" class="ai-key-row">
                <div class="ai-key-main">
                  <div class="ai-key-title">
                    <span class="ai-key-provider">{{ key.provider }}</span>
                    <span *ngIf="key.alias" class="ai-key-alias">- {{ key.alias }}</span>
                  </div>
                  <div class="ai-key-meta">
                    <span class="ai-key-secret" aria-label="Secret stored">{{ maskedSecret() }}</span>
                    <span class="ai-key-status" [class.active]="key.status === 'active'" [class.inactive]="key.status !== 'active'">
                      {{ formatStatus(key.status) }}
                    </span>
                    <span class="ai-key-last-used">
                      Last used:
                      <span *ngIf="key.lastUsedAt; else neverUsed">{{ key.lastUsedAt | date:'medium' }}</span>
                      <ng-template #neverUsed>Never</ng-template>
                    </span>
                  </div>
                </div>
                <div class="ai-key-actions">
                  <app-button
                    variant="ghost"
                    size="sm"
                    tone="danger"
                    (click)="openDeleteProvider(key)"
                    [disabled]="deleteInFlight()"
                  >
                    Delete
                  </app-button>
                </div>
              </div>
            </div>
          </div>

          <div class="ai-form-panel">
            <h3>Add new key</h3>
            <p class="form-help">You can rotate keys at any time by adding a new one.</p>
            <form (ngSubmit)="createProvider()" class="ai-form">
              <div class="form-field">
                <label for="ai-provider">Provider</label>
                <select
                  id="ai-provider"
                  name="provider"
                  [ngModel]="providerPreset()"
                  (ngModelChange)="onProviderPresetChange($event)"
                >
                  <option *ngFor="let opt of providerOptions" [ngValue]="opt.value">
                    {{ opt.label }}
                  </option>
                </select>
                <input
                  *ngIf="providerPreset() === 'custom'"
                  name="providerCustom"
                  type="text"
                  [ngModel]="providerName()"
                  (ngModelChange)="providerName.set($event)"
                  placeholder="custom-provider"
                  autocomplete="organization"
                />
              </div>

              <div class="form-field">
                <label for="ai-alias">Alias (optional)</label>
                <input
                  id="ai-alias"
                  name="alias"
                  type="text"
                  [ngModel]="providerAlias()"
                  (ngModelChange)="providerAlias.set($event)"
                  placeholder="Work key"
                  autocomplete="off"
                />
              </div>

              <div class="form-field">
                <label for="ai-secret">Secret key</label>
                <input
                  id="ai-secret"
                  name="secret"
                  type="password"
                  [ngModel]="providerSecret()"
                  (ngModelChange)="providerSecret.set($event)"
                  placeholder="sk-..."
                  required
                  autocomplete="new-password"
                />
                <p class="field-hint">We will never show this key again.</p>
              </div>

              <div class="form-actions">
                <app-button
                  type="submit"
                  variant="primary"
                  [disabled]="!canCreateProvider()"
                >
                  {{ creatingProvider() ? 'Saving...' : 'Save key' }}
                </app-button>
              </div>

              <div *ngIf="createSuccess()" class="success-state" role="status" aria-live="polite">
                {{ createSuccess() }}
              </div>
              <div *ngIf="createError()" class="error-state" role="alert">
                {{ createError() }}
              </div>
            </form>
          </div>
        </div>
      </section>

      <section class="settings-section ai-jobs">
        <h2>AI Jobs</h2>
        <p class="section-description">
          Create an enrichment or TTS job and monitor progress. Results are summarized when ready.
        </p>

        <div class="ai-jobs-grid">
          <div class="ai-job-form">
            <h3>Create job</h3>
            <form (ngSubmit)="createJob()">
              <div class="form-field">
                <label for="ai-job-type">Job type</label>
                <select
                  id="ai-job-type"
                  name="jobType"
                  [ngModel]="jobType()"
                  (ngModelChange)="jobType.set($event)"
                >
                  <option value="enrich">Enrich</option>
                  <option value="tts">TTS</option>
                </select>
              </div>

              <div class="form-field">
                <label for="ai-job-deck">Deck ID (optional)</label>
                <input
                  id="ai-job-deck"
                  name="deckId"
                  type="text"
                  [ngModel]="jobDeckId()"
                  (ngModelChange)="jobDeckId.set($event)"
                  placeholder="UUID"
                  autocomplete="off"
                />
              </div>

              <div class="form-field">
                <label for="ai-job-text">Input text</label>
                <textarea
                  id="ai-job-text"
                  name="jobText"
                  rows="4"
                  [ngModel]="jobText()"
                  (ngModelChange)="jobText.set($event)"
                  placeholder="Paste the content to enrich or synthesize..."
                  required
                ></textarea>
              </div>

              <div class="form-actions">
                <app-button
                  type="submit"
                  variant="primary"
                  [disabled]="!canCreateJob()"
                >
                  {{ jobCreating() ? 'Creating...' : 'Start job' }}
                </app-button>
              </div>

              <div *ngIf="jobError()" class="error-state" role="alert">
                {{ jobError() }}
              </div>
            </form>
          </div>

          <div class="ai-job-list" aria-live="polite">
            <h3>Recent jobs</h3>
            <div *ngIf="jobs().length === 0" class="empty-state">No AI jobs yet.</div>

            <div *ngFor="let entry of jobs(); trackBy: trackJob" class="ai-job-card">
              <div class="ai-job-header">
                <div>
                  <div class="ai-job-title">{{ formatJobType(entry.job.type) }}</div>
                  <div class="ai-job-id">ID: {{ entry.job.jobId }}</div>
                </div>
                <div class="ai-job-actions">
                  <app-button
                    variant="ghost"
                    size="sm"
                    tone="danger"
                    [disabled]="!canCancel(entry.job.status) || cancelingJobs().has(entry.job.jobId)"
                    (click)="cancelJob(entry.job.jobId)"
                  >
                    {{ cancelingJobs().has(entry.job.jobId) ? 'Canceling...' : 'Cancel' }}
                  </app-button>
                </div>
              </div>

              <div class="ai-job-status">
                <span class="ai-job-status-pill" [class.completed]="entry.job.status === 'completed'" [class.failed]="entry.job.status === 'failed'" [class.canceled]="entry.job.status === 'canceled'">
                  {{ formatStatus(entry.job.status) }}
                </span>
                <span class="ai-job-progress-text">{{ entry.job.progress }}%</span>
              </div>

              <div class="ai-job-progress" role="progressbar" [attr.aria-valuenow]="entry.job.progress" aria-valuemin="0" aria-valuemax="100">
                <div class="ai-job-progress-bar" [style.width.%]="entry.job.progress"></div>
              </div>

              <div *ngIf="entry.job.errorMessage" class="ai-job-error" role="alert">
                {{ entry.job.errorMessage }}
              </div>

              <div class="ai-job-result">
                <h4>Result summary</h4>
                <div *ngIf="entry.resultLoading" class="loading-state">Loading summary...</div>
                <pre *ngIf="!entry.resultLoading && entry.resultSummary" class="ai-job-result-json">{{ formatJson(entry.resultSummary) }}</pre>
                <div *ngIf="!entry.resultLoading && !entry.resultSummary && entry.job.status !== 'completed'" class="empty-state">
                  Summary will appear once the job completes.
                </div>
              </div>
            </div>
          </div>
        </div>
      </section>

      <section class="settings-section">
        <h2>{{ 'settings.archive' | translate }}</h2>
        <p class="section-description">{{ 'settings.archiveDescription' | translate }}</p>

        <div *ngIf="loadingArchive" class="loading-state">Loading archived decks...</div>

        <div *ngIf="!loadingArchive && archivedDecks.length === 0" class="empty-state">
          {{ 'publicDecks.noArchivedDecks' | translate }}
        </div>

        <div *ngIf="!loadingArchive && archivedDecks.length > 0" class="archive-list">
          <div *ngFor="let deck of archivedDecks" class="archive-item">
            <div class="archive-item-content">
              <h3>{{ deck.displayName }}</h3>
              <p>{{ deck.displayDescription }}</p>
            </div>
            <div class="archive-item-actions">
              <app-button variant="ghost" size="sm" (click)="openDeck(deck.userDeckId)">
                Open
              </app-button>
              <app-button variant="secondary" size="sm" (click)="restoreDeck(deck.userDeckId)">
                {{ 'settings.restore' | translate }}
              </app-button>
        <app-button variant="ghost" size="sm" tone="danger" (click)="openHardDeleteConfirm(deck.userDeckId)">
          Delete Permanently
        </app-button>
            </div>
          </div>
        </div>
      </section>

      <section class="settings-section danger-zone">
        <h2>{{ 'settings.dangerZone' | translate }}</h2>
        <p class="section-description">{{ 'settings.deleteAccountWarning' | translate }}</p>

        <app-button variant="ghost" tone="danger" (click)="showDeleteConfirmation = true">
          {{ 'settings.deleteAccount' | translate }}
        </app-button>
      </section>
    </div>

    <div *ngIf="showDeleteConfirmation" class="modal-overlay" (click)="showDeleteConfirmation = false; deleteAccountUsername = ''">
      <div class="modal-content delete-account-modal" (click)="$event.stopPropagation()">
        <div class="modal-header">
          <h2>Delete Account</h2>
          <button class="close-btn" (click)="showDeleteConfirmation = false; deleteAccountUsername = ''">&times;</button>
        </div>
        <div class="modal-body">
          <p class="warning-text">This action cannot be undone. All your decks and progress will be permanently deleted.</p>
          <p>Type your username <strong>{{ currentUsername }}</strong> to confirm:</p>
          <input
            type="text"
            [(ngModel)]="deleteAccountUsername"
            [placeholder]="currentUsername"
            class="delete-confirm-input"
          />
        </div>
        <div class="modal-footer">
          <app-button variant="ghost" (click)="showDeleteConfirmation = false; deleteAccountUsername = ''">
            Cancel
          </app-button>
          <app-button
            variant="primary"
            tone="danger"
            [disabled]="deleteAccountUsername !== currentUsername"
            (click)="deleteAccount()"
          >
            Delete Account
          </app-button>
        </div>
      </div>
    </div>

    <app-confirmation-dialog
      *ngIf="showHardDeleteConfirmation"
      [open]="showHardDeleteConfirmation"
      title="Delete Deck Permanently"
      message="Are you sure you want to permanently delete this deck? This action cannot be undone and all card progress will be lost."
      confirmText="Delete Permanently"
      cancelText="Cancel"
      (confirm)="confirmHardDelete()"
      (cancel)="closeHardDeleteConfirm()"
    ></app-confirmation-dialog>

    <app-confirmation-dialog
      *ngIf="deleteProviderTarget()"
      [open]="!!deleteProviderTarget()"
      title="Delete provider key"
      message="This will remove the stored key. You can add a new key at any time."
      confirmText="Delete key"
      cancelText="Cancel"
      confirmVariant="ghost"
      (confirm)="confirmDeleteProvider()"
      (cancel)="closeDeleteProvider()"
    ></app-confirmation-dialog>
  `,
    styles: [`
      .settings-page {
        max-width: 56rem;
        margin: 0 auto;
      }

      h1 {
        font-size: 2rem;
        margin: 0 0 var(--spacing-xl) 0;
      }

      .settings-section {
        padding: var(--spacing-xl);
        background: var(--color-card-background);
        border: 1px solid var(--border-color);
        border-radius: var(--border-radius-lg);
        margin-bottom: var(--spacing-xl);
      }

      .settings-section h2 {
        font-size: 1.25rem;
        margin: 0 0 var(--spacing-md) 0;
      }

      .section-description {
        color: var(--color-text-muted);
        margin: 0 0 var(--spacing-lg) 0;
      }

      .theme-controls {
        display: flex;
        flex-direction: column;
        gap: var(--spacing-lg);
      }

      .control-group {
        display: flex;
        flex-direction: column;
        gap: var(--spacing-sm);
      }

      .control-label {
        font-weight: 600;
        font-size: 0.9rem;
        color: var(--color-text-secondary);
      }

      .button-group {
        display: flex;
        gap: var(--spacing-sm);
      }

      .option-btn {
        padding: var(--spacing-sm) var(--spacing-lg);
        background: var(--color-background);
        border: 2px solid var(--border-color);
        border-radius: var(--border-radius-md);
        cursor: pointer;
        font-weight: 500;
        color: var(--color-text-primary);
        transition: all 0.2s;
      }

      .option-btn:hover {
        border-color: var(--color-primary-accent);
      }

      .option-btn.active {
        background: var(--color-primary-accent);
        border-color: var(--color-primary-accent);
        color: #ffffff;
      }

      .checkbox-group {
        display: flex;
        flex-direction: column;
        gap: var(--spacing-sm);
      }

      .checkbox-label {
        display: flex;
        align-items: center;
        gap: var(--spacing-sm);
        cursor: pointer;
        font-size: 0.95rem;
      }

      .checkbox-label input[type="checkbox"] {
        width: 18px;
        height: 18px;
        cursor: pointer;
      }

      .loading-state, .empty-state {
        padding: var(--spacing-lg);
        text-align: center;
        color: var(--color-text-muted);
      }

      .archive-list {
        display: flex;
        flex-direction: column;
        gap: var(--spacing-md);
      }

      .archive-item {
        display: flex;
        gap: var(--spacing-md);
        padding: var(--spacing-md);
        background: var(--color-background);
        border: 1px solid var(--border-color);
        border-radius: var(--border-radius-md);
      }

      .archive-item-content {
        flex: 1;
      }

      .archive-item-content h3 {
        font-size: 1rem;
        font-weight: 600;
        margin: 0 0 var(--spacing-xs) 0;
      }

      .archive-item-content p {
        font-size: 0.9rem;
        color: var(--color-text-muted);
        margin: 0;
      }

      .archive-item-actions {
        display: flex;
        gap: var(--spacing-sm);
        align-items: center;
      }

      .ai-settings-grid {
        display: grid;
        grid-template-columns: minmax(0, 1.1fr) minmax(0, 0.9fr);
        gap: var(--spacing-xl);
      }

      .ai-keys-panel,
      .ai-form-panel {
        background: var(--color-background);
        border: 1px solid var(--border-color);
        border-radius: var(--border-radius-md);
        padding: var(--spacing-lg);
      }

      .ai-panel-header {
        display: flex;
        justify-content: space-between;
        align-items: center;
        margin-bottom: var(--spacing-md);
      }

      .ai-panel-header h3 {
        margin: 0;
        font-size: 1rem;
      }

      .ai-keys-list {
        display: flex;
        flex-direction: column;
        gap: var(--spacing-md);
      }

      .ai-key-row {
        display: flex;
        justify-content: space-between;
        gap: var(--spacing-md);
        padding: var(--spacing-md);
        border-radius: var(--border-radius-md);
        border: 1px solid var(--border-color);
        background: var(--color-card-background);
      }

      .ai-key-main {
        display: flex;
        flex-direction: column;
        gap: var(--spacing-xs);
      }

      .ai-key-title {
        font-weight: 600;
        display: flex;
        gap: var(--spacing-xs);
        align-items: center;
      }

      .ai-key-alias {
        font-weight: 500;
        color: var(--color-text-muted);
      }

      .ai-key-meta {
        display: flex;
        flex-wrap: wrap;
        gap: var(--spacing-sm);
        font-size: 0.85rem;
        color: var(--color-text-muted);
        align-items: center;
      }

      .ai-key-secret {
        font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace;
        background: rgba(0, 0, 0, 0.06);
        padding: 2px 8px;
        border-radius: 999px;
        letter-spacing: 0.1em;
      }

      .ai-key-status {
        padding: 2px 8px;
        border-radius: 999px;
        font-size: 0.75rem;
        text-transform: uppercase;
        letter-spacing: 0.06em;
        border: 1px solid transparent;
      }

      .ai-key-status.active {
        background: rgba(34, 197, 94, 0.12);
        color: #15803d;
        border-color: rgba(34, 197, 94, 0.3);
      }

      .ai-key-status.inactive {
        background: rgba(148, 163, 184, 0.2);
        color: #475569;
        border-color: rgba(148, 163, 184, 0.4);
      }

      .ai-key-actions {
        display: flex;
        align-items: center;
      }

      .ai-form-panel h3 {
        margin: 0 0 var(--spacing-sm) 0;
      }

      .form-help {
        margin: 0 0 var(--spacing-md) 0;
        color: var(--color-text-muted);
      }

      .ai-form {
        display: flex;
        flex-direction: column;
        gap: var(--spacing-md);
      }

      .form-field {
        display: flex;
        flex-direction: column;
        gap: var(--spacing-xs);
      }

      .form-field label {
        font-weight: 600;
        font-size: 0.9rem;
      }

      .form-field input {
        padding: var(--spacing-sm) var(--spacing-md);
        border-radius: var(--border-radius-md);
        border: 1px solid var(--border-color);
        background: var(--color-surface-solid);
        color: var(--color-text-primary);
      }

      .form-field input:focus {
        outline: none;
        border-color: var(--color-primary-accent);
        box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.15);
      }

      .field-hint {
        margin: 0;
        font-size: 0.8rem;
        color: var(--color-text-muted);
      }

      .form-actions {
        display: flex;
        justify-content: flex-start;
      }

      .success-state {
        color: #15803d;
        font-size: 0.9rem;
      }

      .error-state {
        color: #dc2626;
        font-size: 0.9rem;
      }

      .danger-zone {
        border-color: #dc2626;
      }

      .danger-zone h2 {
        color: #dc2626;
      }

      .ai-jobs-grid {
        display: grid;
        grid-template-columns: minmax(0, 0.9fr) minmax(0, 1.1fr);
        gap: var(--spacing-xl);
      }

      .ai-job-form,
      .ai-job-list {
        background: var(--color-background);
        border: 1px solid var(--border-color);
        border-radius: var(--border-radius-md);
        padding: var(--spacing-lg);
      }

      .ai-job-form select,
      .ai-job-form textarea {
        padding: var(--spacing-sm) var(--spacing-md);
        border-radius: var(--border-radius-md);
        border: 1px solid var(--border-color);
        background: var(--color-surface-solid);
        color: var(--color-text-primary);
        font-family: inherit;
      }

      .ai-job-form textarea {
        resize: vertical;
      }

      .ai-job-list h3 {
        margin: 0 0 var(--spacing-md) 0;
      }

      .ai-job-card {
        border: 1px solid var(--border-color);
        border-radius: var(--border-radius-md);
        padding: var(--spacing-md);
        background: var(--color-card-background);
        margin-bottom: var(--spacing-md);
      }

      .ai-job-header {
        display: flex;
        justify-content: space-between;
        gap: var(--spacing-md);
        align-items: center;
      }

      .ai-job-title {
        font-weight: 600;
      }

      .ai-job-id {
        font-size: 0.75rem;
        color: var(--color-text-muted);
        word-break: break-all;
      }

      .ai-job-status {
        display: flex;
        justify-content: space-between;
        align-items: center;
        margin: var(--spacing-sm) 0;
        font-size: 0.85rem;
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

      .ai-job-progress {
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

      .ai-job-result h4 {
        margin: 0 0 var(--spacing-xs) 0;
        font-size: 0.9rem;
      }

      .ai-job-result-json {
        margin: 0;
        padding: var(--spacing-sm);
        background: rgba(15, 23, 42, 0.04);
        border-radius: var(--border-radius-md);
        font-size: 0.8rem;
        overflow: auto;
        max-height: 200px;
      }

      

      .modal-overlay {
        position: fixed;
        inset: 0;
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
        width: 90%;
        max-width: 32rem;
        box-shadow: var(--shadow-lg);
      }

      .modal-header {
        display: flex;
        justify-content: space-between;
        align-items: center;
        padding: var(--spacing-xl);
        border-bottom: 1px solid var(--glass-border);
      }

      .modal-header h2 {
        margin: 0;
        font-size: 1.25rem;
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
        padding: var(--spacing-xl);
      }

      .modal-footer {
        display: flex;
        justify-content: flex-end;
        gap: var(--spacing-md);
        padding: var(--spacing-xl);
        border-top: 1px solid var(--glass-border);
      }

      .warning-text {
        color: #dc2626;
        font-weight: 500;
        margin: 0 0 var(--spacing-md) 0;
      }

      .delete-confirm-input {
        width: 100%;
        padding: var(--spacing-md);
        border: 2px solid var(--border-color);
        border-radius: var(--border-radius-md);
        font-size: 1rem;
        margin-top: var(--spacing-md);
        font-family: inherit;
      }

      .delete-confirm-input:focus {
        outline: none;
        border-color: #dc2626;
      }

      @media (max-width: 768px) {
        .settings-page {
          padding: 0 var(--spacing-md);
        }

        h1 {
          font-size: 1.5rem;
        }

        .settings-section {
          padding: var(--spacing-lg);
        }

        .button-group {
          flex-wrap: wrap;
        }

        .archive-item {
          flex-direction: column;
        }

        .archive-item-actions {
          flex-wrap: wrap;
          justify-content: flex-start;
        }

        .ai-settings-grid {
          grid-template-columns: 1fr;
        }

        .ai-jobs-grid {
          grid-template-columns: 1fr;
        }

        .modal-header,
        .modal-body,
        .modal-footer {
          padding: var(--spacing-lg);
        }

        .modal-footer {
          flex-direction: column;
          align-items: stretch;
        }
      }

      @media (max-width: 480px) {
        .settings-page {
          padding: 0 var(--spacing-sm);
        }

        .settings-section {
          padding: var(--spacing-md);
        }

        .checkbox-label {
          align-items: flex-start;
        }
      }
    `]
})
export class SettingsComponent implements OnInit, OnDestroy {
    archivedDecks: UserDeckDTO[] = [];
    loadingArchive = false;
    showDeleteConfirmation = false;
    showHardDeleteConfirmation = false;
    deckToHardDelete: string | null = null;
    deleteAccountUsername = '';
    currentUsername = '';

    readonly providerKeys = signal<AiProviderCredential[]>([]);
    readonly providersLoading = signal(false);
    readonly providerError = signal<string | null>(null);
    readonly createError = signal<string | null>(null);
    readonly createSuccess = signal<string | null>(null);
    readonly creatingProvider = signal(false);
    readonly deleteInFlight = signal(false);
    readonly providerName = signal('');
    readonly providerPreset = signal('openai');
    readonly providerAlias = signal('');
    readonly providerSecret = signal('');
    readonly deleteProviderTarget = signal<AiProviderCredential | null>(null);
    readonly canCreateProvider = computed(() =>
        !this.creatingProvider() &&
        this.providerName().trim().length > 0 &&
        this.providerSecret().trim().length > 0
    );

    readonly providerOptions = [
        { label: 'OpenAI', value: 'openai' },
        { label: 'Google Gemini', value: 'gemini' },
        { label: 'Anthropic', value: 'anthropic' },
        { label: 'DeepSeek', value: 'deepseek' },
        { label: 'GigaChat', value: 'gigachat' },
        { label: 'Custom', value: 'custom' }
    ];

    readonly jobType = signal<AiJobType>('enrich');
    readonly jobDeckId = signal('');
    readonly jobText = signal('');
    readonly jobCreating = signal(false);
    readonly jobError = signal<string | null>(null);
    readonly jobs = signal<AiJobEntry[]>([]);
    readonly cancelingJobs = signal<Set<string>>(new Set());
    readonly canCreateJob = computed(() =>
        !this.jobCreating() && this.jobText().trim().length > 0
    );

    private readonly jobPollers = new Map<string, number>();

    constructor(
        public theme: ThemeService,
        public i18n: I18nService,
        public preferences: PreferencesService,
        private deckApi: DeckApiService,
        private aiApi: AiApiService,
        private userApi: UserApiService,
        private auth: AuthService,
        private router: Router
    ) {}

    ngOnInit(): void {
        this.loadArchivedDecks();
        this.loadProviders();
        this.providerName.set(this.providerPreset());
        this.userApi.getMe().subscribe({
            next: profile => {
                this.currentUsername = profile.username;
            }
        });
    }

    ngOnDestroy(): void {
        this.jobPollers.forEach(id => window.clearInterval(id));
        this.jobPollers.clear();
    }

    loadArchivedDecks(): void {
        this.loadingArchive = true;
        this.deckApi.getDeletedDecks().subscribe({
            next: decks => {
                this.archivedDecks = decks;
                this.loadingArchive = false;
            },
            error: () => {
                this.loadingArchive = false;
            }
        });
    }

    loadProviders(): void {
        this.providersLoading.set(true);
        this.providerError.set(null);
        this.aiApi.listProviders().subscribe({
            next: providers => {
                this.providerKeys.set(providers);
                this.providersLoading.set(false);
            },
            error: () => {
                this.providerError.set('Failed to load provider keys.');
                this.providersLoading.set(false);
            }
        });
    }

    onProviderPresetChange(value: string): void {
        this.providerPreset.set(value);
        if (value === 'custom') {
            this.providerName.set('');
            return;
        }
        this.providerName.set(value);
    }

    createProvider(): void {
        if (!this.canCreateProvider()) {
            return;
        }
        this.creatingProvider.set(true);
        this.createError.set(null);
        this.createSuccess.set(null);
        this.aiApi.createProvider({
            provider: this.providerName().trim(),
            alias: this.providerAlias().trim() || null,
            secret: this.providerSecret()
        }).subscribe({
            next: provider => {
                this.providerKeys.update(list => [provider, ...list]);
                this.providerSecret.set('');
                this.createSuccess.set('Key saved securely.');
                this.creatingProvider.set(false);
            },
            error: () => {
                this.createError.set('Failed to save key.');
                this.creatingProvider.set(false);
            }
        });
    }

    openDeleteProvider(provider: AiProviderCredential): void {
        this.deleteProviderTarget.set(provider);
    }

    closeDeleteProvider(): void {
        this.deleteProviderTarget.set(null);
    }

    confirmDeleteProvider(): void {
        const target = this.deleteProviderTarget();
        if (!target) {
            return;
        }
        this.deleteInFlight.set(true);
        this.aiApi.deleteProvider(target.id).subscribe({
            next: () => {
                this.providerKeys.update(list => list.filter(item => item.id !== target.id));
                this.deleteInFlight.set(false);
                this.closeDeleteProvider();
            },
            error: () => {
                this.deleteInFlight.set(false);
                this.closeDeleteProvider();
            }
        });
    }

    maskedSecret(): string {
        return '********';
    }

    formatStatus(status: string): string {
        if (!status) {
            return 'Unknown';
        }
        return status.charAt(0).toUpperCase() + status.slice(1);
    }

    trackProvider(_: number, item: AiProviderCredential): string {
        return item.id;
    }

    createJob(): void {
        if (!this.canCreateJob()) {
            return;
        }
        this.jobCreating.set(true);
        this.jobError.set(null);
        const deckIdValue = this.jobDeckId().trim();
        if (deckIdValue && !this.isValidUuid(deckIdValue)) {
            this.jobCreating.set(false);
            this.jobError.set('Deck ID must be a valid UUID.');
            return;
        }
        const requestId = this.generateRequestId();
        const payload = {
            requestId,
            deckId: deckIdValue || null,
            type: this.jobType(),
            params: { text: this.jobText().trim() }
        };
        this.aiApi.createJob(payload).subscribe({
            next: job => {
                this.jobCreating.set(false);
                this.jobText.set('');
                this.upsertJob(job);
                this.startPolling(job.jobId);
            },
            error: () => {
                this.jobCreating.set(false);
                this.jobError.set('Failed to create AI job.');
            }
        });
    }

    cancelJob(jobId: string): void {
        const pending = new Set(this.cancelingJobs());
        if (pending.has(jobId)) {
            return;
        }
        pending.add(jobId);
        this.cancelingJobs.set(pending);
        this.aiApi.cancelJob(jobId).subscribe({
            next: job => {
                this.stopPolling(jobId);
                this.upsertJob(job);
                this.clearCanceling(jobId);
            },
            error: () => {
                this.clearCanceling(jobId);
            }
        });
    }

    trackJob(_: number, entry: AiJobEntry): string {
        return entry.job.jobId;
    }

    formatJobType(type: AiJobType): string {
        switch (type) {
            case 'enrich':
                return 'Enrich';
            case 'tts':
                return 'TTS';
            default:
                return 'AI Job';
        }
    }

    canCancel(status: AiJobStatus): boolean {
        return status === 'queued' || status === 'processing';
    }

    formatJson(value: unknown): string {
        try {
            return JSON.stringify(value ?? {}, null, 2);
        } catch {
            return '{}';
        }
    }

    private upsertJob(job: AiJobResponse): void {
        this.jobs.update(list => {
            const index = list.findIndex(entry => entry.job.jobId === job.jobId);
            if (index === -1) {
                return [{ job, resultSummary: null, resultLoading: false }, ...list];
            }
            const updated = [...list];
            updated[index] = { ...updated[index], job };
            return updated;
        });
    }

    private startPolling(jobId: string): void {
        if (this.jobPollers.has(jobId)) {
            return;
        }
        this.refreshJob(jobId);
        const id = window.setInterval(() => this.refreshJob(jobId), 3000);
        this.jobPollers.set(jobId, id);
    }

    private stopPolling(jobId: string): void {
        const id = this.jobPollers.get(jobId);
        if (id) {
            window.clearInterval(id);
            this.jobPollers.delete(jobId);
        }
    }

    private refreshJob(jobId: string): void {
        this.aiApi.getJob(jobId).subscribe({
            next: job => {
                this.upsertJob(job);
                if (job.status === 'completed' || job.status === 'failed' || job.status === 'canceled') {
                    this.stopPolling(jobId);
                    if (job.status === 'completed') {
                        this.fetchResult(jobId);
                    }
                }
            }
        });
    }

    private fetchResult(jobId: string): void {
        this.jobs.update(list => list.map(entry => entry.job.jobId === jobId
            ? { ...entry, resultLoading: true }
            : entry
        ));
        this.aiApi.getJobResult(jobId).subscribe({
            next: result => {
                this.jobs.update(list => list.map(entry => entry.job.jobId === jobId
                    ? { ...entry, resultLoading: false, resultSummary: result.resultSummary }
                    : entry
                ));
            },
            error: () => {
                this.jobs.update(list => list.map(entry => entry.job.jobId === jobId
                    ? { ...entry, resultLoading: false }
                    : entry
                ));
            }
        });
    }

    private clearCanceling(jobId: string): void {
        const pending = new Set(this.cancelingJobs());
        pending.delete(jobId);
        this.cancelingJobs.set(pending);
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

    private isValidUuid(value: string): boolean {
        return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value);
    }

    restoreDeck(userDeckId: string): void {
        this.deckApi.patchDeck(userDeckId, { archived: false }).subscribe({
            next: () => {
                this.archivedDecks = this.archivedDecks.filter(d => d.userDeckId !== userDeckId);
            },
            error: () => {
            }
        });
    }

    openDeck(userDeckId: string): void {
        void this.router.navigate(['/decks', userDeckId]);
    }

    openHardDeleteConfirm(userDeckId: string): void {
        this.deckToHardDelete = userDeckId;
        this.showHardDeleteConfirmation = true;
    }

    closeHardDeleteConfirm(): void {
        this.showHardDeleteConfirmation = false;
        this.deckToHardDelete = null;
    }

    confirmHardDelete(): void {
        if (!this.deckToHardDelete) return;

        const deckId = this.deckToHardDelete;
        this.deckApi.hardDeleteDeck(deckId).subscribe({
            next: () => {
                this.archivedDecks = this.archivedDecks.filter(d => d.userDeckId !== deckId);
                this.closeHardDeleteConfirm();
            },
            error: () => {
                this.closeHardDeleteConfirm();
            }
        });
    }

    deleteAccount(): void {
        this.showDeleteConfirmation = false;
        this.userApi.deleteMe().subscribe({
            next: () => {
                this.auth.logout();
                void this.router.navigate(['/']);
            },
            error: () => {
            }
        });
    }
}

type AiJobEntry = {
    job: AiJobResponse;
    resultSummary: unknown | null;
    resultLoading: boolean;
};
