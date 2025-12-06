import { Component, Input } from '@angular/core';

@Component({
    selector: 'app-streak-indicator',
    standalone: true,
    template: `
    <div class="streak-indicator">
      <span class="streak-icon">🔥</span>
      <span class="streak-count">{{ streak }}-day streak</span>
    </div>
  `,
    styles: [
        `
      .streak-indicator {
        display: inline-flex;
        align-items: center;
        gap: var(--spacing-xs);
        padding: var(--spacing-xs) var(--spacing-sm);
        background: var(--color-background);
        border: 1px solid var(--border-color);
        border-radius: var(--border-radius-full);
        font-size: 0.9rem;
        font-weight: 500;
        color: var(--color-text-primary);
        cursor: pointer;
        transition: all 0.2s ease;
      }

      .streak-indicator:hover {
        border-color: var(--border-color-hover);
        box-shadow: var(--shadow-sm);
      }

      .streak-icon {
        font-size: 1rem;
        line-height: 1;
      }

      .streak-count {
        line-height: 1;
      }
    `
    ]
})
export class StreakIndicatorComponent {
    @Input() streak = 0;
}
