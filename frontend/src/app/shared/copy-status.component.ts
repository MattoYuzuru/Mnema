import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';

import { COPY_TEXT } from './copy-text';

/**
 * The block that takes the place of a copy's content while the copy is not ready (Deck hub). Three states, always in words:
 * `preparing` «Готовим колоду…» (what will be in the copy, and that the page updates itself), `failed` «Не удалось подготовить
 * колоду» with «Повторить» (and whatever else the page projects into `[copy-actions]`, for example the delete control), and, when
 * the page stopped checking, `stalled` with «Проверить снова». The block is a plain section with a heading: the page announces
 * changes of state once in its own polite live region, so a screen reader hears each change a single time. Nothing here is a
 * progress bar: the server reports only these states, and a bar would invent a figure.
 */
let nextStatus = 0;

@Component({
    selector: 'app-copy-status',
    imports: [NgTemplateOutlet],
    template: `
      <section class="notice" [class.error]="state() === 'failed'" [attr.aria-labelledby]="headingId">
        <ng-template #heading>{{ title() }}</ng-template>
        @switch (headingLevel()) {
          @case (3) { <h3 [id]="headingId" tabindex="-1" class="subsection-heading"><ng-container [ngTemplateOutlet]="heading" /></h3> }
          @case (4) { <h4 [id]="headingId" tabindex="-1" class="subsection-heading"><ng-container [ngTemplateOutlet]="heading" /></h4> }
          @default { <h2 [id]="headingId" tabindex="-1" class="subsection-heading"><ng-container [ngTemplateOutlet]="heading" /></h2> }
        }
        @if (summary(); as line) { <p class="line">{{ text.preparingLine(line) }}</p> }
        <p class="hint">{{ hint() }}</p>
        <div class="actions">
          @if (state() === 'failed') {
            <button type="button" class="button primary" [attr.aria-busy]="busy() ? 'true' : null" [attr.aria-disabled]="busy() ? 'true' : null" (click)="retry.emit()">{{ busy() ? text.retrying : text.retry }}</button>
          } @else if (stalled()) {
            <button type="button" class="button" (click)="check.emit()">{{ text.check }}</button>
          }
          <ng-content select="[copy-actions]" />
        </div>
      </section>
    `,
    styles: [`
      :host { display: block; min-inline-size: 0; margin-block: 0 1.5rem; }
      .notice { display: grid; gap: .6rem; }
      .line { margin: 0; color: var(--mn-body); }
      .actions { display: flex; flex-wrap: wrap; align-items: center; gap: .75rem; }
      .actions:empty { display: none; }
      :is(h2, h3, h4):focus-visible { outline: none; }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class CopyStatusComponent {
    /** `preparing`: the job runs; `failed`: it ended without success. */
    readonly state = input.required<'preparing' | 'failed'>();
    /** «40 материалов · 12 упражнений»: what the copy will hold, from the counts the server reports. */
    readonly summary = input<string | null>(null);
    /** The page stopped checking by itself (it waited long enough). */
    readonly stalled = input(false);
    /** «Повторить» was pressed and the request is on its way. */
    readonly busy = input(false);
    /** The level of the heading: 2 in the hub, 3-4 inside a page that has more levels above (the styleguide). */
    readonly headingLevel = input<2 | 3 | 4>(2);
    readonly retry = output<void>();
    readonly check = output<void>();

    protected readonly text = COPY_TEXT;
    protected readonly headingId = `mn-copy-status-${nextStatus++}`;
    protected readonly title = computed(() => this.state() === 'failed' ? COPY_TEXT.failed : COPY_TEXT.preparing);
    protected readonly hint = computed(() => this.state() === 'failed' ? COPY_TEXT.failedHint
        : this.stalled() ? COPY_TEXT.stalledHint : COPY_TEXT.preparingHint);
}
