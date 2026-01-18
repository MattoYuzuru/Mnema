import { Component, OnInit, OnDestroy } from '@angular/core';
import { NgIf, NgFor } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import { forkJoin, of, Subscription, from } from 'rxjs';
import { catchError, distinctUntilChanged, filter, mergeMap, skip } from 'rxjs/operators';
import { AuthService, AuthStatus } from './auth.service';
import { UserApiService } from './user-api.service';
import { I18nService, Language } from './core/services/i18n.service';
import { PublicDeckApiService } from './core/services/public-deck-api.service';
import { DeckApiService } from './core/services/deck-api.service';
import { ReviewApiService } from './core/services/review-api.service';
import { MediaApiService } from './core/services/media-api.service';
import { PublicDeckDTO } from './core/models/public-deck.models';
import { UserDeckDTO } from './core/models/user-deck.models';
import { DeckCardComponent } from './shared/components/deck-card.component';
import { MemoryTipLoaderComponent } from './shared/components/memory-tip-loader.component';
import { ButtonComponent } from './shared/components/button.component';
import { EmptyStateComponent } from './shared/components/empty-state.component';
import { TranslatePipe } from './shared/pipes/translate.pipe';

@Component({
    standalone: true,
    selector: 'app-home-page',
    imports: [NgIf, NgFor, RouterLink, DeckCardComponent, MemoryTipLoaderComponent, ButtonComponent, EmptyStateComponent, TranslatePipe],
    template: `
    <app-memory-tip-loader *ngIf="loading"></app-memory-tip-loader>

    <div *ngIf="!loading" class="home-page">
      <section *ngIf="auth.status() !== 'authenticated'" class="hero">
        <div class="container hero-grid">
          <div class="hero-content">
            <span class="hero-badge">{{ 'home.heroBadge' | translate }}</span>
            <h1 class="hero-title">{{ 'home.heroTitle' | translate }}</h1>
            <p class="hero-subtitle">{{ 'home.heroSubtitle' | translate }}</p>

            <div class="hero-actions">
              <app-button variant="primary" size="lg" routerLink="/register">
                {{ 'home.heroPrimaryCta' | translate }}
              </app-button>
              <app-button variant="secondary" size="lg" routerLink="/public-decks">
                {{ 'home.heroSecondaryCta' | translate }}
              </app-button>
            </div>

            <div class="hero-search glass-strong">
              <label class="search-label" for="hero-search">
                {{ 'home.heroSearchLabel' | translate }}
              </label>
              <div class="search-row">
                <div class="search-input" [class.has-value]="heroSearchQuery">
                  <span class="search-icon">🔍</span>
                  <input
                    id="hero-search"
                    type="search"
                    [value]="heroSearchQuery"
                    autocomplete="off"
                    [attr.aria-label]="'home.heroSearchLabel' | translate"
                    (input)="onHeroSearchInput($event)"
                    (keydown.enter)="submitHeroSearch()"
                  />
                  <span *ngIf="!heroSearchQuery" class="typing-placeholder">{{ typedPlaceholder }}</span>
                </div>
                <app-button variant="primary" size="lg" (click)="submitHeroSearch()">
                  {{ 'home.heroSearchButton' | translate }}
                </app-button>
              </div>
              <div class="search-hints">
                <button
                  type="button"
                  class="chip"
                  *ngFor="let topic of popularTopics"
                  (click)="applyHeroTopic(topic)"
                >
                  {{ topic }}
                </button>
              </div>
            </div>

            <div class="hero-pillars">
              <div class="pillar" *ngFor="let pillar of heroPillars">
                <div class="pillar-dot"></div>
                <div>
                  <h3>{{ pillar.titleKey | translate }}</h3>
                  <p>{{ pillar.descriptionKey | translate }}</p>
                </div>
              </div>
            </div>
          </div>

          <div class="hero-visual">
            <div class="hero-card glass">
              <div class="hero-card-top">
                <span class="hero-chip">{{ 'home.heroCardToday' | translate }}</span>
                <span class="hero-time">6 {{ 'home.heroCardMinutes' | translate }}</span>
              </div>
              <h3>{{ 'home.heroCardTitle' | translate }}</h3>
              <p>{{ 'home.heroCardSubtitle' | translate }}</p>
              <div class="hero-progress">
                <span>{{ 'home.heroCardProgress' | translate }}</span>
                <div class="progress-track">
                  <span class="progress-fill"></span>
                </div>
              </div>
            </div>

            <div class="hero-card glass-strong">
              <div class="hero-card-top">
                <span class="hero-chip">{{ 'home.heroCardNext' | translate }}</span>
                <span class="hero-time">{{ 'home.heroCardEstimate' | translate }}</span>
              </div>
              <h3>{{ 'home.heroCardFocusTitle' | translate }}</h3>
              <p>{{ 'home.heroCardFocusSubtitle' | translate }}</p>
              <div class="hero-tags">
                <span class="hero-tag">C1</span>
                <span class="hero-tag">{{ 'home.heroCardTag1' | translate }}</span>
                <span class="hero-tag">{{ 'home.heroCardTag2' | translate }}</span>
              </div>
            </div>
          </div>
        </div>
      </section>

      <section *ngIf="auth.status() !== 'authenticated'" class="section how-it-works">
        <div class="container">
          <div class="section-heading">
            <h2>{{ 'home.howTitle' | translate }}</h2>
            <p>{{ 'home.howSubtitle' | translate }}</p>
          </div>
          <div class="how-grid">
            <div class="how-card glass" *ngFor="let step of howSteps; let i = index">
              <div class="how-step">{{ i + 1 }}</div>
              <h3>{{ step.titleKey | translate }}</h3>
              <p>{{ step.descriptionKey | translate }}</p>
            </div>
          </div>
        </div>
      </section>

      <section *ngIf="auth.status() !== 'authenticated'" class="section value-props">
        <div class="container">
          <div class="section-heading">
            <h2>{{ 'home.valueTitle' | translate }}</h2>
            <p>{{ 'home.valueSubtitle' | translate }}</p>
          </div>
          <div class="value-grid">
            <div class="value-card glass" *ngFor="let value of valueProps">
              <div class="value-icon">{{ value.icon }}</div>
              <div>
                <h3>{{ value.titleKey | translate }}</h3>
                <p>{{ value.descriptionKey | translate }}</p>
              </div>
            </div>
          </div>
        </div>
      </section>

      <section *ngIf="auth.status() !== 'authenticated'" class="section deck-search">
        <div class="container">
          <div class="deck-search-card glass-strong">
            <div class="deck-search-copy">
              <h2>{{ 'home.searchTitle' | translate }}</h2>
              <p>{{ 'home.searchSubtitle' | translate }}</p>
            </div>
            <div class="deck-search-form">
              <div class="search-input" [class.has-value]="catalogSearchQuery">
                <span class="search-icon">⌕</span>
                <input
                  type="search"
                  [value]="catalogSearchQuery"
                  autocomplete="off"
                  [attr.aria-label]="'home.searchTitle' | translate"
                  (input)="onCatalogSearchInput($event)"
                  (keydown.enter)="submitCatalogSearch()"
                />
                <span *ngIf="!catalogSearchQuery" class="typing-placeholder static">{{ 'home.searchPlaceholder' | translate }}</span>
              </div>
              <app-button variant="primary" size="lg" (click)="submitCatalogSearch()">
                {{ 'home.searchButton' | translate }}
              </app-button>
            </div>
            <div class="search-hints">
              <button
                type="button"
                class="chip"
                *ngFor="let topic of popularTopics"
                (click)="applyCatalogTopic(topic)"
              >
                {{ topic }}
              </button>
            </div>
          </div>
        </div>
      </section>

      <section *ngIf="auth.status() !== 'authenticated'" class="section starter-decks">
        <div class="container">
          <div class="section-heading">
            <h2>{{ 'home.starterTitle' | translate }}</h2>
            <p>{{ 'home.starterSubtitle' | translate }}</p>
          </div>
          <div class="starter-grid">
            <a
              *ngFor="let deck of starterDecks"
              class="starter-card glass"
              [attr.href]="deck.href"
            >
              <div class="starter-card-top">
                <h3>{{ deck.titleKey | translate }}</h3>
                <span class="starter-badge" [class.popular]="deck.badge === 'popular'">
                  {{ deck.badge === 'popular' ? ('home.starterBadgePopular' | translate) : ('home.starterBadgeNew' | translate) }}
                </span>
              </div>
              <p>{{ deck.descriptionKey | translate }}</p>
              <span class="starter-link">{{ 'home.starterAction' | translate }} →</span>
            </a>
          </div>
        </div>
      </section>

      <section *ngIf="auth.status() !== 'authenticated'" class="section community-decks">
        <div class="container">
          <div class="section-heading">
            <h2>{{ 'home.communityTitle' | translate }}</h2>
            <p>{{ 'home.communitySubtitle' | translate }}</p>
          </div>

          <div *ngIf="publicDecks.length > 0" class="deck-grid">
            <app-deck-card
              *ngFor="let deck of publicDecks"
              [publicDeck]="deck"
              [iconUrl]="getPublicDeckIconUrl(deck)"
              [showFork]="canForkDeck(deck)"
              [showBrowse]="true"
              (open)="openPublicDeck(deck.deckId)"
              (fork)="forkDeck(deck.deckId)"
              (browse)="browsePublicDeck(deck.deckId)"
            ></app-deck-card>
          </div>

          <app-empty-state
            *ngIf="publicDecks.length === 0"
            icon="🌐"
            [title]="'home.noPublicDecks' | translate"
            [description]="'home.noPublicDecksDescription' | translate"
          ></app-empty-state>

          <div class="section-cta">
            <app-button variant="primary" size="lg" routerLink="/public-decks">
              {{ 'home.communityCta' | translate }}
            </app-button>
          </div>
        </div>
      </section>

      <section *ngIf="auth.status() === 'authenticated'" class="study-today glass container">
        <h2>{{ 'home.studyToday' | translate }}</h2>
        <div class="study-summary">
          <div class="study-info">
            <p class="study-message">{{ todayStats.due }} {{ 'home.cardsDue' | translate }} · {{ todayStats.new }} {{ 'home.new' | translate }}</p>
            <app-button variant="primary" size="lg" routerLink="/my-study">
              {{ 'home.continueLearn' | translate }}
            </app-button>
          </div>
        </div>

        <div *ngIf="userDecks.length > 0" class="recent-decks">
          <h3>{{ 'home.yourDecks' | translate }}</h3>
          <div class="deck-grid">
            <app-deck-card
              *ngFor="let deck of userDecks"
              [userDeck]="deck"
              [iconUrl]="getUserDeckIconUrl(deck)"
              [showLearn]="true"
              [showBrowse]="true"
              [stats]="getDeckStats(deck)"
              (open)="openUserDeck(deck.userDeckId)"
              (learn)="learnDeck(deck.userDeckId)"
              (browse)="browseDeck(deck.userDeckId)"
            ></app-deck-card>
          </div>
        </div>

        <app-empty-state
          *ngIf="userDecks.length === 0"
          icon="📚"
          [title]="'home.noDecksYet' | translate"
          [description]="'home.noDecksDescription' | translate"
          [actionText]="'home.browsePublicDecks' | translate"
          (action)="goToPublicDecks()"
        ></app-empty-state>
      </section>

      <section *ngIf="auth.status() === 'authenticated'" class="public-decks container">
        <div class="section-header">
          <h2>{{ 'home.topPublicDecks' | translate }}</h2>
          <a routerLink="/public-decks" class="view-all">{{ 'home.viewAll' | translate }} →</a>
        </div>

        <div *ngIf="publicDecks.length > 0" class="deck-list">
          <app-deck-card
            *ngFor="let deck of publicDecks"
            [publicDeck]="deck"
            [iconUrl]="getPublicDeckIconUrl(deck)"
            [showFork]="canForkDeck(deck)"
            [showBrowse]="true"
            (open)="openPublicDeck(deck.deckId)"
            (fork)="forkDeck(deck.deckId)"
            (browse)="browsePublicDeck(deck.deckId)"
          ></app-deck-card>
        </div>

        <app-empty-state
          *ngIf="publicDecks.length === 0"
          icon="🌐"
          [title]="'home.noPublicDecks' | translate"
          [description]="'home.noPublicDecksDescription' | translate"
        ></app-empty-state>
      </section>
    </div>
  `,
    styles: [
        `
      .home-page {
        display: flex;
        flex-direction: column;
        gap: var(--spacing-2xl);
      }

      .hero {
        position: relative;
        padding: var(--spacing-2xl) 0 var(--spacing-xl);
      }

      .hero-grid {
        display: grid;
        grid-template-columns: minmax(0, 1.1fr) minmax(0, 0.9fr);
        gap: var(--spacing-2xl);
        align-items: center;
      }

      .hero-content {
        display: flex;
        flex-direction: column;
        gap: var(--spacing-md);
        animation: fade-up 0.7s ease both;
      }

      .hero-badge {
        align-self: flex-start;
        display: inline-flex;
        align-items: center;
        padding: 0.35rem 0.9rem;
        border-radius: var(--border-radius-full);
        background: var(--glass-surface);
        border: 1px solid var(--glass-border);
        color: var(--color-text-secondary);
        font-size: 0.85rem;
        letter-spacing: 0.04em;
        text-transform: uppercase;
      }

      .hero-title {
        font-size: clamp(2.6rem, 4.8vw, 4.6rem);
        line-height: 1.05;
        margin: 0;
      }

      .hero-subtitle {
        font-size: 1.15rem;
        color: var(--color-text-secondary);
        max-width: 38rem;
        margin: 0;
      }

      .hero-actions {
        display: flex;
        flex-wrap: wrap;
        gap: var(--spacing-md);
        margin-top: var(--spacing-sm);
      }

      .hero-search {
        margin-top: var(--spacing-lg);
        padding: var(--spacing-lg);
        border-radius: var(--border-radius-lg);
        display: flex;
        flex-direction: column;
        gap: var(--spacing-md);
      }

      .search-label {
        font-size: 0.9rem;
        color: var(--color-text-muted);
      }

      .search-row {
        display: grid;
        grid-template-columns: 1fr auto;
        gap: var(--spacing-md);
        align-items: center;
      }

      .search-input {
        position: relative;
        display: flex;
        align-items: center;
        padding: 0.85rem 1rem 0.85rem 2.6rem;
        border-radius: var(--border-radius-full);
        border: 1px solid var(--glass-border);
        background: var(--glass-surface);
        backdrop-filter: blur(var(--glass-blur));
        transition: border-color 0.2s ease, box-shadow 0.2s ease;
      }

      .search-input:focus-within {
        border-color: rgba(14, 165, 233, 0.6);
        box-shadow: var(--focus-ring);
      }

      .search-input input {
        width: 100%;
        border: none;
        background: transparent;
        padding: 0;
        color: var(--color-text-primary);
        font-size: 1rem;
        outline: none;
        box-shadow: none;
      }

      .search-icon {
        position: absolute;
        left: 1rem;
        font-size: 1rem;
        color: var(--color-text-muted);
      }

      .typing-placeholder {
        position: absolute;
        left: 2.6rem;
        color: var(--color-text-muted);
        pointer-events: none;
        transition: opacity 0.2s ease, transform 0.2s ease;
      }

      .typing-placeholder::after {
        content: "|";
        margin-left: 2px;
        animation: blink 1s steps(2, start) infinite;
      }

      .search-input:focus-within .typing-placeholder {
        opacity: 0;
        transform: translateY(-4px);
      }

      .typing-placeholder.static::after {
        content: "";
      }

      .search-hints {
        display: flex;
        flex-wrap: wrap;
        gap: var(--spacing-sm);
      }

      .chip {
        border: 1px solid var(--glass-border);
        background: var(--glass-surface);
        border-radius: var(--border-radius-full);
        padding: 0.35rem 0.85rem;
        font-size: 0.85rem;
        color: var(--color-text-secondary);
        cursor: pointer;
        transition: transform 0.2s ease, border-color 0.2s ease;
      }

      .chip:hover {
        border-color: var(--border-color-hover);
        transform: translateY(-1px);
      }

      .hero-pillars {
        display: grid;
        gap: var(--spacing-md);
        margin-top: var(--spacing-lg);
      }

      .pillar {
        display: flex;
        gap: var(--spacing-md);
        align-items: flex-start;
      }

      .pillar-dot {
        width: 12px;
        height: 12px;
        border-radius: 50%;
        margin-top: 0.45rem;
        background: linear-gradient(135deg, var(--color-primary-accent), var(--color-secondary-accent));
        box-shadow: 0 0 10px rgba(14, 165, 233, 0.35);
      }

      .pillar h3 {
        font-size: 1rem;
        margin: 0 0 0.15rem 0;
      }

      .pillar p {
        margin: 0;
        color: var(--color-text-muted);
        font-size: 0.95rem;
      }

      .hero-visual {
        display: grid;
        gap: var(--spacing-lg);
        animation: fade-up 0.9s ease both;
      }

      .hero-card {
        padding: var(--spacing-lg);
        border-radius: var(--border-radius-lg);
        display: flex;
        flex-direction: column;
        gap: var(--spacing-sm);
        animation: float 8s ease-in-out infinite;
      }

      .hero-card:nth-child(2) {
        margin-top: var(--spacing-md);
        animation-delay: 1.6s;
      }

      .hero-card-top {
        display: flex;
        justify-content: space-between;
        align-items: center;
        gap: var(--spacing-sm);
      }

      .hero-chip {
        display: inline-flex;
        padding: 0.25rem 0.7rem;
        border-radius: var(--border-radius-full);
        border: 1px solid var(--glass-border);
        background: rgba(255, 255, 255, 0.35);
        font-size: 0.75rem;
        text-transform: uppercase;
        letter-spacing: 0.08em;
        color: var(--color-text-secondary);
      }

      .hero-time {
        font-size: 0.85rem;
        color: var(--color-text-muted);
      }

      .hero-card h3 {
        font-size: 1.2rem;
        margin: 0;
      }

      .hero-card p {
        margin: 0;
        color: var(--color-text-muted);
      }

      .hero-progress {
        display: grid;
        gap: var(--spacing-xs);
        font-size: 0.85rem;
        color: var(--color-text-muted);
      }

      .progress-track {
        height: 6px;
        border-radius: var(--border-radius-full);
        background: rgba(255, 255, 255, 0.35);
        overflow: hidden;
      }

      .progress-fill {
        display: block;
        width: 72%;
        height: 100%;
        border-radius: inherit;
        background: linear-gradient(90deg, var(--color-primary-accent), var(--color-secondary-accent));
      }

      .hero-tags {
        display: flex;
        flex-wrap: wrap;
        gap: var(--spacing-xs);
      }

      .hero-tag {
        padding: 0.2rem 0.6rem;
        border-radius: var(--border-radius-full);
        border: 1px solid var(--glass-border);
        background: rgba(255, 255, 255, 0.35);
        font-size: 0.75rem;
        color: var(--color-text-secondary);
      }

      .section-heading {
        text-align: center;
        max-width: 46rem;
        margin: 0 auto var(--spacing-xl);
        display: flex;
        flex-direction: column;
        gap: var(--spacing-sm);
      }

      .section-heading p {
        color: var(--color-text-muted);
        margin: 0;
      }

      .how-grid {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
        gap: var(--spacing-lg);
      }

      .how-card {
        padding: var(--spacing-lg);
        border-radius: var(--border-radius-lg);
        display: flex;
        flex-direction: column;
        gap: var(--spacing-sm);
      }

      .how-step {
        width: 46px;
        height: 46px;
        border-radius: 50%;
        display: inline-flex;
        align-items: center;
        justify-content: center;
        font-weight: 700;
        color: var(--color-primary-accent);
        background: var(--glass-surface-strong);
        border: 1px solid var(--glass-border);
      }

      .how-card h3 {
        margin: 0;
        font-size: 1.1rem;
      }

      .how-card p {
        margin: 0;
        color: var(--color-text-muted);
      }

      .value-grid {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
        gap: var(--spacing-lg);
      }

      .value-card {
        display: grid;
        grid-template-columns: auto 1fr;
        gap: var(--spacing-md);
        padding: var(--spacing-lg);
        border-radius: var(--border-radius-lg);
      }

      .value-icon {
        width: 46px;
        height: 46px;
        display: inline-flex;
        align-items: center;
        justify-content: center;
        border-radius: 14px;
        border: 1px solid var(--glass-border);
        background: rgba(255, 255, 255, 0.45);
        font-size: 1.3rem;
      }

      .value-card h3 {
        margin: 0 0 0.3rem 0;
        font-size: 1.05rem;
      }

      .value-card p {
        margin: 0;
        color: var(--color-text-muted);
      }

      .deck-search-card {
        padding: var(--spacing-xl);
        border-radius: var(--border-radius-lg);
        display: grid;
        gap: var(--spacing-lg);
      }

      .deck-search-copy h2 {
        margin: 0 0 var(--spacing-sm) 0;
      }

      .deck-search-copy p {
        margin: 0;
        color: var(--color-text-muted);
      }

      .deck-search-form {
        display: grid;
        grid-template-columns: 1fr auto;
        gap: var(--spacing-md);
        align-items: center;
      }

      .starter-grid {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
        gap: var(--spacing-lg);
      }

      .starter-card {
        text-decoration: none;
        color: inherit;
        padding: var(--spacing-lg);
        border-radius: var(--border-radius-lg);
        display: flex;
        flex-direction: column;
        gap: var(--spacing-sm);
        transition: transform 0.25s ease, box-shadow 0.25s ease, border-color 0.25s ease;
      }

      .starter-card:hover {
        transform: translateY(-2px);
        border-color: var(--border-color-hover);
        box-shadow: var(--shadow-md);
      }

      .starter-card-top {
        display: flex;
        align-items: baseline;
        justify-content: space-between;
        gap: var(--spacing-sm);
      }

      .starter-card h3 {
        margin: 0;
        font-size: 1.05rem;
      }

      .starter-card p {
        margin: 0;
        color: var(--color-text-muted);
      }

      .starter-badge {
        padding: 0.2rem 0.6rem;
        border-radius: var(--border-radius-full);
        font-size: 0.7rem;
        text-transform: uppercase;
        letter-spacing: 0.08em;
        border: 1px solid rgba(14, 165, 233, 0.35);
        background: rgba(14, 165, 233, 0.12);
        color: var(--color-primary-accent);
      }

      .starter-badge.popular {
        border-color: rgba(20, 184, 166, 0.35);
        background: rgba(20, 184, 166, 0.15);
        color: var(--color-secondary-accent);
      }

      .starter-link {
        font-size: 0.9rem;
        color: var(--color-text-secondary);
        font-weight: 600;
      }

      .community-decks .deck-grid,
      .study-today .deck-grid {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
        gap: var(--spacing-lg);
      }

      .section-cta {
        display: flex;
        justify-content: center;
        margin-top: var(--spacing-xl);
      }

      .study-today {
        padding: var(--spacing-xl);
        border-radius: var(--border-radius-lg);
        display: flex;
        flex-direction: column;
        gap: var(--spacing-md);
      }

      .study-today h2 {
        font-size: 1.5rem;
        margin: 0;
      }

      .study-summary {
        margin-bottom: var(--spacing-sm);
      }

      .study-info {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: var(--spacing-md);
      }

      .study-message {
        font-size: 1.05rem;
        color: var(--color-text-muted);
        margin: 0;
      }

      .recent-decks {
        margin-top: var(--spacing-md);
        padding-top: var(--spacing-md);
        border-top: 1px solid var(--glass-border);
      }

      .public-decks {
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

      .view-all {
        font-size: 0.95rem;
        color: var(--color-primary-accent);
        text-decoration: none;
        font-weight: 600;
        transition: opacity 0.2s ease;
      }

      .view-all:hover {
        opacity: 0.8;
      }

      .deck-list {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
        gap: var(--spacing-lg);
      }

      @keyframes blink {
        0%, 100% { opacity: 1; }
        50% { opacity: 0; }
      }

      @keyframes fade-up {
        from { opacity: 0; transform: translateY(10px); }
        to { opacity: 1; transform: translateY(0); }
      }

      @keyframes float {
        0%, 100% { transform: translateY(0); }
        50% { transform: translateY(-6px); }
      }

      @media (max-width: 1024px) {
        .hero-grid {
          grid-template-columns: 1fr;
        }

        .hero-visual {
          order: 2;
        }
      }

      @media (max-width: 768px) {
        .hero {
          padding: var(--spacing-xl) 0;
        }

        .hero-actions {
          width: 100%;
        }

        .search-row,
        .deck-search-form {
          grid-template-columns: 1fr;
        }

        .study-info {
          flex-direction: column;
          align-items: flex-start;
        }

        .study-info app-button {
          width: 100%;
        }

        .section-header {
          flex-direction: column;
          align-items: flex-start;
        }
      }

      @media (max-width: 520px) {
        .hero-title {
          font-size: 2.2rem;
        }

        .hero-card:nth-child(2) {
          margin-top: 0;
        }
      }

      @media (prefers-reduced-motion: reduce) {
        .hero-content,
        .hero-visual,
        .hero-card {
          animation: none;
        }
      }
    `
    ]
})
export class HomePageComponent implements OnInit, OnDestroy {
    loading = true;
    publicDecks: PublicDeckDTO[] = [];
    userDecks: UserDeckDTO[] = [];
    todayStats = { due: 0, new: 0 };
    currentUserId: string | null = null;
    userPublicDeckIds: Set<string> = new Set();
    userPublicDeckIdsLoaded = false;
    heroSearchQuery = '';
    catalogSearchQuery = '';
    typedPlaceholder = '';
    popularTopics: string[] = [];
    readonly heroPillars = [
        { titleKey: 'home.heroPillar1Title', descriptionKey: 'home.heroPillar1Desc' },
        { titleKey: 'home.heroPillar2Title', descriptionKey: 'home.heroPillar2Desc' },
        { titleKey: 'home.heroPillar3Title', descriptionKey: 'home.heroPillar3Desc' }
    ];
    readonly howSteps = [
        { titleKey: 'home.howStep1Title', descriptionKey: 'home.howStep1Desc' },
        { titleKey: 'home.howStep2Title', descriptionKey: 'home.howStep2Desc' },
        { titleKey: 'home.howStep3Title', descriptionKey: 'home.howStep3Desc' },
        { titleKey: 'home.howStep4Title', descriptionKey: 'home.howStep4Desc' }
    ];
    readonly valueProps = [
        { icon: '⏱️', titleKey: 'home.value1Title', descriptionKey: 'home.value1Desc' },
        { icon: '🎯', titleKey: 'home.value2Title', descriptionKey: 'home.value2Desc' },
        { icon: '🧩', titleKey: 'home.value3Title', descriptionKey: 'home.value3Desc' }
    ];
    readonly starterDecks = [
        { href: '/public-decks?starter=english-core', titleKey: 'home.starterDeck1Title', descriptionKey: 'home.starterDeck1Desc', badge: 'popular' },
        { href: '/public-decks?starter=english-patterns', titleKey: 'home.starterDeck2Title', descriptionKey: 'home.starterDeck2Desc', badge: 'new' },
        { href: '/public-decks?starter=kanji-n5', titleKey: 'home.starterDeck3Title', descriptionKey: 'home.starterDeck3Desc', badge: 'popular' },
        { href: '/public-decks?starter=anatomy', titleKey: 'home.starterDeck4Title', descriptionKey: 'home.starterDeck4Desc', badge: 'new' },
        { href: '/public-decks?starter=art-history', titleKey: 'home.starterDeck5Title', descriptionKey: 'home.starterDeck5Desc', badge: 'popular' },
        { href: '/public-decks?starter=world-capitals', titleKey: 'home.starterDeck6Title', descriptionKey: 'home.starterDeck6Desc', badge: 'new' }
    ];
    private typingSamples: string[] = [];
    private readonly typingSamplesByLanguage: Record<Language, string[]> = {
        en: [
            'English C1',
            'Japanese N5',
            'Medical terms',
            'Spanish travel',
            'JS interviews'
        ],
        ru: [
            'Английский C1',
            'Японский N5',
            'Медицинские термины',
            'Испанский для поездок',
            'JS интервью'
        ]
    };
    private readonly popularTopicsByLanguage: Record<Language, string[]> = {
        en: ['English', 'Japanese', 'Programming', 'Medicine', 'Art', 'Geography'],
        ru: ['Английский', 'Японский', 'Программирование', 'Медицина', 'Искусство', 'География']
    };
    private typingTimeoutId?: number;
    private typingSampleIndex = 0;
    private typingCharIndex = 0;
    private typingDeleting = false;
    private authSubscription?: Subscription;
    private languageSubscription?: Subscription;
    private hasLoadedUserDecks = false;
    private reviewStatsSubscription?: Subscription;
    private static reviewStatsCache: { data: { due: number; new: number }; timestamp: number } | null = null;
    private static deckSizesCache: Map<string, { size: number; timestamp: number }> = new Map();
    private static readonly CACHE_TTL_MS = 5 * 60 * 1000;
    private deckSizes: Map<string, number> = new Map();
    private static deckIconMediaCache: Map<string, string> = new Map();
    private static deckIconUrlCache: Map<string, string> = new Map();
    private deckIcons: Map<string, string> = new Map();

    constructor(
        public auth: AuthService,
        private i18n: I18nService,
        private userApi: UserApiService,
        private publicDeckApi: PublicDeckApiService,
        private deckApi: DeckApiService,
        private reviewApi: ReviewApiService,
        private mediaApi: MediaApiService,
        private router: Router
    ) {}

    ngOnInit(): void {
        this.syncLandingCopy();
        this.languageSubscription = this.i18n.currentLanguage$.subscribe(() => {
            this.syncLandingCopy();
        });
        this.loadData();
        this.authSubscription = this.auth.status$
            .pipe(
                distinctUntilChanged(),
                skip(1),
                filter((status: AuthStatus) => status === 'authenticated')
            )
            .subscribe(() => {
                if (!this.hasLoadedUserDecks) {
                    this.loadUserData();
                }
            });
    }

    ngOnDestroy(): void {
        this.authSubscription?.unsubscribe();
        this.reviewStatsSubscription?.unsubscribe();
        this.languageSubscription?.unsubscribe();
        this.clearTypingTimeout();
    }

    onHeroSearchInput(event: Event): void {
        this.heroSearchQuery = (event.target as HTMLInputElement).value;
    }

    submitHeroSearch(): void {
        this.submitDeckSearch(this.heroSearchQuery);
    }

    applyHeroTopic(topic: string): void {
        this.heroSearchQuery = topic;
        this.submitDeckSearch(topic);
    }

    onCatalogSearchInput(event: Event): void {
        this.catalogSearchQuery = (event.target as HTMLInputElement).value;
    }

    submitCatalogSearch(): void {
        this.submitDeckSearch(this.catalogSearchQuery);
    }

    applyCatalogTopic(topic: string): void {
        this.catalogSearchQuery = topic;
        this.submitDeckSearch(topic);
    }

    private submitDeckSearch(query: string): void {
        const trimmed = query.trim();
        const queryParams = trimmed ? { q: trimmed } : {};
        void this.router.navigate(['/public-decks'], { queryParams });
    }

    private syncLandingCopy(): void {
        const lang = this.i18n.currentLanguage;
        this.popularTopics = this.popularTopicsByLanguage[lang] || this.popularTopicsByLanguage.en;
        this.typingSamples = this.typingSamplesByLanguage[lang] || this.typingSamplesByLanguage.en;
        this.resetTypingEffect();
    }

    private resetTypingEffect(): void {
        this.typedPlaceholder = '';
        this.typingSampleIndex = 0;
        this.typingCharIndex = 0;
        this.typingDeleting = false;
        this.clearTypingTimeout();
        this.scheduleTyping();
    }

    private scheduleTyping(): void {
        if (this.typingSamples.length === 0) {
            return;
        }

        const phrase = this.typingSamples[this.typingSampleIndex];
        if (this.typingDeleting) {
            this.typingCharIndex = Math.max(0, this.typingCharIndex - 1);
        } else {
            this.typingCharIndex = Math.min(phrase.length, this.typingCharIndex + 1);
        }

        this.typedPlaceholder = phrase.slice(0, this.typingCharIndex);

        let delay = this.typingDeleting ? 45 : 70;
        if (!this.typingDeleting && this.typingCharIndex >= phrase.length) {
            delay = 1400;
            this.typingDeleting = true;
        } else if (this.typingDeleting && this.typingCharIndex <= 0) {
            this.typingDeleting = false;
            this.typingSampleIndex = (this.typingSampleIndex + 1) % this.typingSamples.length;
            delay = 320;
        }

        this.typingTimeoutId = window.setTimeout(() => this.scheduleTyping(), delay);
    }

    private clearTypingTimeout(): void {
        if (this.typingTimeoutId) {
            window.clearTimeout(this.typingTimeoutId);
            this.typingTimeoutId = undefined;
        }
    }

    private loadData(): void {
        const publicDecks$ = this.publicDeckApi.getPublicDecks(1, 6).pipe(
            catchError(() => of({ content: [] as PublicDeckDTO[] }))
        );

        const isAuthenticated = this.auth.status() === 'authenticated';
        const userDecks$ = isAuthenticated
            ? this.deckApi.getMyDecks(1, 3).pipe(
                catchError(() => of({ content: [] as UserDeckDTO[] }))
            )
            : of({ content: [] as UserDeckDTO[] });

        const user$ = isAuthenticated
            ? this.userApi.getMe().pipe(
                catchError(() => of(null))
            )
            : of(null);

        const publicDeckIds$ = isAuthenticated
            ? this.deckApi.getMyPublicDeckIds().pipe(
                catchError(() => of({ publicDeckIds: [] as string[] }))
            )
            : of({ publicDeckIds: [] as string[] });

        forkJoin({
            publicDecks: publicDecks$,
            userDecks: userDecks$,
            user: user$,
            publicDeckIds: publicDeckIds$
        }).subscribe({
            next: result => {
                this.publicDecks = result.publicDecks.content;
                this.userDecks = result.userDecks.content;
                this.currentUserId = result.user?.id || null;
                this.userPublicDeckIds = new Set(result.publicDeckIds.publicDeckIds || []);
                this.userPublicDeckIdsLoaded = isAuthenticated;
                this.hasLoadedUserDecks = isAuthenticated;
                this.loading = false;

                if (isAuthenticated && this.userDecks.length > 0) {
                    this.loadReviewStats();
                    this.loadDeckSizes();
                    this.loadUserDeckIcons();
                }

                if (this.publicDecks.length > 0) {
                    this.resolvePublicDeckIcons(this.publicDecks);
                }
            },
            error: () => {
                this.loading = false;
            }
        });
    }

    private loadReviewStats(): void {
        const now = Date.now();
        const cached = HomePageComponent.reviewStatsCache;

        if (cached && (now - cached.timestamp) < HomePageComponent.CACHE_TTL_MS) {
            this.todayStats = cached.data;
            return;
        }

        this.todayStats = { due: 0, new: 0 };
        this.reviewStatsSubscription?.unsubscribe();

        this.reviewStatsSubscription = this.reviewApi
            .getSummary()
            .pipe(catchError(() => of({ dueCount: 0, newCount: 0 })))
            .subscribe({
                next: summary => {
                    this.todayStats = {
                        due: summary.dueCount || 0,
                        new: summary.newCount || 0
                    };
                    HomePageComponent.reviewStatsCache = {
                        data: { ...this.todayStats },
                        timestamp: now
                    };
                }
            });
    }

    private loadUserData(): void {
        const userDecks$ = this.deckApi.getMyDecks(1, 3).pipe(
            catchError(() => of({ content: [] as UserDeckDTO[] }))
        );

        const user$ = this.userApi.getMe().pipe(
            catchError(() => of(null))
        );

        const publicDeckIds$ = this.deckApi.getMyPublicDeckIds().pipe(
            catchError(() => of({ publicDeckIds: [] as string[] }))
        );

        forkJoin({
            userDecks: userDecks$,
            user: user$,
            publicDeckIds: publicDeckIds$
        }).subscribe({
            next: result => {
                this.userDecks = result.userDecks.content;
                this.currentUserId = result.user?.id || null;
                this.userPublicDeckIds = new Set(result.publicDeckIds.publicDeckIds || []);
                this.userPublicDeckIdsLoaded = true;
                this.hasLoadedUserDecks = true;

                if (this.userDecks.length > 0) {
                    this.loadReviewStats();
                    this.loadDeckSizes();
                    this.loadUserDeckIcons();
                }
            }
        });
    }

    private loadDeckSizes(): void {
        const now = Date.now();

        from(this.userDecks)
            .pipe(
                mergeMap(deck => {
                    const cached = HomePageComponent.deckSizesCache.get(deck.userDeckId);
                    if (cached && (now - cached.timestamp) < HomePageComponent.CACHE_TTL_MS) {
                        this.deckSizes.set(deck.userDeckId, cached.size);
                        return of(null);
                    }

                    return this.deckApi.getUserDeckSize(deck.userDeckId).pipe(
                        catchError(() => of(null))
                    );
                }, 4)
            )
            .subscribe({
                next: response => {
                    if (response) {
                        this.deckSizes.set(response.deckId, response.cardsQty);
                        HomePageComponent.deckSizesCache.set(response.deckId, {
                            size: response.cardsQty,
                            timestamp: now
                        });
                    }
                }
            });
    }

    private resolvePublicDeckIcons(decks: PublicDeckDTO[]): void {
        const publicDeckIds: string[] = [];

        decks.forEach(deck => {
            publicDeckIds.push(deck.deckId);
            if (deck.iconUrl) {
                this.deckIcons.set(deck.deckId, deck.iconUrl);
                HomePageComponent.deckIconUrlCache.set(deck.deckId, deck.iconUrl);
            }
            if (deck.iconMediaId) {
                HomePageComponent.deckIconMediaCache.set(deck.deckId, deck.iconMediaId);
            }
        });

        this.resolveDeckIcons(publicDeckIds);
    }

    private loadUserDeckIcons(): void {
        const publicDeckIds = this.userDecks
            .map(deck => deck.publicDeckId)
            .filter(Boolean);

        if (publicDeckIds.length === 0) {
            return;
        }

        const missingIds = publicDeckIds.filter(id =>
            !HomePageComponent.deckIconMediaCache.has(id)
            && !HomePageComponent.deckIconUrlCache.has(id)
        );

        if (missingIds.length === 0) {
            this.resolveDeckIcons(publicDeckIds);
            return;
        }

        from(missingIds)
            .pipe(
                mergeMap(id => this.publicDeckApi.getPublicDeck(id).pipe(
                    catchError(() => of(null))
                ), 3)
            )
            .subscribe({
                next: response => {
                    if (response?.iconMediaId) {
                        HomePageComponent.deckIconMediaCache.set(response.deckId, response.iconMediaId);
                    }
                    if (response?.iconUrl) {
                        this.deckIcons.set(response.deckId, response.iconUrl);
                        HomePageComponent.deckIconUrlCache.set(response.deckId, response.iconUrl);
                    }
                },
                complete: () => {
                    this.resolveDeckIcons(publicDeckIds);
                }
            });
    }

    private resolveDeckIcons(publicDeckIds: string[]): void {
        const mediaIds: string[] = [];
        const deckIdsByMedia = new Map<string, string[]>();

        for (const publicDeckId of publicDeckIds) {
            const cachedUrl = HomePageComponent.deckIconUrlCache.get(publicDeckId);
            if (cachedUrl) {
                this.deckIcons.set(publicDeckId, cachedUrl);
                continue;
            }

            const mediaId = HomePageComponent.deckIconMediaCache.get(publicDeckId);
            if (!mediaId) {
                continue;
            }

            if (!deckIdsByMedia.has(mediaId)) {
                deckIdsByMedia.set(mediaId, []);
                mediaIds.push(mediaId);
            }

            deckIdsByMedia.get(mediaId)!.push(publicDeckId);
        }

        if (mediaIds.length === 0) {
            return;
        }

        this.mediaApi.resolve(mediaIds).subscribe({
            next: resolved => {
                resolved.forEach(media => {
                    const deckIds = deckIdsByMedia.get(media.mediaId);
                    if (!deckIds || !media.url) {
                        return;
                    }

                    deckIds.forEach(deckId => {
                        this.deckIcons.set(deckId, media.url);
                        HomePageComponent.deckIconUrlCache.set(deckId, media.url);
                    });
                });
            },
            error: err => {
                console.error('Failed to resolve deck icons', err);
            }
        });
    }

    canForkDeck(deck: PublicDeckDTO): boolean {
        if (this.auth.status() !== 'authenticated') {
            return false;
        }
        if (!this.currentUserId || !this.userPublicDeckIdsLoaded) {
            return false;
        }
        if (this.currentUserId && deck.authorId === this.currentUserId) {
            return false;
        }
        return !this.userPublicDeckIds.has(deck.deckId);
    }

    getDeckStats(deck: UserDeckDTO): { cardCount?: number; dueToday?: number } {
        const size = this.deckSizes.get(deck.userDeckId);
        return {
            cardCount: size,
            dueToday: undefined
        };
    }

    getUserDeckIconUrl(deck: UserDeckDTO): string | null {
        if (!deck.publicDeckId) {
            return null;
        }

        return this.deckIcons.get(deck.publicDeckId) || null;
    }

    getPublicDeckIconUrl(deck: PublicDeckDTO): string | null {
        return this.deckIcons.get(deck.deckId) || null;
    }

    openUserDeck(userDeckId: string): void {
        void this.router.navigate(['/decks', userDeckId]);
    }

    openPublicDeck(deckId: string): void {
        void this.router.navigate(['/public-decks', deckId, 'browse']);
    }

    learnDeck(userDeckId: string): void {
        void this.router.navigate(['/decks', userDeckId, 'review']);
    }

    browseDeck(userDeckId: string): void {
        void this.router.navigate(['/decks', userDeckId, 'browse']);
    }

    browsePublicDeck(deckId: string): void {
        void this.router.navigate(['/public-decks', deckId, 'browse']);
    }

    forkDeck(deckId: string): void {
        this.publicDeckApi.fork(deckId).subscribe({
            next: userDeck => {
                void this.router.navigate(['/decks', userDeck.userDeckId]);
            },
            error: err => {
                console.error('Failed to fork deck:', err);
            }
        });
    }

    goToPublicDecks(): void {
        void this.router.navigate(['/public-decks']);
    }
}
