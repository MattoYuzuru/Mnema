import { ChangeDetectionStrategy, Component, ElementRef, computed, inject, input } from '@angular/core';

import { plural, takedownRemedy } from '../core/notifications/notification-presenter';

const MATERIALS = ['материал', 'материала', 'материалов'] as const;
const EXERCISES = ['упражнение', 'упражнения', 'упражнений'] as const;

let nextNotice = 0;

/**
 * What the owner of a deck is told after a complaint was decided (Share/15): the deck is hidden from the Community, and/or some of its
 * materials and exercises were taken down. A `.notice.warning` with its own heading «Модерация», so the notification link
 * (`/decks/{id}#moderation`) can put the focus there. Draws nothing for a clean deck. The words carry the state; the colour does not.
 * Nothing about the complaint or the reporter is shown: the owner is told what happened to the deck, never who asked.
 */
@Component({
    selector: 'app-moderation-notice',
    template: `
      @if (shown()) {
        <section class="notice warning" [attr.aria-labelledby]="headingId">
          <h2 [id]="headingId" tabindex="-1" class="heading">Модерация</h2>
          @if (hiddenFromCommunity()) {
            <p class="what">Колода скрыта из Сообщества</p>
            <p class="more">Другие читатели её не видят, а ссылки на неё не открываются. Вам она доступна как раньше. Копии, которые уже сделали другие люди, остались у них.</p>
          }
          @if (takenDown()) {
            <p class="what">Материалы сняты по жалобе</p>
            <p class="more">{{ counts() }} В списках они отмечены как недоступные. {{ remedy() }} Копии, где снятое не меняли, тоже его не показывают.</p>
          }
        </section>
      }
    `,
    styles: [`
      :host { display: block; min-inline-size: 0; }
      .notice { margin-block: var(--mn-space-4, 1rem); }
      .heading { margin: 0 0 .5rem; font: 700 .75rem/1.4 var(--mn-font-mono); letter-spacing: .12em; text-transform: uppercase; color: var(--mn-ink); }
      .heading:focus-visible { outline: 3px solid var(--mn-focus, var(--mn-ink)); outline-offset: 3px; }
      .what { margin: 0; color: var(--mn-ink); font-weight: 700; }
      .more { margin: .25rem 0 .75rem; overflow-wrap: anywhere; }
      .more:last-child { margin-block-end: 0; }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class ModerationNoticeComponent {
    /** The deck is hidden from the Community by moderation. */
    readonly hiddenFromCommunity = input(false);
    /** Materials of this deck that are taken down right now. */
    readonly materials = input(0);
    /** Exercises of this deck that are taken down right now. */
    readonly exercises = input(0);

    protected readonly headingId = `mn-moderation-${nextNotice++}`;
    private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
    protected readonly takenDown = computed(() => this.materials() + this.exercises() > 0);
    protected readonly shown = computed(() => this.hiddenFromCommunity() || this.takenDown());
    protected readonly counts = computed(() => {
        const parts: string[] = [];
        if (this.materials() > 0) parts.push(`${this.materials()} ${plural(this.materials(), MATERIALS)}`);
        if (this.exercises() > 0) parts.push(`${this.exercises()} ${plural(this.exercises(), EXERCISES)}`);
        return `Снято: ${parts.join(' и ')}.`;
    });

    protected readonly remedy = computed(() => `${takedownRemedy(this.materials(), this.exercises())}.`);

    /** Puts the focus on the heading (the notification link `#moderation`). `false` when nothing is shown. */
    focusHeading(): boolean {
        const heading = this.host.nativeElement.querySelector<HTMLElement>('h2');
        heading?.focus();
        return heading !== null;
    }
}
