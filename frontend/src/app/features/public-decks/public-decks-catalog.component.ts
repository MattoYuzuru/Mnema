import { Component, OnInit, ViewChild, ElementRef, AfterViewInit, OnDestroy } from '@angular/core';
import { Router } from '@angular/router';
import { NgIf, NgFor } from '@angular/common';
import { catchError } from 'rxjs/operators';
import { forkJoin, of, firstValueFrom } from 'rxjs';
import { AuthService } from '../../auth.service';
import { UserApiService } from '../../user-api.service';
import { PublicDeckApiService } from '../../core/services/public-deck-api.service';
import { MediaApiService } from '../../core/services/media-api.service';
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
          [iconUrl]="deckIcons.get(deck.deckId) || null"
          [showFork]="canForkDeck(deck)"
          [showBrowse]="true"
          (open)="openDeck(deck.deckId)"
          (fork)="forkDeck(deck.deckId)"
          (browse)="browseDeck(deck.deckId)"
        ></app-deck-card>
        <div #sentinel class="sentinel"></div>
        <div *ngIf="loadingMore" class="loading-more">Loading more...</div>
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
        display: grid;
        grid-template-columns: repeat(1, minmax(0, 1fr));
        gap: var(--spacing-md);
      }

      .sentinel {
        grid-column: 1 / -1;
        height: 1px;
        visibility: hidden;
      }

      .loading-more {
        grid-column: 1 / -1;
        text-align: center;
        padding: var(--spacing-lg);
        color: var(--color-text-secondary);
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
        .public-decks-catalog {
          padding: 0 var(--spacing-md);
        }

        .page-header h1 {
          font-size: 1.5rem;
        }
      }

      @media (max-width: 480px) {
        .public-decks-catalog {
          padding: 0 var(--spacing-sm);
        }

        .page-header h1 {
          font-size: 1.25rem;
        }
      }
    `]
})
export class PublicDecksCatalogComponent implements OnInit, AfterViewInit, OnDestroy {
    @ViewChild('sentinel') set sentinelRef(ref: ElementRef<HTMLDivElement> | undefined) {
        if (ref) {
            this.sentinel = ref;
            this.observeSentinel();
        }
    }

    loading = true;
    loadingMore = false;
    decks: PublicDeckDTO[] = [];
    deckIcons: Map<string, string> = new Map();
    currentUserId: string | null = null;
    page = 1;
    pageSize = 15;
    totalPages = 1;
    last = false;

    private observer?: IntersectionObserver;
    private sentinel?: ElementRef<HTMLDivElement>;

    constructor(
        public auth: AuthService,
        private userApi: UserApiService,
        private publicDeckApi: PublicDeckApiService,
        private mediaApi: MediaApiService,
        private router: Router
    ) {}

    ngOnInit(): void {
        this.loadDecks();
    }

    ngAfterViewInit(): void {
        this.observeSentinel();
    }

    ngOnDestroy(): void {
        if (this.observer) {
            this.observer.disconnect();
        }
    }

    private observeSentinel(): void {
        if (!this.sentinel?.nativeElement) {
            return;
        }

        if (!this.observer) {
            this.observer = new IntersectionObserver(
                entries => {
                    const entry = entries[0];
                    if (this.loading || this.loadingMore || this.last || this.decks.length === 0) {
                        return;
                    }
                    if (entry.isIntersecting) {
                        this.loadMore();
                    }
                },
                { threshold: 0.1, rootMargin: '200px 0px' }
            );
        }

        this.observer.disconnect();
        this.observer.observe(this.sentinel.nativeElement);
    }

    private loadDecks(): void {
        const decks$ = this.publicDeckApi.getPublicDecks(this.page, this.pageSize).pipe(
            catchError(() => of({ content: [] as PublicDeckDTO[], last: true, totalPages: 0 }))
        );

        const user$ = this.auth.status() === 'authenticated'
            ? this.userApi.getMe().pipe(catchError(() => of(null)))
            : of(null);

        forkJoin({ decks: decks$, user: user$ }).subscribe({
            next: async result => {
                this.decks = result.decks.content;
                this.last = result.decks.last;
                this.totalPages = result.decks.totalPages || 1;
                this.currentUserId = result.user?.id || null;
                await this.resolveDeckIcons(this.decks);
                this.loading = false;
            },
            error: () => {
                this.loading = false;
            }
        });
    }

    private loadMore(): void {
        if (this.last || this.page >= this.totalPages) {
            return;
        }

        this.loadingMore = true;
        this.page++;

        this.publicDeckApi.getPublicDecks(this.page, this.pageSize).pipe(
            catchError(() => of({ content: [] as PublicDeckDTO[], last: true, totalPages: 0 }))
        ).subscribe({
            next: async result => {
                this.decks = [...this.decks, ...result.content];
                this.last = result.last;
                this.totalPages = result.totalPages || this.totalPages;
                await this.resolveDeckIcons(result.content);
                this.loadingMore = false;
            },
            error: () => {
                this.loadingMore = false;
            }
        });
    }

    private async resolveDeckIcons(decks: PublicDeckDTO[]): Promise<void> {
        const decksWithIcons = decks.filter(d => d.iconMediaId);
        if (decksWithIcons.length === 0) return;

        try {
            const mediaIds = decksWithIcons.map(d => d.iconMediaId!);
            const resolved = await firstValueFrom(this.mediaApi.resolve(mediaIds));
            resolved.forEach((media, index) => {
                if (media?.url) {
                    const deck = decksWithIcons[index];
                    this.deckIcons.set(deck.deckId, media.url);
                }
            });
        } catch (err) {
            console.error('Failed to resolve deck icons', err);
        }
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
