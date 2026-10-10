import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { combineLatest, firstValueFrom, map } from 'rxjs';
import { AutoLoadComponent } from '../../shared/auto-load.component';
import { AdminApiService, AdminSession } from './admin-api.service';
import { Conversation, SupportTicket, TicketCommand, TicketStatus } from './admin.models';
import { CursorList } from './cursor-list';
import {
    bytes, dateTime, deliveryNames, errorText, isForbidden, ticketCategories, ticketStatuses, unknownOutcome
} from './admin-presenters';
@Component({
    selector: 'app-admin-support-page',
    host: {
        '(window:beforeunload)': 'protectBeforeUnload($event)'
    },
    imports: [ReactiveFormsModule, RouterLink, AutoLoadComponent],
    templateUrl: './admin-support-page.component.html',
    styleUrls: ['./admin-page.css', './admin-support-page.component.css'],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class AdminSupportPageComponent implements OnInit {
    private readonly api = inject(AdminApiService);
    private readonly route = inject(ActivatedRoute);
    private readonly router = inject(Router);
    private readonly destroy = inject(DestroyRef);
    protected readonly session = inject(AdminSession);
    private threadEpoch = 0;
    private mutation: {
        readonly id: string;
        readonly command: TicketCommand;
    } | null = null;
    protected readonly tickets = new CursorList<SupportTicket>({
        fetch: cursor => this.api.tickets({ ...this.listParams(), before: cursor }).pipe(map(page => ({ items: page.entries, next: page.nextCursor }))),
        key: ticket => ticket.id,
        destroy: this.destroy,
        firstError: 'Очередь недоступна. Проверьте подключение бота и повторите. Текст ответа сохранён.',
        moreError: 'Следующие обращения не загрузились. Текст ответа сохранён.',
        onForbidden: () => this.clearPrivate()
    });
    protected readonly thread = signal<Conversation | null>(null);
    protected readonly selectedId = signal<string | null>(null);
    protected readonly threadLoading = signal(false);
    protected readonly moreLoading = signal(false);
    protected readonly moreError = signal<string | null>(null);
    protected readonly threadContext = signal(0);
    protected readonly busy = signal(false);
    protected readonly uncertain = signal(false);
    protected readonly threadError = signal('');
    protected readonly actionError = signal('');
    protected readonly notice = signal('');
    protected readonly pendingSelection = signal<SupportTicket | null>(null);
    protected readonly listParams = signal<Record<string, string>>({});
    protected readonly filters = new FormGroup({
        q: new FormControl('', {
            nonNullable: true
        }),
        status: new FormControl('open', {
            nonNullable: true
        }),
        category: new FormControl('', {
            nonNullable: true
        }),
        delivery: new FormControl('', {
            nonNullable: true
        }),
        userId: new FormControl('', {
            nonNullable: true
        }),
        accountId: new FormControl('', {
            nonNullable: true
        })
    });
    protected readonly composer = new FormGroup({
        type: new FormControl<'reply' | 'note'>('reply', {
            nonNullable: true
        }),
        text: new FormControl('', {
            nonNullable: true
        })
    });
    protected readonly statusValues: readonly TicketStatus[] = ['open', 'working', 'waiting', 'closed'];
    protected readonly hiddenMessages = signal(0);
    protected readonly date = dateTime;
    protected readonly categories = ticketCategories;
    protected readonly statuses = ticketStatuses;
    protected readonly deliveries = deliveryNames;
    protected readonly bytes = bytes;
    ngOnInit(): void {
        combineLatest([this.route.paramMap, this.route.queryParamMap]).pipe(takeUntilDestroyed(this.destroy)).subscribe(([params, query]) => {
            const values = {
                q: query.get('q') ?? '',
                status: query.get('status') ?? (query.has('accountId') ? '' : 'open'),
                category: query.get('category') ?? '',
                delivery: query.get('delivery') ?? '',
                userId: query.get('userId') ?? '',
                accountId: query.get('accountId') ?? ''
            };
            this.filters.reset(values);
            this.listParams.set(values);
            const id = params.get('ticketId');
            if (id !== this.selectedId()) {
                this.composer.reset({
                    type: 'reply',
                    text: ''
                });
                this.thread.set(null);
                this.actionError.set('');
                this.notice.set('');
                this.pendingSelection.set(null);
            }
            this.selectedId.set(id);
            if (this.session.access()?.permissions.support) {
                void this.tickets.reload();
                if (id)
                    void this.loadThread(id);
                else {
                    ++this.threadEpoch;
                    this.thread.set(null);
                }
            }
        });
    }

    protected async apply(): Promise<void> {
        if (this.busy() || this.uncertain())
            return;
        await this.router.navigate(['/manage/support'], {
            queryParams: this.filters.getRawValue()
        });
    }

    protected async reset(): Promise<void> {
        this.filters.reset({
            q: '',
            status: '',
            category: '',
            delivery: '',
            userId: '',
            accountId: ''
        });
        await this.apply();
    }

    protected async choose(ticket: SupportTicket): Promise<void> {
        if (this.busy() || this.uncertain() || ticket.id === this.selectedId())
            return;
        if (this.composer.dirty && this.composer.controls.text.value) {
            this.pendingSelection.set(ticket);
            return;
        }
        await this.open(ticket);
    }

    private async open(ticket: SupportTicket): Promise<void> {
        await this.router.navigate(['/manage/support', ticket.id], {
            queryParams: this.listParams()
        });
    }

    protected async discardAndOpen(): Promise<void> {
        if (this.busy() || this.uncertain())
            return;
        const ticket = this.pendingSelection();
        if (!ticket)
            return;
        this.composer.reset({
            type: 'reply',
            text: ''
        });
        this.pendingSelection.set(null);
        await this.open(ticket);
    }

    protected async loadThread(id: string, append = false): Promise<void> {
        const epoch = append ? this.threadEpoch : ++this.threadEpoch;
        if (append) {
            if (this.moreLoading() || !this.thread()?.nextMessageCursor)
                return;
            this.moreLoading.set(true);
            this.moreError.set(null);
        }
        else {
            this.threadLoading.set(true);
            this.threadContext.update(value => value + 1);
            this.moreError.set(null);
            this.threadError.set('');
        }
        try {
            const after = append ? this.thread()?.nextMessageCursor ?? null : null;
            const result = await firstValueFrom(this.api.conversation(id, after).pipe(takeUntilDestroyed(this.destroy)));
            if (epoch === this.threadEpoch && id === this.selectedId()) {
                const previous = this.thread();
                const seen = new Set(previous?.messages.map(message => message.id));
                const messages = append && previous ? [...previous.messages, ...result.messages.filter(message => !seen.has(message.id))] : result.messages;
                this.hiddenMessages.set(append ? this.hiddenMessages() + Math.max(0, messages.length - 200) : 0);
                this.thread.set({
                    ...result,
                    messages: messages.slice(-200)
                });
            }
        } catch (error) {
            if (epoch === this.threadEpoch) {
                if (isForbidden(error))
                    this.clearPrivate();
                if (append)
                    this.moreError.set(errorText(error, 'Следующие сообщения не загрузились. Текст ответа сохранён.'));
                else
                    this.threadError.set(errorText(error, 'Переписка не загрузилась. Текст ответа сохранён; попробуйте обновить переписку.'));
            }
        } finally {
            if (epoch === this.threadEpoch) {
                this.threadLoading.set(false);
                this.moreLoading.set(false);
            }
        }
    }

    protected async send(): Promise<void> {
        const thread = this.thread();
        if (!thread || this.busy() || this.uncertain())
            return;
        const text = this.composer.controls.text.value.trim();
        if (!text || text.length > 3500) {
            this.actionError.set('Введите сообщение: от 1 до 3500 символов.');
            return;
        }
        this.mutation = {
            id: thread.ticket.id,
            command: {
                commandId: crypto.randomUUID(),
                expectedVersion: thread.ticket.version,
                type: this.composer.controls.type.value,
                text
            }
        };
        await this.submit();
    }

    protected async changeStatus(status: TicketStatus): Promise<void> {
        const thread = this.thread();
        if (!thread || this.busy() || this.uncertain() || thread.ticket.status === status)
            return;
        this.mutation = {
            id: thread.ticket.id,
            command: {
                commandId: crypto.randomUUID(),
                expectedVersion: thread.ticket.version,
                type: 'status',
                status
            }
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
        this.composer.disable();
        try {
            const receipt = await firstValueFrom(this.api.ticketCommand(mutation.id, mutation.command).pipe(takeUntilDestroyed(this.destroy)));
            if (receipt.commandId !== mutation.command.commandId || receipt.ticketId !== mutation.id)
                throw new Error('Uncorrelated receipt');
            if (mutation.command.type !== 'status')
                this.composer.reset({
                    type: mutation.command.type,
                    text: ''
                });
            this.notice.set(mutation.command.type === 'reply' ? 'Ответ принят в очередь. Это ещё не подтверждение доставки Telegram.' : mutation.command.type === 'note' ? 'Внутренняя заметка сохранена. Пользователь её не получает.' : 'Статус обращения сохранён.');
            this.mutation = null;
            await this.loadThread(mutation.id);
            await this.tickets.reload();
        } catch (error) {
            if (isForbidden(error)) {
                this.clearPrivate();
                this.mutation = null;
            }
            else if (unknownOutcome(error))
                this.uncertain.set(true);
            else
                this.mutation = null;
            this.actionError.set(errorText(error, this.uncertain() ? 'Результат команды неизвестен. Повторите ту же команду, чтобы получить её подтверждение.' : 'Команда отклонена или обращение уже изменилось. Текст сохранён. Обновите переписку, сравните её и отправьте снова явно.'));
        } finally {
            this.busy.set(false);
            if (!this.uncertain())
                this.composer.enable();
        }
    }

    private clearPrivate(): void {
        ++this.threadEpoch;
        this.mutation = null;
        this.uncertain.set(false);
        this.busy.set(false);
        this.tickets.clear();
        this.thread.set(null);
        this.composer.reset({
            type: 'reply',
            text: ''
        });
        this.pendingSelection.set(null);
    }

    protected protectBeforeUnload(event: BeforeUnloadEvent): void {
        if (this.session.access() && (this.busy() || this.uncertain() || this.composer.dirty && !!this.composer.controls.text.value))
            event.preventDefault();
    }

    canLeave(): boolean {
        if (!this.session.access())
            return true;
        if (this.busy() || this.uncertain())
            return false;
        return !this.composer.dirty || !this.composer.controls.text.value || window.confirm('Текст ответа не отправлен. Покинуть обращение и отбросить его?');
    }
}

export const canLeaveAdminSupport = (component: AdminSupportPageComponent): boolean => component.canLeave();
