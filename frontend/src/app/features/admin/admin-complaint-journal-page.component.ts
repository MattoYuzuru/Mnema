import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { map } from 'rxjs';
import { AutoLoadComponent } from '../../shared/auto-load.component';
import { AdminApiService } from './admin-api.service';
import { JournalEntry } from './admin-complaints.models';
import { journalActionNames, targetName } from './admin-complaints.presenters';
import { dateTime } from './admin-presenters';
import { CursorList } from './cursor-list';

/**
 * The takedown journal (`/manage/complaints/journal`): the append-only record of every decision about a complaint, newest first. It names
 * the time, the actor, the action and the target; there is no way to change or remove an entry. Continued by the shared auto-load.
 */
@Component({
    selector: 'app-admin-complaint-journal-page',
    imports: [RouterLink, AutoLoadComponent],
    template: `
  <header>
    <p class="eyebrow">Кабинет владельца</p><h1 tabindex="-1">Журнал решений по жалобам</h1>
    <p class="hint">
      Каждое решение записано навсегда: время, кто решил, что сделано и к чему относится. Записи нельзя изменить. Тексты жалоб и сами материалы сюда не попадают.
    </p>
    <p><a class="button quiet" routerLink="/manage/complaints">← К очереди жалоб</a></p>
  </header>
  <div class="actions">
    <button class="button" type="button" (click)="journal.reload()" [disabled]="journal.loading()">Обновить</button>
  </div>
  @if (journal.loading()) {<p role="status">Загружаем журнал…</p>}
  @if (journal.error()) {
    <div class="notice error" role="alert">
      <p>{{ journal.error() }}</p><button class="button" type="button" (click)="journal.reload()">Повторить</button>
    </div>
  }
  <div class="table-scroll" tabindex="0" role="region" aria-label="Журнал решений по жалобам">
    <table #rows class="data-table">
      <caption>Решения, новые сначала; записи подгружаются при прокрутке</caption>
      <thead>
        <tr><th scope="col">Решение</th><th scope="col">Объект</th><th scope="col">Кто решил</th><th scope="col">Когда, Москва</th></tr>
      </thead>
      <tbody>
        @for (entry of journal.items(); track entry.journalId) {
          <tr>
            <th scope="row">
              <a [routerLink]="['/manage/complaints', entry.complaintId]">{{ action(entry) }}</a>
              @if (entry.unitCount > 0) { <small class="hint">Новых снятий: {{ entry.unitCount }}</small> }
            </th>
            <td>
              {{ kind(entry) }}
              @if (entry.publicCode) { <small class="hint">Код {{ entry.publicCode }}</small> }
              @if (entry.profileAccountId) { <small class="hint"><a [routerLink]="['/manage/users', entry.profileAccountId]">Аккаунт <code>{{ entry.profileAccountId }}</code></a></small> }
            </td>
            <td><code>{{ entry.actorId }}</code></td>
            <td>{{ date(entry.occurredAt) }}</td>
          </tr>
        }
        @empty {
          @if (!journal.loading() && !journal.error()) {
            <tr><td colspan="4">Решений пока нет.</td></tr>
          }
        }
      </tbody>
    </table>
  </div>
  <app-auto-load [content]="rows" [context]="journal.context()" [continuation]="journal.next()" [loading]="journal.loadingMore()" [error]="journal.moreError()"
    loadingText="Загружаем следующие записи журнала…" (loadNext)="journal.more()" />`,
    styleUrl: './admin-page.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class AdminComplaintJournalPageComponent implements OnInit {
    private readonly api = inject(AdminApiService);
    private readonly destroy = inject(DestroyRef);
    protected readonly date = dateTime;
    protected readonly journal = new CursorList<JournalEntry>({
        fetch: cursor => this.api.complaintJournal(cursor).pipe(map(page => ({ items: page.items, next: page.next }))),
        key: entry => entry.journalId,
        destroy: this.destroy,
        firstError: 'Не удалось загрузить журнал. Повторите запрос.',
        moreError: 'Следующие записи журнала не загрузились.'
    });

    ngOnInit(): void { void this.journal.reload(); }

    protected action(entry: JournalEntry): string { return journalActionNames[entry.action]; }
    protected kind(entry: JournalEntry): string { return targetName[entry.targetKind]; }
}
