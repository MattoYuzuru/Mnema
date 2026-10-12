import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { UPDATE_TEXT } from './copy-text';
import { UpdateBadgeComponent } from './update-badge.component';

let nextNews = 0;

/**
 * «Что нового» in the hub of a copy after an automatic update (Updates/6 part B): a compact block under the actions of the deck, so «Учить» stays
 * in the first screen. A small `h2`, the time of the update in a `<time>` («2 часа назад, 12.10.2026, 12:30»), one plain sentence («Автор обновил
 * колоду, мы взяли 3 изменения автоматически. 2 ждут вашего решения.»; the second sentence only when something waits) and the links the page
 * projects (a text link to the history, a link to the decision). It is not a bordered sheet and has no button of its own.
 *
 * With `offer` the author also has something waiting («Есть обновление»): the block then carries the badge and the page's one link to the review
 * screen, so the hub never shows two blocks about the same update. It is not a live region: the update arrived while the owner was away.
 */
@Component({
    selector: 'app-update-news',
    imports: [UpdateBadgeComponent],
    template: `
      <section class="news" [attr.aria-labelledby]="headingId">
        <div class="head">
          <h2 class="subsection-heading" [id]="headingId">{{ text.whatsNew }}</h2>
          @if (offer()) { <app-update-badge /> }
          <time class="when" [attr.datetime]="appliedAt()">{{ when() }}</time>
        </div>
        <p class="line">{{ line() }}</p>
        <div class="actions"><ng-content /></div>
      </section>
    `,
    styles: [`
      :host { display: block; min-inline-size: 0; }
      .news { display: grid; gap: .4rem; justify-items: start; margin-block: 0 1rem; border-block: 1px solid var(--mn-rule); padding-block: .75rem; }
      .head { display: flex; flex-wrap: wrap; align-items: center; gap: .25rem .75rem; min-inline-size: 0; }
      .news h2, .news p { margin: 0; }
      .when { color: var(--mn-muted); font-size: .9rem; }
      .line { max-inline-size: 62ch; color: var(--mn-body); line-height: 1.5; }
      .actions { display: flex; flex-wrap: wrap; align-items: center; gap: .25rem .75rem; }
      .actions:empty { display: none; }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class UpdateNewsComponent {
    /** Units the automatic update took, dependencies included. */
    readonly applied = input.required<number>();
    /** Units it left to the owner. */
    readonly pending = input.required<number>();
    /** When the update was applied, ISO, for the `datetime` of the time. */
    readonly appliedAt = input.required<string>();
    /** The time as the page writes it. */
    readonly when = input.required<string>();
    /** The author also has an update waiting: the block carries the badge «Есть обновление». */
    readonly offer = input(false);

    protected readonly text = UPDATE_TEXT;
    protected readonly headingId = `update-news-${nextNews++}`;
    protected readonly line = computed(() => {
        const pending = this.pending();
        const sentence = pending > 0 ? `${this.text.newsApplied(this.applied())} ${this.text.newsPending(pending)}` : this.text.newsApplied(this.applied());
        // Nothing waits from this update, but the author has published again: say what the badge means.
        return this.offer() && pending === 0 ? `${sentence} ${this.text.offerNote}` : sentence;
    });
}
