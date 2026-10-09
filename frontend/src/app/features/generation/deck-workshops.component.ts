import { ChangeDetectionStrategy, Component, DestroyRef, effect, inject, input, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { AutoLoadComponent } from '../../shared/auto-load.component';

import { GenerationApiService } from './generation-api.service';
import { describeSessionProgress, formatWorkshopStart, workshopHeading } from './generation-view';
import { SessionSummary } from './generation.models';

/**
 * «Мастерская: N активных» on the Deck page, each with a link to its Workshop. It shows nothing while there is nothing
 * to show: no active Workshop, or a list that could not be read (the Deck page works without it).
 */
@Component({
    selector: 'app-deck-workshops',
    imports: [RouterLink, AutoLoadComponent],
    template: `
      @if (sessions().length > 0) {
        <section class="workshops" aria-labelledby="deck-workshops-title">
          <h2 id="deck-workshops-title">Мастерская: {{ heading() }}</h2>
          <ul #workshopRows>
            @for (session of sessions(); track session.sessionId) {
              <li>
                <a [routerLink]="['/decks', deckId(), 'workshop', session.sessionId]">{{ title(session) }} от {{ startedAt(session) }}</a>
                <span class="progress">{{ progress(session) }}</span>
              </li>
            }
          </ul>
          <app-auto-load [content]="workshopRows" [continuation]="cursor()" [loading]="loadingMore()"
            [error]="moreError()" loadingText="Загружаем следующие мастерские…" (loadNext)="loadMore()" />
        </section>
      }
    `,
    styles: [`
      :host { display: block; min-inline-size: 0; }
      .workshops { margin-block: 2rem; border-block-start: 1px solid var(--mn-ink); padding-block-start: 1rem; }
      h2 { margin: 0 0 .5rem; color: var(--mn-ink); font: 500 clamp(1.4rem, 3vw, 1.9rem)/1.2 var(--mn-font-display, Georgia, serif); }
      ul { display: grid; gap: .25rem; margin: 0; padding: 0; list-style: none; }
      li { display: flex; flex-wrap: wrap; align-items: baseline; gap: .25rem 1rem; min-block-size: var(--mn-touch-min, 2.75rem); }
      a { color: var(--mn-ink); font-weight: 600; text-underline-offset: .22em; }
      .progress { color: var(--mn-muted); }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class DeckWorkshopsComponent {
    readonly deckId = input.required<string>();
    readonly sessions = signal<readonly SessionSummary[]>([]);
    /** The first page was full: there are more than are listed. */
    readonly more = signal(false);
    protected readonly cursor = signal<string | null>(null);
    protected readonly loadingMore = signal(false);
    protected readonly moreError = signal<string | null>(null);
    private epoch = 0;

    private readonly api = inject(GenerationApiService);
    private readonly destroyRef = inject(DestroyRef);

    protected readonly heading = () => {
        const count = this.sessions().length;
        const lastTwo = count % 100;
        const last = count % 10;
        if (this.more()) return `${count}+ активных`;
        const word = lastTwo >= 11 && lastTwo <= 14 ? 'активных' : last === 1 ? 'активная' : last >= 2 && last <= 4 ? 'активные' : 'активных';
        return `${count} ${word}`;
    };

    constructor() {
        effect(onCleanup => {
            const deckId = this.deckId();
            const epoch = ++this.epoch;
            this.loadingMore.set(false);
            this.moreError.set(null);
            this.cursor.set(null);
            this.sessions.set([]);
            const request = this.api.listSessions(deckId, { active: true }).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
                next: page => { if (epoch === this.epoch) {
                    this.sessions.set(page.items); this.cursor.set(page.nextCursor); this.more.set(page.nextCursor !== null);
                } },
                error: () => { if (epoch === this.epoch) { this.sessions.set([]); this.more.set(false); } }
            });
            onCleanup(() => request.unsubscribe());
        });
    }

    protected loadMore(): void {
        const cursor = this.cursor();
        if (cursor === null || this.loadingMore()) return;
        const epoch = this.epoch;
        const deckId = this.deckId();
        this.loadingMore.set(true);
        this.moreError.set(null);
        this.api.listSessions(deckId, { active: true, cursor }).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
            next: page => {
                if (epoch !== this.epoch) return;
                const known = new Set(this.sessions().map(session => session.sessionId));
                this.sessions.update(sessions => [...sessions, ...page.items.filter(session => !known.has(session.sessionId))]);
                this.cursor.set(page.nextCursor);
                this.more.set(page.nextCursor !== null);
                this.loadingMore.set(false);
            },
            error: () => {
                if (epoch !== this.epoch) return;
                this.moreError.set('Не удалось загрузить следующие мастерские.'); this.loadingMore.set(false);
            }
        });
    }

    protected readonly title = (session: SessionSummary): string => workshopHeading(session.kind);

    protected startedAt(session: SessionSummary): string {
        return formatWorkshopStart(session.createdAt);
    }

    protected progress(session: SessionSummary): string {
        return describeSessionProgress(session);
    }
}
