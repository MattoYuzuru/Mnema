import { Component, Output, EventEmitter, OnInit } from '@angular/core';
import { NgIf, NgFor } from '@angular/common';
import { DeckWizardStateService, DeckWizardState } from '../deck-wizard-state.service';
import { CardApiService } from '../../../core/services/card-api.service';
import { UserCardDTO } from '../../../core/models/user-card.models';
import { ButtonComponent } from '../../../shared/components/button.component';
import { TranslatePipe } from '../../../shared/pipes/translate.pipe';
import { I18nService } from '../../../core/services/i18n.service';

@Component({
    selector: 'app-review-step',
    standalone: true,
    imports: [NgIf, NgFor, ButtonComponent, TranslatePipe],
    template: `
    <div class="step">
      <h2>{{ 'wizard.reviewDeck' | translate }}</h2>
      <p class="subtitle">{{ 'wizard.reviewSubtitle' | translate }}</p>
      <div class="review-section">
        <h3>{{ 'wizard.deckInformation' | translate }}</h3>
        <div class="info-grid">
          <div><span class="label">{{ 'wizard.labelName' | translate }}:</span> {{ state.deckMetadata.name }}</div>
          <div><span class="label">{{ 'wizard.labelDescription' | translate }}:</span> {{ state.deckMetadata.description || ('wizard.noDescription' | translate) }}</div>
          <div><span class="label">{{ 'wizard.labelLanguage' | translate }}:</span> {{ state.deckMetadata.language }}</div>
          <div>
            <span class="label">{{ 'wizard.labelVisibility' | translate }}:</span>
            {{ state.deckMetadata.isPublic ? ('wizard.visibilityPublic' | translate) : ('wizard.visibilityPrivate' | translate) }}{{ state.deckMetadata.isListed ? ' (' + ('wizard.visibilityListed' | translate) + ')' : '' }}
          </div>
          <div *ngIf="state.deckMetadata.tags.length > 0"><span class="label">{{ 'wizard.labelTags' | translate }}:</span> {{ state.deckMetadata.tags.join(', ') }}</div>
        </div>
      </div>
      <div *ngIf="loading">{{ 'wizard.loadingCards' | translate }}</div>
      <div *ngIf="!loading && cards.length > 0" class="review-section">
        <h3>{{ 'wizard.cards' | translate }} ({{ cards.length }})</h3>
        <div *ngFor="let card of cards.slice(0, 3)" class="card-preview">{{ getCardPreview(card) }}</div>
        <div *ngIf="cards.length > 3" class="more-cards">+{{ cards.length - 3 }} {{ 'wizard.moreCards' | translate }}</div>
      </div>
      <div *ngIf="!loading && cards.length === 0" class="review-section">
        <h3>{{ 'wizard.cards' | translate }}</h3>
        <p>{{ 'wizard.noCardsAdded' | translate }}</p>
      </div>
      <div class="step-actions">
        <app-button variant="ghost" (click)="onBack()">{{ 'wizard.back' | translate }}</app-button>
        <div class="action-buttons">
          <app-button variant="secondary" (click)="onFinish('home')">{{ 'wizard.goToHome' | translate }}</app-button>
          <app-button variant="primary" (click)="onFinish('deck')">{{ 'wizard.goToDeck' | translate }}</app-button>
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

      @media (max-width: 768px) {
        .step-actions {
          flex-direction: column;
          gap: var(--spacing-sm);
        }

        .action-buttons {
          flex-direction: column;
        }
      }
    `]
})
export class ReviewStepComponent implements OnInit {
    @Output() finish = new EventEmitter<'deck' | 'home'>();
    @Output() back = new EventEmitter<void>();
    state: DeckWizardState;
    loading = true;
    cards: UserCardDTO[] = [];

    constructor(private wizardState: DeckWizardStateService, private cardApi: CardApiService, private i18n: I18nService) {
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
        return Object.values(card.effectiveContent).filter(v => v).slice(0, 2).join(' - ') || this.i18n.translate('wizard.emptyCard');
    }

    onBack(): void {
        this.back.emit();
    }

    onFinish(destination: 'deck' | 'home'): void {
        this.finish.emit(destination);
    }
}
