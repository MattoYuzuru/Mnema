import { Component, Output, EventEmitter, OnInit } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { Router } from '@angular/router';
import { forkJoin } from 'rxjs';
import { TemplateApiService } from '../../../core/services/template-api.service';
import { CardTemplateDTO } from '../../../core/models/template.models';
import { DeckWizardStateService } from '../deck-wizard-state.service';
import { UserApiService } from '../../../user-api.service';
import { ButtonComponent } from '../../../shared/components/button.component';
import { TemplateCardComponent } from '../../../shared/components/template-card.component';
import { TemplateCreatorModalComponent } from '../template-creator-modal.component';
import { ImportDeckModalComponent } from '../../import/import-deck-modal.component';
import { TranslatePipe } from '../../../shared/pipes/translate.pipe';

type TemplateMode = 'choose' | 'browse';
type TemplateFilter = 'mine' | 'public';

@Component({
    selector: 'app-template-selection-step',
    standalone: true,
    imports: [NgFor, NgIf, ButtonComponent, TemplateCardComponent, TemplateCreatorModalComponent, ImportDeckModalComponent, TranslatePipe],
    template: `
    <div class="step">
      <h2>{{ 'wizard.chooseTemplate' | translate }}</h2>
      <p class="subtitle">{{ mode === 'choose' ? ('wizard.templateSubtitle' | translate) : ('wizard.browseSubtitle' | translate) }}</p>

      <div *ngIf="mode === 'choose'" class="choice-grid">
        <div class="choice-card" (click)="openTemplateCreator()">
          <div class="choice-icon">+</div>
          <h3>{{ 'wizard.createNewTemplate' | translate }}</h3>
          <p>{{ 'wizard.createNewTemplateDesc' | translate }}</p>
        </div>

        <div class="choice-card" (click)="openVisualBuilder()">
          <div class="choice-icon">🎨</div>
          <h3>{{ 'wizard.visualBuilder' | translate }}</h3>
          <p>{{ 'wizard.visualBuilderDesc' | translate }}</p>
        </div>

        <div class="choice-card" (click)="enterBrowseMode()">
          <div class="choice-icon">📚</div>
          <h3>{{ 'wizard.useExistingTemplate' | translate }}</h3>
          <p>{{ 'wizard.useExistingTemplateDesc' | translate }}</p>
        </div>

        <div class="choice-card" (click)="openImportModal()">
          <div class="choice-icon">⬆️</div>
          <h3>{{ 'wizard.importDeck' | translate }}</h3>
          <p>{{ 'wizard.importDeckDesc' | translate }}</p>
        </div>
      </div>

      <div *ngIf="mode === 'browse'">
        <div *ngIf="loading">{{ 'wizard.loadingTemplates' | translate }}</div>

        <div *ngIf="!loading" class="browse-content">
          <div class="filter-tabs">
            <button
              class="filter-tab"
              [class.active]="activeFilter === 'mine'"
              (click)="activeFilter = 'mine'"
            >
              {{ 'wizard.myTemplates' | translate }} ({{ myTemplates.length }})
            </button>
            <button
              class="filter-tab"
              [class.active]="activeFilter === 'public'"
              (click)="activeFilter = 'public'"
            >
              {{ 'wizard.publicTemplates' | translate }} ({{ publicTemplates.length }})
            </button>
          </div>

          <div class="templates-grid">
            <app-template-card
              *ngFor="let t of filteredTemplates"
              [template]="t"
              [selected]="selectedTemplateId === t.templateId"
              [selectLabel]="'templates.select' | translate"
              [selectedLabel]="'templates.selected' | translate"
              (select)="selectTemplate(t.templateId)"
              (click)="selectTemplate(t.templateId)"
            ></app-template-card>
          </div>

          <div *ngIf="filteredTemplates.length === 0" class="empty-state">
            <p>{{ 'wizard.noTemplates' | translate }}</p>
          </div>

          <div class="pagination" *ngIf="totalPages > 1">
            <app-button variant="ghost" size="sm" [disabled]="currentPage === 1" (click)="loadPage(currentPage - 1)">{{ 'wizard.previous' | translate }}</app-button>
            <span class="page-info">{{ 'wizard.page' | translate }} {{ currentPage }} {{ 'wizard.of' | translate }} {{ totalPages }}</span>
            <app-button variant="ghost" size="sm" [disabled]="currentPage >= totalPages" (click)="loadPage(currentPage + 1)">{{ 'wizard.next' | translate }}</app-button>
          </div>
        </div>

        <div class="step-actions">
          <app-button variant="ghost" (click)="mode = 'choose'; selectedTemplateId = null">{{ 'wizard.back' | translate }}</app-button>
          <app-button variant="primary" [disabled]="!selectedTemplateId" (click)="onNext()">{{ 'wizard.nextDeckInfo' | translate }}</app-button>
        </div>
      </div>
    </div>

    <app-template-creator-modal
      *ngIf="showCreator"
      (created)="onTemplateCreated($event)"
      (cancelled)="closeTemplateCreator()"
    ></app-template-creator-modal>

    <app-import-deck-modal
      *ngIf="showImport"
      mode="create"
      [showProfileAction]="true"
      (closed)="closeImportModal()"
      (goProfile)="goToDecks()"
    ></app-import-deck-modal>
  `,
    styles: [`
      .step { display: flex; flex-direction: column; gap: var(--spacing-lg); min-width: 0; }
      h2 { font-size: 1.5rem; font-weight: 600; margin: 0; }
      .subtitle { font-size: 1rem; color: var(--color-text-secondary); margin: 0; }

      .choice-grid {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(min(14.5rem, 100%), 1fr));
        gap: var(--spacing-lg);
        max-width: 100%;
        margin: var(--spacing-xl) auto;
      }

      .choice-card {
        display: grid;
        grid-template-rows: auto auto 1fr;
        justify-items: center;
        align-content: start;
        gap: var(--spacing-sm);
        width: 100%;
        min-width: 0;
        padding: calc(var(--spacing-xxl) + var(--spacing-sm)) var(--spacing-xxl) var(--spacing-xxl);
        background: var(--color-card-background);
        border: 2px solid var(--border-color);
        border-radius: var(--border-radius-lg);
        cursor: pointer;
        transition: all 0.2s;
        min-height: 240px;
        text-align: center;
      }

      .choice-card:hover {
        border-color: #111827;
        background: var(--color-background);
        transform: translateY(-2px);
        box-shadow: 0 4px 12px rgba(0, 0, 0, 0.1);
      }

      .choice-icon {
        width: 80px;
        height: 80px;
        background: #111827;
        color: #fff;
        border-radius: 50%;
        display: flex;
        align-items: center;
        justify-content: center;
        font-size: 2.35rem;
        line-height: 1;
        margin-top: var(--spacing-sm);
      }

      .choice-card h3 {
        font-size: 1.25rem;
        font-weight: 600;
        margin: 0;
        color: var(--color-text-primary);
      }

      .choice-card p {
        font-size: 0.95rem;
        color: var(--color-text-secondary);
        margin: 0;
        line-height: 1.5;
        padding: 0 var(--spacing-md);
      }

      .browse-content {
        display: flex;
        flex-direction: column;
        gap: var(--spacing-lg);
      }

      .filter-tabs {
        display: flex;
        gap: var(--spacing-sm);
        border-bottom: 2px solid var(--border-color);
      }

      .filter-tab {
        padding: var(--spacing-sm) var(--spacing-lg);
        background: none;
        border: none;
        border-bottom: 2px solid transparent;
        margin-bottom: -2px;
        cursor: pointer;
        font-weight: 500;
        color: var(--color-text-secondary);
        transition: all 0.2s;
      }

      .filter-tab:hover {
        color: var(--color-text-primary);
      }

      .filter-tab.active {
        color: #111827;
        border-bottom-color: #111827;
      }

      .templates-grid {
        display: grid;
        grid-template-columns: repeat(auto-fill, minmax(min(14rem, 100%), 1fr));
        gap: var(--spacing-lg);
        margin-top: var(--spacing-lg);
      }

      .empty-state {
        padding: var(--spacing-xxl);
        text-align: center;
        color: var(--color-text-secondary);
      }

      .pagination {
        display: flex;
        align-items: center;
        justify-content: center;
        gap: var(--spacing-md);
        padding-top: var(--spacing-lg);
      }

      .page-info {
        font-size: 0.9rem;
        color: var(--color-text-secondary);
      }

      .step-actions {
        display: flex;
        justify-content: space-between;
        flex-wrap: wrap;
        gap: var(--spacing-sm);
        padding-top: var(--spacing-lg);
        border-top: 1px solid var(--border-color);
      }

      .step-actions app-button {
        flex: 1 1 14rem;
      }

      @media (max-width: 1024px) {
        .choice-grid {
          grid-template-columns: repeat(2, minmax(0, 1fr));
        }
      }

      @media (max-width: 900px) {
        .choice-grid {
          grid-template-columns: 1fr;
          margin: var(--spacing-lg) 0;
        }

        .choice-card {
          padding: calc(var(--spacing-xl) + var(--spacing-xs)) var(--spacing-xl) var(--spacing-xl);
          min-height: 200px;
        }

        .choice-icon {
          width: 64px;
          height: 64px;
          font-size: 2rem;
          margin-top: var(--spacing-xs);
        }

        .filter-tabs {
          flex-wrap: wrap;
        }

        .templates-grid {
          grid-template-columns: 1fr;
        }

        .pagination {
          flex-wrap: wrap;
        }

        .step-actions {
          flex-direction: column;
        }
      }

      @media (max-width: 480px) {
        .choice-card {
          padding: calc(var(--spacing-lg) + var(--spacing-xs)) var(--spacing-lg) var(--spacing-lg);
        }

        .choice-card p {
          padding: 0;
        }
      }
    `]
})
export class TemplateSelectionStepComponent implements OnInit {
    @Output() next = new EventEmitter<void>();

    mode: TemplateMode = 'choose';
    activeFilter: TemplateFilter = 'mine';
    loading = false;
    showCreator = false;
    showImport = false;

    allTemplates: CardTemplateDTO[] = [];
    myTemplates: CardTemplateDTO[] = [];
    publicTemplates: CardTemplateDTO[] = [];
    selectedTemplateId: string | null = null;

    currentPage = 1;
    totalPages = 1;
    pageSize = 10;
    currentUserId: string | null = null;

    constructor(
        private templateApi: TemplateApiService,
        private userApi: UserApiService,
        private wizardState: DeckWizardStateService,
        private router: Router
    ) {}

    ngOnInit(): void {
        this.selectedTemplateId = this.wizardState.getCurrentState().templateId;
    }

    enterBrowseMode(): void {
        void this.router.navigate(['/public-templates'], { queryParams: { from: 'wizard' } });
    }

    loadPage(page: number): void {
        this.loading = true;
        this.currentPage = page;

        forkJoin({
            user: this.userApi.getMe(),
            templates: this.templateApi.getTemplates(page, this.pageSize)
        }).subscribe({
            next: ({ user, templates }) => {
                this.currentUserId = user.id;
                this.allTemplates = templates.content;
                this.totalPages = templates.totalPages;

                this.myTemplates = this.allTemplates.filter(t => t.ownerId === this.currentUserId);
                this.publicTemplates = this.allTemplates.filter(t => t.isPublic && t.ownerId !== this.currentUserId);

                this.loading = false;
            },
            error: () => {
                this.loading = false;
            }
        });
    }

    get filteredTemplates(): CardTemplateDTO[] {
        return this.activeFilter === 'mine' ? this.myTemplates : this.publicTemplates;
    }

    selectTemplate(templateId: string): void {
        this.selectedTemplateId = templateId;
    }

    openTemplateCreator(): void {
        this.showCreator = true;
    }

    openVisualBuilder(): void {
        void this.router.navigate(['/wizard/visual-template-builder']);
    }

    openImportModal(): void {
        this.showImport = true;
    }

    closeImportModal(): void {
        this.showImport = false;
    }

    goToDecks(): void {
        this.showImport = false;
        void this.router.navigate(['/decks']);
    }

    closeTemplateCreator(): void {
        this.showCreator = false;
    }

    onTemplateCreated(template: CardTemplateDTO): void {
        this.showCreator = false;
        this.selectedTemplateId = template.templateId;
        this.mode = 'choose';
        this.wizardState.setTemplateId(this.selectedTemplateId);
        this.next.emit();
    }

    onNext(): void {
        if (this.selectedTemplateId) {
            this.wizardState.setTemplateId(this.selectedTemplateId);
            this.next.emit();
        }
    }
}
