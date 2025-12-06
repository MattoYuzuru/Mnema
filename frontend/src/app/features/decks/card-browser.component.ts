import { Component, OnInit, HostListener } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { NgIf, NgFor } from '@angular/common';
import { forkJoin } from 'rxjs';
import { CardApiService } from '../../core/services/card-api.service';
import { DeckApiService } from '../../core/services/deck-api.service';
import { PublicDeckApiService } from '../../core/services/public-deck-api.service';
import { TemplateApiService } from '../../core/services/template-api.service';
import { UserCardDTO } from '../../core/models/user-card.models';
import { UserDeckDTO } from '../../core/models/user-deck.models';
import { CardTemplateDTO } from '../../core/models/template.models';
import { MemoryTipLoaderComponent } from '../../shared/components/memory-tip-loader.component';
import { EmptyStateComponent } from '../../shared/components/empty-state.component';
import { FlashcardViewComponent } from '../../shared/components/flashcard-view.component';
import { ButtonComponent } from '../../shared/components/button.component';

@Component({
    selector: 'app-card-browser',
    standalone: true,
    imports: [NgIf, NgFor, MemoryTipLoaderComponent, EmptyStateComponent, FlashcardViewComponent, ButtonComponent],
    template: `
    <app-memory-tip-loader *ngIf="loading"></app-memory-tip-loader>

    <div *ngIf="!loading" class="card-browser">
      <header class="page-header">
        <div class="header-left">
          <h1>Card Browser</h1>
          <p class="card-count">{{ cards.length }} cards</p>
        </div>
        <div class="header-right">
          <div class="view-mode-toggle">
            <app-button
              [variant]="viewMode === 'list' ? 'primary' : 'ghost'"
              size="sm"
              (click)="setViewMode('list')"
            >
              List
            </app-button>
            <app-button
              [variant]="viewMode === 'cards' ? 'primary' : 'ghost'"
              size="sm"
              (click)="setViewMode('cards')"
            >
              Cards
            </app-button>
          </div>
        </div>
      </header>

      <div *ngIf="viewMode === 'list' && cards.length > 0" class="cards-table">
        <div class="card-row header-row">
          <div class="card-col">Front Preview</div>
          <div class="card-col">Reviews</div>
          <div class="card-col">Next Review</div>
          <div class="card-col">Status</div>
        </div>
        <div *ngFor="let card of cards" class="card-row">
          <div class="card-col">{{ getFrontPreview(card) }}</div>
          <div class="card-col">{{ card.reviewCount }}</div>
          <div class="card-col">{{ formatDate(card.nextReviewAt) }}</div>
          <div class="card-col">
            <span *ngIf="card.isSuspended" class="status-badge suspended">Suspended</span>
            <span *ngIf="!card.isSuspended" class="status-badge active">Active</span>
          </div>
        </div>
      </div>

      <div *ngIf="viewMode === 'cards' && cards.length > 0" class="card-view-mode">
        <div class="card-navigation">
          <app-button
            variant="ghost"
            size="sm"
            (click)="previousCard()"
            [disabled]="currentCardIndex === 0"
          >
            ← Previous
          </app-button>
          <span class="card-counter">{{ currentCardIndex + 1 }} / {{ cards.length }}</span>
          <app-button
            variant="ghost"
            size="sm"
            (click)="nextCard()"
            [disabled]="currentCardIndex >= cards.length - 1"
          >
            Next →
          </app-button>
        </div>

        <div class="flashcard-container">
          <div class="flashcard" [class.flipped]="isFlipped" (click)="toggleFlip()">
            <div class="flashcard-inner">
              <div class="flashcard-face front">
                <app-flashcard-view
                  *ngIf="template && currentCard"
                  [template]="template"
                  [content]="currentCard.effectiveContent"
                  side="front"
                ></app-flashcard-view>
              </div>
              <div class="flashcard-face back">
                <app-flashcard-view
                  *ngIf="template && currentCard"
                  [template]="template"
                  [content]="currentCard.effectiveContent"
                  side="back"
                ></app-flashcard-view>
              </div>
            </div>
          </div>
          <p class="flip-hint">Click card to flip</p>
        </div>

        <div *ngIf="currentCard?.personalNote" class="personal-note">
          <h3>Personal Note</h3>
          <p>{{ currentCard?.personalNote }}</p>
        </div>
      </div>

      <app-empty-state
        *ngIf="cards.length === 0"
        icon="📝"
        title="No cards yet"
        description="Add cards to this deck to start learning"
      ></app-empty-state>
    </div>
  `,
    styles: [`
      .card-browser {
        max-width: 72rem;
        margin: 0 auto;
      }

      .page-header {
        display: flex;
        align-items: center;
        justify-content: space-between;
        margin-bottom: var(--spacing-xl);
      }

      .header-left h1 {
        font-size: 2rem;
        margin: 0 0 var(--spacing-xs) 0;
      }

      .card-count {
        font-size: 1rem;
        color: var(--color-text-muted);
        margin: 0;
      }

      .view-mode-toggle {
        display: flex;
        gap: var(--spacing-xs);
      }

      .cards-table {
        background: var(--color-card-background);
        border: 1px solid var(--border-color);
        border-radius: var(--border-radius-lg);
        overflow: hidden;
      }

      .card-row {
        display: grid;
        grid-template-columns: 3fr 1fr 1.5fr 1fr;
        gap: var(--spacing-md);
        padding: var(--spacing-md);
        border-bottom: 1px solid var(--border-color);
      }

      .card-row:last-child {
        border-bottom: none;
      }

      .header-row {
        background: var(--color-background);
        font-weight: 600;
      }

      .card-col {
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }

      .status-badge {
        display: inline-block;
        padding: var(--spacing-xs) var(--spacing-sm);
        border-radius: var(--border-radius-full);
        font-size: 0.75rem;
        font-weight: 600;
      }

      .status-badge.active {
        background: #dcfce7;
        color: #166534;
      }

      .status-badge.suspended {
        background: #fee2e2;
        color: #991b1b;
      }

      .card-view-mode {
        display: flex;
        flex-direction: column;
        gap: var(--spacing-xl);
      }

      .card-navigation {
        display: flex;
        align-items: center;
        justify-content: center;
        gap: var(--spacing-lg);
      }

      .card-counter {
        font-size: 1rem;
        font-weight: 600;
        color: var(--color-text-primary);
        min-width: 80px;
        text-align: center;
      }

      .flashcard-container {
        display: flex;
        flex-direction: column;
        align-items: center;
        gap: var(--spacing-md);
      }

      .flashcard {
        width: 100%;
        max-width: 600px;
        min-height: 400px;
        cursor: pointer;
        perspective: 1000px;
      }

      .flashcard-inner {
        position: relative;
        width: 100%;
        min-height: 400px;
        transition: transform 0.6s;
        transform-style: preserve-3d;
      }

      .flashcard.flipped .flashcard-inner {
        transform: rotateY(180deg);
      }

      .flashcard-face {
        position: absolute;
        width: 100%;
        min-height: 400px;
        backface-visibility: hidden;
        background: var(--color-card-background);
        border: 1px solid var(--border-color);
        border-radius: var(--border-radius-lg);
        padding: var(--spacing-xl);
        display: flex;
        align-items: center;
        justify-content: center;
      }

      .flashcard-face.back {
        transform: rotateY(180deg);
      }

      .flip-hint {
        font-size: 0.9rem;
        color: var(--color-text-muted);
        text-align: center;
        margin: 0;
      }

      .personal-note {
        max-width: 600px;
        margin: 0 auto;
        padding: var(--spacing-lg);
        background: var(--color-card-background);
        border: 1px solid var(--border-color);
        border-radius: var(--border-radius-lg);
      }

      .personal-note h3 {
        font-size: 1rem;
        font-weight: 600;
        margin: 0 0 var(--spacing-sm) 0;
        color: var(--color-text-secondary);
      }

      .personal-note p {
        margin: 0;
        color: var(--color-text-primary);
      }
    `]
})
export class CardBrowserComponent implements OnInit {
    loading = true;
    cards: UserCardDTO[] = [];
    deck: UserDeckDTO | null = null;
    template: CardTemplateDTO | null = null;
    userDeckId = '';
    viewMode: 'list' | 'cards' = 'list';
    currentCardIndex = 0;
    isFlipped = false;

    constructor(
        private route: ActivatedRoute,
        private cardApi: CardApiService,
        private deckApi: DeckApiService,
        private publicDeckApi: PublicDeckApiService,
        private templateApi: TemplateApiService
    ) {}

    ngOnInit(): void {
        this.userDeckId = this.route.snapshot.paramMap.get('userDeckId') || '';
        if (this.userDeckId) {
            this.loadDeckData();
        }
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
        this.deckApi.getUserDeck(this.userDeckId).subscribe({
            next: deck => {
                this.deck = deck;
                this.loadTemplateAndCards(deck.publicDeckId);
            },
            error: err => {
                console.error('Failed to load deck:', err);
                this.loading = false;
            }
        });
    }

    private loadTemplateAndCards(publicDeckId: string): void {
        this.publicDeckApi.getPublicDeck(publicDeckId).subscribe({
            next: publicDeck => {
                forkJoin({
                    template: this.templateApi.getTemplate(publicDeck.templateId),
                    cards: this.cardApi.getUserCards(this.userDeckId, 1, 100)
                }).subscribe({
                    next: ({ template, cards }) => {
                        this.template = template;
                        this.cards = cards.content;
                        this.loading = false;
                    },
                    error: err => {
                        console.error('Failed to load template and cards:', err);
                        this.loading = false;
                    }
                });
            },
            error: err => {
                console.error('Failed to load public deck:', err);
                this.loading = false;
            }
        });
    }

    get currentCard(): UserCardDTO | null {
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

    getFrontPreview(card: UserCardDTO): string {
        if (!this.template || !this.template.layout) {
            return Object.values(card.effectiveContent)[0] || '';
        }

        const frontFieldNames = this.template.layout.front.slice(0, 2);
        const values = frontFieldNames
            .map(name => card.effectiveContent[name])
            .filter(v => v)
            .join(' - ');

        return values.length > 80 ? values.substring(0, 80) + '...' : values;
    }

    formatDate(date: string | null | undefined): string {
        if (!date) return 'N/A';
        return new Date(date).toLocaleDateString();
    }
}
