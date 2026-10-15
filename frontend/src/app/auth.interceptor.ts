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
        const accountId = '[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}';
        const identityRoute = url.origin === identity.origin &&
            (['/userinfo', '/api/accounts/me', '/api/accounts/me/avatar', '/api/accounts/me/public-profile', '/api/accounts/admin/directory', '/api/accounts/admin/audit'].includes(url.pathname)
            || new RegExp(`^/api/accounts/admin/directory/${accountId}$`, 'iu').test(url.pathname)
            || new RegExp(`^/api/accounts/admin/accounts/${accountId}/(?:ban|unban)$`, 'iu').test(url.pathname));
        const prefix = learning.pathname.replace(/\/$/u, '');
        // The configured base is data, not a pattern: escape it before it enters a regular expression.
        const pattern = prefix.replace(/[.*+?^${}()|[\]\\]/gu, '\\$&');
        const learningRoots = [`${prefix}/decks`, `${prefix}/editing-drafts`, `${prefix}/capture-notes`,
            `${prefix}/media-assets`, `${prefix}/notifications`, `${prefix}/generation-sessions`, `${prefix}/speech-inputs`,
            `${prefix}/admin/events`, `${prefix}/billing/orders`];
        // The account-wide `generation-sessions` list is the only generation route outside `/decks/{id}`.
        // Single-resource routes without subpaths: capability flags, stateless author preview evaluation, the usage bar,
        // the paywall catalogue and the goal answer.
        // The promo code redemption, the promo popup, the A/B event sink and the checkout are single-resource routes too; the bank's
        // notification endpoint (`billing/tbank/notifications`) never receives a token.
        const learningExact = [`${prefix}/capabilities`, `${prefix}/exercise-previews`, `${prefix}/usage`, `${prefix}/plans`,
            `${prefix}/learning-profile`, `${prefix}/speech-consent`, `${prefix}/promo-codes/redemptions`, `${prefix}/promo-popup`, `${prefix}/promo-popup/events`,
            `${prefix}/experiment-events`, `${prefix}/billing/checkout`,
            // The topic directory of the publication checklist (Share/8) and the invitation redemption of a signed-in viewer (Share/13).
            `${prefix}/topics`, `${prefix}/invites:redeem`,
            `${prefix}/community/interests`, `${prefix}/community/interests:reset`, `${prefix}/community/events`,
            `${prefix}/community/exposures`, `${prefix}/community/recommendations`];
        const adminRoute = [`${prefix}/admin/console/access`, `${prefix}/admin/console/report`, `${prefix}/admin/console/audit`, `${prefix}/admin/promo-codes`, `${prefix}/admin/support/tickets`,
            `${prefix}/admin/complaints`, `${prefix}/admin/complaints/journal`].includes(url.pathname)
            // The complaint queue of the owner console (Share/15): one complaint, its unit picker and the two decisions (`:decide`, `:unhide`).
            || new RegExp(`^${pattern}/admin/complaints/${accountId}(?:/units|:decide|:unhide)?$`, 'iu').test(url.pathname)
            || new RegExp(`^${pattern}/admin/console/users/${accountId}$`, 'iu').test(url.pathname)
            || new RegExp(`^${pattern}/admin/promo-codes/${accountId}$`, 'iu').test(url.pathname)
            || new RegExp(`^${pattern}/admin/support/tickets/[1-9][0-9]*(?:/commands)?$`, 'u').test(url.pathname);
        // Public deck reads (`/public/decks/{code}`, its `items`, one `items/{memberKey}`, `exercises` and `media/{assetId}`): the
        // bearer is optional there. A guest has no token and sends none; a signed-in viewer sends it so the server can resolve OWNER or
        // GRANTEE. The code is exactly ten base58 characters, never a prefix match.
        const entityId = '[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}';
        const publicRead = new RegExp(`^${pattern}/public/decks/[1-9A-HJ-NP-Za-km-z]{10}(?:/items(?:/${entityId})?|/exercises|/media/${entityId}|/complaints|/access-requests(?::withdraw)?)?$`, 'u')
            .test(url.pathname)
            // The community catalogue (epic 3): facets, shelves, browse and search, and the decks of an author. The bearer is optional there too: a guest sends none, and a
            // signed-in viewer sends it so the limits are keyed by the account and the author's page can add the invitation-only decks the viewer was granted.
            || new RegExp(`^${pattern}/public/community/(?:facets|shelves|decks|authors/${entityId}/decks)$`, 'u').test(url.pathname)
            // Asking for and withdrawing access to an invitation deck (Share/13) is signed-in only, like filing a complaint.
            // Filing a complaint about a profile (Share/15): signed-in only, the login is the Identity profile username.
            || new RegExp(`^${pattern}/public/profiles/[A-Za-z0-9_.-]{3,50}/complaints$`, 'u').test(url.pathname);
        const learningRoute = url.origin === learning.origin && (adminRoute || publicRead || learningExact.includes(url.pathname)
            || learningRoots.some(root => url.pathname === root || url.pathname.startsWith(`${root}/`)));
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
