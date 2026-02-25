import { Component, Output, EventEmitter, OnInit } from '@angular/core';
import { NgIf, NgFor } from '@angular/common';
import { DeckWizardStateService, DeckWizardState } from '../deck-wizard-state.service';
import { CardApiService } from '../../../core/services/card-api.service';
import { UserCardDTO } from '../../../core/models/user-card.models';
import { ButtonComponent } from '../../../shared/components/button.component';
import { TranslatePipe } from '../../../shared/pipes/translate.pipe';
import { I18nService } from '../../../core/services/i18n.service';
import { markdownToHtml } from '../../../shared/utils/markdown.util';

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
          <div class="description-row">
            <span class="label">{{ 'wizard.labelDescription' | translate }}:</span>
            <div class="markdown-preview" [innerHTML]="formatDescription(state.deckMetadata.description)"></div>
          </div>
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
      .step { display: flex; flex-direction: column; gap: var(--spacing-lg); min-width: 0; }
      .review-section { padding: var(--spacing-lg); background: var(--color-background); border: 1px solid var(--border-color); border-radius: var(--border-radius-md); }
      .review-section h3 { font-size: 1.1rem; font-weight: 600; margin: 0 0 var(--spacing-md) 0; }
      .info-grid { display: flex; flex-direction: column; gap: var(--spacing-sm); }
      .label { font-weight: 600; color: var(--color-text-secondary); }
      .description-row { display: flex; flex-direction: column; gap: var(--spacing-xs); }
      .markdown-preview { color: var(--color-text-primary); line-height: 1.5; }
      .markdown-preview h1,
      .markdown-preview h2,
      .markdown-preview h3 { font-size: 1rem; margin: 0 0 var(--spacing-xs) 0; font-weight: 600; }
      .markdown-preview ul { margin: 0 0 var(--spacing-xs) 0; padding-left: 1.2rem; }
      .markdown-preview li { margin: 0; }
      .markdown-preview pre { margin: 0 0 var(--spacing-xs) 0; padding: var(--spacing-sm); background: var(--color-card-background); border-radius: var(--border-radius-sm); white-space: pre-wrap; }
      .markdown-preview code { font-family: inherit; font-size: 0.9rem; background: var(--color-card-background); padding: 0 0.2rem; border-radius: var(--border-radius-sm); }
      .card-preview { padding: var(--spacing-sm) var(--spacing-md); background: var(--color-card-background); border: 1px solid var(--border-color); border-radius: var(--border-radius-sm); margin-bottom: var(--spacing-xs); }
      .more-cards { text-align: center; padding: var(--spacing-sm); color: var(--color-text-muted); }
      .step-actions { display: flex; justify-content: space-between; flex-wrap: wrap; gap: var(--spacing-sm); padding-top: var(--spacing-lg); border-top: 1px solid var(--border-color); }
      .step-actions app-button { flex: 1 1 14rem; }
      .action-buttons { display: flex; gap: var(--spacing-sm); }
      .action-buttons app-button { flex: 1 1 12rem; }

      @media (max-width: 900px) {
        .step-actions {
          flex-direction: column;
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

    formatDescription(description?: string): string {
        const trimmed = (description || '').trim();
        if (!trimmed) {
            return this.i18n.translate('wizard.noDescription');
        }
        return markdownToHtml(trimmed);
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
