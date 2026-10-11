import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, Injector, OnInit, afterNextRender, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { learnerOrigin } from '../../app.config';
import { AdminApiService, AdminSession } from './admin-api.service';
import { AdminTakedownPickerComponent } from './admin-takedown-picker.component';
import { ComplaintDecision, ComplaintDetail, DecideRequest, TakedownUnit, uuidOrNull } from './admin-complaints.models';
import { decisionNames, deadlineText, reasonName, targetName, visibilityNames } from './admin-complaints.presenters';
import { dateTime, errorText, isForbidden, unknownOutcome } from './admin-presenters';
import { exercisesText, materialsText } from '../own-decks/hub/deck-hub.text';

/** What the confirmation panel is asking about: one of the four decisions, or returning a hidden deck to the Community. */
type Pending = ComplaintDecision | 'UNHIDE';

/**
 * One complaint of the owner console (`/manage/complaints/:id`): who and what it is about, the review deadline in words, the live state of
 * the deck, and the decisions. Every decision is asked twice, in the console's confirmation pattern (a warning that says what happens, then
 * «Подтвердить» or «Отмена»; no `confirm()`): the API acts at once. A complaint has one decision; the legal takedown can be extended with
 * more units, and a hidden deck is returned by «Вернуть в Сообщество». A repeat of a decision is harmless (the API answers «changed: false»),
 * so an unknown outcome is simply retried. The picker for the takedown is {@link AdminTakedownPickerComponent}.
 */
@Component({
    selector: 'app-admin-complaint-detail-page',
    host: { '(window:beforeunload)': 'protectBeforeUnload($event)' },
    imports: [RouterLink, AdminTakedownPickerComponent],
    templateUrl: './admin-complaint-detail-page.component.html',
    styleUrl: './admin-page.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class AdminComplaintDetailPageComponent implements OnInit {
    private readonly api = inject(AdminApiService);
    private readonly route = inject(ActivatedRoute);
    private readonly destroy = inject(DestroyRef);
    private readonly injector = inject(Injector);
    private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
    protected readonly session = inject(AdminSession);
    private epoch = 0;
    /** The button that opened the confirmation: «Отмена» gives the focus back to it. */
    private asked: Pending | null = null;

    protected readonly complaintId = signal<string | null>(null);
    protected readonly detail = signal<ComplaintDetail | null>(null);
    protected readonly loading = signal(false);
    protected readonly loadError = signal('');
    protected readonly missing = signal(false);
    protected readonly pending = signal<Pending | null>(null);
    protected readonly units = signal<readonly TakedownUnit[]>([]);
    protected readonly alsoHide = signal(false);
    protected readonly busy = signal(false);
    protected readonly notice = signal('');
    protected readonly actionError = signal('');
    protected readonly decisions = decisionNames;
    protected readonly visibilities = visibilityNames;
    protected readonly date = dateTime;
    protected readonly target = targetName;
    protected readonly materialsText = materialsText;
    protected readonly exercisesText = exercisesText;
    protected readonly reasonOf = reasonName;

    /** The decisions that fit the complaint right now. A decided complaint keeps only what the API still accepts. */
    protected readonly offered = computed<readonly ComplaintDecision[]>(() => {
        const detail = this.detail();
        if (detail === null) return [];
        if (detail.status === 'OPEN') return detail.targetKind === 'DECK' ? ['REJECTED', 'HIDDEN', 'TAKEN_DOWN'] : ['REJECTED', 'ACCOUNT_REFERRED'];
        // The legal takedown may name more units after the decision; nothing else changes once a complaint is decided.
        return detail.decision === 'TAKEN_DOWN' ? ['TAKEN_DOWN'] : [];
    });
    /**
     * «Вернуть в Сообщество» exists only where the API accepts it (`:unhide`, else `409 COMPLAINT_NOT_HIDDEN`): the complaint is decided HIDDEN
     * and its deck is hidden now. A hide made by a takedown with «скрыть» is lifted through a complaint decided HIDDEN, see {@link hideHint}.
     */
    protected readonly canUnhide = computed(() => {
        const detail = this.detail();
        return detail?.decision === 'HIDDEN' && detail.deck?.hiddenByModeration === true;
    });
    /** Why there is no «Вернуть в Сообщество» on a hidden deck, or what the «Скрыть» decision is still for. Empty when nothing needs saying. */
    protected readonly hideHint = computed(() => {
        const detail = this.detail();
        if (detail?.deck?.hiddenByModeration !== true || this.canUnhide()) return '';
        return detail.status === 'OPEN'
            ? 'Колода уже скрыта. Решение «Скрыть из Сообщества» на этой жалобе нужно, чтобы потом вернуть колоду через неё.'
            : 'Колода скрыта, но не этой жалобой. Чтобы вернуть её, примите решение «Скрыть из Сообщества» на открытой жалобе об этой колоде и вернуть колоду через неё.';
    });
    protected readonly deadline = computed(() => {
        const detail = this.detail();
        return detail === null ? '' : deadlineText(detail, Date.now());
    });
    protected readonly deckUrl = computed(() => {
        const code = this.detail()?.deck?.publicCode ?? null;
        return code === null ? null : `${learnerOrigin(window.location.hostname, window.location.origin)}/d/${encodeURIComponent(code)}`;
    });
    protected readonly pickedText = computed(() => {
        const units = this.units();
        const items = units.filter(unit => unit.kind === 'ITEM').length;
        return [...(items > 0 ? [materialsText(items)] : []), ...(units.length - items > 0 ? [exercisesText(units.length - items)] : [])].join(' и ');
    });

    ngOnInit(): void {
        this.route.paramMap.pipe(takeUntilDestroyed(this.destroy)).subscribe(params => {
            const id = uuidOrNull(params.get('complaintId'));
            this.complaintId.set(id);
            this.reset();
            this.notice.set('');
            this.actionError.set('');
            if (id === null) { this.detail.set(null); this.missing.set(true); return; }
            void this.load(id);
        });
    }

    protected async load(id: string | null = this.complaintId()): Promise<void> {
        if (id === null) return;
        const epoch = ++this.epoch;
        this.loading.set(true);
        this.loadError.set('');
        this.missing.set(false);
        try {
            const detail = await firstValueFrom(this.api.complaint(id).pipe(takeUntilDestroyed(this.destroy)));
            if (epoch === this.epoch) this.detail.set(detail);
        } catch (error) {
            if (epoch !== this.epoch) return;
            this.detail.set(null);
            if (error instanceof HttpErrorResponse && error.status === 404) this.missing.set(true);
            else this.loadError.set(errorText(error, 'Не удалось загрузить жалобу. Повторите запрос.'));
        } finally {
            if (epoch === this.epoch) this.loading.set(false);
        }
    }

    protected ask(action: Pending): void {
        if (this.busy()) return;
        this.actionError.set('');
        this.notice.set('');
        this.pending.set(action);
        this.asked = action;
        if (action === 'TAKEN_DOWN') { this.units.set([]); this.alsoHide.set(false); }
        // The button that was pressed leaves the page with the question: the warning takes the focus, so the keyboard and the screen reader land on it.
        this.focusLater('#confirm-warning');
    }

    /** «Отмена»: back to the buttons, with the focus on the one that was pressed. */
    protected cancel(): void {
        if (this.busy()) return;
        const asked = this.asked;
        this.reset();
        if (asked !== null) this.focusLater(`[data-action="${asked}"]`);
    }

    private reset(): void {
        this.pending.set(null);
        this.units.set([]);
        this.alsoHide.set(false);
    }

    private focusLater(selector: string): void {
        afterNextRender(() => this.host.nativeElement.querySelector<HTMLElement>(selector)?.focus(), { injector: this.injector });
    }

    protected setUnits(units: readonly TakedownUnit[]): void { this.units.set(units); }

    protected confirmDisabled(): boolean {
        return this.busy() || (this.pending() === 'TAKEN_DOWN' && this.units().length === 0);
    }

    protected async confirm(): Promise<void> {
        const detail = this.detail();
        const action = this.pending();
        if (detail === null || action === null || this.confirmDisabled()) return;
        this.busy.set(true);
        this.actionError.set('');
        this.notice.set('');
        try {
            const result = await firstValueFrom((action === 'UNHIDE' ? this.api.unhideComplaint(detail.complaintId)
                : this.api.decideComplaint(detail.complaintId, this.request(action))).pipe(takeUntilDestroyed(this.destroy)));
            ++this.epoch;
            this.detail.set(result);
            this.notice.set(this.success(action, result.changed));
            this.pending.set(null);
            this.units.set([]);
            this.alsoHide.set(false);
            // The confirmation and the button that opened it are gone: the focus goes to the result, a status region that is always on the page.
            this.focusLater('#decision-status');
        } catch (error) {
            if (isForbidden(error)) this.detail.set(null);
            else if (error instanceof HttpErrorResponse && error.status === 409) {
                // COMPLAINT_NOT_HIDDEN: «Вернуть» was asked for a deck that is not hidden now or a complaint that did not hide it; otherwise the complaint has another decision.
                const notHidden = action === 'UNHIDE' || (error.error as { code?: unknown } | null)?.code === 'COMPLAINT_NOT_HIDDEN';
                this.actionError.set(notHidden
                    ? 'Вернуть колоду не получилось: по этой жалобе она не скрыта. Загружаем жалобу заново.'
                    : 'Жалоба уже решена другим решением. Загружаем её заново.');
                this.reset();
                void this.load();
                this.focusLater('#decision-title');
            } else if (error instanceof HttpErrorResponse && error.status === 404) {
                this.actionError.set('Жалобы или колоды больше нет. Обновите страницу.');
            } else {
                this.actionError.set(errorText(error, unknownOutcome(error)
                    ? 'Результат не подтверждён. Решение можно отправить ещё раз: повтор ничего не задвоит.'
                    : 'Решение не принято. Проверьте выбор и повторите.'));
            }
        } finally {
            this.busy.set(false);
        }
    }

    protected protectBeforeUnload(event: BeforeUnloadEvent): void {
        if (this.session.access() && this.busy()) event.preventDefault();
    }

    canLeave(): boolean { return !this.session.access() || !this.busy(); }

    protected confirmText(action: Pending): string {
        switch (action) {
            case 'REJECTED': return 'Отклонить жалобу? Колода или профиль останутся как есть, жалоба закроется.';
            case 'HIDDEN': return 'Скрыть колоду из Сообщества? Другие читатели перестанут её видеть, а ссылки на неё перестанут открываться. Автор получит уведомление, копии других людей останутся у них.';
            case 'ACCOUNT_REFERRED': return 'Продолжить в разделе «Пользователи»? Здесь ничего не меняется: блокировку или скрытие профиля вы делаете там. Жалоба закроется.';
            case 'UNHIDE': return 'Вернуть колоду в Сообщество? Она снова станет видна читателям по своему уровню доступа. Автор отдельного уведомления не получит.';
            case 'TAKEN_DOWN': return this.units().length === 0
                ? 'Отметьте ниже, что снять. Снятие нельзя отменить в кабинете.'
                : `Снять по закону: ${this.pickedText()}? Эти версии перестанут открываться в колоде и в копиях, где их не меняли, а их картинки, звук и видео перестанут показываться везде, даже в новых версиях. Автор получит уведомление. Снятие нельзя отменить в кабинете.`;
        }
    }

    protected confirmLabel(action: Pending): string {
        return { REJECTED: 'Подтвердить отклонение', HIDDEN: 'Подтвердить скрытие', ACCOUNT_REFERRED: 'Подтвердить и закрыть жалобу', UNHIDE: 'Подтвердить возврат', TAKEN_DOWN: 'Подтвердить снятие' }[action];
    }

    protected askLabel(action: ComplaintDecision, decided: boolean): string {
        return { REJECTED: 'Отклонить жалобу', HIDDEN: 'Скрыть из Сообщества', ACCOUNT_REFERRED: 'Продолжить в разделе «Пользователи»',
            TAKEN_DOWN: decided ? 'Добавить к снятию…' : 'Снять материалы по закону…' }[action];
    }

    private request(action: ComplaintDecision): DecideRequest {
        return action === 'TAKEN_DOWN' ? { decision: 'TAKEN_DOWN', units: this.units(), hide: this.alsoHide() } : { decision: action };
    }

    private success(action: Pending, changed: boolean | null): string {
        if (changed === false) return 'Ничего не изменилось: это решение уже было принято.';
        switch (action) {
            case 'REJECTED': return 'Жалоба отклонена и закрыта.';
            case 'HIDDEN': return 'Колода скрыта из Сообщества. Автор получил уведомление.';
            case 'ACCOUNT_REFERRED': return 'Жалоба закрыта. Продолжите в разделе «Пользователи».';
            case 'UNHIDE': return 'Колода возвращена в Сообщество.';
            case 'TAKEN_DOWN': return 'Материалы сняты. Автор получил уведомление.';
        }
    }
}

export const canLeaveAdminComplaint = (component: AdminComplaintDetailPageComponent): boolean => component.canLeave();
