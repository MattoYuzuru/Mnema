import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { AdminSession } from '../admin/admin-api.service';
import { HoldToDeleteButtonComponent } from '../../shared/hold-to-delete-button.component';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { AutoLoadComponent } from '../../shared/auto-load.component';
import { EventsApiService } from './events-api.service';
import { EventBodyComponent } from './event-body.component';
import { EventEdit, EventPage, ManagedEvent, eventDate, formatEventDate } from './events.models';

type Mutation = { readonly kind: 'save'; readonly edit: EventEdit; readonly existing: ManagedEvent | null }
    | { readonly kind: 'delete'; readonly existing: ManagedEvent; readonly commandId: string };

@Component({
    selector: 'app-manage-events-page',
    host: { '(window:beforeunload)': 'protectBeforeUnload($event)' },
    imports: [ReactiveFormsModule, RouterLink, EventBodyComponent, HoldToDeleteButtonComponent, AutoLoadComponent],
    templateUrl: './manage-events-page.component.html',
    styleUrl: './manage-events-page.component.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class ManageEventsPageComponent implements OnInit {
    private readonly api = inject(EventsApiService);
    private readonly adminSession = inject(AdminSession);
    private readonly destroyRef = inject(DestroyRef);
    protected readonly state = signal<'loading' | 'ready' | 'forbidden' | 'error'>('loading');
    protected readonly page = signal<EventPage<ManagedEvent> | null>(null);
    protected readonly selected = signal<ManagedEvent | null>(null);
    protected readonly busy = signal(false);
    protected readonly error = signal('');
    protected readonly loadingMore = signal(false);
    protected readonly listError = signal<string | null>(null);
    protected readonly notice = signal('');
    protected readonly uncertain = signal(false);
    protected readonly preview = signal(false);
    protected readonly previewBody = signal('');
    protected readonly previewTitle = signal('');
    protected readonly previewDate = signal('');
    protected readonly pendingSelection = signal<{ event: ManagedEvent | null } | null>(null);
    protected readonly date = formatEventDate;
    protected readonly heading = computed(() => this.selected() ? 'Редактировать событие' : 'Новое событие');
    protected readonly form = new FormGroup({
        title: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.maxLength(160)] }),
        eventDate: new FormControl(localToday(), { nonNullable: true, validators: [Validators.required] }),
        bodyMarkdown: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.maxLength(16000)] }),
        published: new FormControl(false, { nonNullable: true })
    });
    private listEpoch = 0;
    private mutation: Mutation | null = null;

    ngOnInit(): void { void this.initialize(); }

    protected async initialize(): Promise<void> {
        this.state.set('loading'); this.error.set('');
        try {
            await firstValueFrom(this.api.access().pipe(takeUntilDestroyed(this.destroyRef)));
            await this.load(null); this.state.set('ready');
        } catch (error) {
            this.state.set(error instanceof HttpErrorResponse && error.status === 403 ? 'forbidden' : 'error');
        }
    }

    protected edit(event: ManagedEvent | null): void {
        if (this.busy() || this.uncertain()) return;
        if (this.form.dirty) { this.pendingSelection.set({ event }); return; }
        this.open(event);
    }

    protected discardAndOpen(): void {
        if (this.busy() || this.uncertain()) return;
        const pending = this.pendingSelection();
        if (pending === null) return;
        this.pendingSelection.set(null); this.open(pending.event);
    }

    private open(event: ManagedEvent | null): void {
        this.selected.set(event); this.preview.set(false); this.error.set(''); this.notice.set('');
        this.form.reset(event ? { title: event.title, eventDate: event.eventDate, bodyMarkdown: event.bodyMarkdown, published: event.published }
            : { title: '', eventDate: localToday(), bodyMarkdown: '', published: false });
    }

    protected protectBeforeUnload(event: BeforeUnloadEvent): void {
        if (this.adminSession.generation() > 0 && !this.adminSession.access()) return;
        if (this.busy() || this.uncertain() || this.form.dirty) event.preventDefault();
    }

    canLeave(): boolean {
        if (this.adminSession.generation() > 0 && !this.adminSession.access()) return true;
        if (this.busy() || this.uncertain()) return false;
        return !this.form.dirty || window.confirm('Изменения не сохранены. Покинуть страницу и отбросить их?');
    }

    protected showPreview(): void { this.previewBody.set(this.form.controls.bodyMarkdown.value); this.previewTitle.set(this.form.controls.title.value); this.previewDate.set(this.form.controls.eventDate.value); this.preview.update(value => !value); }

    protected async save(): Promise<void> {
        if (this.busy() || this.uncertain()) return;
        this.form.markAllAsTouched();
        const value = this.form.getRawValue();
        try { eventDate(value.eventDate); }
        catch { this.error.set('Выберите существующую дату.'); return; }
        if (this.form.invalid || !value.title.trim() || !value.bodyMarkdown.trim()) {
            this.error.set('Заполните заголовок и текст. Заголовок до 160 символов, текст до 16 000.'); return;
        }
        this.mutation = { kind: 'save', edit: { ...value, title: value.title.trim(), commandId: crypto.randomUUID() }, existing: this.selected() };
        await this.submit();
    }

    protected async remove(): Promise<void> {
        const existing = this.selected();
        if (existing === null || this.busy() || this.uncertain()) return;
        this.mutation = { kind: 'delete', existing, commandId: crypto.randomUUID() };
        await this.submit();
    }

    /** An unknown HTTP outcome reuses the frozen command/body/version; it never creates a second event. */
    protected async submit(): Promise<void> {
        const mutation = this.mutation;
        if (mutation === null || this.busy()) return;
        this.busy.set(true); this.uncertain.set(false); this.error.set(''); this.notice.set(''); this.form.disable();
        ++this.listEpoch;
        this.loadingMore.set(false);
        try {
            if (mutation.kind === 'delete') {
                await firstValueFrom(this.api.remove(mutation.existing, mutation.commandId).pipe(takeUntilDestroyed(this.destroyRef)));
                this.selected.set(null); this.form.reset({ title: '', eventDate: localToday(), bodyMarkdown: '', published: false });
                this.notice.set('Событие удалено.');
            } else {
                const saved = await firstValueFrom(this.api.save(mutation.edit, mutation.existing).pipe(takeUntilDestroyed(this.destroyRef)));
                this.selected.set(saved); this.form.markAsPristine();
                this.notice.set(saved.published ? 'Событие опубликовано.' : 'Черновик сохранён.');
            }
            this.mutation = null; this.pendingSelection.set(null);
            try { await this.load(null); }
            catch { this.error.set('Изменение сохранено, но список не обновился. Нажмите «Обновить».'); }
        } catch (error) {
            if (error instanceof HttpErrorResponse && error.status >= 400 && error.status < 500) {
                this.mutation = null;
                if (error.status === 401 || error.status === 403) { this.state.set('forbidden'); this.page.set(null); this.selected.set(null); this.form.reset(); }
                else this.error.set(error.status === 409 || error.status === 412
                    ? 'Запись уже изменилась. Ваш текст остался в редакторе. Скопируйте его, затем обновите список и сравните с актуальной записью.'
                    : 'Не удалось сохранить. Проверьте поля и попробуйте ещё раз.');
            } else {
                this.uncertain.set(true);
                this.error.set('Не удалось получить подтверждение. Повторите тот же запрос, чтобы проверить результат.');
            }
        } finally { this.busy.set(false); if (!this.uncertain()) this.form.enable(); }
    }

    protected async load(cursor: string | null, append = false): Promise<void> {
        const epoch = ++this.listEpoch;
        this.loadingMore.set(append);
        this.listError.set(null);
        try {
            const page = await firstValueFrom(this.api.manage(cursor).pipe(takeUntilDestroyed(this.destroyRef)));
            if (epoch !== this.listEpoch) return;
            const previous = this.page()?.items ?? [];
            const known = new Set(previous.map(event => event.eventId));
            this.page.set(append ? { ...page, items: [...previous, ...page.items.filter(event => !known.has(event.eventId))] } : page);
        } finally { if (epoch === this.listEpoch) this.loadingMore.set(false); }
    }

    protected async refresh(): Promise<void> { await this.changePage(null); }
    protected async next(): Promise<void> {
        const next = this.page()?.nextCursor;
        if (!next || this.loadingMore() || this.busy() || this.uncertain()) return;
        const epoch = this.listEpoch + 1;
        try { await this.load(next, true); }
        catch (error) {
            if (epoch !== this.listEpoch) return;
            if (error instanceof HttpErrorResponse && (error.status === 401 || error.status === 403)) {
                this.state.set('forbidden'); this.page.set(null);
            } else this.listError.set('Не удалось загрузить следующие записи. Текст в редакторе сохранён.');
        }
    }
    private async changePage(cursor: string | null): Promise<boolean> {
        if (this.busy() || this.uncertain()) return false;
        this.busy.set(true); this.error.set('');
        try { await this.load(cursor); return true; }
        catch { this.error.set('Не удалось обновить список. Текст в редакторе сохранён.'); return false; }
        finally { this.busy.set(false); }
    }
}

export function canLeaveEventEditor(component: ManageEventsPageComponent): boolean { return component.canLeave(); }

function localToday(): string {
    const now = new Date();
    return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`;
}
