import { Component, OnInit, HostListener, OnDestroy } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { NgIf, NgFor } from '@angular/common';
import { forkJoin, of, Subscription } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { AuthService } from '../../auth.service';
import { UserApiService } from '../../user-api.service';
import { DeckApiService } from '../../core/services/deck-api.service';
import { PublicDeckApiService } from '../../core/services/public-deck-api.service';
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
    imports: [NgIf, NgFor, MemoryTipLoaderComponent, EmptyStateComponent, FlashcardViewComponent, ButtonComponent, TranslatePipe, TagChipComponent],
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
          <p class="card-count">{{ cardCount }} {{ 'publicCardBrowser.cards' | translate }}</p>
        </div>
        <div class="header-right">
          <app-button *ngIf="canFork" variant="primary" size="md" (click)="forkDeck()">{{ 'button.fork' | translate }}</app-button>
          <div class="view-mode-toggle">
            <app-button [variant]="viewMode === 'list' ? 'primary' : 'ghost'" size="sm" (click)="setViewMode('list')">{{ 'cardBrowser.list' | translate }}</app-button>
            <app-button [variant]="viewMode === 'cards' ? 'primary' : 'ghost'" size="sm" (click)="setViewMode('cards')">{{ 'cardBrowser.cardsView' | translate }}</app-button>
          </div>
        </div>
      </header>

      <div *ngIf="viewMode === 'list'" class="cards-toolbar">
        <input
          type="search"
          class="card-search"
          [placeholder]="'publicCardBrowser.searchPlaceholder' | translate"
          [attr.aria-label]="'publicCardBrowser.searchPlaceholder' | translate"
          [value]="searchQuery"
          (input)="onSearchInput($event)"
        />
        <span *ngIf="searchQuery" class="search-meta">
          {{ visibleCards.length }} / {{ totalCards || cards.length }} {{ 'publicCardBrowser.cards' | translate }}
        </span>
      </div>

      <div *ngIf="viewMode === 'list' && visibleCards.length > 0" class="cards-table">
        <div class="card-row header-row">
          <div class="card-col">{{ 'publicCardBrowser.frontPreview' | translate }}</div>
          <div class="card-col">{{ 'cardBrowser.tags' | translate }}</div>
        </div>
        <div *ngFor="let card of visibleCards; let index = index" class="card-row">
          <button class="card-col card-preview" type="button" (click)="openCardFromList(index)">
            {{ getFrontPreview(card) }}
          </button>
          <div class="card-col">
            <span *ngFor="let tag of card.tags" class="tag-chip">{{ tag }}</span>
          </div>
        </div>
      </div>

      <div *ngIf="viewMode === 'cards' && cards.length > 0" class="card-view-mode">
        <div class="card-navigation">
          <app-button variant="ghost" size="sm" (click)="previousCard()" [disabled]="currentCardIndex === 0">{{ 'cardBrowser.previous' | translate }}</app-button>
          <span class="card-counter">{{ currentCardIndex + 1 }} / {{ cardCount }}</span>
          <app-button variant="ghost" size="sm" (click)="nextCard()" [disabled]="currentCardIndex >= cards.length - 1">{{ 'cardBrowser.next' | translate }}</app-button>
        </div>

        <div class="flashcard-container">
          <div class="flashcard" (click)="toggleReveal()">
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
      </div>

      <app-empty-state
        *ngIf="visibleCards.length === 0"
        icon="📝"
        [title]="searchQuery ? ('publicCardBrowser.noSearchResults' | translate) : ('publicCardBrowser.noCards' | translate)"
        [description]="searchQuery ? ('publicCardBrowser.noSearchResultsDescription' | translate) : ('publicCardBrowser.noCardsDescription' | translate)"
      ></app-empty-state>
    </div>
  `,
    styles: [`
      .public-card-browser { max-width: 72rem; margin: 0 auto; }
      .page-header { margin-bottom: var(--spacing-xl); }
      .header-left h1 { font-size: 2rem; margin: 0 0 var(--spacing-sm) 0; }
      .deck-description { font-size: 1rem; color: var(--color-text-secondary); margin: 0 0 var(--spacing-xs) 0; line-height: 1.6; }
      .deck-description h1,
      .deck-description h2,
      .deck-description h3 { font-size: 1rem; margin: 0 0 var(--spacing-xs) 0; font-weight: 600; }
      .deck-description ul { margin: 0 0 var(--spacing-xs) 0; padding-left: 1.2rem; }
      .deck-description li { margin: 0; }
      .deck-description pre { margin: 0 0 var(--spacing-xs) 0; padding: var(--spacing-sm); background: var(--color-background); border-radius: var(--border-radius-md); white-space: pre-wrap; }
      .deck-description code { font-family: inherit; font-size: 0.9rem; background: var(--color-background); padding: 0 0.2rem; border-radius: var(--border-radius-sm); }
      .deck-tags { display: flex; flex-wrap: wrap; gap: var(--spacing-xs); margin-bottom: var(--spacing-xs); }
      .card-count { font-size: 0.9rem; color: var(--color-text-muted); margin: 0 0 var(--spacing-md) 0; }
      .header-right { display: flex; gap: var(--spacing-md); align-items: center; }
      .view-mode-toggle { display: flex; gap: var(--spacing-xs); }
      .cards-toolbar { display: flex; align-items: center; gap: var(--spacing-md); margin-bottom: var(--spacing-md); }
      .card-search { flex: 1; padding: var(--spacing-sm) var(--spacing-md); border: 1px solid var(--border-color); border-radius: var(--border-radius-full); font-size: 0.9rem; background: var(--color-background); color: var(--color-text-primary); }
      .card-search:focus { outline: none; border-color: var(--color-primary-accent); }
      .search-meta { font-size: 0.85rem; color: var(--color-text-muted); white-space: nowrap; }
      .cards-table { background: var(--color-card-background); border: 1px solid var(--border-color); border-radius: var(--border-radius-lg); overflow: hidden; }
      .card-row { display: grid; grid-template-columns: 3fr 1fr; gap: var(--spacing-md); padding: var(--spacing-md); border-bottom: 1px solid var(--border-color); }
      .card-row:last-child { border-bottom: none; }
      .header-row { background: var(--color-background); font-weight: 600; }
      .card-col { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
      .card-preview { text-align: left; background: none; border: none; padding: 0; color: var(--color-text-primary); cursor: pointer; font: inherit; }
      .card-preview:hover { text-decoration: underline; }
      .tag-chip { display: inline-block; padding: var(--spacing-xs) var(--spacing-sm); margin-right: var(--spacing-xs); background: var(--color-background); border: 1px solid var(--border-color); border-radius: var(--border-radius-full); font-size: 0.75rem; }
      .card-view-mode { display: flex; flex-direction: column; gap: var(--spacing-xl); }
      .card-navigation { display: flex; align-items: center; justify-content: center; gap: var(--spacing-lg); }
      .card-counter { font-size: 1rem; font-weight: 600; color: var(--color-text-primary); min-width: 80px; text-align: center; }
      .flashcard-container { display: flex; flex-direction: column; align-items: center; gap: var(--spacing-md); }
      .flashcard { width: 100%; max-width: 42rem; min-height: 16rem; cursor: pointer; background: var(--color-card-background); border: 1px solid var(--border-color); border-radius: var(--border-radius-lg); padding: var(--spacing-xl); display: flex; align-items: center; justify-content: center; box-shadow: var(--shadow-md); }
      .flashcard-content { width: 100%; display: flex; flex-direction: column; gap: var(--spacing-lg); position: relative; }
      .card-side { width: 100%; }
      .card-side.preload { position: absolute; left: -9999px; top: 0; height: 0; overflow: hidden; pointer-events: none; visibility: hidden; }
      .divider { height: 1px; background: var(--border-color); margin: var(--spacing-md) 0; }
      .flip-hint { font-size: 0.9rem; color: var(--color-text-muted); text-align: center; margin: 0; }

      @media (max-width: 768px) {
        .public-card-browser {
          padding: 0 var(--spacing-md);
        }

        .page-header {
          display: flex;
          flex-direction: column;
          gap: var(--spacing-md);
        }

        .header-right {
          width: 100%;
          flex-wrap: wrap;
          justify-content: space-between;
        }

        .view-mode-toggle {
          width: 100%;
          flex-wrap: wrap;
          justify-content: flex-start;
        }

        .cards-toolbar {
          flex-direction: column;
          align-items: stretch;
        }

        .cards-table {
          overflow-x: auto;
          -webkit-overflow-scrolling: touch;
        }

        .card-row {
          grid-template-columns: 1fr;
        }

        .card-col {
          white-space: normal;
        }

        .card-navigation {
          flex-wrap: wrap;
          gap: var(--spacing-md);
        }

        .flashcard { min-height: 14rem; padding: var(--spacing-lg); }
      }

      @media (max-width: 480px) {
        .public-card-browser {
          padding: 0 var(--spacing-sm);
        }

        .header-left h1 {
          font-size: 1.5rem;
        }

        .flashcard { min-height: 12rem; padding: var(--spacing-md); }
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
    viewMode: 'list' | 'cards' = 'list';
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
        if (this.viewMode !== 'cards') return;

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

    @HostListener('window:scroll')
    handleScroll(): void {
        if (this.viewMode !== 'list') return;
        this.maybeLoadMoreOnScroll();
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
        if (this.searchQuery) {
            return this.visibleCards.length;
        }
        return this.totalCards || this.cards.length;
    }

    get visibleCards(): PublicCardDTO[] {
        const query = this.searchQuery.trim().toLowerCase();
        if (!query) {
            return this.cards;
        }

        return this.cards.filter(card => this.matchesSearch(card, query));
    }

    setViewMode(mode: 'list' | 'cards'): void {
        this.viewMode = mode;
        this.currentCardIndex = 0;
        this.revealed = false;
        if (mode === 'cards') {
            this.maybePrefetchMoreCards();
        }
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
        const cardPool = this.searchQuery ? this.visibleCards : this.cards;
        if (!this.hasMoreCards || this.loadingMore || cardPool.length === 0) {
            return;
        }
        const thresholdIndex = Math.floor(cardPool.length * PublicCardBrowserComponent.PREFETCH_THRESHOLD);
        if (this.currentCardIndex + 1 >= thresholdIndex) {
            this.loadMoreCards();
        }
    }

    private maybeLoadMoreOnScroll(): void {
        if (!this.hasMoreCards || this.loadingMore || this.cards.length === 0) {
            return;
        }
        const scrollPosition = window.scrollY + window.innerHeight;
        const threshold = document.documentElement.scrollHeight * PublicCardBrowserComponent.PREFETCH_THRESHOLD;
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
        this.viewMode = 'cards';
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
                void this.router.navigate(['/decks', userDeck.userDeckId]);
            },
            error: err => {
                console.error('Failed to fork deck:', err);
            }
        });
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
