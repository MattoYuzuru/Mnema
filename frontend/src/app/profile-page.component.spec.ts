import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { AccountProfile, AccountProfileApi } from './account-profile.api';
import { AuthService } from './auth.service';
import { ProfilePageComponent } from './profile-page.component';
import { appConfig } from './app.config';
import { DURING_STUDY_STORAGE_KEY, NotificationPreferences } from './core/notifications/notification-preferences';
import { spyObj, type SpyObj } from '../testing/mocks';

const profile: AccountProfile = { accountId: 'd2815e20-ea25-4dce-977a-66ee086f294d',
    email: 'reader@example.test', emailVerified: true, profileUsername: 'reader', displayName: 'Reader',
    bio: '', avatarPresent: false, hasPassword: true };

describe('ProfilePageComponent', () => {
    let component: ProfilePageComponent;
    let api: SpyObj<AccountProfileApi>;
    const originalEmailWarning = appConfig.features.showEmailVerificationWarning;

    afterEach(() => { appConfig.features.showEmailVerificationWarning = originalEmailWarning; });

    beforeEach(() => {
        api = spyObj<AccountProfileApi>({
            load: vi.fn().mockName("AccountProfileApi.load"),
            update: vi.fn().mockName("AccountProfileApi.update"),
            uploadAvatar: vi.fn().mockName("AccountProfileApi.uploadAvatar"),
            avatarUrl: vi.fn().mockName("AccountProfileApi.avatarUrl")
        });
        api.load.mockReturnValue(of(profile));
        api.update.mockReturnValue(of({ ...profile, displayName: 'Updated' }));
        TestBed.configureTestingModule({ providers: [
                provideRouter([]),
                { provide: AccountProfileApi, useValue: api },
                { provide: AuthService, useValue: {
                        setPassword: vi.fn().mockName("AuthService.setPassword")
                    } }
            ] });
        component = TestBed.runInInjectionContext(() => new ProfilePageComponent());
    });

    it('loads and saves the native profile fields', async () => {
        await component.load();
        component.form.patchValue({ displayName: 'Updated' });
        await component.save();
        expect(api.update).toHaveBeenCalledWith({ profileUsername: 'reader', displayName: 'Updated', bio: '' });
        expect(component.profile()?.displayName).toBe('Updated');
        expect(component.saveSuccess()).toBe(true);
    });

    it('rejects oversized avatars before upload', async () => {
        const file = new File([new Uint8Array(10 * 1024 * 1024 + 1)], 'big.png', { type: 'image/png' });
        const input = document.createElement('input');
        Object.defineProperty(input, 'files', { value: [file] });
        await component.uploadAvatar({ target: input } as unknown as Event);
        expect(api.uploadAvatar).not.toHaveBeenCalled();
        expect(component.avatarError()).toBe('Изображение слишком большое или пустое. Выберите другое.');
    });

    it('requires password confirmation before contacting Identity', async () => {
        const auth = TestBed.inject(AuthService) as unknown as SpyObj<AuthService>;
        component.passwordForm.setValue({ currentPassword: 'existing-secret',
            newPassword: 'new-long-password', confirmPassword: 'different-password' });
        await component.changePassword();
        expect(auth.setPassword).not.toHaveBeenCalled();
        expect(component.passwordError()).toContain('не совпадают');
    });

    it('renders email verification and blocks a password over the server UTF-8 limit', async () => {
        appConfig.features.showEmailVerificationWarning = true;
        api.load.mockReturnValue(of({ ...profile, emailVerified: false }));
        const fixture = TestBed.createComponent(ProfilePageComponent);
        fixture.detectChanges();
        await fixture.whenStable();
        fixture.detectChanges();
        const root = fixture.nativeElement as HTMLElement;
        expect(root.querySelector('.notice')?.textContent).toContain('Почта ещё не подтверждена');
        const passwordButton = root.querySelector<HTMLButtonElement>('.password-sheet button[type=submit]')!;
        expect(passwordButton.disabled).toBe(true);
        const newPassword = root.querySelector<HTMLInputElement>('#new-password')!;
        expect(newPassword.getAttribute('aria-describedby')).toBe('password-hint');

        fixture.componentInstance.passwordForm.setValue({ currentPassword: 'current-secret',
            newPassword: 'é'.repeat(40), confirmPassword: 'é'.repeat(40) });
        fixture.componentInstance.passwordForm.controls.newPassword.markAsTouched();
        fixture.detectChanges();
        expect(passwordButton.disabled).toBe(true);
        expect(newPassword.getAttribute('aria-invalid')).toBe('true');
        expect(newPassword.getAttribute('aria-describedby')).toBe('password-hint password-error');
        expect(root.querySelector('.password-sheet .error')?.textContent).toContain('слишком короткий или длинный');
    });

    it('associates an invalid profile username with its field guidance', async () => {
        const fixture = TestBed.createComponent(ProfilePageComponent);
        fixture.detectChanges();
        await fixture.whenStable();
        fixture.componentInstance.form.controls.profileUsername.setValue('!');
        fixture.componentInstance.form.controls.profileUsername.markAsTouched();
        fixture.detectChanges();

        const root = fixture.nativeElement as HTMLElement;
        const input = root.querySelector<HTMLInputElement>('#profile-username')!;
        expect(input.getAttribute('aria-invalid')).toBe('true');
        expect(input.getAttribute('aria-describedby')).toBe('username-hint username-error');
        expect(root.querySelector('#username-error')?.textContent).toContain('3–50');
        expect(root.querySelector<HTMLButtonElement>('.profile-layout button[type=submit]')?.disabled).toBe(true);
    });

    it('uses the avatar itself as the keyboard accessible file trigger', async () => {
        const fixture = TestBed.createComponent(ProfilePageComponent);
        fixture.detectChanges();
        await fixture.whenStable();
        fixture.detectChanges();
        const root = fixture.nativeElement as HTMLElement;
        expect(root.querySelector('.profile-sheet .avatar-action')?.getAttribute('for')).toBe('avatar-file');
        expect(root.querySelector('.profile-sheet .avatar-action')?.getAttribute('aria-label')).toBe('Изменить аватар');
        expect(root.querySelector('.profile-sheet .file-input')?.getAttribute('type')).toBe('file');
        expect(root.querySelector('.file-label')).toBeNull();
    });

    it('stores the «Во время занятия» choice for notifications in this browser', async () => {
        localStorage.removeItem(DURING_STUDY_STORAGE_KEY);
        const fixture = TestBed.createComponent(ProfilePageComponent);
        fixture.detectChanges();
        await fixture.whenStable();
        fixture.detectChanges();
        const root = fixture.nativeElement as HTMLElement;
        expect(root.querySelector('#notifications-heading')?.textContent).toBe('Уведомления');
        const group = root.querySelector('section[aria-labelledby=notifications-heading] fieldset')!;
        expect(group.querySelector('legend')?.textContent).toBe('Во время занятия');
        expect(Array.from(group.querySelectorAll('.text')).map(label => label.textContent))
            .toEqual(['В паузах', 'Сразу', 'Только значок']);
        expect(group.querySelector<HTMLInputElement>('input:checked')?.value).toBe('AT_PAUSES');

        group.querySelector<HTMLInputElement>('input[value=BADGE_ONLY]')!.click();
        fixture.detectChanges();
        expect(TestBed.inject(NotificationPreferences).duringStudy()).toBe('BADGE_ONLY');
        expect(localStorage.getItem(DURING_STUDY_STORAGE_KEY)).toBe('BADGE_ONLY');
        localStorage.removeItem(DURING_STUDY_STORAGE_KEY);
    });
});
