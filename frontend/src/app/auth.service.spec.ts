import type { Mock } from "vitest";
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { AuthService } from './auth.service';
import { AUTH_BROWSER, AuthBrowser, BROWSER_IDENTITY_CONFIG } from './auth-browser';
import { AUTH_SCOPES, AUTH_STORAGE_KEY, PKCE_STORAGE_KEY } from './auth-protocol';
import { spyObj, type SpyObj, lastCall } from '../testing/mocks';

describe('real Identity browser protocol orchestration', () => {
    const issuer = 'https://identity.example.test';
    const origin = 'https://app.example.test';
    const redirectUri = `${origin}/auth/callback`;
    const now = 1800000000000;
    const profile = { accountId: '11111111-1111-4111-8111-111111111111', email: 'fixture@example.test',
        emailVerified: false, profileUsername: 'fixture', displayName: null, hasPassword: true, status: 'ACTIVE' };
    let auth: AuthService;
    let http: HttpTestingController;
    let router: SpyObj<Router>;
    let storage: Map<string, string>;
    let browser: AuthBrowser;
    let navigate: Mock;
    const settle = async () => { for (let i = 0; i < 8; i++)
        await Promise.resolve(); };

    beforeEach(() => {
        storage = new Map();
        navigate = vi.fn().mockName('navigate');
        browser = { origin, pathname: '/decks', search: '', now: () => now,
            random: () => 'r'.repeat(43), challenge: async () => 'c'.repeat(43), navigate,
            clearQuery: vi.fn().mockName('clearQuery'), storage: {
                get length() { return storage.size; }, clear: () => storage.clear(),
                key: index => Array.from(storage.keys())[index] ?? null,
                getItem: key => storage.get(key) ?? null, setItem: (key, value) => { storage.set(key, value); },
                removeItem: key => { storage.delete(key); }
            } };
        router = spyObj<Router>({
            navigate: vi.fn().mockName("Router.navigate"),
            navigateByUrl: vi.fn().mockName("Router.navigateByUrl"),
            url: '/decks'
        });
        router.navigate.mockResolvedValue(true);
        router.navigateByUrl.mockResolvedValue(true);
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
        expect(request.request.withCredentials).toBe(false);
        expect(request.request.headers.has('Authorization')).toBe(false);
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
        expect(me.request.withCredentials).toBe(false);
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
        expect(storage.has(AUTH_STORAGE_KEY)).toBe(false);
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
        const url = new URL(lastCall(navigate)[0]);
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
        expect(storage.has(PKCE_STORAGE_KEY)).toBe(false);
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
            await expect(auth.completeCallback()).rejects.toThrow();
            expect(storage.has(PKCE_STORAGE_KEY)).toBe(false);
            expect(auth.accessToken()).toBeNull();
        }
        await expect(auth.completeCallback()).rejects.toThrow();
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
        expect(csrf.request.withCredentials).toBe(true);
        csrf.flush({ headerName: 'X-CSRF-TOKEN', token: 'csrf-token' });
        await settle();
        const login = http.expectOne(`${issuer}/api/accounts/login`);
        expect(login.request.body).toEqual({ login: 'fixture', password: 'synthetic-password' });
        expect(login.request.withCredentials).toBe(true);
        expect(login.request.headers.get('X-CSRF-TOKEN')).toBe('csrf-token');
        login.flush(profile);
        await result;
        expect(navigate).toHaveBeenCalled();
        expect(auth.accessToken()).toBeNull();
        expect(Array.from(storage.values()).join('')).not.toContain('synthetic-password');
    });

    it('does not send a password mutation after its CSRF preparation was superseded', async () => {
        const result = auth.loginWithPassword('fixture', 'synthetic-password', '/decks');
        const rejection = expect(result).rejects.toThrow();
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
        const rejection = expect(result).rejects.toThrow();
        expect(auth.accessToken()).toBeNull();
        expect(storage.has(AUTH_STORAGE_KEY)).toBe(false);
        http.expectNone(`${issuer}/api/accounts/csrf`);
        const logout = http.expectOne(`${issuer}/api/accounts/logout`);
        expect(logout.request.withCredentials).toBe(false);
        expect(logout.request.headers.get('Authorization')).toBe('Bearer opaque-token');
        logout.flush({}, { status: 503, statusText: 'Unavailable' });
        await rejection;
        expect(auth.logoutUnconfirmed()).toBe(true);
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
        await expect(auth.loginWithPassword('second', 'different-password', '/decks')).rejects.toThrow();
        await expect(auth.registerWithPassword('x@example.test', 'third', 'different-password', '/decks')).rejects.toThrow();
        await expect(auth.beginLogin('/decks')).rejects.toThrow();
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
        expect(storage.has(PKCE_STORAGE_KEY)).toBe(true);
    });

    it('password mutation is explicitly bound to the verified bearer without ambient cookies', async () => {
        stored();
        const restored = auth.restore();
        http.expectOne(`${issuer}/api/accounts/me`).flush(profile);
        await restored;
        const result = auth.setPassword('synthetic-password', 'new-synthetic-password');
        const password = http.expectOne(`${issuer}/api/accounts/me/password`);
        expect(password.request.withCredentials).toBe(false);
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
        const rejection = expect(first).rejects.toThrow();
        http.expectOne(`${issuer}/api/accounts/logout`).flush({}, { status: 503, statusText: 'Unavailable' });
        await rejection;
        const second = auth.logout();
        const secondRejection = expect(second).rejects.toThrow();
        const retry = http.expectOne(`${issuer}/api/accounts/logout`);
        expect(retry.request.headers.get('Authorization')).toBe('Bearer opaque-token');
        retry.flush({}, { status: 503, statusText: 'Unavailable' });
        await secondRejection;
        browser.now = () => now + 300001;
        await expect(auth.logout()).rejects.toThrow();
        http.expectNone(`${issuer}/api/accounts/logout`);
        expect(auth.logoutUnconfirmed()).toBe(true);
        expect(storage.has(AUTH_STORAGE_KEY)).toBe(false);
    });
    it('loads only validated public provider names without bearer or cookie credentials', async () => {
        const result = auth.availableProviders();
        const request = http.expectOne(`${issuer}/api/accounts/providers`);
        expect(request.request.withCredentials).toBe(false);
        expect(request.request.headers.has('Authorization')).toBe(false);
        request.flush({ providers: ['google', 'yandex', 'github'] });
        expect(await result).toEqual(['google', 'yandex', 'github']);
        const invalid = auth.availableProviders();
        const rejected = expect(invalid).rejects.toThrow();
        http.expectOne(`${issuer}/api/accounts/providers`).flush({ providers: ['https://evil.test'] });
        await rejected;
    });

    it('honors an explicit runtime federation disable without provider traffic', async () => {
        TestBed.inject(BROWSER_IDENTITY_CONFIG).features = { federatedAuthEnabled: false };
        expect(await auth.availableProviders()).toEqual([]);
        await expect(auth.beginFederatedLogin('github', '/decks')).rejects.toThrow();
        expect(navigate).not.toHaveBeenCalled();
    });

    it('federates with a one-use browser correlation then performs the existing Mnema S256 exchange', async () => {
        await auth.beginFederatedLogin('github', '/decks?tab=mine');
        const upstream = new URL(lastCall(navigate)[0]);
        expect(upstream.pathname).toBe('/oauth2/authorization/github');
        const pending = JSON.parse(storage.get(PKCE_STORAGE_KEY)!);
        expect(upstream.searchParams.get('mnema_state')).toBe(pending.state);
        expect(pending.provider).toBe('github');
        expect(auth.accessToken()).toBeNull();
        Object.assign(browser, { pathname: '/auth/callback', search: `?federation_state=${pending.state}` });
        await auth.completeCallback();
        expect(auth.status()).toBe('pending');
        const authorization = new URL(lastCall(navigate)[0]);
        expect(authorization.pathname).toBe('/oauth2/authorize');
        expect(authorization.searchParams.get('code_challenge_method')).toBe('S256');
        const transaction = JSON.parse(storage.get(PKCE_STORAGE_KEY)!);
        expect(transaction.provider).toBeUndefined();
        expect(transaction.returnUrl).toBe('/decks?tab=mine');
        Object.assign(browser, { pathname: '/auth/callback', search: `?code=one-use-code&state=${transaction.state}` });
        const result = auth.completeCallback();
        const request = http.expectOne(`${issuer}/oauth2/token`);
        expect(new URLSearchParams(request.request.body).get('code_verifier')).toBe(transaction.verifier);
        request.flush({ access_token: 'opaque-token', expires_in: 300, token_type: 'Bearer', scope: AUTH_SCOPES });
        await settle();
        http.expectOne(`${issuer}/api/accounts/me`).flush({ ...profile, hasPassword: false });
        await result;
        expect(auth.status()).toBe('authenticated');
        expect(router.navigateByUrl).toHaveBeenCalledWith('/decks?tab=mine', { replaceUrl: true });
    });

    it('rejects wrong, mixed, cancelled and replayed federation callbacks before creating API access', async () => {
        for (const query of ['?federation_state=wrong', '?federation_state=' + 'r'.repeat(43) + '&code=injected',
            '?code=injected&state=' + 'r'.repeat(43), '?error=federation_failed',
            '?federation_state=' + 'r'.repeat(43) + '&federation_state=duplicate']) {
            await auth.beginFederatedLogin('google', '/decks');
            Object.assign(browser, { pathname: '/auth/callback', search: query });
            await expect(auth.completeCallback()).rejects.toThrow();
            expect(storage.has(PKCE_STORAGE_KEY)).toBe(false);
            expect(auth.accessToken()).toBeNull();
        }
        callback('?federation_state=' + 's'.repeat(43));
        await expect(auth.completeCallback()).rejects.toThrow();
        await expect(auth.completeCallback()).rejects.toThrow();
        http.expectNone(`${issuer}/oauth2/token`);
    });

});
