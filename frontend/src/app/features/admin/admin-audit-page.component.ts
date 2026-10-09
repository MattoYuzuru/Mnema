import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { firstValueFrom } from 'rxjs';
import { AdminApiService } from './admin-api.service';
import { AuditPage } from './admin.models';
import { dateTime, errorText, isForbidden } from './admin-presenters';
interface JournalState {
    readonly page: AuditPage | null;
    readonly cursor: string | null;
    readonly history: readonly (string | null)[];
    readonly loading: boolean;
    readonly error: string;
}
const initial = (): JournalState => ({
    page: null,
    cursor: null,
    history: [],
    loading: false,
    error: ''
});
@Component({
    selector: 'app-admin-audit-page',
    template: `
  <header><p class="eyebrow">Кабинет владельца</p><h1 tabindex="-1">Журнал действий</h1><p class="hint">Подтверждённые административные действия. Тексты обращений, новостей, причины блокировок и полные промокоды сюда не попадают.</p></header>
  @for (source of sources; track source.key) {
    <section class="rule-section" [attr.aria-labelledby]="'audit-' + source.key"><div class="actions"><h2 [id]="'audit-' + source.key">{{ source.name }}</h2><button class="button" type="button" (click)="load(source.key, journals()[source.key].cursor)" [disabled]="journals()[source.key].loading">Обновить</button></div>
      @if (journals()[source.key].loading) { <p role="status">Загружаем журнал…</p> } @if (journals()[source.key].error) { <p class="notice error" role="alert">{{ journals()[source.key].error }}</p> }
      <div class="table-scroll" tabindex="0" role="region" [attr.aria-label]="source.name"><table class="data-table"><caption>{{ source.name }} · до 50 записей, новые сначала</caption><thead><tr><th scope="col">Действие</th><th scope="col">Кто / объект</th><th scope="col">Дата, Москва</th><th scope="col">Команда</th></tr></thead><tbody>@for (entry of journals()[source.key].page?.entries ?? []; track entry.auditId) { <tr><th scope="row">{{ action(entry.action) }}</th><td><code>{{ entry.actorAccountId }}</code><small class="hint">Объект: {{ entry.resourceId }}</small></td><td>{{ date(entry.occurredAt) }}</td><td><code>{{ entry.commandId || 'Без команды браузера' }}</code></td></tr> } @empty { <tr><td colspan="4">Записей нет или журнал ещё не загружен.</td></tr> }</tbody></table></div>
      <nav class="actions" [attr.aria-label]="'Страницы: ' + source.name"><button class="button" type="button" (click)="previous(source.key)" [disabled]="journals()[source.key].loading || !journals()[source.key].cursor">Назад</button><button class="button" type="button" (click)="next(source.key)" [disabled]="journals()[source.key].loading || !journals()[source.key].page?.next">Далее</button></nav>
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
    protected readonly journals = signal<Record<'learning' | 'identity', JournalState>>({
        learning: initial(),
        identity: initial()
    });
    protected readonly date = dateTime;
    ngOnInit(): void {
        void this.load('learning', null);
        void this.load('identity', null);
    }

    protected action(value: string): string {
        return ({
            PROMO_CREATE: 'Создан промокод',
            PROMO_ENABLE: 'Промокод включён',
            PROMO_DISABLE: 'Промокод выключен',
            EVENT_CREATE: 'Создана новость',
            EVENT_REPLACE: 'Изменена новость',
            EVENT_DELETE: 'Удалена новость',
            BAN: 'Аккаунт заблокирован',
            UNBAN: 'Аккаунт разблокирован',
            GRANT_ADMIN: 'Выданы права администратора',
            REVOKE_ADMIN: 'Отозваны права администратора'
        } as Record<string, string>)[value] ?? value;
    }

    private update(source: 'learning' | 'identity', delta: Partial<JournalState>): void {
        this.journals.update(journals => ({
            ...journals,
            [source]: {
                ...journals[source],
                ...delta
            }
        }));
    }

    protected async load(source: 'learning' | 'identity', before: string | null): Promise<boolean> {
        if (this.journals()[source].loading)
            return false;
        this.update(source, {
            loading: true,
            error: ''
        });
        try {
            const page = await firstValueFrom(this.api.audit(source, before).pipe(takeUntilDestroyed(this.destroy)));
            this.update(source, {
                page,
                cursor: before
            });
            return true;
        } catch (error) {
            this.update(source, {
                error: errorText(error, 'Этот источник журнала недоступен. Повторите загрузку.'),
                ...(isForbidden(error) ? {
                    page: null
                } : {})
            });
            return false;
        } finally {
            this.update(source, {
                loading: false
            });
        }
    }

    protected async next(source: 'learning' | 'identity'): Promise<void> {
        const state = this.journals()[source];
        if (state.page?.next && await this.load(source, state.page.next))
            this.update(source, {
                history: [...state.history, state.cursor]
            });
    }

    protected async previous(source: 'learning' | 'identity'): Promise<void> {
        const state = this.journals()[source];
        if (await this.load(source, state.history.at(-1) ?? null))
            this.update(source, {
                history: state.history.slice(0, -1)
            });
    }
}
