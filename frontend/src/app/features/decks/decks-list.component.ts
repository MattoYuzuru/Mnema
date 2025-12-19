import { Component, OnInit } from '@angular/core';
import { Router } from '@angular/router';
import { NgIf, NgFor } from '@angular/common';
import { from, of } from 'rxjs';
import { catchError, mergeMap } from 'rxjs/operators';
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
        display: grid;
        grid-template-columns: 1fr;
        gap: var(--spacing-md);
      }

      @media (min-width: 768px) {
        .decks-list {
          grid-template-columns: repeat(auto-fill, minmax(350px, 1fr));
        }
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
    private static deckSizesCache: Map<string, { size: number; timestamp: number }> = new Map();
    private static readonly CACHE_TTL_MS = 5 * 60 * 1000;
    private deckSizes: Map<string, number> = new Map();

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

                if (this.decks.length > 0) {
                    this.loadDeckSizes();
                }
            },
            error: () => {
                this.loading = false;
            }
        });
    }

    private loadDeckSizes(): void {
        const now = Date.now();

        from(this.decks)
            .pipe(
                mergeMap(deck => {
                    const cached = DecksListComponent.deckSizesCache.get(deck.userDeckId);
                    if (cached && (now - cached.timestamp) < DecksListComponent.CACHE_TTL_MS) {
                        this.deckSizes.set(deck.userDeckId, cached.size);
                        return of(null);
                    }

                    return this.deckApi.getUserDeckSize(deck.userDeckId).pipe(
                        catchError(() => of(null))
                    );
                }, 4)
            )
            .subscribe({
                next: response => {
                    if (response) {
                        this.deckSizes.set(response.deckId, response.cardsQty);
                        DecksListComponent.deckSizesCache.set(response.deckId, {
                            size: response.cardsQty,
                            timestamp: now
                        });
                    }
                }
            });
    }

    getDeckStats(deck: UserDeckDTO): { cardCount?: number; dueToday?: number } {
        const size = this.deckSizes.get(deck.userDeckId);
        return {
            cardCount: size,
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
