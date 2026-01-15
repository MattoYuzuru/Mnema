import { Component, OnInit } from '@angular/core';
import { Router } from '@angular/router';
import { NgIf, NgFor } from '@angular/common';
import { TemplateApiService } from '../../core/services/template-api.service';
import { CardTemplateDTO } from '../../core/models/template.models';
import { TemplateCardComponent } from '../../shared/components/template-card.component';
import { MemoryTipLoaderComponent } from '../../shared/components/memory-tip-loader.component';
import { EmptyStateComponent } from '../../shared/components/empty-state.component';
import { ButtonComponent } from '../../shared/components/button.component';
import { TranslatePipe } from '../../shared/pipes/translate.pipe';

@Component({
    selector: 'app-templates-list',
    standalone: true,
    imports: [NgIf, NgFor, TemplateCardComponent, MemoryTipLoaderComponent, EmptyStateComponent, ButtonComponent, TranslatePipe],
    template: `
    <app-memory-tip-loader *ngIf="loading"></app-memory-tip-loader>

    <div *ngIf="!loading" class="templates-list-page">
      <header class="page-header">
        <div class="title-row">
          <h1>{{ 'templates.title' | translate }}</h1>
          <a
            class="help-link"
            href="https://github.com/MattoYuzuru/Mnema/wiki#templates"
            target="_blank"
            rel="noopener noreferrer"
            aria-label="Templates guide"
          >?</a>
        </div>
      </header>

      <div *ngIf="templates.length > 0" class="templates-grid">
        <app-template-card
          *ngFor="let template of templates"
          [template]="template"
          [showActions]="true"
          [showViewButton]="true"
          [showSelectButton]="false"
          [showVisibility]="true"
          [viewLabel]="'templates.view' | translate"
          [publicLabel]="'templates.public' | translate"
          [privateLabel]="'templates.private' | translate"
          (view)="openTemplate(template.templateId)"
          (click)="openTemplate(template.templateId)"
        ></app-template-card>
      </div>

      <div *ngIf="templates.length > 0 && hasMore" class="load-more-container">
        <app-button
          variant="secondary"
          [disabled]="loadingMore"
          (click)="loadMore()"
        >
          {{ (loadingMore ? 'templates.loading' : 'templates.loadMore') | translate }}
        </app-button>
      </div>

      <app-empty-state
        *ngIf="templates.length === 0"
        icon="T"
        [title]="'templates.noTemplates' | translate"
        [description]="'templates.noTemplatesDescription' | translate"
      ></app-empty-state>
    </div>
  `,
    styles: [`
      .templates-list-page {
        max-width: 72rem;
        margin: 0 auto;
      }

      .page-header {
        margin-bottom: var(--spacing-xl);
      }

      .title-row {
        display: flex;
        align-items: center;
        gap: var(--spacing-sm);
      }

      .page-header h1 {
        font-size: 2rem;
        margin: 0;
      }

      .help-link {
        display: inline-flex;
        align-items: center;
        justify-content: center;
        width: 1.25rem;
        height: 1.25rem;
        border-radius: 50%;
        border: 1px solid var(--border-color);
        color: var(--color-text-secondary);
        text-decoration: none;
        font-size: 0.75rem;
        font-weight: 600;
        transition: all 0.2s ease;
      }

      .help-link:hover {
        color: var(--color-text-primary);
        border-color: var(--color-text-primary);
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
        .templates-list-page {
          padding: 0 var(--spacing-md);
        }

        .page-header h1 {
          font-size: 1.5rem;
        }
      }
    `]
})
export class TemplatesListComponent implements OnInit {
    loading = true;
    loadingMore = false;
    templates: CardTemplateDTO[] = [];
    page = 1;
    pageSize = 12;
    hasMore = false;

    constructor(private templateApi: TemplateApiService, private router: Router) {}

    ngOnInit(): void {
        this.loadTemplates();
    }

    private loadTemplates(): void {
        this.templateApi.getTemplates(this.page, this.pageSize, 'mine').subscribe({
            next: page => {
                this.templates = page.content;
                this.hasMore = !page.last;
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

        this.templateApi.getTemplates(this.page, this.pageSize, 'mine').subscribe({
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

    openTemplate(templateId: string): void {
        void this.router.navigate(['/templates', templateId]);
    }
}
