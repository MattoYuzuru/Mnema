import { Component, OnInit } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { NgIf } from '@angular/common';
import { ReactiveFormsModule, FormBuilder, FormGroup, Validators } from '@angular/forms';
import { DeckApiService } from '../../core/services/deck-api.service';
import { PublicDeckApiService } from '../../core/services/public-deck-api.service';
import { UserDeckDTO } from '../../core/models/user-deck.models';
import { MemoryTipLoaderComponent } from '../../shared/components/memory-tip-loader.component';
import { ButtonComponent } from '../../shared/components/button.component';
import { AddCardsModalComponent } from './add-cards-modal.component';
import { ConfirmationDialogComponent } from '../../shared/components/confirmation-dialog.component';
import { InputComponent } from '../../shared/components/input.component';
import { TextareaComponent } from '../../shared/components/textarea.component';

@Component({
    selector: 'app-deck-profile',
    standalone: true,
    imports: [NgIf, ReactiveFormsModule, MemoryTipLoaderComponent, ButtonComponent, AddCardsModalComponent, ConfirmationDialogComponent, InputComponent, TextareaComponent],
    template: `
    <app-memory-tip-loader *ngIf="loading"></app-memory-tip-loader>

    <div *ngIf="!loading && deck" class="deck-profile">
      <header class="deck-header">
        <h1>{{ deck.displayName }}</h1>
        <p class="deck-description">{{ deck.displayDescription }}</p>
      </header>

      <div class="deck-meta">
        <div class="meta-item">
          <span class="meta-label">Algorithm:</span>
          <span class="meta-value">{{ deck.algorithmId }}</span>
        </div>
        <div class="meta-item">
          <span class="meta-label">Auto-update:</span>
          <span class="meta-value">{{ deck.autoUpdate ? 'Yes' : 'No' }}</span>
        </div>
        <div class="meta-item" *ngIf="deck.publicDeckId">
          <span class="meta-label">Version:</span>
          <span class="meta-value">{{ deck.currentVersion }}<span *ngIf="latestPublicVersion !== null"> / {{ latestPublicVersion }}</span></span>
        </div>
        <div class="meta-item" *ngIf="!deck.publicDeckId">
          <span class="meta-label">Version:</span>
          <span class="meta-value">{{ deck.currentVersion }}</span>
        </div>
      </div>

      <div class="deck-actions">
        <app-button variant="primary" size="md" (click)="learn()">
          Learn
        </app-button>
        <app-button variant="secondary" size="md" (click)="browse()">
          Browse Cards
        </app-button>
        <app-button variant="secondary" (click)="openAddCards()">
          Add Cards
        </app-button>
        <app-button variant="ghost" (click)="sync()" *ngIf="needsUpdate()">
          Sync to Latest
        </app-button>
        <app-button variant="secondary" (click)="openEditModal()">
          Edit
        </app-button>
        <app-button variant="ghost" (click)="openDeleteConfirm()">
          Delete
        </app-button>
      </div>
    </div>

    <app-add-cards-modal
      *ngIf="showAddCards && deck"
      [userDeckId]="deck.userDeckId"
      [publicDeckId]="deck.publicDeckId"
      (saved)="onCardsSaved()"
      (cancelled)="closeAddCards()"
    ></app-add-cards-modal>

    <div *ngIf="showEditModal && deck" class="modal-overlay" (click)="closeEditModal()">
      <div class="modal-content" (click)="$event.stopPropagation()">
        <div class="modal-header">
          <h2>Edit Deck</h2>
          <button class="close-btn" (click)="closeEditModal()">&times;</button>
        </div>
        <div class="modal-body">
          <form [formGroup]="editForm" class="edit-form">
            <app-input
              label="Display Name *"
              formControlName="displayName"
              [hasError]="editForm.get('displayName')?.invalid && editForm.get('displayName')?.touched || false"
              errorMessage="Name is required"
            ></app-input>
            <app-textarea
              label="Description"
              formControlName="displayDescription"
              [rows]="4"
            ></app-textarea>
            <div class="checkbox-group">
              <label>
                <input type="checkbox" formControlName="autoUpdate" />
                Auto-update when new version is available
              </label>
            </div>
            <div class="checkbox-group">
              <label>
                <input type="checkbox" formControlName="archived" />
                Archive this deck
              </label>
            </div>
          </form>
        </div>
        <div class="modal-footer">
          <app-button variant="ghost" (click)="closeEditModal()" [disabled]="saving">Cancel</app-button>
          <app-button variant="primary" (click)="saveEdit()" [disabled]="editForm.invalid || saving">
            {{ saving ? 'Saving...' : 'Save' }}
          </app-button>
        </div>
      </div>
    </div>

    <app-confirmation-dialog
      [open]="showDeleteConfirm"
      title="Delete Deck"
      message="Are you sure you want to delete this deck? This will archive it and it can be restored later."
      confirmText="Delete"
      cancelText="Cancel"
      (confirm)="confirmDelete()"
      (cancel)="closeDeleteConfirm()"
    ></app-confirmation-dialog>
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
    `]
})
export class DeckProfileComponent implements OnInit {
    loading = true;
    deck: UserDeckDTO | null = null;
    latestPublicVersion: number | null = null;
    userDeckId = '';
    showAddCards = false;
    showEditModal = false;
    showDeleteConfirm = false;
    saving = false;
    editForm!: FormGroup;

    constructor(
        private route: ActivatedRoute,
        private router: Router,
        private deckApi: DeckApiService,
        private publicDeckApi: PublicDeckApiService,
        private fb: FormBuilder
    ) {}

    ngOnInit(): void {
        this.userDeckId = this.route.snapshot.paramMap.get('userDeckId') || '';
        if (this.userDeckId) {
            this.loadDeck();
        }
    }

    private loadDeck(): void {
        this.deckApi.getUserDeck(this.userDeckId).subscribe({
            next: deck => {
                this.deck = deck;
                if (deck.publicDeckId) {
                    this.loadLatestPublicVersion(deck.publicDeckId);
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

    private loadLatestPublicVersion(publicDeckId: string): void {
        this.publicDeckApi.getPublicDeck(publicDeckId).subscribe({
            next: publicDeck => {
                this.latestPublicVersion = publicDeck.version;
                this.loading = false;
            },
            error: err => {
                console.error('Failed to load public deck version:', err);
                this.loading = false;
            }
        });
    }

    needsUpdate(): boolean {
        if (!this.deck || !this.deck.publicDeckId) return false;
        if (this.latestPublicVersion === null) return false;
        return this.deck.currentVersion < this.latestPublicVersion;
    }

    openAddCards(): void {
        this.showAddCards = true;
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
                    this.loadLatestPublicVersion(updatedDeck.publicDeckId);
                }
                console.log('Deck synced successfully');
            },
            error: err => {
                console.error('Failed to sync deck:', err);
            }
        });
    }

    openEditModal(): void {
        if (this.deck) {
            this.editForm = this.fb.group({
                displayName: [this.deck.displayName, Validators.required],
                displayDescription: [this.deck.displayDescription],
                autoUpdate: [this.deck.autoUpdate],
                archived: [this.deck.archived]
            });
            this.showEditModal = true;
        }
    }

    closeEditModal(): void {
        this.showEditModal = false;
    }

    saveEdit(): void {
        if (this.editForm.invalid) return;

        this.saving = true;
        const updates = this.editForm.value;

        this.deckApi.patchDeck(this.userDeckId, updates).subscribe({
            next: updatedDeck => {
                this.deck = updatedDeck;
                this.saving = false;
                this.showEditModal = false;
            },
            error: err => {
                console.error('Failed to update deck:', err);
                this.saving = false;
            }
        });
    }

    openDeleteConfirm(): void {
        this.showDeleteConfirm = true;
    }

    closeDeleteConfirm(): void {
        this.showDeleteConfirm = false;
    }

    confirmDelete(): void {
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
