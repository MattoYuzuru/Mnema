import { InjectionToken } from '@angular/core';
import { appConfig } from './app.config';
import { AuthFailure } from './auth-protocol';

export interface AuthBrowser {
    readonly origin: string;
    readonly pathname: string;
    readonly search: string;
    readonly storage: Storage;
    now(): number;
    random(): string;
    challenge(verifier: string): Promise<string>;
    navigate(url: string): void;
    clearQuery(): void;
}

function base64url(bytes: Uint8Array): string {
    return btoa(String.fromCharCode(...bytes)).replace(/\+/gu, '-').replace(/\//gu, '_').replace(/=+$/u, '');
}

export const AUTH_BROWSER = new InjectionToken<AuthBrowser>('AUTH_BROWSER', { providedIn: 'root', factory: () => ({
    get origin() { return window.location.origin; },
    get pathname() { return window.location.pathname; },
    get search() { return window.location.search; },
    get storage() { return window.sessionStorage; },
    now: () => Date.now(),
    random: () => base64url(crypto.getRandomValues(new Uint8Array(32))),
    challenge: async verifier => base64url(new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier)))),
    navigate: url => window.location.assign(url),
    clearQuery: () => window.history.replaceState(window.history.state, '', window.location.pathname)
}) });

export interface BrowserIdentityConfig { authServerUrl: string; identityRedirectUri: string; clientId: string; learningApiBaseUrl: string }
export const BROWSER_IDENTITY_CONFIG = new InjectionToken<BrowserIdentityConfig>('BROWSER_IDENTITY_CONFIG', {
    providedIn: 'root', factory: () => appConfig
});

export function validateIdentityConfig(config: BrowserIdentityConfig, frontendOrigin: string): void {
    try {
        const issuer = new URL(config.authServerUrl);
        const redirect = new URL(config.identityRedirectUri);
        // Identity is a separate origin. XHR withCredentials:false then cannot send an ambient account cookie.
        if (issuer.protocol !== 'https:' || issuer.origin === frontendOrigin || issuer.origin !== config.authServerUrl || issuer.username || issuer.password ||
            redirect.protocol !== 'https:' || redirect.origin !== frontendOrigin || redirect.pathname !== '/auth/callback' ||
            redirect.username || redirect.password || redirect.search || redirect.hash || !/^[A-Za-z0-9._-]{1,128}$/u.test(config.clientId)) throw new Error();
    } catch { throw new AuthFailure('configuration'); }
}
