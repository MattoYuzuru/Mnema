import type { Mock } from "vitest";
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { AuthService, AuthStatus, AuthUser } from './auth.service';
import { LoginPageComponent, identityErrorMessage } from './login-page.component';

describe('Identity form behavior', () => {
    const status = signal<AuthStatus>('anonymous');
    const user = signal<AuthUser | null>(null);
    let auth: {
        status: typeof status;
        user: typeof user;
        logoutUnconfirmed: ReturnType<typeof signal<boolean>>;
        loginWithPassword: Mock;
        registerWithPassword: Mock;
        logout: Mock;
    };
    async function page(path = 'login') {
        status.set('anonymous');
        user.set(null);
        auth = { status, user, logoutUnconfirmed: signal(false), loginWithPassword: vi.fn().mockResolvedValue(undefined),
            registerWithPassword: vi.fn().mockResolvedValue(undefined), logout: vi.fn().mockResolvedValue(undefined) };
        await TestBed.configureTestingModule({ imports: [LoginPageComponent], providers: [provideRouter([]),
                { provide: AuthService, useValue: auth }, { provide: ActivatedRoute, useValue: { snapshot: {
                            routeConfig: { path }, queryParamMap: convertToParamMap({ returnUrl: '/decks?tab=mine' })
                        } } }] }).compileComponents();
        const fixture = TestBed.createComponent(LoginPageComponent);
        fixture.detectChanges();
        await fixture.whenStable();
        return fixture;
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
        fixture.componentInstance.login = 'fixture';
        fixture.componentInstance.password = 'synthetic-password';
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
        fixture.componentInstance.email = 'fixture@example.test';
        fixture.componentInstance.username = 'fixture';
        fixture.componentInstance.password = 'я'.repeat(37);
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
        fixture.componentInstance.login = 'fixture';
        fixture.componentInstance.password = 'synthetic-password';
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
});
