import { Component, Input, Output, EventEmitter } from '@angular/core';
import { NgIf, NgFor } from '@angular/common';
import { PublicDeckDTO } from '../../core/models/public-deck.models';
import { UserDeckDTO } from '../../core/models/user-deck.models';
import { TagChipComponent } from './tag-chip.component';
import { ButtonComponent } from './button.component';
import { TranslatePipe } from '../pipes/translate.pipe';

@Component({
    selector: 'app-deck-card',
    standalone: true,
    imports: [NgIf, NgFor, TagChipComponent, ButtonComponent, TranslatePipe],
    template: `
    <div class="deck-card">
      <div class="deck-card-header">
        <div class="deck-card-icon clickable" (click)="open.emit()">
          <img *ngIf="iconUrl" [src]="iconUrl" alt="Deck icon" class="deck-icon-image" />
          <span *ngIf="!iconUrl" class="deck-icon-placeholder">📚</span>
        </div>
        <h3 class="deck-name clickable" (click)="open.emit()">{{ displayName }}</h3>
      </div>

      <div class="deck-card-body">
        <p class="deck-description">{{ formatDescription(displayDescription) }}</p>

        <div *ngIf="tags.length > 0" class="deck-tags">
          <app-tag-chip
            *ngFor="let tag of tags"
            [text]="tag"
          ></app-tag-chip>
        </div>

        <div *ngIf="stats" class="deck-stats">
          <span class="stat">{{ stats.cardCount || 0 }} cards</span>
          <span *ngIf="stats.dueToday" class="stat stat-due">{{ stats.dueToday }} due today</span>
        </div>
      </div>

      <div class="deck-card-actions">
        <app-button
          *ngIf="showFork"
          variant="secondary"
          size="md"
          (click)="fork.emit()"
        >
          {{ 'button.fork' | translate }}
        </app-button>

        <app-button
          *ngIf="showUpdate"
          variant="secondary"
          size="md"
          (click)="update.emit()"
        >
          {{ 'button.update' | translate }}
        </app-button>

        <app-button
          *ngIf="showLearn"
          variant="primary"
          size="md"
          (click)="learn.emit()"
        >
          {{ 'button.learn' | translate }}
        </app-button>

        <app-button
          *ngIf="showBrowse"
          variant="ghost"
          size="md"
          (click)="browse.emit()"
        >
          {{ 'button.browse' | translate }}
        </app-button>
      </div>
    </div>
  `,
    styles: [
        `
      .deck-card {
        display: flex;
        flex-direction: column;
        gap: var(--spacing-md);
        padding: var(--spacing-lg);
        background: var(--glass-surface);
        border: 1px solid var(--glass-border);
        border-radius: var(--border-radius-lg);
        box-shadow: var(--shadow-sm);
        transition: all 0.25s ease;
        min-height: 12rem;
        height: 100%;
        backdrop-filter: blur(var(--glass-blur));
      }

      .deck-card:hover {
        border-color: var(--border-color-hover);
        box-shadow: var(--shadow-md);
        transform: translateY(-2px);
      }

      .clickable {
        cursor: pointer;
        transition: opacity 0.2s;
      }

      .clickable:hover {
        opacity: 0.8;
      }

      .deck-card-header {
        display: flex;
        align-items: center;
        gap: var(--spacing-sm);
      }

      .deck-card-icon {
        flex-shrink: 0;
        width: 2.5rem;
        height: 2.5rem;
        display: flex;
        align-items: center;
        justify-content: center;
        background: var(--glass-surface-strong);
        border-radius: var(--border-radius-md);
        border: 1px solid var(--glass-border);
      }

      .deck-icon-placeholder {
        font-size: 1.25rem;
      }

      .deck-icon-image {
        width: 100%;
        height: 100%;
        object-fit: cover;
        border-radius: var(--border-radius-sm);
      }

      .deck-card-body {
        flex: 1;
        display: flex;
        flex-direction: column;
        gap: var(--spacing-sm);
      }

      .deck-name {
        font-size: 1.1rem;
        font-weight: 600;
        margin: 0;
        flex: 1;
      }

      .deck-description {
        font-size: 0.9rem;
        color: var(--color-text-muted);
        margin: 0;
        line-height: 1.4;
        display: -webkit-box;
        -webkit-line-clamp: 2;
        -webkit-box-orient: vertical;
        overflow: hidden;
        max-height: 2.8rem;
        min-height: 2.8rem;
      }

      .deck-description h1,
      .deck-description h2,
      .deck-description h3 {
        font-size: 0.9rem;
        margin: 0;
        font-weight: 600;
      }

      .deck-description ul {
        margin: 0;
        padding-left: 1rem;
      }

      .deck-description li {
        margin: 0;
      }

      .deck-description pre {
        margin: 0;
        white-space: pre-wrap;
      }

      .deck-description code {
        font-family: inherit;
        font-size: 0.85rem;
        background: var(--glass-surface-strong);
        padding: 0 0.2rem;
        border-radius: var(--border-radius-sm);
      }

      .deck-tags {
        display: flex;
        flex-wrap: wrap;
        gap: var(--spacing-xs);
        margin-top: var(--spacing-xs);
      }

      .deck-stats {
        display: flex;
        gap: var(--spacing-md);
        font-size: 0.85rem;
        color: var(--color-text-muted);
        margin-top: var(--spacing-xs);
      }

      .stat-due {
        color: var(--color-primary-accent);
        font-weight: 500;
      }

      .deck-card-actions {
        display: flex;
        gap: var(--spacing-sm);
        justify-content: flex-end;
        align-items: center;
        margin-top: auto;
      }
    `
    ]
})
export class DeckCardComponent {
    @Input() publicDeck: PublicDeckDTO | null = null;
    @Input() userDeck: UserDeckDTO | null = null;
    @Input() iconUrl: string | null = null;
    @Input() showFork = false;
    @Input() showUpdate = false;
    @Input() showLearn = false;
    @Input() showBrowse = false;
    @Input() stats: { cardCount?: number; dueToday?: number } | null = null;

    @Output() open = new EventEmitter<void>();
    @Output() fork = new EventEmitter<void>();
    @Output() update = new EventEmitter<void>();
    @Output() learn = new EventEmitter<void>();
    @Output() browse = new EventEmitter<void>();

    formatDescription(description?: string): string {
        const text = (description || '').trim();
        if (!text) {
            return '';
        }

        return text
            .replace(/```[\s\S]*?```/g, '')
            .replace(/\[([^\]]+)\]\([^)]+\)/g, '$1')
            .replace(/`([^`]+)`/g, '$1')
            .replace(/\*\*(.+?)\*\*/g, '$1')
            .replace(/\*(.+?)\*/g, '$1')
            .replace(/_(.+?)_/g, '$1')
            .replace(/^#+\s*/gm, '')
            .replace(/^\s*[-*+]\s+/gm, '')
            .replace(/\n+/g, ' ')
            .replace(/\s+/g, ' ')
            .trim();
    }

    get displayName(): string {
        if (this.userDeck) {
            return this.userDeck.displayName;
        }
        if (this.publicDeck) {
            return this.publicDeck.name;
        }
        return '';
    }

    get displayDescription(): string {
        if (this.userDeck) {
            return this.userDeck.displayDescription;
        }
        if (this.publicDeck) {
            return this.publicDeck.description;
        }
        return '';
    }

    get tags(): string[] {
        if (this.publicDeck) {
            return this.publicDeck.tags || [];
        }
        return [];
    }
}
