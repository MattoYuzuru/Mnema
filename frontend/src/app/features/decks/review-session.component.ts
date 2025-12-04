import { Component, OnInit } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { NgIf } from '@angular/common';
import { DeckApiService } from '../../core/services/deck-api.service';
import { UserDeckDTO } from '../../core/models/user-deck.models';
import { ButtonComponent } from '../../shared/components/button.component';

@Component({
    selector: 'app-review-session',
    standalone: true,
    imports: [NgIf, ButtonComponent],
    template: `
    <div class="review-session">
      <header class="review-header">
        <h2 *ngIf="deck">{{ deck.displayName }}</h2>
        <div class="progress-bar">
          <div class="progress" [style.width.%]="progress"></div>
        </div>
        <p class="progress-text">{{ currentCard }} / {{ totalCards }} cards</p>
      </header>

      <div class="card-container">
        <div class="flashcard">
          <div class="flashcard-content">
            <p class="card-text">Sample card front content</p>
            <p class="card-hint">Click to reveal answer</p>
          </div>
        </div>
      </div>

      <div class="answer-buttons">
        <app-button variant="ghost" (click)="answerCard('again')">
          Again
        </app-button>
        <app-button variant="secondary" (click)="answerCard('hard')">
          Hard
        </app-button>
        <app-button variant="primary" (click)="answerCard('good')">
          Good
        </app-button>
        <app-button variant="primary" (click)="answerCard('easy')">
          Easy
        </app-button>
      </div>

      <div class="review-actions">
        <app-button variant="ghost" size="sm" (click)="exitReview()">
          Exit Review
        </app-button>
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

      .review-header {
        text-align: center;
      }

      .review-header h2 {
        font-size: 1.5rem;
        margin: 0 0 var(--spacing-md) 0;
      }

      .progress-bar {
        height: 0.5rem;
        background: var(--border-color);
        border-radius: var(--border-radius-full);
        overflow: hidden;
        margin-bottom: var(--spacing-sm);
      }

      .progress {
        height: 100%;
        background: var(--color-primary-accent);
        transition: width 0.3s ease;
      }

      .progress-text {
        font-size: 0.9rem;
        color: var(--color-text-muted);
        margin: 0;
      }

      .card-container {
        min-height: 20rem;
        display: flex;
        align-items: center;
        justify-content: center;
      }

      .flashcard {
        width: 100%;
        max-width: 32rem;
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

      .flashcard-content {
        text-align: center;
      }

      .card-text {
        font-size: 1.5rem;
        margin: 0 0 var(--spacing-md) 0;
      }

      .card-hint {
        font-size: 0.9rem;
        color: var(--color-text-muted);
        margin: 0;
      }

      .answer-buttons {
        display: flex;
        justify-content: center;
        gap: var(--spacing-md);
      }

      .review-actions {
        display: flex;
        justify-content: center;
      }
    `]
})
export class ReviewSessionComponent implements OnInit {
    deck: UserDeckDTO | null = null;
    currentCard = 1;
    totalCards = 10;

    constructor(
        private route: ActivatedRoute,
        private router: Router,
        private deckApi: DeckApiService
    ) {}

    ngOnInit(): void {
        const userDeckId = this.route.snapshot.paramMap.get('userDeckId') || '';
        if (userDeckId) {
            this.loadDeck(userDeckId);
        }
    }

    private loadDeck(userDeckId: string): void {
        this.deckApi.getUserDeck(userDeckId).subscribe({
            next: deck => {
                this.deck = deck;
            },
            error: err => {
                console.error('Failed to load deck:', err);
            }
        });
    }

    get progress(): number {
        return (this.currentCard / this.totalCards) * 100;
    }

    answerCard(quality: string): void {
        console.log('Answered:', quality);
        this.currentCard++;
        if (this.currentCard > this.totalCards) {
            alert('Review session complete!');
            this.exitReview();
        }
    }

    exitReview(): void {
        const userDeckId = this.route.snapshot.paramMap.get('userDeckId') || '';
        void this.router.navigate(['/decks', userDeckId]);
    }
}
