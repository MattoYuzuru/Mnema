import { Component, OnInit } from '@angular/core';
import { DatePipe, NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { forkJoin } from 'rxjs';
import { AdminApiService, AdminOverview, AdminUserEntry } from '../../core/services/admin-api.service';
import { ModerationReportEntry, ModerationReportStats, ReportApiService } from '../../core/services/report-api.service';
import { ButtonComponent } from '../../shared/components/button.component';
import { TranslatePipe } from '../../shared/pipes/translate.pipe';

@Component({
    standalone: true,
    selector: 'app-admin-panel',
    imports: [NgIf, NgFor, FormsModule, DatePipe, ButtonComponent, TranslatePipe],
    template: `
    <section class="admin-page">
      <div class="admin-shell">
        <header class="hero glass-strong">
          <div>
            <div class="eyebrow">{{ 'adminPanel.eyebrow' | translate }}</div>
            <h1>{{ 'adminPanel.title' | translate }}</h1>
            <p>{{ 'adminPanel.subtitle' | translate }}</p>
          </div>
          <app-button variant="ghost" size="sm" (click)="reloadAll()">
            {{ 'adminPanel.refresh' | translate }}
          </app-button>
        </header>

        <section class="stats-grid">
          <article class="stat-card glass" *ngFor="let stat of statCards()">
            <div class="stat-value">{{ stat.value }}</div>
            <div class="stat-label">{{ stat.label | translate }}</div>
            <p class="stat-hint">{{ stat.hint | translate }}</p>
          </article>
        </section>

        <section class="chart-grid" *ngIf="reportStats">
          <article class="chart-card glass" *ngFor="let chart of charts()">
            <div class="panel-head compact">
              <div>
                <h2>{{ chart.title | translate }}</h2>
                <p>{{ chart.subtitle | translate }}</p>
              </div>
            </div>

            <div class="chart-body">
              <div class="pie-chart" [style.background]="pieBackground(chart.slices)"></div>
              <div class="chart-legend">
                <div class="legend-item" *ngFor="let slice of chart.slices; let index = index">
                  <span class="legend-dot" [style.background]="chartColor(index)"></span>
                  <span class="legend-label">{{ chart.label(slice.key) | translate }}</span>
                  <strong>{{ slice.count }}</strong>
                </div>
              </div>
            </div>
          </article>
        </section>

        <section class="content-grid">
          <article class="panel glass-strong">
            <div class="panel-head">
              <div>
                <h2>{{ 'adminPanel.userSearchTitle' | translate }}</h2>
                <p>{{ 'adminPanel.userSearchHint' | translate }}</p>
              </div>
            </div>

            <div class="search-row">
              <input
                type="search"
                [(ngModel)]="searchQuery"
                (keydown.enter)="searchUsers()"
                [placeholder]="'adminPanel.userSearchPlaceholder' | translate"
                [attr.aria-label]="'adminPanel.userSearchPlaceholder' | translate"
              />
              <app-button variant="primary" size="sm" (click)="searchUsers()" [disabled]="searchLoading">
                {{ searchLoading ? ('adminPanel.loading' | translate) : ('adminPanel.search' | translate) }}
              </app-button>
            </div>

            <p *ngIf="searchError" class="error-text">{{ searchError }}</p>

            <div class="user-list" *ngIf="searchResults.length > 0; else emptySearch">
              <article class="user-card" *ngFor="let user of searchResults">
                <div class="user-main">
                  <img *ngIf="user.avatarUrl" class="avatar" [src]="user.avatarUrl" [alt]="user.username" />
                  <div *ngIf="!user.avatarUrl" class="avatar placeholder">{{ user.username.charAt(0).toUpperCase() }}</div>
                  <div>
                    <div class="user-name-row">
                      <strong>{{ user.username }}</strong>
                      <span class="badge" *ngIf="user.admin">{{ 'adminPanel.adminBadge' | translate }}</span>
                      <span class="badge banned" *ngIf="user.banned">{{ 'adminPanel.bannedBadge' | translate }}</span>
                    </div>
                    <div class="user-email">{{ user.email }}</div>
                  </div>
                </div>
                <div class="user-actions">
                  <app-button *ngIf="user.canPromoteToAdmin" variant="secondary" size="sm" (click)="grantAdmin(user)">
                    {{ 'adminPanel.grantAdmin' | translate }}
                  </app-button>
                  <app-button *ngIf="user.bannableByCurrentAdmin" variant="ghost" size="sm" tone="danger" (click)="banUser(user)">
                    {{ 'adminPanel.banUser' | translate }}
                  </app-button>
                  <app-button *ngIf="user.unbannableByCurrentAdmin" variant="ghost" size="sm" (click)="unbanUser(user)">
                    {{ 'adminPanel.unbanUser' | translate }}
                  </app-button>
                </div>
              </article>
            </div>

            <ng-template #emptySearch>
              <div class="empty-panel">{{ 'adminPanel.userSearchEmpty' | translate }}</div>
            </ng-template>
          </article>

          <article class="panel glass">
            <div class="panel-head">
              <div>
                <h2>{{ 'adminPanel.adminsTitle' | translate }}</h2>
                <p>{{ 'adminPanel.adminsHint' | translate }}</p>
              </div>
            </div>

            <div class="user-list" *ngIf="admins.length > 0; else noAdmins">
              <article class="user-card compact" *ngFor="let user of admins">
                <div class="user-main">
                  <img *ngIf="user.avatarUrl" class="avatar" [src]="user.avatarUrl" [alt]="user.username" />
                  <div *ngIf="!user.avatarUrl" class="avatar placeholder">{{ user.username.charAt(0).toUpperCase() }}</div>
                  <div>
                    <div class="user-name-row">
                      <strong>{{ user.username }}</strong>
                      <span class="badge" *ngIf="!user.adminGrantedBy">{{ 'adminPanel.rootAdmin' | translate }}</span>
                      <span class="badge accent" *ngIf="user.assignedByCurrentAdmin">{{ 'adminPanel.assignedByYou' | translate }}</span>
                    </div>
                    <div class="user-email">{{ user.email }}</div>
                    <div class="meta-text" *ngIf="user.adminGrantedAt">
                      {{ 'adminPanel.since' | translate }} {{ user.adminGrantedAt | date:'mediumDate' }}
                    </div>
                  </div>
                </div>
                <app-button *ngIf="user.revocableByCurrentAdmin" variant="ghost" size="sm" tone="danger" (click)="revokeAdmin(user)">
                  {{ 'adminPanel.revokeAdmin' | translate }}
                </app-button>
              </article>
            </div>

            <ng-template #noAdmins>
              <div class="empty-panel">{{ 'adminPanel.noAdmins' | translate }}</div>
            </ng-template>
          </article>

          <article class="panel glass">
            <div class="panel-head">
              <div>
                <h2>{{ 'adminPanel.bannedTitle' | translate }}</h2>
                <p>{{ 'adminPanel.bannedHint' | translate }}</p>
              </div>
            </div>

            <div class="search-row compact">
              <input
                type="search"
                [(ngModel)]="bannedQuery"
                (keydown.enter)="reloadBanned()"
                [placeholder]="'adminPanel.bannedSearchPlaceholder' | translate"
                [attr.aria-label]="'adminPanel.bannedSearchPlaceholder' | translate"
              />
              <app-button variant="ghost" size="sm" (click)="reloadBanned()">
                {{ 'adminPanel.search' | translate }}
              </app-button>
            </div>

            <div class="user-list" *ngIf="bannedUsers.length > 0; else noBanned">
              <article class="user-card compact" *ngFor="let user of bannedUsers">
                <div class="user-main">
                  <img *ngIf="user.avatarUrl" class="avatar" [src]="user.avatarUrl" [alt]="user.username" />
                  <div *ngIf="!user.avatarUrl" class="avatar placeholder">{{ user.username.charAt(0).toUpperCase() }}</div>
                  <div>
                    <div class="user-name-row">
                      <strong>{{ user.username }}</strong>
                      <span class="badge banned">{{ 'adminPanel.bannedBadge' | translate }}</span>
                    </div>
                    <div class="user-email">{{ user.email }}</div>
                    <div class="meta-text" *ngIf="user.bannedAt">
                      {{ 'adminPanel.bannedSince' | translate }} {{ user.bannedAt | date:'mediumDate' }}
                    </div>
                    <div class="meta-text" *ngIf="user.banReason">
                      {{ user.banReason }}
                    </div>
                  </div>
                </div>
                <app-button *ngIf="user.unbannableByCurrentAdmin" variant="ghost" size="sm" (click)="unbanUser(user)">
                  {{ 'adminPanel.unbanUser' | translate }}
                </app-button>
              </article>
            </div>

            <ng-template #noBanned>
              <div class="empty-panel">{{ 'adminPanel.noBanned' | translate }}</div>
            </ng-template>

            <div class="panel-actions" *ngIf="bannedHasMore">
              <app-button variant="ghost" size="sm" (click)="loadMoreBanned()" [disabled]="bannedLoading">
                {{ bannedLoading ? ('adminPanel.loading' | translate) : ('adminPanel.loadMore' | translate) }}
              </app-button>
            </div>
          </article>

          <article class="panel glass reports">
            <div class="panel-head">
              <div>
                <h2>{{ 'adminPanel.openReportsTitle' | translate }}</h2>
                <p>{{ 'adminPanel.openReportsHint' | translate }}</p>
              </div>
            </div>

            <div class="report-list" *ngIf="openReports.length > 0; else noOpenReports">
              <article class="report-card" *ngFor="let report of openReports">
                <div class="report-copy">
                  <div class="user-name-row">
                    <strong>{{ report.targetTitle }}</strong>
                    <span class="badge">{{ reportTargetLabel(report.targetType) | translate }}</span>
                    <span class="badge accent">{{ reportReasonLabel(report.reason) | translate }}</span>
                  </div>
                  <div class="meta-text">{{ 'adminPanel.reportedBy' | translate }} {{ report.reporterUsername }}</div>
                  <div class="meta-text">{{ report.createdAt | date:'medium' }}</div>
                  <div class="meta-text" *ngIf="report.details">{{ report.details }}</div>
                </div>
                <app-button variant="primary" size="sm" (click)="closeReport(report)">
                  {{ 'adminPanel.closeReport' | translate }}
                </app-button>
              </article>
            </div>

            <ng-template #noOpenReports>
              <div class="empty-panel">{{ 'adminPanel.noOpenReports' | translate }}</div>
            </ng-template>
          </article>

          <article class="panel glass">
            <div class="panel-head">
              <div>
                <h2>{{ 'adminPanel.closedReportsTitle' | translate }}</h2>
                <p>{{ 'adminPanel.closedReportsHint' | translate }}</p>
              </div>
            </div>

            <div class="report-list" *ngIf="closedReports.length > 0; else noClosedReports">
              <article class="report-card compact" *ngFor="let report of closedReports">
                <div class="report-copy">
                  <div class="user-name-row">
                    <strong>{{ report.targetTitle }}</strong>
                    <span class="badge">{{ reportTargetLabel(report.targetType) | translate }}</span>
                  </div>
                  <div class="meta-text">
                    {{ 'adminPanel.closedBy' | translate }} {{ report.closedByUsername || '—' }}
                  </div>
                  <div class="meta-text">{{ (report.closedAt || report.updatedAt) | date:'medium' }}</div>
                  <div class="meta-text" *ngIf="report.resolutionNote">{{ report.resolutionNote }}</div>
                </div>
              </article>
            </div>

            <ng-template #noClosedReports>
              <div class="empty-panel">{{ 'adminPanel.noClosedReports' | translate }}</div>
            </ng-template>
          </article>

          <article class="panel glass">
            <div class="panel-head">
              <div>
                <h2>{{ 'adminPanel.resolversTitle' | translate }}</h2>
                <p>{{ 'adminPanel.resolversHint' | translate }}</p>
              </div>
            </div>

            <div class="resolver-list" *ngIf="reportStats?.resolverBreakdown?.length; else noResolvers">
              <article class="resolver-card" *ngFor="let resolver of reportStats!.resolverBreakdown">
                <div>
                  <strong>{{ resolver.username }}</strong>
                  <div class="meta-text">{{ 'adminPanel.resolvedReports' | translate }}</div>
                </div>
                <span class="resolver-count">{{ resolver.resolvedCount }}</span>
              </article>
            </div>

            <ng-template #noResolvers>
              <div class="empty-panel">{{ 'adminPanel.noResolvers' | translate }}</div>
            </ng-template>
          </article>

          <article class="panel glass rules">
            <h2>{{ 'adminPanel.rulesTitle' | translate }}</h2>
            <ul>
              <li>{{ 'adminPanel.ruleDirectOnly' | translate }}</li>
              <li>{{ 'adminPanel.ruleNoAdminContent' | translate }}</li>
              <li>{{ 'adminPanel.ruleNoNestedDowngrade' | translate }}</li>
            </ul>
          </article>
        </section>
      </div>
    </section>
  `,
    styles: [`
      .admin-page {
        padding: var(--spacing-xl) var(--spacing-md);
      }

      .admin-shell {
        max-width: 1200px;
        margin: 0 auto;
        display: flex;
        flex-direction: column;
        gap: var(--spacing-lg);
      }

      .hero {
        display: flex;
        justify-content: space-between;
        gap: var(--spacing-lg);
        padding: var(--spacing-xl);
        border-radius: var(--border-radius-xl);
      }

      .hero h1 {
        margin: 0 0 var(--spacing-xs) 0;
        font-size: clamp(2rem, 3vw, 2.8rem);
      }

      .hero p, .eyebrow {
        margin: 0;
        color: var(--color-text-secondary);
      }

      .eyebrow {
        margin-bottom: var(--spacing-sm);
        text-transform: uppercase;
        letter-spacing: 0.12em;
        font-size: 0.75rem;
      }

      .stats-grid, .content-grid {
        display: grid;
        gap: var(--spacing-lg);
      }

      .stats-grid {
        grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
      }

      .chart-grid {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
        gap: var(--spacing-lg);
      }

      .content-grid {
        grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
      }

      .stat-card, .panel, .chart-card {
        border-radius: var(--border-radius-xl);
        padding: var(--spacing-lg);
      }

      .stat-value {
        font-size: 2rem;
        font-weight: 700;
      }

      .stat-label {
        margin-top: var(--spacing-xs);
        font-weight: 600;
      }

      .stat-hint, .meta-text {
        margin: var(--spacing-xs) 0 0 0;
        color: var(--color-text-secondary);
        font-size: 0.9rem;
      }

      .panel-head {
        margin-bottom: var(--spacing-md);
      }

      .panel-head.compact {
        margin-bottom: var(--spacing-sm);
      }

      .panel-head h2 {
        margin: 0 0 var(--spacing-xs) 0;
      }

      .panel-head p,
      .empty-panel,
      .user-email,
      .error-text {
        margin: 0;
        color: var(--color-text-secondary);
      }

      .search-row {
        display: grid;
        grid-template-columns: minmax(0, 1fr) auto;
        gap: var(--spacing-sm);
        margin-bottom: var(--spacing-md);
      }

      .search-row input {
        min-width: 0;
        border: 1px solid var(--border-color);
        background: rgba(255, 255, 255, 0.08);
        color: var(--color-text-primary);
        border-radius: var(--border-radius-lg);
        padding: 0.85rem 1rem;
      }

      .search-row.compact {
        margin-top: calc(var(--spacing-sm) * -1);
      }

      .user-list {
        display: flex;
        flex-direction: column;
        gap: var(--spacing-sm);
      }

      .user-card {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: var(--spacing-md);
        padding: var(--spacing-md);
        border-radius: var(--border-radius-lg);
        background: rgba(255, 255, 255, 0.05);
        border: 1px solid rgba(255, 255, 255, 0.08);
      }

      .user-card.compact {
        padding: 0.85rem 1rem;
      }

      .user-main, .user-name-row, .user-actions, .queue-grid {
        display: flex;
      }

      .user-main {
        align-items: center;
        gap: var(--spacing-md);
        min-width: 0;
      }

      .user-name-row {
        gap: var(--spacing-xs);
        flex-wrap: wrap;
        align-items: center;
        margin-bottom: 0.2rem;
      }

      .user-actions {
        gap: var(--spacing-xs);
        flex-wrap: wrap;
        justify-content: flex-end;
      }

      .avatar {
        width: 2.75rem;
        height: 2.75rem;
        border-radius: 999px;
        object-fit: cover;
        flex-shrink: 0;
      }

      .avatar.placeholder {
        display: grid;
        place-items: center;
        background: linear-gradient(135deg, rgba(34, 197, 94, 0.26), rgba(59, 130, 246, 0.26));
        font-weight: 700;
      }

      .badge {
        padding: 0.2rem 0.55rem;
        border-radius: 999px;
        font-size: 0.75rem;
        background: rgba(59, 130, 246, 0.12);
        color: var(--color-text-secondary);
      }

      .badge.accent {
        background: rgba(16, 185, 129, 0.14);
      }

      .badge.banned {
        background: rgba(239, 68, 68, 0.14);
      }

      .chart-body {
        display: grid;
        grid-template-columns: auto minmax(0, 1fr);
        gap: var(--spacing-lg);
        align-items: center;
      }

      .pie-chart {
        width: 132px;
        height: 132px;
        border-radius: 50%;
        border: 8px solid rgba(255, 255, 255, 0.08);
        box-shadow: inset 0 0 0 10px rgba(15, 23, 42, 0.18);
      }

      .chart-legend,
      .report-list,
      .resolver-list {
        display: flex;
        flex-direction: column;
        gap: var(--spacing-sm);
      }

      .legend-item,
      .resolver-card {
        display: flex;
        align-items: center;
        gap: var(--spacing-sm);
        justify-content: space-between;
      }

      .legend-dot {
        width: 0.8rem;
        height: 0.8rem;
        border-radius: 999px;
        flex-shrink: 0;
      }

      .legend-label {
        flex: 1;
        color: var(--color-text-secondary);
      }

      .report-card,
      .resolver-card {
        display: flex;
        justify-content: space-between;
        gap: var(--spacing-md);
        padding: var(--spacing-md);
        border-radius: var(--border-radius-lg);
        background: rgba(255, 255, 255, 0.05);
        border: 1px solid rgba(255, 255, 255, 0.08);
      }

      .report-copy {
        min-width: 0;
      }

      .resolver-count {
        font-size: 1.4rem;
        font-weight: 700;
      }

      .panel-actions {
        margin-top: var(--spacing-md);
      }

      .reports, .rules {
        grid-column: span 1;
      }

      .rules ul {
        margin: var(--spacing-sm) 0 0 1rem;
        color: var(--color-text-secondary);
      }

      .rules li + li {
        margin-top: var(--spacing-xs);
      }

      @media (max-width: 720px) {
        .hero,
        .user-card {
          flex-direction: column;
          align-items: stretch;
        }

        .search-row {
          grid-template-columns: 1fr;
        }

        .chart-body {
          grid-template-columns: 1fr;
          justify-items: center;
        }

        .user-actions {
          justify-content: flex-start;
        }
      }
  `]
})
export class AdminPanelComponent implements OnInit {
    overview: AdminOverview | null = null;
    searchQuery = '';
    bannedQuery = '';
    searchResults: AdminUserEntry[] = [];
    admins: AdminUserEntry[] = [];
    bannedUsers: AdminUserEntry[] = [];
    searchLoading = false;
    bannedLoading = false;
    searchError = '';
    bannedPage = 1;
    bannedHasMore = false;
    reportStats: ModerationReportStats | null = null;
    openReports: ModerationReportEntry[] = [];
    closedReports: ModerationReportEntry[] = [];

    constructor(
        private adminApi: AdminApiService,
        private reportApi: ReportApiService
    ) {}

    ngOnInit(): void {
        this.reloadAll();
    }

    reloadAll(): void {
        forkJoin({
            overview: this.adminApi.getOverview(),
            search: this.adminApi.searchUsers('', 1, 8),
            admins: this.adminApi.getAdmins('', 1, 20),
            banned: this.adminApi.getBannedUsers('', 1, 20),
            reportStats: this.reportApi.getStats(),
            openReports: this.reportApi.getOpenReports(1, 12),
            closedReports: this.reportApi.getClosedReports(1, 8)
        }).subscribe({
            next: ({ overview, search, admins, banned, reportStats, openReports, closedReports }) => {
                this.overview = overview;
                this.searchResults = search.content;
                this.admins = admins.content;
                this.bannedUsers = banned.content;
                this.bannedPage = 1;
                this.bannedHasMore = !banned.last;
                this.reportStats = reportStats;
                this.openReports = openReports.content;
                this.closedReports = closedReports.content;
            },
            error: err => {
                this.searchError = err?.error?.message || err?.error?.reason || 'Failed to load admin panel';
            }
        });
    }

    searchUsers(): void {
        this.searchLoading = true;
        this.searchError = '';
        this.adminApi.searchUsers(this.searchQuery, 1, 20).subscribe({
            next: page => {
                this.searchResults = page.content;
                this.searchLoading = false;
            },
            error: err => {
                this.searchLoading = false;
                this.searchError = err?.error?.message || err?.error?.reason || 'Search failed';
            }
        });
    }

    reloadBanned(): void {
        this.bannedLoading = true;
        this.adminApi.getBannedUsers(this.bannedQuery, 1, 20).subscribe({
            next: page => {
                this.bannedUsers = page.content;
                this.bannedPage = 1;
                this.bannedHasMore = !page.last;
                this.bannedLoading = false;
            },
            error: () => {
                this.bannedLoading = false;
            }
        });
    }

    loadMoreBanned(): void {
        if (this.bannedLoading || !this.bannedHasMore) return;
        this.bannedLoading = true;
        const nextPage = this.bannedPage + 1;
        this.adminApi.getBannedUsers(this.bannedQuery, nextPage, 20).subscribe({
            next: page => {
                this.bannedUsers = [...this.bannedUsers, ...page.content];
                this.bannedPage = nextPage;
                this.bannedHasMore = !page.last;
                this.bannedLoading = false;
            },
            error: () => {
                this.bannedLoading = false;
            }
        });
    }

    grantAdmin(user: AdminUserEntry): void {
        this.adminApi.grantAdmin(user.id).subscribe({ next: () => this.reloadAll() });
    }

    revokeAdmin(user: AdminUserEntry): void {
        this.adminApi.revokeAdmin(user.id).subscribe({ next: () => this.reloadAll() });
    }

    banUser(user: AdminUserEntry): void {
        const reason = window.prompt('Ban reason (optional)', '') ?? '';
        this.adminApi.banUser(user.id, reason).subscribe({ next: () => this.reloadAll() });
    }

    unbanUser(user: AdminUserEntry): void {
        this.adminApi.unbanUser(user.id).subscribe({ next: () => this.reloadAll() });
    }

    closeReport(report: ModerationReportEntry): void {
        const resolutionNote = window.prompt('Resolution note (optional)', '') ?? '';
        this.reportApi.closeReport(report.reportId, resolutionNote).subscribe({ next: () => this.reloadAll() });
    }

    statCards(): Array<{ value: number; label: string; hint: string }> {
        return [
            {
                value: this.overview?.totalAdmins ?? 0,
                label: 'adminPanel.totalAdmins',
                hint: 'adminPanel.totalAdminsHint'
            },
            {
                value: this.overview?.bannedUsers ?? 0,
                label: 'adminPanel.totalBanned',
                hint: 'adminPanel.totalBannedHint'
            },
            {
                value: this.reportStats?.totalOpen ?? 0,
                label: 'adminPanel.openReportsShort',
                hint: 'adminPanel.openReportsHint'
            },
            {
                value: this.reportStats?.totalClosed ?? 0,
                label: 'adminPanel.closedReportsShort',
                hint: 'adminPanel.closedReportsHint'
            }
        ];
    }

    charts(): Array<{
        title: string;
        subtitle: string;
        slices: Array<{ key: string; count: number }>;
        label: (key: string) => string;
    }> {
        return [
            {
                title: 'adminPanel.statusChartTitle',
                subtitle: 'adminPanel.statusChartHint',
                slices: [
                    { key: 'OPEN', count: this.reportStats?.totalOpen ?? 0 },
                    { key: 'CLOSED', count: this.reportStats?.totalClosed ?? 0 }
                ],
                label: key => key === 'OPEN' ? 'adminPanel.reportStatusOpen' : 'adminPanel.reportStatusClosed'
            },
            {
                title: 'adminPanel.targetsChartTitle',
                subtitle: 'adminPanel.targetsChartHint',
                slices: this.reportStats?.targetBreakdown ?? [],
                label: key => this.reportTargetLabel(key)
            },
            {
                title: 'adminPanel.reasonsChartTitle',
                subtitle: 'adminPanel.reasonsChartHint',
                slices: this.reportStats?.reasonBreakdown ?? [],
                label: key => this.reportReasonLabel(key)
            }
        ];
    }

    pieBackground(slices: Array<{ key: string; count: number }>): string {
        const activeSlices = slices.filter(slice => slice.count > 0);
        const total = activeSlices.reduce((sum, slice) => sum + slice.count, 0);
        if (total === 0) {
            return 'conic-gradient(rgba(148, 163, 184, 0.18) 0deg 360deg)';
        }

        let current = 0;
        const segments = activeSlices.map((slice, index) => {
            const start = (current / total) * 360;
            current += slice.count;
            const end = (current / total) * 360;
            return `${this.chartColor(index)} ${start}deg ${end}deg`;
        });
        return `conic-gradient(${segments.join(', ')})`;
    }

    chartColor(index: number): string {
        const palette = ['#38bdf8', '#f59e0b', '#10b981', '#fb7185', '#a78bfa', '#f97316', '#22c55e'];
        return palette[index % palette.length];
    }

    reportTargetLabel(targetType: string): string {
        switch (targetType) {
            case 'DECK':
                return 'adminPanel.deckReportsTitle';
            case 'CARD':
                return 'adminPanel.cardReportsTitle';
            default:
                return 'adminPanel.templateReportsTitle';
        }
    }

    reportReasonLabel(reason: string): string {
        switch (reason) {
            case 'INAPPROPRIATE_LANGUAGE':
                return 'reports.reason.inappropriateLanguage';
            case 'OFFENSIVE_CONTENT':
                return 'reports.reason.offensiveContent';
            case 'FACTUAL_ERROR':
                return 'reports.reason.factualError';
            case 'MISLEADING_METADATA':
                return 'reports.reason.misleadingMetadata';
            case 'SPAM':
                return 'reports.reason.spam';
            case 'BROKEN_FORMATTING':
                return 'reports.reason.brokenFormatting';
            default:
                return 'reports.reason.other';
        }
    }
}
