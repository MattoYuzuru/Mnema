import { Component, OnInit, OnDestroy } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { NgIf, NgFor } from '@angular/common';
import { ReactiveFormsModule, FormsModule, FormBuilder, FormGroup, Validators } from '@angular/forms';
import { forkJoin } from 'rxjs';
import { DeckApiService } from '../../core/services/deck-api.service';
import { PublicDeckApiService } from '../../core/services/public-deck-api.service';
import { ReviewApiService } from '../../core/services/review-api.service';
import { ImportApiService } from '../../core/services/import-api.service';
import { MediaApiService } from '../../core/services/media-api.service';
import { UserApiService } from '../../user-api.service';
import { UserDeckDTO } from '../../core/models/user-deck.models';
import { PublicDeckDTO } from '../../core/models/public-deck.models';
import { ReviewDeckAlgorithmResponse } from '../../core/models/review.models';
import { ImportJobResponse } from '../../core/models/import.models';
import { MemoryTipLoaderComponent } from '../../shared/components/memory-tip-loader.component';
import { ButtonComponent } from '../../shared/components/button.component';
import { AddCardsModalComponent } from './add-cards-modal.component';
import { ConfirmationDialogComponent } from '../../shared/components/confirmation-dialog.component';
import { InputComponent } from '../../shared/components/input.component';
import { TextareaComponent } from '../../shared/components/textarea.component';
import { TranslatePipe } from '../../shared/pipes/translate.pipe';
import { ImportDeckModalComponent } from '../import/import-deck-modal.component';

@Component({
    selector: 'app-deck-profile',
    standalone: true,
    imports: [NgIf, NgFor, ReactiveFormsModule, FormsModule, MemoryTipLoaderComponent, ButtonComponent, AddCardsModalComponent, ConfirmationDialogComponent, InputComponent, TextareaComponent, ImportDeckModalComponent, TranslatePipe],
    template: `
    <app-memory-tip-loader *ngIf="loading"></app-memory-tip-loader>

    <div *ngIf="!loading && deck" class="deck-profile">
      <header class="deck-header">
        <h1>{{ deck.displayName }}</h1>
        <p class="deck-description">{{ deck.displayDescription }}</p>
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
        <app-button variant="secondary" (click)="openExportConfirm()" [disabled]="exporting">
          {{ exporting ? ('deckProfile.exporting' | translate) : ('deckProfile.export' | translate) }}
        </app-button>
        <app-button variant="ghost" (click)="sync()" *ngIf="needsUpdate()">
          {{ 'deckProfile.sync' | translate }}
        </app-button>
        <app-button variant="secondary" (click)="openEditModal()">
          {{ 'deckProfile.edit' | translate }}
        </app-button>
        <app-button variant="ghost" (click)="openDeleteConfirm()">
          {{ 'deckProfile.delete' | translate }}
        </app-button>
      </div>

      <p *ngIf="exportStatusKey" class="export-status">{{ exportStatusKey | translate }}</p>
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
          </div>
        </div>
      </div>
    </div>

    <app-add-cards-modal
      *ngIf="showAddCards && deck"
      [userDeckId]="deck.userDeckId"
      [publicDeckId]="deck.publicDeckId"
      (saved)="onCardsSaved()"
      (cancelled)="closeAddCards()"
    ></app-add-cards-modal>

    <app-import-deck-modal
      *ngIf="showImportModal && deck"
      mode="merge"
      [targetDeckId]="deck.userDeckId"
      (closed)="closeImportModal()"
    ></app-import-deck-modal>

    <div *ngIf="showEditModal && deck" class="modal-overlay" (click)="closeEditModal()">
      <div class="modal-content" (click)="$event.stopPropagation()">
        <div class="modal-header">
          <h2>{{ 'deckProfile.editDeck' | translate }}</h2>
          <button class="close-btn" (click)="closeEditModal()">&times;</button>
        </div>
        <div class="modal-body">
          <form [formGroup]="editForm" class="edit-form">
            <app-input
              [label]="('deckProfile.displayName' | translate) + ' *'"
              formControlName="displayName"
              [hasError]="editForm.get('displayName')?.invalid && editForm.get('displayName')?.touched || false"
              [errorMessage]="'deckProfile.required' | translate"
            ></app-input>
            <app-textarea
              [label]="'deckProfile.description' | translate"
              formControlName="displayDescription"
              [rows]="4"
            ></app-textarea>
            <div class="checkbox-group">
              <label>
                <input type="checkbox" formControlName="autoUpdate" />
                {{ 'deckProfile.autoUpdateLabel' | translate }}
              </label>
            </div>

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
              </select>
              <p *ngIf="currentAlgorithm && currentAlgorithm.pendingMigrationCards > 0" class="migration-info">
                {{ currentAlgorithm.pendingMigrationCards }} {{ 'deckProfile.pendingMigrationText' | translate }}
              </p>
            </div>

            <div class="review-preferences-section">
              <h4 class="subsection-title">{{ 'deckProfile.reviewPreferences' | translate }}</h4>
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
            </div>

            <div *ngIf="isAuthor" class="public-deck-section">
              <h3 class="section-title">{{ 'deckProfile.publicDeckSettings' | translate }}</h3>
              <app-input
                [label]="('deckProfile.publicDeckName' | translate) + ' *'"
                formControlName="publicName"
                [hasError]="editForm.get('publicName')?.invalid && editForm.get('publicName')?.touched || false"
                [errorMessage]="'deckProfile.required' | translate"
              ></app-input>
              <app-textarea
                [label]="'deckProfile.publicDescription' | translate"
                formControlName="publicDescription"
                [rows]="4"
              ></app-textarea>
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
                <input type="text" class="tag-input" [(ngModel)]="tagInput" [ngModelOptions]="{standalone: true}" (keydown.enter)="addTag($event)" [placeholder]="'deckProfile.tagsPlaceholder' | translate" />
                <div *ngIf="tags.length > 0" class="tags-list">
                  <span *ngFor="let tag of tags; let i = index" class="tag-chip">{{ tag }} <button type="button" (click)="removeTag(i)">×</button></span>
                </div>
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

    <app-confirmation-dialog
      [open]="showExportConfirm"
      [title]="'deckProfile.exportTitle' | translate"
      [message]="'deckProfile.exportMessage' | translate"
      [confirmText]="'deckProfile.exportConfirm' | translate"
      [cancelText]="'deckProfile.cancel' | translate"
      (confirm)="confirmExport()"
      (cancel)="closeExportConfirm()"
    ></app-confirmation-dialog>

    <div *ngIf="showScopePrompt" class="modal-overlay" (click)="closeScopePrompt()">
      <div class="modal-content scope-prompt" (click)="$event.stopPropagation()">
        <div class="modal-header">
          <h2>Apply Changes</h2>
          <button class="close-btn" (click)="closeScopePrompt()">&times;</button>
        </div>
        <div class="modal-body">
          <p>Would you like to apply your changes globally (to the public deck) or locally (to your copy only)?</p>
          <div class="scope-buttons">
            <app-button variant="secondary" (click)="applyScopeChoice('local')">
              Apply Locally
            </app-button>
            <app-button variant="primary" (click)="applyScopeChoice('global')">
              Apply Globally
            </app-button>
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

      .modal-overlay {
        position: fixed;
        top: 0;
        left: 0;
        right: 0;
        bottom: 0;
        background: rgba(0, 0, 0, 0.5);
        display: flex;
        align-items: center;
        justify-content: center;
        z-index: 1000;
      }

      .modal-content {
        background: var(--color-card-background);
        border-radius: var(--border-radius-lg);
        max-width: 600px;
        width: 90%;
        max-height: 90vh;
        display: flex;
        flex-direction: column;
        box-shadow: 0 20px 25px -5px rgba(0, 0, 0, 0.1);
      }

      .modal-header {
        display: flex;
        justify-content: space-between;
        align-items: center;
        padding: var(--spacing-lg);
        border-bottom: 1px solid var(--border-color);
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

      .edit-form {
        display: flex;
        flex-direction: column;
        gap: var(--spacing-md);
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
        border-top: 1px solid var(--border-color);
      }

      .scope-prompt {
        max-width: 32rem;
      }

      .scope-buttons {
        display: flex;
        gap: var(--spacing-md);
        margin-top: var(--spacing-lg);
      }

      .public-deck-section {
        margin-top: var(--spacing-xl);
        padding-top: var(--spacing-xl);
        border-top: 1px solid var(--border-color);
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
      }

      .subsection-title {
        font-size: 1rem;
        font-weight: 600;
        margin: 0 0 var(--spacing-md) 0;
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
        background: #111827;
        color: #fff;
        display: flex;
        align-items: center;
        justify-content: center;
        font-size: 1.8rem;
      }
    `]
})
export class DeckProfileComponent implements OnInit, OnDestroy {
    loading = true;
    deck: UserDeckDTO | null = null;
    publicDeck: PublicDeckDTO | null = null;
    latestPublicVersion: number | null = null;
    currentUserId: string | null = null;
    isAuthor = false;
    userDeckId = '';
    showAddCards = false;
    showAddCardsChoice = false;
    showImportModal = false;
    showEditModal = false;
    showDeleteConfirm = false;
    showExportConfirm = false;
    showScopePrompt = false;
    saving = false;
    exporting = false;
    exportStatusKey: string | null = null;
    exportJob: ImportJobResponse | null = null;
    editForm!: FormGroup;
    tagInput = '';
    tags: string[] = [];
    currentAlgorithm: ReviewDeckAlgorithmResponse | null = null;
    originalAlgorithmId = '';
    pendingSaveChoice: 'local' | 'global' | null = null;

    private exportPollHandle: ReturnType<typeof setInterval> | null = null;

    constructor(
        private route: ActivatedRoute,
        private router: Router,
        private deckApi: DeckApiService,
        private publicDeckApi: PublicDeckApiService,
        private reviewApi: ReviewApiService,
        private userApi: UserApiService,
        private fb: FormBuilder,
        private importApi: ImportApiService,
        private mediaApi: MediaApiService
    ) {}

    ngOnInit(): void {
        this.userDeckId = this.route.snapshot.paramMap.get('userDeckId') || '';
        if (this.userDeckId) {
            this.loadDeck();
        }
    }

    ngOnDestroy(): void {
        this.stopExportPolling();
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

    closeImportModal(): void {
        this.showImportModal = false;
    }

    closeAddCards(): void {
        this.showAddCards = false;
    }

    onCardsSaved(): void {
        this.showAddCards = false;
        console.log('Cards saved successfully');
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

    openExportConfirm(): void {
        this.showExportConfirm = true;
    }

    closeExportConfirm(): void {
        this.showExportConfirm = false;
    }

    confirmExport(): void {
        if (!this.deck) {
            return;
        }
        this.showExportConfirm = false;
        this.exporting = true;
        this.exportStatusKey = 'deckProfile.exportPreparing';

        this.importApi.createExportJob({ userDeckId: this.deck.userDeckId, format: 'csv' }).subscribe({
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

    openEditModal(): void {
        if (this.deck) {
            this.reviewApi.getDeckAlgorithm(this.userDeckId).subscribe({
                next: algorithmData => {
                    this.currentAlgorithm = algorithmData;
                    this.originalAlgorithmId = algorithmData.algorithmId;

                    const preferences = algorithmData.reviewPreferences;

                    const formConfig: any = {
                        displayName: [this.deck!.displayName, Validators.required],
                        displayDescription: [this.deck!.displayDescription],
                        autoUpdate: [this.deck!.autoUpdate],
                        algorithmId: [algorithmData.algorithmId, Validators.required],
                        dailyNewLimit: [preferences?.dailyNewLimit ?? 20],
                        learningHorizonHours: [preferences?.learningHorizonHours ?? 24]
                    };

                    if (this.isAuthor && this.publicDeck) {
                        formConfig.publicName = [this.publicDeck.name, Validators.required];
                        formConfig.publicDescription = [this.publicDeck.description];
                        formConfig.isPublic = [this.publicDeck.isPublic];
                        formConfig.isListed = [this.publicDeck.isListed];
                        formConfig.language = [this.publicDeck.language];
                        this.tags = [...(this.publicDeck.tags || [])];
                    } else {
                        this.tags = [];
                    }

                    this.tagInput = '';
                    this.editForm = this.fb.group(formConfig);
                    this.showEditModal = true;
                }
            });
        }
    }

    addTag(event: Event): void {
        event.preventDefault();
        const tag = this.tagInput.trim();
        if (tag && !this.tags.includes(tag)) {
            this.tags.push(tag);
            this.tagInput = '';
        }
    }

    removeTag(index: number): void {
        this.tags.splice(index, 1);
    }

    closeEditModal(): void {
        this.showEditModal = false;
    }

    saveEdit(): void {
        if (this.editForm.invalid) return;

        const formValue = this.editForm.value;

        if (this.isAuthor && this.publicDeck && this.hasPublicDeckChanges() && this.pendingSaveChoice === null) {
            this.showScopePrompt = true;
            return;
        }

        this.saving = true;

        const userDeckUpdates: Partial<UserDeckDTO> = {
            displayName: formValue.displayName,
            displayDescription: formValue.displayDescription,
            autoUpdate: formValue.autoUpdate
        };

        const algorithmChanged = formValue.algorithmId !== this.originalAlgorithmId;
        const hasPreferenceValues = formValue.dailyNewLimit !== undefined || formValue.learningHorizonHours !== undefined;

        const requests: any = {
            userDeck: this.deckApi.patchDeck(this.userDeckId, userDeckUpdates)
        };

        if (algorithmChanged || hasPreferenceValues) {
            const reviewPreferences = {
                dailyNewLimit: formValue.dailyNewLimit !== undefined ? Number(formValue.dailyNewLimit) : null,
                learningHorizonHours: formValue.learningHorizonHours !== undefined ? Number(formValue.learningHorizonHours) : null
            };

            requests.algorithm = this.reviewApi.updateDeckAlgorithm(this.userDeckId, {
                algorithmId: formValue.algorithmId,
                algorithmParams: null,
                reviewPreferences: reviewPreferences
            });
        }

        if (this.isAuthor && this.publicDeck && this.pendingSaveChoice === 'global') {
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
                this.pendingSaveChoice = null;
            },
            error: () => {
                this.saving = false;
                this.pendingSaveChoice = null;
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

    applyScopeChoice(choice: 'local' | 'global'): void {
        this.pendingSaveChoice = choice;
        this.showScopePrompt = false;
        this.saveEdit();
    }

    closeScopePrompt(): void {
        this.showScopePrompt = false;
        this.pendingSaveChoice = null;
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
