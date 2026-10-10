import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, provideRouter } from '@angular/router';
import { NEVER, Subject, of, throwError } from 'rxjs';
import { AccountProfile, AccountProfileApi } from './account-profile.api';
import { AuthService } from './auth.service';
import { ProfilePageComponent } from './profile-page.component';
import { appConfig } from './app.config';
import { DURING_STUDY_STORAGE_KEY, NotificationPreferences } from './core/notifications/notification-preferences';
import { PlansApiService } from './features/plans/plans-api.service';
import { SpeechInputApiService } from './features/speech/speech-input.api';
import { UsageApiService } from './features/usage/usage-api.service';
import type { UsageSnapshot } from './features/usage/usage.models';
import { plusUsage } from './features/usage/usage-test-data';
import { spyObj, type SpyObj } from '../testing/mocks';
import fixture from '../../../contracts/identity/public-profile.json';
import type { PublicProfileConsent } from './public-profile';

const profile: AccountProfile = { accountId: 'd2815e20-ea25-4dce-977a-66ee086f294d',
    email: 'reader@example.test', emailVerified: true, profileUsername: 'reader', displayName: 'Reader',
    bio: '', avatarPresent: false, hasPassword: true };

const consentDefault = fixture.consentDefault as PublicProfileConsent;

describe('ProfilePageComponent', () => {
    let component: ProfilePageComponent;
    let api: SpyObj<AccountProfileApi>;
    let usage: SpyObj<UsageApiService>;
    const originalEmailWarning = appConfig.features.showEmailVerificationWarning;

    afterEach(() => { appConfig.features.showEmailVerificationWarning = originalEmailWarning; });

    beforeEach(() => {
        api = spyObj<AccountProfileApi>({
            load: vi.fn().mockName("AccountProfileApi.load"),
            update: vi.fn().mockName("AccountProfileApi.update"),
            uploadAvatar: vi.fn().mockName("AccountProfileApi.uploadAvatar"),
            loadAvatar: vi.fn().mockName("AccountProfileApi.loadAvatar"),
            loadPublicProfile: vi.fn().mockName("AccountProfileApi.loadPublicProfile"),
            savePublicProfile: vi.fn().mockName("AccountProfileApi.savePublicProfile")
        });
        usage = spyObj<UsageApiService>({ load: vi.fn().mockName("UsageApiService.load") });
        usage.load.mockReturnValue(of(plusUsage() as unknown as UsageSnapshot));
        api.load.mockReturnValue(of(profile));
        api.update.mockReturnValue(of({ ...profile, displayName: 'Updated' }));
        api.loadPublicProfile.mockReturnValue(of(consentDefault));
        api.loadAvatar.mockReturnValue(of(new Blob(['png'], { type: 'image/png' })));
        TestBed.configureTestingModule({ providers: [
                provideRouter([]),
                { provide: AccountProfileApi, useValue: api },
                { provide: UsageApiService, useValue: usage },
                { provide: PlansApiService, useValue: { load: () => NEVER } },
                { provide: SpeechInputApiService, useValue: { withdrawConsent: () => of(undefined) } },
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

    it('saves multiline bio and displays the canonical server text', async () => {
        await component.load();
        const bio = '  Учусь ✨  \n\n\n  • Языки  ';
        api.update.mockReturnValue(of({ ...profile, bio: 'Учусь ✨\n\n• Языки' }));
        component.form.controls.bio.setValue(bio);
        await component.save();
        expect(api.update).toHaveBeenCalledWith({ profileUsername: 'reader', displayName: 'Reader', bio });
        expect(component.saveSuccess()).toBe(true);
        expect(component.saveError()).toBeNull();
        expect(component.form.controls.bio.value).toBe('Учусь ✨\n\n• Языки');
    });

    it('blocks excessive lines with accessible guidance before sending an edit', async () => {
        const fixture = TestBed.createComponent(ProfilePageComponent);
        fixture.detectChanges();
        await fixture.whenStable();
        fixture.componentInstance.form.controls.bio.setValue(Array(7).fill('строка').join('\n'));
        fixture.componentInstance.form.controls.bio.markAsTouched();
        fixture.detectChanges();
        const root = fixture.nativeElement as HTMLElement;
        const input = root.querySelector<HTMLTextAreaElement>('#profile-bio')!;
        expect(input.getAttribute('aria-invalid')).toBe('true');
        expect(input.getAttribute('aria-describedby')).toBe('bio-hint bio-error');
        expect(root.querySelector('#bio-error')?.textContent).toContain('6 строк');
        expect(root.querySelector<HTMLButtonElement>('form:has(#profile-bio) button')?.disabled).toBe(true);
        await fixture.componentInstance.save();
        expect(api.update).not.toHaveBeenCalled();
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

    it('shows the «ИИ-бюджет» block next to the other sections', async () => {
        const fixture = TestBed.createComponent(ProfilePageComponent);
        fixture.detectChanges();
        await fixture.whenStable();
        fixture.detectChanges();
        await fixture.whenStable();
        fixture.detectChanges();
        const root = fixture.nativeElement as HTMLElement;
        const section = root.querySelector('section#ai-budget')!;
        expect(section.getAttribute('aria-labelledby')).toBe('ai-budget-heading');
        expect(root.querySelector('#ai-budget-heading')?.textContent).toBe('ИИ-бюджет');
        expect(section.querySelector('app-usage-meter .summary')?.textContent).toContain('Использовано 14');
        expect(root.querySelector('#speech-consent-heading')?.textContent).toBe('Распознавание речи');
        expect(root.querySelector('#speech-consent app-speech-consent-settings button')?.textContent).toContain('Отозвать согласие');
    });

    it('keeps the profile working when the usage request fails', async () => {
        usage.load.mockReturnValue(throwError(() => new Error('offline')));
        const fixture = TestBed.createComponent(ProfilePageComponent);
        fixture.detectChanges();
        await fixture.whenStable();
        fixture.detectChanges();
        await fixture.whenStable();
        fixture.detectChanges();
        const root = fixture.nativeElement as HTMLElement;
        expect(root.querySelector('#ai-budget')?.textContent).toContain('Не удалось узнать расход ИИ');
        expect(root.querySelector('#ai-budget [role=alert]')).toBeNull();
        expect(root.querySelector('#profile-username')).not.toBeNull();
        expect(root.querySelector('#notifications-heading')).not.toBeNull();
        expect(root.querySelector('.profile-layout button[type=submit]')).not.toBeNull();
    });

    it('brings the «ИИ-бюджет» heading into view and focuses it for /profile#ai-budget', async () => {
        const scroll = vi.spyOn(HTMLElement.prototype, 'scrollIntoView');
        const route = TestBed.inject(ActivatedRoute);
        Object.defineProperty(route.snapshot, 'fragment', { value: 'ai-budget' });
        const fixture = TestBed.createComponent(ProfilePageComponent);
        document.body.append(fixture.nativeElement as HTMLElement);
        fixture.detectChanges();
        await fixture.whenStable();
        fixture.detectChanges();
        await fixture.whenStable();
        const heading = (fixture.nativeElement as HTMLElement).querySelector<HTMLElement>('#ai-budget-heading')!;
        expect(scroll).toHaveBeenCalledWith({ block: 'start' });
        expect(document.activeElement).toBe(heading);
        (fixture.nativeElement as HTMLElement).remove();
    });

    it('reveals the block when only the fragment changes on an already open profile', async () => {
        const scroll = vi.spyOn(HTMLElement.prototype, 'scrollIntoView');
        const fragments = new Subject<string | null>();
        Object.defineProperty(TestBed.inject(ActivatedRoute), 'fragment', { value: fragments });
        const fixture = TestBed.createComponent(ProfilePageComponent);
        document.body.append(fixture.nativeElement as HTMLElement);
        fixture.detectChanges();
        await fixture.whenStable();
        fixture.detectChanges();
        await fixture.whenStable();
        expect(scroll).not.toHaveBeenCalled();

        fragments.next('elsewhere');
        fixture.detectChanges();
        await fixture.whenStable();
        expect(scroll).not.toHaveBeenCalled();

        fragments.next('ai-budget');
        fixture.detectChanges();
        await fixture.whenStable();
        expect(scroll).toHaveBeenCalledTimes(1);
        expect(document.activeElement).toBe((fixture.nativeElement as HTMLElement).querySelector('#ai-budget-heading'));

        fixture.destroy();
        fragments.next('ai-budget');
        expect(scroll).toHaveBeenCalledTimes(1);
        (fixture.nativeElement as HTMLElement).remove();
    });

    it('does not move focus when the profile is opened without the anchor', async () => {
        const scroll = vi.spyOn(HTMLElement.prototype, 'scrollIntoView');
        const fixture = TestBed.createComponent(ProfilePageComponent);
        fixture.detectChanges();
        await fixture.whenStable();
        fixture.detectChanges();
        expect(scroll).not.toHaveBeenCalled();
    });

    describe('own avatar', () => {
        const withAvatar = { ...profile, avatarPresent: true };
        let created: string[];
        let revoked: string[];

        const original = { create: Object.getOwnPropertyDescriptor(URL, 'createObjectURL'), revoke: Object.getOwnPropertyDescriptor(URL, 'revokeObjectURL') };
        const restore = (name: 'createObjectURL' | 'revokeObjectURL', descriptor: PropertyDescriptor | undefined): void => {
            if (descriptor === undefined) Reflect.deleteProperty(URL, name); else Object.defineProperty(URL, name, descriptor);
        };
        afterEach(() => { restore('createObjectURL', original.create); restore('revokeObjectURL', original.revoke); });

        beforeEach(() => {
            created = [];
            revoked = [];
            api.load.mockReturnValue(of(withAvatar));
            Object.defineProperty(URL, 'createObjectURL', { configurable: true, writable: true, value: vi.fn(() => `blob:own-${created.push('x')}`) });
            Object.defineProperty(URL, 'revokeObjectURL', { configurable: true, writable: true, value: vi.fn((url: string) => { revoked.push(url); }) });
        });

        async function open() {
            const fixture = TestBed.createComponent(ProfilePageComponent);
            fixture.detectChanges();
            await fixture.whenStable();
            fixture.detectChanges();
            await fixture.whenStable();
            fixture.detectChanges();
            return { fixture, root: fixture.nativeElement as HTMLElement };
        }

        it('shows the photo from the owner endpoint as a local address, never the public avatar URL', async () => {
            const { root } = await open();
            expect(api.loadAvatar).toHaveBeenCalledOnce();
            expect(root.querySelector('.avatar-preview img')?.getAttribute('src')).toBe('blob:own-1');
        });

        it('keeps the letter when the owner endpoint fails or answers with something that is not an image', async () => {
            api.loadAvatar.mockReturnValue(throwError(() => new Error('offline')));
            let view = await open();
            expect(view.root.querySelector('.avatar-preview img')).toBeNull();
            expect(view.root.querySelector('.avatar-preview span')?.textContent).toBe('R');
            view.fixture.destroy();

            api.loadAvatar.mockReturnValue(of(new Blob(['<svg/>'], { type: 'image/svg+xml' })));
            view = await open();
            expect(view.root.querySelector('.avatar-preview img')).toBeNull();
            expect(URL.createObjectURL).not.toHaveBeenCalled();
        });

        it('does not ask for a photo the profile does not have', async () => {
            api.load.mockReturnValue(of(profile));
            const { root } = await open();
            expect(api.loadAvatar).not.toHaveBeenCalled();
            expect(root.querySelector('.avatar-preview img')).toBeNull();
        });

        it('releases the previous address when a new photo replaces it and the last one on destroy', async () => {
            const { fixture } = await open();
            api.uploadAvatar.mockReturnValue(of(undefined));
            const input = document.createElement('input');
            Object.defineProperty(input, 'files', { value: [new File(['x'], 'me.png', { type: 'image/png' })] });
            await fixture.componentInstance.uploadAvatar({ target: input } as unknown as Event);
            await fixture.whenStable();
            expect(fixture.componentInstance.avatarUrl()).toBe('blob:own-2');
            expect(revoked).toEqual(['blob:own-1']);
            fixture.destroy();
            expect(revoked).toEqual(['blob:own-1', 'blob:own-2']);
        });

        it('drops an older photo response that arrives after a newer one', async () => {
            const slow = new Subject<Blob>();
            api.loadAvatar.mockReturnValueOnce(slow).mockReturnValueOnce(of(new Blob(['png'], { type: 'image/webp' })));
            const fixture = TestBed.createComponent(ProfilePageComponent);
            fixture.detectChanges();
            await fixture.whenStable();
            api.uploadAvatar.mockReturnValue(of(undefined));
            const input = document.createElement('input');
            Object.defineProperty(input, 'files', { value: [new File(['x'], 'me.png', { type: 'image/png' })] });
            await fixture.componentInstance.uploadAvatar({ target: input } as unknown as Event);
            await fixture.whenStable();
            expect(fixture.componentInstance.avatarUrl()).toBe('blob:own-1');
            slow.next(new Blob(['old'], { type: 'image/png' }));
            slow.complete();
            await fixture.whenStable();
            expect(fixture.componentInstance.avatarUrl()).toBe('blob:own-1');
            expect(created).toHaveLength(1);
        });
    });

    it('hosts the «Публичный профиль» section between the profile details and the AI budget', async () => {
        const fixture = TestBed.createComponent(ProfilePageComponent);
        fixture.detectChanges();
        await fixture.whenStable();
        fixture.detectChanges();
        await fixture.whenStable();
        fixture.detectChanges();
        const root = fixture.nativeElement as HTMLElement;
        const section = root.querySelector('section#public-profile-settings')!;
        expect(section.getAttribute('aria-labelledby')).toBe('public-profile-heading');
        expect(root.querySelector('#public-profile-heading')?.textContent).toBe('Публичный профиль');
        expect(section.querySelector('app-public-profile-settings input[role=switch]')).not.toBeNull();
        const ids = [...root.querySelectorAll('.profile-layout > section')].map(item => item.id || 'details');
        expect(ids.slice(0, 3)).toEqual(['details', 'public-profile-settings', 'ai-budget']);
    });
});
