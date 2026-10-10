import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Subject, of, throwError } from 'rxjs';
import fixture from '../../../../../contracts/identity/public-profile.json';
import { AccountProfile, AccountProfileApi } from '../../account-profile.api';
import { ToastService } from '../../core/notifications/toast.service';
import { PUBLIC_PROFILE_TEXT_VERSION, PublicProfileConsent } from '../../public-profile';
import { spyObj, type SpyObj } from '../../../testing/mocks';
import { PublicProfileSettingsComponent } from './public-profile-settings.component';

const profile: AccountProfile = { accountId: 'd2815e20-ea25-4dce-977a-66ee086f294d', email: 'reader@example.test', emailVerified: true,
    profileUsername: 'reader', displayName: 'Читатель', bio: 'Учусь каждый день\nи не спешу', avatarPresent: true, hasPassword: true };
const off = fixture.consentDefault as PublicProfileConsent;
const on: PublicProfileConsent = { ...off, enabled: true, publishReady: true, updatedAt: '2026-10-10T12:00:00Z' };

describe('PublicProfileSettingsComponent', () => {
    let api: SpyObj<AccountProfileApi>;
    let toasts: { echo: ReturnType<typeof vi.fn> };
    let view: ComponentFixture<PublicProfileSettingsComponent>;
    let root: HTMLElement;

    beforeEach(() => {
        api = spyObj<AccountProfileApi>({ loadPublicProfile: vi.fn(), savePublicProfile: vi.fn() });
        api.loadPublicProfile.mockReturnValue(of(off));
        toasts = { echo: vi.fn() };
        TestBed.configureTestingModule({ providers: [provideRouter([]), { provide: AccountProfileApi, useValue: api }, { provide: ToastService, useValue: toasts }] });
    });

    async function open(account: AccountProfile = profile, avatarUrl: string | null = 'blob:own-photo'): Promise<void> {
        view = TestBed.createComponent(PublicProfileSettingsComponent);
        view.componentRef.setInput('profile', account);
        view.componentRef.setInput('avatarUrl', avatarUrl);
        root = view.nativeElement as HTMLElement;
        view.detectChanges();
        await view.whenStable();
        view.detectChanges();
    }
    const toggle = () => root.querySelector<HTMLInputElement>('input[role=switch]')!;
    const field = (label: string) => [...root.querySelectorAll<HTMLInputElement>('.check-row input')]
        .find(input => input.closest('label')?.textContent?.trim() === label)!;
    async function click(input: HTMLInputElement): Promise<void> {
        input.click();
        await view.whenStable();
        view.detectChanges();
    }
    const notice = () => root.querySelector('.notice[role=alert]');

    it('shows the approved paragraph, an off switch, three unticked and disabled fields and the policy link', async () => {
        await open();
        const text = root.querySelector('.consent-text')!.textContent!;
        expect(text.startsWith('Когда профиль публичный, ваш логин видят все, кто открывает ваши публичные колоды или страницу профиля.')).toBe(true);
        expect(text).toContain('Ниже отметьте, что ещё можно показывать: имя, фото, «О себе».');
        expect(text).toContain('Эти данные видны только на сайте mnema.app, и мы не передаём их никому для других целей.');
        expect(text).toContain('Согласие действует, пока вы его не выключите.');
        expect(text).toContain('Выключить можно здесь в любой момент или письмом на адрес из раздела «Оператор»: профиль и подпись на колодах скроются сразу.');
        expect(text.endsWith('Без публичного профиля ваши колоды остаются доступны вам и тем, кому вы дали доступ.')).toBe(true);

        const label = toggle().closest('label')!;
        expect(label.classList.contains('settings-row') && label.classList.contains('is-switch')).toBe(true);
        expect(label.textContent?.trim()).toBe('Показывать мой профиль другим');
        expect(toggle().checked).toBe(false);
        expect(toggle().disabled).toBe(false);
        const fields = [...root.querySelectorAll<HTMLInputElement>('.check-field > .check-row input')];
        expect(fields.map(input => input.closest('label')!.textContent!.trim())).toEqual(['Имя', 'Фото', 'О себе']);
        expect(fields.every(input => !input.checked && input.disabled && input.type === 'checkbox')).toBe(true);
        expect(root.querySelector('fieldset.check-group')?.getAttribute('aria-describedby')).toBe('consent-fields-hint');
        // form-mode screen readers hear the consent text with the switch
        expect(toggle().getAttribute('aria-describedby')).toBe('public-profile-consent-text');
        expect(root.querySelector('#public-profile-consent-text')).toBe(root.querySelector('.consent-text'));
        expect(root.querySelector('.preview')?.hasAttribute('role')).toBe(false);
        expect(root.querySelector('.preview h3')?.textContent).toBe('Как меня видят другие');
        const link = root.querySelector<HTMLAnchorElement>('a[href$="/privacy#public-profile"]')!;
        expect(link.textContent).toContain('Условия показа профиля');
        expect(link.getAttribute('target')).toBe('_blank');
        expect(link.getAttribute('rel')).toBe('noopener');
        expect(root.querySelector('.preview h3')?.textContent).toBe('Как меня видят другие');
        expect(root.querySelector('.preview-hidden')?.textContent).toBe('Профиль скрыт');
        expect(root.querySelector('app-author-chip .chip')).toBeNull();
    });

    it('keeps every control a native, reachable input in reading order', async () => {
        api.loadPublicProfile.mockReturnValue(of(on));
        await open();
        const order = [...root.querySelectorAll<HTMLElement>('input, a, button')];
        expect(order.map(element => element.tagName === 'A' ? 'link' : (element as HTMLInputElement).closest('label')!.textContent!.trim()))
            .toEqual(['Показывать мой профиль другим', 'Имя', 'Фото', 'О себе', 'link']);
        expect(order.every(element => element.getAttribute('tabindex') === null)).toBe(true);
        expect(root.querySelector('input[role=switch]')?.getAttribute('type')).toBe('checkbox');
        expect([...root.querySelectorAll<HTMLInputElement>('.check-row input')].every(input => !input.disabled)).toBe(true);
        expect(root.querySelector('fieldset legend')?.textContent).toBe('Что ещё показывать');
    });

    it('blocks the switch with a hint while the profile has no login', async () => {
        await open({ ...profile, profileUsername: null });
        expect(toggle().disabled).toBe(true);
        expect(toggle().getAttribute('aria-describedby')).toBe('public-profile-consent-text consent-username-hint');
        expect(root.querySelector('#consent-username-hint')?.textContent).toContain('задайте имя пользователя');
        await click(toggle());
        expect(api.savePublicProfile).not.toHaveBeenCalled();
    });

    it('switches on without ticking any field, confirms with a toast and shows the login in the preview', async () => {
        api.savePublicProfile.mockReturnValue(of(on));
        await open();
        await click(toggle());
        expect(api.savePublicProfile).toHaveBeenCalledWith({ enabled: true, showDisplayName: false, showAvatar: false, showBio: false,
            textVersion: PUBLIC_PROFILE_TEXT_VERSION });
        expect(toasts.echo).toHaveBeenCalledWith('Публичный профиль включён.');
        expect(toggle().checked).toBe(true);
        expect([...root.querySelectorAll<HTMLInputElement>('.check-row input')].every(input => !input.checked && !input.disabled)).toBe(true);
        expect(root.querySelector('#consent-fields-hint')).toBeNull();
        expect(root.querySelector('.preview app-author-chip .login')?.textContent).toBe('@reader');
        expect(root.querySelector('.preview-name')).toBeNull();
        expect(root.querySelector('.preview-hidden')).toBeNull();
        expect(notice()).toBeNull();
    });

    it('saves each field at once as the complete state and previews exactly what is shown', async () => {
        api.loadPublicProfile.mockReturnValue(of(on));
        await open();
        api.savePublicProfile.mockReturnValueOnce(of({ ...on, showDisplayName: true }));
        await click(field('Имя'));
        expect(api.savePublicProfile).toHaveBeenLastCalledWith({ enabled: true, showDisplayName: true, showAvatar: false, showBio: false, textVersion: '2026-10-10' });
        expect(toasts.echo).toHaveBeenLastCalledWith('Настройки показа сохранены.');
        expect(root.querySelector('.preview-name')?.textContent).toBe('Читатель');
        expect(root.querySelector('.preview-bio')).toBeNull();
        expect(root.querySelector('.preview img')).toBeNull();

        api.savePublicProfile.mockReturnValueOnce(of({ ...on, showDisplayName: true, showAvatar: true }));
        await click(field('Фото'));
        expect(root.querySelector('.preview img')?.getAttribute('src')).toBe('blob:own-photo');

        api.savePublicProfile.mockReturnValueOnce(of({ ...on, showDisplayName: true, showAvatar: true, showBio: true }));
        await click(field('О себе'));
        expect(api.savePublicProfile).toHaveBeenLastCalledWith({ enabled: true, showDisplayName: true, showAvatar: true, showBio: true, textVersion: '2026-10-10' });
        expect(root.querySelector('.preview-bio')?.textContent).toBe('Учусь каждый день\nи не спешу');

        api.savePublicProfile.mockReturnValueOnce(of({ ...on, showAvatar: true, showBio: true }));
        await click(field('Имя'));
        expect(root.querySelector('.preview-name')).toBeNull();
    });

    it('does not preview a photo or name the profile does not have', async () => {
        api.loadPublicProfile.mockReturnValue(of({ ...on, showDisplayName: true, showAvatar: true, showBio: true }));
        await open({ ...profile, displayName: null, bio: null, avatarPresent: false });
        expect(root.querySelector('.preview app-author-chip .placeholder')).not.toBeNull();
        expect(root.querySelector('.preview img')).toBeNull();
        expect(root.querySelector('.preview-name')).toBeNull();
        expect(root.querySelector('.preview-bio')).toBeNull();
    });

    it('withdraws everything at once when the switch goes off', async () => {
        api.loadPublicProfile.mockReturnValue(of({ ...on, showDisplayName: true, showBio: true }));
        await open();
        api.savePublicProfile.mockReturnValue(of(off));
        await click(toggle());
        expect(api.savePublicProfile).toHaveBeenCalledWith({ enabled: false, showDisplayName: false, showAvatar: false, showBio: false, textVersion: '2026-10-10' });
        expect(toasts.echo).toHaveBeenCalledWith('Публичный профиль скрыт.');
        expect([...root.querySelectorAll<HTMLInputElement>('.check-row input')].every(input => !input.checked && input.disabled)).toBe(true);
        expect(root.querySelector('.preview-hidden')?.textContent).toBe('Профиль скрыт');
    });

    it('keeps controls focusable but busy while saving, ignores a second change and announces the progress', async () => {
        api.loadPublicProfile.mockReturnValue(of(on));
        await open();
        const pending = new Subject<PublicProfileConsent>();
        api.savePublicProfile.mockReturnValue(pending);
        field('Имя').click();
        view.detectChanges();
        expect(root.querySelector('.controls')?.hasAttribute('aria-busy')).toBe(false);
        expect(root.querySelector('.status')?.textContent?.trim()).toBe('Сохраняем…');
        // the box being saved keeps the value that is being saved
        expect(field('Имя').checked).toBe(true);
        const inputs = [...root.querySelectorAll<HTMLInputElement>('input')];
        expect(inputs.every(input => input.getAttribute('aria-disabled') === 'true' && !input.disabled)).toBe(true);

        // a click during the save is cancelled before the box flips: no visible flip and back
        const flips = vi.fn();
        field('Фото').addEventListener('change', flips);
        field('Фото').click();
        expect(field('Фото').checked).toBe(false);
        field('Имя').click();
        expect(field('Имя').checked).toBe(true);
        expect(flips).not.toHaveBeenCalled();
        expect(api.savePublicProfile).toHaveBeenCalledTimes(1);

        pending.next({ ...on, showDisplayName: true });
        pending.complete();
        await view.whenStable();
        view.detectChanges();
        expect(inputs.some(input => input.hasAttribute('aria-disabled'))).toBe(false);
        expect(root.querySelector('.status')?.textContent?.trim()).toBe('');
        expect(field('Имя').checked).toBe(true);
    });

    it('puts the control back and says so when the save fails', async () => {
        api.loadPublicProfile.mockReturnValue(of(on));
        await open();
        api.savePublicProfile.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 503 })));
        await click(field('О себе'));
        expect(field('О себе').checked).toBe(false);
        expect(notice()?.textContent).toContain('Не удалось сохранить настройки');
        expect(root.querySelector('.preview-bio')).toBeNull();
        expect(toasts.echo).not.toHaveBeenCalled();
        expect(notice()?.querySelector('button')).toBeNull();

        api.savePublicProfile.mockReturnValue(of({ ...on, showBio: true }));
        await click(field('О себе'));
        expect(notice()).toBeNull();
        expect(field('О себе').checked).toBe(true);
    });

    it('puts the switch back when the login is missing on the server and shows what the server holds', async () => {
        await open();
        api.savePublicProfile.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 409, error: { code: 'profile_username_required' } })));
        await click(toggle());
        expect(toggle().checked).toBe(false);
        expect(notice()?.textContent).toContain('задайте имя пользователя');
        expect(api.loadPublicProfile).toHaveBeenCalledTimes(2);
    });

    it('asks for a page reload when the consent text is outdated', async () => {
        await open();
        api.savePublicProfile.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 409, error: { code: 'consent_text_outdated' } })));
        await click(toggle());
        expect(toggle().checked).toBe(false);
        expect(notice()?.textContent).toContain('Текст согласия обновился');
        const reload = vi.spyOn(view.componentInstance as unknown as { reloadPage(): void }, 'reloadPage').mockImplementation(() => undefined);
        notice()!.querySelector('button')!.click();
        expect(reload).toHaveBeenCalledOnce();
    });

    it('keeps the last state when re-reading it after a conflict fails', async () => {
        await open();
        api.savePublicProfile.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 409, error: { code: 'profile_username_required' } })));
        api.loadPublicProfile.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 503 })));
        await click(toggle());
        expect(notice()?.textContent).toContain('задайте имя пользователя');
        expect(toggle().checked).toBe(false);
    });

    it('announces the first load by filling a status region that was rendered empty', async () => {
        vi.useFakeTimers();
        const pending = new Subject<PublicProfileConsent>();
        api.loadPublicProfile.mockReturnValue(pending);
        view = TestBed.createComponent(PublicProfileSettingsComponent);
        view.componentRef.setInput('profile', profile);
        root = view.nativeElement as HTMLElement;
        view.detectChanges();
        const status = root.querySelector('.status')!;
        expect(status.getAttribute('role')).toBe('status');
        expect(status.textContent?.trim()).toBe('');
        await vi.advanceTimersByTimeAsync(60);
        view.detectChanges();
        expect(root.querySelector('.status')).toBe(status);
        expect(status.textContent?.trim()).toBe('Загружаем настройки…');
        pending.next(off);
        pending.complete();
        await view.whenStable();
        view.detectChanges();
        expect(status.textContent?.trim()).toBe('');
        expect(toggle()).not.toBeNull();
        vi.useRealTimers();
    });

    it('offers a retry when the settings cannot be loaded, keeps the button mounted while retrying and moves focus on success', async () => {
        api.loadPublicProfile.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 503 })));
        await open();
        document.body.append(root);
        expect(notice()?.textContent).toContain('Не удалось загрузить настройки публичного профиля');
        expect(root.querySelector('input')).toBeNull();
        expect(root.querySelector('.consent-text')).not.toBeNull();
        const retry = root.querySelector<HTMLButtonElement>('button')!;
        retry.focus();
        const pending = new Subject<PublicProfileConsent>();
        api.loadPublicProfile.mockReturnValue(pending);
        retry.click();
        view.detectChanges();
        expect(root.querySelector('button')).toBe(retry);
        expect(retry.getAttribute('aria-disabled')).toBe('true');
        expect(retry.textContent?.trim()).toBe('Загружаем…');
        expect(document.activeElement).toBe(retry);
        retry.click();
        expect(api.loadPublicProfile).toHaveBeenCalledTimes(2);

        pending.next(on);
        pending.complete();
        await view.whenStable();
        view.detectChanges();
        await view.whenStable();
        expect(toggle().checked).toBe(true);
        expect(document.activeElement).toBe(toggle());
        root.remove();
    });

    it('does not steal focus after a retry the user has left', async () => {
        api.loadPublicProfile.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 503 })));
        await open();
        document.body.append(root);
        api.loadPublicProfile.mockReturnValue(of(on));
        root.querySelector<HTMLButtonElement>('button')!.click();
        await view.whenStable();
        view.detectChanges();
        await view.whenStable();
        expect(toggle().checked).toBe(true);
        expect(document.activeElement).not.toBe(toggle());
        root.remove();
    });
});
