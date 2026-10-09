import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { combineLatest, firstValueFrom } from 'rxjs';
import { AuthService } from '../../auth.service';
import { UsageMeterComponent } from '../../shared/usage-meter.component';
import { AdminApiService, AdminSession } from './admin-api.service';
import { DirectoryDetail, DirectoryPage, UserReport, uuid } from './admin.models';
import {
    accountStatus, deletionStatus, entitlementSource, operationUnits, bytes, dateTime, defaultPeriod, errorText,
    isForbidden, number, operationName, reportPeriod, unknownOutcome
} from './admin-presenters';
@Component({
    selector: 'app-admin-users-page',
    host: {
        '(window:beforeunload)': 'protectBeforeUnload($event)'
    },
    imports: [ReactiveFormsModule, RouterLink, UsageMeterComponent],
    templateUrl: './admin-users-page.component.html',
    styleUrl: './admin-page.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class AdminUsersPageComponent implements OnInit {
    private readonly api = inject(AdminApiService);
    private readonly route = inject(ActivatedRoute);
    private readonly router = inject(Router);
    private readonly destroy = inject(DestroyRef);
    protected readonly session = inject(AdminSession);
    private readonly auth = inject(AuthService);
    private listEpoch = 0;
    private detailEpoch = 0;
    protected readonly page = signal<DirectoryPage | null>(null);
    protected readonly detail = signal<DirectoryDetail | null>(null);
    protected readonly report = signal<UserReport | null>(null);
    protected readonly selectedId = signal<string | null>(null);
    protected readonly loading = signal(false);
    protected readonly detailLoading = signal(false);
    protected readonly listError = signal('');
    protected readonly detailError = signal('');
    protected readonly reportError = signal('');
    protected readonly busy = signal(false);
    protected readonly notice = signal('');
    protected readonly actionError = signal('');
    protected readonly confirmAction = signal<'ban' | 'unban' | null>(null);
    protected readonly actionUnknown = signal(false);
    protected readonly form = new FormGroup({
        query: new FormControl('', {
            nonNullable: true
        }),
        status: new FormControl('', {
            nonNullable: true
        }),
        from: new FormControl(defaultPeriod().from, {
            nonNullable: true
        }),
        through: new FormControl(defaultPeriod().through, {
            nonNullable: true
        })
    });
    protected readonly reason = new FormControl('', {
        nonNullable: true
    });
    protected readonly accountStatus = accountStatus;
    protected readonly deletionStatus = deletionStatus;
    protected readonly source = entitlementSource;
    protected readonly units = operationUnits;
    protected readonly date = dateTime;
    protected readonly number = number;
    protected readonly bytes = bytes;
    protected readonly operation = operationName;
    protected readonly listParams = signal<Record<string, string>>({});
    protected readonly cursor = signal<string | null>(null);
    protected readonly history = signal<(string | null)[]>([]);
    ngOnInit(): void {
        combineLatest([this.route.paramMap, this.route.queryParamMap]).pipe(takeUntilDestroyed(this.destroy)).subscribe(([params, query]) => {
            const defaults = defaultPeriod();
            this.form.reset({
                query: query.get('query') ?? '',
                status: query.get('status') ?? '',
                from: query.get('from') ?? defaults.from,
                through: query.get('through') ?? defaults.through
            });
            this.listParams.set(this.form.getRawValue());
            const after = query.get('after');
            this.cursor.set(after);
            void this.loadList(after);
            const id = params.get('accountId');
            this.selectedId.set(id);
            this.confirmAction.set(null);
            this.notice.set('');
            this.actionError.set('');
            this.actionUnknown.set(false);
            this.reason.reset();
            if (id)
                void this.loadDetail(id);
            else {
                ++this.detailEpoch;
                this.detail.set(null);
                this.report.set(null);
                this.detailError.set('');
                this.reportError.set('');
            }
        });
    }

    protected async apply(): Promise<void> {
        if (this.busy())
            return;
        try {
            reportPeriod(this.form.controls.from.value, this.form.controls.through.value);
            this.history.set([]);
            await this.router.navigate(['/manage/users'], {
                queryParams: this.form.getRawValue()
            });
        } catch {
            this.listError.set('Выберите период от 1 до 90 дней, не позже сегодня.');
        }
    }

    protected async reset(): Promise<void> {
        this.form.reset({
            query: '',
            status: '',
            ...defaultPeriod()
        });
        await this.apply();
    }

    protected async loadList(after: string | null): Promise<void> {
        const epoch = ++this.listEpoch;
        this.loading.set(true);
        this.listError.set('');
        try {
            const values = this.listParams();
            const page = await firstValueFrom(this.api.directory(values['query'] ?? '', values['status'] ?? '', after).pipe(takeUntilDestroyed(this.destroy)));
            if (epoch === this.listEpoch)
                this.page.set(page);
        } catch (error) {
            if (epoch === this.listEpoch) {
                if (isForbidden(error)) {
                    ++this.detailEpoch;
                    this.page.set(null);
                    this.detail.set(null);
                    this.report.set(null);
                }
                this.listError.set(errorText(error, 'Не удалось загрузить пользователей. Повторите запрос.'));
            }
        } finally {
            if (epoch === this.listEpoch)
                this.loading.set(false);
        }
    }

    protected async loadDetail(id: string): Promise<void> {
        const epoch = ++this.detailEpoch;
        this.detailLoading.set(true);
        this.detail.set(null);
        this.report.set(null);
        this.detailError.set('');
        this.reportError.set('');
        try {
            uuid(id);
        } catch {
            this.detailLoading.set(false);
            this.detailError.set('Некорректный ID аккаунта.');
            return;
        }
        try {
            const detail = await firstValueFrom(this.api.account(id).pipe(takeUntilDestroyed(this.destroy)));
            if (epoch === this.detailEpoch) {
                this.detail.set(detail);
                this.actionUnknown.set(false);
            }
        } catch (error) {
            if (epoch === this.detailEpoch)
                this.detailError.set(errorText(error, 'Карточка аккаунта недоступна. Повторите загрузку.'));
        } finally {
            if (epoch === this.detailEpoch)
                this.detailLoading.set(false);
        }
        if (epoch !== this.detailEpoch || !this.session.access())
            return;
        try {
            const period = reportPeriod(this.form.controls.from.value, this.form.controls.through.value);
            const report = await firstValueFrom(this.api.userReport(id, period.from, period.to).pipe(takeUntilDestroyed(this.destroy)));
            if (epoch === this.detailEpoch)
                this.report.set(report);
        } catch (error) {
            if (epoch === this.detailEpoch) {
                if (isForbidden(error))
                    this.detail.set(null);
                this.reportError.set(errorText(error, 'Данные использования не загрузились. Сведения аккаунта загружены отдельно.'));
            }
        }
    }

    protected async next(): Promise<void> {
        const next = this.page()?.next;
        if (!next || this.loading())
            return;
        this.history.update(values => [...values, this.cursor()]);
        await this.router.navigate([], {
            relativeTo: this.route,
            queryParams: {
                ...this.listParams(),
                after: next
            }
        });
    }

    protected async previous(): Promise<void> {
        if (this.loading())
            return;
        const values = this.history();
        const cursor = values.at(-1) ?? null;
        this.history.set(values.slice(0, -1));
        await this.router.navigate([], {
            relativeTo: this.route,
            queryParams: {
                ...this.listParams(),
                after: cursor
            }
        });
    }

    protected canModerate(): boolean {
        return !!this.session.access()?.permissions.moderation && this.detail()?.accountId !== this.auth.user()?.accountId;
    }

    protected protectBeforeUnload(event: BeforeUnloadEvent): void {
        if (this.session.access() && (this.busy() || this.actionUnknown() || this.reason.dirty && !!this.reason.value))
            event.preventDefault();
    }

    canLeave(): boolean {
        return !this.session.access() || !this.busy() && !this.actionUnknown();
    }

    protected async moderate(): Promise<void> {
        const detail = this.detail();
        const action = this.confirmAction();
        if (!detail || !action || !this.canModerate() || this.busy() || this.actionUnknown())
            return;
        const reason = this.reason.value.trim();
        if (action === 'ban' && (!reason || reason.length > 280)) {
            this.actionError.set('Укажите причину блокировки, до 280 символов.');
            return;
        }
        this.busy.set(true);
        this.actionError.set('');
        this.notice.set('');
        try {
            await firstValueFrom((action === 'ban' ? this.api.ban(detail.accountId, reason) : this.api.unban(detail.accountId)).pipe(takeUntilDestroyed(this.destroy)));
            this.confirmAction.set(null);
            this.reason.reset();
            this.notice.set(action === 'ban' ? 'Аккаунт заблокирован.' : 'Аккаунт разблокирован.');
            await this.loadDetail(detail.accountId);
        } catch (error) {
            if (isForbidden(error)) {
                this.detail.set(null);
                this.report.set(null);
            }
            this.actionUnknown.set(unknownOutcome(error));
            this.actionError.set(errorText(error, unknownOutcome(error) ? 'Результат не подтверждён. Обновите карточку и проверьте статус перед новым действием.' : 'Не удалось изменить статус. Проверьте права и состояние аккаунта.'));
        } finally {
            this.busy.set(false);
        }
    }
}

export const canLeaveAdminUsers = (component: AdminUsersPageComponent): boolean => component.canLeave();
