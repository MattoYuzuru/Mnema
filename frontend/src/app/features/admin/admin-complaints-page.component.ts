import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { map } from 'rxjs';
import { AutoLoadComponent } from '../../shared/auto-load.component';
import { SegmentedChoiceComponent, SegmentedOption } from '../../shared/segmented-choice.component';
import { AdminApiService } from './admin-api.service';
import { ComplaintRow, ComplaintStatus } from './admin-complaints.models';
import { decisionNames, deadlineText, reasonName, targetName, targetTitle } from './admin-complaints.presenters';
import { dateTime } from './admin-presenters';
import { CursorList } from './cursor-list';

const STATUS_OPTIONS: readonly SegmentedOption<ComplaintStatus>[] = [
    { value: 'OPEN', label: 'Открытые', hint: 'Показаны открытые жалобы: самый ранний срок первым.' },
    { value: 'DECIDED', label: 'Решённые', hint: 'Показаны решённые жалобы: недавние решения первыми.' }
];

/**
 * The complaint queue of the owner console (Share/15, `/manage/complaints`). OPEN complaints come oldest due date first, so an overdue one
 * is at the top and says «Просрочена» in words; DECIDED ones come newest decision first. The list continues by itself while it is
 * scrolled (no page buttons). The status lives in the address (`?status=`), the cursor does not.
 */
@Component({
    selector: 'app-admin-complaints-page',
    imports: [RouterLink, AutoLoadComponent, SegmentedChoiceComponent],
    template: `
  <header>
    <p class="eyebrow">Кабинет владельца</p><h1 tabindex="-1">Жалобы</h1>
    <p class="hint">
      Жалобы на публичные колоды и профили. Жалобу нужно рассмотреть за 30 дней. Тому, кто пожаловался, о решении не сообщается; автор колоды
      получает уведомление о скрытии и снятии.
    </p>
    <p><a class="button quiet" routerLink="/manage/complaints/journal">Журнал решений</a></p>
  </header>
  <app-segmented-choice legend="Какие жалобы показать" [options]="statusOptions" [value]="status()" (valueChange)="choose($event)" />
  @if (complaints.loading()) {<p role="status">Загружаем жалобы…</p>}
  @if (complaints.error()) {
    <div class="notice error" role="alert">
      <p>{{ complaints.error() }}</p><button class="button" type="button" (click)="complaints.reload()">Повторить</button>
    </div>
  }
  <div class="table-scroll" tabindex="0" role="region" aria-label="Очередь жалоб">
    <table #rows class="data-table">
      <caption>{{ status() === 'OPEN' ? 'Открытые жалобы, самый ранний срок первым' : 'Решённые жалобы, недавние первыми' }}; следующие подгружаются при прокрутке</caption>
      <thead>
        <tr><th scope="col">Объект</th><th scope="col">Причина</th><th scope="col">{{ status() === 'OPEN' ? 'Срок' : 'Решение' }}</th></tr>
      </thead>
      <tbody>
        @for (row of complaints.items(); track row.complaintId) {
          <tr>
            <th scope="row">
              <a [routerLink]="['/manage/complaints', row.complaintId]">{{ title(row) }}</a>
              <small class="hint">{{ kind(row) }}@if (row.publicCode) { · код {{ row.publicCode }} }</small>
            </th>
            <td>{{ reason(row) }}<small class="hint">Подана {{ date(row.createdAt) }}</small></td>
            <td>
              @if (row.status === 'OPEN') {
                <strong>{{ deadline(row) }}</strong><small class="hint">До {{ date(row.dueAt) }}</small>
              } @else {
                <strong>{{ row.decision ? decisions[row.decision] : 'Решена' }}</strong><small class="hint">{{ deadline(row) }}</small>
              }
            </td>
          </tr>
        }
        @empty {
          @if (!complaints.loading() && !complaints.error()) {
            <tr><td colspan="3">{{ status() === 'OPEN' ? 'Открытых жалоб нет.' : 'Решённых жалоб пока нет.' }}</td></tr>
          }
        }
      </tbody>
    </table>
  </div>
  <app-auto-load [content]="rows" [context]="complaints.context()" [continuation]="complaints.next()" [loading]="complaints.loadingMore()" [error]="complaints.moreError()"
    loadingText="Загружаем следующие жалобы…" (loadNext)="complaints.more()" />`,
    styleUrl: './admin-page.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class AdminComplaintsPageComponent implements OnInit {
    private readonly api = inject(AdminApiService);
    private readonly route = inject(ActivatedRoute);
    private readonly router = inject(Router);
    private readonly destroy = inject(DestroyRef);
    protected readonly statusOptions = STATUS_OPTIONS;
    protected readonly status = signal<ComplaintStatus>('OPEN');
    protected readonly decisions = decisionNames;
    protected readonly date = dateTime;
    protected readonly complaints = new CursorList<ComplaintRow>({
        fetch: cursor => this.api.complaints(this.status(), cursor).pipe(map(page => ({ items: page.items, next: page.next }))),
        key: row => row.complaintId,
        destroy: this.destroy,
        firstError: 'Не удалось загрузить жалобы. Повторите запрос.',
        moreError: 'Следующие жалобы не загрузились.'
    });

    ngOnInit(): void {
        this.route.queryParamMap.pipe(takeUntilDestroyed(this.destroy)).subscribe(query => {
            this.status.set(query.get('status') === 'DECIDED' ? 'DECIDED' : 'OPEN');
            void this.complaints.reload();
        });
    }

    protected choose(value: ComplaintStatus | null): void {
        if (value === null || value === this.status()) return;
        void this.router.navigate(['/manage/complaints'], { queryParams: value === 'OPEN' ? {} : { status: value } });
    }

    protected title(row: ComplaintRow): string { return targetTitle(row); }
    protected kind(row: ComplaintRow): string { return targetName[row.targetKind]; }
    protected reason(row: ComplaintRow): string { return reasonName(row.reason); }
    protected deadline(row: ComplaintRow): string { return deadlineText(row, Date.now()); }
}
