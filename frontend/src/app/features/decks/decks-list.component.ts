import { Component, OnInit } from '@angular/core';
import { Router } from '@angular/router';
import { NgIf, NgFor } from '@angular/common';
import { DeckApiService } from '../../core/services/deck-api.service';
import { UserDeckDTO } from '../../core/models/user-deck.models';
import { DeckCardComponent } from '../../shared/components/deck-card.component';
import { MemoryTipLoaderComponent } from '../../shared/components/memory-tip-loader.component';
import { EmptyStateComponent } from '../../shared/components/empty-state.component';
import { ButtonComponent } from '../../shared/components/button.component';

@Component({
    selector: 'app-decks-list',
    standalone: true,
    imports: [NgIf, NgFor, DeckCardComponent, MemoryTipLoaderComponent, EmptyStateComponent, ButtonComponent],
    template: `
    <app-memory-tip-loader *ngIf="loading"></app-memory-tip-loader>

    <div *ngIf="!loading" class="decks-list-page">
      <header class="page-header">
        <h1>My Decks</h1>
        <app-button variant="primary" (click)="createDeck()">
          Create Deck
        </app-button>
      </header>

      <div *ngIf="decks.length > 0" class="decks-list">
        <app-deck-card
          *ngFor="let deck of decks"
          [userDeck]="deck"
          [showLearn]="true"
          [showBrowse]="true"
          [showUpdate]="needsUpdate(deck)"
          [stats]="getDeckStats(deck)"
          (open)="openDeck(deck.userDeckId)"
          (learn)="learnDeck(deck.userDeckId)"
          (browse)="browseDeck(deck.userDeckId)"
          (update)="syncDeck(deck.userDeckId)"
        ></app-deck-card>
      </div>

      <app-empty-state
        *ngIf="decks.length === 0"
        icon="📚"
        title="No decks yet"
        description="Create your first deck or fork a public deck to get started learning"
        actionText="Browse Public Decks"
        (action)="goToPublicDecks()"
      ></app-empty-state>
    </div>
  `,
    styles: [
        `
      .decks-list-page {
        max-width: 72rem;
        margin: 0 auto;
      }

      .page-header {
        display: flex;
        align-items: center;
        justify-content: space-between;
        margin-bottom: var(--spacing-xl);
      }

      .page-header h1 {
        font-size: 2rem;
        margin: 0;
      }

      .decks-list {
        display: flex;
        flex-direction: column;
        gap: var(--spacing-md);
      }

      @media (max-width: 768px) {
        .decks-list-page {
          padding: 0 var(--spacing-md);
        }

        .page-header {
          flex-direction: column;
          align-items: flex-start;
          gap: var(--spacing-md);
        }

        .page-header h1 {
          font-size: 1.5rem;
        }
      }
    `
    ]
})
export class DecksListComponent implements OnInit {
    loading = true;
    decks: UserDeckDTO[] = [];

    constructor(
        private deckApi: DeckApiService,
        private router: Router
    ) {}

    ngOnInit(): void {
        this.loadDecks();
    }

    private loadDecks(): void {
        this.deckApi.getMyDecks(1, 50).subscribe({
            next: page => {
                this.decks = page.content;
                this.loading = false;
            },
            error: err => {
                console.error('Failed to load decks:', err);
                this.loading = false;
            }
        });
    }

    getDeckStats(deck: UserDeckDTO): { cardCount?: number; dueToday?: number } {
        return {
            cardCount: 0,
            dueToday: undefined
        };
    }

    needsUpdate(deck: UserDeckDTO): boolean {
        return deck.subscribedVersion < deck.currentVersion;
    }

    openDeck(userDeckId: string): void {
        void this.router.navigate(['/decks', userDeckId]);
    }

    learnDeck(userDeckId: string): void {
        void this.router.navigate(['/decks', userDeckId, 'review']);
    }

    browseDeck(userDeckId: string): void {
        void this.router.navigate(['/decks', userDeckId, 'browse']);
    }

    syncDeck(userDeckId: string): void {
        console.log('Syncing deck:', userDeckId);
        this.deckApi.syncDeck(userDeckId).subscribe({
            next: updatedDeck => {
                console.log('Deck synced successfully');
                const index = this.decks.findIndex(d => d.userDeckId === userDeckId);
                if (index !== -1) {
                    this.decks[index] = updatedDeck;
                }
            },
            error: err => {
                console.error('Failed to sync deck:', err);
            }
        });
    }

    createDeck(): void {
        void this.router.navigate(['/create-deck']);
    }

    goToPublicDecks(): void {
        void this.router.navigate(['/public-decks']);
    }
}
