import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';
import { AuthService } from './auth.service';
import { BROWSER_IDENTITY_CONFIG } from './auth-browser';
import { boundedText } from './auth-protocol';

/** Canonical owned route boundary, not a hostname/string prefix allowlist. */
export function isCredentialTarget(requestUrl: string, identityOrigin: string, learningBase: string, origin: string): boolean {
    try {
        // Encoded routing separators and dot segments are not canonical application paths.
        if (!boundedText(requestUrl, 8192) || requestUrl.includes('\\') || requestUrl.includes(' ') ||
            /%(?:2f|5c|2e)/iu.test(requestUrl) || /(?:^|\/)\.{1,2}(?:\/|$)/u.test(requestUrl)) return false;
        const url = new URL(requestUrl, origin);
        const identity = new URL(identityOrigin);
        const learning = new URL(learningBase, origin);
        if (url.username || url.password || url.hash || identity.origin !== identityOrigin) return false;
        const identityRoute = url.origin === identity.origin && ['/userinfo', '/api/accounts/me'].includes(url.pathname);
        const prefix = learning.pathname.replace(/\/$/u, '');
        const learningRoots = [`${prefix}/decks`, `${prefix}/editing-drafts`, `${prefix}/capture-notes`];
        const learningRoute = url.origin === learning.origin && learningRoots.some(root =>
            url.pathname === root || url.pathname.startsWith(`${root}/`));
        return identityRoute || learningRoute;
    } catch { return false; }
}

export const authInterceptor: HttpInterceptorFn = (req, next) => {
    const config = inject(BROWSER_IDENTITY_CONFIG);
    if (!isCredentialTarget(req.url, config.authServerUrl, config.learningApiBaseUrl, window.location.origin)) return next(req);
    const auth = inject(AuthService);
    const token = auth.accessToken();
    if (!token) return next(req);
    return next(req.clone({ setHeaders: { Authorization: `Bearer ${token}` } })).pipe(
        catchError((error: unknown) => {
            if (error instanceof HttpErrorResponse && error.status === 401) auth.expireSession(token);
            return throwError(() => error);
        })
    );
};
