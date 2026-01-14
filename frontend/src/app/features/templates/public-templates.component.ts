import { Component, OnInit } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { NgIf, NgFor } from '@angular/common';
import { forkJoin } from 'rxjs';
import { TemplateApiService } from '../../core/services/template-api.service';
import { UserApiService } from '../../user-api.service';
import { DeckWizardStateService } from '../wizard/deck-wizard-state.service';
import { CardTemplateDTO } from '../../core/models/template.models';
import { TemplateCardComponent } from '../../shared/components/template-card.component';
import { MemoryTipLoaderComponent } from '../../shared/components/memory-tip-loader.component';
import { EmptyStateComponent } from '../../shared/components/empty-state.component';
import { ButtonComponent } from '../../shared/components/button.component';
import { TranslatePipe } from '../../shared/pipes/translate.pipe';

type TemplateFilter = 'all' | 'mine' | 'public';

@Component({
    selector: 'app-public-templates',
    standalone: true,
    imports: [
        NgIf,
        NgFor,
        TemplateCardComponent,
        MemoryTipLoaderComponent,
        EmptyStateComponent,
        ButtonComponent,
        TranslatePipe
    ],
    template: `
    <app-memory-tip-loader *ngIf="loading"></app-memory-tip-loader>

    <div *ngIf="!loading" class="templates-catalog">
      <header class="page-header">
        <div>
          <h1>{{ 'publicTemplates.title' | translate }}</h1>
          <p class="subtitle">{{ 'publicTemplates.subtitle' | translate }}</p>
        </div>
      </header>

      <div class="filter-tabs">
        <button class="filter-tab" [class.active]="activeFilter === 'all'" (click)="activeFilter = 'all'">
          {{ 'publicTemplates.filterAll' | translate }} ({{ templates.length }})
        </button>
        <button class="filter-tab" [class.active]="activeFilter === 'mine'" (click)="activeFilter = 'mine'">
          {{ 'publicTemplates.filterMine' | translate }} ({{ myTemplates.length }})
        </button>
        <button class="filter-tab" [class.active]="activeFilter === 'public'" (click)="activeFilter = 'public'">
          {{ 'publicTemplates.filterPublic' | translate }} ({{ publicTemplates.length }})
        </button>
      </div>

      <div *ngIf="filteredTemplates.length > 0" class="templates-grid">
        <app-template-card
          *ngFor="let template of filteredTemplates"
          [template]="template"
          [showActions]="true"
          [showViewButton]="true"
          [showSelectButton]="fromWizard"
          [showVisibility]="true"
          [viewLabel]="'templates.view' | translate"
          [selectLabel]="'publicTemplates.select' | translate"
          [publicLabel]="'templates.public' | translate"
          [privateLabel]="'templates.private' | translate"
          (view)="openTemplate(template.templateId)"
          (select)="selectTemplate(template.templateId)"
          (click)="openTemplate(template.templateId)"
        ></app-template-card>
      </div>

      <app-empty-state
        *ngIf="filteredTemplates.length === 0"
        icon="T"
        [title]="'templates.noTemplates' | translate"
        [description]="'templates.noTemplatesDescription' | translate"
      ></app-empty-state>

      <div *ngIf="templates.length > 0 && hasMore" class="load-more-container">
        <app-button
          variant="secondary"
          [disabled]="loadingMore"
          (click)="loadMore()"
        >
          {{ (loadingMore ? 'templates.loading' : 'templates.loadMore') | translate }}
        </app-button>
      </div>
    </div>
  `,
    styles: [`
      .templates-catalog {
        max-width: 72rem;
        margin: 0 auto;
      }

      .page-header {
        display: flex;
        align-items: flex-end;
        justify-content: space-between;
        margin-bottom: var(--spacing-lg);
      }

      .page-header h1 {
        font-size: 2rem;
        margin: 0 0 var(--spacing-xs) 0;
      }

      .subtitle {
        margin: 0;
        color: var(--color-text-secondary);
      }

      .filter-tabs {
        display: flex;
        gap: var(--spacing-sm);
        border-bottom: 2px solid var(--border-color);
        margin-bottom: var(--spacing-lg);
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
        grid-template-columns: repeat(1, minmax(0, 1fr));
        gap: var(--spacing-md);
      }

      .load-more-container {
        display: flex;
        justify-content: center;
        padding: var(--spacing-xl) 0;
      }

      @media (min-width: 768px) {
        .templates-grid {
          grid-template-columns: repeat(2, minmax(0, 1fr));
        }
      }

      @media (min-width: 1100px) {
        .templates-grid {
          grid-template-columns: repeat(3, minmax(0, 1fr));
        }
      }

      @media (max-width: 768px) {
        .templates-catalog {
          padding: 0 var(--spacing-md);
        }

        .page-header {
          flex-direction: column;
          align-items: flex-start;
          gap: var(--spacing-sm);
        }

        .page-header h1 {
          font-size: 1.5rem;
        }

        .filter-tabs {
          flex-wrap: wrap;
        }
      }
    `]
})
export class PublicTemplatesComponent implements OnInit {
    loading = true;
    loadingMore = false;
    templates: CardTemplateDTO[] = [];
    page = 1;
    pageSize = 12;
    hasMore = false;
    fromWizard = false;
    activeFilter: TemplateFilter = 'all';
    currentUserId: string | null = null;

    constructor(
        private templateApi: TemplateApiService,
        private userApi: UserApiService,
        private wizardState: DeckWizardStateService,
        private router: Router,
        private route: ActivatedRoute
    ) {}

    ngOnInit(): void {
        this.fromWizard = this.route.snapshot.queryParamMap.get('from') === 'wizard';
        this.loadTemplates();
    }

    private loadTemplates(): void {
        this.loading = true;
        forkJoin({
            user: this.userApi.getMe(),
            templates: this.templateApi.getTemplates(this.page, this.pageSize, 'all')
        }).subscribe({
            next: ({ user, templates }) => {
                this.currentUserId = user.id;
                this.templates = templates.content;
                this.hasMore = !templates.last;
                this.loading = false;
            },
            error: () => {
                this.loading = false;
            }
        });
    }

    loadMore(): void {
        if (this.loadingMore || !this.hasMore) {
            return;
        }

        this.loadingMore = true;
        this.page += 1;

        this.templateApi.getTemplates(this.page, this.pageSize, 'all').subscribe({
            next: page => {
                this.templates = [...this.templates, ...page.content];
                this.hasMore = !page.last;
                this.loadingMore = false;
            },
            error: () => {
                this.loadingMore = false;
                this.page -= 1;
            }
        });
    }

    get myTemplates(): CardTemplateDTO[] {
        if (!this.currentUserId) {
            return [];
        }
        return this.templates.filter(t => t.ownerId === this.currentUserId);
    }

    get publicTemplates(): CardTemplateDTO[] {
        return this.templates.filter(t => t.isPublic);
    }

    get filteredTemplates(): CardTemplateDTO[] {
        if (this.activeFilter === 'mine') {
            return this.myTemplates;
        }
        if (this.activeFilter === 'public') {
            return this.publicTemplates;
        }
        return this.templates;
    }

    openTemplate(templateId: string): void {
        const queryParams = this.fromWizard ? { from: 'wizard' } : {};
        void this.router.navigate(['/templates', templateId], { queryParams });
    }

    selectTemplate(templateId: string): void {
        if (!this.fromWizard) {
            return;
        }
        this.wizardState.setTemplateId(templateId);
        this.wizardState.setCurrentStep(2);
        void this.router.navigate(['/create-deck']);
    }
}
