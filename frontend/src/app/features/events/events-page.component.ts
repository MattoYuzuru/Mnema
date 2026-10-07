import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { EventsApiService } from './events-api.service';
import { EventBodyComponent } from './event-body.component';
import { EventPage, ProductEvent, formatEventDate } from './events.models';

@Component({
    selector: 'app-events-page',
    imports: [RouterLink, EventBodyComponent],
    template: `
      <div class="events-page">
        <a routerLink="/" class="back-link">На главную</a>
        <header><span class="eyebrow">Журнал проекта</span><h1 tabindex="-1">События Мнемы</h1>
          <p class="lede">Обновления, идеи и планы. Рассказываем, что меняется в Мнеме и над чем мы работаем.</p></header>
        <p class="visually-hidden" role="status">{{ loading() ? 'Загружаем события' : 'События загружены' }}</p>
        @if (error()) { <div class="notice" role="alert"><p>{{ error() }}</p><button class="button" type="button" (click)="load(currentCursor)" [disabled]="loading()">Попробовать снова</button></div> }
        @if (!loading() && !error() && page()?.items?.length === 0) {
          <div class="empty-state"><h2>Здесь будут новости проекта</h2><p>Первая запись появится после публикации.</p></div>
        }
        <ol class="timeline" [attr.aria-busy]="loading()">
          @for (event of page()?.items ?? []; track event.eventId) {
            <li><article [attr.aria-labelledby]="'event-' + event.eventId">
              <time [attr.datetime]="event.eventDate">{{ date(event.eventDate) }}</time>
              <div><h2 [id]="'event-' + event.eventId">{{ event.title }}</h2><app-event-body [markdown]="event.bodyMarkdown" /></div>
            </article></li>
          }
        </ol>
        @if (history.length || page()?.nextCursor) {
          <nav class="pagination" aria-label="Страницы событий">
            <button type="button" class="button" (click)="previous()" [disabled]="loading() || history.length === 0">Более новые</button>
            <button type="button" class="button" (click)="next()" [disabled]="loading() || !page()?.nextCursor">Более ранние</button>
          </nav>
        }
      </div>
    `,
    styles: [`
      .events-page { max-width: 66rem; padding: var(--mn-space-7) var(--mn-page-gutter); margin: auto; }
      header { max-width: 43rem; margin-block: var(--mn-space-6) var(--mn-space-7); }
      h1 { margin-block: var(--mn-space-2) var(--mn-space-4); font-size: clamp(2.7rem, 6vw, 4rem); }
      .lede { font-size: 1.1rem; line-height: 1.7; color: var(--mn-muted); }
      .timeline { list-style: none; margin: 0; padding: 0; }
      article { display: grid; grid-template-columns: 11rem minmax(0, 1fr); gap: var(--mn-space-6); padding-block: var(--mn-space-6); border-top: 1px solid var(--mn-rule); }
      time { color: var(--mn-muted); font-size: .85rem; padding-top: .5rem; }
      h2 { font-size: clamp(1.7rem, 3vw, 2.3rem); margin: 0 0 var(--mn-space-4); overflow-wrap: anywhere; }
      .pagination { display: flex; flex-wrap: wrap; justify-content: space-between; gap: var(--mn-space-4); margin-top: var(--mn-space-6); }
      @media (max-width: 640px) { article { grid-template-columns: minmax(0, 1fr); gap: var(--mn-space-2); } }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class EventsPageComponent implements OnInit {
    private readonly api = inject(EventsApiService);
    private readonly destroyRef = inject(DestroyRef);
    protected readonly page = signal<EventPage<ProductEvent> | null>(null);
    protected readonly loading = signal(false);
    protected readonly error = signal('');
    protected readonly date = formatEventDate;
    protected history: (string | null)[] = [];
    protected currentCursor: string | null = null;
    private epoch = 0;

    ngOnInit(): void { void this.load(null); }

    protected async load(cursor: string | null): Promise<boolean> {
        const epoch = ++this.epoch;
        this.loading.set(true); this.error.set('');
        try {
            const page = await firstValueFrom(this.api.list(cursor).pipe(takeUntilDestroyed(this.destroyRef)));
            if (epoch !== this.epoch) return false;
            this.page.set(page); this.currentCursor = cursor;
            return true;
        } catch (error) {
            if (epoch === this.epoch) this.error.set(error instanceof HttpErrorResponse && error.status === 400
                ? 'Эта страница событий устарела. Вернитесь к более новым записям.' : 'Не удалось загрузить события. Попробуйте ещё раз.');
            return false;
        } finally { if (epoch === this.epoch) this.loading.set(false); }
    }

    protected async next(): Promise<void> {
        const cursor = this.page()?.nextCursor;
        if (this.loading() || !cursor) return;
        const previous = this.currentCursor;
        if (await this.load(cursor)) this.history.push(previous);
    }

    protected async previous(): Promise<void> {
        if (this.loading() || !this.history.length) return;
        const cursor = this.history.at(-1) ?? null;
        if (await this.load(cursor)) this.history.pop();
    }
}
