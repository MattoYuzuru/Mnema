import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, Injector, OnInit, afterNextRender, computed, inject, signal, viewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { firstValueFrom, map } from 'rxjs';
import { learnerOrigin } from '../../app.config';
import { AutoLoadComponent } from '../../shared/auto-load.component';
import { OrderRowComponent } from '../../shared/order-row.component';
import { AdminApiService, AdminSession } from './admin-api.service';
import { AnomalyFlag, EDITORIAL_MAX, EditorialCommand, offendingCodes, parseDeckInput } from './admin-community.models';
import { dateTime, errorText, isForbidden, unknownOutcome } from './admin-presenters';
import { CursorList } from './cursor-list';

interface ShelfRow {
    readonly code: string;
    /** `null`: added in this session, the server names the deck after the list is saved. */
    readonly title: string | null;
    readonly listed: boolean;
    readonly error: string | null;
}

interface PendingSave {
    readonly commandId: string;
    readonly codes: readonly string[];
    readonly version: string;
}

const percent = new Intl.NumberFormat('ru-RU', { style: 'percent', maximumFractionDigits: 0 });
const NOT_FIT = 'Эту колоду нельзя поставить на полку: она не публичная или не опубликована. Уберите её или замените.';
const FULL_HINT = `На полке уже ${EDITORIAL_MAX} колод. Уберите одну, чтобы добавить другую.`;
const sameCodes = (a: readonly string[], b: readonly string[]): boolean => a.length === b.length && a.every((code, index) => code === b[index]);

/**
 * The community section of the owner console (`/manage/community`, Community/7 and Community/3).
 *
 * «Выбор Мнемы»: the owner's shelf of at most 24 public decks. The list is edited locally (add by link or code, «Выше», «Ниже»,
 * «Убрать») and saved as a whole with a fresh `commandId` and `If-Match` = the row version it was read at; an unknown outcome
 * repeats the same command. A stale version (412) reloads the actual list and drops the local edits with a notice; a 400 marks the
 * rows the server named. «Флаги накруток»: the daily job's flags as a keyset list with the shared auto-load; «Просмотрено» dismisses
 * one with its own `commandId`. Leaving with unsaved shelf changes asks first.
 */
@Component({
    selector: 'app-admin-community-page',
    host: { '(window:beforeunload)': 'protectBeforeUnload($event)' },
    imports: [AutoLoadComponent, OrderRowComponent],
    template: `
  <header>
    <p class="eyebrow">Кабинет владельца</p><h1 tabindex="-1">Сообщество</h1>
    <p class="hint">Полка «Выбор Мнемы» и флаги накруток. Каждое изменение попадает в журнал действий.</p>
  </header>

  <section class="rule-section" aria-labelledby="shelf-title">
    <h2 id="shelf-title">Выбор Мнемы</h2>
    <p class="hint">
      До {{ max }} публичных колод, которые Сообщество показывает первыми, в порядке списка. Колода со штампом «Не в каталоге» остаётся в списке,
      но читатели её не видят: она скрыта или больше не публична.
    </p>
    @if (shelfError()) {
      <div class="notice error" role="alert">
        <p>{{ shelfError() }}</p><button class="button" type="button" (click)="loadShelf()">Повторить</button>
      </div>
    }
    @if (notice()) { <div class="notice warning" role="alert"><p>{{ notice() }}</p></div> }
    @if (saveError()) { <div class="notice error" role="alert" tabindex="-1" id="shelf-save-error"><p>{{ saveError() }}</p></div> }
    @if (loaded()) {
      @if (rows().length > 0) {
        <ol class="shelf-list" role="list" aria-label="Колоды полки в порядке показа">
          @for (row of rows(); track row.code; let index = $index) {
            <li [attr.data-code]="row.code">
              <app-order-row [position]="index + 1" [total]="rows().length" [label]="name(row)" [error]="row.error" [busy]="busy()"
                (moveUp)="move(row.code, -1)" (moveDown)="move(row.code, 1)" (remove)="remove(row.code)">
                <span class="shelf-title">{{ name(row) }}</span>
                <span class="hint">Код <code>{{ row.code }}</code>@if (row.title === null) { · название появится после сохранения }</span>
                @if (!row.listed) { <span><span class="stamp">Не в каталоге</span></span> }
              </app-order-row>
            </li>
          }
        </ol>
      } @else {
        <p class="hint">На полке пока нет колод.</p>
      }
      <form class="shelf-add" (submit)="add($event)" novalidate>
        <div class="field">
          <label for="shelf-add-input">Добавить колоду</label>
          <div class="field-row">
            <input #deckInput id="shelf-add-input" name="deck" type="text" inputmode="url" autocomplete="off" autocapitalize="off" spellcheck="false"
              [value]="draft()" (input)="typed($event)" [attr.aria-invalid]="addError() ? 'true' : null" [attr.aria-describedby]="addError() ? 'shelf-add-hint shelf-add-error' : 'shelf-add-hint'" />
            <button class="button" type="submit" [attr.aria-disabled]="full() || busy() ? 'true' : null" [attr.aria-describedby]="full() ? 'shelf-add-hint' : null">Добавить</button>
          </div>
          <p class="hint" id="shelf-add-hint">{{ full() ? fullHint : 'Публичная ссылка на колоду вида https://mnema.app/d/Kq7xT3mNpR или код из 10 знаков.' }}</p>
          @if (addError()) { <p class="field-error" id="shelf-add-error" role="alert">{{ addError() }}</p> }
        </div>
      </form>
      <div class="actions shelf-save">
        <button #saveButton class="button primary" type="button" [attr.aria-disabled]="canSave() ? null : 'true'" [attr.aria-busy]="busy() ? 'true' : null" (click)="save()">Сохранить</button>
        @if (dirty()) { <p class="hint">Есть несохранённые изменения.</p> }
      </div>
    }
    <!-- Both status regions are in the tree, empty, before their first text: a region that appears with its text is not announced reliably. -->
    <p class="status-region" role="status">{{ !loaded() && shelfLoading() ? 'Загружаем полку…' : status() }}</p>
  </section>

  <section class="rule-section" aria-labelledby="flags-title">
    <h2 id="flags-title">Флаги накруток</h2>
    <p class="hint">
      Раз в сутки Мнема отмечает колоду, если за 7 дней её скопировали не меньше 20 раз и больше 70 % копий сделали аккаунты младше 48 часов. Сама колода не меняется:
      посмотрите её и отметьте флаг «Просмотрено». Если колода уже не публична, ссылка откроет «Колода не найдена».
    </p>
    <p class="status-region" role="status">{{ flags.loading() ? 'Загружаем флаги…' : flagStatus() }}</p>
    @if (flags.error()) {
      <div class="notice error" role="alert">
        <p>{{ flags.error() }}</p><button class="button" type="button" (click)="flags.reload()">Повторить</button>
      </div>
    }
    @if (flags.items().length > 0) {
    <ul #flagList class="flag-list" role="list" aria-label="Флаги накруток: открытые первыми, дальше просмотренные">
      @for (flag of flags.items(); track flag.code) {
        <li class="flag-row">
          <h3 tabindex="-1" [id]="'flag-' + flag.code"><a [href]="deckUrl(flag.code)" target="_blank" rel="noopener noreferrer" [attr.aria-label]="flag.title + ' (новая вкладка)'">{{ flag.title }}</a></h3>
          <p>Копий за 7 дней: <strong>{{ flag.windowForks }}</strong> · Новых аккаунтов: <strong>{{ share(flag) }}</strong></p>
          <p class="hint">Код <code>{{ flag.code }}</code> · выявлено {{ date(flag.detectedAt) }}</p>
          <p>
            @if (flag.dismissedAt === null) { <span class="stamp solid">Открыт</span> }
            @else { <span class="stamp">Просмотрен {{ date(flag.dismissedAt) }}</span> }
          </p>
          @if (flag.dismissedAt === null) {
            <p><button class="button small" type="button" [attr.aria-label]="'Просмотрено: ' + flag.title" [attr.aria-busy]="dismissing().has(flag.code) ? 'true' : null" [attr.aria-describedby]="flagErrors()[flag.code] ? 'flag-error-' + flag.code : null"
              [attr.aria-disabled]="dismissing().has(flag.code) ? 'true' : null" (click)="dismiss(flag)">Просмотрено</button></p>
          }
          @if (flagErrors()[flag.code]; as message) { <p class="field-error" role="alert" [id]="'flag-error-' + flag.code">{{ message }}</p> }
        </li>
      }
    </ul>
    <app-auto-load [content]="flagList" [context]="flags.context()" [continuation]="flags.next()" [loading]="flags.loadingMore()" [error]="flags.moreError()"
      loadingText="Загружаем следующие флаги…" (loadNext)="flags.more()" />
    } @else if (!flags.loading() && !flags.error()) { <p>Флагов нет.</p> }
  </section>`,
    styleUrl: './admin-page.css',
    styles: [`
      .shelf-list, .flag-list { list-style: none; margin: var(--mn-space-4) 0; padding: 0; }
      .shelf-title { font-weight: 600; }
      .shelf-add { margin-block: var(--mn-space-5); max-inline-size: 36rem; }
      .shelf-save { align-items: center; }
      .flag-row { display: grid; gap: var(--mn-space-1); padding-block: var(--mn-space-4); border-block-end: 1px solid var(--mn-rule); }
      .flag-row > * { margin: 0; }
      .flag-row h3 { margin-block: 0; overflow-wrap: anywhere; }
      .flag-row h3:focus-visible, #shelf-save-error:focus-visible { outline: 3px solid var(--mn-focus); outline-offset: 3px; }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class AdminCommunityPageComponent implements OnInit {
    private readonly api = inject(AdminApiService);
    private readonly session = inject(AdminSession);
    private readonly destroy = inject(DestroyRef);
    private readonly injector = inject(Injector);
    protected readonly max = EDITORIAL_MAX;
    protected readonly fullHint = FULL_HINT;

    protected readonly rows = signal<readonly ShelfRow[]>([]);
    private readonly baseline = signal<readonly string[]>([]);
    private readonly rowVersion = signal<string | null>(null);
    protected readonly loaded = signal(false);
    protected readonly shelfLoading = signal(false);
    protected readonly shelfError = signal('');
    protected readonly saveError = signal('');
    protected readonly notice = signal('');
    protected readonly status = signal('');
    protected readonly busy = signal(false);
    protected readonly draft = signal('');
    protected readonly addError = signal('');
    protected readonly full = computed(() => this.rows().length >= EDITORIAL_MAX);
    protected readonly dirty = computed(() => !sameCodes(this.rows().map(row => row.code), this.baseline()));
    protected readonly canSave = computed(() => this.dirty() && !this.busy() && this.rowVersion() !== null);
    private readonly saveButton = viewChild<ElementRef<HTMLButtonElement>>('saveButton');
    private readonly deckInput = viewChild<ElementRef<HTMLInputElement>>('deckInput');
    private pending: PendingSave | null = null;
    private epoch = 0;

    protected readonly flags = new CursorList<AnomalyFlag>({
        fetch: cursor => this.api.anomalies(cursor).pipe(map(page => ({ items: page.items, next: page.next }))),
        key: flag => flag.code,
        destroy: this.destroy,
        firstError: 'Не удалось загрузить флаги. Повторите запрос.',
        moreError: 'Следующие флаги не загрузились.'
    });
    protected readonly flagStatus = signal('');
    protected readonly dismissing = signal<ReadonlySet<string>>(new Set());
    protected readonly flagErrors = signal<Readonly<Record<string, string>>>({});
    private readonly dismissCommands = new Map<string, string>();
    protected readonly date = dateTime;

    ngOnInit(): void {
        void this.loadShelf();
        void this.flags.reload();
    }

    protected name(row: ShelfRow): string { return row.title ?? `Колода ${row.code}`; }
    protected share(flag: AnomalyFlag): string { return percent.format(flag.newShare); }
    protected deckUrl(code: string): string { return `${learnerOrigin(window.location.hostname, window.location.origin)}/d/${encodeURIComponent(code)}`; }

    /** Reads the actual list; local edits are replaced (the caller says why in a notice when that matters). */
    protected async loadShelf(): Promise<boolean> {
        const epoch = ++this.epoch;
        this.shelfLoading.set(true);
        this.shelfError.set('');
        try {
            const state = await firstValueFrom(this.api.editorial().pipe(takeUntilDestroyed(this.destroy)));
            if (epoch !== this.epoch) return false;
            this.rows.set(state.entries.map(entry => ({ code: entry.code, title: entry.title, listed: entry.listed, error: null })));
            this.baseline.set(state.entries.map(entry => entry.code));
            this.rowVersion.set(state.rowVersion);
            this.loaded.set(true);
            return true;
        } catch (failure) {
            if (epoch === this.epoch) {
                if (isForbidden(failure)) this.dropShelf();
                this.shelfError.set(errorText(failure, 'Не удалось загрузить полку. Повторите запрос.'));
            }
            return false;
        } finally {
            if (epoch === this.epoch) this.shelfLoading.set(false);
        }
    }

    protected typed(event: Event): void {
        this.draft.set((event.target as HTMLInputElement).value);
        this.addError.set('');
    }

    protected add(event: Event): void {
        event.preventDefault();
        if (this.busy()) return;
        if (this.full()) { this.addError.set(FULL_HINT); return; }
        const parsed = parseDeckInput(this.draft());
        if ('error' in parsed) { this.addError.set(parsed.error); return; }
        if (this.rows().some(row => row.code === parsed.code)) { this.addError.set('Эта колода уже на полке.'); return; }
        this.rows.update(list => [...list, { code: parsed.code, title: null, listed: true, error: null }]);
        this.draft.set('');
        const field = this.deckInput()?.nativeElement;
        if (field) field.value = '';
        this.addError.set('');
        this.touched();
        this.status.set(`Колода ${parsed.code} добавлена в конец списка, место ${this.rows().length}. Нажмите «Сохранить».`);
    }

    protected move(code: string, step: -1 | 1): void {
        const list = [...this.rows()];
        const from = list.findIndex(row => row.code === code), to = from + step;
        if (from < 0 || to < 0 || to >= list.length) return;
        [list[from], list[to]] = [list[to], list[from]];
        this.rows.set(list);
        this.touched();
        this.status.set(`${this.name(list[to])}: место ${to + 1} из ${list.length}.`);
        this.focusAction(code, step < 0 ? 'up' : 'down');
    }

    protected remove(code: string): void {
        const list = this.rows(), index = list.findIndex(row => row.code === code);
        if (index < 0) return;
        const removed = list[index];
        const rest = list.filter(row => row.code !== code);
        this.rows.set(rest);
        this.touched();
        this.status.set(`${this.name(removed)}: убрана из списка. Нажмите «Сохранить».`);
        const neighbour = rest[Math.min(index, rest.length - 1)];
        if (neighbour) this.focusAction(neighbour.code, 'remove'); else this.focusAfterRender(() => document.getElementById('shelf-add-input'));
    }

    protected async save(): Promise<void> {
        if (this.busy()) return;
        if (!this.dirty()) { this.status.set('Нечего сохранять'); return; }
        if (this.rowVersion() === null) { this.status.set('Список обновляется, подождите и повторите.'); return; }
        const codes = this.rows().map(row => row.code), version = this.rowVersion()!;
        // A repeat of the same list at the same version is the same command, so an unknown outcome never writes twice.
        const command: PendingSave = this.pending !== null && this.pending.version === version && sameCodes(this.pending.codes, codes)
            ? this.pending : { commandId: crypto.randomUUID(), codes, version };
        this.pending = command;
        this.busy.set(true);
        this.status.set('');
        this.saveError.set('');
        this.notice.set('');
        this.rows.update(list => list.map(row => row.error === null ? row : { ...row, error: null }));
        const body: EditorialCommand = { commandId: command.commandId, codes: command.codes };
        try {
            await firstValueFrom(this.api.saveEditorial(body, command.version).pipe(takeUntilDestroyed(this.destroy)));
        } catch (failure) {
            this.busy.set(false);
            await this.saveFailed(failure);
            return;
        }
        this.busy.set(false);
        this.pending = null;
        // What was sent is now the saved list; the actual rows (names of the new decks) and the new version come from a fresh read.
        this.baseline.set(command.codes);
        this.rowVersion.set(null);
        this.status.set('Сохранено');
        await this.loadShelf();
    }

    private async saveFailed(failure: unknown): Promise<void> {
        if (isForbidden(failure)) {
            this.dropShelf();
            this.saveError.set(errorText(failure, ''));
            return;
        }
        const status = failure instanceof HttpErrorResponse ? failure.status : 0;
        if (status === 412) {
            this.pending = null;
            this.notice.set('Список изменился, вот актуальный. Повторите изменения.');
            await this.loadShelf();
            return;
        }
        if (status === 400) {
            this.pending = null;
            const named = new Set(offendingCodes(failure));
            this.rows.update(list => list.map(row => named.has(row.code) ? { ...row, error: NOT_FIT } : row));
            this.saveError.set(named.size > 0 ? 'Список не сохранён: отмеченные колоды нельзя поставить на полку.' : 'Список не сохранён: сервер не принял одну из колод. Проверьте ссылки и коды.');
            this.focusAfterRender(() => (named.size > 0 ? this.firstErrorAction() : null) ?? document.getElementById('shelf-save-error'));
            return;
        }
        if (unknownOutcome(failure)) {
            this.saveError.set('Результат не подтверждён. Нажмите «Сохранить» ещё раз: повтор безопасен, список не запишется дважды.');
            return;
        }
        // 409 (the command id was used for another list), 428 and the rest: the next press is a new command.
        this.pending = null;
        this.saveError.set(errorText(failure, 'Сервер отклонил команду. Обновите страницу и повторите изменения.'));
    }

    protected async dismiss(flag: AnomalyFlag): Promise<void> {
        if (this.dismissing().has(flag.code) || flag.dismissedAt !== null) return;
        const commandId = this.dismissCommands.get(flag.code) ?? crypto.randomUUID();
        this.dismissCommands.set(flag.code, commandId);
        this.flagStatus.set('');
        this.dismissing.update(set => new Set(set).add(flag.code));
        this.flagErrors.update(errors => { const { [flag.code]: _gone, ...rest } = errors; return rest; });
        try {
            await firstValueFrom(this.api.dismissAnomaly(flag.code, commandId).pipe(takeUntilDestroyed(this.destroy)));
            this.dismissCommands.delete(flag.code);
            this.flags.replace({ ...flag, dismissedAt: new Date().toISOString() });
            this.flagStatus.set(`Флаг «${flag.title}» отмечен просмотренным.`);
            this.focusAfterRender(() => document.getElementById(`flag-${flag.code}`));
        } catch (failure) {
            if (isForbidden(failure)) {
                this.dropShelf();
                this.flags.clear();
            }
            const message = isForbidden(failure) ? errorText(failure, '')
                : unknownOutcome(failure) ? 'Результат не подтверждён. Нажмите «Просмотрено» ещё раз: повтор безопасен.'
                    : failure instanceof HttpErrorResponse && failure.status === 404 ? 'Флаг не найден. Обновите страницу.'
                        : errorText(failure, 'Не удалось отметить флаг. Повторите.');
            if (!unknownOutcome(failure)) this.dismissCommands.delete(flag.code);
            this.flagErrors.update(errors => ({ ...errors, [flag.code]: message }));
        } finally {
            this.dismissing.update(set => { const next = new Set(set); next.delete(flag.code); return next; });
        }
    }

    protected protectBeforeUnload(event: BeforeUnloadEvent): void {
        if (this.session.access() && (this.busy() || this.dirty())) event.preventDefault();
    }

    canLeave(): boolean {
        if (!this.session.access()) return true;
        if (this.busy()) {
            this.status.set('Идёт сохранение, подождите');
            this.focusAfterRender(() => this.saveButton()?.nativeElement ?? null);
            return false;
        }
        if (!this.dirty()) return true;
        this.notice.set('В списке полки есть несохранённые изменения. Если уйти, они пропадут. Нажмите «Сохранить», чтобы их оставить.');
        return window.confirm('Несохранённые изменения полки «Выбор Мнемы» будут отброшены. Покинуть страницу?');
    }

    /** An edit makes the earlier outcome stale: the old «Сохранено» and notices no longer describe the list. */
    private touched(): void {
        this.status.set('');
        this.notice.set('');
        this.saveError.set('');
    }

    private dropShelf(): void {
        ++this.epoch;
        this.rows.set([]);
        this.baseline.set([]);
        this.rowVersion.set(null);
        this.loaded.set(false);
        this.pending = null;
    }

    private focusAction(code: string, action: 'up' | 'down' | 'remove'): void {
        this.focusAfterRender(() => document.querySelector<HTMLElement>(`.shelf-list [data-code="${code}"] [data-action="${action}"]`));
    }

    private firstErrorAction(): HTMLElement | null {
        return document.querySelector<HTMLElement>('.shelf-list li:has(.field-error) [data-action="remove"]');
    }

    private focusAfterRender(find: () => HTMLElement | null): void {
        afterNextRender(() => find()?.focus(), { injector: this.injector });
    }
}

export const canLeaveAdminCommunity = (component: AdminCommunityPageComponent): boolean => component.canLeave();
