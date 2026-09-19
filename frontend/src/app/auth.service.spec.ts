import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { AuthService } from './auth.service';
import { AUTH_BROWSER, AuthBrowser, BROWSER_IDENTITY_CONFIG } from './auth-browser';
import { AUTH_SCOPES, AUTH_STORAGE_KEY, PKCE_STORAGE_KEY } from './auth-protocol';

describe('real Identity browser protocol orchestration', () => {
    const issuer = 'https://identity.example.test';
    const origin = 'https://app.example.test';
    const redirectUri = `${origin}/auth/callback`;
    const now = 1800000000000;
    const profile = { accountId: '11111111-1111-4111-8111-111111111111', email: 'fixture@example.test',
        emailVerified: false, profileUsername: 'fixture', displayName: null, hasPassword: true, status: 'ACTIVE' };
    let auth: AuthService;
    let http: HttpTestingController;
    let router: jasmine.SpyObj<Router>;
    let storage: Map<string, string>;
    let browser: AuthBrowser;
    let navigate: jasmine.Spy;
    const settle = async () => { for (let i = 0; i < 8; i++) await Promise.resolve(); };

    beforeEach(() => {
        storage = new Map();
        navigate = jasmine.createSpy('navigate');
        browser = { origin, pathname: '/decks', search: '', now: () => now,
            random: () => 'r'.repeat(43), challenge: async () => 'c'.repeat(43), navigate,
            clearQuery: jasmine.createSpy('clearQuery'), storage: {
                get length() { return storage.size; }, clear: () => storage.clear(),
                key: index => Array.from(storage.keys())[index] ?? null,
                getItem: key => storage.get(key) ?? null, setItem: (key, value) => { storage.set(key, value); },
                removeItem: key => { storage.delete(key); }
            } };
        router = jasmine.createSpyObj<Router>('Router', ['navigate', 'navigateByUrl'], { url: '/decks' });
        router.navigate.and.resolveTo(true);
        router.navigateByUrl.and.resolveTo(true);
        TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(),
            { provide: Router, useValue: router }, { provide: AUTH_BROWSER, useValue: browser },
            { provide: BROWSER_IDENTITY_CONFIG, useValue: { authServerUrl: issuer, clientId: 'mnema-web', identityRedirectUri: redirectUri, learningApiBaseUrl: '/api' } }] });
        auth = TestBed.inject(AuthService);
        http = TestBed.inject(HttpTestingController);
    });
    afterEach(() => { auth.expireSession(); http.verify(); });

    function stored(token = 'opaque-token'): void {
        storage.set(AUTH_STORAGE_KEY, JSON.stringify({ token, expiresAt: now + 300000, issuer, clientId: 'mnema-web' }));
    }
    function callback(search = `?code=one-use-code&state=${'s'.repeat(43)}`): void {
        Object.assign(browser, { pathname: '/auth/callback', search });
        storage.set(PKCE_STORAGE_KEY, JSON.stringify({ state: 's'.repeat(43), verifier: 'v'.repeat(43),
            returnUrl: '/decks?tab=mine', createdAt: now, issuer, clientId: 'mnema-web', redirectUri }));
    }
    function tokenResponse(): void {
        const request = http.expectOne(`${issuer}/oauth2/token`);
        expect(request.request.withCredentials).toBeFalse();
        expect(request.request.headers.has('Authorization')).toBeFalse();
        const body = new URLSearchParams(request.request.body as string);
        expect(body.get('code_verifier')).toBe('v'.repeat(43));
        expect(body.get('client_secret')).toBeNull();
        expect(body.get('redirect_uri')).toBe(redirectUri);
        request.flush({ access_token: 'opaque-token', expires_in: 300, token_type: 'Bearer', scope: AUTH_SCOPES,
            id_token: 'untrusted-id-token', refresh_token: 'unwanted-refresh-token' });
    }

    it('restores single-flight only after real server profile verification, never from stored claims', async () => {
        stored();
        const first = auth.restore();
        expect(auth.restore()).toBe(first);
        expect(auth.accessToken()).toBeNull();
        expect(auth.status()).toBe('pending');
        const me = http.expectOne(`${issuer}/api/accounts/me`);
        expect(me.request.headers.get('Authorization')).toBe('Bearer opaque-token');
        expect(me.request.withCredentials).toBeFalse();
        me.flush(profile);
        await first;
        expect(auth.status()).toBe('authenticated');
        expect(auth.user()?.accountId).toBe(profile.accountId);
    });

    it('revoked cached access is discarded and cannot satisfy a guard', async () => {
        stored();
        const result = auth.restore();
        http.expectOne(`${issuer}/api/accounts/me`).flush({}, { status: 401, statusText: 'Unauthorized' });
        await result;
        expect(auth.status()).toBe('anonymous');
        expect(storage.has(AUTH_STORAGE_KEY)).toBeFalse();
    });

    it('late restoration cannot resurrect an expired or replaced session', async () => {
        stored();
        const result = auth.restore();
        const me = http.expectOne(`${issuer}/api/accounts/me`);
        auth.expireSession();
        me.flush(profile);
        await result;
        expect(auth.user()).toBeNull();
        expect(auth.accessToken()).toBeNull();
    });

    it('starts public S256 authorization with one bounded transaction and safe return path', async () => {
        await auth.beginLogin('//evil.test');
        const url = new URL(navigate.calls.mostRecent().args[0]);
        expect(url.origin).toBe(issuer);
        expect(url.pathname).toBe('/oauth2/authorize');
        expect(url.searchParams.get('scope')).toBe(AUTH_SCOPES);
        expect(url.searchParams.get('code_challenge_method')).toBe('S256');
        expect(url.searchParams.get('redirect_uri')).toBe(redirectUri);
        expect(JSON.parse(storage.get(PKCE_STORAGE_KEY)!).returnUrl).toBe('/decks');
        expect(auth.accessToken()).toBeNull();
    });

    it('consumes callback before exchange, then verifies profile and stores no ID/refresh token', async () => {
        callback();
        const result = auth.completeCallback();
        expect(storage.has(PKCE_STORAGE_KEY)).toBeFalse();
        expect(browser.clearQuery).toHaveBeenCalled();
        tokenResponse();
        await settle();
        expect(auth.status()).toBe('pending');
        http.expectOne(`${issuer}/api/accounts/me`).flush(profile);
        await result;
        expect(auth.status()).toBe('authenticated');
        expect(router.navigateByUrl).toHaveBeenCalledWith('/decks?tab=mine', { replaceUrl: true });
        expect(storage.get(AUTH_STORAGE_KEY)).not.toContain('untrusted');
        expect(storage.get(AUTH_STORAGE_KEY)).not.toContain('unwanted');
    });

    it('rejects wrong, duplicated, unsolicited and replayed callback before network exchange', async () => {
        for (const search of ['?code=code&state=wrong', `?code=a&code=b&state=${'s'.repeat(43)}`,
            `?code=a&state=${'s'.repeat(43)}&iss=https://evil.test`, `?error=denied&state=${'s'.repeat(43)}`]) {
            callback(search);
            await expectAsync(auth.completeCallback()).toBeRejected();
            expect(storage.has(PKCE_STORAGE_KEY)).toBeFalse();
            expect(auth.accessToken()).toBeNull();
        }
        await expectAsync(auth.completeCallback()).toBeRejected();
        http.expectNone(`${issuer}/oauth2/token`);
    });

    it('late callback token response cannot resurrect a cleared session or request profile', async () => {
        callback();
        const result = auth.completeCallback();
        auth.expireSession();
        tokenResponse();
        await result;
        http.expectNone(`${issuer}/api/accounts/me`);
        expect(auth.accessToken()).toBeNull();
    });

    it('requires CSRF cookie exchange for password login and never treats its response as bearer access', async () => {
        const result = auth.loginWithPassword('fixture', 'synthetic-password', '/decks');
        const csrf = http.expectOne(`${issuer}/api/accounts/csrf`);
        expect(csrf.request.withCredentials).toBeTrue();
        csrf.flush({ headerName: 'X-CSRF-TOKEN', token: 'csrf-token' });
        await settle();
        const login = http.expectOne(`${issuer}/api/accounts/login`);
        expect(login.request.body).toEqual({ login: 'fixture', password: 'synthetic-password' });
        expect(login.request.withCredentials).toBeTrue();
        expect(login.request.headers.get('X-CSRF-TOKEN')).toBe('csrf-token');
        login.flush(profile);
        await result;
        expect(navigate).toHaveBeenCalled();
        expect(auth.accessToken()).toBeNull();
        expect(Array.from(storage.values()).join('')).not.toContain('synthetic-password');
    });

    it('does not send a password mutation after its CSRF preparation was superseded', async () => {
        const result = auth.loginWithPassword('fixture', 'synthetic-password', '/decks');
        const rejection = expectAsync(result).toBeRejected();
        const csrf = http.expectOne(`${issuer}/api/accounts/csrf`);
        auth.expireSession();
        csrf.flush({ headerName: 'X-CSRF-TOKEN', token: 'csrf-token' });
        await rejection;
        http.expectNone(`${issuer}/api/accounts/login`);
    });

    it('logout transport failure removes local access without claiming remote revocation', async () => {
        stored();
        const restored = auth.restore();
        http.expectOne(`${issuer}/api/accounts/me`).flush(profile);
        await restored;
        const result = auth.logout();
        const rejection = expectAsync(result).toBeRejected();
        expect(auth.accessToken()).toBeNull();
        expect(storage.has(AUTH_STORAGE_KEY)).toBeFalse();
        http.expectNone(`${issuer}/api/accounts/csrf`);
        const logout = http.expectOne(`${issuer}/api/accounts/logout`);
        expect(logout.request.withCredentials).toBeFalse();
        expect(logout.request.headers.get('Authorization')).toBe('Bearer opaque-token');
        logout.flush({}, { status: 503, statusText: 'Unavailable' });
        await rejection;
        expect(auth.logoutUnconfirmed()).toBeTrue();
        expect(router.navigateByUrl).not.toHaveBeenCalled();
    });

    it('stale401 for a previous token cannot clear the current verified session', async () => {
        stored('new-token');
        const restored = auth.restore();
        http.expectOne(`${issuer}/api/accounts/me`).flush(profile);
        await restored;
        auth.expireSession('old-token');
        expect(auth.accessToken()).toBe('new-token');
    });

    it('rejects overlapping cookie logins even after the first password POST was dispatched', async () => {
        const first = auth.loginWithPassword('first', 'synthetic-password', '/decks');
        http.expectOne(`${issuer}/api/accounts/csrf`).flush({ headerName: 'X-CSRF-TOKEN', token: 'csrf-token' });
        await settle();
        const login = http.expectOne(`${issuer}/api/accounts/login`);
        await expectAsync(auth.loginWithPassword('second', 'different-password', '/decks')).toBeRejected();
        await expectAsync(auth.registerWithPassword('x@example.test', 'third', 'different-password', '/decks')).toBeRejected();
        await expectAsync(auth.beginLogin('/decks')).toBeRejected();
        http.expectNone(`${issuer}/api/accounts/csrf`);
        login.flush(profile);
        await first;
        expect(navigate).toHaveBeenCalledTimes(1);
    });

    it('delayed logout completion cannot navigate over a newer authorization intent', async () => {
        stored();
        const restored = auth.restore();
        http.expectOne(`${issuer}/api/accounts/me`).flush(profile);
        await restored;
        const result = auth.logout();
        const logout = http.expectOne(`${issuer}/api/accounts/logout`);
        await auth.beginLogin('/decks');
        logout.flush(null);
        await result;
        expect(router.navigateByUrl).not.toHaveBeenCalled();
        expect(storage.has(PKCE_STORAGE_KEY)).toBeTrue();
    });

    it('password mutation is explicitly bound to the verified bearer without ambient cookies', async () => {
        stored();
        const restored = auth.restore();
        http.expectOne(`${issuer}/api/accounts/me`).flush(profile);
        await restored;
        const result = auth.setPassword('synthetic-password', 'new-synthetic-password');
        const password = http.expectOne(`${issuer}/api/accounts/me/password`);
        expect(password.request.withCredentials).toBeFalse();
        expect(password.request.headers.get('Authorization')).toBe('Bearer opaque-token');
        http.expectNone(`${issuer}/api/accounts/csrf`);
        password.flush(null);
        await result;
        expect(auth.accessToken()).toBeNull();
    });

    it('explicit logout retry keeps target binding in memory; expired retry cannot revoke another cookie account', async () => {
        stored();
        const restored = auth.restore();
        http.expectOne(`${issuer}/api/accounts/me`).flush(profile);
        await restored;
        const first = auth.logout();
        const rejection = expectAsync(first).toBeRejected();
        http.expectOne(`${issuer}/api/accounts/logout`).flush({}, { status: 503, statusText: 'Unavailable' });
        await rejection;
        const second = auth.logout();
        const secondRejection = expectAsync(second).toBeRejected();
        const retry = http.expectOne(`${issuer}/api/accounts/logout`);
        expect(retry.request.headers.get('Authorization')).toBe('Bearer opaque-token');
        retry.flush({}, { status: 503, statusText: 'Unavailable' });
        await secondRejection;
        browser.now = () => now + 300001;
        await expectAsync(auth.logout()).toBeRejected();
        http.expectNone(`${issuer}/api/accounts/logout`);
        expect(auth.logoutUnconfirmed()).toBeTrue();
        expect(storage.has(AUTH_STORAGE_KEY)).toBeFalse();
    });
});
