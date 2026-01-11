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
import { UserDeckDTO } from '../../core/models/user-deck.models';
import { CardContentValue } from '../../core/models/user-card.models';
import { CardTemplateDTO, FieldTemplateDTO } from '../../core/models/template.models';
import { MemoryTipLoaderComponent } from '../../shared/components/memory-tip-loader.component';
import { EmptyStateComponent } from '../../shared/components/empty-state.component';
import { FlashcardViewComponent } from '../../shared/components/flashcard-view.component';
import { ButtonComponent } from '../../shared/components/button.component';
import { TranslatePipe } from '../../shared/pipes/translate.pipe';

@Component({
    selector: 'app-public-card-browser',
    standalone: true,
    imports: [NgIf, NgFor, MemoryTipLoaderComponent, EmptyStateComponent, FlashcardViewComponent, ButtonComponent, TranslatePipe],
    template: `
    <app-memory-tip-loader *ngIf="loading"></app-memory-tip-loader>

    <div *ngIf="!loading" class="public-card-browser">
      <header class="page-header">
        <div class="header-left">
          <h1>{{ deck?.name || ('publicCardBrowser.publicDeck' | translate) }}</h1>
          <p class="deck-description">{{ deck?.description }}</p>
          <p class="card-count">{{ cards.length }} {{ 'publicCardBrowser.cards' | translate }}</p>
        </div>
        <div class="header-right">
          <app-button *ngIf="canFork" variant="primary" size="md" (click)="forkDeck()">{{ 'button.fork' | translate }}</app-button>
          <div class="view-mode-toggle">
            <app-button [variant]="viewMode === 'list' ? 'primary' : 'ghost'" size="sm" (click)="setViewMode('list')">{{ 'cardBrowser.list' | translate }}</app-button>
            <app-button [variant]="viewMode === 'cards' ? 'primary' : 'ghost'" size="sm" (click)="setViewMode('cards')">{{ 'cardBrowser.cardsView' | translate }}</app-button>
          </div>
        </div>
      </header>

      <div *ngIf="viewMode === 'list' && cards.length > 0" class="cards-table">
        <div class="card-row header-row">
          <div class="card-col">{{ 'publicCardBrowser.frontPreview' | translate }}</div>
          <div class="card-col">{{ 'cardBrowser.tags' | translate }}</div>
        </div>
        <div *ngFor="let card of cards" class="card-row">
          <div class="card-col">{{ getFrontPreview(card) }}</div>
          <div class="card-col">
            <span *ngFor="let tag of card.tags" class="tag-chip">{{ tag }}</span>
          </div>
        </div>
      </div>

      <div *ngIf="viewMode === 'cards' && cards.length > 0" class="card-view-mode">
        <div class="card-navigation">
          <app-button variant="ghost" size="sm" (click)="previousCard()" [disabled]="currentCardIndex === 0">{{ 'cardBrowser.previous' | translate }}</app-button>
          <span class="card-counter">{{ currentCardIndex + 1 }} / {{ cards.length }}</span>
          <app-button variant="ghost" size="sm" (click)="nextCard()" [disabled]="currentCardIndex >= cards.length - 1">{{ 'cardBrowser.next' | translate }}</app-button>
        </div>

        <div class="flashcard-container">
          <div class="flashcard" [class.flipped]="isFlipped" (click)="toggleFlip()">
            <div class="flashcard-inner">
              <div class="flashcard-face front">
                <app-flashcard-view *ngIf="template && currentCard" [template]="template" [content]="currentCard.content" side="front"></app-flashcard-view>
              </div>
              <div class="flashcard-face back">
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

      <app-empty-state *ngIf="cards.length === 0" icon="📝" [title]="'publicCardBrowser.noCards' | translate" [description]="'publicCardBrowser.noCardsDescription' | translate"></app-empty-state>
    </div>
  `,
    styles: [`
      .public-card-browser { max-width: 72rem; margin: 0 auto; }
      .page-header { margin-bottom: var(--spacing-xl); }
      .header-left h1 { font-size: 2rem; margin: 0 0 var(--spacing-sm) 0; }
      .deck-description { font-size: 1rem; color: var(--color-text-secondary); margin: 0 0 var(--spacing-xs) 0; }
      .card-count { font-size: 0.9rem; color: var(--color-text-muted); margin: 0 0 var(--spacing-md) 0; }
      .header-right { display: flex; gap: var(--spacing-md); align-items: center; }
      .view-mode-toggle { display: flex; gap: var(--spacing-xs); }
      .cards-table { background: var(--color-card-background); border: 1px solid var(--border-color); border-radius: var(--border-radius-lg); overflow: hidden; }
      .card-row { display: grid; grid-template-columns: 3fr 1fr; gap: var(--spacing-md); padding: var(--spacing-md); border-bottom: 1px solid var(--border-color); }
      .card-row:last-child { border-bottom: none; }
      .header-row { background: var(--color-background); font-weight: 600; }
      .card-col { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
      .tag-chip { display: inline-block; padding: var(--spacing-xs) var(--spacing-sm); margin-right: var(--spacing-xs); background: var(--color-background); border: 1px solid var(--border-color); border-radius: var(--border-radius-full); font-size: 0.75rem; }
      .card-view-mode { display: flex; flex-direction: column; gap: var(--spacing-xl); }
      .card-navigation { display: flex; align-items: center; justify-content: center; gap: var(--spacing-lg); }
      .card-counter { font-size: 1rem; font-weight: 600; color: var(--color-text-primary); min-width: 80px; text-align: center; }
      .flashcard-container { display: flex; flex-direction: column; align-items: center; gap: var(--spacing-md); }
      .flashcard { width: 100%; max-width: 600px; min-height: 400px; cursor: pointer; perspective: 1000px; }
      .flashcard-inner { display: grid; width: 100%; min-height: 400px; transition: transform 0.6s; transform-style: preserve-3d; }
      .flashcard.flipped .flashcard-inner { transform: rotateY(180deg); }
      .flashcard-face { grid-area: 1 / 1; position: relative; width: 100%; height: auto; min-height: 400px; backface-visibility: hidden; background: var(--color-card-background); border: 1px solid var(--border-color); border-radius: var(--border-radius-lg); padding: var(--spacing-xl); display: flex; align-items: center; justify-content: center; }
      .flashcard-face.back { transform: rotateY(180deg); }
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

        .flashcard,
        .flashcard-inner,
        .flashcard-face {
          min-height: 300px;
        }

        .flashcard-face {
          padding: var(--spacing-lg);
        }
      }

      @media (max-width: 480px) {
        .public-card-browser {
          padding: 0 var(--spacing-sm);
        }

        .header-left h1 {
          font-size: 1.5rem;
        }

        .flashcard,
        .flashcard-inner,
        .flashcard-face {
          min-height: 250px;
        }

        .flashcard-face {
          padding: var(--spacing-md);
        }
      }
    `]
})
export class PublicCardBrowserComponent implements OnInit, OnDestroy {
    loading = true;
    cards: PublicCardDTO[] = [];
    deck: PublicDeckDTO | null = null;
    template: CardTemplateDTO | null = null;
    deckId = '';
    viewMode: 'list' | 'cards' = 'list';
    currentCardIndex = 0;
    isFlipped = false;
    canFork = false;
    currentUserId: string | null = null;
    userPublicDeckIds: Set<string> = new Set();
    private authSubscription?: Subscription;
    private userDecksLoading = false;

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
            this.toggleFlip();
        } else if (event.key === 'ArrowLeft') {
            event.preventDefault();
            this.previousCard();
        } else if (event.key === 'ArrowRight') {
            event.preventDefault();
            this.nextCard();
        }
    }

    private loadDeckData(): void {
        this.loading = true;
        forkJoin({
            deck: this.publicDeckApi.getPublicDeck(this.deckId),
            cardsPage: this.publicDeckApi.getPublicDeckCards(this.deckId, undefined, 1, 100)
        }).subscribe({
            next: ({ deck, cardsPage }) => {
                this.deck = deck;
                this.cards = cardsPage.content;
                this.buildTemplateFromFields(cardsPage.fields);
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
                this.userDecksLoading = false;
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

        this.loadUserDeckIds();
    }

    private loadUserDeckIds(): void {
        if (this.userDecksLoading) {
            return;
        }
        this.userDecksLoading = true;
        this.userPublicDeckIds.clear();
        this.fetchUserDeckPage(1);
    }

    private fetchUserDeckPage(page: number): void {
        this.deckApi.getMyDecks(page, 50).pipe(
            catchError(() => of({ content: [] as UserDeckDTO[], last: true, totalPages: 0 }))
        ).subscribe({
            next: result => {
                result.content.forEach(deck => {
                    if (deck.publicDeckId) {
                        this.userPublicDeckIds.add(deck.publicDeckId);
                    }
                });

                if (!result.last && page < (result.totalPages || 0)) {
                    this.fetchUserDeckPage(page + 1);
                    return;
                }

                this.userDecksLoading = false;
                this.updateCanFork();
            },
            error: () => {
                this.userDecksLoading = false;
            }
        });
    }

    private updateCanFork(): void {
        if (this.auth.status() !== 'authenticated' || !this.deck || !this.currentUserId || this.userDecksLoading) {
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
        return this.cards[this.currentCardIndex] || null;
    }

    setViewMode(mode: 'list' | 'cards'): void {
        this.viewMode = mode;
        this.currentCardIndex = 0;
        this.isFlipped = false;
    }

    previousCard(): void {
        if (this.currentCardIndex > 0) {
            this.currentCardIndex--;
            this.isFlipped = false;
        }
    }

    nextCard(): void {
        if (this.currentCardIndex < this.cards.length - 1) {
            this.currentCardIndex++;
            this.isFlipped = false;
        }
    }

    toggleFlip(): void {
        this.isFlipped = !this.isFlipped;
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
}
