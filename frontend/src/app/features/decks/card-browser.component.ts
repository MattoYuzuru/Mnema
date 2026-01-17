import { Component, OnInit, HostListener } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { NgIf, NgFor } from '@angular/common';
import { ReactiveFormsModule, FormBuilder, FormGroup, Validators } from '@angular/forms';
import { forkJoin } from 'rxjs';
import { CardApiService } from '../../core/services/card-api.service';
import { DeckApiService } from '../../core/services/deck-api.service';
import { PublicDeckApiService } from '../../core/services/public-deck-api.service';
import { TemplateApiService } from '../../core/services/template-api.service';
import { PreferencesService } from '../../core/services/preferences.service';
import { UserCardDTO, CardContentValue } from '../../core/models/user-card.models';
import { FieldTemplateDTO } from '../../core/models/template.models';
import { UserDeckDTO } from '../../core/models/user-deck.models';
import { PublicDeckDTO } from '../../core/models/public-deck.models';
import { CardTemplateDTO } from '../../core/models/template.models';
import { UserApiService } from '../../user-api.service';
import { MemoryTipLoaderComponent } from '../../shared/components/memory-tip-loader.component';
import { EmptyStateComponent } from '../../shared/components/empty-state.component';
import { FlashcardViewComponent } from '../../shared/components/flashcard-view.component';
import { ButtonComponent } from '../../shared/components/button.component';
import { InputComponent } from '../../shared/components/input.component';
import { TextareaComponent } from '../../shared/components/textarea.component';
import { MediaUploadComponent } from '../../shared/components/media-upload.component';
import { ConfirmationDialogComponent } from '../../shared/components/confirmation-dialog.component';
import { TranslatePipe } from '../../shared/pipes/translate.pipe';

@Component({
    selector: 'app-card-browser',
    standalone: true,
    imports: [NgIf, NgFor, ReactiveFormsModule, MemoryTipLoaderComponent, EmptyStateComponent, FlashcardViewComponent, ButtonComponent, InputComponent, TextareaComponent, MediaUploadComponent, ConfirmationDialogComponent, TranslatePipe],
    template: `
    <app-memory-tip-loader *ngIf="loading"></app-memory-tip-loader>

    <div *ngIf="!loading" class="card-browser">
      <header class="page-header">
        <div class="header-left">
          <app-button variant="ghost" size="sm" (click)="backToDeck()">
            {{ 'cardBrowser.backToDeck' | translate }}
          </app-button>
          <div>
            <h1>{{ 'cardBrowser.title' | translate }}</h1>
            <p class="card-count">{{ cardCount }} {{ 'cardBrowser.cards' | translate }}</p>
          </div>
        </div>
        <div class="header-right">
          <div class="view-mode-toggle">
            <app-button
              [variant]="viewMode === 'list' ? 'primary' : 'ghost'"
              size="sm"
              (click)="setViewMode('list')"
            >
              {{ 'cardBrowser.list' | translate }}
            </app-button>
            <app-button
              [variant]="viewMode === 'cards' ? 'primary' : 'ghost'"
              size="sm"
              (click)="setViewMode('cards')"
            >
              {{ 'cardBrowser.cardsView' | translate }}
            </app-button>
          </div>
        </div>
      </header>

      <div *ngIf="viewMode === 'list' && cards.length > 0" class="cards-table">
        <div class="card-row header-row">
          <div class="card-col">{{ 'cardBrowser.frontPreview' | translate }}</div>
          <div class="card-col-actions">{{ 'cardBrowser.actions' | translate }}</div>
        </div>
        <div *ngFor="let card of cards" class="card-row">
          <div class="card-col">{{ getFrontPreview(card) }}</div>
          <div class="card-col-actions">
            <button class="icon-btn" (click)="openEditModal(card)" [title]="'cardBrowser.editCard' | translate">
              <svg width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24">
                <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/>
                <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/>
              </svg>
            </button>
            <button class="icon-btn delete" (click)="openDeleteModal(card)" [title]="'cardBrowser.deleteCard' | translate">
              <svg width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24">
                <path d="M3 6h18"/>
                <path d="M8 6V4h8v2"/>
                <path d="M6 6l1 14a2 2 0 0 0 2 2h6a2 2 0 0 0 2-2l1-14"/>
                <path d="M10 11v6"/>
                <path d="M14 11v6"/>
              </svg>
            </button>
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
            {{ 'cardBrowser.previous' | translate }}
          </app-button>
          <span class="card-counter">{{ currentCardIndex + 1 }} / {{ cardCount }}</span>
          <app-button
            variant="ghost"
            size="sm"
            (click)="nextCard()"
            [disabled]="currentCardIndex >= cards.length - 1"
          >
            {{ 'cardBrowser.next' | translate }}
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
                  [hideLabels]="preferences.hideFieldLabels"
                ></app-flashcard-view>
              </div>
              <div class="flashcard-face back">
                <app-flashcard-view
                  *ngIf="template && currentCard"
                  [template]="template"
                  [content]="currentCard.effectiveContent"
                  side="back"
                  [hideLabels]="preferences.hideFieldLabels"
                ></app-flashcard-view>
              </div>
            </div>
          </div>
          <div class="flip-hint">
            <p>{{ 'cardBrowser.clickToFlip' | translate }}</p>
            <p>{{ 'cardBrowser.keyboardHint' | translate }}</p>
          </div>
        </div>

        <div class="card-actions">
          <app-button variant="secondary" size="sm" (click)="openEditModal(currentCard!)" *ngIf="currentCard">
            <svg width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24">
              <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/>
              <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/>
            </svg>
            {{ 'cardBrowser.editCard' | translate }}
          </app-button>
          <app-button variant="ghost" size="sm" (click)="openDeleteModal(currentCard!)" *ngIf="currentCard">
            <svg width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24">
              <path d="M3 6h18"/>
              <path d="M8 6V4h8v2"/>
              <path d="M6 6l1 14a2 2 0 0 0 2 2h6a2 2 0 0 0 2-2l1-14"/>
              <path d="M10 11v6"/>
              <path d="M14 11v6"/>
            </svg>
            {{ 'cardBrowser.deleteCard' | translate }}
          </app-button>
        </div>

        <div *ngIf="currentCard?.personalNote" class="personal-note">
          <h3>{{ 'cardBrowser.personalNote' | translate }}</h3>
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

    <div *ngIf="showEditModal && editingCard && template" class="modal-overlay" (click)="closeEditModal()">
      <div class="modal-content" (click)="$event.stopPropagation()">
        <div class="modal-header">
          <h2>{{ 'cardBrowser.editCard' | translate }}</h2>
          <button class="close-btn" (click)="closeEditModal()">&times;</button>
        </div>
        <div class="modal-body">
          <form [formGroup]="editForm" class="edit-form">
            <div *ngFor="let field of template.fields" class="field-group">
              <app-media-upload
                *ngIf="isMediaField(field)"
                [label]="field.label + (field.isRequired ? ' *' : '')"
                [fieldType]="getMediaFieldType(field)"
                [value]="getMediaValue(field.name)"
                (valueChange)="onMediaChange(field.name, $event)"
              ></app-media-upload>
              <app-input
                *ngIf="field.fieldType === 'text'"
                [label]="field.label + (field.isRequired ? ' *' : '')"
                type="text"
                [formControlName]="field.name"
                [placeholder]="field.helpText || 'Enter ' + field.label"
              ></app-input>
              <app-textarea
                *ngIf="field.fieldType === 'rich_text' || field.fieldType === 'markdown'"
                [label]="field.label + (field.isRequired ? ' *' : '')"
                [formControlName]="field.name"
                [placeholder]="field.fieldType === 'markdown' ? 'Use **bold**, *italic*, inline code' : (field.helpText || 'Enter ' + field.label)"
                [rows]="4"
              ></app-textarea>
            </div>
            <app-textarea
              [label]="'cardBrowser.personalNote' | translate"
              formControlName="personalNote"
              [rows]="3"
              placeholder="Add a personal note (optional)"
            ></app-textarea>
          </form>
        </div>
        <div class="modal-footer">
          <app-button variant="ghost" (click)="closeEditModal()" [disabled]="saving">{{ 'cardBrowser.cancel' | translate }}</app-button>
          <app-button variant="primary" (click)="saveEdit()" [disabled]="editForm.invalid || saving">
            {{ saving ? ('cardBrowser.saving' | translate) : ('cardBrowser.save' | translate) }}
          </app-button>
        </div>
      </div>
    </div>

    <app-confirmation-dialog
      [open]="showDeleteConfirm"
      [title]="'cardBrowser.deleteCardTitle' | translate"
      [message]="'cardBrowser.deleteCardMessage' | translate"
      [confirmText]="'cardBrowser.confirmDelete' | translate"
      [cancelText]="'cardBrowser.cancel' | translate"
      (confirm)="confirmDelete('local')"
      (cancel)="closeDeleteConfirm()"
    ></app-confirmation-dialog>

    <div *ngIf="showScopePrompt" class="modal-overlay" (click)="closeScopePrompt()">
      <div class="modal-content scope-prompt" (click)="$event.stopPropagation()">
        <div class="modal-header">
          <h2>{{ 'cardBrowser.deleteScopeTitle' | translate }}</h2>
          <button class="close-btn" (click)="closeScopePrompt()">&times;</button>
        </div>
        <div class="modal-body">
          <p>{{ 'cardBrowser.deleteScopeMessage' | translate }}</p>
          <div class="scope-buttons">
            <app-button variant="secondary" (click)="confirmDelete('local')" [disabled]="deleting">
              {{ 'cardBrowser.deleteLocal' | translate }}
            </app-button>
            <app-button variant="primary" (click)="confirmDelete('global')" [disabled]="deleting">
              {{ 'cardBrowser.deleteGlobal' | translate }}
            </app-button>
          </div>
        </div>
      </div>
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

      .header-left {
        display: flex;
        align-items: center;
        gap: var(--spacing-md);
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
        grid-template-columns: 1fr 120px;
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
        display: grid;
        width: 100%;
        min-height: 400px;
        transition: transform 0.6s;
        transform-style: preserve-3d;
      }

      .flashcard.flipped .flashcard-inner {
        transform: rotateY(180deg);
      }

      .flashcard-face {
        grid-area: 1 / 1;
        position: relative;
        width: 100%;
        height: auto;
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

      .card-col-actions {
        display: flex;
        align-items: center;
        justify-content: center;
        gap: var(--spacing-xs);
      }

      .icon-btn {
        background: none;
        border: none;
        cursor: pointer;
        padding: var(--spacing-xs);
        color: var(--color-text-secondary);
        transition: color 0.2s ease;
        display: inline-flex;
        align-items: center;
        justify-content: center;
      }

      .icon-btn:hover {
        color: var(--color-primary-accent);
      }

      .icon-btn.delete {
        color: #b91c1c;
      }

      .icon-btn.delete:hover {
        color: #dc2626;
      }

      .card-actions {
        display: flex;
        justify-content: center;
        margin-top: var(--spacing-lg);
        gap: var(--spacing-sm);
      }

      .modal-overlay {
        position: fixed;
        top: 0;
        left: 0;
        right: 0;
        bottom: 0;
        background: rgba(0, 0, 0, 0.5);
        display: flex;
        align-items: center;
        justify-content: center;
        z-index: 1000;
      }

      .modal-content {
        background: var(--color-card-background);
        border-radius: var(--border-radius-lg);
        max-width: 800px;
        width: 90%;
        max-height: 90vh;
        display: flex;
        flex-direction: column;
        box-shadow: 0 20px 25px -5px rgba(0, 0, 0, 0.1);
      }

      .modal-header {
        display: flex;
        justify-content: space-between;
        align-items: center;
        padding: var(--spacing-lg);
        border-bottom: 1px solid var(--border-color);
        flex-shrink: 0;
      }

      .modal-header h2 {
        margin: 0;
        font-size: 1.5rem;
        font-weight: 600;
      }

      .close-btn {
        background: none;
        border: none;
        font-size: 2rem;
        cursor: pointer;
        color: var(--color-text-secondary);
        line-height: 1;
        padding: 0;
      }

      .modal-body {
        padding: var(--spacing-lg);
        overflow-y: auto;
        flex: 1;
      }

      .edit-form {
        display: flex;
        flex-direction: column;
        gap: var(--spacing-md);
      }

      .field-group {
        display: flex;
        flex-direction: column;
      }

      .checkbox-group {
        display: flex;
        align-items: center;
        gap: var(--spacing-sm);
      }

      .checkbox-group label {
        display: flex;
        align-items: center;
        gap: var(--spacing-sm);
        font-size: 0.9rem;
        cursor: pointer;
      }

      .checkbox-group input[type="checkbox"] {
        width: 18px;
        height: 18px;
        cursor: pointer;
      }

      .modal-footer {
        display: flex;
        justify-content: flex-end;
        gap: var(--spacing-md);
        padding: var(--spacing-lg);
        border-top: 1px solid var(--border-color);
        flex-shrink: 0;
      }

      .scope-buttons {
        display: flex;
        gap: var(--spacing-sm);
        justify-content: flex-end;
        flex-wrap: wrap;
        margin-top: var(--spacing-md);
      }

      @media (max-width: 768px) {
        .card-browser {
          padding: 0 var(--spacing-md);
        }

        .page-header {
          flex-direction: column;
          align-items: flex-start;
          gap: var(--spacing-md);
        }

        .page-header h1 {
          font-size: 1.5rem;
        }

        .header-actions {
          width: 100%;
          flex-direction: column;
        }

        .cards-table {
          overflow-x: auto;
          -webkit-overflow-scrolling: touch;
        }

        .card-row {
          min-width: 600px;
        }

        .flashcard {
          min-height: 300px;
        }

        .card-actions {
          flex-direction: column;
          width: 100%;
        }
      }

      @media (max-width: 480px) {
        .card-browser {
          padding: 0 var(--spacing-sm);
        }

        .page-header h1 {
          font-size: 1.25rem;
        }

        .flashcard {
          min-height: 250px;
        }
      }
    `]
})
export class CardBrowserComponent implements OnInit {
    private static readonly PAGE_SIZE = 50;
    private static readonly PREFETCH_THRESHOLD = 0.9;

    loading = true;
    cards: UserCardDTO[] = [];
    deck: UserDeckDTO | null = null;
    publicDeck: PublicDeckDTO | null = null;
    template: CardTemplateDTO | null = null;
    userDeckId = '';
    viewMode: 'list' | 'cards' = 'list';
    currentCardIndex = 0;
    isFlipped = false;
    showEditModal = false;
    editingCard: UserCardDTO | null = null;
    showDeleteConfirm = false;
    showScopePrompt = false;
    editForm!: FormGroup;
    saving = false;
    deleting = false;
    deleteTarget: UserCardDTO | null = null;
    currentUserId: string | null = null;
    isAuthor = false;
    totalCards = 0;
    private currentPage = 1;
    private hasMoreCards = true;
    private loadingMore = false;

    constructor(
        private route: ActivatedRoute,
        private router: Router,
        private cardApi: CardApiService,
        private deckApi: DeckApiService,
        private publicDeckApi: PublicDeckApiService,
        private templateApi: TemplateApiService,
        private userApi: UserApiService,
        public preferences: PreferencesService,
        private fb: FormBuilder
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

    @HostListener('window:scroll')
    handleScroll(): void {
        if (this.viewMode !== 'list') return;
        this.maybeLoadMoreOnScroll();
    }

    private loadDeckData(): void {
        this.loading = true;
        forkJoin({
            deck: this.deckApi.getUserDeck(this.userDeckId),
            user: this.userApi.getMe()
        }).subscribe({
            next: ({ deck, user }) => {
                this.deck = deck;
                this.currentUserId = user.id;
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
                this.publicDeck = publicDeck;
                this.isAuthor = publicDeck.authorId === this.currentUserId;
                forkJoin({
                    template: this.templateApi.getTemplate(publicDeck.templateId),
                    cards: this.cardApi.getUserCards(this.userDeckId, 1, CardBrowserComponent.PAGE_SIZE),
                    size: this.deckApi.getUserDeckSize(this.userDeckId)
                }).subscribe({
                    next: ({ template, cards, size }) => {
                        this.template = template;
                        this.cards = cards.content;
                        this.currentPage = cards.number + 1;
                        this.hasMoreCards = !cards.last;
                        this.totalCards = size.cardsQty;
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

    get cardCount(): number {
        return this.totalCards || this.cards.length;
    }

    setViewMode(mode: 'list' | 'cards'): void {
        this.viewMode = mode;
        this.currentCardIndex = 0;
        this.isFlipped = false;
        if (mode === 'cards') {
            this.maybePrefetchMoreCards();
        }
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
            this.maybePrefetchMoreCards();
        }
    }

    private maybePrefetchMoreCards(): void {
        if (!this.hasMoreCards || this.loadingMore || this.cards.length === 0) {
            return;
        }
        const thresholdIndex = Math.floor(this.cards.length * CardBrowserComponent.PREFETCH_THRESHOLD);
        if (this.currentCardIndex + 1 >= thresholdIndex) {
            this.loadMoreCards();
        }
    }

    private maybeLoadMoreOnScroll(): void {
        if (!this.hasMoreCards || this.loadingMore || this.cards.length === 0) {
            return;
        }
        const scrollPosition = window.scrollY + window.innerHeight;
        const threshold = document.documentElement.scrollHeight * CardBrowserComponent.PREFETCH_THRESHOLD;
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
        this.cardApi.getUserCards(this.userDeckId, nextPage, CardBrowserComponent.PAGE_SIZE).subscribe({
            next: page => {
                const existingIds = new Set(this.cards.map(card => card.userCardId));
                const newCards = page.content.filter(card => !existingIds.has(card.userCardId));
                this.cards = [...this.cards, ...newCards];
                this.currentPage = page.number + 1;
                this.hasMoreCards = !page.last;
                this.totalCards = Math.max(this.totalCards, page.totalElements);
            },
            error: err => {
                console.error('Failed to load more cards:', err);
                this.loadingMore = false;
            },
            complete: () => {
                this.loadingMore = false;
            }
        });
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

    getFrontPreview(card: UserCardDTO): string {
        const anki = (card.effectiveContent as any)?._anki;
        if (this.template?.layout?.renderMode === 'anki' || anki) {
            const html = typeof anki?.front === 'string' ? anki.front : '';
            const text = this.stripHtml(html);
            return text.length > 80 ? text.substring(0, 80) + '...' : text;
        }

        if (!this.template || !this.template.layout) {
            const firstValue = Object.values(card.effectiveContent)[0];
            return this.getPreviewText(firstValue);
        }

        const frontFieldNames = this.template.layout.front.slice(0, 2);
        const values = frontFieldNames
            .map(name => {
                const field = this.template?.fields?.find(f => f.name === name);
                return this.getPreviewText(card.effectiveContent[name], field?.fieldType);
            })
            .filter(v => v)
            .join(' - ');

        return values.length > 80 ? values.substring(0, 80) + '...' : values;
    }

    private stripHtml(value: string): string {
        return value.replace(/<[^>]+>/g, '').replace(/\s+/g, ' ').trim();
    }

    backToDeck(): void {
        void this.router.navigate(['/decks', this.userDeckId]);
    }

    openEditModal(card: UserCardDTO): void {
        this.editingCard = card;
        if (this.template) {
            const controls: { [key: string]: any } = {};
            this.template.fields?.forEach(field => {
                controls[field.name] = [card.effectiveContent[field.name] || '', field.isRequired ? Validators.required : []];
            });
            controls['personalNote'] = [card.personalNote || ''];
            this.editForm = this.fb.group(controls);
            this.showEditModal = true;
        }
    }

    closeEditModal(): void {
        this.showEditModal = false;
        this.editingCard = null;
    }

    isMediaField(field: FieldTemplateDTO): boolean {
        return field.fieldType === 'image' || field.fieldType === 'audio' || field.fieldType === 'video';
    }

    getMediaFieldType(field: FieldTemplateDTO): 'image' | 'audio' | 'video' {
        return field.fieldType as 'image' | 'audio' | 'video';
    }

    getMediaValue(fieldName: string): CardContentValue | null {
        const value = this.editForm.get(fieldName)?.value;
        if (!value) return null;
        return value;
    }

    onMediaChange(fieldName: string, value: CardContentValue | null): void {
        this.editForm.get(fieldName)?.setValue(value);
        this.editForm.get(fieldName)?.markAsTouched();
    }

    saveEdit(): void {
        if (this.editForm.invalid || !this.editingCard) return;

        this.saving = true;
        const formValue = this.editForm.value;
        const { personalNote, ...content } = formValue;

        const updates: Partial<UserCardDTO> = {
            effectiveContent: content,
            personalNote: personalNote || null
        };

        this.cardApi.patchUserCard(this.userDeckId, this.editingCard.userCardId, updates).subscribe({
            next: updatedCard => {
                const index = this.cards.findIndex(c => c.userCardId === updatedCard.userCardId);
                if (index !== -1) {
                    this.cards[index] = updatedCard;
                }
                this.saving = false;
                this.showEditModal = false;
                this.editingCard = null;
            },
            error: err => {
                console.error('Failed to update card:', err);
                this.saving = false;
            }
        });
    }

    openDeleteModal(card: UserCardDTO): void {
        if (this.deleting) return;
        this.deleteTarget = card;
        if (this.canDeleteGlobally(card)) {
            this.showScopePrompt = true;
        } else {
            this.showDeleteConfirm = true;
        }
    }

    closeDeleteConfirm(): void {
        this.showDeleteConfirm = false;
        this.deleteTarget = null;
    }

    closeScopePrompt(): void {
        this.showScopePrompt = false;
        this.deleteTarget = null;
    }

    confirmDelete(scope: 'local' | 'global'): void {
        if (!this.deleteTarget || this.deleting) return;

        this.deleting = true;
        this.showDeleteConfirm = false;
        this.showScopePrompt = false;

        const targetId = this.deleteTarget.userCardId;
        this.cardApi.deleteUserCard(this.userDeckId, targetId, scope).subscribe({
            next: () => {
                this.removeCardFromList(targetId);
                this.resetDeleteState();
            },
            error: err => {
                console.error('Failed to delete card:', err);
                this.resetDeleteState();
            }
        });
    }

    private resetDeleteState(): void {
        this.deleting = false;
        this.showDeleteConfirm = false;
        this.showScopePrompt = false;
        this.deleteTarget = null;
    }

    private removeCardFromList(cardId: string): void {
        const index = this.cards.findIndex(card => card.userCardId === cardId);
        if (index === -1) return;

        this.cards = this.cards.filter(card => card.userCardId !== cardId);
        if (this.totalCards > 0) {
            this.totalCards = Math.max(0, this.totalCards - 1);
        }

        if (this.currentCardIndex > index) {
            this.currentCardIndex -= 1;
        }
        if (this.currentCardIndex >= this.cards.length) {
            this.currentCardIndex = Math.max(0, this.cards.length - 1);
        }
        this.isFlipped = false;
    }

    private canDeleteGlobally(card: UserCardDTO): boolean {
        return this.isAuthor && !!this.publicDeck && !card.isCustom;
    }
}
