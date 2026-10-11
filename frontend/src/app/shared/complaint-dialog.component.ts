import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, afterNextRender, computed, inject, input, output, signal, viewChild } from '@angular/core';

import { ToastService } from '../core/notifications/toast.service';
import { ComplaintApiService } from './complaint-api.service';
import {
    COMPLAINT_REASONS,
    COMPLAINT_REASON_HINTS,
    COMPLAINT_REASON_LABELS,
    COMPLAINT_TEXT_MAX,
    ComplaintFailure,
    ComplaintReason,
    codePoints,
    complaintFailureOf
} from './complaint.models';
import { SegmentedChoiceComponent, SegmentedOption } from './segmented-choice.component';

let nextDialog = 0;

const OPTIONS: readonly SegmentedOption<ComplaintReason>[] = COMPLAINT_REASONS.map(value => ({
    value, label: COMPLAINT_REASON_LABELS[value], hint: COMPLAINT_REASON_HINTS[value]
}));

/**
 * «Пожаловаться»: a modal `<dialog>` with the reason (a native radio group), an optional text and the send button. The platform traps
 * focus, makes the rest of the page inert and returns focus to what opened it when the dialog closes; Esc and «×» close it (while a
 * request is on its way they wait, so the answer is never lost). The host renders it with `@if` and takes it away to close it.
 *
 * Outcomes. Sent: «Жалоба отправлена» (a plain toast), `filed`. `404` (the deck or profile is gone for this reader): an error toast
 * and `gone`, so the host can ask for the page again. `429`: «Попробуйте позже» stays in the dialog with the typed text. Anything
 * else: a retryable sentence in the dialog. Filing is idempotent, so pressing «Отправить» again after an unknown outcome is safe.
 */
@Component({
    selector: 'app-complaint-dialog',
    imports: [SegmentedChoiceComponent],
    template: `
      <dialog #surface class="complaint-dialog" [attr.aria-labelledby]="titleId" [attr.aria-describedby]="introId" (cancel)="onCancel($event)">
        <form class="complaint-body" novalidate (submit)="submit($event)">
          <button type="button" class="complaint-close" aria-label="Закрыть окно жалобы" [attr.aria-disabled]="sending() ? 'true' : null" (click)="dismiss()"><span aria-hidden="true">×</span></button>
          <p class="eyebrow">Жалоба</p>
          <h2 #title [id]="titleId" tabindex="-1">{{ target() === 'deck' ? 'Пожаловаться на колоду' : 'Пожаловаться на профиль' }}</h2>
          @if (subjectName(); as name) { <p class="subject">{{ name }}</p> }
          <p [id]="introId" class="hint">Мы рассмотрим жалобу в течение 30 дней. О решении мы не пишем.</p>

          <app-segmented-choice legend="Причина" [options]="options" [value]="reason()" [name]="groupName" [disabled]="sending()" (valueChange)="chooseReason($event)" />
          @if (reasonMissing()) { <p class="field-error" id="{{ uid }}-reason-error" role="alert">Выберите причину.</p> }

          <div class="field">
            <label [for]="textId">Что не так <span class="optional">необязательно</span></label>
            <textarea #field [id]="textId" rows="4" autocomplete="off" [value]="text()" [readOnly]="sending()"
              [attr.aria-invalid]="overLimit() ? 'true' : null" [attr.aria-describedby]="textId + '-count' + (overLimit() ? ' ' + textId + '-error' : '')"
              (input)="onText($event)"></textarea>
            <p class="hint" [id]="textId + '-count'">{{ count() }} из {{ max }}. Не указывайте в тексте лишние личные данные.</p>
            @if (overLimit()) { <p class="field-error" [id]="textId + '-error'">Сократите текст до {{ max }} знаков.</p> }
          </div>

          @if (error(); as message) { <p class="notice error" role="alert">{{ message }}</p> }
          <div class="complaint-actions">
            <button type="submit" class="button primary" [attr.aria-disabled]="sending() ? 'true' : null" [attr.aria-busy]="sending() ? 'true' : null">{{ sending() ? 'Отправляем…' : 'Отправить жалобу' }}</button>
            <button type="button" class="button" [attr.aria-disabled]="sending() ? 'true' : null" (click)="dismiss()">Отмена</button>
          </div>
        </form>
      </dialog>
    `,
    styleUrl: './complaint-dialog.component.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class ComplaintDialogComponent {
    /** What is reported: a deck (by its public code) or a profile (by its username). */
    readonly target = input.required<'deck' | 'profile'>();
    /** The public code of the deck or the username of the profile. */
    readonly subject = input.required<string>();
    /** The title of the deck or the login of the profile, shown under the heading so the reader sees what the complaint is about. */
    readonly subjectName = input<string | null>(null);
    /** The complaint was received. */
    readonly filed = output<void>();
    /** The deck or profile is gone for this reader (404): the host asks for the page again. */
    readonly gone = output<void>();
    /** Closed without sending: «×», «Отмена» or Esc. */
    readonly dismissed = output<void>();

    private readonly api = inject(ComplaintApiService);
    private readonly toasts = inject(ToastService);
    private readonly surface = viewChild.required<ElementRef<HTMLDialogElement>>('surface');
    private readonly title = viewChild.required<ElementRef<HTMLElement>>('title');
    private readonly field = viewChild.required<ElementRef<HTMLTextAreaElement>>('field');

    protected readonly uid = `mn-complaint-${nextDialog++}`;
    protected readonly titleId = `${this.uid}-title`;
    protected readonly introId = `${this.uid}-intro`;
    protected readonly textId = `${this.uid}-text`;
    protected readonly groupName = `${this.uid}-reason`;
    protected readonly options = OPTIONS;
    protected readonly max = COMPLAINT_TEXT_MAX;
    protected readonly reason = signal<ComplaintReason | null>(null);
    protected readonly text = signal('');
    protected readonly sending = signal(false);
    protected readonly reasonMissing = signal(false);
    protected readonly error = signal<string | null>(null);
    protected readonly count = computed(() => codePoints(this.text().trim()));
    protected readonly overLimit = computed(() => this.count() > COMPLAINT_TEXT_MAX);

    private opened: HTMLDialogElement | null = null;

    constructor() {
        afterNextRender(() => {
            const dialog = this.surface().nativeElement;
            this.opened = dialog;
            if (!dialog.open) dialog.showModal();
            this.title().nativeElement.focus();
        });
        // Taking the element away does not run `close()`, which hands the focus back to what opened the dialog: do it first.
        inject(DestroyRef).onDestroy(() => {
            if (this.opened?.open) this.opened.close();
        });
    }

    protected chooseReason(value: ComplaintReason | null): void {
        this.reason.set(value);
        if (value !== null) this.reasonMissing.set(false);
    }

    protected onText(event: Event): void { this.text.set((event.target as HTMLTextAreaElement).value); }

    /** Esc closes a modal dialog natively; the host takes the dialog away instead (and a sending one stays). */
    protected onCancel(event: Event): void {
        event.preventDefault();
        this.dismiss();
    }

    protected dismiss(): void {
        if (!this.sending()) this.dismissed.emit();
    }

    protected submit(event: Event): void {
        event.preventDefault();
        if (this.sending()) return;
        const reason = this.reason();
        if (reason === null) {
            this.reasonMissing.set(true);
            this.surface().nativeElement.querySelector<HTMLElement>('input[type=radio]')?.focus();
            return;
        }
        if (this.overLimit()) {
            this.field().nativeElement.focus();
            return;
        }
        this.error.set(null);
        this.sending.set(true);
        const request = this.target() === 'deck' ? this.api.fileDeck(this.subject(), reason, this.text()) : this.api.fileProfile(this.subject(), reason, this.text());
        request.subscribe({
            next: () => {
                this.sending.set(false);
                this.filed.emit();
                this.toasts.echo('Жалоба отправлена');
            },
            error: (failure: unknown) => this.failed(complaintFailureOf(failure))
        });
    }

    private failed(failure: ComplaintFailure): void {
        this.sending.set(false);
        switch (failure.kind) {
            case 'not-found':
                this.toasts.echoError(this.target() === 'deck' ? 'Колода больше недоступна' : 'Профиль больше недоступен');
                this.gone.emit();
                return;
            case 'rate-limited': this.error.set('Попробуйте позже'); return;
            case 'sign-in': this.error.set('Сессия закончилась. Войдите снова и отправьте жалобу ещё раз.'); return;
            case 'rejected': this.error.set('Жалобу нельзя отправить: проверьте причину и текст.'); return;
            default: this.error.set('Не удалось отправить жалобу. Проверьте соединение и повторите.');
        }
    }
}
