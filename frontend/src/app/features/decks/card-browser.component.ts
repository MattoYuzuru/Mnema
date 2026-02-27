import { Component, OnInit, HostListener } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { NgIf, NgFor } from '@angular/common';
import { ReactiveFormsModule, FormBuilder, FormGroup, Validators, FormControl } from '@angular/forms';
import { forkJoin } from 'rxjs';
import { CardApiService } from '../../core/services/card-api.service';
import { DeckApiService } from '../../core/services/deck-api.service';
import { PublicDeckApiService } from '../../core/services/public-deck-api.service';
import { TemplateApiService } from '../../core/services/template-api.service';
import { SearchApiService } from '../../core/services/search-api.service';
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
import { AiEnhanceCardModalComponent } from './ai-enhance-card-modal.component';
import { InputComponent } from '../../shared/components/input.component';
import { TextareaComponent } from '../../shared/components/textarea.component';
import { MediaUploadComponent } from '../../shared/components/media-upload.component';
import { ConfirmationDialogComponent } from '../../shared/components/confirmation-dialog.component';
import { TagChipComponent } from '../../shared/components/tag-chip.component';
import { TranslatePipe } from '../../shared/pipes/translate.pipe';

@Component({
    selector: 'app-card-browser',
    standalone: true,
    imports: [NgIf, NgFor, ReactiveFormsModule, MemoryTipLoaderComponent, EmptyStateComponent, FlashcardViewComponent, ButtonComponent, AiEnhanceCardModalComponent, InputComponent, TextareaComponent, MediaUploadComponent, ConfirmationDialogComponent, TagChipComponent, TranslatePipe],
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
      </header>

      <div *ngIf="cards.length > 0" class="browser-layout">
        <aside class="cards-panel glass">
          <div class="panel-header">
            <div>
              <h2>{{ 'cardBrowser.list' | translate }}</h2>
              <p class="panel-meta">{{ cardCount }} {{ 'cardBrowser.cards' | translate }}</p>
            </div>
          </div>

          <div class="panel-search">
            <input
              type="search"
              class="card-search"
              [placeholder]="'cardBrowser.searchPlaceholder' | translate"
              [attr.aria-label]="'cardBrowser.searchPlaceholder' | translate"
              [value]="searchQuery"
              (input)="onSearchInput($event)"
            />
            <span *ngIf="searchActive" class="search-meta">
              {{ searchResultCount }} / {{ totalCards }} {{ 'cardBrowser.cards' | translate }}
            </span>
          </div>

          <div class="cards-list" (scroll)="onListScroll($event)">
            <div *ngFor="let card of cards; let index = index" class="cards-list-item" [class.active]="index === currentCardIndex">
              <button class="card-preview" type="button" (click)="openCardFromList(index)">
                <span class="card-index">{{ index + 1 }}</span>
                <div class="card-preview-body">
                  <span class="card-text">{{ getFrontPreview(card) }}</span>
                  <div *ngIf="card.tags?.length" class="card-tags-inline">
                    <app-tag-chip *ngFor="let tag of card.tags" [text]="tag"></app-tag-chip>
                  </div>
                </div>
              </button>
              <div class="card-item-actions">
                <button class="icon-btn" (click)="openEditModal(card); $event.stopPropagation()" [title]="'cardBrowser.editCard' | translate">
                  <svg width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24">
                    <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/>
                    <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/>
                  </svg>
                </button>
                <button class="icon-btn delete" (click)="openDeleteModal(card); $event.stopPropagation()" [title]="'cardBrowser.deleteCard' | translate">
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
        </aside>

        <section class="preview-panel glass-strong">
          <div class="preview-header">
            <div>
              <h2>{{ 'cardBrowser.cardsView' | translate }}</h2>
              <p class="panel-meta">
                {{ searchNoResults ? 0 : (currentCardIndex + 1) }} / {{ searchNoResults ? 0 : cardCount }}
              </p>
            </div>
            <div class="preview-nav">
              <app-button
                variant="ghost"
                size="sm"
                (click)="previousCard()"
                [disabled]="searchNoResults || currentCardIndex === 0"
              >
                {{ 'cardBrowser.previous' | translate }}
              </app-button>
              <app-button
                variant="ghost"
                size="sm"
                (click)="nextCard()"
                [disabled]="searchNoResults || currentCardIndex >= cards.length - 1"
              >
                {{ 'cardBrowser.next' | translate }}
              </app-button>
            </div>
          </div>

          <div *ngIf="searchNoResults" class="no-results-panel">
            <div class="no-results-card glass">
              <h3>{{ 'cardBrowser.noSearchResults' | translate }}</h3>
              <p>{{ 'cardBrowser.noSearchResultsDescription' | translate }}</p>
            </div>
          </div>

          <div *ngIf="!searchNoResults && currentCard" class="flashcard-container">
            <div class="flashcard glass" (click)="onFlashcardClick($event)">
              <div class="flashcard-content">
                <div class="card-side front" [class.is-hidden]="revealed && !preferences.showFrontSideAfterFlip">
                  <app-flashcard-view
                    *ngIf="template && currentCard"
                    [template]="template"
                    [content]="currentCard.effectiveContent"
                    side="front"
                    [hideLabels]="preferences.hideFieldLabels"
                    [autoPlayAudioSequence]="preferences.autoPlayCardAudioSequence"
                    [autoPlaySequenceToken]="currentCard.userCardId"
                  ></app-flashcard-view>
                </div>
                <div *ngIf="revealed && preferences.showFrontSideAfterFlip" class="divider"></div>
                <div class="card-side back" [class.preload]="!revealed">
                  <app-flashcard-view
                    *ngIf="template && currentCard"
                    [template]="template"
                    [content]="currentCard.effectiveContent"
                    side="back"
                    [hideLabels]="preferences.hideFieldLabels"
                    [autoPlayAudioSequence]="false"
                  ></app-flashcard-view>
                </div>
              </div>
            </div>
            <div class="flip-hint">
              <p>{{ 'cardBrowser.clickToFlip' | translate }}</p>
              <p>{{ 'cardBrowser.keyboardHint' | translate }}</p>
            </div>
          </div>

          <div *ngIf="!searchNoResults && currentCard?.tags?.length" class="card-tags-panel">
            <app-tag-chip *ngFor="let tag of currentCard!.tags" [text]="tag"></app-tag-chip>
          </div>

          <div class="card-actions" *ngIf="!searchNoResults && currentCard">
            <app-button variant="secondary" size="sm" (click)="openEditModal(currentCard!)" *ngIf="currentCard">
              <svg width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24">
                <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/>
                <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/>
              </svg>
              {{ 'cardBrowser.editCard' | translate }}
            </app-button>
            <app-button
              variant="ghost"
              size="sm"
              (click)="openAiEnhanceModal(currentCard!)"
              *ngIf="currentCard && deck && publicDeck"
            >
              ✨ {{ 'cardBrowser.enhanceCard' | translate }}
            </app-button>
            <app-button variant="ghost" size="sm" tone="danger" (click)="openDeleteModal(currentCard!)" *ngIf="currentCard">
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

          <div *ngIf="!searchNoResults && currentCard?.personalNote" class="personal-note">
            <h3>{{ 'cardBrowser.personalNote' | translate }}</h3>
            <p>{{ currentCard?.personalNote }}</p>
          </div>
        </section>
      </div>

      <app-empty-state
        *ngIf="cards.length === 0"
        icon="📝"
        [title]="searchActive ? ('cardBrowser.noSearchResults' | translate) : ('cardBrowser.noCards' | translate)"
        [description]="searchActive ? ('cardBrowser.noSearchResultsDescription' | translate) : ('cardBrowser.noCardsDescription' | translate)"
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
            <div class="tag-editor">
              <label>{{ 'cardBrowser.tags' | translate }}</label>
              <input
                type="text"
                class="tag-input"
                [formControl]="tagInputControl"
                (keydown.enter)="addTag($event)"
                [placeholder]="'cardBrowser.tagsPlaceholder' | translate"
                [attr.maxlength]="maxTagLength"
                [attr.aria-label]="'cardBrowser.tags' | translate"
              />
              <div *ngIf="tags.length > 0" class="tags-list">
                <app-tag-chip
                  *ngFor="let tag of tags; let i = index"
                  [text]="tag"
                  [removable]="true"
                  (remove)="removeTag(i)"
                ></app-tag-chip>
              </div>
              <p *ngIf="tagErrorKey" class="error-message">{{ tagErrorKey | translate }}</p>
            </div>
            <app-textarea
              [label]="'cardBrowser.personalNote' | translate"
              formControlName="personalNote"
              [rows]="3"
              placeholder="Add a personal note (optional)"
            ></app-textarea>
            <div class="global-edit" *ngIf="editingCard && canEditGlobally(editingCard)">
              <label>
                <input
                  type="checkbox"
                  [checked]="applyGlobalEdits"
                  (change)="toggleGlobalEdit($event)"
                />
                {{ 'cardBrowser.editScopeGlobal' | translate }}
              </label>
              <p class="field-hint">{{ 'cardBrowser.editScopeGlobalHint' | translate }}</p>
            </div>
          </form>
        </div>
        <div class="modal-footer">
          <app-button variant="ghost" (click)="closeEditModal()" [disabled]="saving">{{ 'cardBrowser.cancel' | translate }}</app-button>
          <app-button variant="primary" (click)="saveEdit()" [disabled]="!canSaveEdit()">
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

    <app-ai-enhance-card-modal
      *ngIf="showAiEnhanceModal && enhanceCardTarget && template"
      [userDeckId]="userDeckId"
      [deckName]="deck?.displayName || ''"
      [deckDescription]="deck?.displayDescription || ''"
      [card]="enhanceCardTarget"
      [template]="template"
      (cardUpdated)="onAiCardUpdated($event)"
      (closed)="closeAiEnhanceModal()"
    ></app-ai-enhance-card-modal>
  `,
    styles: [`
      .card-browser {
        max-width: 82rem;
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

      .browser-layout {
        display: grid;
        grid-template-columns: minmax(240px, 300px) minmax(0, 1fr);
        gap: var(--spacing-xl);
        align-items: stretch;
      }

      .cards-panel {
        display: flex;
        flex-direction: column;
        gap: var(--spacing-md);
        padding: var(--spacing-lg);
        border-radius: var(--border-radius-lg);
        height: clamp(420px, 70vh, 760px);
        min-height: 420px;
      }

      .panel-header {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: var(--spacing-md);
      }

      .panel-header h2 {
        font-size: 1.1rem;
        margin: 0 0 var(--spacing-xs) 0;
      }

      .panel-meta {
        font-size: 0.85rem;
        color: var(--color-text-muted);
        margin: 0;
      }

      .panel-search {
        display: flex;
        flex-direction: column;
        gap: var(--spacing-xs);
      }

      .card-search {
        width: 100%;
        padding: 0.7rem 1rem;
        border: 1px solid var(--glass-border-strong);
        border-radius: var(--border-radius-full);
        font-size: 0.9rem;
        background: var(--color-surface-solid);
        color: var(--color-text-primary);
        box-shadow: inset 0 0 0 1px rgba(255, 255, 255, 0.5);
      }

      .card-search:focus {
        outline: none;
        border-color: var(--color-primary-accent);
        box-shadow: var(--focus-ring);
      }

      .search-meta {
        font-size: 0.85rem;
        color: var(--color-text-muted);
        white-space: nowrap;
        text-align: right;
      }

      .cards-list {
        display: flex;
        flex-direction: column;
        gap: var(--spacing-sm);
        overflow-y: auto;
        flex: 1;
        min-height: 0;
        padding-right: var(--spacing-xs);
        scrollbar-width: thin;
        scrollbar-color: var(--glass-border-strong) transparent;
      }

      .cards-list::-webkit-scrollbar {
        width: 8px;
      }

      .cards-list::-webkit-scrollbar-track {
        background: transparent;
      }

      .cards-list::-webkit-scrollbar-thumb {
        background: var(--glass-border-strong);
        border-radius: 999px;
        border: 2px solid transparent;
        background-clip: padding-box;
      }

      .cards-list::-webkit-scrollbar-thumb:hover {
        background: var(--border-color-hover);
      }

      .cards-list-item {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: var(--spacing-sm);
        padding: var(--spacing-sm) var(--spacing-md);
        border-radius: var(--border-radius-md);
        border: 1px solid var(--glass-border);
        background: var(--glass-surface-strong);
        transition: border-color 0.2s ease, box-shadow 0.2s ease, background 0.2s ease;
      }

      .cards-list-item.active {
        border-color: var(--color-primary-accent);
        box-shadow: var(--shadow-sm);
      }

      .card-preview {
        display: flex;
        align-items: center;
        gap: var(--spacing-sm);
        text-align: left;
        background: none;
        border: none;
        padding: 0;
        color: var(--color-text-primary);
        cursor: pointer;
        font: inherit;
        min-width: 0;
        flex: 1;
      }

      .card-index {
        font-size: 0.8rem;
        color: var(--color-text-muted);
        min-width: 1.5rem;
      }

      .card-text {
        font-size: 0.9rem;
        line-height: 1.3;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }

      .card-preview-body {
        display: flex;
        flex-direction: column;
        gap: var(--spacing-xs);
        min-width: 0;
        flex: 1;
      }

      .card-tags-inline {
        display: flex;
        flex-wrap: wrap;
        gap: var(--spacing-xs);
      }

      .flashcard-container {
        display: flex;
        flex-direction: column;
        align-items: center;
        gap: var(--spacing-md);
      }

      .no-results-panel {
        display: flex;
        flex: 1;
        align-items: center;
        justify-content: center;
        padding: var(--spacing-lg);
      }

      .no-results-card {
        width: 100%;
        max-width: 30rem;
        padding: var(--spacing-xl);
        border-radius: var(--border-radius-lg);
        text-align: center;
      }

      .no-results-card h3 {
        margin: 0 0 var(--spacing-sm) 0;
        font-size: 1.1rem;
      }

      .no-results-card p {
        margin: 0;
        color: var(--color-text-muted);
      }

      .flashcard {
        width: 100%;
        max-width: 42rem;
        min-height: 16rem;
        cursor: pointer;
        border-radius: var(--border-radius-lg);
        padding: var(--spacing-xl);
        display: flex;
        align-items: center;
        justify-content: center;
      }

      .flashcard-content {
        width: 100%;
        display: flex;
        flex-direction: column;
        gap: var(--spacing-lg);
        position: relative;
      }

      .card-side {
        width: 100%;
      }

      .card-side.preload {
        position: absolute;
        left: -9999px;
        top: 0;
        height: 0;
        overflow: hidden;
        pointer-events: none;
        visibility: hidden;
      }

      .card-side.is-hidden {
        display: none;
      }

      .divider {
        height: 1px;
        background: var(--border-color);
        margin: var(--spacing-md) 0;
      }

      .flip-hint {
        font-size: 0.9rem;
        color: var(--color-text-muted);
        text-align: center;
        margin: 0;
      }

      .card-tags-panel {
        display: flex;
        flex-wrap: wrap;
        gap: var(--spacing-xs);
        justify-content: center;
        padding: var(--spacing-xs) 0;
      }

      .personal-note {
        max-width: 600px;
        margin: 0 auto;
        padding: var(--spacing-lg);
        background: var(--glass-surface);
        border: 1px solid var(--glass-border);
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

      .card-item-actions {
        display: flex;
        gap: var(--spacing-xs);
      }

      .icon-btn {
        background: var(--glass-surface);
        border: 1px solid var(--glass-border);
        cursor: pointer;
        padding: var(--spacing-xs);
        color: var(--color-text-secondary);
        transition: color 0.2s ease;
        display: inline-flex;
        align-items: center;
        justify-content: center;
        border-radius: var(--border-radius-sm);
      }

      .icon-btn:hover {
        color: var(--color-primary-accent);
        border-color: var(--glass-border-strong);
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

      .preview-panel {
        display: flex;
        flex-direction: column;
        gap: var(--spacing-lg);
        padding: var(--spacing-lg);
        border-radius: var(--border-radius-lg);
        min-height: 420px;
      }

      .preview-header {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: var(--spacing-md);
      }

      .preview-header h2 {
        font-size: 1.1rem;
        margin: 0 0 var(--spacing-xs) 0;
      }

      .preview-nav {
        display: flex;
        align-items: center;
        gap: var(--spacing-xs);
      }

      .modal-overlay {
        position: fixed;
        top: 0;
        left: 0;
        right: 0;
        bottom: 0;
        background: rgba(8, 12, 22, 0.55);
        display: flex;
        align-items: center;
        justify-content: center;
        backdrop-filter: blur(12px) saturate(140%);
        z-index: 1000;
      }

      .modal-content {
        background: var(--color-surface-solid);
        border: 1px solid var(--glass-border);
        border-radius: var(--border-radius-lg);
        max-width: 800px;
        width: 90%;
        max-height: 90vh;
        display: flex;
        flex-direction: column;
        box-shadow: var(--shadow-lg);
      }

      .modal-header {
        display: flex;
        justify-content: space-between;
        align-items: center;
        padding: var(--spacing-lg);
        border-bottom: 1px solid var(--glass-border);
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

      .tag-editor {
        display: flex;
        flex-direction: column;
        gap: var(--spacing-sm);
      }

      .global-edit {
        margin-top: var(--spacing-md);
        padding: var(--spacing-sm) var(--spacing-md);
        border-radius: var(--border-radius-md);
        background: var(--color-card-background);
        border: 1px dashed var(--glass-border);
      }

      .global-edit label {
        display: flex;
        align-items: center;
        gap: var(--spacing-sm);
        font-weight: 600;
      }

      .tag-input {
        width: 100%;
        padding: var(--spacing-sm) var(--spacing-md);
        border: 1px solid var(--border-color);
        border-radius: var(--border-radius-md);
        background: var(--color-card-background);
        color: var(--color-text-primary);
        transition: border-color 0.2s ease;
      }

      .tag-input:focus {
        outline: none;
        border-color: var(--color-primary-accent);
        box-shadow: var(--focus-ring);
      }

      .tags-list {
        display: flex;
        flex-wrap: wrap;
        gap: var(--spacing-xs);
      }

      .error-message {
        margin: 0;
        font-size: 0.85rem;
        color: #dc2626;
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
        border-top: 1px solid var(--glass-border);
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

        .browser-layout {
          grid-template-columns: 1fr;
        }

        .cards-panel {
          height: auto;
        }

        .cards-list {
          max-height: 40vh;
        }

        .flashcard {
          min-height: 14rem;
          padding: var(--spacing-lg);
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
          min-height: 12rem;
          padding: var(--spacing-md);
        }
      }
    `]
})
export class CardBrowserComponent implements OnInit {
    private static readonly PAGE_SIZE = 50;
    private static readonly PREFETCH_THRESHOLD = 0.9;
    private static readonly MAX_TAGS = 3;
    private static readonly MAX_TAG_LENGTH = 25;

    loading = true;
    cards: UserCardDTO[] = [];
    deck: UserDeckDTO | null = null;
    publicDeck: PublicDeckDTO | null = null;
    template: CardTemplateDTO | null = null;
    userDeckId = '';
    currentCardIndex = 0;
    revealed = false;
    showEditModal = false;
    editingCard: UserCardDTO | null = null;
    showDeleteConfirm = false;
    showScopePrompt = false;
    showAiEnhanceModal = false;
    enhanceCardTarget: UserCardDTO | null = null;
    editForm!: FormGroup;
    tagInputControl = new FormControl('', { nonNullable: true });
    tags: string[] = [];
    tagErrorKey = '';
    readonly maxTagLength = CardBrowserComponent.MAX_TAG_LENGTH;
    saving = false;
    deleting = false;
    deleteTarget: UserCardDTO | null = null;
    currentUserId: string | null = null;
    isAuthor = false;
    totalCards = 0;
    deckTotalCards = 0;
    searchQuery = '';
    searchActive = false;
    searchNoResults = false;
    searchResultCount = 0;
    applyGlobalEdits = false;
    private unfilteredCards: UserCardDTO[] = [];
    private currentPage = 1;
    private hasMoreCards = true;
    private loadingMore = false;
    private searchDebounce?: ReturnType<typeof setTimeout>;

    constructor(
        private route: ActivatedRoute,
        private router: Router,
        private cardApi: CardApiService,
        private deckApi: DeckApiService,
        private publicDeckApi: PublicDeckApiService,
        private templateApi: TemplateApiService,
        private userApi: UserApiService,
        private searchApi: SearchApiService,
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
        if (this.cards.length === 0 || !this.currentCard) return;
        if (this.showEditModal || this.showDeleteConfirm || this.showScopePrompt) return;
        if (this.searchNoResults) return;

        const target = event.target as HTMLElement;
        if (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.tagName === 'SELECT' || target.isContentEditable) {
            return;
        }

        if (this.isSpaceKey(event)) {
            event.preventDefault();
            this.toggleReveal();
        } else if (event.key === 'ArrowLeft') {
            event.preventDefault();
            this.previousCard();
        } else if (event.key === 'ArrowRight') {
            event.preventDefault();
            this.nextCard();
        }
    }

    onListScroll(event: Event): void {
        const target = event.target as HTMLElement | null;
        if (!target) return;
        this.maybeLoadMoreOnScroll(target);
    }

    onSearchInput(event: Event): void {
        const input = event.target as HTMLInputElement;
        this.searchQuery = input.value;
        if (this.searchDebounce) {
            clearTimeout(this.searchDebounce);
        }
        this.searchDebounce = setTimeout(() => {
            this.applySearch();
        }, 300);
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
                const templateVersion = this.deck?.templateVersion ?? publicDeck.templateVersion ?? null;
                forkJoin({
                    template: this.templateApi.getTemplate(publicDeck.templateId, templateVersion),
                    cards: this.cardApi.getUserCards(this.userDeckId, 1, CardBrowserComponent.PAGE_SIZE),
                    size: this.deckApi.getUserDeckSize(this.userDeckId)
                }).subscribe({
                    next: ({ template, cards, size }) => {
                        this.template = template;
                        this.cards = cards.content;
                        this.unfilteredCards = cards.content;
                        this.currentPage = cards.number + 1;
                        this.hasMoreCards = !cards.last;
                        this.deckTotalCards = size.cardsQty;
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

    private applySearch(): void {
        const normalized = this.searchQuery.trim();
        this.searchActive = normalized.length > 0;
        this.searchNoResults = false;
        this.searchResultCount = 0;
        this.currentPage = 1;
        this.hasMoreCards = true;
        this.loadingMore = false;
        this.currentCardIndex = 0;
        this.revealed = false;

        if (!this.searchActive) {
            this.totalCards = this.deckTotalCards;
        }

        this.fetchCardsPage(1, this.searchActive ? normalized : null).subscribe({
            next: page => {
                this.searchResultCount = page.totalElements || page.content.length;
                this.currentPage = page.number + 1;
                this.hasMoreCards = !page.last;
                this.totalCards = this.searchActive ? (page.totalElements || 0) : this.deckTotalCards;

                if (this.searchActive && page.content.length === 0) {
                    this.searchNoResults = true;
                    this.cards = this.unfilteredCards;
                    this.hasMoreCards = false;
                    return;
                }

                this.cards = page.content;
                if (!this.searchActive) {
                    this.unfilteredCards = page.content;
                }
            },
            error: err => {
                console.error('Failed to search cards:', err);
            }
        });
    }

    get currentCard(): UserCardDTO | null {
        return this.cards[this.currentCardIndex] || null;
    }

    get cardCount(): number {
        return this.totalCards || this.cards.length;
    }

    previousCard(): void {
        if (this.currentCardIndex > 0) {
            this.currentCardIndex--;
            this.revealed = false;
        }
    }

    nextCard(): void {
        if (this.currentCardIndex < this.cards.length - 1) {
            this.currentCardIndex++;
            this.revealed = false;
            this.maybePrefetchMoreCards();
        }
    }

    private maybePrefetchMoreCards(): void {
        if (this.searchNoResults) {
            return;
        }
        if (!this.hasMoreCards || this.loadingMore || this.cards.length === 0) {
            return;
        }
        const thresholdIndex = Math.floor(this.cards.length * CardBrowserComponent.PREFETCH_THRESHOLD);
        if (this.currentCardIndex + 1 >= thresholdIndex) {
            this.loadMoreCards();
        }
    }

    private maybeLoadMoreOnScroll(container: HTMLElement): void {
        if (!this.hasMoreCards || this.loadingMore || this.cards.length === 0) {
            return;
        }
        const scrollPosition = container.scrollTop + container.clientHeight;
        const threshold = container.scrollHeight * CardBrowserComponent.PREFETCH_THRESHOLD;
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
        const normalizedQuery = this.searchQuery.trim();
        const query = this.searchActive && normalizedQuery.length > 0 ? normalizedQuery : null;
        this.fetchCardsPage(nextPage, query).subscribe({
            next: page => {
                const existingIds = new Set(this.cards.map(card => card.userCardId));
                const newCards = page.content.filter(card => !existingIds.has(card.userCardId));
                this.cards = [...this.cards, ...newCards];
                this.currentPage = page.number + 1;
                this.hasMoreCards = !page.last;
                this.totalCards = this.searchActive ? (page.totalElements || 0) : this.deckTotalCards;
                if (!this.searchActive) {
                    this.unfilteredCards = this.cards;
                }
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

    private fetchCardsPage(page: number, query: string | null) {
        if (this.searchActive && query) {
            return this.searchApi.searchUserCards(this.userDeckId, query, null, page, CardBrowserComponent.PAGE_SIZE);
        }
        return this.cardApi.getUserCards(this.userDeckId, page, CardBrowserComponent.PAGE_SIZE);
    }

    toggleReveal(): void {
        this.revealed = !this.revealed;
    }

    onFlashcardClick(event: MouseEvent): void {
        const target = event.target as HTMLElement | null;
        if (!target) {
            this.toggleReveal();
            return;
        }
        if (target.closest('a, button, input, textarea, select, audio, video, [data-no-flip]')) {
            return;
        }
        this.toggleReveal();
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

    private isSpaceKey(event: KeyboardEvent): boolean {
        return event.key === ' ' || event.key === 'Space' || event.key === 'Spacebar' || event.code === 'Space';
    }

    backToDeck(): void {
        void this.router.navigate(['/decks', this.userDeckId]);
    }

    openCardFromList(index: number): void {
        if (index < 0 || index >= this.cards.length) {
            return;
        }
        this.currentCardIndex = index;
        this.revealed = false;
        this.maybePrefetchMoreCards();
    }

    openEditModal(card: UserCardDTO): void {
        this.editingCard = card;
        this.applyGlobalEdits = false;
        if (this.template) {
            const controls: { [key: string]: any } = {};
            this.template.fields?.forEach(field => {
                controls[field.name] = [card.effectiveContent[field.name] || '', field.isRequired ? Validators.required : []];
            });
            controls['personalNote'] = [card.personalNote || ''];
            this.editForm = this.fb.group(controls);
            this.tags = [...(card.tags || [])];
            this.tagInputControl.setValue('');
            this.tagErrorKey = '';
            this.showEditModal = true;
        }
    }

    closeEditModal(): void {
        this.showEditModal = false;
        this.editingCard = null;
        this.applyGlobalEdits = false;
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

    addTag(event: Event): void {
        event.preventDefault();
        const tag = this.tagInputControl.value.trim();
        if (!tag) {
            return;
        }
        if (this.tags.length >= CardBrowserComponent.MAX_TAGS) {
            this.tagErrorKey = 'validation.cardTagsLimit';
            return;
        }
        if (tag.length > CardBrowserComponent.MAX_TAG_LENGTH) {
            this.tagErrorKey = 'validation.tagTooLong';
            return;
        }
        if (!this.tags.includes(tag)) {
            this.tags.push(tag);
        }
        this.tagInputControl.setValue('');
        this.tagErrorKey = '';
    }

    removeTag(index: number): void {
        this.tags.splice(index, 1);
        this.tagErrorKey = '';
    }

    private validateTags(): boolean {
        if (this.tags.length > CardBrowserComponent.MAX_TAGS) {
            this.tagErrorKey = 'validation.cardTagsLimit';
            return false;
        }
        if (this.tags.some(tag => tag.length > CardBrowserComponent.MAX_TAG_LENGTH)) {
            this.tagErrorKey = 'validation.tagTooLong';
            return false;
        }
        this.tagErrorKey = '';
        return true;
    }

    canSaveEdit(): boolean {
        if (this.saving || !this.editForm || !this.editingCard) return false;
        if (this.editForm.valid) return true;
        return this.canSaveTagsOnly();
    }

    saveEdit(): void {
        if (!this.editingCard) return;
        if (!this.validateTags()) return;

        const formValue = this.editForm.value;
        const { personalNote, ...content } = formValue;
        const contentChanged = this.hasContentChanges(formValue);
        const noteChanged = this.hasPersonalNoteChange(formValue);
        const tagsChanged = this.areTagsChanged();

        if (this.editForm.invalid && (contentChanged || noteChanged)) {
            return;
        }
        if (!contentChanged && !noteChanged && !tagsChanged) {
            return;
        }

        const updates: Partial<UserCardDTO> = {
            tags: this.tags
        };

        if (contentChanged) {
            updates.effectiveContent = content;
        }

        if (noteChanged) {
            updates.personalNote = personalNote || null;
        }

        this.saving = true;
        const scope = this.applyGlobalEdits ? 'global' : 'local';
        this.cardApi.patchUserCard(this.userDeckId, this.editingCard.userCardId, updates, scope).subscribe({
            next: updatedCard => {
                const index = this.cards.findIndex(c => c.userCardId === updatedCard.userCardId);
                if (index !== -1) {
                    this.cards[index] = updatedCard;
                }
                this.saving = false;
                this.showEditModal = false;
                this.editingCard = null;
                this.applyGlobalEdits = false;
            },
            error: err => {
                console.error('Failed to update card:', err);
                this.saving = false;
            }
        });
    }

    private canSaveTagsOnly(): boolean {
        if (!this.editingCard || !this.editForm) return false;
        const formValue = this.editForm.value;
        if (this.hasContentChanges(formValue) || this.hasPersonalNoteChange(formValue)) {
            return false;
        }
        return this.areTagsChanged();
    }

    private hasContentChanges(formValue: Record<string, any>): boolean {
        if (!this.template || !this.editingCard) return false;
        return (this.template.fields || []).some(field => !this.contentEquals(
            formValue[field.name],
            this.editingCard?.effectiveContent?.[field.name]
        ));
    }

    private hasPersonalNoteChange(formValue: Record<string, any>): boolean {
        if (!this.editingCard) return false;
        const current = formValue['personalNote'] ?? '';
        const original = this.editingCard.personalNote ?? '';
        return current !== original;
    }

    private areTagsChanged(): boolean {
        if (!this.editingCard) return false;
        const next = this.normalizeTags(this.tags);
        const original = this.normalizeTags(this.editingCard.tags);
        return JSON.stringify(next) !== JSON.stringify(original);
    }

    private normalizeTags(tags?: string[] | null): string[] {
        return (tags || [])
            .map(tag => tag.trim())
            .filter(tag => tag.length > 0)
            .sort();
    }

    private contentEquals(a: CardContentValue | undefined, b: CardContentValue | undefined): boolean {
        if (a === b) return true;
        return JSON.stringify(a ?? null) === JSON.stringify(b ?? null);
    }

    toggleGlobalEdit(event: Event): void {
        const input = event.target as HTMLInputElement | null;
        this.applyGlobalEdits = !!input?.checked;
    }

    openAiEnhanceModal(card: UserCardDTO): void {
        this.enhanceCardTarget = card;
        this.showAiEnhanceModal = true;
    }

    closeAiEnhanceModal(): void {
        this.showAiEnhanceModal = false;
        this.enhanceCardTarget = null;
    }

    onAiCardUpdated(updated: UserCardDTO): void {
        const index = this.cards.findIndex(card => card.userCardId === updated.userCardId);
        if (index !== -1) {
            this.cards[index] = updated;
            this.currentCardIndex = index;
        }
        const unfilteredIndex = this.unfilteredCards.findIndex(card => card.userCardId === updated.userCardId);
        if (unfilteredIndex !== -1) {
            this.unfilteredCards[unfilteredIndex] = updated;
        }
        if (this.enhanceCardTarget?.userCardId === updated.userCardId) {
            this.enhanceCardTarget = updated;
        }
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
        this.unfilteredCards = this.unfilteredCards.filter(card => card.userCardId !== cardId);
        if (this.totalCards > 0) {
            this.totalCards = Math.max(0, this.totalCards - 1);
        }

        if (this.currentCardIndex > index) {
            this.currentCardIndex -= 1;
        }
        if (this.currentCardIndex >= this.cards.length) {
            this.currentCardIndex = Math.max(0, this.cards.length - 1);
        }
        this.revealed = false;
    }

    private canDeleteGlobally(card: UserCardDTO): boolean {
        return this.isAuthor && !!this.publicDeck && !card.isCustom;
    }

    canEditGlobally(card: UserCardDTO): boolean {
        return this.isAuthor && !!this.publicDeck && !card.isCustom;
    }
}
