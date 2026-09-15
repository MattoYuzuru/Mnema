import { AUTH_SCOPES, parseProfile, parseStoredAccess, parseToken, parseTransaction, safeReturnUrl } from './auth-protocol';
import { validateIdentityConfig } from './auth-browser';

describe('browser Identity protocol boundaries', () => {
    const issuer = 'https://identity.example.test';
    const origin = 'https://app.example.test';
    const clientId = 'mnema-web';
    const redirectUri = `${origin}/auth/callback`;
    const now = 1800000000000;
    const token = { access_token: 'opaque-token', expires_in: 300, token_type: 'Bearer', scope: AUTH_SCOPES };

    it('only accepts same-application return paths and prevents auth loops', () => {
        expect(safeReturnUrl('/decks/123?view=metadata#title')).toBe('/decks/123?view=metadata#title');
        for (const value of [null, '', 'https://evil.test', '//evil.test', '/\\evil.test', '/%2fevil.test', '/%5cevil.test',
            '/%0aevil', '/%xx', '/login', '/register?x=1', '/auth/callback', '/auth;aux=1', '/a\n', '/'.repeat(2049)]) {
            expect(safeReturnUrl(value)).withContext(String(value)).toBe('/decks');
        }
    });

    it('requires HTTPS origins and exact same-origin callback without injected params', () => {
        const config = { authServerUrl: issuer, identityRedirectUri: redirectUri, clientId, learningApiBaseUrl: '/api' };
        expect(() => validateIdentityConfig(config, origin)).not.toThrow();
        for (const authServerUrl of [origin, 'http://identity.example.test', `${issuer}/`, `${issuer}/path`, 'https://user@identity.example.test']) {
            expect(() => validateIdentityConfig({ ...config, authServerUrl }, origin)).toThrow();
        }
        for (const identityRedirectUri of [`${origin}/`, `${redirectUri}?code=1`, `${redirectUri}#x`, 'https://elsewhere.test/auth/callback']) {
            expect(() => validateIdentityConfig({ ...config, identityRedirectUri }, origin)).toThrow();
        }
    });

    it('stores only access expiry/binding and never trusts ID/refresh claims', () => {
        expect(parseToken({ ...token, id_token: 'unverified', refresh_token: 'do-not-store' }, now, issuer, clientId)).toEqual({
            token: 'opaque-token', expiresAt: now + 300000, issuer, clientId
        });
    });

    it('rejects malformed token response, excessive lifetime and missing permissions', () => {
        for (const value of [null, [], {}, { ...token, access_token: 'abc\ndef' }, { ...token, access_token: 'a'.repeat(16385) },
            { ...token, expires_in: 301 }, { ...token, expires_in: 0 }, { ...token, expires_in: 1.5 },
            { ...token, expires_in: '300' }, { ...token, token_type: 'Basic' }, { ...token, scope: 'openid profile account.read' }]) {
            expect(() => parseToken(value, now, issuer, clientId)).toThrow();
        }
    });

    it('restored access must be unexpired and bound to this issuer/client', () => {
        const stored = parseToken(token, now, issuer, clientId);
        expect(parseStoredAccess(JSON.stringify(stored), now, issuer, clientId)).toEqual(stored);
        for (const update of [{ expiresAt: now }, { expiresAt: now + 300001 }, { issuer: 'https://elsewhere.test' }, { clientId: 'other' }, { token: '' }]) {
            expect(() => parseStoredAccess(JSON.stringify({ ...stored, ...update }), now, issuer, clientId)).toThrow();
        }
        expect(() => parseStoredAccess(' '.repeat(18001), now, issuer, clientId)).toThrow();
    });

    it('PKCE transaction is short-lived, state/verifier bounded and deployment-bound', () => {
        const t = { state: 's'.repeat(43), verifier: 'v'.repeat(43), returnUrl: '/decks', createdAt: now,
            issuer, clientId, redirectUri };
        expect(parseTransaction(JSON.stringify(t), now, issuer, clientId, redirectUri)).toEqual(t);
        for (const update of [{ state: 'short' }, { verifier: 'x'.repeat(129) }, { returnUrl: '//evil.test' },
            { createdAt: now + 1 }, { createdAt: now - 600001 }, { issuer: 'https://other.test' }, { clientId: 'other' }, { redirectUri: origin }]) {
            expect(() => parseTransaction(JSON.stringify({ ...t, ...update }), now, issuer, clientId, redirectUri)).toThrow();
        }
    });

    it('identity comes only from canonical server profile; recovery is never ordinary access', () => {
        const profile = { accountId: '11111111-1111-4111-8111-111111111111', email: 'fixture@example.test',
            emailVerified: false, profileUsername: 'fixture', displayName: null, hasPassword: true, status: 'ACTIVE' };
        expect(parseProfile(profile).accountId).toBe(profile.accountId);
        for (const value of [{ ...profile, accountId: '0' }, { ...profile, status: 'BANNED' },
            { ...profile, hasPassword: 'true' }, { operationId: 'synthetic-deletion' }, { email: profile.email }]) {
            expect(() => parseProfile(value)).toThrow();
        }
    });
});
