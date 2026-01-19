import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Router } from '@angular/router';
import { BehaviorSubject, Observable, firstValueFrom } from 'rxjs';
import { appConfig } from './app.config';

export interface AuthUser {
    email: string;
    name?: string;
    picture?: string;
    emailVerified?: boolean;
}

export type AuthStatus = 'anonymous' | 'pending' | 'authenticated' | 'error';

interface TokenResponse {
    access_token: string;
    refresh_token?: string;
    id_token?: string;
    expires_in: number;
    expires_at?: number;
    token_type: string;
    scope?: string;
    [key: string]: unknown;
}

export interface TurnstileConfig {
    enabled: boolean;
    siteKey?: string | null;
}

export interface PasswordStatus {
    hasPassword: boolean;
}

@Injectable({ providedIn: 'root' })
export class AuthService {
    private readonly storageKey = 'mnema_tokens';

    private _statusSubject = new BehaviorSubject<AuthStatus>('anonymous');
    private _userSubject = new BehaviorSubject<AuthUser | null>(null);
    private _accessToken: string | null = null;
    private _expiresAt: number | null = null;
    private tokenExpiryTimer: number | null = null;
    private turnstileConfig: TurnstileConfig | null = null;

    status$: Observable<AuthStatus> = this._statusSubject.asObservable();
    user$: Observable<AuthUser | null> = this._userSubject.asObservable();

    constructor(
        private http: HttpClient,
        private router: Router
    ) {}

    status(): AuthStatus {
        return this._statusSubject.value;
    }

    user(): AuthUser | null {
        return this._userSubject.value;
    }

    accessToken(): string | null {
        if (this._accessToken && this.isExpired()) {
            this.expireSession();
            return null;
        }
        return this._accessToken;
    }

    initFromUrlAndStorage(): void {
        const params = new URLSearchParams(window.location.search);
        const code = params.get('code');
        const state = params.get('state');

        if (code) {
            void this.handleAuthCallback(code, state);
            return;
        }

        const raw = window.sessionStorage.getItem(this.storageKey);
        if (raw) {
            try {
                const saved = JSON.parse(raw) as TokenResponse;
                this.applyTokens(saved);
            } catch {
                window.sessionStorage.removeItem(this.storageKey);
            }
        }
    }

    async beginLogin(returnTo: string = window.location.pathname, provider?: string): Promise<void> {
        this._statusSubject.next('pending');

        const codeVerifier = this.randomString(64);
        const codeChallenge = await this.pkceChallengeFromVerifier(codeVerifier);
        const state = crypto.randomUUID();

        window.sessionStorage.setItem('pkce_verifier', codeVerifier);
        window.sessionStorage.setItem('oauth_state', state);
        window.sessionStorage.setItem('oauth_return_to', returnTo);

        const redirectUri = `${window.location.origin}/`;
        const params = new URLSearchParams({
            response_type: 'code',
            client_id: appConfig.clientId,
            redirect_uri: redirectUri,
            scope: 'openid profile email user.read user.write',
            state,
            code_challenge: codeChallenge,
            code_challenge_method: 'S256'
        });

        if (provider) {
            params.set('provider', provider);
        }

        window.location.href = `${appConfig.authServerUrl}/oauth2/authorize?${params.toString()}`;
    }

    logout(): void {
        this.clearSession();

        const redirectUrl = `${window.location.origin}/`;
        window.location.href = `${appConfig.authServerUrl}/logout?redirect=${encodeURIComponent(redirectUrl)}`;
    }

    async loginWithPassword(
        login: string,
        password: string,
        returnTo: string,
        turnstileToken?: string | null
    ): Promise<void> {
        this._statusSubject.next('pending');
        try {
            const tokenResponse = await firstValueFrom(
                this.http.post<TokenResponse>(`${appConfig.authServerUrl}/auth/login`, {
                    login,
                    password,
                    turnstileToken
                })
            );
            const storedTokens = this.withExpiresAt(tokenResponse);
            window.sessionStorage.setItem(this.storageKey, JSON.stringify(storedTokens));
            this.applyTokens(storedTokens);
            await this.router.navigateByUrl(returnTo);
        } catch (e) {
            console.error('Local login failed', e);
            this._statusSubject.next('error');
            throw e;
        }
    }

    async registerWithPassword(
        email: string,
        username: string,
        password: string,
        returnTo: string,
        turnstileToken?: string | null
    ): Promise<void> {
        this._statusSubject.next('pending');
        try {
            const tokenResponse = await firstValueFrom(
                this.http.post<TokenResponse>(`${appConfig.authServerUrl}/auth/register`, {
                    email,
                    username,
                    password,
                    turnstileToken
                })
            );
            const storedTokens = this.withExpiresAt(tokenResponse);
            window.sessionStorage.setItem(this.storageKey, JSON.stringify(storedTokens));
            this.applyTokens(storedTokens);
            await this.router.navigateByUrl(returnTo);
        } catch (e) {
            console.error('Local registration failed', e);
            this._statusSubject.next('error');
            throw e;
        }
    }

    async getTurnstileConfig(): Promise<TurnstileConfig> {
        if (this.turnstileConfig) {
            return this.turnstileConfig;
        }
        try {
            const config = await firstValueFrom(
                this.http.get<TurnstileConfig>(`${appConfig.authServerUrl}/auth/turnstile/config`)
            );
            this.turnstileConfig = config;
            return config;
        } catch {
            this.turnstileConfig = { enabled: false };
            return this.turnstileConfig;
        }
    }

    async getPasswordStatus(): Promise<PasswordStatus> {
        return firstValueFrom(
            this.http.get<PasswordStatus>(`${appConfig.authServerUrl}/auth/password/status`)
        );
    }

    async setPassword(currentPassword: string | null, newPassword: string): Promise<PasswordStatus> {
        return firstValueFrom(
            this.http.post<PasswordStatus>(`${appConfig.authServerUrl}/auth/password`, {
                currentPassword,
                newPassword
            })
        );
    }

    private async handleAuthCallback(code: string, returnedState: string | null): Promise<void> {
        const expectedState = window.sessionStorage.getItem('oauth_state');
        const codeVerifier = window.sessionStorage.getItem('pkce_verifier');
        const redirectUri = `${window.location.origin}/`;

        window.history.replaceState(null, '', window.location.pathname);

        if (!codeVerifier || !expectedState || returnedState !== expectedState) {
            this._statusSubject.next('error');
            return;
        }

        const body = new URLSearchParams({
            grant_type: 'authorization_code',
            code,
            redirect_uri: redirectUri,
            client_id: appConfig.clientId,
            code_verifier: codeVerifier
        });

        try {
            const tokenResponse = await firstValueFrom(
                this.http.post<TokenResponse>(
                    `${appConfig.authServerUrl}/oauth2/token`,
                    body,
                    { headers: { 'Content-Type': 'application/x-www-form-urlencoded' } }
                )
            );

            const storedTokens = this.withExpiresAt(tokenResponse);
            window.sessionStorage.setItem(this.storageKey, JSON.stringify(storedTokens));
            this.applyTokens(storedTokens);

            const returnTo = window.sessionStorage.getItem('oauth_return_to') || '/';
            window.sessionStorage.removeItem('oauth_return_to');
            await this.router.navigateByUrl(returnTo);
        } catch (e) {
            console.error('Token exchange failed', e);
            this._statusSubject.next('error');
        } finally {
            window.sessionStorage.removeItem('pkce_verifier');
            window.sessionStorage.removeItem('oauth_state');
        }
    }

    private applyTokens(tokens: TokenResponse): void {
        const expiresAt = this.resolveExpiresAt(tokens);
        if (expiresAt && Date.now() >= expiresAt) {
            this.expireSession();
            return;
        }

        this.clearExpiryTimer();
        this._accessToken = tokens.access_token;
        this._expiresAt = expiresAt;
        this._statusSubject.next('authenticated');
        if (expiresAt) {
            this.scheduleExpiry(expiresAt);
        }

        const tokenForProfile = tokens.id_token ?? tokens.access_token;
        if (tokenForProfile) {
            const payload = this.decodeJwt(tokenForProfile);
            const user: AuthUser = {
                email: (payload['email'] as string) ?? '',
                name: (payload['name'] || payload['given_name']) as string | undefined,
                picture: payload['picture'] as string | undefined,
                emailVerified: payload['email_verified'] as boolean | undefined
            };
            this._userSubject.next(user);
        }
    }

    expireSession(): void {
        const wasAnonymous = this._statusSubject.value === 'anonymous';
        this.clearSession();
        if (wasAnonymous) {
            return;
        }
        const returnUrl = this.router.url || '/';
        if (!returnUrl.startsWith('/login')) {
            void this.router.navigate(['/login'], { queryParams: { returnUrl } });
        }
    }

    private clearSession(): void {
        this._statusSubject.next('anonymous');
        this._userSubject.next(null);
        this._accessToken = null;
        this._expiresAt = null;
        this.clearExpiryTimer();
        window.sessionStorage.removeItem(this.storageKey);
    }

    private scheduleExpiry(expiresAt: number): void {
        this.clearExpiryTimer();
        const delay = Math.max(expiresAt - Date.now(), 0);
        this.tokenExpiryTimer = window.setTimeout(() => this.expireSession(), delay);
    }

    private clearExpiryTimer(): void {
        if (this.tokenExpiryTimer !== null) {
            window.clearTimeout(this.tokenExpiryTimer);
            this.tokenExpiryTimer = null;
        }
    }

    private isExpired(): boolean {
        return this._expiresAt !== null && Date.now() >= this._expiresAt;
    }

    private withExpiresAt(tokens: TokenResponse): TokenResponse {
        if (typeof tokens.expires_at === 'number') {
            return tokens;
        }
        const expiresAt = this.resolveExpiresAt(tokens) ?? (Date.now() + tokens.expires_in * 1000);
        return { ...tokens, expires_at: expiresAt };
    }

    private resolveExpiresAt(tokens: TokenResponse): number | null {
        if (typeof tokens.expires_at === 'number') {
            return tokens.expires_at;
        }
        const accessExp = this.getJwtExpiry(tokens.access_token);
        if (accessExp) {
            return accessExp;
        }
        if (tokens.id_token) {
            const idExp = this.getJwtExpiry(tokens.id_token);
            if (idExp) {
                return idExp;
            }
        }
        return null;
    }

    private getJwtExpiry(token: string): number | null {
        try {
            const payload = this.decodeJwt(token);
            const exp = payload['exp'];
            if (typeof exp === 'number') {
                return exp * 1000;
            }
        } catch {
            return null;
        }
        return null;
    }

    private decodeJwt(token: string): Record<string, unknown> {
        const [, payload] = token.split('.');
        const decoded = atob(payload.replace(/-/g, '+').replace(/_/g, '/'));
        return JSON.parse(decoded);
    }

    private randomString(length: number): string {
        const charset = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
        const result: string[] = [];
        const randomValues = crypto.getRandomValues(new Uint8Array(length));
        for (let i = 0; i < length; i++) {
            result.push(charset[randomValues[i] % charset.length]);
        }
        return result.join('');
    }

    private async pkceChallengeFromVerifier(verifier: string): Promise<string> {
        const data = new TextEncoder().encode(verifier);
        const hashBuffer = await crypto.subtle.digest('SHA-256', data);
        const hashArray = Array.from(new Uint8Array(hashBuffer));
        const base64 = btoa(String.fromCharCode(...hashArray));
        return base64.replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
    }
}
