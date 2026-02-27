import { Component, computed, signal } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { ActivatedRoute, Router } from '@angular/router';
import { forkJoin, firstValueFrom } from 'rxjs';
import { ButtonComponent } from '../../shared/components/button.component';
import { CardApiService } from '../../core/services/card-api.service';
import { DeckApiService } from '../../core/services/deck-api.service';
import { PublicDeckApiService } from '../../core/services/public-deck-api.service';
import { TemplateApiService } from '../../core/services/template-api.service';
import { DuplicateGroup, UserCardDTO } from '../../core/models/user-card.models';
import { FieldTemplateDTO } from '../../core/models/template.models';
import { UserApiService } from '../../user-api.service';
import { I18nService } from '../../core/services/i18n.service';
import { TranslatePipe } from '../../shared/pipes/translate.pipe';

@Component({
    selector: 'app-duplicate-review-page',
    standalone: true,
    imports: [NgIf, NgFor, ButtonComponent, TranslatePipe],
    template: `
    <div class="dupe-page">
      <header class="dupe-header glass">
        <div>
          <h1>{{ 'duplicateReview.title' | translate }}</h1>
          <p>{{ 'duplicateReview.subtitle' | translate }}</p>
        </div>
        <app-button variant="ghost" (click)="goBack()">{{ 'duplicateReview.backToDeck' | translate }}</app-button>
      </header>

      <div *ngIf="loading()" class="state-card glass">{{ 'duplicateReview.loading' | translate }}</div>
      <div *ngIf="!loading() && error()" class="state-card glass error">{{ error() }}</div>
      <div *ngIf="!loading() && !error() && groups().length === 0" class="state-card glass">{{ 'duplicateReview.empty' | translate }}</div>

      <div *ngIf="!loading() && groups().length > 0" class="dupe-layout">
        <aside class="groups-panel glass">
          <div class="groups-title">{{ 'duplicateReview.groupsTitle' | translate }}</div>
          <button
            type="button"
            class="group-row"
            *ngFor="let group of groups(); let i = index"
            [class.active]="i === currentGroupIndex()"
            (click)="selectGroup(i)"
          >
            <span>{{ 'duplicateReview.group' | translate }} {{ i + 1 }}</span>
            <small>{{ matchTypeLabel(group.matchType) }} · {{ group.size }} {{ 'duplicateReview.cards' | translate }}</small>
          </button>
        </aside>

        <section class="group-cards glass-strong" *ngIf="currentGroup() as group">
          <div class="cards-header">
            <div>
              <h2>{{ 'duplicateReview.group' | translate }} {{ currentGroupIndex() + 1 }}</h2>
              <p>{{ matchTypeLabel(group.matchType) }} · {{ 'duplicateReview.confidence' | translate }} {{ formatConfidence(group.confidence, group.matchType) }}</p>
            </div>
          </div>

          <div class="cards-strip">
            <article class="dupe-card" *ngFor="let card of group.cards">
              <div class="dupe-card-header">
                <strong>{{ card.userCardId.slice(0, 8) }}</strong>
                <button class="toggle-delete" type="button" (click)="toggleDelete(card.userCardId)">
                  {{ isMarked(card.userCardId) ? ('duplicateReview.undoDelete' | translate) : ('duplicateReview.deleteCard' | translate) }}
                </button>
              </div>
              <div class="dupe-fields">
                <div class="dupe-field" *ngFor="let field of displayFields()">
                  <span class="dupe-label">{{ fieldLabel(field) }}</span>
                  <span class="dupe-value">{{ readField(card, field) }}</span>
                </div>
              </div>
            </article>
          </div>

          <div class="footer-actions">
            <app-button variant="secondary" (click)="nextGroup()" [disabled]="!hasNextGroup()">{{ 'duplicateReview.nextGroup' | translate }}</app-button>
            <app-button variant="primary" (click)="finishDeletion()" [disabled]="saving() || pendingDelete().size === 0">
              {{ saving() ? ('duplicateReview.applying' | translate) : ('duplicateReview.finishDeletion' | translate) }}
            </app-button>
          </div>
        </section>
      </div>

      <div *ngIf="showScopePrompt()" class="modal-overlay" (click)="showScopePrompt.set(false)">
        <div class="modal-content" (click)="$event.stopPropagation()">
          <h3>{{ 'duplicateReview.scopeTitle' | translate }}</h3>
          <p>{{ 'duplicateReview.scopeMessage' | translate }}</p>
          <div class="scope-actions">
            <app-button variant="secondary" (click)="confirmScope('local')" [disabled]="saving()">{{ 'duplicateReview.scopeLocal' | translate }}</app-button>
            <app-button variant="primary" (click)="confirmScope('global')" [disabled]="saving()">{{ 'duplicateReview.scopeGlobal' | translate }}</app-button>
          </div>
        </div>
      </div>
    </div>
  `,
    styles: [`
      .dupe-page { max-width: 92rem; margin: 0 auto; display: grid; gap: var(--spacing-lg); }
      .dupe-header { display: flex; justify-content: space-between; align-items: center; padding: var(--spacing-lg); border-radius: var(--border-radius-lg); border: 1px solid var(--glass-border); }
      .dupe-header h1 { margin: 0; }
      .dupe-header p { margin: 0.35rem 0 0; color: var(--color-text-secondary); }
      .state-card { padding: var(--spacing-lg); border: 1px solid var(--glass-border); border-radius: var(--border-radius-lg); }
      .state-card.error { color: var(--color-error); }
      .dupe-layout { display: grid; grid-template-columns: minmax(220px, 280px) minmax(0, 1fr); gap: var(--spacing-lg); }
      .groups-panel {
        padding: var(--spacing-md);
        border: 1px solid var(--glass-border);
        border-radius: var(--border-radius-lg);
        display: grid;
        gap: var(--spacing-sm);
        align-content: start;
        max-height: 72vh;
        overflow-y: auto;
        scrollbar-width: thin;
        scrollbar-color: var(--glass-border-strong) transparent;
      }
      .groups-panel::-webkit-scrollbar { width: 8px; }
      .groups-panel::-webkit-scrollbar-track { background: transparent; }
      .groups-panel::-webkit-scrollbar-thumb {
        background: var(--glass-border-strong);
        border-radius: 999px;
        border: 2px solid transparent;
        background-clip: padding-box;
      }
      .groups-panel::-webkit-scrollbar-thumb:hover { background: var(--border-color-hover); }
      .groups-title { font-weight: 700; margin-bottom: var(--spacing-xs); }
      .group-row { text-align: left; border: 1px solid var(--glass-border); border-radius: var(--border-radius-md); background: var(--glass-surface); color: var(--color-text-primary); padding: 0.55rem 0.7rem; cursor: pointer; display: grid; gap: 0.15rem; }
      .group-row.active { border-color: var(--color-primary-accent); box-shadow: var(--shadow-sm); }
      .group-row small { color: var(--color-text-secondary); }
      .group-cards { border: 1px solid var(--glass-border); border-radius: var(--border-radius-lg); padding: var(--spacing-md); display: grid; gap: var(--spacing-md); }
      .cards-header h2 { margin: 0; }
      .cards-header p { margin: 0.2rem 0 0; color: var(--color-text-secondary); }
      .cards-strip {
        display: flex;
        gap: var(--spacing-md);
        overflow-x: auto;
        padding-bottom: var(--spacing-xs);
        scrollbar-width: thin;
        scrollbar-color: var(--glass-border-strong) transparent;
      }
      .cards-strip::-webkit-scrollbar { height: 8px; }
      .cards-strip::-webkit-scrollbar-track { background: transparent; }
      .cards-strip::-webkit-scrollbar-thumb {
        background: var(--glass-border-strong);
        border-radius: 999px;
        border: 2px solid transparent;
        background-clip: padding-box;
      }
      .cards-strip::-webkit-scrollbar-thumb:hover { background: var(--border-color-hover); }
      .dupe-card { min-width: min(360px, 80vw); border: 1px solid var(--glass-border); border-radius: var(--border-radius-md); padding: var(--spacing-sm); background: var(--color-card-background); display: grid; gap: var(--spacing-sm); }
      .dupe-card-header { display: flex; justify-content: space-between; align-items: center; gap: var(--spacing-sm); }
      .toggle-delete { border: 1px solid var(--glass-border); background: var(--glass-surface); color: var(--color-text-primary); border-radius: 999px; padding: 0.35rem 0.8rem; cursor: pointer; }
      .dupe-fields { display: grid; gap: 0.35rem; }
      .dupe-field { display: grid; gap: 0.15rem; }
      .dupe-label { font-size: 0.78rem; color: var(--color-text-secondary); text-transform: uppercase; letter-spacing: 0.05em; }
      .dupe-value { white-space: pre-wrap; word-break: break-word; color: var(--color-text-primary); }
      .footer-actions { display: flex; justify-content: flex-end; gap: var(--spacing-sm); }
      .modal-overlay { position: fixed; inset: 0; display: flex; align-items: center; justify-content: center; background: rgba(8, 12, 22, 0.55); backdrop-filter: blur(12px) saturate(140%); z-index: 1200; }
      .modal-content { width: min(92%, 480px); border: 1px solid var(--glass-border); border-radius: var(--border-radius-lg); padding: var(--spacing-lg); background: var(--color-surface-solid); }
      .modal-content h3 { margin: 0; }
      .modal-content p { margin: var(--spacing-sm) 0 0; color: var(--color-text-secondary); }
      .scope-actions { margin-top: var(--spacing-lg); display: flex; justify-content: flex-end; gap: var(--spacing-sm); }
      @media (max-width: 960px) {
        .dupe-layout { grid-template-columns: 1fr; }
        .groups-panel { max-height: 240px; }
      }
    `]
})
export class DuplicateReviewPageComponent {
    userDeckId = '';
    loading = signal(true);
    saving = signal(false);
    error = signal('');
    showScopePrompt = signal(false);
    groups = signal<DuplicateGroup[]>([]);
    currentGroupIndex = signal(0);
    pendingDelete = signal<Set<string>>(new Set());
    fields = signal<string[]>([]);
    templateFields = signal<FieldTemplateDTO[]>([]);
    isAuthor = signal(false);

    currentGroup = computed(() => this.groups()[this.currentGroupIndex()] ?? null);

    constructor(
        private route: ActivatedRoute,
        private router: Router,
        private cardApi: CardApiService,
        private deckApi: DeckApiService,
        private publicDeckApi: PublicDeckApiService,
        private templateApi: TemplateApiService,
        private userApi: UserApiService,
        private i18n: I18nService
    ) {
        this.bootstrap();
    }

    private bootstrap(): void {
        this.userDeckId = this.route.snapshot.paramMap.get('userDeckId') || '';
        if (!this.userDeckId) {
            this.error.set(this.i18n.translate('duplicateReview.error.deckMissing'));
            this.loading.set(false);
            return;
        }

        this.deckApi.getUserDeck(this.userDeckId).subscribe({
            next: deck => {
                if (!deck.publicDeckId) {
                    this.error.set(this.i18n.translate('duplicateReview.error.publicDeckMissing'));
                    this.loading.set(false);
                    return;
                }

                forkJoin({
                    publicDeck: this.publicDeckApi.getPublicDeck(deck.publicDeckId, deck.currentVersion),
                    me: this.userApi.getMe()
                }).subscribe({
                    next: ({ publicDeck, me }) => {
                        this.isAuthor.set(publicDeck.authorId === me.id);
                        this.templateApi.getTemplate(publicDeck.templateId, deck.templateVersion || null).subscribe({
                            next: template => {
                                this.templateFields.set(template.fields || []);
                                const requested = (this.route.snapshot.queryParamMap.get('fields') || '')
                                    .split(',')
                                    .map(item => item.trim())
                                    .filter(Boolean);
                                const fallback = (template.fields || [])
                                    .filter(field => ['text', 'rich_text', 'markdown', 'cloze'].includes(field.fieldType))
                                    .map(field => field.name);
                                const fields = requested.length > 0 ? requested : fallback;
                                this.fields.set(fields);
                                this.loadGroups();
                            },
                            error: () => {
                                this.error.set(this.i18n.translate('duplicateReview.error.templateLoad'));
                                this.loading.set(false);
                            }
                        });
                    },
                    error: () => {
                        this.error.set(this.i18n.translate('duplicateReview.error.contextLoad'));
                        this.loading.set(false);
                    }
                });
            },
            error: () => {
                this.error.set(this.i18n.translate('duplicateReview.error.deckLoad'));
                this.loading.set(false);
            }
        });
    }

    private loadGroups(): void {
        const fields = this.fields();
        if (fields.length === 0) {
            this.error.set(this.i18n.translate('duplicateReview.error.noTextFields'));
            this.loading.set(false);
            return;
        }

        this.cardApi.getDuplicateGroups(this.userDeckId, fields, 50, 10, true, 0.92).subscribe({
            next: groups => {
                this.groups.set(groups || []);
                this.currentGroupIndex.set(0);
                this.loading.set(false);
            },
            error: () => {
                this.error.set(this.i18n.translate('duplicateReview.error.groupsLoad'));
                this.loading.set(false);
            }
        });
    }

    goBack(): void {
        this.router.navigate(['/decks', this.userDeckId]);
    }

    selectGroup(index: number): void {
        this.currentGroupIndex.set(index);
    }

    nextGroup(): void {
        const next = this.currentGroupIndex() + 1;
        if (next < this.groups().length) {
            this.currentGroupIndex.set(next);
        }
    }

    hasNextGroup(): boolean {
        return this.currentGroupIndex() < this.groups().length - 1;
    }

    toggleDelete(cardId: string): void {
        this.pendingDelete.update(set => {
            const next = new Set(set);
            if (next.has(cardId)) {
                next.delete(cardId);
            } else {
                next.add(cardId);
            }
            return next;
        });
    }

    isMarked(cardId: string): boolean {
        return this.pendingDelete().has(cardId);
    }

    displayFields(): string[] {
        return this.fields();
    }

    fieldLabel(fieldName: string): string {
        const field = this.templateFields().find(item => item.name === fieldName);
        return field?.label || fieldName;
    }

    readField(card: UserCardDTO, field: string): string {
        const value = (card.effectiveContent || {})[field] as unknown;
        if (value == null) {
            return '—';
        }
        if (typeof value === 'string') {
            return value.trim() || '—';
        }
        if (typeof value === 'object' && 'mediaId' in (value as Record<string, unknown>)) {
            return `[media] ${(value as { mediaId?: string }).mediaId || ''}`.trim();
        }
        return String(value);
    }

    formatConfidence(value?: number, matchType?: string): string {
        if (matchType !== 'semantic') {
            return this.i18n.translate('duplicateReview.confidenceExact');
        }
        if (typeof value !== 'number') {
            return '—';
        }
        const percent = Math.round(value * 1000) / 10;
        return `${percent}%`;
    }

    matchTypeLabel(matchType?: string): string {
        return matchType === 'semantic'
            ? this.i18n.translate('duplicateReview.matchSemantic')
            : this.i18n.translate('duplicateReview.matchExact');
    }

    finishDeletion(): void {
        if (this.pendingDelete().size === 0 || this.saving()) {
            return;
        }
        if (this.isAuthor()) {
            this.showScopePrompt.set(true);
            return;
        }
        void this.applyDeletion('local');
    }

    confirmScope(scope: 'local' | 'global'): void {
        this.showScopePrompt.set(false);
        void this.applyDeletion(scope);
    }

    private async applyDeletion(scope: 'local' | 'global'): Promise<void> {
        const ids = Array.from(this.pendingDelete());
        if (ids.length === 0) {
            return;
        }
        this.saving.set(true);
        this.error.set('');
        const operationId = scope === 'global' ? this.generateRequestId() : undefined;
        try {
            for (const cardId of ids) {
                await firstValueFrom(this.cardApi.deleteUserCard(this.userDeckId, cardId, scope, operationId));
            }
            this.pendingDelete.set(new Set());
            this.loadGroups();
        } catch {
            this.error.set(this.i18n.translate('duplicateReview.error.deleteSelected'));
        } finally {
            this.saving.set(false);
        }
    }

    private generateRequestId(): string {
        if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
            return crypto.randomUUID();
        }
        return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
            const r = Math.random() * 16 | 0;
            const v = c === 'x' ? r : (r & 0x3 | 0x8);
            return v.toString(16);
        });
    }
}
