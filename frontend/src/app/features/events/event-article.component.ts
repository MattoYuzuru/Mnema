import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { EventBodyComponent } from './event-body.component';
import { formatEventDate } from './events.models';

/** One public journal entry: the public page and the editor's preview render exactly this markup. */
@Component({
    selector: 'app-event-article',
    imports: [EventBodyComponent],
    template: `
      <article [attr.aria-labelledby]="headingId()">
        <time [attr.datetime]="eventDate()">{{ formatted() }}</time>
        <div><h2 [id]="headingId()">{{ title() }}</h2><app-event-body [markdown]="markdown()" /></div>
      </article>
    `,
    styles: [`
      :host { display: block; }
      article { display: grid; grid-template-columns: 11rem minmax(0, 1fr); gap: var(--mn-space-6); padding-block: var(--mn-space-6); border-top: 1px solid var(--mn-rule); }
      time { color: var(--mn-muted); font-size: .85rem; padding-top: .5rem; }
      h2 { font-size: clamp(1.7rem, 3vw, 2.3rem); margin: 0 0 var(--mn-space-4); overflow-wrap: anywhere; }
      @media (max-width: 640px) { article { grid-template-columns: minmax(0, 1fr); gap: var(--mn-space-2); } }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class EventArticleComponent {
    /** Unique within the page; only builds the heading id. */
    readonly eventId = input.required<string>();
    readonly title = input.required<string>();
    readonly eventDate = input.required<string>();
    readonly markdown = input.required<string>();
    protected readonly headingId = computed(() => 'event-' + this.eventId());
    /** An unfinished draft may have no valid date yet. */
    protected readonly formatted = computed(() => /^\d{4}-\d{2}-\d{2}$/u.test(this.eventDate()) && !Number.isNaN(Date.parse(this.eventDate())) ? formatEventDate(this.eventDate()) : '');
}
