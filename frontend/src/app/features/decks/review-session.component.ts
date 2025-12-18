import { Component, OnInit, OnDestroy, HostListener } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { NgIf } from '@angular/common';
import { forkJoin } from 'rxjs';
import { DeckApiService } from '../../core/services/deck-api.service';
import { PublicDeckApiService } from '../../core/services/public-deck-api.service';
import { TemplateApiService } from '../../core/services/template-api.service';
import { ReviewApiService } from '../../core/services/review-api.service';
import { PreferencesService } from '../../core/services/preferences.service';
import { UserDeckDTO } from '../../core/models/user-deck.models';
import { CardTemplateDTO } from '../../core/models/template.models';
import { ReviewNextCardResponse } from '../../core/models/review.models';
import { ButtonComponent } from '../../shared/components/button.component';
import { FlashcardViewComponent } from '../../shared/components/flashcard-view.component';
import { TranslatePipe } from '../../shared/pipes/translate.pipe';

@Component({
    selector: 'app-review-session',
    standalone: true,
    imports: [NgIf, ButtonComponent, FlashcardViewComponent, TranslatePipe],
    template: `
    <div class="review-session">
      <div *ngIf="sessionComplete" class="session-complete">
        <div class="complete-message">
          <h2>{{ 'review.sessionComplete' | translate }}</h2>
          <p>{{ 'review.allCardsReviewed' | translate }}</p>
          <app-button variant="primary" size="lg" (click)="backToDeck()">
            {{ 'review.backToDeck' | translate }}
          </app-button>
        </div>
      </div>

      <div *ngIf="!sessionComplete && currentCard">
        <header class="review-header">
          <h2 *ngIf="deck">{{ deck.displayName }}</h2>
          <div class="progress-info">
            <div class="progress-bar">
              <div class="progress" [style.width.%]="progressPercent"></div>
            </div>
            <p class="progress-text">
              <span class="count-new">{{ 'review.new' | translate }}: {{ queue.newCount }}</span>
              <span class="count-separator">·</span>
              <span class="count-due">{{ 'review.due' | translate }}: {{ queue.dueCount }}</span>
            </p>
          </div>
        </header>

        <div class="card-container">
          <div class="flashcard">
            <app-flashcard-view
              *ngIf="template && currentCard"
              [template]="template"
              [content]="currentCard.effectiveContent"
              [side]="'front'"
              [hideLabels]="preferences.hideFieldLabels"
            ></app-flashcard-view>
          </div>
        </div>

        <div class="answer-buttons">
          <app-button variant="ghost" (click)="answer('AGAIN')">
            {{ 'review.again' | translate }}{{ formatInterval('AGAIN') }}
          </app-button>
          <app-button variant="secondary" (click)="answer('HARD')">
            {{ 'review.hard' | translate }}{{ formatInterval('HARD') }}
          </app-button>
          <app-button variant="primary" (click)="answer('GOOD')">
            {{ 'review.good' | translate }}{{ formatInterval('GOOD') }}
          </app-button>
          <app-button variant="primary" (click)="answer('EASY')">
            {{ 'review.easy' | translate }}{{ formatInterval('EASY') }}
          </app-button>
        </div>

        <div class="keyboard-hint">
          <p>{{ 'review.keyboardShortcuts' | translate }}</p>
        </div>
      </div>
    </div>
  `,
    styles: [`
      .review-session {
        max-width: 56rem;
        margin: 0 auto;
        display: flex;
        flex-direction: column;
        gap: var(--spacing-xl);
        padding-top: var(--spacing-xl);
      }

      .session-complete {
        min-height: 24rem;
        display: flex;
        align-items: center;
        justify-content: center;
      }

      .complete-message {
        text-align: center;
        padding: var(--spacing-2xl);
      }

      .complete-message h2 {
        font-size: 1.75rem;
        margin: 0 0 var(--spacing-md) 0;
      }

      .complete-message p {
        font-size: 1.1rem;
        color: var(--color-text-muted);
        margin: 0 0 var(--spacing-xl) 0;
      }

      .review-header {
        text-align: center;
      }

      .review-header h2 {
        font-size: 1.5rem;
        margin: 0 0 var(--spacing-lg) 0;
      }

      .progress-info {
        display: flex;
        flex-direction: column;
        gap: var(--spacing-sm);
      }

      .progress-bar {
        height: 0.5rem;
        background: var(--border-color);
        border-radius: var(--border-radius-full);
        overflow: hidden;
      }

      .progress {
        height: 100%;
        background: var(--color-primary-accent);
        transition: width 0.3s ease;
      }

      .progress-text {
        font-size: 0.95rem;
        color: var(--color-text-muted);
        margin: 0;
        display: flex;
        align-items: center;
        justify-content: center;
        gap: var(--spacing-xs);
      }

      .count-new {
        color: var(--color-primary-accent);
        font-weight: 500;
      }

      .count-due {
        color: var(--color-text-secondary);
        font-weight: 500;
      }

      .count-separator {
        color: var(--color-text-tertiary);
      }

      .card-container {
        min-height: 20rem;
        display: flex;
        align-items: center;
        justify-content: center;
        padding: var(--spacing-lg) 0;
      }

      .flashcard {
        width: 100%;
        max-width: 36rem;
        min-height: 16rem;
        padding: var(--spacing-2xl);
        background: var(--color-card-background);
        border: 1px solid var(--border-color);
        border-radius: var(--border-radius-lg);
        box-shadow: var(--shadow-md);
        display: flex;
        align-items: center;
        justify-content: center;
      }

      .answer-buttons {
        display: flex;
        justify-content: center;
        gap: var(--spacing-md);
        flex-wrap: wrap;
      }

      .keyboard-hint {
        text-align: center;
        margin-top: var(--spacing-md);
      }

      .keyboard-hint p {
        font-size: 0.85rem;
        color: var(--color-text-tertiary);
        margin: 0;
      }
    `]
})
export class ReviewSessionComponent implements OnInit, OnDestroy {
    deck: UserDeckDTO | null = null;
    template: CardTemplateDTO | null = null;
    currentCard: ReviewNextCardResponse | null = null;
    queue = { newCount: 0, dueCount: 0 };
    initialTotal = 0;
    sessionComplete = false;
    cardShownTime = 0;
    private userDeckId = '';

    constructor(
        private route: ActivatedRoute,
        private router: Router,
        private deckApi: DeckApiService,
        private publicDeckApi: PublicDeckApiService,
        private templateApi: TemplateApiService,
        private reviewApi: ReviewApiService,
        public preferences: PreferencesService
    ) {}

    ngOnInit(): void {
        this.userDeckId = this.route.snapshot.paramMap.get('userDeckId') || '';
        if (this.userDeckId) {
            this.loadDeckAndStart();
        }
    }

    ngOnDestroy(): void {
    }

    @HostListener('window:keydown', ['$event'])
    handleKeyDown(event: KeyboardEvent): void {
        const target = event.target as HTMLElement;
        const isInputField = target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.tagName === 'SELECT' || target.isContentEditable;

        if (isInputField || this.sessionComplete || !this.currentCard) {
            return;
        }

        if (event.key === '1') {
            event.preventDefault();
            this.answer('AGAIN');
        } else if (event.key === '2') {
            event.preventDefault();
            this.answer('HARD');
        } else if (event.key === '3') {
            event.preventDefault();
            this.answer('GOOD');
        } else if (event.key === '4') {
            event.preventDefault();
            this.answer('EASY');
        }
    }

    private loadDeckAndStart(): void {
        const deck$ = this.deckApi.getUserDeck(this.userDeckId);
        const nextCard$ = this.reviewApi.getNextCard(this.userDeckId);

        forkJoin({ deck: deck$, nextCard: nextCard$ }).subscribe({
            next: result => {
                this.deck = result.deck;
                this.handleNextCard(result.nextCard);
                if (this.deck.publicDeckId && !this.sessionComplete) {
                    this.publicDeckApi.getPublicDeck(this.deck.publicDeckId).subscribe({
                        next: publicDeck => {
                            this.templateApi.getTemplate(publicDeck.templateId).subscribe({
                                next: (template: CardTemplateDTO) => {
                                    this.template = template;
                                }
                            });
                        }
                    });
                }
            },
            error: () => {
                this.backToDeck();
            }
        });
    }

    private handleNextCard(nextCard: ReviewNextCardResponse): void {
        this.queue = nextCard.queue;

        if (this.initialTotal === 0) {
            this.initialTotal = this.queue.newCount + this.queue.dueCount;
        }

        if (nextCard.userCardId === null) {
            this.sessionComplete = true;
            setTimeout(() => this.backToDeck(), 2000);
            return;
        }

        this.currentCard = nextCard;
        this.cardShownTime = performance.now();
    }

    get progressPercent(): number {
        if (this.initialTotal === 0) {
            return 100;
        }
        const currentTotal = this.queue.newCount + this.queue.dueCount;
        return Math.max(0, Math.min(100, (1 - currentTotal / this.initialTotal) * 100));
    }

    formatInterval(rating: string): string {
        if (!this.currentCard?.intervals) {
            return '';
        }
        const interval = this.currentCard.intervals[rating];
        return interval ? ` · ${interval}` : '';
    }

    answer(rating: 'AGAIN' | 'HARD' | 'GOOD' | 'EASY'): void {
        if (!this.currentCard || !this.currentCard.userCardId) {
            return;
        }

        const responseMs = Math.round(performance.now() - this.cardShownTime);

        this.reviewApi.answerCard(this.userDeckId, this.currentCard.userCardId, {
            rating,
            responseMs,
            source: 'web'
        }).subscribe({
            next: response => {
                this.handleNextCard(response.next);
            },
            error: () => {
                this.backToDeck();
            }
        });
    }

    backToDeck(): void {
        void this.router.navigate(['/decks', this.userDeckId]);
    }
}
