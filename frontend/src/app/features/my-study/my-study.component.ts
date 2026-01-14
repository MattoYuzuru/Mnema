import { Component, OnInit } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { NgIf, NgFor } from '@angular/common';
import { forkJoin } from 'rxjs';
import { DeckApiService } from '../../core/services/deck-api.service';
import { TemplateApiService } from '../../core/services/template-api.service';
import { UserDeckDTO } from '../../core/models/user-deck.models';
import { CardTemplateDTO } from '../../core/models/template.models';
import { DeckCardComponent } from '../../shared/components/deck-card.component';
import { TemplateCardComponent } from '../../shared/components/template-card.component';
import { MemoryTipLoaderComponent } from '../../shared/components/memory-tip-loader.component';
import { EmptyStateComponent } from '../../shared/components/empty-state.component';
import { TranslatePipe } from '../../shared/pipes/translate.pipe';

@Component({
    selector: 'app-my-study',
    standalone: true,
    imports: [
        NgIf,
        NgFor,
        RouterLink,
        DeckCardComponent,
        TemplateCardComponent,
        MemoryTipLoaderComponent,
        EmptyStateComponent,
        TranslatePipe
    ],
    template: `
    <app-memory-tip-loader *ngIf="loading"></app-memory-tip-loader>

    <div *ngIf="!loading" class="my-study">
      <header class="page-header">
        <h1>{{ 'myStudy.title' | translate }}</h1>
      </header>

      <section class="summary-section">
        <div class="section-header">
          <h2>{{ 'myStudy.myDecks' | translate }}</h2>
          <a routerLink="/decks" class="section-link">{{ 'myStudy.viewAllDecks' | translate }} -></a>
        </div>

        <div *ngIf="decks.length > 0" class="decks-grid">
          <app-deck-card
            *ngFor="let deck of decks"
            [userDeck]="deck"
            [showLearn]="true"
            [showBrowse]="true"
            (open)="openDeck(deck.userDeckId)"
            (learn)="learnDeck(deck.userDeckId)"
            (browse)="browseDeck(deck.userDeckId)"
          ></app-deck-card>
        </div>

        <app-empty-state
          *ngIf="decks.length === 0"
          icon="D"
          [title]="'decks.noDecks' | translate"
          [description]="'decks.noDecksDescription' | translate"
          [actionText]="'home.browsePublicDecks' | translate"
          (action)="goToPublicDecks()"
        ></app-empty-state>
      </section>

      <section class="summary-section">
        <div class="section-header">
          <h2>{{ 'myStudy.myTemplates' | translate }}</h2>
          <a routerLink="/templates" class="section-link">{{ 'myStudy.viewAllTemplates' | translate }} -></a>
        </div>

        <div *ngIf="templates.length > 0" class="templates-grid">
          <app-template-card
            *ngFor="let template of templates"
            [template]="template"
            [showActions]="false"
            [showVisibility]="true"
            [publicLabel]="'templates.public' | translate"
            [privateLabel]="'templates.private' | translate"
            (click)="openTemplate(template.templateId)"
          ></app-template-card>
        </div>

        <app-empty-state
          *ngIf="templates.length === 0"
          icon="T"
          [title]="'templates.noTemplates' | translate"
          [description]="'templates.noTemplatesDescription' | translate"
        ></app-empty-state>
      </section>
    </div>
  `,
    styles: [`
      .my-study {
        max-width: 72rem;
        margin: 0 auto;
      }

      .page-header {
        margin-bottom: var(--spacing-xl);
      }

      .page-header h1 {
        font-size: 2rem;
        margin: 0;
      }

      .summary-section {
        display: flex;
        flex-direction: column;
        gap: var(--spacing-lg);
        margin-bottom: var(--spacing-2xl);
      }

      .section-header {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: var(--spacing-md);
      }

      .section-header h2 {
        font-size: 1.5rem;
        margin: 0;
      }

      .section-link {
        color: var(--color-text-secondary);
        text-decoration: none;
        font-weight: 500;
        font-size: 0.95rem;
      }

      .section-link:hover {
        color: var(--color-text-primary);
      }

      .decks-grid {
        display: grid;
        grid-template-columns: repeat(1, minmax(0, 1fr));
        gap: var(--spacing-md);
      }

      .templates-grid {
        display: grid;
        grid-template-columns: repeat(1, minmax(0, 1fr));
        gap: var(--spacing-md);
      }

      @media (min-width: 768px) {
        .decks-grid {
          grid-template-columns: repeat(2, minmax(0, 1fr));
        }

        .templates-grid {
          grid-template-columns: repeat(2, minmax(0, 1fr));
        }
      }

      @media (min-width: 1100px) {
        .decks-grid {
          grid-template-columns: repeat(3, minmax(0, 1fr));
        }

        .templates-grid {
          grid-template-columns: repeat(4, minmax(0, 1fr));
        }
      }

      @media (max-width: 768px) {
        .my-study {
          padding: 0 var(--spacing-md);
        }

        .page-header h1 {
          font-size: 1.5rem;
        }

        .section-header {
          flex-direction: column;
          align-items: flex-start;
        }
      }
    `]
})
export class MyStudyComponent implements OnInit {
    private static readonly DECKS_PREVIEW_LIMIT = 6;
    private static readonly TEMPLATES_PREVIEW_LIMIT = 4;

    loading = true;
    decks: UserDeckDTO[] = [];
    templates: CardTemplateDTO[] = [];

    constructor(
        private deckApi: DeckApiService,
        private templateApi: TemplateApiService,
        private router: Router
    ) {}

    ngOnInit(): void {
        forkJoin({
            decks: this.deckApi.getMyDecks(1, MyStudyComponent.DECKS_PREVIEW_LIMIT),
            templates: this.templateApi.getTemplates(1, MyStudyComponent.TEMPLATES_PREVIEW_LIMIT, 'mine')
        }).subscribe({
            next: ({ decks, templates }) => {
                this.decks = decks.content;
                this.templates = templates.content;
                this.loading = false;
            },
            error: () => {
                this.loading = false;
            }
        });
    }

    openDeck(userDeckId: string): void {
        void this.router.navigate(['/decks', userDeckId]);
    }

    learnDeck(userDeckId: string): void {
        void this.router.navigate(['/decks', userDeckId, 'review']);
    }

    browseDeck(userDeckId: string): void {
        void this.router.navigate(['/decks', userDeckId, 'browse']);
    }

    openTemplate(templateId: string): void {
        void this.router.navigate(['/templates', templateId]);
    }

    goToPublicDecks(): void {
        void this.router.navigate(['/public-decks']);
    }
}
