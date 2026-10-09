import type { Mock } from "vitest";
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { AuthService, AuthStatus, AuthUser } from './auth.service';
import { LoginPageComponent, identityErrorMessage } from './login-page.component';
import { AbuseProtectionFailure } from './turnstile.service';

describe('Identity form behavior', () => {
    it('explains protection outages and expiry without exposing upstream content', () => {
        expect(identityErrorMessage(new AbuseProtectionFailure('blocked'))).toBe('Вход и регистрация пока недоступны. Попробуйте позже.');
        expect(identityErrorMessage(new AbuseProtectionFailure('unavailable'))).toContain('недоступна');
        expect(identityErrorMessage(new AbuseProtectionFailure('retry'))).toContain('новая проверка');
        expect(identityErrorMessage(new HttpErrorResponse({ status: 503, error: {
            code: 'abuse_protection_unavailable', detail: 'private-upstream-content' } }))).toContain('недоступна');
        expect(identityErrorMessage(new HttpErrorResponse({ status: 403, error: {
            code: 'abuse_verification_failed', detail: 'private-upstream-content' } }))).toContain('Повторите');
    });
    const status = signal<AuthStatus>('anonymous');
    const user = signal<AuthUser | null>(null);
    let auth: {
        status: typeof status;
        user: typeof user;
        logoutUnconfirmed: ReturnType<typeof signal<boolean>>;
        loginWithPassword: Mock;
        registerWithPassword: Mock;
        logout: Mock;
        availableProviders: Mock;
        beginFederatedLogin: Mock;
    };
    async function page(path = 'login') {
        status.set('anonymous');
        user.set(null);
        auth = { status, user, logoutUnconfirmed: signal(false), loginWithPassword: vi.fn().mockResolvedValue(undefined),
            registerWithPassword: vi.fn().mockResolvedValue(undefined), logout: vi.fn().mockResolvedValue(undefined),
            availableProviders: vi.fn().mockResolvedValue(['google', 'github']), beginFederatedLogin: vi.fn().mockResolvedValue(undefined) };
        await TestBed.configureTestingModule({ imports: [LoginPageComponent], providers: [provideRouter([]),
                { provide: AuthService, useValue: auth }, { provide: ActivatedRoute, useValue: { snapshot: {
                            routeConfig: { path }, queryParamMap: convertToParamMap({ returnUrl: '/decks?tab=mine' })
                        } } }] }).compileComponents();
        const fixture = TestBed.createComponent(LoginPageComponent);
        fixture.detectChanges();
        await fixture.whenStable();
        fixture.detectChanges();
        return fixture;
    }

    function enter(fixture: ComponentFixture<LoginPageComponent>, id: string, value: string): void {
        const input = (fixture.nativeElement as HTMLElement).querySelector<HTMLInputElement>(`#${id}`)!;
        input.value = value;
        input.dispatchEvent(new Event('input', { bubbles: true }));
    }

    it('exposes real labels and focuses the invalid field without sending credentials', async () => {
        const fixture = await page();
        const element = fixture.nativeElement as HTMLElement;
        expect(element.querySelector('label[for="login-name"]')?.textContent).toContain('Логин');
        expect(element.querySelector<HTMLInputElement>('#password')?.autocomplete).toBe('current-password');
        element.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }));
        fixture.detectChanges();
        await fixture.whenStable();
        expect(auth.loginWithPassword).not.toHaveBeenCalled();
        expect(element.querySelector('[role="alert"]')?.textContent).toContain('Проверьте');
        expect(document.activeElement?.id).toBe('login-name');
    });

    it('preserves login and clears password after an unsuccessful request without echoing server content', async () => {
        const fixture = await page();
        auth.loginWithPassword.mockRejectedValue(new HttpErrorResponse({ status: 401, error: { detail: 'private-debug-payload' } }));
        enter(fixture, 'login-name', 'fixture');
        enter(fixture, 'password', 'synthetic-password');
        fixture.detectChanges();
        await fixture.whenStable();
        (fixture.nativeElement as HTMLElement).querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }));
        await fixture.whenStable();
        fixture.detectChanges();
        expect(auth.loginWithPassword).toHaveBeenCalledTimes(1);
        expect(auth.loginWithPassword).toHaveBeenCalledWith('fixture', 'synthetic-password', '/decks?tab=mine');
        expect(fixture.componentInstance.login).toBe('fixture');
        expect(fixture.componentInstance.password).toBe('');
        expect(fixture.nativeElement.textContent).not.toContain('private-debug-payload');
    });

    it('enforces registration UTF-8 password bound before a network mutation', async () => {
        const fixture = await page('register');
        enter(fixture, 'email', 'fixture@example.test');
        enter(fixture, 'username', 'fixture');
        enter(fixture, 'password', 'я'.repeat(37));
        fixture.detectChanges();
        await fixture.whenStable();
        (fixture.nativeElement as HTMLElement).querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }));
        await fixture.whenStable();
        fixture.detectChanges();
        expect(auth.registerWithPassword).not.toHaveBeenCalled();
        expect(document.activeElement?.id).toBe('password');
    });

    it('disables duplicate submission while a request is pending', async () => {
        const fixture = await page();
        let reject!: (reason: unknown) => void;
        auth.loginWithPassword.mockReturnValue(new Promise<void>((_, fail) => { reject = fail; }));
        enter(fixture, 'login-name', 'fixture');
        enter(fixture, 'password', 'synthetic-password');
        fixture.detectChanges();
        await fixture.whenStable();
        const form = (fixture.nativeElement as HTMLElement).querySelector('form')!;
        form.dispatchEvent(new Event('submit', { cancelable: true }));
        form.dispatchEvent(new Event('submit', { cancelable: true }));
        fixture.detectChanges();
        expect(auth.loginWithPassword).toHaveBeenCalledTimes(1);
        expect((fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('button[type="submit"]')?.disabled).toBe(true);
        reject(new Error('synthetic-unavailable'));
        await fixture.whenStable();
        expect(fixture.componentInstance.busy()).toBe(false);
    });

    it('distinguishes local logout from unconfirmed server revocation', async () => {
        const fixture = await page();
        auth.logout.mockImplementation(async () => { auth.logoutUnconfirmed.set(true); throw new Error('offline'); });
        await fixture.componentInstance.logout();
        fixture.detectChanges();
        expect(fixture.nativeElement.textContent).toContain('На этом устройстве вы вышли');
        expect(fixture.nativeElement.textContent).toContain('Повторить выход');
    });

    it('keeps all transport diagnostics out of public error copy', () => {
        expect(identityErrorMessage(new Error('private-token'))).not.toContain('private-token');
        expect(identityErrorMessage(new HttpErrorResponse({ status: 429 }))).toContain('Слишком много');
    });

    it('enables only configured services and preserves the return page without submitting the password form', async () => {
        const fixture = await page();
        const element = fixture.nativeElement as HTMLElement;
        const google = element.querySelector<HTMLButtonElement>('[aria-label="Войти через Google"]')!;
        expect(google.disabled).toBe(false);
        expect(element.querySelector('[aria-label="Войти через Яндекс"]')).toBeNull();
        expect([...element.querySelectorAll('.provider-option strong')].map(name => name.textContent)).toEqual(['Google', 'GitHub']);
        google.click();
        google.click();
        await fixture.whenStable();
        expect(auth.beginFederatedLogin).toHaveBeenCalledExactlyOnceWith('google', '/decks?tab=mine');
        expect(auth.loginWithPassword).not.toHaveBeenCalled();
        expect(fixture.componentInstance.busy()).toBe(true);
    });

    it('draws no provider buttons or divider when Identity reports no configured provider', async () => {
        status.set('anonymous');
        user.set(null);
        auth = { status, user, logoutUnconfirmed: signal(false), loginWithPassword: vi.fn(), registerWithPassword: vi.fn(), logout: vi.fn(),
            availableProviders: vi.fn().mockResolvedValue([]), beginFederatedLogin: vi.fn() };
        await TestBed.configureTestingModule({ imports: [LoginPageComponent], providers: [provideRouter([]),
                { provide: AuthService, useValue: auth }, { provide: ActivatedRoute, useValue: { snapshot: {
                            routeConfig: { path: 'login' }, queryParamMap: convertToParamMap({})
                        } } }] }).compileComponents();
        const fixture = TestBed.createComponent(LoginPageComponent);
        fixture.detectChanges();
        await fixture.whenStable();
        fixture.detectChanges();
        const element = fixture.nativeElement as HTMLElement;
        expect(element.querySelector('.provider-options')).toBeNull();
        expect(element.querySelector('.password-divider')).toBeNull();
        expect(element.querySelector('form')).not.toBeNull();
        expect(element.querySelector('.intro')?.textContent).toContain('Войдите с логином или почтой');
    });

    it('tells the user which documents apply before they submit or pick a provider, without a consent control', async () => {
        const fixture = await page('register');
        const element = fixture.nativeElement as HTMLElement;
        const notice = element.querySelector('[data-testid="legal-notice"]')!;
        const links = [...notice.querySelectorAll('a')].map(link => [link.getAttribute('href'), link.textContent!.replace(/\s+/g, ' ').trim()]);
        expect(links[0]).toEqual(['/terms', 'Пользовательское соглашение (откроется в новой вкладке)']);
        expect(links[1]![0]).toBe('/privacy');
        expect(links[2]![0]).toBe('/privacy#cross-border');
        expect(notice.textContent).toContain('Продолжая, вы принимаете');
        expect(notice.textContent).toContain('ознакомились с');
        for (const link of notice.querySelectorAll('a')) expect(link.getAttribute('rel')).toContain('noopener');
        expect(element.querySelector('input[type="checkbox"]')).toBeNull();
    });

    it('keeps password login usable when availability fails and offers a retry', async () => {
        const fixture = await page();
        auth.availableProviders.mockRejectedValue(new Error('private-provider-secret'));
        await fixture.componentInstance.loadProviders();
        fixture.detectChanges();
        expect(fixture.nativeElement.textContent).toContain('Не удалось проверить');
        expect(fixture.nativeElement.textContent).not.toContain('private-provider-secret');
        expect(fixture.componentInstance.busy()).toBe(false);
        auth.availableProviders.mockResolvedValue(['yandex']);
        await fixture.componentInstance.loadProviders();
        expect(fixture.componentInstance.available()).toEqual(['yandex']);
    });

    it('recovers from navigation failure and ignores unavailable provider clicks', async () => {
        const fixture = await page('register');
        await fixture.componentInstance.loginWithProvider('yandex');
        expect(auth.beginFederatedLogin).not.toHaveBeenCalled();
        auth.beginFederatedLogin.mockRejectedValue(new Error('private-diagnostic'));
        await fixture.componentInstance.loginWithProvider('google');
        expect(fixture.componentInstance.busy()).toBe(false);
        expect(fixture.componentInstance.error()).not.toContain('private-diagnostic');
    });
});
