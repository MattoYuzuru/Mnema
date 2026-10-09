import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { AdminApiService, AdminSession } from './admin-api.service';
import { AdminPromo, AdminProtocolError, PromoCreate, PromoKind, PromoPage } from './admin.models';
import { dateTime, errorText, isForbidden, unknownOutcome } from './admin-presenters';
type PromoMutation = {
    readonly kind: 'create';
    readonly commandId: string;
    readonly body: PromoCreate;
} | {
    readonly kind: 'switch';
    readonly id: string;
    readonly enabled: boolean;
};
/** Twelve unambiguous characters; rejection sampling avoids bias if the alphabet changes. */
export function randomPromoCode(): string {
    const alphabet = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789';
    const cutoff = Math.floor(256 / alphabet.length) * alphabet.length;
    let code = '';
    while (code.length < 12)
        for (const value of crypto.getRandomValues(new Uint8Array(24))) {
            if (value < cutoff && code.length < 12)
                code += alphabet[value % alphabet.length];
        }
    return code;
}
function utcFromMoscow(value: string): string | null {
    if (!value)
        return null;
    const date = new Date(`${value}:00+03:00`);
    if (!Number.isFinite(date.getTime()))
        throw new Error('Дата недействительна');
    return date.toISOString();
}

@Component({
    selector: 'app-admin-promos-page',
    host: {
        '(window:beforeunload)': 'protectBeforeUnload($event)'
    },
    imports: [ReactiveFormsModule],
    templateUrl: './admin-promos-page.component.html',
    styleUrl: './admin-page.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class AdminPromosPageComponent implements OnInit {
    private readonly api = inject(AdminApiService);
    private readonly destroy = inject(DestroyRef);
    protected readonly session = inject(AdminSession);
    protected readonly page = signal<PromoPage | null>(null);
    protected readonly loading = signal(false);
    protected readonly listError = signal('');
    protected readonly actionError = signal('');
    protected readonly notice = signal('');
    protected readonly busy = signal(false);
    protected readonly uncertain = signal(false);
    protected readonly createdCode = signal<string | null>(null);
    protected readonly query = signal('');
    protected readonly enabledFilter = signal('');
    protected readonly typeFilter = signal('');
    protected readonly kind = signal<PromoKind>('TIER_DAYS');
    protected readonly filtered = computed(() => (this.page()?.codes ?? []).filter(code => (!this.query() || `${code.hint} ${code.channel ?? ''}`.toLowerCase().includes(this.query().trim().toLowerCase())) && (!this.enabledFilter() || code.enabled === (this.enabledFilter() === 'yes')) && (!this.typeFilter() || code.type === this.typeFilter())));
    protected readonly form = new FormGroup({
        type: new FormControl<PromoKind>('TIER_DAYS', {
            nonNullable: true
        }),
        plan: new FormControl('PLUS', {
            nonNullable: true
        }),
        amount: new FormControl(7, {
            nonNullable: true
        }),
        maxRedemptions: new FormControl(1, {
            nonNullable: true
        }),
        validFrom: new FormControl('', {
            nonNullable: true
        }),
        validUntil: new FormControl('', {
            nonNullable: true
        }),
        oncePerAccount: new FormControl(true, {
            nonNullable: true
        }),
        channel: new FormControl('', {
            nonNullable: true
        }),
        code: new FormControl('', {
            nonNullable: true
        })
    });
    protected readonly date = dateTime;
    protected readonly cursor = signal<string | null>(null);
    protected readonly history = signal<(string | null)[]>([]);
    private mutation: PromoMutation | null = null;
    ngOnInit(): void {
        this.form.controls.type.valueChanges.pipe(takeUntilDestroyed(this.destroy)).subscribe(type => {
            this.kind.set(type);
            this.form.controls.amount.setValue(type === 'DISCOUNT_PERCENT' ? 10 : type === 'TIER_MONTHS' ? 1 : 7);
        });
        if (this.session.access()?.permissions.promos)
            void this.load(null);
    }

    protected async load(after: string | null): Promise<boolean> {
        if (this.loading())
            return false;
        this.loading.set(true);
        this.listError.set('');
        try {
            this.page.set(await firstValueFrom(this.api.promos(after).pipe(takeUntilDestroyed(this.destroy))));
            this.cursor.set(after);
            return true;
        } catch (error) {
            if (isForbidden(error)) {
                this.page.set(null);
                this.createdCode.set(null);
                this.form.reset();
            }
            this.listError.set(errorText(error, 'Список промокодов не загрузился. Поля формы сохранены.'));
            return false;
        } finally {
            this.loading.set(false);
        }
    }

    protected async next(): Promise<void> {
        const next = this.page()?.next, old = this.cursor();
        if (next && await this.load(next))
            this.history.update(values => [...values, old]);
    }

    protected async previous(): Promise<void> {
        const values = this.history();
        if (await this.load(values.at(-1) ?? null))
            this.history.set(values.slice(0, -1));
    }

    protected async create(): Promise<void> {
        if (this.busy() || this.uncertain() || this.createdCode() || !this.session.access()?.permissions.promos)
            return;
        this.actionError.set('');
        try {
            const v = this.form.getRawValue(), type = v.type;
            const max = type === 'TIER_DAYS' ? 366 : type === 'TIER_MONTHS' ? 24 : 90;
            if (!Number.isInteger(v.amount) || v.amount < 1 || v.amount > max || !Number.isInteger(v.maxRedemptions) || v.maxRedemptions < 1 || v.maxRedemptions > 1000000)
                throw new Error(`Количество: от 1 до ${max}; лимит активаций: от 1 до 1 000 000.`);
            const plan = v.plan === '' && type === 'DISCOUNT_PERCENT' ? null : v.plan === 'PLUS' || v.plan === 'PRO' ? v.plan : null;
            if (plan === null && type !== 'DISCOUNT_PERCENT')
                throw new Error('Выберите Plus или Pro.');
            const validFrom = utcFromMoscow(v.validFrom), validUntil = utcFromMoscow(v.validUntil);
            if (type === 'DISCOUNT_PERCENT' && !validUntil)
                throw new Error('Для скидки укажите конец действия.');
            if (validUntil && Date.parse(validUntil) <= (validFrom ? Date.parse(validFrom) : Date.now()))
                throw new Error('Конец действия должен быть позже начала.');
            const channel = v.channel.trim() || null;
            if (channel && !/^[A-Za-z0-9][A-Za-z0-9 _.:/@#+-]{0,39}$/u.test(channel))
                throw new Error('Канал: до 40 латинских букв, цифр и разрешённых знаков.');
            const code = v.code ? v.code.toUpperCase().replace(/[\s_-]/gu, '') : randomPromoCode();
            if (!/^[A-Z0-9]{8,24}$/u.test(code))
                throw new Error('Код: от 8 до 24 латинских букв или цифр после удаления пробелов и разделителей.');
            this.mutation = {
                kind: 'create',
                commandId: crypto.randomUUID(),
                body: {
                    type,
                    plan,
                    days: type === 'TIER_DAYS' ? v.amount : null,
                    months: type === 'TIER_MONTHS' ? v.amount : null,
                    percent: type === 'DISCOUNT_PERCENT' ? v.amount : null,
                    validFrom,
                    validUntil,
                    maxRedemptions: v.maxRedemptions,
                    oncePerAccount: v.oncePerAccount,
                    channel,
                    code
                }
            };
            await this.submit();
        } catch (error) {
            this.actionError.set(error instanceof Error ? error.message : 'Проверьте поля.');
        }
    }

    protected async switchCode(code: AdminPromo): Promise<void> {
        if (this.busy() || this.uncertain())
            return;
        this.mutation = {
            kind: 'switch',
            id: code.codeId,
            enabled: !code.enabled
        };
        await this.submit();
    }

    protected async submit(): Promise<void> {
        const mutation = this.mutation;
        if (!mutation || this.busy())
            return;
        this.busy.set(true);
        this.uncertain.set(false);
        this.actionError.set('');
        this.notice.set('');
        this.form.disable();
        try {
            const result = await firstValueFrom((mutation.kind === 'create' ? this.api.createPromo(mutation.body, mutation.commandId) : this.api.switchPromo(mutation.id, mutation.enabled)).pipe(takeUntilDestroyed(this.destroy)));
            if (mutation.kind === 'create') {
                const returned = result as AdminPromo & {
                    readonly code: string;
                };
                if (returned.code.toUpperCase().replace(/[\s_-]/gu, '') !== mutation.body.code || returned.type !== mutation.body.type || returned.plan !== mutation.body.plan)
                    throw new AdminProtocolError();
                this.createdCode.set(returned.code);
                this.form.reset({
                    type: 'TIER_DAYS',
                    plan: 'PLUS',
                    amount: 7,
                    maxRedemptions: 1,
                    validFrom: '',
                    validUntil: '',
                    oncePerAccount: true,
                    channel: '',
                    code: ''
                });
                this.notice.set('Промокод создан. Сохраните его сейчас: список хранит только подсказку.');
            }
            else {
                this.page.update(page => page ? {
                    ...page,
                    codes: page.codes.map(code => code.codeId === result.codeId ? result : code)
                } : page);
                this.notice.set(result.enabled ? 'Код включён.' : 'Код выключен. Уже выданный доступ сохранён.');
            }
            this.mutation = null;
            if (mutation.kind === 'create') {
                this.history.set([]);
                await this.load(null);
            }
        } catch (error) {
            if (isForbidden(error)) {
                this.page.set(null);
                this.createdCode.set(null);
                this.form.reset();
                this.mutation = null;
            }
            else if (unknownOutcome(error))
                this.uncertain.set(true);
            else
                this.mutation = null;
            this.actionError.set(errorText(error, this.uncertain() ? 'Результат не подтверждён. Повторите ту же команду; поля и код зафиксированы.' : 'Команда отклонена. Проверьте поля, права и лимиты.'));
        } finally {
            this.busy.set(false);
            if (!this.uncertain())
                this.form.enable();
        }
    }

    protected protectBeforeUnload(event: BeforeUnloadEvent): void {
        if (this.session.access() && (this.busy() || this.uncertain() || this.form.dirty || this.createdCode()))
            event.preventDefault();
    }

    canLeave(): boolean {
        if (!this.session.access())
            return true;
        if (this.busy() || this.uncertain())
            return false;
        return !(this.form.dirty || this.createdCode()) || window.confirm(this.createdCode() ? 'Сохраните промокод. После ухода его полный текст исчезнет. Покинуть страницу?' : 'Несохранённая форма промокода будет отброшена. Покинуть страницу?');
    }
}

export const canLeaveAdminPromos = (component: AdminPromosPageComponent): boolean => component.canLeave();
