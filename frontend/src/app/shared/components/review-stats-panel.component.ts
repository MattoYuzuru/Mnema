import { Component, DestroyRef, Input, OnChanges, OnInit, SimpleChanges, computed, inject, signal } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ReviewApiService } from '../../core/services/review-api.service';
import { ReviewStatsDailyPoint, ReviewStatsForecastPoint, ReviewStatsHourlyPoint, ReviewStatsResponse } from '../../core/models/review.models';

type StatsMetric = 'reviews' | 'time';
type StatsPreset = 7 | 30 | 90;

interface ChartBarPoint {
    label: string;
    secondaryLabel?: string;
    value: number;
    raw: number;
}

@Component({
    selector: 'app-review-stats-panel',
    standalone: true,
    imports: [NgIf, NgFor],
    template: `
    <section class="stats-panel" [class.compact]="compact">
      <header class="stats-header">
        <div>
          <h2>{{ title }}</h2>
          <p class="stats-subtitle">
            Track retention, speed, and workload trends for {{ userDeckId ? 'this deck' : 'your account' }}.
          </p>
        </div>
        <div class="stats-actions">
          <button type="button" (click)="setMetric('reviews')" [class.active]="metric() === 'reviews'">Reviews</button>
          <button type="button" (click)="setMetric('time')" [class.active]="metric() === 'time'">Time</button>
        </div>
      </header>

      <div class="stats-filters">
        <div class="preset-group">
          <button type="button" (click)="applyPreset(7)" [class.active]="preset() === 7">7d</button>
          <button type="button" (click)="applyPreset(30)" [class.active]="preset() === 30">30d</button>
          <button type="button" (click)="applyPreset(90)" [class.active]="preset() === 90">90d</button>
        </div>
        <label>
          From
          <input type="date" [value]="fromDate()" (change)="onFromDateChange($event)" />
        </label>
        <label>
          To
          <input type="date" [value]="toDate()" (change)="onToDateChange($event)" />
        </label>
        <label>
          Forecast
          <select [value]="forecastDays()" (change)="onForecastDaysChange($event)">
            <option value="14">14d</option>
            <option value="30">30d</option>
            <option value="60">60d</option>
          </select>
        </label>
        <button type="button" class="apply-btn" (click)="reload()">Apply</button>
      </div>

      <div *ngIf="loading()" class="loading">Loading statistics…</div>
      <div *ngIf="error()" class="error" role="alert">{{ error() }}</div>

      <ng-container *ngIf="!loading() && stats() as data">
        <div class="kpi-grid">
          <article class="kpi-card">
            <span class="kpi-label">Reviews</span>
            <strong class="kpi-value">{{ data.overview.reviewCount }}</strong>
            <small>{{ data.overview.reviewsPerDay }} / day</small>
          </article>
          <article class="kpi-card">
            <span class="kpi-label">Success rate</span>
            <strong class="kpi-value">{{ data.overview.successRatePercent }}%</strong>
            <small>Again {{ data.overview.againRatePercent }}%</small>
          </article>
          <article class="kpi-card">
            <span class="kpi-label">Avg response</span>
            <strong class="kpi-value">{{ humanizeMs(data.overview.avgResponseMs) }}</strong>
            <small>Median {{ humanizeMs(data.overview.medianResponseMs) }}</small>
          </article>
          <article class="kpi-card">
            <span class="kpi-label">Queue now</span>
            <strong class="kpi-value">{{ data.queue.dueNow }}</strong>
            <small>Due today {{ data.queue.dueToday }}</small>
          </article>
        </div>

        <article class="chart-card">
          <div class="chart-header">
            <h3>Daily trend</h3>
            <p>{{ metric() === 'reviews' ? 'Daily review volume' : 'Daily study time (seconds)' }}</p>
          </div>
          <div class="chart-scroll">
            <div class="bar-chart daily">
              <button
                type="button"
                class="bar-item"
                *ngFor="let point of dailyBars(); let i = index; trackBy: trackByLabel"
                [style.height.%]="heightPercent(point.value, dailyMax())"
                [attr.aria-label]="point.label + ': ' + point.raw"
                [class.active]="hoverDailyIndex() === i"
                (mouseenter)="hoverDailyIndex.set(i)"
                (focus)="hoverDailyIndex.set(i)"
                (mouseleave)="hoverDailyIndex.set(-1)"
                (blur)="hoverDailyIndex.set(-1)"
              >
                <span class="bar-label">{{ point.secondaryLabel || '' }}</span>
              </button>
            </div>
          </div>
          <p class="chart-footnote" *ngIf="dailyHoverPoint() as hovered">
            {{ hovered.label }}: {{ hovered.raw }} {{ metric() === 'reviews' ? 'reviews' : 'sec' }}
          </p>
        </article>

        <div class="chart-grid">
          <article class="chart-card">
            <div class="chart-header">
              <h3>Hourly load</h3>
              <p>When you usually study</p>
            </div>
            <div class="bar-chart hourly">
              <button
                type="button"
                class="bar-item"
                *ngFor="let point of hourlyBars(); let i = index; trackBy: trackByLabel"
                [style.height.%]="heightPercent(point.value, hourlyMax())"
                [class.active]="hoverHourlyIndex() === i"
                (mouseenter)="hoverHourlyIndex.set(i)"
                (focus)="hoverHourlyIndex.set(i)"
                (mouseleave)="hoverHourlyIndex.set(-1)"
                (blur)="hoverHourlyIndex.set(-1)"
              >
                <span class="bar-label">{{ point.label }}</span>
              </button>
            </div>
            <p class="chart-footnote" *ngIf="hourlyHoverPoint() as hovered">
              {{ hovered.label }}: {{ hovered.raw }} reviews
            </p>
          </article>

          <article class="chart-card">
            <div class="chart-header">
              <h3>Due forecast</h3>
              <p>Expected due cards ahead</p>
            </div>
            <div class="bar-chart forecast">
              <button
                type="button"
                class="bar-item"
                *ngFor="let point of forecastBars(); let i = index; trackBy: trackByLabel"
                [style.height.%]="heightPercent(point.value, forecastMax())"
                [class.active]="hoverForecastIndex() === i"
                (mouseenter)="hoverForecastIndex.set(i)"
                (focus)="hoverForecastIndex.set(i)"
                (mouseleave)="hoverForecastIndex.set(-1)"
                (blur)="hoverForecastIndex.set(-1)"
              >
                <span class="bar-label">{{ point.secondaryLabel || '' }}</span>
              </button>
            </div>
            <p class="chart-footnote" *ngIf="forecastHoverPoint() as hovered">
              {{ hovered.label }}: {{ hovered.raw }} due
            </p>
          </article>
        </div>

        <div class="breakdown-grid">
          <article class="breakdown-card">
            <h3>Answer buttons</h3>
            <ul>
              <li *ngFor="let point of data.ratings; trackBy: trackByRating">
                <span class="label">{{ point.rating }}</span>
                <span class="value">{{ point.reviewCount }} ({{ point.ratioPercent }}%)</span>
              </li>
            </ul>
          </article>
          <article class="breakdown-card">
            <h3>Sources</h3>
            <ul>
              <li *ngFor="let point of data.sources; trackBy: trackBySource">
                <span class="label">{{ point.source }}</span>
                <span class="value">{{ point.reviewCount }} ({{ point.ratioPercent }}%)</span>
              </li>
            </ul>
          </article>
        </div>
      </ng-container>
    </section>
  `,
    styles: [
        `
      .stats-panel {
        display: flex;
        flex-direction: column;
        gap: 1rem;
        padding: 1.25rem;
        border-radius: 1.25rem;
        border: 1px solid color-mix(in srgb, var(--border-color) 75%, transparent);
        background:
          linear-gradient(160deg, color-mix(in srgb, var(--color-card-background) 88%, white 12%), color-mix(in srgb, var(--color-card-background) 94%, transparent)),
          radial-gradient(120% 120% at 0% 0%, color-mix(in srgb, var(--color-primary-accent) 12%, transparent), transparent 60%);
        box-shadow: 0 24px 48px -32px rgba(15, 23, 42, 0.45);
      }

      .stats-panel.compact {
        padding: 1rem;
      }

      .stats-header {
        display: flex;
        justify-content: space-between;
        gap: 1rem;
        align-items: flex-start;
      }

      .stats-header h2 {
        margin: 0;
        font-size: 1.25rem;
      }

      .stats-subtitle {
        margin: 0.25rem 0 0 0;
        color: var(--color-text-secondary);
      }

      .stats-actions,
      .preset-group {
        display: inline-flex;
        gap: 0.375rem;
        padding: 0.25rem;
        border-radius: 999px;
        background: color-mix(in srgb, var(--color-card-background) 80%, transparent);
        border: 1px solid color-mix(in srgb, var(--border-color) 70%, transparent);
      }

      .stats-actions button,
      .preset-group button {
        border: 0;
        border-radius: 999px;
        padding: 0.35rem 0.7rem;
        font-size: 0.82rem;
        background: transparent;
        color: var(--color-text-secondary);
        cursor: pointer;
      }

      .stats-actions button.active,
      .preset-group button.active {
        color: #fff;
        background: linear-gradient(130deg, var(--color-primary-accent), var(--color-secondary-accent));
      }

      .stats-filters {
        display: flex;
        flex-wrap: wrap;
        gap: 0.75rem;
        align-items: end;
      }

      .stats-filters label {
        display: grid;
        gap: 0.3rem;
        font-size: 0.78rem;
        color: var(--color-text-secondary);
      }

      .stats-filters input,
      .stats-filters select {
        border: 1px solid var(--border-color);
        border-radius: 0.6rem;
        padding: 0.35rem 0.5rem;
        background: var(--color-card-background);
        color: var(--color-text-primary);
      }

      .apply-btn {
        border: 1px solid var(--border-color);
        border-radius: 0.65rem;
        padding: 0.45rem 0.8rem;
        background: var(--color-card-background);
        color: var(--color-text-primary);
        cursor: pointer;
      }

      .loading,
      .error {
        border-radius: 0.85rem;
        padding: 0.75rem 0.9rem;
      }

      .loading {
        background: color-mix(in srgb, var(--color-primary-accent) 15%, transparent);
      }

      .error {
        color: #b91c1c;
        background: rgba(239, 68, 68, 0.1);
      }

      .kpi-grid {
        display: grid;
        grid-template-columns: repeat(4, minmax(0, 1fr));
        gap: 0.75rem;
      }

      .kpi-card {
        border-radius: 0.95rem;
        border: 1px solid color-mix(in srgb, var(--border-color) 75%, transparent);
        background: color-mix(in srgb, var(--color-card-background) 85%, transparent);
        padding: 0.75rem;
      }

      .kpi-label {
        display: block;
        font-size: 0.72rem;
        text-transform: uppercase;
        letter-spacing: 0.04em;
        color: var(--color-text-secondary);
      }

      .kpi-value {
        display: block;
        margin-top: 0.25rem;
        font-size: 1.3rem;
        line-height: 1.2;
      }

      .kpi-card small {
        color: var(--color-text-secondary);
        font-size: 0.76rem;
      }

      .chart-grid {
        display: grid;
        grid-template-columns: repeat(2, minmax(0, 1fr));
        gap: 0.75rem;
      }

      .chart-card {
        border-radius: 1rem;
        border: 1px solid color-mix(in srgb, var(--border-color) 75%, transparent);
        background: color-mix(in srgb, var(--color-card-background) 82%, transparent);
        padding: 0.75rem;
        display: flex;
        flex-direction: column;
        gap: 0.65rem;
      }

      .chart-header h3 {
        margin: 0;
        font-size: 1rem;
      }

      .chart-header p {
        margin: 0.25rem 0 0 0;
        color: var(--color-text-secondary);
        font-size: 0.85rem;
      }

      .chart-scroll {
        overflow-x: auto;
      }

      .bar-chart {
        display: grid;
        align-items: end;
        gap: 0.35rem;
        min-height: 168px;
      }

      .bar-chart.daily {
        grid-template-columns: repeat(var(--daily-cols, 30), minmax(14px, 1fr));
        min-width: 420px;
      }

      .bar-chart.hourly {
        grid-template-columns: repeat(24, minmax(0, 1fr));
      }

      .bar-chart.forecast {
        grid-template-columns: repeat(var(--forecast-cols, 30), minmax(10px, 1fr));
        min-width: 340px;
      }

      .bar-item {
        border: 0;
        border-radius: 0.55rem 0.55rem 0.35rem 0.35rem;
        background: linear-gradient(180deg, color-mix(in srgb, var(--color-primary-accent) 75%, #fff 25%), var(--color-primary-accent));
        cursor: pointer;
        position: relative;
        min-height: 8px;
        transition: transform 0.16s ease, filter 0.16s ease;
      }

      .bar-item.active,
      .bar-item:hover,
      .bar-item:focus-visible {
        transform: translateY(-2px);
        filter: brightness(1.08);
      }

      .bar-label {
        position: absolute;
        bottom: -1.1rem;
        left: 50%;
        transform: translateX(-50%);
        font-size: 0.64rem;
        color: var(--color-text-tertiary);
        white-space: nowrap;
      }

      .chart-footnote {
        margin: 0;
        min-height: 1.1rem;
        font-size: 0.82rem;
        color: var(--color-text-secondary);
      }

      .breakdown-grid {
        display: grid;
        grid-template-columns: repeat(2, minmax(0, 1fr));
        gap: 0.75rem;
      }

      .breakdown-card {
        border-radius: 1rem;
        border: 1px solid color-mix(in srgb, var(--border-color) 75%, transparent);
        background: color-mix(in srgb, var(--color-card-background) 84%, transparent);
        padding: 0.8rem;
      }

      .breakdown-card h3 {
        margin: 0 0 0.5rem 0;
      }

      .breakdown-card ul {
        margin: 0;
        padding: 0;
        list-style: none;
        display: grid;
        gap: 0.35rem;
      }

      .breakdown-card li {
        display: flex;
        justify-content: space-between;
        gap: 0.6rem;
        font-size: 0.9rem;
      }

      .breakdown-card .label {
        text-transform: capitalize;
      }

      .breakdown-card .value {
        color: var(--color-text-secondary);
      }

      @media (max-width: 1024px) {
        .kpi-grid {
          grid-template-columns: repeat(2, minmax(0, 1fr));
        }

        .chart-grid,
        .breakdown-grid {
          grid-template-columns: 1fr;
        }
      }

      @media (max-width: 720px) {
        .stats-header {
          flex-direction: column;
        }

        .stats-filters {
          display: grid;
          grid-template-columns: 1fr 1fr;
          align-items: start;
        }

        .stats-filters .preset-group {
          grid-column: span 2;
          justify-content: center;
        }

        .stats-filters .apply-btn {
          grid-column: span 2;
        }

        .kpi-grid {
          grid-template-columns: 1fr;
        }
      }
    `
    ]
})
export class ReviewStatsPanelComponent implements OnInit, OnChanges {
    @Input() userDeckId: string | null = null;
    @Input() title = 'Study analytics';
    @Input() compact = false;

    readonly loading = signal(false);
    readonly error = signal<string | null>(null);
    readonly stats = signal<ReviewStatsResponse | null>(null);
    readonly metric = signal<StatsMetric>('reviews');
    readonly preset = signal<StatsPreset>(30);
    readonly fromDate = signal('');
    readonly toDate = signal('');
    readonly forecastDays = signal(30);

    readonly hoverDailyIndex = signal(-1);
    readonly hoverHourlyIndex = signal(-1);
    readonly hoverForecastIndex = signal(-1);

    readonly dailyBars = computed(() => this.buildDailyBars(this.stats()?.daily ?? [], this.metric()));
    readonly dailyMax = computed(() => this.maxOf(this.dailyBars()));
    readonly hourlyBars = computed(() => this.buildHourlyBars(this.stats()?.hourly ?? []));
    readonly hourlyMax = computed(() => this.maxOf(this.hourlyBars()));
    readonly forecastBars = computed(() => this.buildForecastBars(this.stats()?.forecast ?? []));
    readonly forecastMax = computed(() => this.maxOf(this.forecastBars()));

    readonly dailyHoverPoint = computed(() => this.pointByIndex(this.dailyBars(), this.hoverDailyIndex()));
    readonly hourlyHoverPoint = computed(() => this.pointByIndex(this.hourlyBars(), this.hoverHourlyIndex()));
    readonly forecastHoverPoint = computed(() => this.pointByIndex(this.forecastBars(), this.hoverForecastIndex()));

    private readonly reviewApi = inject(ReviewApiService);
    private readonly destroyRef = inject(DestroyRef);
    private readonly browserTimeZone = Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';
    private requestVersion = 0;

    ngOnInit(): void {
        this.applyPreset(30);
    }

    ngOnChanges(changes: SimpleChanges): void {
        if (changes['userDeckId'] && !changes['userDeckId'].firstChange) {
            this.reload();
        }
    }

    setMetric(next: StatsMetric): void {
        this.metric.set(next);
        this.hoverDailyIndex.set(-1);
    }

    applyPreset(days: StatsPreset): void {
        this.preset.set(days);
        const today = new Date();
        const from = new Date(today);
        from.setDate(today.getDate() - days + 1);
        this.fromDate.set(this.dateToInputValue(from));
        this.toDate.set(this.dateToInputValue(today));
        this.reload();
    }

    onFromDateChange(event: Event): void {
        const value = (event.target as HTMLInputElement).value;
        this.fromDate.set(value);
        this.preset.set(30);
    }

    onToDateChange(event: Event): void {
        const value = (event.target as HTMLInputElement).value;
        this.toDate.set(value);
        this.preset.set(30);
    }

    onForecastDaysChange(event: Event): void {
        const value = Number((event.target as HTMLSelectElement).value);
        if ([14, 30, 60].includes(value)) {
            this.forecastDays.set(value);
        }
    }

    reload(): void {
        const from = this.fromDate();
        const to = this.toDate();
        if (!from || !to) {
            return;
        }

        this.loading.set(true);
        this.error.set(null);
        const version = ++this.requestVersion;

        this.reviewApi.getStats({
            userDeckId: this.userDeckId,
            from,
            to,
            timeZone: this.browserTimeZone,
            forecastDays: this.forecastDays()
        })
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe({
                next: response => {
                    if (version !== this.requestVersion) {
                        return;
                    }
                    this.stats.set(response);
                    this.hoverDailyIndex.set(-1);
                    this.hoverHourlyIndex.set(-1);
                    this.hoverForecastIndex.set(-1);
                },
                error: () => {
                    if (version !== this.requestVersion) {
                        return;
                    }
                    this.error.set('Failed to load statistics');
                    this.stats.set(null);
                },
                complete: () => {
                    if (version !== this.requestVersion) {
                        return;
                    }
                    this.loading.set(false);
                }
            });
    }

    trackByLabel(_: number, item: ChartBarPoint): string {
        return item.label;
    }

    trackByRating(_: number, item: { ratingCode: number }): number {
        return item.ratingCode;
    }

    trackBySource(_: number, item: { source: string }): string {
        return item.source;
    }

    heightPercent(value: number, max: number): number {
        if (max <= 0) {
            return 4;
        }
        return Math.max(4, Math.round((value / max) * 100));
    }

    humanizeMs(ms: number): string {
        if (!ms || ms <= 0) {
            return '0s';
        }
        if (ms < 1000) {
            return `${ms}ms`;
        }
        const seconds = Math.round(ms / 100) / 10;
        return `${seconds}s`;
    }

    private pointByIndex(points: ChartBarPoint[], index: number): ChartBarPoint | null {
        if (index < 0 || index >= points.length) {
            return null;
        }
        return points[index];
    }

    private buildDailyBars(points: ReviewStatsDailyPoint[], metric: StatsMetric): ChartBarPoint[] {
        const showEvery = points.length > 45 ? 8 : points.length > 30 ? 6 : points.length > 14 ? 4 : 2;
        return points.map((point, index) => {
            const value = metric === 'reviews'
                ? point.reviewCount
                : Math.round(point.totalResponseMs / 1000);
            return {
                label: point.date,
                secondaryLabel: index % showEvery === 0 ? point.date.slice(5) : '',
                value,
                raw: value
            };
        });
    }

    private buildHourlyBars(points: ReviewStatsHourlyPoint[]): ChartBarPoint[] {
        const map = new Map<number, ReviewStatsHourlyPoint>();
        for (const point of points) {
            map.set(point.hourOfDay, point);
        }
        const out: ChartBarPoint[] = [];
        for (let hour = 0; hour < 24; hour++) {
            const point = map.get(hour);
            out.push({
                label: `${hour.toString().padStart(2, '0')}:00`,
                value: point?.reviewCount ?? 0,
                raw: point?.reviewCount ?? 0
            });
        }
        return out;
    }

    private buildForecastBars(points: ReviewStatsForecastPoint[]): ChartBarPoint[] {
        const showEvery = points.length > 40 ? 8 : points.length > 20 ? 4 : 2;
        return points.map((point, index) => ({
            label: point.date,
            secondaryLabel: index % showEvery === 0 ? point.date.slice(5) : '',
            value: point.dueCount,
            raw: point.dueCount
        }));
    }

    private maxOf(points: ChartBarPoint[]): number {
        let max = 0;
        for (const point of points) {
            if (point.value > max) {
                max = point.value;
            }
        }
        return max;
    }

    private dateToInputValue(date: Date): string {
        const year = date.getFullYear();
        const month = `${date.getMonth() + 1}`.padStart(2, '0');
        const day = `${date.getDate()}`.padStart(2, '0');
        return `${year}-${month}-${day}`;
    }
}
