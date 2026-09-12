import { Component, Input, OnInit, inject, ChangeDetectionStrategy } from '@angular/core';

import { MemoryTipsService } from '../../core/services/memory-tips.service';
import { MemoryTip } from '../../core/models/theme.models';

@Component({
    selector: 'app-memory-tip-loader',
    imports: [],
    template: `
    <div class="memory-tip-loader">
      <div class="loader-content">
        <div class="spinner"></div>
        @if (tip) {
          <div class="tip-content">
            <p class="tip-category">{{ tip.category }}</p>
            <p class="tip-text">{{ tip.content }}</p>
          </div>
        }
      </div>
    </div>
    `,
    changeDetection: ChangeDetectionStrategy.Eager,
    styles: [
        `
      .memory-tip-loader {
        position: fixed;
        top: 0;
        left: 0;
        right: 0;
        bottom: 0;
        display: flex;
        align-items: center;
        justify-content: center;
        background: rgba(0, 0, 0, 0.5);
        backdrop-filter: blur(4px);
        z-index: 9999;
      }

      .loader-content {
        display: flex;
        flex-direction: column;
        align-items: center;
        gap: var(--spacing-xl);
        max-width: 32rem;
        padding: var(--spacing-2xl);
        background: var(--color-card-background);
        border-radius: var(--border-radius-lg);
        box-shadow: var(--shadow-lg);
      }

      .spinner {
        width: 3rem;
        height: 3rem;
        border: 3px solid var(--border-color);
        border-top-color: var(--color-primary-accent);
        border-radius: 50%;
        animation: spin 1s linear infinite;
      }

      @keyframes spin {
        to {
          transform: rotate(360deg);
        }
      }

      .tip-content {
        text-align: center;
      }

      .tip-category {
        font-size: 0.85rem;
        font-weight: 600;
        text-transform: uppercase;
        letter-spacing: 0.05em;
        color: var(--color-primary-accent);
        margin: 0 0 var(--spacing-sm) 0;
      }

      .tip-text {
        font-size: 1rem;
        line-height: 1.6;
        color: var(--color-text-primary);
        margin: 0;
      }
    `
    ]
})
export class MemoryTipLoaderComponent implements OnInit {
    private memoryTipsService = inject(MemoryTipsService);

    @Input() themeId?: string;
    tip: MemoryTip | null = null;

    ngOnInit(): void {
        this.tip = this.memoryTipsService.getRandomTip();
    }
}
