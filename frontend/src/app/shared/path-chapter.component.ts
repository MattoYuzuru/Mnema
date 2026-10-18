import { ChangeDetectionStrategy, Component, OnInit, input } from '@angular/core';

import { onboardingText } from '../core/onboarding/onboarding-texts';

/**
 * A chapter of «Путь»: a native disclosure (`<details>`) whose summary is the chapter name and «N из M»; the milestones are projected.
 * The browser says whether it is open (the summary is a button with `aria-expanded`), the little triangle is decoration. The chapter name is
 * the `h2` of the page (the title is the `h1`, the milestones are `h3`), inside the summary. The current
 * chapter (the first with something left) is open, the others are folded.
 */
@Component({
    selector: 'app-path-chapter',
    template: `
      <details class="chapter" [open]="startedOpen">
        <summary>
          <h2 class="chapter-name">{{ name() }}</h2>
          <span class="count">{{ count() }}</span>
        </summary>
        <div class="body"><ng-content /></div>
      </details>
    `,
    styles: [`
      :host { display: block; min-inline-size: 0; }
      .chapter { border-block-start: 2px solid var(--mn-ink); }
      summary { display: flex; gap: 1rem; justify-content: space-between; align-items: center; min-block-size: 3.25rem; cursor: pointer; list-style: none; }
      summary::-webkit-details-marker { display: none; }
      summary:focus-visible { outline: 3px solid var(--mn-focus); outline-offset: 2px; }
      .chapter-name { margin: 0; color: var(--mn-ink); font: 600 1.6rem/1.1 var(--mn-font-display); overflow-wrap: anywhere; }
      .count { color: var(--mn-muted); font: 600 .8rem/1.3 var(--mn-font-mono); white-space: nowrap; }
      /* The mark is decoration (empty alternative text): the open state is said by the browser. */
      .count::after { content: ' ▸' / ''; }
      .chapter[open] .count::after { content: ' ▾' / ''; }
      .body { margin-block-end: 1rem; }
      @media (forced-colors: active) { .chapter { border-block-start-color: CanvasText; } summary:focus-visible { outline-color: Highlight; } }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class PathChapterComponent implements OnInit {
    readonly name = input.required<string>();
    readonly done = input.required<number>();
    readonly total = input.required<number>();
    /** The current chapter starts open. Read once, when the chapter appears: a refresh that moves «the current chapter» never folds the one the learner is in. */
    readonly open = input(false);
    protected startedOpen = false;

    ngOnInit(): void {
        this.startedOpen = this.open();
    }

    protected count(): string {
        return onboardingText('path.total', { done: this.done(), total: this.total() });
    }
}
