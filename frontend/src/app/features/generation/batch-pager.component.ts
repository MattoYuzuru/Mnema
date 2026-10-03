import { ChangeDetectionStrategy, Component, ElementRef, computed, input, output, viewChildren } from '@angular/core';

import { artifactStatus, positionLabel, StatusShape } from './generation-view';
import { ArtifactSummary } from './generation.models';

/**
 * «‹ 3 из 10 ›» with one dot per material. The shape of a dot (●◐○✕✓) says its status without colour and its accessible
 * name says it in words; the current dot carries `aria-current="step"`. The dots are one tab stop (roving tabindex):
 * ArrowLeft/ArrowRight (and Up/Down) move between materials, Home and End jump to the first and the last. Selecting
 * never scrolls or moves focus anywhere but onto the dot the user just chose.
 */
@Component({
    selector: 'app-batch-pager',
    template: `
      <nav class="pager" aria-label="Материалы партии">
        <button type="button" class="step" aria-label="Предыдущий материал" [attr.aria-disabled]="index() <= 0 ? 'true' : null" (click)="move(-1)"><span aria-hidden="true">‹</span></button>
        <p class="position">{{ position() }}</p>
        <button type="button" class="step" aria-label="Следующий материал" [attr.aria-disabled]="index() >= artifacts().length - 1 ? 'true' : null" (click)="move(1)"><span aria-hidden="true">›</span></button>
        <ol class="dots" (keydown)="onKeydown($event)">
          @for (artifact of artifacts(); track artifact.artifactId; let position = $index) {
            <li>
              <button type="button" #dot class="dot" [attr.data-status]="status(artifact).shape" [attr.tabindex]="position === tabStop() ? 0 : -1"
                [attr.aria-current]="position === index() ? 'step' : null" [attr.aria-label]="label(artifact, position)"
                (click)="picked.emit(artifact.artifactId)">
                <svg viewBox="0 0 20 20" width="20" height="20" aria-hidden="true" focusable="false">
                  <circle cx="10" cy="10" r="7" fill="none" stroke="currentColor" stroke-width="1.75" />
                  @switch (status(artifact).shape) {
                    @case ('ready') { <circle cx="10" cy="10" r="4.5" fill="currentColor" /> }
                    @case ('writing') { <path d="M10 5.5a4.5 4.5 0 0 0 0 9z" fill="currentColor" /> }
                    @case ('failed') { <path d="M7.25 7.25l5.5 5.5m0-5.5l-5.5 5.5" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" /> }
                    @case ('done') { <path d="M6.5 10.25l2.5 2.5 4.5-5" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" /> }
                    @case ('rejected') { <path d="M6.75 10h6.5" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" /> }
                    @case ('stale') { <path d="M10 6.5v4.25" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" /><circle cx="10" cy="13.25" r="1" fill="currentColor" /> }
                  }
                </svg>
              </button>
            </li>
          }
        </ol>
      </nav>
    `,
    styleUrl: './batch-pager.component.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class BatchPagerComponent {
    readonly artifacts = input.required<readonly ArtifactSummary[]>();
    /** The artifact on show; `null` while none is chosen yet. */
    readonly selectedId = input<string | null>(null);
    /** The artifact id the user chose. */
    readonly picked = output<string>();

    private readonly dots = viewChildren<ElementRef<HTMLButtonElement>>('dot');
    protected readonly index = computed(() => this.artifacts().findIndex(artifact => artifact.artifactId === this.selectedId()));
    protected readonly tabStop = computed(() => Math.max(0, this.index()));
    protected readonly position = computed(() => this.index() < 0 ? `Всего: ${this.artifacts().length}`
        : `${this.index() + 1} из ${this.artifacts().length}`);

    protected status(artifact: ArtifactSummary): { readonly shape: StatusShape; readonly word: string } {
        return artifactStatus(artifact);
    }

    protected label(artifact: ArtifactSummary, position: number): string {
        return `${positionLabel(position, this.artifacts().length)}, ${artifactStatus(artifact).word}`;
    }

    protected move(step: number): void {
        this.choose(this.index() + step, false);
    }

    protected onKeydown(event: KeyboardEvent): void {
        const count = this.artifacts().length;
        if (count === 0 || event.altKey || event.ctrlKey || event.metaKey) return;
        const from = Math.max(0, this.index());
        let to: number;
        switch (event.key) {
            case 'ArrowRight': case 'ArrowDown': to = from + 1; break;
            case 'ArrowLeft': case 'ArrowUp': to = from - 1; break;
            case 'Home': to = 0; break;
            case 'End': to = count - 1; break;
            default: return;
        }
        event.preventDefault();
        this.choose(to, true);
    }

    private choose(position: number, focus: boolean): void {
        const target = this.artifacts()[position];
        if (target === undefined) return;
        this.picked.emit(target.artifactId);
        if (focus) queueMicrotask(() => this.dots()[position]?.nativeElement.focus());
    }
}
