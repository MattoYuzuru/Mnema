import { Component, OnInit, OnDestroy, HostListener } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { NgFor, NgIf } from '@angular/common';
import { forkJoin } from 'rxjs';
import { DeckApiService } from '../../core/services/deck-api.service';
import { PublicDeckApiService } from '../../core/services/public-deck-api.service';
import { TemplateApiService } from '../../core/services/template-api.service';
import { ReviewApiService } from '../../core/services/review-api.service';
import { PreferencesService } from '../../core/services/preferences.service';
import { I18nService } from '../../core/services/i18n.service';
import { UserDeckDTO } from '../../core/models/user-deck.models';
import { CardTemplateDTO } from '../../core/models/template.models';
import { ReviewClientFeatures, ReviewNextCardResponse, ReviewQueueDTO } from '../../core/models/review.models';
import { ButtonComponent } from '../../shared/components/button.component';
import { FlashcardViewComponent } from '../../shared/components/flashcard-view.component';
import { TranslatePipe } from '../../shared/pipes/translate.pipe';

type ReviewRating = 'AGAIN' | 'HARD' | 'GOOD' | 'EASY';

interface ReviewAnswerOption {
    rating: ReviewRating;
    labelKey: string;
    variant: 'ghost' | 'secondary' | 'primary';
}

@Component({
    selector: 'app-review-session',
    standalone: true,
    imports: [NgIf, NgFor, ButtonComponent, FlashcardViewComponent, TranslatePipe],
    template: `
    <div class="review-session" [class.mobile-swipe-enabled]="isMobileSwipeColumnMode()">
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
              <span class="count-new">
                {{ 'review.new' | translate }}: {{ queue.newCount }}
                <ng-container *ngIf="queue.newTotalCount !== undefined">
                  / {{ queue.newTotalCount }} {{ 'review.total' | translate }}
                </ng-container>
              </span>
              <span class="count-separator">·</span>
              <span class="count-due">
                {{ 'review.due' | translate }}: {{ queue.dueCount }}
                <ng-container *ngIf="queue.dueTodayCount !== undefined">
                  / {{ queue.dueTodayCount }} {{ 'review.today' | translate }}
                </ng-container>
              </span>
              <ng-container *ngIf="queue.learningAheadCount && queue.learningAheadCount > 0">
                <span class="count-separator">·</span>
                <span class="count-ahead">{{ 'review.ahead' | translate }}: {{ queue.learningAheadCount }}</span>
              </ng-container>
            </p>
          </div>
        </header>

        <div class="card-container">
          <div class="flashcard">
            <app-flashcard-view
              *ngIf="template && currentCard && !revealed"
              [template]="template"
              [content]="currentCard.effectiveContent"
              [side]="'front'"
              [hideLabels]="preferences.hideFieldLabels"
            ></app-flashcard-view>
            <div *ngIf="revealed && template && currentCard" class="revealed-content">
              <app-flashcard-view
                *ngIf="preferences.showFrontSideAfterFlip"
                [template]="template"
                [content]="currentCard.effectiveContent"
                [side]="'front'"
                [hideLabels]="preferences.hideFieldLabels"
              ></app-flashcard-view>
              <div *ngIf="preferences.showFrontSideAfterFlip" class="divider"></div>
              <app-flashcard-view
                [template]="template"
                [content]="currentCard.effectiveContent"
                [side]="'back'"
                [hideLabels]="preferences.hideFieldLabels"
              ></app-flashcard-view>
            </div>
          </div>
        </div>

        <div *ngIf="!revealed" class="show-answer-container">
          <app-button variant="primary" size="lg" (click)="revealAnswer()">
            {{ 'review.showAnswer' | translate }}
          </app-button>
          <p class="keyboard-hint">{{ 'review.spaceToReveal' | translate }}</p>
        </div>

        <div *ngIf="revealed" class="answer-actions" [class.mobile-column-mode]="isMobileSwipeColumnMode()">
          <div
            class="answer-buttons"
            [class.answer-buttons-mobile-column]="isMobileSwipeColumnMode()"
            [class.answer-buttons-mobile-left]="isMobileSwipeColumnMode() && preferences.mobileReviewButtonsSide === 'left'"
            [class.answer-buttons-mobile-right]="isMobileSwipeColumnMode() && preferences.mobileReviewButtonsSide === 'right'"
            (pointerdown)="onAnswerRailPointerDown($event)"
            (pointerup)="onAnswerRailPointerUp($event)"
            (pointercancel)="onAnswerRailPointerCancel()"
          >
            <p *ngIf="isMobileSwipeColumnMode()" class="swipe-hint">{{ 'review.mobileSwipeHint' | translate }}</p>

            <app-button
              *ngFor="let option of answerOptions"
              [variant]="option.variant"
              [fullWidth]="isMobileSwipeColumnMode()"
              (click)="answer(option.rating)"
            >
              {{ option.labelKey | translate }}{{ formatInterval(option.rating) }}
            </app-button>
          </div>
        </div>

        <div *ngIf="revealed" class="keyboard-hint">
          <p>{{ (isHlr() ? 'review.keyboardShortcutsBinary' : 'review.keyboardShortcuts') | translate }}</p>
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

      .review-session.mobile-swipe-enabled {
        padding-bottom: calc(12.5rem + env(safe-area-inset-bottom, 0px));
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
        flex-wrap: wrap;
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

      .count-ahead {
        color: var(--color-text-tertiary);
        font-weight: 500;
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

      .answer-actions {
        display: flex;
        justify-content: center;
      }

      .answer-buttons {
        display: flex;
        justify-content: center;
        gap: var(--spacing-md);
        flex-wrap: wrap;
      }

      .answer-buttons-mobile-column {
        position: fixed;
        bottom: calc(var(--spacing-lg) + env(safe-area-inset-bottom, 0px));
        z-index: 80;
        width: min(11rem, 46vw);
        display: flex;
        flex-direction: column;
        gap: var(--spacing-sm);
        padding: var(--spacing-sm);
        border-radius: calc(var(--border-radius-lg) + 0.1rem);
        border: 1px solid var(--glass-border);
        background: var(--glass-surface-strong);
        box-shadow: var(--shadow-md);
        backdrop-filter: blur(calc(var(--glass-blur) + 4px)) saturate(160%);
        touch-action: pan-y;
        transition: left 0.22s ease, right 0.22s ease, transform 0.22s ease;
      }

      .answer-buttons-mobile-left {
        left: max(var(--spacing-sm), env(safe-area-inset-left, 0px));
      }

      .answer-buttons-mobile-right {
        right: max(var(--spacing-sm), env(safe-area-inset-right, 0px));
      }

      .swipe-hint {
        margin: 0;
        text-align: center;
        font-size: 0.74rem;
        line-height: 1.25;
        color: var(--color-text-muted);
      }

      .show-answer-container {
        display: flex;
        flex-direction: column;
        align-items: center;
        gap: var(--spacing-md);
      }

      .revealed-content {
        width: 100%;
        display: flex;
        flex-direction: column;
        gap: var(--spacing-lg);
      }

      .divider {
        height: 1px;
        background: var(--border-color);
        margin: var(--spacing-md) 0;
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

      @media (max-width: 768px) {
        .review-session {
          padding-top: var(--spacing-md);
        }

        .review-header h2 {
          font-size: 1.25rem;
        }

        .card-container {
          min-height: 16rem;
        }

        .flashcard {
          min-height: 16rem;
          padding: var(--spacing-lg);
        }

        .answer-buttons {
          gap: var(--spacing-sm);
        }

        .show-answer-container {
          gap: var(--spacing-sm);
        }
      }

      @media (max-width: 480px) {
        .card-container {
          min-height: 14rem;
        }

        .flashcard {
          min-height: 14rem;
          padding: var(--spacing-md);
        }

        .answer-buttons:not(.answer-buttons-mobile-column) {
          flex-direction: column;
          width: 100%;
        }
      }

      @media (prefers-reduced-motion: reduce) {
        .progress,
        .answer-buttons-mobile-column {
          transition: none;
        }
      }
    `]
})
export class ReviewSessionComponent implements OnInit, OnDestroy {
    deck: UserDeckDTO | null = null;
    template: CardTemplateDTO | null = null;
    currentCard: ReviewNextCardResponse | null = null;
    queue: ReviewQueueDTO = { newCount: 0, dueCount: 0 };
    initialTotalRemaining = 0;
    sessionComplete = false;
    revealed = false;
    cardShownTime = 0;
    isMobileViewport = false;
    private userDeckId = '';
    private swipeStartX: number | null = null;
    private swipeStartY: number | null = null;
    private suppressAnswerClickUntil = 0;

    private readonly quadAnswerOptions: ReadonlyArray<ReviewAnswerOption> = [
        { rating: 'AGAIN', labelKey: 'review.again', variant: 'ghost' },
        { rating: 'HARD', labelKey: 'review.hard', variant: 'secondary' },
        { rating: 'GOOD', labelKey: 'review.good', variant: 'primary' },
        { rating: 'EASY', labelKey: 'review.easy', variant: 'primary' }
    ];

    private readonly binaryAnswerOptions: ReadonlyArray<ReviewAnswerOption> = [
        { rating: 'AGAIN', labelKey: 'review.again', variant: 'ghost' },
        { rating: 'GOOD', labelKey: 'review.good', variant: 'primary' }
    ];

    constructor(
        private route: ActivatedRoute,
        private router: Router,
        private deckApi: DeckApiService,
        private publicDeckApi: PublicDeckApiService,
        private templateApi: TemplateApiService,
        private reviewApi: ReviewApiService,
        public preferences: PreferencesService,
        private i18n: I18nService
    ) {}

    ngOnInit(): void {
        this.updateMobileViewport();
        this.userDeckId = this.route.snapshot.paramMap.get('userDeckId') || '';
        if (this.userDeckId) {
            this.loadDeckAndStart();
        }
    }

    ngOnDestroy(): void {
    }

    get answerOptions(): ReadonlyArray<ReviewAnswerOption> {
        return this.isHlr() ? this.binaryAnswerOptions : this.quadAnswerOptions;
    }

    @HostListener('window:resize')
    onViewportResize(): void {
        this.updateMobileViewport();
    }

    @HostListener('window:keydown', ['$event'])
    handleKeyDown(event: KeyboardEvent): void {
        const target = event.target as HTMLElement;
        const isInputField = target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.tagName === 'SELECT' || target.isContentEditable;

        if (isInputField || this.sessionComplete || !this.currentCard) {
            return;
        }

        if (event.key === ' ' || event.key === 'Space') {
            event.preventDefault();
            if (!this.revealed) {
                this.revealAnswer();
            }
            return;
        }

        if (!this.revealed) {
            return;
        }

        if (event.key === '1') {
            event.preventDefault();
            this.answer('AGAIN');
        } else if (event.key === '2') {
            event.preventDefault();
            this.answer(this.isHlr() ? 'GOOD' : 'HARD');
        } else if (!this.isHlr() && event.key === '3') {
            event.preventDefault();
            this.answer('GOOD');
        } else if (!this.isHlr() && event.key === '4') {
            event.preventDefault();
            this.answer('EASY');
        }
    }

    revealAnswer(): void {
        this.revealed = true;
    }

    isMobileSwipeColumnMode(): boolean {
        return this.isMobileViewport && this.preferences.mobileReviewButtonsMode === 'swipe-column';
    }

    onAnswerRailPointerDown(event: PointerEvent): void {
        if (!this.isMobileSwipeColumnMode() || event.pointerType === 'mouse') {
            return;
        }
        this.swipeStartX = event.clientX;
        this.swipeStartY = event.clientY;
    }

    onAnswerRailPointerUp(event: PointerEvent): void {
        if (!this.isMobileSwipeColumnMode() || this.swipeStartX === null || this.swipeStartY === null || event.pointerType === 'mouse') {
            return;
        }

        const deltaX = event.clientX - this.swipeStartX;
        const deltaY = event.clientY - this.swipeStartY;
        this.resetSwipeState();

        if (Math.abs(deltaX) < 48 || Math.abs(deltaX) < Math.abs(deltaY) * 1.2) {
            return;
        }

        const nextSide: 'left' | 'right' = deltaX > 0 ? 'right' : 'left';
        if (nextSide !== this.preferences.mobileReviewButtonsSide) {
            this.preferences.setMobileReviewButtonsSide(nextSide);
            this.suppressAnswerClickUntil = performance.now() + 220;
        }
    }

    onAnswerRailPointerCancel(): void {
        this.resetSwipeState();
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
                            const templateVersion = this.deck?.templateVersion ?? publicDeck.templateVersion ?? null;
                            this.templateApi.getTemplate(publicDeck.templateId, templateVersion).subscribe({
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

        if (this.initialTotalRemaining === 0) {
            this.initialTotalRemaining = this.getRemainingToday(this.queue);
        }

        if (nextCard.userCardId === null) {
            this.sessionComplete = true;
            setTimeout(() => this.backToDeck(), 2000);
            return;
        }

        this.currentCard = nextCard;
        this.revealed = false;
        this.cardShownTime = performance.now();
        this.suppressAnswerClickUntil = 0;
        this.resetSwipeState();
    }

    get progressPercent(): number {
        if (this.initialTotalRemaining === 0) {
            return 100;
        }
        const currentRemaining = this.getRemainingToday(this.queue);
        return Math.max(0, Math.min(100, (1 - currentRemaining / this.initialTotalRemaining) * 100));
    }

    private getRemainingToday(queue: ReviewQueueDTO): number {
        if (queue.dueTodayCount !== undefined) {
            return queue.dueTodayCount + queue.newCount;
        }
        return queue.totalRemaining ?? (queue.newCount + queue.dueCount);
    }

    formatInterval(rating: ReviewRating): string {
        if (!this.currentCard?.intervals) {
            return '';
        }
        const interval = this.currentCard.intervals[rating];
        return interval?.display ? ` · ${this.localizeIntervalDisplay(interval.display)}` : '';
    }

    answer(rating: ReviewRating): void {
        if (performance.now() < this.suppressAnswerClickUntil) {
            return;
        }

        if (!this.currentCard || !this.currentCard.userCardId) {
            return;
        }

        const responseMs = Math.round(performance.now() - this.cardShownTime);

        this.reviewApi.answerCard(this.userDeckId, this.currentCard.userCardId, {
            rating,
            responseMs,
            source: 'web',
            features: this.buildClientFeatures()
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

    isHlr(): boolean {
        return this.deck?.algorithmId === 'hlr';
    }

    private buildClientFeatures(): ReviewClientFeatures | null {
        return {
            meta: {
                uiMode: this.isHlr() ? 'binary' : 'quad'
            }
        };
    }

    private updateMobileViewport(): void {
        this.isMobileViewport = window.matchMedia('(max-width: 900px)').matches;
    }

    private resetSwipeState(): void {
        this.swipeStartX = null;
        this.swipeStartY = null;
    }

    private localizeIntervalDisplay(display: string): string {
        const normalized = display.trim();
        const localized = normalized.replace(/(\d+)\s*(mo|[smhdwy])/gi, (_, amount: string, unit: string) => {
            const lowerUnit = unit.toLowerCase();
            const translatedUnit = this.i18n.translate(`review.intervalUnit.${lowerUnit}`);
            return `${amount} ${translatedUnit}`;
        });
        return localized;
    }
}
