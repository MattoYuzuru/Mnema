import { Component, OnInit, OnDestroy } from '@angular/core';
import { NgIf, NgFor } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import { forkJoin, of, Subscription } from 'rxjs';
import { catchError, filter } from 'rxjs/operators';
import { AuthService, AuthStatus } from './auth.service';
import { UserApiService } from './user-api.service';
import { PublicDeckApiService } from './core/services/public-deck-api.service';
import { DeckApiService } from './core/services/deck-api.service';
import { PublicDeckDTO } from './core/models/public-deck.models';
import { UserDeckDTO } from './core/models/user-deck.models';
import { DeckCardComponent } from './shared/components/deck-card.component';
import { MemoryTipLoaderComponent } from './shared/components/memory-tip-loader.component';
import { ButtonComponent } from './shared/components/button.component';
import { EmptyStateComponent } from './shared/components/empty-state.component';
import { TranslatePipe } from './shared/pipes/translate.pipe';

@Component({
    standalone: true,
    selector: 'app-home-page',
    imports: [NgIf, NgFor, RouterLink, DeckCardComponent, MemoryTipLoaderComponent, ButtonComponent, EmptyStateComponent, TranslatePipe],
    template: `
    <app-memory-tip-loader *ngIf="loading"></app-memory-tip-loader>

    <div *ngIf="!loading" class="home-page">
      <section *ngIf="auth.status() === 'authenticated'" class="study-today">
        <h2>{{ 'home.studyToday' | translate }}</h2>
        <div class="study-summary">
          <div class="study-info">
            <p class="study-message">{{ todayStats.due }} {{ 'home.cardsDue' | translate }} · {{ todayStats.new }} {{ 'home.new' | translate }}</p>
            <app-button variant="primary" size="lg" routerLink="/decks">
              {{ 'home.continueLearn' | translate }}
            </app-button>
          </div>
        </div>

        <div *ngIf="userDecks.length > 0" class="recent-decks">
          <h3>{{ 'home.yourDecks' | translate }}</h3>
          <div class="deck-grid">
            <app-deck-card
              *ngFor="let deck of userDecks"
              [userDeck]="deck"
              [showLearn]="true"
              [showBrowse]="true"
              [stats]="getDeckStats(deck)"
              (open)="openUserDeck(deck.userDeckId)"
              (learn)="learnDeck(deck.userDeckId)"
              (browse)="browseDeck(deck.userDeckId)"
            ></app-deck-card>
          </div>
        </div>

        <app-empty-state
          *ngIf="userDecks.length === 0"
          icon="📚"
          title="No decks yet"
          description="Create your first deck or fork a public deck to get started"
          actionText="Browse Public Decks"
          (action)="goToPublicDecks()"
        ></app-empty-state>
      </section>

      <section class="public-decks">
        <div class="section-header">
          <h2>{{ 'home.topPublicDecks' | translate }}</h2>
          <a routerLink="/public-decks" class="view-all">{{ 'home.viewAll' | translate }} →</a>
        </div>

        <div *ngIf="publicDecks.length > 0" class="deck-list">
          <app-deck-card
            *ngFor="let deck of publicDecks"
            [publicDeck]="deck"
            [showFork]="canForkDeck(deck)"
            [showBrowse]="true"
            (open)="openPublicDeck(deck.deckId)"
            (fork)="forkDeck(deck.deckId)"
            (browse)="browsePublicDeck(deck.deckId)"
          ></app-deck-card>
        </div>

        <app-empty-state
          *ngIf="publicDecks.length === 0"
          icon="🌐"
          title="No public decks available"
          description="Public decks will appear here once they are published"
        ></app-empty-state>
      </section>
    </div>
  `,
    styles: [
        `
      .home-page {
        max-width: 72rem;
        margin: 0 auto;
      }

      .study-today {
        margin-bottom: var(--spacing-2xl);
        padding: var(--spacing-xl);
        background: var(--color-card-background);
        border: 1px solid var(--border-color);
        border-radius: var(--border-radius-lg);
        box-shadow: var(--shadow-sm);
      }

      .study-today h2 {
        font-size: 1.5rem;
        margin-bottom: var(--spacing-lg);
      }

      .study-today h3 {
        font-size: 1.25rem;
        margin: var(--spacing-xl) 0 var(--spacing-md) 0;
      }

      .study-summary {
        margin-bottom: var(--spacing-lg);
      }

      .study-info {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: var(--spacing-md);
      }

      .study-message {
        font-size: 1.1rem;
        color: var(--color-text-muted);
        margin: 0;
      }

      .recent-decks {
        margin-top: var(--spacing-xl);
        padding-top: var(--spacing-xl);
        border-top: 1px solid var(--border-color);
      }

      .deck-grid {
        display: grid;
        grid-template-columns: repeat(auto-fill, minmax(20rem, 1fr));
        gap: var(--spacing-md);
      }

      .public-decks {
        margin-bottom: var(--spacing-2xl);
      }

      .section-header {
        display: flex;
        align-items: center;
        justify-content: space-between;
        margin-bottom: var(--spacing-lg);
      }

      .section-header h2 {
        font-size: 1.5rem;
        margin: 0;
      }

      .view-all {
        font-size: 0.95rem;
        color: var(--color-primary-accent);
        text-decoration: none;
        font-weight: 500;
        transition: opacity 0.2s ease;
      }

      .view-all:hover {
        opacity: 0.8;
      }

      .deck-list {
        display: flex;
        flex-direction: column;
        gap: var(--spacing-md);
      }
    `
    ]
})
export class HomePageComponent implements OnInit, OnDestroy {
    loading = true;
    publicDecks: PublicDeckDTO[] = [];
    userDecks: UserDeckDTO[] = [];
    todayStats = { due: 0, new: 0 };
    currentUserId: string | null = null;
    private authSubscription?: Subscription;
    private hasLoadedUserDecks = false;

    constructor(
        public auth: AuthService,
        private userApi: UserApiService,
        private publicDeckApi: PublicDeckApiService,
        private deckApi: DeckApiService,
        private router: Router
    ) {}

    ngOnInit(): void {
        this.loadData();
        this.authSubscription = this.auth.status$
            .pipe(filter((status: AuthStatus) => status === 'authenticated'))
            .subscribe(() => {
                if (!this.hasLoadedUserDecks) {
                    this.loadUserData();
                }
            });
    }

    ngOnDestroy(): void {
        this.authSubscription?.unsubscribe();
    }

    private loadData(): void {
        const publicDecks$ = this.publicDeckApi.getPublicDecks(1, 5).pipe(
            catchError(() => of({ content: [] as PublicDeckDTO[] }))
        );

        const isAuthenticated = this.auth.status() === 'authenticated';
        const userDecks$ = isAuthenticated
            ? this.deckApi.getMyDecks(1, 3).pipe(
                catchError(() => of({ content: [] as UserDeckDTO[] }))
            )
            : of({ content: [] as UserDeckDTO[] });

        const user$ = isAuthenticated
            ? this.userApi.getMe().pipe(
                catchError(() => of(null))
            )
            : of(null);

        forkJoin({
            publicDecks: publicDecks$,
            userDecks: userDecks$,
            user: user$
        }).subscribe({
            next: result => {
                this.publicDecks = result.publicDecks.content;
                this.userDecks = result.userDecks.content;
                this.currentUserId = result.user?.id || null;
                this.hasLoadedUserDecks = isAuthenticated;
                this.loading = false;
            },
            error: () => {
                this.loading = false;
            }
        });
    }

    private loadUserData(): void {
        const userDecks$ = this.deckApi.getMyDecks(1, 3).pipe(
            catchError(() => of({ content: [] as UserDeckDTO[] }))
        );

        const user$ = this.userApi.getMe().pipe(
            catchError(() => of(null))
        );

        forkJoin({
            userDecks: userDecks$,
            user: user$
        }).subscribe({
            next: result => {
                this.userDecks = result.userDecks.content;
                this.currentUserId = result.user?.id || null;
                this.hasLoadedUserDecks = true;
            }
        });
    }

    canForkDeck(deck: PublicDeckDTO): boolean {
        return this.auth.status() === 'authenticated' && deck.authorId !== this.currentUserId;
    }

    getDeckStats(deck: UserDeckDTO): { cardCount?: number; dueToday?: number } {
        return {
            cardCount: 0,
            dueToday: undefined
        };
    }

    openUserDeck(userDeckId: string): void {
        void this.router.navigate(['/decks', userDeckId]);
    }

    openPublicDeck(deckId: string): void {
        void this.router.navigate(['/public-decks', deckId, 'browse']);
    }

    learnDeck(userDeckId: string): void {
        void this.router.navigate(['/decks', userDeckId, 'review']);
    }

    browseDeck(userDeckId: string): void {
        void this.router.navigate(['/decks', userDeckId, 'browse']);
    }

    browsePublicDeck(deckId: string): void {
        void this.router.navigate(['/public-decks', deckId, 'browse']);
    }

    forkDeck(deckId: string): void {
        console.log('Forking deck:', deckId);
        this.publicDeckApi.fork(deckId).subscribe({
            next: userDeck => {
                console.log('Deck forked successfully:', userDeck);
                void this.router.navigate(['/decks', userDeck.userDeckId]);
            },
            error: err => {
                console.error('Failed to fork deck:', err);
            }
        });
    }

    goToPublicDecks(): void {
        void this.router.navigate(['/public-decks']);
    }
}
