import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, input } from '@angular/core';

let nextScreen = 0;

/**
 * The page that says why a deck is not shown: not found, by invitation, too many requests, a busy service, no connection.
 * It owns the page heading (`h1` by default, focusable by the shell and by the screen that opens it), one short explanation (the
 * default content) and the actions that exist today (`[access-actions]`: a link or a button, never a dead control). Ruled top
 * and bottom like an empty state, not a card. Only text and real actions go inside; the tone is carried by the words.
 */
@Component({
    selector: 'app-access-screen',
    imports: [NgTemplateOutlet],
    template: `
      <section class="access" [attr.aria-labelledby]="headingId">
        @if (eyebrow(); as rubric) { <p class="eyebrow">{{ rubric }}</p> }
        <ng-template #title>{{ heading() }}</ng-template>
        @switch (headingLevel()) {
          @case (2) { <h2 [id]="headingId" tabindex="-1"><ng-container [ngTemplateOutlet]="title" /></h2> }
          @case (3) { <h3 [id]="headingId" tabindex="-1"><ng-container [ngTemplateOutlet]="title" /></h3> }
          @case (4) { <h4 [id]="headingId" tabindex="-1"><ng-container [ngTemplateOutlet]="title" /></h4> }
          @default { <h1 [id]="headingId" tabindex="-1"><ng-container [ngTemplateOutlet]="title" /></h1> }
        }
        <div class="message"><ng-content /></div>
        <div class="actions"><ng-content select="[access-actions]" /></div>
      </section>
    `,
    styles: [`
      :host { display: block; min-inline-size: 0; }
      .access { max-inline-size: 40rem; border-block: 1px solid var(--mn-ink); padding-block: 2rem 2.5rem; }
      :is(h1, h2, h3, h4) { max-inline-size: 24ch; margin: 0 0 1rem; color: var(--mn-ink); font-family: var(--mn-font-display, Georgia, serif); font-size: clamp(1.9rem, 4.5vw, 2.75rem); font-weight: 500; line-height: 1.15; overflow-wrap: anywhere; text-wrap: balance; }
      .message { max-inline-size: 48ch; line-height: 1.6; }
      .message > :last-child { margin-block-end: 0; }
      .actions { display: flex; flex-wrap: wrap; gap: .75rem; align-items: center; margin-block-start: 1.5rem; }
      .actions:empty { display: none; }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class AccessScreenComponent {
    readonly heading = input.required<string>();
    /** The mono rubric above the heading (for example «Колода»). */
    readonly eyebrow = input<string | null>(null);
    /** The level of the heading: 1 where the screen is the whole page (default), 2-4 inside a page that has its own `h1`. */
    readonly headingLevel = input<1 | 2 | 3 | 4>(1);

    protected readonly headingId = `mn-access-screen-${nextScreen++}`;
}
