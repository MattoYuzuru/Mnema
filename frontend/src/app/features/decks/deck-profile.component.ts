import { Component, OnInit } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { NgIf } from '@angular/common';
import { DeckApiService } from '../../core/services/deck-api.service';
import { UserDeckDTO } from '../../core/models/user-deck.models';
import { MemoryTipLoaderComponent } from '../../shared/components/memory-tip-loader.component';
import { ButtonComponent } from '../../shared/components/button.component';
import { AddCardsModalComponent } from './add-cards-modal.component';

@Component({
    selector: 'app-deck-profile',
    standalone: true,
    imports: [NgIf, MemoryTipLoaderComponent, ButtonComponent, AddCardsModalComponent],
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
        <div class="meta-item">
          <span class="meta-label">Version:</span>
          <span class="meta-value">{{ deck.subscribedVersion }} / {{ deck.currentVersion }}</span>
        </div>
      </div>

      <div class="deck-actions">
        <app-button variant="primary" size="lg" (click)="learn()">
          Learn
        </app-button>
        <app-button variant="secondary" (click)="browse()">
          Browse Cards
        </app-button>
        <app-button variant="secondary" (click)="openAddCards()">
          Add Cards
        </app-button>
        <app-button variant="ghost" (click)="sync()" *ngIf="needsUpdate()">
          Sync to Latest
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
    `]
})
export class DeckProfileComponent implements OnInit {
    loading = true;
    deck: UserDeckDTO | null = null;
    userDeckId = '';
    showAddCards = false;

    constructor(
        private route: ActivatedRoute,
        private router: Router,
        private deckApi: DeckApiService
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
                this.loading = false;
            },
            error: err => {
                console.error('Failed to load deck:', err);
                this.loading = false;
            }
        });
    }

    needsUpdate(): boolean {
        if (!this.deck) return false;
        return this.deck.subscribedVersion < this.deck.currentVersion;
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
                console.log('Deck synced successfully');
            },
            error: err => {
                console.error('Failed to sync deck:', err);
            }
        });
    }
}
