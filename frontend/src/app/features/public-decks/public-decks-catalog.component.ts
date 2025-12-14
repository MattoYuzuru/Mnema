import { Component, OnInit } from '@angular/core';
import { Router } from '@angular/router';
import { NgIf, NgFor } from '@angular/common';
import { catchError } from 'rxjs/operators';
import { forkJoin, of } from 'rxjs';
import { AuthService } from '../../auth.service';
import { UserApiService } from '../../user-api.service';
import { PublicDeckApiService } from '../../core/services/public-deck-api.service';
import { PublicDeckDTO } from '../../core/models/public-deck.models';
import { DeckCardComponent } from '../../shared/components/deck-card.component';
import { MemoryTipLoaderComponent } from '../../shared/components/memory-tip-loader.component';
import { EmptyStateComponent } from '../../shared/components/empty-state.component';
import { TranslatePipe } from '../../shared/pipes/translate.pipe';

@Component({
    selector: 'app-public-decks-catalog',
    standalone: true,
    imports: [NgIf, NgFor, DeckCardComponent, MemoryTipLoaderComponent, EmptyStateComponent, TranslatePipe],
    template: `
    <app-memory-tip-loader *ngIf="loading"></app-memory-tip-loader>

    <div *ngIf="!loading" class="public-decks-catalog">
      <header class="page-header">
        <h1>{{ 'publicDecks.title' | translate }}</h1>
        <p class="subtitle">{{ 'publicDecks.subtitle' | translate }}</p>
      </header>

      <div *ngIf="decks.length > 0" class="decks-list">
        <app-deck-card
          *ngFor="let deck of decks"
          [publicDeck]="deck"
          [showFork]="canForkDeck(deck)"
          [showBrowse]="true"
          (open)="openDeck(deck.deckId)"
          (fork)="forkDeck(deck.deckId)"
          (browse)="browseDeck(deck.deckId)"
        ></app-deck-card>
      </div>

      <app-empty-state
        *ngIf="decks.length === 0"
        icon="🌐"
        [title]="'home.noPublicDecks' | translate"
        [description]="'home.noPublicDecksDescription' | translate"
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
    currentUserId: string | null = null;

    constructor(
        public auth: AuthService,
        private userApi: UserApiService,
        private publicDeckApi: PublicDeckApiService,
        private router: Router
    ) {}

    ngOnInit(): void {
        this.loadDecks();
    }

    private loadDecks(): void {
        const decks$ = this.publicDeckApi.getPublicDecks(1, 50).pipe(
            catchError(() => of({ content: [] as PublicDeckDTO[] }))
        );

        const user$ = this.auth.status() === 'authenticated'
            ? this.userApi.getMe().pipe(catchError(() => of(null)))
            : of(null);

        forkJoin({ decks: decks$, user: user$ }).subscribe({
            next: result => {
                this.decks = result.decks.content;
                this.currentUserId = result.user?.id || null;
                this.loading = false;
            },
            error: () => {
                this.loading = false;
            }
        });
    }

    canForkDeck(deck: PublicDeckDTO): boolean {
        return this.auth.status() === 'authenticated' && deck.authorId !== this.currentUserId;
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
