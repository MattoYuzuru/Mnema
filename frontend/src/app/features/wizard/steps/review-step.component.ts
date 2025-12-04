import { Component, Output, EventEmitter, OnInit } from '@angular/core';
import { NgIf, NgFor } from '@angular/common';
import { DeckWizardStateService, DeckWizardState } from '../deck-wizard-state.service';
import { CardApiService } from '../../../core/services/card-api.service';
import { UserCardDTO } from '../../../core/models/user-card.models';
import { ButtonComponent } from '../../../shared/components/button.component';

@Component({
    selector: 'app-review-step',
    standalone: true,
    imports: [NgIf, NgFor, ButtonComponent],
    template: `
    <div class="step">
      <h2>Review Your Deck</h2>
      <p class="subtitle">Review and finalize your new deck</p>
      <div class="review-section">
        <h3>Deck Information</h3>
        <div class="info-grid">
          <div><span class="label">Name:</span> {{ state.deckMetadata.name }}</div>
          <div><span class="label">Description:</span> {{ state.deckMetadata.description || 'No description' }}</div>
          <div><span class="label">Language:</span> {{ state.deckMetadata.language }}</div>
          <div><span class="label">Visibility:</span> {{ state.deckMetadata.isPublic ? 'Public' : 'Private' }}{{ state.deckMetadata.isListed ? ' (Listed)' : '' }}</div>
          <div *ngIf="state.deckMetadata.tags.length > 0"><span class="label">Tags:</span> {{ state.deckMetadata.tags.join(', ') }}</div>
        </div>
      </div>
      <div *ngIf="loading">Loading cards...</div>
      <div *ngIf="!loading && cards.length > 0" class="review-section">
        <h3>Cards ({{ cards.length }})</h3>
        <div *ngFor="let card of cards.slice(0, 3)" class="card-preview">{{ getCardPreview(card) }}</div>
        <div *ngIf="cards.length > 3" class="more-cards">+{{ cards.length - 3 }} more cards</div>
      </div>
      <div *ngIf="!loading && cards.length === 0" class="review-section">
        <h3>Cards</h3>
        <p>No cards added yet. You can add cards later.</p>
      </div>
      <div class="step-actions">
        <app-button variant="ghost" (click)="onBack()">Back</app-button>
        <div class="action-buttons">
          <app-button variant="secondary" (click)="onFinish('home')">Go to Home</app-button>
          <app-button variant="primary" (click)="onFinish('deck')">Go to Deck</app-button>
        </div>
      </div>
    </div>
  `,
    styles: [`
      .step { display: flex; flex-direction: column; gap: var(--spacing-lg); }
      .review-section { padding: var(--spacing-lg); background: var(--color-background); border: 1px solid var(--border-color); border-radius: var(--border-radius-md); }
      .review-section h3 { font-size: 1.1rem; font-weight: 600; margin: 0 0 var(--spacing-md) 0; }
      .info-grid { display: flex; flex-direction: column; gap: var(--spacing-sm); }
      .label { font-weight: 600; color: var(--color-text-secondary); }
      .card-preview { padding: var(--spacing-sm) var(--spacing-md); background: var(--color-card-background); border: 1px solid var(--border-color); border-radius: var(--border-radius-sm); margin-bottom: var(--spacing-xs); }
      .more-cards { text-align: center; padding: var(--spacing-sm); color: var(--color-text-muted); }
      .step-actions { display: flex; justify-content: space-between; padding-top: var(--spacing-lg); border-top: 1px solid var(--border-color); }
      .action-buttons { display: flex; gap: var(--spacing-sm); }
    `]
})
export class ReviewStepComponent implements OnInit {
    @Output() finish = new EventEmitter<'deck' | 'home'>();
    @Output() back = new EventEmitter<void>();
    state: DeckWizardState;
    loading = true;
    cards: UserCardDTO[] = [];

    constructor(private wizardState: DeckWizardStateService, private cardApi: CardApiService) {
        this.state = this.wizardState.getCurrentState();
    }

    ngOnInit(): void {
        const { createdDeck } = this.state;
        if (!createdDeck) {
            this.loading = false;
            return;
        }

        this.cardApi.getUserCards(createdDeck.userDeckId, 1, 10).subscribe({
            next: page => {
                this.cards = page.content;
                this.loading = false;
            },
            error: () => { this.loading = false; }
        });
    }

    getCardPreview(card: UserCardDTO): string {
        return Object.values(card.effectiveContent).filter(v => v).slice(0, 2).join(' - ') || 'Empty card';
    }

    onBack(): void {
        this.back.emit();
    }

    onFinish(destination: 'deck' | 'home'): void {
        this.finish.emit(destination);
    }
}
