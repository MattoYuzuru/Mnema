import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { AdminApiService } from './admin-api.service';
import { OnboardingExperimentGroup, OnboardingExperimentReport } from './admin.models';
import { addDays, defaultPeriod, errorText, number, percent, reportPeriod } from './admin-presenters';

const VARIANT_NAMES: Readonly<Record<string, string>> = { control: 'Без автопредложения (holdout)', tour: 'Знакомство предлагается' };

/**
 * The holdout of `onboarding_v1` (OB-105): per variant the accounts that entered the cohort in the period, how many finished a
 * scheduled session within 7 days and how many studied on two different days within 14. Aggregates only; a small group is
 * marked so that a difference is not read as a result.
 */
@Component({
    selector: 'app-admin-onboarding-page',
    imports: [ReactiveFormsModule],
    template: `
      <header>
        <p class="eyebrow">Кабинет владельца</p><h1 tabindex="-1">Знакомство: группа сравнения</h1>
        <p class="hint">Новые аккаунты по дате, когда им предложили знакомство (UTC). Только агрегаты: аккаунты не показываются.</p>
      </header>
      <form class="filters" [formGroup]="form" (ngSubmit)="apply()">
        <label class="field"><span>С даты, UTC</span><input type="date" formControlName="from" required></label>
        <label class="field"><span>По дату включительно, UTC</span><input type="date" formControlName="through" required></label>
        <button class="button primary" type="submit">Показать период</button><button class="button" type="button" (click)="load()" [disabled]="loading()">Обновить</button>
      </form>
      @if (loading()) {<p role="status">Собираем отчёт…</p>}
      @if (error()) {<p class="notice error" role="alert">{{ error() }}</p>}
      @if (report(); as data) {
        <p class="hint">{{ data.from }} — {{ through(data.to) }}, UTC · эксперимент {{ data.experiment }}</p>
        @if (data.smallSample) {
          <p class="notice warning">Мало данных: в одной из групп меньше {{ number(data.minimumGroup) }} аккаунтов. Разница между группами пока может быть случайной.</p>
        }
        <div class="table-scroll" tabindex="0" role="region" aria-label="Активация по группам">
          <table class="data-table">
            <caption>Активация — завершённое занятие по расписанию за 7 дней; возврат — занятия в двух разных днях за 14 дней. Недавние аккаунты ещё не прожили эти сроки.</caption>
            <thead><tr><th scope="col">Группа</th><th scope="col">Аккаунты</th><th scope="col">Активация</th><th scope="col">Возврат</th></tr></thead>
            <tbody>
              @for (group of data.groups; track group.variant) {
                <tr>
                  <th scope="row">{{ variant(group.variant) }}</th><td>{{ number(group.accounts) }}</td>
                  <td>{{ number(group.activated) }} · {{ share(group, group.activated) }}</td><td>{{ number(group.returned) }} · {{ share(group, group.returned) }}</td>
                </tr>
              }
              @empty {<tr><td colspan="4">За период новых аккаунтов в эксперименте нет.</td></tr>}
            </tbody>
          </table>
        </div>
      }
    `,
    styleUrl: './admin-page.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class AdminOnboardingPageComponent implements OnInit {
    private readonly api = inject(AdminApiService);
    private readonly route = inject(ActivatedRoute);
    private readonly router = inject(Router);
    private readonly destroy = inject(DestroyRef);
    private epoch = 0;
    protected readonly report = signal<OnboardingExperimentReport | null>(null);
    protected readonly loading = signal(false);
    protected readonly error = signal('');
    protected readonly form = new FormGroup({
        from: new FormControl(defaultPeriod().from, { nonNullable: true }),
        through: new FormControl(defaultPeriod().through, { nonNullable: true })
    });
    protected readonly number = number;

    ngOnInit(): void {
        this.route.queryParamMap.pipe(takeUntilDestroyed(this.destroy)).subscribe(params => {
            const defaults = defaultPeriod();
            this.form.reset({ from: params.get('from') ?? defaults.from, through: params.get('through') ?? defaults.through });
            void this.load();
        });
    }

    protected async apply(): Promise<void> {
        try {
            reportPeriod(this.form.controls.from.value, this.form.controls.through.value);
            await this.router.navigate([], { relativeTo: this.route, queryParams: this.form.getRawValue() });
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
            const report = await firstValueFrom(this.api.onboardingReport(period.from, period.to).pipe(takeUntilDestroyed(this.destroy)));
            if (epoch === this.epoch) this.report.set(report);
        } catch (error) {
            if (epoch === this.epoch) this.error.set(errorText(error, 'Не удалось получить отчёт. Повторите загрузку. Проверьте период: от 1 до 90 дней, не позже сегодня.'));
        } finally {
            if (epoch === this.epoch) this.loading.set(false);
        }
    }

    protected through(value: string): string { return addDays(value, -1); }

    protected variant(name: string): string { return VARIANT_NAMES[name] ?? name; }

    protected share(group: OnboardingExperimentGroup, count: number): string {
        return group.accounts === 0 ? 'Нет данных' : percent(count / group.accounts);
    }
}
