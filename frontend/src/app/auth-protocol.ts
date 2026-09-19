/** Public-client protocol bounds; persisted values are input, never identity proof. */
export const AUTH_SCOPES = 'openid profile account.read account.write learning.read learning.write';
export const AUTH_STORAGE_KEY = 'mnema.identity.access';
export const PKCE_STORAGE_KEY = 'mnema.identity.pkce';

export interface IdentityProfile {
    accountId: string;
    email: string;
    emailVerified: boolean;
    profileUsername: string | null;
    displayName: string | null;
    hasPassword: boolean;
}

export interface StoredAccess {
    token: string;
    expiresAt: number;
    issuer: string;
    clientId: string;
}

export interface PkceTransaction {
    state: string;
    verifier: string;
    returnUrl: string;
    createdAt: number;
    issuer: string;
    clientId: string;
    redirectUri: string;
}

export class AuthFailure extends Error {
    constructor(readonly code: 'protocol' | 'storage' | 'configuration' | 'recovery_required') {
        super('Identity request could not be completed');
    }
}

export function objectValue(value: unknown): Record<string, unknown> {
    if (!value || typeof value !== 'object' || Array.isArray(value)) throw new AuthFailure('protocol');
    return value as Record<string, unknown>;
}

export function boundedText(value: unknown, maximum: number): value is string {
    if (typeof value !== 'string' || value.length > maximum) return false;
    for (let index = 0; index < value.length; index++) {
        const code = value.charCodeAt(index);
        if (code < 32 || code === 127) return false;
    }
    return true;
}

/** Only same-application paths; refuse encoded separators/control characters and auth loops. */
export function safeReturnUrl(value: unknown): string {
    if (!boundedText(value, 2048) || !value.startsWith('/') || value.startsWith('//') || value.includes('\\')) return '/decks';
    let decoded: string;
    try { decoded = decodeURIComponent(value); } catch { return '/decks'; }
    if (decoded.startsWith('//') || decoded.includes('\\') || !boundedText(decoded, 2048)) return '/decks';
    const path = decoded.split(/[?#;]/u, 1)[0];
    if (/^\/(?:login|register|auth)(?:\/|$)/u.test(path)) return '/decks';
    return value;
}

export function parseProfile(value: unknown): IdentityProfile {
    const p = objectValue(value);
    if ('operationId' in p && !('accountId' in p)) throw new AuthFailure('recovery_required');
    if (typeof p['accountId'] !== 'string' || !/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/u.test(p['accountId']) ||
        !boundedText(p['email'], 320) || typeof p['emailVerified'] !== 'boolean' ||
        !(p['profileUsername'] === null || boundedText(p['profileUsername'], 50)) ||
        !(p['displayName'] === null || boundedText(p['displayName'], 200)) ||
        typeof p['hasPassword'] !== 'boolean' || p['status'] !== 'ACTIVE') throw new AuthFailure('protocol');
    return { accountId: p['accountId'], email: p['email'], emailVerified: p['emailVerified'],
        profileUsername: p['profileUsername'], displayName: p['displayName'], hasPassword: p['hasPassword'] };
}

export function validAccessToken(value: unknown): value is string {
    return typeof value === 'string' && value.length > 0 && value.length <= 16384 && /^[A-Za-z0-9._~+/-]+=*$/u.test(value);
}

export function parseToken(value: unknown, now: number, issuer: string, clientId: string): StoredAccess {
    const t = objectValue(value);
    if (!validAccessToken(t['access_token']) || typeof t['token_type'] !== 'string' || t['token_type'].toLowerCase() !== 'bearer' ||
        !Number.isInteger(t['expires_in']) || Number(t['expires_in']) < 1 || Number(t['expires_in']) > 300 ||
        !boundedText(t['scope'], 512) || !AUTH_SCOPES.split(' ').every(scope => (t['scope'] as string).split(' ').includes(scope))) {
        throw new AuthFailure('protocol');
    }
    // Never retain refresh/id tokens or interpret a JWT payload as a verified profile.
    return { token: t['access_token'], expiresAt: now + Number(t['expires_in']) * 1000, issuer, clientId };
}

export function parseStoredAccess(raw: string, now: number, issuer: string, clientId: string): StoredAccess {
    if (raw.length > 18000) throw new AuthFailure('protocol');
    const t = objectValue(JSON.parse(raw));
    if (!validAccessToken(t['token']) || !Number.isSafeInteger(t['expiresAt']) || Number(t['expiresAt']) <= now ||
        Number(t['expiresAt']) > now + 300000 || t['issuer'] !== issuer || t['clientId'] !== clientId) throw new AuthFailure('protocol');
    return { token: t['token'], expiresAt: Number(t['expiresAt']), issuer, clientId };
}

export function parseTransaction(raw: string, now: number, issuer: string, clientId: string, redirectUri: string): PkceTransaction {
    if (raw.length > 4096) throw new AuthFailure('protocol');
    const t = objectValue(JSON.parse(raw));
    if (typeof t['state'] !== 'string' || !/^[A-Za-z0-9_-]{43}$/u.test(t['state']) ||
        typeof t['verifier'] !== 'string' || !/^[A-Za-z0-9_-]{43}$/u.test(t['verifier']) ||
        !Number.isSafeInteger(t['createdAt']) || Number(t['createdAt']) > now || now - Number(t['createdAt']) > 600000 ||
        t['issuer'] !== issuer || t['clientId'] !== clientId || t['redirectUri'] !== redirectUri ||
        t['returnUrl'] !== safeReturnUrl(t['returnUrl'])) throw new AuthFailure('protocol');
    return { state: t['state'], verifier: t['verifier'], returnUrl: t['returnUrl'] as string,
        createdAt: Number(t['createdAt']), issuer, clientId, redirectUri };
}
