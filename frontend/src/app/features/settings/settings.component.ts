import { Component, OnInit } from '@angular/core';
import { Router } from '@angular/router';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ThemeService } from '../../core/services/theme.service';
import { I18nService, Language } from '../../core/services/i18n.service';
import { PreferencesService } from '../../core/services/preferences.service';
import { DeckApiService } from '../../core/services/deck-api.service';
import { UserApiService } from '../../user-api.service';
import { AuthService } from '../../auth.service';
import { UserDeckDTO } from '../../core/models/user-deck.models';
import { ButtonComponent } from '../../shared/components/button.component';
import { ConfirmationDialogComponent } from '../../shared/components/confirmation-dialog.component';
import { TranslatePipe } from '../../shared/pipes/translate.pipe';

@Component({
    selector: 'app-settings',
    standalone: true,
    imports: [NgFor, NgIf, FormsModule, ButtonComponent, ConfirmationDialogComponent, TranslatePipe],
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
              <app-button variant="ghost" size="sm" (click)="openHardDeleteConfirm(deck.userDeckId)" class="delete-btn-small">
                Delete Permanently
              </app-button>
            </div>
          </div>
        </div>
      </section>

      <section class="settings-section danger-zone">
        <h2>{{ 'settings.dangerZone' | translate }}</h2>
        <p class="section-description">{{ 'settings.deleteAccountWarning' | translate }}</p>

        <app-button variant="ghost" (click)="showDeleteConfirmation = true" class="delete-btn">
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
            [disabled]="deleteAccountUsername !== currentUsername"
            (click)="deleteAccount()"
            class="delete-btn"
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

      .danger-zone {
        border-color: #dc2626;
      }

      .danger-zone h2 {
        color: #dc2626;
      }

      .delete-btn {
        color: #dc2626;
        border-color: #dc2626;
      }

      .delete-btn:hover {
        background: #dc2626;
        color: white;
      }

      .delete-btn-small {
        color: #dc2626;
      }

      .delete-btn-small:hover {
        background: #fee2e2;
        color: #dc2626;
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
export class SettingsComponent implements OnInit {
    archivedDecks: UserDeckDTO[] = [];
    loadingArchive = false;
    showDeleteConfirmation = false;
    showHardDeleteConfirmation = false;
    deckToHardDelete: string | null = null;
    deleteAccountUsername = '';
    currentUsername = '';

    constructor(
        public theme: ThemeService,
        public i18n: I18nService,
        public preferences: PreferencesService,
        private deckApi: DeckApiService,
        private userApi: UserApiService,
        private auth: AuthService,
        private router: Router
    ) {}

    ngOnInit(): void {
        this.loadArchivedDecks();
        this.userApi.getMe().subscribe({
            next: profile => {
                this.currentUsername = profile.username;
            }
        });
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
