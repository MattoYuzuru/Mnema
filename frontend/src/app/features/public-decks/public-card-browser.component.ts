import { Component, OnInit } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { NgIf, NgFor } from '@angular/common';
import { forkJoin } from 'rxjs';
import { AuthService } from '../../auth.service';
import { PublicDeckApiService } from '../../core/services/public-deck-api.service';
import { PublicDeckDTO, PublicCardDTO } from '../../core/models/public-deck.models';
import { CardTemplateDTO, FieldTemplateDTO } from '../../core/models/template.models';
import { MemoryTipLoaderComponent } from '../../shared/components/memory-tip-loader.component';
import { EmptyStateComponent } from '../../shared/components/empty-state.component';
import { FlashcardViewComponent } from '../../shared/components/flashcard-view.component';
import { ButtonComponent } from '../../shared/components/button.component';

@Component({
    selector: 'app-public-card-browser',
    standalone: true,
    imports: [NgIf, NgFor, MemoryTipLoaderComponent, EmptyStateComponent, FlashcardViewComponent, ButtonComponent],
    template: `
    <app-memory-tip-loader *ngIf="loading"></app-memory-tip-loader>

    <div *ngIf="!loading" class="public-card-browser">
      <header class="page-header">
        <div class="header-left">
          <h1>{{ deck?.name || 'Public Deck' }}</h1>
          <p class="deck-description">{{ deck?.description }}</p>
          <p class="card-count">{{ cards.length }} cards</p>
        </div>
        <div class="header-right">
          <app-button variant="primary" size="md" (click)="forkDeck()">Fork Deck</app-button>
          <div class="view-mode-toggle">
            <app-button [variant]="viewMode === 'list' ? 'primary' : 'ghost'" size="sm" (click)="setViewMode('list')">List</app-button>
            <app-button [variant]="viewMode === 'cards' ? 'primary' : 'ghost'" size="sm" (click)="setViewMode('cards')">Cards</app-button>
          </div>
        </div>
      </header>

      <div *ngIf="viewMode === 'list' && cards.length > 0" class="cards-table">
        <div class="card-row header-row">
          <div class="card-col">Front Preview</div>
          <div class="card-col">Tags</div>
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
          <app-button variant="ghost" size="sm" (click)="previousCard()" [disabled]="currentCardIndex === 0">← Previous</app-button>
          <span class="card-counter">{{ currentCardIndex + 1 }} / {{ cards.length }}</span>
          <app-button variant="ghost" size="sm" (click)="nextCard()" [disabled]="currentCardIndex >= cards.length - 1">Next →</app-button>
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
          <p class="flip-hint">Click card to flip</p>
        </div>
      </div>

      <app-empty-state *ngIf="cards.length === 0" icon="📝" title="No cards in this deck" description="This public deck doesn't have any cards yet"></app-empty-state>
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
      .flashcard-inner { position: relative; width: 100%; min-height: 400px; transition: transform 0.6s; transform-style: preserve-3d; }
      .flashcard.flipped .flashcard-inner { transform: rotateY(180deg); }
      .flashcard-face { position: absolute; width: 100%; min-height: 400px; backface-visibility: hidden; background: var(--color-card-background); border: 1px solid var(--border-color); border-radius: var(--border-radius-lg); padding: var(--spacing-xl); display: flex; align-items: center; justify-content: center; }
      .flashcard-face.back { transform: rotateY(180deg); }
      .flip-hint { font-size: 0.9rem; color: var(--color-text-muted); text-align: center; margin: 0; }
    `]
})
export class PublicCardBrowserComponent implements OnInit {
    loading = true;
    cards: PublicCardDTO[] = [];
    deck: PublicDeckDTO | null = null;
    template: CardTemplateDTO | null = null;
    deckId = '';
    viewMode: 'list' | 'cards' = 'list';
    currentCardIndex = 0;
    isFlipped = false;

    constructor(
        private route: ActivatedRoute,
        private router: Router,
        private publicDeckApi: PublicDeckApiService,
        public auth: AuthService
    ) {}

    ngOnInit(): void {
        this.deckId = this.route.snapshot.paramMap.get('deckId') || '';
        if (this.deckId) {
            this.loadDeckData();
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
                this.loading = false;
            },
            error: err => {
                console.error('Failed to load public deck:', err);
                this.loading = false;
            }
        });
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

    getFrontPreview(card: PublicCardDTO): string {
        if (!this.template || !this.template.layout) {
            return Object.values(card.content)[0] || '';
        }

        const frontFieldNames = this.template.layout.front.slice(0, 2);
        const values = frontFieldNames
            .map(name => card.content[name])
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
