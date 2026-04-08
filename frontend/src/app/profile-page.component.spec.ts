import { FormBuilder } from '@angular/forms';
import { of } from 'rxjs';

import { AuthService, PasswordStatus } from './auth.service';
import { I18nService } from './core/services/i18n.service';
import { ToastService } from './core/services/toast.service';
import { MediaApiService } from './core/services/media-api.service';
import { ProfilePageComponent } from './profile-page.component';
import { UserApiService, UserProfile } from './user-api.service';

describe('ProfilePageComponent', () => {
    let component: ProfilePageComponent;
    let auth: jasmine.SpyObj<AuthService>;
    let api: jasmine.SpyObj<UserApiService>;
    let toast: jasmine.SpyObj<ToastService>;

    const profile: UserProfile = {
        id: 'user-1',
        email: 'mnema@example.com',
        username: 'mnema',
        bio: 'old bio',
        avatarUrl: null,
        avatarMediaId: null,
        admin: false,
        createdAt: '2026-04-08T10:00:00Z',
        updatedAt: '2026-04-08T10:00:00Z'
    };

    beforeEach(() => {
        localStorage.removeItem('mnema_language');

        auth = jasmine.createSpyObj<AuthService>('AuthService', ['setPassword', 'status', 'getPasswordStatus']);
        api = jasmine.createSpyObj<UserApiService>('UserApiService', ['updateMe', 'getMe']);
        toast = jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error', 'info', 'warning', 'show', 'dismiss']);

        component = new ProfilePageComponent(
            auth,
            api,
            jasmine.createSpyObj<MediaApiService>('MediaApiService', ['resolve', 'uploadFile']),
            new FormBuilder(),
            new I18nService(),
            toast
        );
    });

    it('shows a toast after saving profile changes', async () => {
        api.updateMe.and.returnValue(of({ ...profile, username: 'updated', bio: 'new bio' }));
        component.profile = profile;
        component.form.setValue({
            username: 'updated',
            bio: 'new bio'
        });

        component.save();
        await Promise.resolve();

        expect(toast.success).toHaveBeenCalledWith('profile.saveSuccess');
    });

    it('shows a toast after updating password', async () => {
        const passwordStatus: PasswordStatus = { hasPassword: true };
        auth.setPassword.and.returnValue(Promise.resolve(passwordStatus));
        component.passwordStatus = passwordStatus;
        component.passwordForm.setValue({
            currentPassword: 'old-password',
            newPassword: 'new-password-123',
            confirmPassword: 'new-password-123'
        });

        component.savePassword();
        await Promise.resolve();
        await Promise.resolve();

        expect(toast.success).toHaveBeenCalledWith('profile.passwordSuccess');
    });
});
