import { Component, OnInit, HostListener, OnDestroy } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { NgIf, NgFor, DatePipe } from '@angular/common';
import { forkJoin, of, Subscription } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { AuthService } from '../../auth.service';
import { UserApiService } from '../../user-api.service';
import { DeckApiService } from '../../core/services/deck-api.service';
import { PublicDeckApiService } from '../../core/services/public-deck-api.service';
import { ReviewApiService } from '../../core/services/review-api.service';
import { PublicDeckDTO, PublicCardDTO } from '../../core/models/public-deck.models';
import { CardContentValue } from '../../core/models/user-card.models';
import { CardTemplateDTO, FieldTemplateDTO } from '../../core/models/template.models';
import { MemoryTipLoaderComponent } from '../../shared/components/memory-tip-loader.component';
import { EmptyStateComponent } from '../../shared/components/empty-state.component';
import { FlashcardViewComponent } from '../../shared/components/flashcard-view.component';
import { ButtonComponent } from '../../shared/components/button.component';
import { TranslatePipe } from '../../shared/pipes/translate.pipe';
import { TagChipComponent } from '../../shared/components/tag-chip.component';
import { markdownToHtml } from '../../shared/utils/markdown.util';

@Component({
    selector: 'app-public-card-browser',
    standalone: true,
    imports: [NgIf, NgFor, DatePipe, MemoryTipLoaderComponent, EmptyStateComponent, FlashcardViewComponent, ButtonComponent, TranslatePipe, TagChipComponent],
    template: `
    <app-memory-tip-loader *ngIf="loading"></app-memory-tip-loader>

    <div *ngIf="!loading" class="public-card-browser">
      <header class="page-header">
        <div class="header-left">
          <h1>{{ deck?.name || ('publicCardBrowser.publicDeck' | translate) }}</h1>
          <div class="deck-description" [innerHTML]="formatDescription(deck?.description)"></div>
          <div *ngIf="deck?.tags?.length" class="deck-tags">
            <app-tag-chip *ngFor="let tag of deck!.tags" [text]="tag"></app-tag-chip>
          </div>
          <div *ngIf="deck" class="deck-meta">
            <div class="meta-item" *ngIf="deck.language">
              <span class="meta-label">{{ 'deckProfile.language' | translate }}:</span>
              <span class="meta-value">{{ deck.language }}</span>
            </div>
            <div class="meta-item" *ngIf="deck.publishedAt">
              <span class="meta-label">{{ 'deckProfile.publishedAt' | translate }}:</span>
              <span class="meta-value">{{ deck.publishedAt | date:'mediumDate' }}</span>
            </div>
            <div class="meta-item" *ngIf="deck.updatedAt">
              <span class="meta-label">{{ 'deckProfile.updatedAt' | translate }}:</span>
              <span class="meta-value">{{ deck.updatedAt | date:'mediumDate' }}</span>
            </div>
            <div class="meta-item" *ngIf="deck.forkedFromDeck">
              <span class="meta-label">{{ 'deckProfile.forkedFrom' | translate }}:</span>
              <span class="meta-value">{{ deck.forkedFromDeck }}</span>
            </div>
          </div>
          <p class="card-count">{{ cardCount }} {{ 'publicCardBrowser.cards' | translate }}</p>
        </div>
        <div class="header-right">
          <app-button *ngIf="canFork" variant="primary" size="md" (click)="forkDeck()">{{ 'button.fork' | translate }}</app-button>
        </div>
      </header>

      <div *ngIf="cards.length > 0" class="browser-layout">
        <aside class="cards-panel glass">
          <div class="panel-header">
            <div>
              <h2>{{ 'cardBrowser.list' | translate }}</h2>
              <p class="panel-meta">{{ cardCount }} {{ 'publicCardBrowser.cards' | translate }}</p>
            </div>
          </div>

          <div class="panel-search">
            <input
              type="search"
              class="card-search"
              [placeholder]="'publicCardBrowser.searchPlaceholder' | translate"
              [attr.aria-label]="'publicCardBrowser.searchPlaceholder' | translate"
              [value]="searchQuery"
              (input)="onSearchInput($event)"
            />
            <span *ngIf="searchActive" class="search-meta">
              {{ visibleCards.length }} / {{ totalCards || cards.length }} {{ 'publicCardBrowser.cards' | translate }}
            </span>
          </div>

          <div class="cards-list" (scroll)="onListScroll($event)">
            <div *ngFor="let card of visibleCards; let index = index" class="cards-list-item" [class.active]="index === currentCardIndex">
              <button class="card-preview" type="button" (click)="openCardFromList(index)">
                <span class="card-index">{{ index + 1 }}</span>
                <div class="card-preview-body">
                  <span class="card-text">{{ getFrontPreview(card) }}</span>
                  <div *ngIf="card.tags?.length" class="card-tags-inline">
                    <app-tag-chip *ngFor="let tag of card.tags" [text]="tag"></app-tag-chip>
                  </div>
                </div>
              </button>
            </div>
          </div>
        </aside>

        <section class="preview-panel glass-strong">
          <div class="preview-header">
            <div>
              <h2>{{ 'cardBrowser.cardsView' | translate }}</h2>
              <p class="panel-meta">
                {{ searchNoResults ? 0 : (currentCardIndex + 1) }} / {{ searchNoResults ? 0 : cardCount }}
              </p>
            </div>
            <div class="preview-nav">
              <app-button
                variant="ghost"
                size="sm"
                (click)="previousCard()"
                [disabled]="searchNoResults || currentCardIndex === 0"
              >
                {{ 'cardBrowser.previous' | translate }}
              </app-button>
              <app-button
                variant="ghost"
                size="sm"
                (click)="nextCard()"
                [disabled]="searchNoResults || currentCardIndex >= visibleCards.length - 1"
              >
                {{ 'cardBrowser.next' | translate }}
              </app-button>
            </div>
          </div>

          <div *ngIf="searchNoResults" class="no-results-panel">
            <div class="no-results-card glass">
              <h3>{{ 'publicCardBrowser.noSearchResults' | translate }}</h3>
              <p>{{ 'publicCardBrowser.noSearchResultsDescription' | translate }}</p>
            </div>
          </div>

          <div *ngIf="!searchNoResults && currentCard" class="flashcard-container">
            <div class="flashcard glass" (click)="toggleReveal()">
              <div class="flashcard-content">
                <div class="card-side front">
                  <app-flashcard-view *ngIf="template && currentCard" [template]="template" [content]="currentCard.content" side="front"></app-flashcard-view>
                </div>
                <div *ngIf="revealed" class="divider"></div>
                <div class="card-side back" [class.preload]="!revealed">
                  <app-flashcard-view *ngIf="template && currentCard" [template]="template" [content]="currentCard.content" side="back"></app-flashcard-view>
                </div>
              </div>
            </div>
            <div class="flip-hint">
              <p>{{ 'cardBrowser.clickToFlip' | translate }}</p>
              <p>{{ 'cardBrowser.keyboardHint' | translate }}</p>
            </div>
          </div>

          <div *ngIf="!searchNoResults && currentCard?.tags?.length" class="card-tags-panel">
            <app-tag-chip *ngFor="let tag of currentCard!.tags" [text]="tag"></app-tag-chip>
          </div>
        </section>
      </div>

      <app-empty-state
        *ngIf="cards.length === 0"
        icon="📝"
        [title]="searchNoResults ? ('publicCardBrowser.noSearchResults' | translate) : ('publicCardBrowser.noCards' | translate)"
        [description]="searchNoResults ? ('publicCardBrowser.noSearchResultsDescription' | translate) : ('publicCardBrowser.noCardsDescription' | translate)"
      ></app-empty-state>
    </div>
  `,
    styles: [`
      .public-card-browser {
        max-width: 82rem;
        margin: 0 auto;
      }

      .page-header {
        display: flex;
        align-items: flex-start;
        justify-content: space-between;
        gap: var(--spacing-xl);
        margin-bottom: var(--spacing-xl);
      }

      .header-left {
        display: flex;
        flex-direction: column;
        gap: var(--spacing-sm);
      }

      .header-left h1 {
        font-size: 2rem;
        margin: 0;
      }

      .deck-description {
        font-size: 1rem;
        color: var(--color-text-secondary);
        margin: 0;
        line-height: 1.6;
      }

      .deck-description h1,
      .deck-description h2,
      .deck-description h3 {
        font-size: 1rem;
        margin: 0 0 var(--spacing-xs) 0;
        font-weight: 600;
      }

      .deck-description ul {
        margin: 0 0 var(--spacing-xs) 0;
        padding-left: 1.2rem;
      }

      .deck-description li {
        margin: 0;
      }

      .deck-description pre {
        margin: 0 0 var(--spacing-xs) 0;
        padding: var(--spacing-sm);
        background: var(--color-background);
        border-radius: var(--border-radius-md);
        white-space: pre-wrap;
      }

      .deck-description code {
        font-family: inherit;
        font-size: 0.9rem;
        background: var(--color-background);
        padding: 0 0.2rem;
        border-radius: var(--border-radius-sm);
      }

      .deck-tags {
        display: flex;
        flex-wrap: wrap;
        gap: var(--spacing-xs);
      }

      .deck-meta {
        display: flex;
        flex-wrap: wrap;
        gap: var(--spacing-xs) var(--spacing-lg);
      }

      .meta-item {
        display: flex;
        gap: var(--spacing-xs);
        font-size: 0.9rem;
        color: var(--color-text-muted);
      }

      .meta-label {
        font-weight: 600;
        color: var(--color-text-secondary);
      }

      .card-count {
        font-size: 0.95rem;
        color: var(--color-text-muted);
        margin: 0;
      }

      .header-right {
        display: flex;
        gap: var(--spacing-md);
        align-items: center;
      }

      .browser-layout {
        display: grid;
        grid-template-columns: minmax(240px, 300px) minmax(0, 1fr);
        gap: var(--spacing-xl);
        align-items: stretch;
      }

      .cards-panel {
        display: flex;
        flex-direction: column;
        gap: var(--spacing-md);
        padding: var(--spacing-lg);
        border-radius: var(--border-radius-lg);
        height: clamp(420px, 70vh, 760px);
        min-height: 420px;
      }

      .panel-header {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: var(--spacing-md);
      }

      .panel-header h2 {
        font-size: 1.1rem;
        margin: 0 0 var(--spacing-xs) 0;
      }

      .panel-meta {
        font-size: 0.85rem;
        color: var(--color-text-muted);
        margin: 0;
      }

      .panel-search {
        display: flex;
        flex-direction: column;
        gap: var(--spacing-xs);
      }

      .card-search {
        width: 100%;
        padding: 0.7rem 1rem;
        border: 1px solid var(--glass-border-strong);
        border-radius: var(--border-radius-full);
        font-size: 0.9rem;
        background: var(--color-surface-solid);
        color: var(--color-text-primary);
        box-shadow: inset 0 0 0 1px rgba(255, 255, 255, 0.5);
      }

      .card-search:focus {
        outline: none;
        border-color: var(--color-primary-accent);
        box-shadow: var(--focus-ring);
      }

      .search-meta {
        font-size: 0.85rem;
        color: var(--color-text-muted);
        white-space: nowrap;
        text-align: right;
      }

      .cards-list {
        display: flex;
        flex-direction: column;
        gap: var(--spacing-sm);
        overflow-y: auto;
        flex: 1;
        min-height: 0;
        padding-right: var(--spacing-xs);
        scrollbar-width: thin;
        scrollbar-color: var(--glass-border-strong) transparent;
      }

      .cards-list::-webkit-scrollbar {
        width: 8px;
      }

      .cards-list::-webkit-scrollbar-track {
        background: transparent;
      }

      .cards-list::-webkit-scrollbar-thumb {
        background: var(--glass-border-strong);
        border-radius: 999px;
        border: 2px solid transparent;
        background-clip: padding-box;
      }

      .cards-list::-webkit-scrollbar-thumb:hover {
        background: var(--border-color-hover);
      }

      .cards-list-item {
        display: flex;
        align-items: center;
        gap: var(--spacing-sm);
        padding: var(--spacing-sm) var(--spacing-md);
        border-radius: var(--border-radius-md);
        border: 1px solid var(--glass-border);
        background: var(--glass-surface-strong);
        transition: border-color 0.2s ease, box-shadow 0.2s ease, background 0.2s ease;
      }

      .cards-list-item.active {
        border-color: var(--color-primary-accent);
        box-shadow: var(--shadow-sm);
      }

      .card-preview {
        display: flex;
        align-items: center;
        gap: var(--spacing-sm);
        text-align: left;
        background: none;
        border: none;
        padding: 0;
        color: var(--color-text-primary);
        cursor: pointer;
        font: inherit;
        min-width: 0;
        flex: 1;
      }

      .card-index {
        font-size: 0.8rem;
        color: var(--color-text-muted);
        min-width: 1.5rem;
      }

      .card-text {
        font-size: 0.9rem;
        line-height: 1.3;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }

      .card-preview-body {
        display: flex;
        flex-direction: column;
        gap: var(--spacing-xs);
        min-width: 0;
        flex: 1;
      }

      .card-tags-inline {
        display: flex;
        flex-wrap: wrap;
        gap: var(--spacing-xs);
      }

      .flashcard-container {
        display: flex;
        flex-direction: column;
        align-items: center;
        gap: var(--spacing-md);
      }

      .no-results-panel {
        display: flex;
        flex: 1;
        align-items: center;
        justify-content: center;
        padding: var(--spacing-lg);
      }

      .no-results-card {
        width: 100%;
        max-width: 30rem;
        padding: var(--spacing-xl);
        border-radius: var(--border-radius-lg);
        text-align: center;
      }

      .no-results-card h3 {
        margin: 0 0 var(--spacing-sm) 0;
        font-size: 1.1rem;
      }

      .no-results-card p {
        margin: 0;
        color: var(--color-text-muted);
      }

      .flashcard {
        width: 100%;
        max-width: 42rem;
        min-height: 16rem;
        cursor: pointer;
        border-radius: var(--border-radius-lg);
        padding: var(--spacing-xl);
        display: flex;
        align-items: center;
        justify-content: center;
      }

      .flashcard-content {
        width: 100%;
        display: flex;
        flex-direction: column;
        gap: var(--spacing-lg);
        position: relative;
      }

      .card-side {
        width: 100%;
      }

      .card-side.preload {
        position: absolute;
        left: -9999px;
        top: 0;
        height: 0;
        overflow: hidden;
        pointer-events: none;
        visibility: hidden;
      }

      .divider {
        height: 1px;
        background: var(--border-color);
        margin: var(--spacing-md) 0;
      }

      .flip-hint {
        font-size: 0.9rem;
        color: var(--color-text-muted);
        text-align: center;
        margin: 0;
      }

      .card-tags-panel {
        display: flex;
        flex-wrap: wrap;
        gap: var(--spacing-xs);
        justify-content: center;
        padding: var(--spacing-xs) 0;
      }

      .preview-panel {
        display: flex;
        flex-direction: column;
        gap: var(--spacing-lg);
        padding: var(--spacing-lg);
        border-radius: var(--border-radius-lg);
        min-height: 420px;
      }

      .preview-header {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: var(--spacing-md);
      }

      .preview-header h2 {
        font-size: 1.1rem;
        margin: 0 0 var(--spacing-xs) 0;
      }

      .preview-nav {
        display: flex;
        align-items: center;
        gap: var(--spacing-xs);
      }

      @media (max-width: 768px) {
        .public-card-browser {
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

        .browser-layout {
          grid-template-columns: 1fr;
        }

        .cards-panel {
          height: auto;
        }

        .cards-list {
          max-height: 40vh;
        }

        .flashcard {
          min-height: 14rem;
          padding: var(--spacing-lg);
        }
      }

      @media (max-width: 480px) {
        .public-card-browser {
          padding: 0 var(--spacing-sm);
        }

        .page-header h1 {
          font-size: 1.25rem;
        }

        .flashcard {
          min-height: 12rem;
          padding: var(--spacing-md);
        }
      }
    `]
})
export class PublicCardBrowserComponent implements OnInit, OnDestroy {
    private static readonly PAGE_SIZE = 50;
    private static readonly PREFETCH_THRESHOLD = 0.8;

    loading = true;
    cards: PublicCardDTO[] = [];
    deck: PublicDeckDTO | null = null;
    template: CardTemplateDTO | null = null;
    deckId = '';
    currentCardIndex = 0;
    revealed = false;
    totalCards = 0;
    searchQuery = '';
    private currentPage = 1;
    private hasMoreCards = true;
    private loadingMore = false;
    private searchDebounce?: ReturnType<typeof setTimeout>;
    canFork = false;
    currentUserId: string | null = null;
    userPublicDeckIds: Set<string> = new Set();
    userPublicDeckIdsLoaded = false;
    private authSubscription?: Subscription;
    private userPublicDeckIdsLoading = false;

    formatDescription(description?: string): string {
        return markdownToHtml((description || '').trim());
    }

    constructor(
        private route: ActivatedRoute,
        private router: Router,
        private publicDeckApi: PublicDeckApiService,
        private userApi: UserApiService,
        private deckApi: DeckApiService,
        private reviewApi: ReviewApiService,
        public auth: AuthService
    ) {}

    ngOnInit(): void {
        this.deckId = this.route.snapshot.paramMap.get('deckId') || '';
        this.bindAuth();
        if (this.deckId) {
            this.loadDeckData();
        }
    }

    ngOnDestroy(): void {
        this.authSubscription?.unsubscribe();
    }

    @HostListener('window:keydown', ['$event'])
    handleKeyDown(event: KeyboardEvent): void {
        if (this.cards.length === 0 || !this.currentCard) return;
        if (this.searchNoResults) return;

        const target = event.target as HTMLElement;
        if (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.isContentEditable) {
            return;
        }

        if (event.key === ' ' || event.key === 'Space') {
            event.preventDefault();
            this.toggleReveal();
        } else if (event.key === 'ArrowLeft') {
            event.preventDefault();
            this.previousCard();
        } else if (event.key === 'ArrowRight') {
            event.preventDefault();
            this.nextCard();
        }
    }

    onListScroll(event: Event): void {
        const target = event.target as HTMLElement | null;
        if (!target) return;
        this.maybeLoadMoreOnScroll(target);
    }

    private loadDeckData(): void {
        this.loading = true;
        forkJoin({
            deck: this.publicDeckApi.getPublicDeck(this.deckId),
            cardsPage: this.publicDeckApi.getPublicDeckCards(this.deckId, undefined, 1, PublicCardBrowserComponent.PAGE_SIZE),
            size: this.publicDeckApi.getPublicDeckSize(this.deckId)
        }).subscribe({
            next: ({ deck, cardsPage, size }) => {
                this.deck = deck;
                this.cards = cardsPage.content;
                this.buildTemplateFromFields(cardsPage.fields);
                this.currentPage = cardsPage.number + 1;
                this.hasMoreCards = !cardsPage.last;
                this.totalCards = size.cardsQty;
                this.updateCanFork();
                this.loading = false;
            },
            error: err => {
                console.error('Failed to load public deck:', err);
                this.loading = false;
            }
        });
    }

    private bindAuth(): void {
        this.authSubscription = this.auth.status$.subscribe(status => {
            if (status === 'authenticated') {
                this.loadUserContext();
            } else {
                this.currentUserId = null;
                this.userPublicDeckIds.clear();
                this.userPublicDeckIdsLoaded = false;
                this.userPublicDeckIdsLoading = false;
                this.updateCanFork();
            }
        });

        if (this.auth.status() === 'authenticated') {
            this.loadUserContext();
        }
    }

    private loadUserContext(): void {
        this.userApi.getMe().pipe(
            catchError(() => of(null))
        ).subscribe({
            next: user => {
                this.currentUserId = user?.id || null;
                this.updateCanFork();
            }
        });

        this.loadUserPublicDeckIds();
    }

    private loadUserPublicDeckIds(): void {
        if (this.userPublicDeckIdsLoading) {
            return;
        }
        this.userPublicDeckIdsLoading = true;
        this.userPublicDeckIdsLoaded = false;
        this.deckApi.getMyPublicDeckIds().pipe(
            catchError(() => of({ publicDeckIds: [] }))
        ).subscribe({
            next: result => {
                this.userPublicDeckIds = new Set(result.publicDeckIds || []);
                this.userPublicDeckIdsLoaded = true;
                this.updateCanFork();
            },
            error: () => {
                this.userPublicDeckIdsLoaded = true;
            },
            complete: () => {
                this.userPublicDeckIdsLoading = false;
            }
        });
    }

    private updateCanFork(): void {
        if (this.auth.status() !== 'authenticated' || !this.deck || !this.currentUserId || !this.userPublicDeckIdsLoaded) {
            this.canFork = false;
            return;
        }
        if (this.currentUserId && this.deck.authorId === this.currentUserId) {
            this.canFork = false;
            return;
        }
        this.canFork = !this.userPublicDeckIds.has(this.deck.deckId);
    }

    private buildTemplateFromFields(fields: FieldTemplateDTO[]): void {
        const frontFields = fields.filter(f => f.isOnFront).sort((a, b) => a.orderIndex - b.orderIndex);
        const backFields = fields.filter(f => !f.isOnFront).sort((a, b) => a.orderIndex - b.orderIndex);

        this.template = {
            templateId: fields[0]?.templateId || '',
            ownerId: '',
            name: 'Public Template',
            description: '',
            isPublic: true,
            createdAt: '',
            updatedAt: '',
            layout: {
                front: frontFields.map(f => f.name),
                back: backFields.map(f => f.name)
            },
            fields: fields
        };
    }

    get currentCard(): PublicCardDTO | null {
        return this.visibleCards[this.currentCardIndex] || null;
    }

    get cardCount(): number {
        if (this.searchActive) {
            return this.visibleCards.length;
        }
        return this.totalCards || this.cards.length;
    }

    get searchActive(): boolean {
        return this.searchQuery.trim().length > 0;
    }

    get searchNoResults(): boolean {
        return this.searchActive && this.visibleCards.length === 0;
    }

    get visibleCards(): PublicCardDTO[] {
        const query = this.searchQuery.trim().toLowerCase();
        if (!query) {
            return this.cards;
        }

        return this.cards.filter(card => this.matchesSearch(card, query));
    }

    previousCard(): void {
        if (this.currentCardIndex > 0) {
            this.currentCardIndex--;
            this.revealed = false;
        }
    }

    nextCard(): void {
        if (this.currentCardIndex < this.visibleCards.length - 1) {
            this.currentCardIndex++;
            this.revealed = false;
            this.maybePrefetchMoreCards();
        }
    }

    private maybePrefetchMoreCards(): void {
        const cardPool = this.searchActive ? this.visibleCards : this.cards;
        if (!this.hasMoreCards || this.loadingMore || cardPool.length === 0) {
            return;
        }
        const thresholdIndex = Math.floor(cardPool.length * PublicCardBrowserComponent.PREFETCH_THRESHOLD);
        if (this.currentCardIndex + 1 >= thresholdIndex) {
            this.loadMoreCards();
        }
    }

    private maybeLoadMoreOnScroll(container: HTMLElement): void {
        if (!this.hasMoreCards || this.loadingMore || this.cards.length === 0) {
            return;
        }
        const scrollPosition = container.scrollTop + container.clientHeight;
        const threshold = container.scrollHeight * PublicCardBrowserComponent.PREFETCH_THRESHOLD;
        if (scrollPosition >= threshold) {
            this.loadMoreCards();
        }
    }

    private loadMoreCards(): void {
        if (this.loadingMore || !this.hasMoreCards) {
            return;
        }
        this.loadingMore = true;
        const nextPage = this.currentPage + 1;
        this.publicDeckApi.getPublicDeckCards(this.deckId, undefined, nextPage, PublicCardBrowserComponent.PAGE_SIZE)
            .subscribe({
                next: page => {
                    const existingIds = new Set(this.cards.map(card => card.cardId));
                    const newCards = page.content.filter(card => !existingIds.has(card.cardId));
                    this.cards = [...this.cards, ...newCards];
                    this.currentPage = page.number + 1;
                    this.hasMoreCards = !page.last;
                    this.totalCards = Math.max(this.totalCards, page.totalElements || 0);
                },
                error: err => {
                    console.error('Failed to load more public cards:', err);
                    this.loadingMore = false;
                },
                complete: () => {
                    this.loadingMore = false;
                }
            });
    }

    toggleReveal(): void {
        this.revealed = !this.revealed;
    }

    onSearchInput(event: Event): void {
        const input = event.target as HTMLInputElement;
        this.searchQuery = input.value;
        if (this.searchDebounce) {
            clearTimeout(this.searchDebounce);
        }
        this.searchDebounce = setTimeout(() => {
            this.currentCardIndex = 0;
            this.revealed = false;
        }, 200);
    }

    private getPreviewText(value: CardContentValue | undefined, fieldType?: string): string {
        if (!value) return '';
        if (typeof value === 'string') return value;
        if (fieldType === 'image') return '[Image]';
        if (fieldType === 'audio') return '[Audio]';
        if (fieldType === 'video') return '[Video]';
        return '[Media]';
    }

    getFrontPreview(card: PublicCardDTO): string {
        const anki = (card.content as any)?._anki;
        if (this.template?.layout?.renderMode === 'anki' || anki) {
            const html = typeof anki?.front === 'string' ? anki.front : '';
            const text = this.stripHtml(html);
            return text.length > 80 ? text.substring(0, 80) + '...' : text;
        }

        if (!this.template || !this.template.layout) {
            const firstValue = Object.values(card.content)[0];
            return this.getPreviewText(firstValue);
        }

        const frontFieldNames = this.template.layout.front.slice(0, 2);
        const values = frontFieldNames
            .map(name => {
                const field = this.template?.fields?.find(f => f.name === name);
                return this.getPreviewText(card.content[name], field?.fieldType);
            })
            .filter(v => v)
            .join(' - ');

        return values.length > 80 ? values.substring(0, 80) + '...' : values;
    }

    openCardFromList(index: number): void {
        if (index < 0 || index >= this.visibleCards.length) {
            return;
        }
        this.currentCardIndex = index;
        this.revealed = false;
        this.maybePrefetchMoreCards();
    }

    private stripHtml(value: string): string {
        return value.replace(/<[^>]+>/g, '').replace(/\s+/g, ' ').trim();
    }

    forkDeck(): void {
        if (this.auth.status() !== 'authenticated') {
            void this.router.navigate(['/login']);
            return;
        }

        if (!this.deckId) return;

        this.publicDeckApi.fork(this.deckId).subscribe({
            next: userDeck => {
                this.applyDeckTimeZone(userDeck.userDeckId, userDeck.algorithmId);
                void this.router.navigate(['/decks', userDeck.userDeckId]);
            },
            error: err => {
                console.error('Failed to fork deck:', err);
            }
        });
    }

    private applyDeckTimeZone(userDeckId: string, algorithmId: string): void {
        const timeZone = this.resolveBrowserTimeZone();
        if (!timeZone) {
            return;
        }
        this.reviewApi.updateDeckAlgorithm(userDeckId, {
            algorithmId,
            algorithmParams: null,
            reviewPreferences: { timeZone }
        }).subscribe({ error: () => {} });
    }

    private resolveBrowserTimeZone(): string | null {
        try {
            return Intl.DateTimeFormat().resolvedOptions().timeZone || null;
        } catch {
            return null;
        }
    }

    private matchesSearch(card: PublicCardDTO, query: string): boolean {
        const text = this.buildSearchText(card);
        return text.includes(query);
    }

    private buildSearchText(card: PublicCardDTO): string {
        const parts: string[] = [];
        const preview = this.getFrontPreview(card);
        if (preview) {
            parts.push(preview);
        }
        if (card.tags?.length) {
            parts.push(card.tags.join(' '));
        }
        Object.values(card.content || {}).forEach(value => {
            if (typeof value === 'string') {
                parts.push(value);
            }
        });
        return parts.join(' ').toLowerCase();
    }
}
