import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Subject, of, throwError } from 'rxjs';

import { ToastService } from '../core/notifications/toast.service';
import { ComplaintApiService } from './complaint-api.service';
import { ComplaintDialogComponent } from './complaint-dialog.component';
import { COMPLAINT_REASONS, COMPLAINT_REASON_LABELS, ComplaintFailure } from './complaint.models';

@Component({
    imports: [ComplaintDialogComponent],
    template: `<button id="opener" type="button">Пожаловаться</button>
      @if (open()) {
        <app-complaint-dialog [target]="target()" [subject]="subject()" subjectName="Английский: путешествия"
          (filed)="log.push('filed')" (gone)="log.push('gone')" (dismissed)="log.push('dismissed')" />
      }`
})
class HostComponent {
    readonly open = signal(true);
    readonly target = signal<'deck' | 'profile'>('deck');
    readonly subject = signal('Kq7xT3mNpR');
    readonly log: string[] = [];
}

describe('ComplaintDialogComponent', () => {
    let api: { fileDeck: ReturnType<typeof vi.fn>; fileProfile: ReturnType<typeof vi.fn> };
    let toasts: { echo: ReturnType<typeof vi.fn>; echoError: ReturnType<typeof vi.fn> };
    let fixture: ReturnType<typeof TestBed.createComponent<HostComponent>>;
    let host: HostComponent;
    let root: HTMLElement;

    beforeEach(() => {
        api = { fileDeck: vi.fn().mockReturnValue(of(undefined)), fileProfile: vi.fn().mockReturnValue(of(undefined)) };
        toasts = { echo: vi.fn(), echoError: vi.fn() };
        TestBed.configureTestingModule({ providers: [{ provide: ComplaintApiService, useValue: api }, { provide: ToastService, useValue: toasts }] });
        fixture = TestBed.createComponent(HostComponent);
        host = fixture.componentInstance;
        root = fixture.nativeElement as HTMLElement;
        fixture.detectChanges();
    });

    const dialog = (): HTMLDialogElement => root.querySelector('dialog')!;
    const radios = (): HTMLInputElement[] => [...root.querySelectorAll<HTMLInputElement>('input[type=radio]')];
    const field = (): HTMLTextAreaElement => root.querySelector('textarea')!;
    const submitButton = (): HTMLButtonElement => root.querySelector<HTMLButtonElement>('button[type=submit]')!;
    const typeText = (value: string): void => { field().value = value; field().dispatchEvent(new Event('input')); fixture.detectChanges(); };
    const choose = (reason: string): void => { radios().find(radio => radio.closest('label')?.textContent?.trim() === reason)!.click(); fixture.detectChanges(); };
    const submit = (): void => { root.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true })); fixture.detectChanges(); };

    it('opens as a modal dialog named by its heading, with the focus on the heading, and says what happens next', () => {
        fixture.whenStable();
        expect(dialog().hasAttribute('open')).toBe(true);
        expect(dialog().getAttribute('aria-labelledby')).toBe(root.querySelector('h2')!.id);
        expect(root.querySelector('h2')!.textContent).toBe('Пожаловаться на колоду');
        expect(root.querySelector('.subject')!.textContent).toBe('Английский: путешествия');
        expect(dialog().getAttribute('aria-describedby')).toBe(root.querySelector('p.hint')!.id);
        expect(root.querySelector('p.hint')!.textContent).toBe('Мы рассмотрим жалобу в течение 30 дней. О решении мы не пишем.');
    });

    it('offers the five reasons of the contract as one radio group, none chosen, with a hint under the chosen one', () => {
        expect(radios().map(radio => radio.closest('label')!.textContent!.trim())).toEqual(COMPLAINT_REASONS.map(reason => COMPLAINT_REASON_LABELS[reason]));
        expect(radios().some(radio => radio.checked)).toBe(false);
        choose('Раскрыты личные данные');
        expect(root.querySelector('app-segmented-choice .hint')!.textContent).toContain('личные данные');
    });

    it('needs a reason: sending without one says so and puts the focus on the group, without a request', () => {
        submit();
        expect(root.querySelector('#' + dialog().querySelector('[role=alert]')!.id)!.textContent).toBe('Выберите причину.');
        expect(api.fileDeck).not.toHaveBeenCalled();
        choose('Спам или реклама');
        expect(root.querySelector('.field-error')).toBeNull();
    });

    it('counts the text and refuses more than 1000 characters', () => {
        typeText('привет');
        expect(root.querySelector('.hint[id$="-count"]')!.textContent).toContain('6 из 1000');
        choose('Другое');
        typeText('я'.repeat(1001));
        expect(field().getAttribute('aria-invalid')).toBe('true');
        expect(root.querySelector('.field-error')!.textContent).toBe('Сократите текст до 1000 знаков.');
        submit();
        expect(api.fileDeck).not.toHaveBeenCalled();
        typeText('я'.repeat(1000));
        expect(field().hasAttribute('aria-invalid')).toBe(false);
        submit();
        expect(api.fileDeck).toHaveBeenCalledTimes(1);
    });

    it('files a deck complaint, closes through «filed» and confirms with the plain toast', () => {
        choose('Нарушены авторские права');
        typeText('Моя иллюстрация');
        submit();
        expect(api.fileDeck).toHaveBeenCalledWith('Kq7xT3mNpR', 'COPYRIGHT', 'Моя иллюстрация');
        expect(host.log).toEqual(['filed']);
        expect(toasts.echo).toHaveBeenCalledWith('Жалоба отправлена');
    });

    it('files a profile complaint by login with its own heading', () => {
        host.target.set('profile');
        host.subject.set('ivan.k');
        fixture.detectChanges();
        expect(root.querySelector('h2')!.textContent).toBe('Пожаловаться на профиль');
        choose('Раскрыты личные данные');
        submit();
        expect(api.fileProfile).toHaveBeenCalledWith('ivan.k', 'PERSONAL_DATA', '');
        expect(api.fileDeck).not.toHaveBeenCalled();
    });

    it('waits for the answer: the buttons are aria-disabled and Esc does not close it', () => {
        const pending = new Subject<void>();
        api.fileDeck.mockReturnValue(pending);
        choose('Спам или реклама');
        submit();
        expect(submitButton().textContent!.trim()).toBe('Отправляем…');
        expect(submitButton().getAttribute('aria-disabled')).toBe('true');
        submit();
        expect(api.fileDeck).toHaveBeenCalledTimes(1);
        const cancel = new Event('cancel', { cancelable: true });
        dialog().dispatchEvent(cancel);
        root.querySelector<HTMLButtonElement>('.complaint-close')!.click();
        expect(cancel.defaultPrevented).toBe(true);
        expect(host.log).toEqual([]);
        pending.next();
        pending.complete();
        expect(host.log).toEqual(['filed']);
    });

    it('closes without sending by Esc, by «×» and by «Отмена»', () => {
        dialog().dispatchEvent(new Event('cancel', { cancelable: true }));
        root.querySelector<HTMLButtonElement>('.complaint-close')!.click();
        [...root.querySelectorAll<HTMLButtonElement>('button[type=button]')].find(button => button.textContent!.trim() === 'Отмена')!.click();
        expect(host.log).toEqual(['dismissed', 'dismissed', 'dismissed']);
        expect(api.fileDeck).not.toHaveBeenCalled();
    });

    it('tells the host the deck is gone on 404, with an error toast, and keeps no dialog message', () => {
        api.fileDeck.mockReturnValue(throwError(() => new ComplaintFailure('not-found')));
        choose('Другое');
        submit();
        expect(host.log).toEqual(['gone']);
        expect(toasts.echoError).toHaveBeenCalledWith('Колода больше недоступна');
        expect(toasts.echo).not.toHaveBeenCalled();
    });

    it('says «Попробуйте позже» on 429 and keeps the typed text for another try', () => {
        api.fileDeck.mockReturnValue(throwError(() => new ComplaintFailure('rate-limited', 60)));
        choose('Спам или реклама');
        typeText('Написанное');
        submit();
        expect(root.querySelector('.notice.error')!.textContent).toBe('Попробуйте позже');
        expect(root.querySelector('.notice.error')!.getAttribute('role')).toBe('alert');
        expect(field().value).toBe('Написанное');
        expect(submitButton().textContent!.trim()).toBe('Отправить жалобу');
        expect(host.log).toEqual([]);
    });

    it('explains an ended session, a refused request and an unknown outcome, and a retry clears the message', () => {
        const cases: readonly [ComplaintFailure, string][] = [
            [new ComplaintFailure('sign-in'), 'Сессия закончилась. Войдите снова и отправьте жалобу ещё раз.'],
            [new ComplaintFailure('rejected'), 'Жалобу нельзя отправить: проверьте причину и текст.'],
            [new ComplaintFailure('unavailable'), 'Не удалось отправить жалобу. Проверьте соединение и повторите.']
        ];
        choose('Другое');
        for (const [failure, message] of cases) {
            api.fileDeck.mockReturnValueOnce(throwError(() => failure));
            submit();
            expect(root.querySelector('.notice.error')!.textContent).toBe(message);
        }
        api.fileDeck.mockReturnValueOnce(of(undefined));
        submit();
        expect(host.log).toEqual(['filed']);
    });

    it('closes the native dialog when it is taken away, so the focus goes back to what opened it', () => {
        const element = dialog();
        const close = vi.spyOn(element, 'close');
        host.open.set(false);
        fixture.detectChanges();
        expect(close).toHaveBeenCalled();
        expect(root.querySelector('dialog')).toBeNull();
    });
});
