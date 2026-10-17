import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Subject, of, throwError } from 'rxjs';
import fixture from '../../../../../contracts/identity/public-profile.json';
import { AccountProfile, AccountProfileApi } from '../../account-profile.api';
import { ToastService } from '../../core/notifications/toast.service';
import { PUBLIC_PROFILE_TEXT_VERSION, PublicProfileConsent } from '../../public-profile';
import { COMMUNITY_LAUNCH } from '../../shared/community-launch';
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
    const button = (label: string): HTMLButtonElement => [...root.querySelectorAll<HTMLButtonElement>('button')].find(item => item.textContent?.trim() === label)!;
    const toggle = () => button('Показывать мой профиль другим');
    const pressed = (control: HTMLButtonElement) => control.getAttribute('aria-pressed') === 'true';
    const notice = () => root.querySelector('.notice[role=alert]');
    const settle = async () => { await view.whenStable(); view.detectChanges(); };
    const click = async (control: HTMLButtonElement) => { control.click(); await settle(); };

    beforeEach(() => {
        api = spyObj<AccountProfileApi>({ loadPublicProfile: vi.fn(), savePublicProfile: vi.fn() });
        api.loadPublicProfile.mockReturnValue(of(off));
        toasts = { echo: vi.fn() };
        TestBed.configureTestingModule({ providers: [provideRouter([]), { provide: AccountProfileApi, useValue: api }, { provide: ToastService, useValue: toasts }] });
    });

    async function open(account: AccountProfile = profile): Promise<void> {
        view = TestBed.createComponent(PublicProfileSettingsComponent);
        view.componentRef.setInput('profile', account);
        view.componentRef.setInput('avatarUrl', 'blob:own-photo');
        root = view.nativeElement as HTMLElement;
        view.detectChanges();
        await settle();
    }

    it('keeps the username discoverable and hides the optional controls until enabled', async () => {
        await open();
        expect(root.querySelector('.consent-text')?.textContent).toContain('@reader');
        expect(pressed(toggle())).toBe(false);
        expect(root.querySelector('.profile-fields')).toBeNull();
        expect(root.querySelector('input')).toBeNull();
        expect(toggle().getAttribute('aria-describedby')).toBe('public-profile-consent-text');
        expect(root.textContent).not.toContain('Условия показа профиля');
        expect(root.querySelector('.preview-hidden')?.textContent).toBe('Профиль скрыт');
    });

    it('requires a saved username before enabling', async () => {
        await open({ ...profile, profileUsername: null });
        expect(toggle().disabled).toBe(true);
        expect(toggle().getAttribute('aria-describedby')).toContain('consent-username-hint');
        expect(root.querySelector('#consent-username-hint')?.textContent).toContain('задайте имя пользователя');
        await click(toggle());
        expect(api.savePublicProfile).not.toHaveBeenCalled();
    });

    it('enables without preselecting optional data and exposes joined independent buttons', async () => {
        api.savePublicProfile.mockReturnValue(of(on));
        await open();
        await click(toggle());
        expect(api.savePublicProfile).toHaveBeenCalledWith({ enabled: true, showDisplayName: false, showAvatar: false, showBio: false, textVersion: PUBLIC_PROFILE_TEXT_VERSION });
        expect(root.querySelector('legend')?.textContent).toBe('Что показывать?');
        expect([...root.querySelectorAll('.toggle-buttons button')].map(item => [item.textContent, item.getAttribute('aria-pressed')])).toEqual([['Имя', 'false'], ['Фото', 'false'], ['О себе', 'false']]);
        expect(root.querySelector('.preview .login')?.textContent).toBe('@reader');
        expect(toasts.echo).toHaveBeenCalledWith('Публичный профиль включён.');
    });

    it('saves the full consent state after every independent selection and previews exactly that state', async () => {
        api.loadPublicProfile.mockReturnValue(of(on));
        await open();
        api.savePublicProfile.mockReturnValueOnce(of({ ...on, showDisplayName: true }));
        await click(button('Имя'));
        expect(api.savePublicProfile).toHaveBeenLastCalledWith({ enabled: true, showDisplayName: true, showAvatar: false, showBio: false, textVersion: PUBLIC_PROFILE_TEXT_VERSION });
        expect(root.querySelector('.preview-name')?.textContent).toBe('Читатель');
        api.savePublicProfile.mockReturnValueOnce(of({ ...on, showDisplayName: true, showAvatar: true, showBio: true }));
        await click(button('О себе'));
        expect(root.querySelector('.preview-bio')?.textContent).toBe(profile.bio);
        expect(root.querySelector('.preview img')?.getAttribute('src')).toBe('blob:own-photo');
        api.savePublicProfile.mockReturnValueOnce(of({ ...on, showBio: true }));
        await click(button('Имя'));
        expect(root.querySelector('.preview-name')).toBeNull();
        expect(pressed(button('Имя'))).toBe(false);
    });

    it('withdraws all optional data and removes optional buttons on disabling', async () => {
        api.loadPublicProfile.mockReturnValue(of({ ...on, showDisplayName: true, showBio: true }));
        api.savePublicProfile.mockReturnValue(of(off));
        await open();
        await click(toggle());
        expect(api.savePublicProfile).toHaveBeenCalledWith({ enabled: false, showDisplayName: false, showAvatar: false, showBio: false, textVersion: PUBLIC_PROFILE_TEXT_VERSION });
        expect(root.querySelector('.profile-fields')).toBeNull();
        expect(root.querySelector('.preview-hidden')).not.toBeNull();
    });

    it('keeps controls focusable while saving, ignores concurrent changes and reports progress', async () => {
        api.loadPublicProfile.mockReturnValue(of(on));
        const pending = new Subject<PublicProfileConsent>();
        api.savePublicProfile.mockReturnValue(pending);
        await open();
        button('Имя').click();
        view.detectChanges();
        expect(root.querySelector('.status')?.textContent).toContain('Сохраняем…');
        expect([...root.querySelectorAll<HTMLButtonElement>('.controls button')].every(control => !control.disabled && control.getAttribute('aria-disabled') === 'true')).toBe(true);
        button('Фото').click();
        expect(api.savePublicProfile).toHaveBeenCalledTimes(1);
        pending.next({ ...on, showDisplayName: true }); pending.complete();
        await settle();
        expect(pressed(button('Имя'))).toBe(true);
        expect(root.querySelector('.status')?.textContent?.trim()).toBe('');
    });

    it('keeps the saved state after a failed change and permits retry', async () => {
        api.loadPublicProfile.mockReturnValue(of(on));
        api.savePublicProfile.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 503 })));
        await open();
        await click(button('О себе'));
        expect(pressed(button('О себе'))).toBe(false);
        expect(notice()?.textContent).toContain('Не удалось сохранить настройки');
        api.savePublicProfile.mockReturnValue(of({ ...on, showBio: true }));
        await click(button('О себе'));
        expect(pressed(button('О себе'))).toBe(true);
        expect(notice()).toBeNull();
    });

    it.each(['profile_username_required', 'consent_text_outdated'])('re-reads server state on %s while preserving error recovery', async code => {
        api.savePublicProfile.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 409, error: { code } })));
        await open();
        await click(toggle());
        expect(pressed(toggle())).toBe(false);
        expect(api.loadPublicProfile).toHaveBeenCalledTimes(2);
        expect(notice()?.textContent).toContain(code === 'profile_username_required' ? 'задайте имя пользователя' : 'Текст согласия обновился');
        if (code === 'consent_text_outdated') {
            const reload = vi.spyOn(view.componentInstance as unknown as { reloadPage(): void }, 'reloadPage').mockImplementation(() => undefined);
            await click(button('Обновить страницу'));
            expect(reload).toHaveBeenCalledOnce();
        }
    });

    it('keeps the last consent if the conflict refresh fails', async () => {
        await open();
        api.savePublicProfile.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 409, error: { code: 'profile_username_required' } })));
        api.loadPublicProfile.mockReturnValue(throwError(() => new Error('offline')));
        await click(toggle());
        expect(pressed(toggle())).toBe(false);
        expect(notice()).not.toBeNull();
    });

    it('can retry an isolated initial load failure and focuses the restored control', async () => {
        api.loadPublicProfile.mockReturnValue(throwError(() => new Error('offline')));
        await open();
        document.body.append(root);
        button('Повторить').focus();
        api.loadPublicProfile.mockReturnValue(of(off));
        await click(button('Повторить'));
        expect(document.activeElement).toBe(toggle());
        root.remove();
    });

    it.each([true, false])('offers the author page only when launched: %s', async launched => {
        TestBed.overrideProvider(COMMUNITY_LAUNCH, { useValue: launched });
        api.loadPublicProfile.mockReturnValue(of(on));
        await open();
        expect(root.querySelector('.preview a')?.getAttribute('href') ?? null).toBe(launched ? '/u/reader' : null);
    });

    it('does not preview missing optional data', async () => {
        api.loadPublicProfile.mockReturnValue(of({ ...on, showDisplayName: true, showAvatar: true, showBio: true }));
        await open({ ...profile, displayName: null, bio: null, avatarPresent: false });
        expect(root.querySelector('.preview-name, .preview-bio, .preview img')).toBeNull();
    });
});
