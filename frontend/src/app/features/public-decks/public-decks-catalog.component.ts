import { Component, OnInit } from '@angular/core';
import { Router } from '@angular/router';
import { NgIf, NgFor } from '@angular/common';
import { AuthService } from '../../auth.service';
import { PublicDeckApiService } from '../../core/services/public-deck-api.service';
import { PublicDeckDTO } from '../../core/models/public-deck.models';
import { DeckCardComponent } from '../../shared/components/deck-card.component';
import { MemoryTipLoaderComponent } from '../../shared/components/memory-tip-loader.component';
import { EmptyStateComponent } from '../../shared/components/empty-state.component';

@Component({
    selector: 'app-public-decks-catalog',
    standalone: true,
    imports: [NgIf, NgFor, DeckCardComponent, MemoryTipLoaderComponent, EmptyStateComponent],
    template: `
    <app-memory-tip-loader *ngIf="loading"></app-memory-tip-loader>

    <div *ngIf="!loading" class="public-decks-catalog">
      <header class="page-header">
        <h1>Public Decks</h1>
        <p class="subtitle">Discover and fork community-created flashcard decks</p>
      </header>

      <div *ngIf="decks.length > 0" class="decks-list">
        <app-deck-card
          *ngFor="let deck of decks"
          [publicDeck]="deck"
          [showFork]="auth.status() === 'authenticated'"
          [showBrowse]="true"
          (open)="openDeck(deck.deckId)"
          (fork)="forkDeck(deck.deckId)"
          (browse)="browseDeck(deck.deckId)"
        ></app-deck-card>
      </div>

      <app-empty-state
        *ngIf="decks.length === 0"
        icon="🌐"
        title="No public decks available"
        description="Public decks will appear here once they are published by the community"
      ></app-empty-state>
    </div>
  `,
    styles: [`
      .public-decks-catalog {
        max-width: 72rem;
        margin: 0 auto;
      }

      .page-header {
        margin-bottom: var(--spacing-xl);
      }

      .page-header h1 {
        font-size: 2rem;
        margin: 0 0 var(--spacing-sm) 0;
      }

      .subtitle {
        font-size: 1.1rem;
        color: var(--color-text-muted);
        margin: 0;
      }

      .decks-list {
        display: flex;
        flex-direction: column;
        gap: var(--spacing-md);
      }
    `]
})
export class PublicDecksCatalogComponent implements OnInit {
    loading = true;
    decks: PublicDeckDTO[] = [];

    constructor(
        public auth: AuthService,
        private publicDeckApi: PublicDeckApiService,
        private router: Router
    ) {}

    ngOnInit(): void {
        this.loadDecks();
    }

    private loadDecks(): void {
        this.publicDeckApi.getPublicDecks(1, 50).subscribe({
            next: page => {
                this.decks = page.content;
                this.loading = false;
            },
            error: err => {
                console.error('Failed to load public decks:', err);
                this.loading = false;
            }
        });
    }

    openDeck(deckId: string): void {
        void this.router.navigate(['/public-decks', deckId, 'browse']);
    }

    browseDeck(deckId: string): void {
        void this.router.navigate(['/public-decks', deckId, 'browse']);
    }

    forkDeck(deckId: string): void {
        if (this.auth.status() !== 'authenticated') {
            this.auth.beginLogin();
            return;
        }

        this.publicDeckApi.fork(deckId).subscribe({
            next: userDeck => {
                void this.router.navigate(['/decks', userDeck.userDeckId]);
            },
            error: err => {
                console.error('Failed to fork deck:', err);
            }
        });
    }
}
