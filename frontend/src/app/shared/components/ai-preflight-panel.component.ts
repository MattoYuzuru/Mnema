import { Component, Input } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { AiJobPreflightResponse } from '../../core/models/ai.models';

@Component({
    selector: 'app-ai-preflight-panel',
    standalone: true,
    imports: [NgIf, NgFor],
    template: `
      <section *ngIf="preflight" class="preflight-panel" aria-live="polite">
        <div class="preflight-header">
          <div>
            <p class="preflight-kicker">Analysis ready</p>
            <h3>{{ title }}</h3>
          </div>
          <div class="preflight-badges">
            <span *ngIf="preflight.targetCount !== null && preflight.targetCount !== undefined" class="preflight-badge">
              {{ preflight.targetCount }} planned
            </span>
            <span *ngIf="preflight.queueAhead !== null && preflight.queueAhead !== undefined && preflight.queueAhead > 0" class="preflight-badge warning">
              {{ preflight.queueAhead }} job(s) ahead
            </span>
          </div>
        </div>

        <p *ngIf="preflight.summary" class="preflight-summary">{{ preflight.summary }}</p>

        <div *ngIf="providerLabel(preflight)" class="meta-line provider-line">
          <span class="meta-label">Provider</span>
          <span>{{ providerLabel(preflight) }}</span>
        </div>

        <div class="preflight-meta">
          <div class="meta-card">
            <span class="meta-label">ETA</span>
            <strong>{{ formatEta(preflight.estimatedSecondsRemaining) }}</strong>
          </div>
          <div class="meta-card">
            <span class="meta-label">Estimated cost</span>
            <strong>{{ formatCost(preflight.cost?.estimatedCost, preflight.cost?.estimatedCostCurrency) }}</strong>
          </div>
          <div class="meta-card" *ngIf="preflight.cost?.estimatedInputTokens !== null || preflight.cost?.estimatedOutputTokens !== null">
            <span class="meta-label">Estimated tokens</span>
            <strong>{{ formatTokens(preflight.cost?.estimatedInputTokens, preflight.cost?.estimatedOutputTokens) }}</strong>
          </div>
        </div>

        <div *ngIf="preflight.plannedStages?.length" class="meta-line">
          <span class="meta-label">Stages</span>
          <span>{{ preflight.plannedStages?.join(', ') }}</span>
        </div>

        <div *ngIf="preflight.fields?.length" class="meta-line">
          <span class="meta-label">Fields</span>
          <span>{{ preflight.fields?.join(', ') }}</span>
        </div>

        <div *ngIf="preflight.items?.length" class="planned-items">
          <div class="meta-label">Planned items</div>
          <div class="planned-list">
            <article *ngFor="let item of preflight.items" class="planned-item">
              <div class="planned-title">{{ item.preview || 'Planned item' }}</div>
              <div *ngIf="item.cardId" class="planned-meta">{{ item.cardId }}</div>
              <div *ngIf="item.fields?.length" class="planned-meta">Fields: {{ item.fields?.join(', ') }}</div>
              <div *ngIf="item.plannedStages?.length" class="planned-meta">Stages: {{ item.plannedStages?.join(', ') }}</div>
            </article>
          </div>
        </div>

        <ul *ngIf="preflight.warnings?.length" class="warning-list">
          <li *ngFor="let warning of preflight.warnings">{{ warning }}</li>
        </ul>
      </section>
    `,
    styles: [`
      .preflight-panel {
        margin-top: var(--spacing-md);
        padding: var(--spacing-md);
        border: 1px solid var(--glass-border);
        border-radius: var(--border-radius-lg);
        background:
          linear-gradient(180deg, rgba(255, 255, 255, 0.12), rgba(255, 255, 255, 0.06)),
          color-mix(in srgb, var(--color-card-background) 84%, white 16%);
        box-shadow: var(--shadow-sm);
      }
      .preflight-header {
        display: flex;
        justify-content: space-between;
        gap: var(--spacing-md);
        align-items: flex-start;
      }
      .preflight-header h3 {
        margin: 0;
        font-size: 1rem;
      }
      .preflight-kicker {
        margin: 0 0 4px 0;
        color: var(--color-text-secondary);
        text-transform: uppercase;
        letter-spacing: 0.08em;
        font-size: 0.72rem;
      }
      .preflight-summary {
        margin: var(--spacing-sm) 0 0 0;
        color: var(--color-text-primary);
      }
      .preflight-badges {
        display: flex;
        gap: var(--spacing-xs);
        flex-wrap: wrap;
      }
      .preflight-badge {
        padding: 4px 10px;
        border-radius: 999px;
        background: rgba(255, 255, 255, 0.08);
        border: 1px solid var(--glass-border);
        font-size: 0.8rem;
      }
      .preflight-badge.warning {
        color: var(--color-warning);
      }
      .preflight-meta {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
        gap: var(--spacing-sm);
        margin-top: var(--spacing-md);
      }
      .meta-card {
        border: 1px solid var(--glass-border);
        border-radius: var(--border-radius-md);
        background: rgba(255, 255, 255, 0.05);
        padding: var(--spacing-sm);
      }
      .meta-label {
        display: block;
        color: var(--color-text-secondary);
        font-size: 0.8rem;
        margin-bottom: 4px;
      }
      .meta-line {
        margin-top: var(--spacing-sm);
        color: var(--color-text-primary);
      }
      .provider-line {
        margin-top: var(--spacing-md);
      }
      .planned-items {
        margin-top: var(--spacing-md);
      }
      .planned-list {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
        gap: var(--spacing-sm);
        margin-top: var(--spacing-sm);
      }
      .planned-item {
        border: 1px solid var(--glass-border);
        border-radius: var(--border-radius-md);
        background: rgba(255, 255, 255, 0.05);
        padding: var(--spacing-sm);
      }
      .planned-title {
        font-weight: 600;
      }
      .planned-meta {
        margin-top: 4px;
        color: var(--color-text-secondary);
        font-size: 0.86rem;
        word-break: break-word;
      }
      .warning-list {
        margin: var(--spacing-md) 0 0 0;
        padding-left: 1rem;
        color: var(--color-warning);
      }
    `]
})
export class AiPreflightPanelComponent {
    @Input() title = 'Review before starting';
    @Input() preflight: AiJobPreflightResponse | null = null;

    formatEta(seconds?: number | null): string {
        if (!seconds || seconds <= 0) {
            return 'Unknown';
        }
        if (seconds < 60) {
            return `${seconds}s`;
        }
        const minutes = Math.ceil(seconds / 60);
        if (minutes < 60) {
            return `${minutes} min`;
        }
        const hours = Math.floor(minutes / 60);
        const remMinutes = minutes % 60;
        return remMinutes === 0 ? `${hours} h` : `${hours} h ${remMinutes} min`;
    }

    formatCost(value?: number | null, currency?: string | null): string {
        if (value == null) {
            return 'Unknown';
        }
        return `${value.toFixed(4)} ${currency || 'USD'}`;
    }

    formatTokens(input?: number | null, output?: number | null): string {
        const parts: string[] = [];
        if (input != null) {
            parts.push(`in ${this.formatCompact(input)}`);
        }
        if (output != null) {
            parts.push(`out ${this.formatCompact(output)}`);
        }
        return parts.length ? parts.join(' / ') : 'Unknown';
    }

    private formatCompact(value: number): string {
        if (!Number.isFinite(value)) {
            return '0';
        }
        return Intl.NumberFormat(undefined, { notation: 'compact', maximumFractionDigits: value >= 1000 ? 1 : 0 }).format(value);
    }

    providerLabel(preflight: AiJobPreflightResponse | null): string {
        if (!preflight) {
            return '';
        }
        const provider = preflight.providerAlias || preflight.provider || '';
        const model = preflight.model || '';
        if (provider && model) {
            return `${provider} · ${model}`;
        }
        return provider || model;
    }
}
