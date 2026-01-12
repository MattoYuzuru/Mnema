import { Component, OnInit } from '@angular/core';
import { Router } from '@angular/router';
import { NgIf, NgFor } from '@angular/common';
import { from, of } from 'rxjs';
import { catchError, mergeMap } from 'rxjs/operators';
import { DeckApiService } from '../../core/services/deck-api.service';
import { PublicDeckApiService } from '../../core/services/public-deck-api.service';
import { MediaApiService } from '../../core/services/media-api.service';
import { UserApiService } from '../../user-api.service';
import { UserDeckDTO } from '../../core/models/user-deck.models';
import { DeckCardComponent } from '../../shared/components/deck-card.component';
import { MemoryTipLoaderComponent } from '../../shared/components/memory-tip-loader.component';
import { EmptyStateComponent } from '../../shared/components/empty-state.component';
import { ButtonComponent } from '../../shared/components/button.component';
import { TranslatePipe } from '../../shared/pipes/translate.pipe';

@Component({
    selector: 'app-decks-list',
    standalone: true,
    imports: [NgIf, NgFor, DeckCardComponent, MemoryTipLoaderComponent, EmptyStateComponent, ButtonComponent, TranslatePipe],
    template: `
    <app-memory-tip-loader *ngIf="loading"></app-memory-tip-loader>

    <div *ngIf="!loading" class="decks-list-page">
      <header class="page-header">
        <h1>{{ 'decks.title' | translate }}</h1>
        <app-button variant="primary" (click)="createDeck()">
          {{ 'decks.createDeck' | translate }}
        </app-button>
      </header>

      <div *ngIf="decks.length > 0" class="decks-list">
        <app-deck-card
          *ngFor="let deck of decks"
          [userDeck]="deck"
          [iconUrl]="getDeckIconUrl(deck)"
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

      <div *ngIf="decks.length > 0 && hasMore" class="load-more-container">
        <app-button
          variant="secondary"
          [disabled]="loadingMore"
          (click)="loadMore()"
        >
          {{ (loadingMore ? 'decks.loading' : 'decks.loadMore') | translate }}
        </app-button>
      </div>

      <app-empty-state
        *ngIf="decks.length === 0"
        icon="📚"
        [title]="'decks.noDecks' | translate"
        [description]="'decks.noDecksDescription' | translate"
        [actionText]="'home.browsePublicDecks' | translate"
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
        grid-template-columns: repeat(1, minmax(0, 1fr));
        gap: var(--spacing-md);
      }

      .load-more-container {
        display: flex;
        justify-content: center;
        padding: var(--spacing-xl) 0;
      }

      @media (min-width: 768px) {
        .decks-list {
          grid-template-columns: repeat(2, minmax(0, 1fr));
        }
      }

      @media (min-width: 1100px) {
        .decks-list {
          grid-template-columns: repeat(3, minmax(0, 1fr));
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
    loadingMore = false;
    decks: UserDeckDTO[] = [];
    page = 1;
    pageSize = 12;
    hasMore = false;
    private static deckSizesCache: Map<string, { size: number; timestamp: number }> = new Map();
    private static readonly CACHE_TTL_MS = 5 * 60 * 1000;
    private deckSizes: Map<string, number> = new Map();
    private static authorIdsCache: Map<string, string> = new Map();
    private authorIds: Map<string, string> = new Map();
    private static deckIconMediaCache: Map<string, string> = new Map();
    private static deckIconUrlCache: Map<string, string> = new Map();
    private deckIconMediaIds: Map<string, string> = new Map();
    private deckIcons: Map<string, string> = new Map();
    private currentUserId: string | null = null;

    constructor(
        private deckApi: DeckApiService,
        private publicDeckApi: PublicDeckApiService,
        private mediaApi: MediaApiService,
        private userApi: UserApiService,
        private router: Router
    ) {}

    ngOnInit(): void {
        this.userApi.getMe().subscribe({
            next: profile => {
                this.currentUserId = profile.id;
                this.loadDecks();
            },
            error: () => {
                this.loadDecks();
            }
        });
    }

    private loadDecks(): void {
        this.deckApi.getMyDecks(this.page, this.pageSize).subscribe({
            next: page => {
                this.decks = page.content;
                this.hasMore = !page.last;
                this.loading = false;

                if (this.decks.length > 0) {
                    this.loadDeckSizes();
                    this.loadPublicDeckMetadata();
                }
            },
            error: () => {
                this.loading = false;
            }
        });
    }

    loadMore(): void {
        if (this.loadingMore || !this.hasMore) {
            return;
        }

        this.loadingMore = true;
        this.page++;

        this.deckApi.getMyDecks(this.page, this.pageSize).subscribe({
            next: page => {
                const newDecks = page.content;
                this.decks = [...this.decks, ...newDecks];
                this.hasMore = !page.last;
                this.loadingMore = false;

                if (newDecks.length > 0) {
                    this.loadDeckSizesFor(newDecks);
                    this.loadPublicDeckMetadataFor(newDecks);
                }
            },
            error: () => {
                this.loadingMore = false;
                this.page--;
            }
        });
    }

    private loadPublicDeckMetadata(): void {
        this.loadPublicDeckMetadataFor(this.decks);
    }

    private loadPublicDeckMetadataFor(decks: UserDeckDTO[]): void {
        const decksWithPublicId = decks.filter(d => d.publicDeckId);
        if (decksWithPublicId.length === 0) {
            return;
        }

        const publicDeckIds = decksWithPublicId.map(deck => deck.publicDeckId);
        const missingDetails = decksWithPublicId.filter(deck => {
            const publicDeckId = deck.publicDeckId;
            const cachedAuthor = DecksListComponent.authorIdsCache.get(publicDeckId);
            if (cachedAuthor) {
                this.authorIds.set(publicDeckId, cachedAuthor);
            }

            const cachedIconId = DecksListComponent.deckIconMediaCache.get(publicDeckId);
            if (cachedIconId) {
                this.deckIconMediaIds.set(publicDeckId, cachedIconId);
            }

            const cachedIconUrl = DecksListComponent.deckIconUrlCache.get(publicDeckId);
            if (cachedIconUrl) {
                this.deckIcons.set(publicDeckId, cachedIconUrl);
            }

            return !(cachedAuthor && (cachedIconId || cachedIconUrl));
        });

        if (missingDetails.length === 0) {
            this.resolveDeckIcons(publicDeckIds);
            return;
        }

        from(missingDetails)
            .pipe(
                mergeMap(deck => this.publicDeckApi.getPublicDeck(deck.publicDeckId).pipe(
                    catchError(() => of(null))
                ), 3)
            )
            .subscribe({
                next: response => {
                    if (response) {
                        this.authorIds.set(response.deckId, response.authorId);
                        DecksListComponent.authorIdsCache.set(response.deckId, response.authorId);

                        if (response.iconMediaId) {
                            this.deckIconMediaIds.set(response.deckId, response.iconMediaId);
                            DecksListComponent.deckIconMediaCache.set(response.deckId, response.iconMediaId);
                        }
                        if (response.iconUrl) {
                            this.deckIcons.set(response.deckId, response.iconUrl);
                            DecksListComponent.deckIconUrlCache.set(response.deckId, response.iconUrl);
                        }
                    }
                },
                complete: () => {
                    this.resolveDeckIcons(publicDeckIds);
                }
            });
    }

    private loadDeckSizes(): void {
        this.loadDeckSizesFor(this.decks);
    }

    private loadDeckSizesFor(decks: UserDeckDTO[]): void {
        const now = Date.now();

        from(decks)
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

    private resolveDeckIcons(publicDeckIds: string[]): void {
        const mediaIds: string[] = [];
        const deckIdsByMedia = new Map<string, string[]>();

        for (const publicDeckId of publicDeckIds) {
            const cachedUrl = DecksListComponent.deckIconUrlCache.get(publicDeckId);
            if (cachedUrl) {
                this.deckIcons.set(publicDeckId, cachedUrl);
                continue;
            }

            const mediaId = this.deckIconMediaIds.get(publicDeckId)
                || DecksListComponent.deckIconMediaCache.get(publicDeckId);

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
                        DecksListComponent.deckIconUrlCache.set(deckId, media.url);
                    });
                });
            },
            error: err => {
                console.error('Failed to resolve deck icons', err);
            }
        });
    }

    getDeckIconUrl(deck: UserDeckDTO): string | null {
        if (!deck.publicDeckId) {
            return null;
        }

        return this.deckIcons.get(deck.publicDeckId) || null;
    }

    getDeckStats(deck: UserDeckDTO): { cardCount?: number; dueToday?: number } {
        const size = this.deckSizes.get(deck.userDeckId);
        return {
            cardCount: size,
            dueToday: undefined
        };
    }

    needsUpdate(deck: UserDeckDTO): boolean {
        if (!deck.publicDeckId || deck.subscribedVersion >= deck.currentVersion) {
            return false;
        }

        if (this.currentUserId && this.authorIds.has(deck.publicDeckId)) {
            const authorId = this.authorIds.get(deck.publicDeckId);
            if (authorId === this.currentUserId) {
                return false;
            }
        }

        return true;
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
