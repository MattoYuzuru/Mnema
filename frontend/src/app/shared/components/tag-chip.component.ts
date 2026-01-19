import { Component, Input, Output, EventEmitter } from '@angular/core';
import { NgIf } from '@angular/common';

@Component({
    selector: 'app-tag-chip',
    standalone: true,
    imports: [NgIf],
    template: `
    <span class="tag-chip">
      <span class="tag-text">{{ text }}</span>
      <button
        *ngIf="removable"
        type="button"
        class="tag-remove"
        (click)="onRemove()"
        aria-label="Remove tag"
      >
        ×
      </button>
    </span>
  `,
    styles: [
        `
      .tag-chip {
        display: inline-flex;
        align-items: center;
        gap: var(--spacing-xs);
        padding: 0.25rem 0.7rem;
        background: var(--glass-surface);
        border: 1px solid var(--glass-border);
        border-radius: var(--border-radius-full);
        font-size: 0.85rem;
        color: var(--color-text-primary);
        backdrop-filter: blur(var(--glass-blur));
      }

      .tag-text {
        line-height: 1;
      }

      .tag-remove {
        display: flex;
        align-items: center;
        justify-content: center;
        width: 1rem;
        height: 1rem;
        padding: 0;
        background: transparent;
        border: none;
        border-radius: 50%;
        font-size: 1.2rem;
        line-height: 1;
        color: var(--color-text-muted);
        cursor: pointer;
        transition: all 0.2s ease;
      }

      .tag-remove:hover {
        background: var(--glass-surface-strong);
        color: var(--color-text-primary);
      }
    `
    ]
})
export class TagChipComponent {
    @Input() text = '';
    @Input() removable = false;
    @Output() remove = new EventEmitter<void>();

    onRemove(): void {
        this.remove.emit();
    }
}
