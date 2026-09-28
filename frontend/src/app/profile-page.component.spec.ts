import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { AccountProfile, AccountProfileApi } from './account-profile.api';
import { AuthService } from './auth.service';
import { ProfilePageComponent } from './profile-page.component';
import { appConfig } from './app.config';

const profile: AccountProfile = { accountId: 'd2815e20-ea25-4dce-977a-66ee086f294d',
    email: 'reader@example.test', emailVerified: true, profileUsername: 'reader', displayName: 'Reader',
    bio: '', avatarPresent: false, hasPassword: true };

describe('ProfilePageComponent', () => {
    let component: ProfilePageComponent;
    let api: jasmine.SpyObj<AccountProfileApi>;
    const originalEmailWarning = appConfig.features.showEmailVerificationWarning;

    afterEach(() => { appConfig.features.showEmailVerificationWarning = originalEmailWarning; });

    beforeEach(() => {
        api = jasmine.createSpyObj<AccountProfileApi>('AccountProfileApi', ['load', 'update', 'uploadAvatar', 'avatarUrl']);
        api.load.and.returnValue(of(profile));
        api.update.and.returnValue(of({ ...profile, displayName: 'Updated' }));
        TestBed.configureTestingModule({ providers: [
            provideRouter([]),
            { provide: AccountProfileApi, useValue: api },
            { provide: AuthService, useValue: jasmine.createSpyObj<AuthService>('AuthService', ['setPassword']) }
        ] });
        component = TestBed.runInInjectionContext(() => new ProfilePageComponent());
    });

    it('loads and saves the native profile fields', async () => {
        await component.load();
        component.form.patchValue({ displayName: 'Updated' });
        await component.save();
        expect(api.update).toHaveBeenCalledWith({ profileUsername: 'reader', displayName: 'Updated', bio: '' });
        expect(component.profile()?.displayName).toBe('Updated');
        expect(component.saveSuccess()).toBeTrue();
    });

    it('rejects oversized avatars before upload', async () => {
        const file = new File([new Uint8Array(10 * 1024 * 1024 + 1)], 'big.png', { type: 'image/png' });
        const input = document.createElement('input');
        Object.defineProperty(input, 'files', { value: [file] });
        await component.uploadAvatar({ target: input } as unknown as Event);
        expect(api.uploadAvatar).not.toHaveBeenCalled();
        expect(component.avatarError()).toContain('10 МБ');
    });

    it('requires password confirmation before contacting Identity', async () => {
        const auth = TestBed.inject(AuthService) as jasmine.SpyObj<AuthService>;
        component.passwordForm.setValue({ currentPassword: 'existing-secret',
            newPassword: 'new-long-password', confirmPassword: 'different-password' });
        await component.changePassword();
        expect(auth.setPassword).not.toHaveBeenCalled();
        expect(component.passwordError()).toContain('не совпадают');
    });

    it('renders email verification and blocks a password over the server UTF-8 limit', async () => {
        appConfig.features.showEmailVerificationWarning = true;
        api.load.and.returnValue(of({ ...profile, emailVerified: false }));
        const fixture = TestBed.createComponent(ProfilePageComponent);
        fixture.detectChanges();
        await fixture.whenStable();
        fixture.detectChanges();
        const root = fixture.nativeElement as HTMLElement;
        expect(root.querySelector('.notice')?.textContent).toContain('Почта ещё не подтверждена');
        const passwordButton = root.querySelector<HTMLButtonElement>('.password-sheet button[type=submit]')!;
        expect(passwordButton.disabled).toBeTrue();

        fixture.componentInstance.passwordForm.setValue({ currentPassword: 'current-secret',
            newPassword: 'é'.repeat(40), confirmPassword: 'é'.repeat(40) });
        fixture.componentInstance.passwordForm.controls.newPassword.markAsTouched();
        fixture.detectChanges();
        expect(passwordButton.disabled).toBeTrue();
        expect(root.querySelector('.password-sheet .error')?.textContent).toContain('72 байта');
    });
});
