import { Component, OnInit, OnDestroy } from '@angular/core';
import { NgIf, NgFor } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import { forkJoin, of, Subscription, from } from 'rxjs';
import { catchError, filter, mergeMap } from 'rxjs/operators';
import { AuthService, AuthStatus } from './auth.service';
import { UserApiService } from './user-api.service';
import { PublicDeckApiService } from './core/services/public-deck-api.service';
import { DeckApiService } from './core/services/deck-api.service';
import { ReviewApiService } from './core/services/review-api.service';
import { MediaApiService } from './core/services/media-api.service';
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
              [iconUrl]="getUserDeckIconUrl(deck)"
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
            [iconUrl]="getPublicDeckIconUrl(deck)"
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
        display: grid;
        grid-template-columns: 1fr;
        gap: var(--spacing-md);
      }

      @media (min-width: 768px) {
        .deck-list {
          grid-template-columns: repeat(auto-fill, minmax(350px, 1fr));
        }
      }

      @media (max-width: 768px) {
        .home-page {
          padding: 0 var(--spacing-md);
        }

        .hero h1 {
          font-size: 2rem;
        }

        .hero p {
          font-size: 1rem;
        }

        .section-header {
          flex-direction: column;
          align-items: flex-start;
          gap: var(--spacing-sm);
        }

        .section-header h2 {
          font-size: 1.25rem;
        }
      }

      @media (max-width: 480px) {
        .home-page {
          padding: 0 var(--spacing-sm);
        }

        .hero {
          padding: var(--spacing-lg) 0;
        }

        .hero h1 {
          font-size: 1.5rem;
        }

        .hero p {
          font-size: 0.9rem;
        }
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
    private static reviewStatsCache: { data: { due: number; new: number }; timestamp: number } | null = null;
    private static deckSizesCache: Map<string, { size: number; timestamp: number }> = new Map();
    private static readonly CACHE_TTL_MS = 5 * 60 * 1000;
    private deckSizes: Map<string, number> = new Map();
    private static deckIconMediaCache: Map<string, string> = new Map();
    private static deckIconUrlCache: Map<string, string> = new Map();
    private deckIcons: Map<string, string> = new Map();

    constructor(
        public auth: AuthService,
        private userApi: UserApiService,
        private publicDeckApi: PublicDeckApiService,
        private deckApi: DeckApiService,
        private reviewApi: ReviewApiService,
        private mediaApi: MediaApiService,
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

                if (isAuthenticated && this.userDecks.length > 0) {
                    this.loadReviewStats();
                    this.loadDeckSizes();
                    this.loadUserDeckIcons();
                }

                if (this.publicDecks.length > 0) {
                    this.resolvePublicDeckIcons(this.publicDecks);
                }
            },
            error: () => {
                this.loading = false;
            }
        });
    }

    private loadReviewStats(): void {
        const now = Date.now();
        const cached = HomePageComponent.reviewStatsCache;

        if (cached && (now - cached.timestamp) < HomePageComponent.CACHE_TTL_MS) {
            this.todayStats = cached.data;
            return;
        }

        this.todayStats = { due: 0, new: 0 };

        from(this.userDecks)
            .pipe(
                mergeMap(deck =>
                    this.reviewApi.getNextCard(deck.userDeckId).pipe(
                        catchError(() => of({ queue: { dueCount: 0, newCount: 0 } }))
                    ),
                    4
                )
            )
            .subscribe({
                next: response => {
                    this.todayStats.due += response.queue.dueCount || 0;
                    this.todayStats.new += response.queue.newCount || 0;
                },
                complete: () => {
                    HomePageComponent.reviewStatsCache = {
                        data: { ...this.todayStats },
                        timestamp: now
                    };
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

                if (this.userDecks.length > 0) {
                    this.loadReviewStats();
                    this.loadDeckSizes();
                    this.loadUserDeckIcons();
                }
            }
        });
    }

    private loadDeckSizes(): void {
        const now = Date.now();

        from(this.userDecks)
            .pipe(
                mergeMap(deck => {
                    const cached = HomePageComponent.deckSizesCache.get(deck.userDeckId);
                    if (cached && (now - cached.timestamp) < HomePageComponent.CACHE_TTL_MS) {
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
                        HomePageComponent.deckSizesCache.set(response.deckId, {
                            size: response.cardsQty,
                            timestamp: now
                        });
                    }
                }
            });
    }

    private resolvePublicDeckIcons(decks: PublicDeckDTO[]): void {
        const publicDeckIds: string[] = [];

        decks.forEach(deck => {
            publicDeckIds.push(deck.deckId);
            if (deck.iconMediaId) {
                HomePageComponent.deckIconMediaCache.set(deck.deckId, deck.iconMediaId);
            }
        });

        this.resolveDeckIcons(publicDeckIds);
    }

    private loadUserDeckIcons(): void {
        const publicDeckIds = this.userDecks
            .map(deck => deck.publicDeckId)
            .filter(Boolean);

        if (publicDeckIds.length === 0) {
            return;
        }

        const missingIds = publicDeckIds.filter(id =>
            !HomePageComponent.deckIconMediaCache.has(id)
            && !HomePageComponent.deckIconUrlCache.has(id)
        );

        if (missingIds.length === 0) {
            this.resolveDeckIcons(publicDeckIds);
            return;
        }

        from(missingIds)
            .pipe(
                mergeMap(id => this.publicDeckApi.getPublicDeck(id).pipe(
                    catchError(() => of(null))
                ), 3)
            )
            .subscribe({
                next: response => {
                    if (response?.iconMediaId) {
                        HomePageComponent.deckIconMediaCache.set(response.deckId, response.iconMediaId);
                    }
                },
                complete: () => {
                    this.resolveDeckIcons(publicDeckIds);
                }
            });
    }

    private resolveDeckIcons(publicDeckIds: string[]): void {
        const mediaIds: string[] = [];
        const deckIdsByMedia = new Map<string, string[]>();

        for (const publicDeckId of publicDeckIds) {
            const cachedUrl = HomePageComponent.deckIconUrlCache.get(publicDeckId);
            if (cachedUrl) {
                this.deckIcons.set(publicDeckId, cachedUrl);
                continue;
            }

            const mediaId = HomePageComponent.deckIconMediaCache.get(publicDeckId);
            if (!mediaId) {
                continue;
            }

            if (!deckIdsByMedia.has(mediaId)) {
                deckIdsByMedia.set(mediaId, []);
                mediaIds.push(mediaId);
            }

            deckIdsByMedia.get(mediaId)!.push(publicDeckId);
        }

        if (mediaIds.length === 0) {
            return;
        }

        this.mediaApi.resolve(mediaIds).subscribe({
            next: resolved => {
                resolved.forEach(media => {
                    const deckIds = deckIdsByMedia.get(media.mediaId);
                    if (!deckIds || !media.url) {
                        return;
                    }

                    deckIds.forEach(deckId => {
                        this.deckIcons.set(deckId, media.url);
                        HomePageComponent.deckIconUrlCache.set(deckId, media.url);
                    });
                });
            },
            error: err => {
                console.error('Failed to resolve deck icons', err);
            }
        });
    }

    canForkDeck(deck: PublicDeckDTO): boolean {
        return this.auth.status() === 'authenticated' && deck.authorId !== this.currentUserId;
    }

    getDeckStats(deck: UserDeckDTO): { cardCount?: number; dueToday?: number } {
        const size = this.deckSizes.get(deck.userDeckId);
        return {
            cardCount: size,
            dueToday: undefined
        };
    }

    getUserDeckIconUrl(deck: UserDeckDTO): string | null {
        if (!deck.publicDeckId) {
            return null;
        }

        return this.deckIcons.get(deck.publicDeckId) || null;
    }

    getPublicDeckIconUrl(deck: PublicDeckDTO): string | null {
        return this.deckIcons.get(deck.deckId) || null;
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
