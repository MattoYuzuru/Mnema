import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { ToggletipComponent } from '../../shared/toggletip.component';
import { AdminApiService } from './admin-api.service';
import { AdminReport } from './admin.models';
import {
    mediaState, operationUnits, addDays, bytes, dateTime, defaultPeriod, errorText, isForbidden, milliseconds, moneyRub, moneyUsd, number,
    operationName, percent, reportPeriod
} from './admin-presenters';
@Component({
    selector: 'app-admin-report-page',
    imports: [ReactiveFormsModule, RouterLink, ToggletipComponent],
    templateUrl: './admin-report-page.component.html',
    styleUrl: './admin-page.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class AdminReportPageComponent implements OnInit {
    private readonly api = inject(AdminApiService);
    private readonly route = inject(ActivatedRoute);
    private readonly router = inject(Router);
    private readonly destroy = inject(DestroyRef);
    private epoch = 0;
    protected readonly report = signal<AdminReport | null>(null);
    protected readonly loading = signal(false);
    protected readonly error = signal('');
    protected readonly form = new FormGroup({
        from: new FormControl(defaultPeriod().from, {
            nonNullable: true
        }),
        through: new FormControl(defaultPeriod().through, {
            nonNullable: true
        })
    });
    protected readonly mediaState = mediaState;
    protected readonly units = operationUnits;
    protected readonly money = moneyUsd;
    protected readonly rub = moneyRub;
    protected readonly ms = milliseconds;
    protected readonly number = number;
    protected readonly percent = percent;
    protected readonly bytes = bytes;
    protected readonly date = dateTime;
    protected readonly operation = operationName;
    ngOnInit(): void {
        this.route.queryParamMap.pipe(takeUntilDestroyed(this.destroy)).subscribe(params => {
            const defaults = defaultPeriod();
            this.form.reset({
                from: params.get('from') ?? defaults.from,
                through: params.get('through') ?? defaults.through
            });
            void this.load();
        });
    }

    protected async apply(): Promise<void> {
        try {
            reportPeriod(this.form.controls.from.value, this.form.controls.through.value);
            await this.router.navigate([], {
                relativeTo: this.route,
                queryParams: this.form.getRawValue()
            });
        } catch {
            this.error.set('Выберите существующие даты: от 1 до 90 дней, не позже сегодня.');
        }
    }

    protected async load(): Promise<void> {
        const epoch = ++this.epoch;
        this.error.set('');
        this.loading.set(true);
        this.report.set(null);
        try {
            const period = reportPeriod(this.form.controls.from.value, this.form.controls.through.value);
            const report = await firstValueFrom(this.api.report(period.from, period.to).pipe(takeUntilDestroyed(this.destroy)));
            if (epoch === this.epoch)
                this.report.set(report);
        } catch (error) {
            if (epoch === this.epoch) {
                if (isForbidden(error))
                    this.report.set(null);
                this.error.set(errorText(error, 'Не удалось получить отчёт. Повторите загрузку. Проверьте период: от 1 до 90 дней, не позже сегодня.'));
            }
        } finally {
            if (epoch === this.epoch)
                this.loading.set(false);
        }
    }

    protected through(value: string): string {
        return addDays(value, -1);
    }
}
