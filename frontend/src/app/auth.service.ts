import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Router } from '@angular/router';
import { BehaviorSubject, Observable, firstValueFrom } from 'rxjs';
import { appConfig } from './app.config';

export interface AuthUser {
    email: string;
    name?: string;
    picture?: string;
}

export type AuthStatus = 'anonymous' | 'pending' | 'authenticated' | 'error';

interface TokenResponse {
    access_token: string;
    refresh_token?: string;
    id_token?: string;
    expires_in: number;
    token_type: string;
    scope?: string;
    [key: string]: unknown;
}

@Injectable({ providedIn: 'root' })
export class AuthService {
    private readonly storageKey = 'mnema_tokens';

    private _statusSubject = new BehaviorSubject<AuthStatus>('anonymous');
    private _userSubject = new BehaviorSubject<AuthUser | null>(null);
    private _accessToken: string | null = null;

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

    async beginLogin(returnTo: string = window.location.pathname): Promise<void> {
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

        window.location.href = `${appConfig.authServerUrl}/oauth2/authorize?${params.toString()}`;
    }

    logout(): void {
        this._statusSubject.next('anonymous');
        this._userSubject.next(null);
        this._accessToken = null;
        window.sessionStorage.removeItem(this.storageKey);

        const redirectUrl = `${window.location.origin}/`;
        window.location.href = `${appConfig.authServerUrl}/logout?redirect=${encodeURIComponent(redirectUrl)}`;
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

            window.sessionStorage.setItem(this.storageKey, JSON.stringify(tokenResponse));
            this.applyTokens(tokenResponse);

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
        this._accessToken = tokens.access_token;
        this._statusSubject.next('authenticated');

        if (tokens.id_token) {
            const payload = this.decodeJwt(tokens.id_token);
            const user: AuthUser = {
                email: (payload['email'] as string) ?? '',
                name: (payload['name'] || payload['given_name']) as string | undefined,
                picture: payload['picture'] as string | undefined
            };
            this._userSubject.next(user);
        }
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
