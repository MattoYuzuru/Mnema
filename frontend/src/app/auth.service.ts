import { Injectable, inject, signal } from '@angular/core';
import { HttpBackend, HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Router } from '@angular/router';
import { toObservable } from '@angular/core/rxjs-interop';
import { firstValueFrom, timeout } from 'rxjs';
import { AUTH_BROWSER, BROWSER_IDENTITY_CONFIG, validateIdentityConfig } from './auth-browser';
import { AUTH_SCOPES, AUTH_STORAGE_KEY, PKCE_STORAGE_KEY, AuthFailure, IdentityProfile, StoredAccess,
    objectValue, boundedText, parseProfile, parseStoredAccess, parseToken, parseTransaction, safeReturnUrl } from './auth-protocol';

export type AuthStatus = 'anonymous' | 'pending' | 'authenticated' | 'error';
export interface AuthUser extends IdentityProfile { name?: string }
export interface PasswordStatus { hasPassword: boolean }

@Injectable({ providedIn: 'root' })
export class AuthService {
    // Protocol traffic supplies credentials explicitly; it must not recurse through the bearer interceptor.
    private readonly http = new HttpClient(inject(HttpBackend));
    private readonly router = inject(Router);
    private readonly browser = inject(AUTH_BROWSER);
    private readonly config = inject(BROWSER_IDENTITY_CONFIG);
    private readonly state = signal<AuthStatus>('anonymous');
    private readonly profile = signal<AuthUser | null>(null);
    readonly status = this.state.asReadonly();
    readonly user = this.profile.asReadonly();
    readonly status$ = toObservable(this.status);
    readonly user$ = toObservable(this.user);
    readonly logoutUnconfirmed = signal(false);
    private access: StoredAccess | null = null;
    // Only an explicit failed logout may reuse this in-memory credential; never exposed as active access or persisted.
    private pendingLogout: StoredAccess | null = null;
    private cookieFlowPending = false;
    private epoch = 0;
    private restoration: Promise<void> | null = null;
    private expiryTimer: ReturnType<typeof setTimeout> | null = null;

    accessToken(): string | null {
        if (this.access && this.access.expiresAt <= this.browser.now()) this.expireSession(this.access.token);
        return this.access?.token ?? null;
    }

    /** Single-flight restoration. Guard awaits it, while the public shell may render immediately. */
    restore(): Promise<void> {
        this.restoration ??= this.restoreAccess();
        return this.restoration;
    }

    private async restoreAccess(): Promise<void> {
        if (this.browser.pathname === '/auth/callback') return;
        const epoch = this.epoch;
        try {
            const raw = this.browser.storage.getItem(AUTH_STORAGE_KEY);
            if (!raw) return;
            validateIdentityConfig(this.config, this.browser.origin);
            const access = parseStoredAccess(raw, this.browser.now(), this.config.authServerUrl, this.config.clientId);
            this.state.set('pending');
            const profile = await this.loadProfile(access.token);
            if (epoch === this.epoch) this.accept(access, profile);
        } catch (error) {
            if (epoch !== this.epoch) return;
            this.clear();
            if (error instanceof HttpErrorResponse && error.status !== 401) this.state.set('error');
        }
    }

    async beginLogin(returnTo: string = this.router.url): Promise<void> {
        if (this.cookieFlowPending) throw new AuthFailure('protocol');
        await this.startAuthorization(returnTo);
    }

    private async startAuthorization(returnTo: string): Promise<void> {
        this.pendingLogout = null;
        this.clear();
        const epoch = this.epoch;
        this.state.set('pending');
        try {
            validateIdentityConfig(this.config, this.browser.origin);
            const verifier = this.browser.random();
            const state = this.browser.random();
            const challenge = await this.browser.challenge(verifier);
            if (epoch !== this.epoch) return;
            const transaction = { state, verifier, returnUrl: safeReturnUrl(returnTo), createdAt: this.browser.now(),
                issuer: this.config.authServerUrl, clientId: this.config.clientId, redirectUri: this.config.identityRedirectUri };
            this.persist(PKCE_STORAGE_KEY, transaction);
            const query = new URLSearchParams({ response_type: 'code', client_id: this.config.clientId,
                redirect_uri: this.config.identityRedirectUri, scope: AUTH_SCOPES, state,
                code_challenge: challenge, code_challenge_method: 'S256' });
            this.browser.navigate(`${this.config.authServerUrl}/oauth2/authorize?${query}`);
        } catch (error) {
            if (epoch === this.epoch) { this.clear(); this.state.set('error'); }
            throw error;
        }
    }

    async completeCallback(): Promise<void> {
        this.clear(false);
        const epoch = this.epoch;
        this.state.set('pending');
        try {
            if (this.browser.pathname !== '/auth/callback') throw new AuthFailure('protocol');
            const query = new URLSearchParams(this.browser.search);
            this.browser.clearQuery();
            validateIdentityConfig(this.config, this.browser.origin);
            const raw = this.browser.storage.getItem(PKCE_STORAGE_KEY);
            // Consume before any asynchronous exchange, including malformed/error callbacks.
            this.browser.storage.removeItem(PKCE_STORAGE_KEY);
            if (!raw) throw new AuthFailure('protocol');
            const transaction = parseTransaction(raw, this.browser.now(), this.config.authServerUrl, this.config.clientId, this.config.identityRedirectUri);
            const code = query.get('code');
            if (query.has('error') || query.getAll('code').length !== 1 || query.getAll('state').length !== 1 ||
                query.get('state') !== transaction.state || !boundedText(code, 2048) || !code ||
                (query.has('iss') && (query.getAll('iss').length !== 1 || query.get('iss') !== this.config.authServerUrl))) throw new AuthFailure('protocol');
            const body = new URLSearchParams({ grant_type: 'authorization_code', code, client_id: this.config.clientId,
                redirect_uri: this.config.identityRedirectUri, code_verifier: transaction.verifier }).toString();
            const value = await firstValueFrom(this.http.post<unknown>(`${this.config.authServerUrl}/oauth2/token`, body,
                { headers: { 'Content-Type': 'application/x-www-form-urlencoded' } }).pipe(timeout(8000)));
            if (epoch !== this.epoch) return;
            const access = parseToken(value, this.browser.now(), this.config.authServerUrl, this.config.clientId);
            const profile = await this.loadProfile(access.token);
            if (epoch !== this.epoch) return;
            this.persist(AUTH_STORAGE_KEY, access);
            this.accept(access, profile);
            await this.router.navigateByUrl(transaction.returnUrl, { replaceUrl: true });
        } catch (error) {
            if (epoch === this.epoch) { this.clear(); this.state.set('error'); }
            throw error;
        }
    }

    async loginWithPassword(login: string, password: string, returnTo: string): Promise<void> {
        if (this.cookieFlowPending) throw new AuthFailure('protocol');
        this.cookieFlowPending = true;
        try { await this.passwordLogin(login, password, returnTo); }
        finally { this.cookieFlowPending = false; }
    }

    private async passwordLogin(login: string, password: string, returnTo: string): Promise<void> {
        this.pendingLogout = null;
        this.clear();
        const epoch = this.epoch;
        this.state.set('pending');
        try {
            validateIdentityConfig(this.config, this.browser.origin);
            parseProfile(await this.accountMutation('/login', { login, password }));
            if (epoch === this.epoch) await this.startAuthorization(returnTo);
        } catch (error) {
            if (epoch === this.epoch) this.state.set('error');
            throw error;
        }
    }

    async registerWithPassword(email: string, username: string, password: string, returnTo: string): Promise<void> {
        if (this.cookieFlowPending) throw new AuthFailure('protocol');
        this.cookieFlowPending = true;
        this.pendingLogout = null;
        this.clear();
        const epoch = this.epoch;
        this.state.set('pending');
        try {
            validateIdentityConfig(this.config, this.browser.origin);
            parseProfile(await this.accountMutation('/register', { email, loginName: username, profileUsername: username, password }));
            if (epoch === this.epoch) await this.passwordLogin(username, password, returnTo);
        } catch (error) {
            if (epoch === this.epoch) this.state.set('error');
            throw error;
        } finally {
            this.cookieFlowPending = false;
        }
    }

    async logout(): Promise<void> {
        const access = this.access ?? this.pendingLogout;
        this.clear();
        const epoch = this.epoch;
        this.pendingLogout = access;
        this.logoutUnconfirmed.set(true);
        if (this.cookieFlowPending) throw new AuthFailure('protocol');
        // Bind revocation to this tab's verified account, never a different tab's shared Identity cookie.
        validateIdentityConfig(this.config, this.browser.origin);
        if (!access || access.expiresAt <= this.browser.now()) { this.pendingLogout = null; throw new AuthFailure('protocol'); }
        await this.bearerMutation('/logout', {}, access.token);
        if (epoch !== this.epoch) return;
        this.pendingLogout = null;
        this.logoutUnconfirmed.set(false);
        await this.router.navigateByUrl('/login', { replaceUrl: true });
    }

    async getPasswordStatus(): Promise<PasswordStatus> {
        const token = this.accessToken();
        if (!token) throw new AuthFailure('protocol');
        return { hasPassword: (await this.loadProfile(token)).hasPassword };
    }

    async setPassword(currentPassword: string | null, newPassword: string): Promise<PasswordStatus> {
        const token = this.accessToken();
        if (!token) throw new AuthFailure('protocol');
        await this.bearerMutation('/me/password', { currentPassword, newPassword }, token);
        this.expireSession(token);
        return { hasPassword: true };
    }

    expireSession(expectedToken?: string): void {
        // A delayed401 for an old request must not discard a later successful login.
        if (expectedToken && expectedToken !== this.access?.token) return;
        const wasActive = this.state() === 'authenticated';
        this.clear();
        if (wasActive) void this.router.navigate(['/login'], { queryParams: { returnUrl: safeReturnUrl(this.router.url) } });
    }

    private async loadProfile(token: string): Promise<IdentityProfile> {
        return parseProfile(await firstValueFrom(this.http.get<unknown>(`${this.config.authServerUrl}/api/accounts/me`,
            { headers: { Authorization: `Bearer ${token}` } }).pipe(timeout(8000))));
    }

    private bearerMutation(path: '/logout' | '/me/password', body: unknown, token: string): Promise<unknown> {
        validateIdentityConfig(this.config, this.browser.origin);
        // Spring's existing Resource Server exempts explicit bearer requests from CSRF; cookie mutations do not.
        return firstValueFrom(this.http.post<unknown>(`${this.config.authServerUrl}/api/accounts${path}`, body,
            { withCredentials: false, headers: { Authorization: `Bearer ${token}` } }).pipe(timeout(8000)));
    }

    private async accountMutation(path: string, body: unknown): Promise<unknown> {
        const epoch = this.epoch;
        const csrf = objectValue(await firstValueFrom(this.http.get<unknown>(`${this.config.authServerUrl}/api/accounts/csrf`,
            { withCredentials: true }).pipe(timeout(8000))));
        if (csrf['headerName'] !== 'X-CSRF-TOKEN' || !boundedText(csrf['token'], 256) || !csrf['token']) throw new AuthFailure('protocol');
        if (epoch !== this.epoch) throw new AuthFailure('protocol');
        return firstValueFrom(this.http.post<unknown>(`${this.config.authServerUrl}/api/accounts${path}`, body,
            { withCredentials: true, headers: { 'X-CSRF-TOKEN': csrf['token'] } }).pipe(timeout(8000)));
    }

    private accept(access: StoredAccess, profile: IdentityProfile): void {
        if (access.expiresAt <= this.browser.now()) throw new AuthFailure('protocol');
        this.access = access;
        this.profile.set({ ...profile, name: profile.displayName ?? profile.profileUsername ?? undefined });
        this.state.set('authenticated');
        if (this.expiryTimer !== null) clearTimeout(this.expiryTimer);
        this.expiryTimer = setTimeout(() => this.expireSession(access.token), access.expiresAt - this.browser.now());
    }

    private persist(key: string, value: unknown): void {
        try { this.browser.storage.setItem(key, JSON.stringify(value)); }
        catch { throw new AuthFailure('storage'); }
    }

    private clear(clearTransaction = true): void {
        this.epoch++;
        this.access = null;
        this.profile.set(null);
        this.state.set('anonymous');
        if (this.expiryTimer !== null) clearTimeout(this.expiryTimer);
        this.expiryTimer = null;
        try {
            this.browser.storage.removeItem(AUTH_STORAGE_KEY);
            if (clearTransaction) this.browser.storage.removeItem(PKCE_STORAGE_KEY);
            // Discard replaced legacy credentials; never restore or translate them.
            for (const key of ['mnema_tokens', 'pkce_verifier', 'oauth_state', 'oauth_return_to']) this.browser.storage.removeItem(key);
        } catch { /* In-memory state remains anonymous when browser storage is unavailable. */ }
    }
}
