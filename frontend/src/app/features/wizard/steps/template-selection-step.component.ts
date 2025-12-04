import { Component, Output, EventEmitter, OnInit } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { forkJoin } from 'rxjs';
import { TemplateApiService } from '../../../core/services/template-api.service';
import { CardTemplateDTO } from '../../../core/models/template.models';
import { DeckWizardStateService } from '../deck-wizard-state.service';
import { UserApiService } from '../../../user-api.service';
import { ButtonComponent } from '../../../shared/components/button.component';
import { TemplateCardComponent } from '../../../shared/components/template-card.component';
import { TemplateCreatorModalComponent } from '../template-creator-modal.component';

type TemplateMode = 'choose' | 'create' | 'browse';
type TemplateFilter = 'mine' | 'public';

@Component({
    selector: 'app-template-selection-step',
    standalone: true,
    imports: [NgFor, NgIf, ButtonComponent, TemplateCardComponent, TemplateCreatorModalComponent],
    template: `
    <div class="step">
      <h2>Choose a Template</h2>
      <p class="subtitle">{{ getSubtitle() }}</p>

      <div *ngIf="mode === 'choose'" class="choice-grid">
        <div class="choice-card" (click)="mode = 'create'">
          <div class="choice-icon">+</div>
          <h3>Create New Template</h3>
          <p>Build a custom template from scratch</p>
        </div>

        <div class="choice-card" (click)="enterBrowseMode()">
          <div class="choice-icon">📚</div>
          <h3>Use Existing Template</h3>
          <p>Choose from available templates</p>
        </div>
      </div>

      <div *ngIf="mode === 'create'">
        <div class="info-box">
          Click the button below to open the template creator.
        </div>
        <div class="step-actions">
          <app-button variant="ghost" (click)="mode = 'choose'">Back</app-button>
          <app-button variant="primary" (click)="openTemplateCreator()">Open Template Creator</app-button>
        </div>
      </div>

      <div *ngIf="mode === 'browse'">
        <div *ngIf="loading">Loading templates...</div>

        <div *ngIf="!loading" class="browse-content">
          <div class="filter-tabs">
            <button
              class="filter-tab"
              [class.active]="activeFilter === 'mine'"
              (click)="activeFilter = 'mine'"
            >
              My Templates ({{ myTemplates.length }})
            </button>
            <button
              class="filter-tab"
              [class.active]="activeFilter === 'public'"
              (click)="activeFilter = 'public'"
            >
              Public Templates ({{ publicTemplates.length }})
            </button>
          </div>

          <div class="templates-grid">
            <app-template-card
              *ngFor="let t of filteredTemplates"
              [template]="t"
              [selected]="selectedTemplateId === t.templateId"
              (click)="selectTemplate(t.templateId)"
            ></app-template-card>
          </div>

          <div *ngIf="filteredTemplates.length === 0" class="empty-state">
            <p>No templates found in this category.</p>
          </div>

          <div class="pagination" *ngIf="totalPages > 1">
            <app-button variant="ghost" size="sm" [disabled]="currentPage === 1" (click)="loadPage(currentPage - 1)">Previous</app-button>
            <span class="page-info">Page {{ currentPage }} of {{ totalPages }}</span>
            <app-button variant="ghost" size="sm" [disabled]="currentPage >= totalPages" (click)="loadPage(currentPage + 1)">Next</app-button>
          </div>
        </div>

        <div class="step-actions">
          <app-button variant="ghost" (click)="mode = 'choose'; selectedTemplateId = null">Back</app-button>
          <app-button variant="primary" [disabled]="!selectedTemplateId" (click)="onNext()">Next: Deck Info</app-button>
        </div>
      </div>
    </div>

    <app-template-creator-modal
      *ngIf="showCreator"
      (created)="onTemplateCreated($event)"
      (cancelled)="closeTemplateCreator()"
    ></app-template-creator-modal>
  `,
    styles: [`
      .step { display: flex; flex-direction: column; gap: var(--spacing-lg); }
      h2 { font-size: 1.5rem; font-weight: 600; margin: 0; }
      .subtitle { font-size: 1rem; color: var(--color-text-secondary); margin: 0; }

      .choice-grid {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
        gap: var(--spacing-lg);
        max-width: 800px;
        margin: var(--spacing-xl) auto;
      }

      .choice-card {
        display: flex;
        flex-direction: column;
        align-items: center;
        justify-content: center;
        padding: var(--spacing-xxl);
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
        font-size: 2.5rem;
        margin-bottom: var(--spacing-lg);
      }

      .choice-card h3 {
        font-size: 1.25rem;
        font-weight: 600;
        margin: 0 0 var(--spacing-sm) 0;
        color: var(--color-text-primary);
      }

      .choice-card p {
        font-size: 0.95rem;
        color: var(--color-text-secondary);
        margin: 0;
      }

      .info-box {
        padding: var(--spacing-lg);
        background: var(--color-background);
        border: 1px solid var(--border-color);
        border-radius: var(--border-radius-md);
        color: var(--color-text-secondary);
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
        grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
        gap: var(--spacing-md);
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
        padding-top: var(--spacing-lg);
        border-top: 1px solid var(--border-color);
      }
    `]
})
export class TemplateSelectionStepComponent implements OnInit {
    @Output() next = new EventEmitter<void>();

    mode: TemplateMode = 'choose';
    activeFilter: TemplateFilter = 'mine';
    loading = false;
    showCreator = false;

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
        private wizardState: DeckWizardStateService
    ) {}

    ngOnInit(): void {
        this.selectedTemplateId = this.wizardState.getCurrentState().templateId;
    }

    getSubtitle(): string {
        switch (this.mode) {
            case 'choose': return 'How would you like to get started?';
            case 'create': return 'Build your own custom template';
            case 'browse': return 'Select a template that matches your learning style';
            default: return '';
        }
    }

    enterBrowseMode(): void {
        this.mode = 'browse';
        this.loadPage(1);
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
