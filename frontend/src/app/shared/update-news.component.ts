import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { UPDATE_TEXT } from './copy-text';

let nextNews = 0;

/**
 * «Что нового» in the hub of a copy after an automatic update (Updates/6 part B): a titled block with one plain sentence («Автор обновил колоду,
 * мы взяли 3 изменения автоматически. 2 ждут вашего решения.»; the second sentence only when something waits) and the actions the page
 * projects (a link to the history, a link to the decision). It looks like the update offer next to it (ink rule round a sheet) and is not a live
 * region: the update arrived while the owner was away, so there is nothing to announce.
 */
@Component({
    selector: 'app-update-news',
    template: `
      <section class="news" [attr.aria-labelledby]="headingId">
        <h2 class="section-heading" [id]="headingId">{{ text.whatsNew }}</h2>
        <p class="line">{{ line() }}</p>
        <div class="actions"><ng-content /></div>
      </section>
    `,
    styles: [`
      :host { display: block; min-inline-size: 0; }
      .news { display: grid; gap: .6rem; justify-items: start; margin-block: 0 1.25rem; border: 1px solid var(--mn-ink); padding: 1rem clamp(1rem, 3vw, 1.5rem); background: var(--mn-sheet); }
      .news h2, .news p { margin: 0; }
      .line { max-inline-size: 62ch; color: var(--mn-body); line-height: 1.5; }
      .actions { display: flex; flex-wrap: wrap; gap: .75rem; }
      .actions:empty { display: none; }
      @media (forced-colors: active) { .news { border-color: CanvasText; } }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class UpdateNewsComponent {
    /** Units the automatic update took, dependencies included. */
    readonly applied = input.required<number>();
    /** Units it left to the owner. */
    readonly pending = input.required<number>();

    protected readonly text = UPDATE_TEXT;
    protected readonly headingId = `update-news-${nextNews++}`;
    protected readonly line = computed(() => {
        const pending = this.pending();
        return pending > 0 ? `${this.text.newsApplied(this.applied())} ${this.text.newsPending(pending)}` : this.text.newsApplied(this.applied());
    });
}
