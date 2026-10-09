import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, inject } from '@angular/core';
import { map } from 'rxjs';
import { AutoLoadComponent } from '../../shared/auto-load.component';
import { AdminApiService } from './admin-api.service';
import { AuditEntry } from './admin.models';
import { dateTime } from './admin-presenters';
import { CursorList } from './cursor-list';

type Source = 'learning' | 'identity';

@Component({
    selector: 'app-admin-audit-page',
    imports: [AutoLoadComponent],
    template: `
  <header>
    <p class="eyebrow">Кабинет владельца</p><h1 tabindex="-1">Журнал действий</h1>
    <p class="hint">
      Подтверждённые административные действия и отказы администраторам. Тексты обращений и новостей и полные промокоды сюда не попадают; причина блокировки — только в записи блокировки.
    </p>
  </header>
  @for (source of sources; track source.key) {
    <section class="rule-section" [attr.aria-labelledby]="'audit-' + source.key">
      <div class="actions">
        <h2 [id]="'audit-' + source.key">{{ source.name }}</h2>
        <button class="button" type="button" (click)="journals[source.key].reload()" [disabled]="journals[source.key].loading()">
          Обновить
        </button>
      </div>
      @if (journals[source.key].loading()) {<p role="status">Загружаем журнал…</p>}
      @if (journals[source.key].error()) {
        <div class="notice error" role="alert">
          <p>{{ journals[source.key].error() }}</p><button class="button" type="button" (click)="journals[source.key].reload()">Повторить</button>
        </div>
      }
      <div class="table-scroll" tabindex="0" role="region" [attr.aria-label]="source.name">
        <table #rows class="data-table">
          <caption>{{ source.name }} · новые сначала, записи подгружаются при прокрутке</caption>
          <thead>
            <tr>
              <th scope="col">Действие</th><th scope="col">Кто / объект</th><th scope="col">Дата, Москва</th><th scope="col">Команда</th>
            </tr>
          </thead>
          <tbody>
            @for (entry of journals[source.key].items(); track entry.auditId) {
              <tr>
                <th scope="row">
                  {{ action(entry.action) }}
                  @if (entry.outcome === 'DENIED') {
                    <small class="hint">Отказано: действие не выполнено</small>
                  }@if (entry.reason) {<small class="hint">Причина: {{ entry.reason }}</small>}
                </th>
                <td>
                  <code>{{ entry.actorAccountId }}</code><small class="hint">Объект: {{ entry.resourceId }}</small>
                </td><td>{{ date(entry.occurredAt) }}</td><td><code>{{ entry.commandId || 'Без команды браузера' }}</code></td>
              </tr>
            }
            @empty {
              @if (!journals[source.key].loading() && !journals[source.key].error()) {
                <tr><td colspan="4">Записей нет.</td></tr>
              }
            }
          </tbody>
        </table>
      </div>
      <app-auto-load [content]="rows" [context]="journals[source.key].context()" [continuation]="journals[source.key].next()"
        [loading]="journals[source.key].loadingMore()" [error]="journals[source.key].moreError()" loadingText="Загружаем следующие записи журнала…" (loadNext)="journals[source.key].more()" />
    </section>
  }`,
    styleUrl: './admin-page.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class AdminAuditPageComponent implements OnInit {
    private readonly api = inject(AdminApiService);
    private readonly destroy = inject(DestroyRef);
    protected readonly sources = [{
            key: 'learning',
            name: 'Learning: новости и промокоды'
        }, {
            key: 'identity',
            name: 'Identity: изменения аккаунтов'
        }] as const;
    protected readonly journals: Record<Source, CursorList<AuditEntry>> = {
        learning: this.journal('learning'),
        identity: this.journal('identity')
    };
    protected readonly date = dateTime;
    ngOnInit(): void {
        void this.journals.learning.reload();
        void this.journals.identity.reload();
    }

    protected action(value: string): string {
        return ({
            PROMO_CREATE: 'Создан промокод',
            PROMO_ENABLE: 'Промокод включён',
            PROMO_DISABLE: 'Промокод выключен',
            EVENT_CREATE: 'Создана новость',
            EVENT_REPLACE: 'Изменена новость',
            EVENT_DELETE: 'Удалена новость',
            BAN: 'Блокировка аккаунта',
            UNBAN: 'Разблокировка аккаунта',
            GRANT_ADMIN: 'Выдача прав администратора',
            REVOKE_ADMIN: 'Отзыв прав администратора'
        } as Record<string, string>)[value] ?? value;
    }

    private journal(source: Source): CursorList<AuditEntry> {
        return new CursorList<AuditEntry>({
            fetch: cursor => this.api.audit(source, cursor).pipe(map(page => ({ items: page.entries, next: page.next }))),
            key: entry => entry.auditId,
            destroy: this.destroy,
            firstError: 'Этот источник журнала недоступен. Повторите загрузку.',
            moreError: 'Следующие записи журнала не загрузились.'
        });
    }
}
