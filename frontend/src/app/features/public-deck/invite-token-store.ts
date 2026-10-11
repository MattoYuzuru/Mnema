/**
 * Where an invitation token waits while a guest signs in. The token never travels in the login `returnUrl`, in the history entry or
 * in the OAuth (PKCE) transaction: on arrival the page strips `?invite=` from the address and keeps the token here, in one
 * `sessionStorage` entry of this tab, for at most {@link INVITE_TTL_MS}. After the sign-in returns to `/d/{code}` the entry is
 * consumed exactly once: it is deleted before the redeem request is sent. Every storage access is guarded (private windows and
 * blocked site data throw); without storage the guest simply asks for the link again.
 */
export const INVITE_STORAGE_KEY = 'mnema.invite.pending';
export const INVITE_TTL_MS = 10 * 60_000;
/** An invitation token: 32 random bytes in base64url without padding (`contracts/decks/access.json`). */
export const INVITE_TOKEN = /^[A-Za-z0-9_-]{43}$/u;

export function stashInvite(storage: Storage | null, token: string, code: string, now: number = Date.now()): void {
    if (storage === null || !INVITE_TOKEN.test(token)) return;
    try {
        storage.setItem(INVITE_STORAGE_KEY, JSON.stringify({ token, code, createdAt: now }));
    } catch { /* no storage: the guest asks for the link again */ }
}

/**
 * The stashed token for `code`, or `null`. It is deleted before it is returned, so it can be spent once. An entry that expired, is
 * malformed or is in the future is deleted and ignored; an entry for another deck is left for that deck.
 */
export function takeInvite(storage: Storage | null, code: string, now: number = Date.now()): string | null {
    if (storage === null) return null;
    try {
        const raw = storage.getItem(INVITE_STORAGE_KEY);
        if (raw === null) return null;
        const entry = parse(raw);
        if (entry === null || now < entry.createdAt || now - entry.createdAt > INVITE_TTL_MS) {
            storage.removeItem(INVITE_STORAGE_KEY);
            return null;
        }
        if (entry.code !== code) return null;
        storage.removeItem(INVITE_STORAGE_KEY);
        return entry.token;
    } catch {
        return null;
    }
}

/** Whether a usable token for `code` waits in the store. Reads only: nothing is deleted and nothing is returned. */
export function peekInvite(storage: Storage | null, code: string, now: number = Date.now()): boolean {
    if (storage === null) return false;
    try {
        const raw = storage.getItem(INVITE_STORAGE_KEY);
        const entry = raw === null ? null : parse(raw);
        return entry !== null && entry.code === code && now >= entry.createdAt && now - entry.createdAt <= INVITE_TTL_MS;
    } catch {
        return false;
    }
}

export function clearInvite(storage: Storage | null): void {
    try { storage?.removeItem(INVITE_STORAGE_KEY); } catch { /* nothing to clear */ }
}

function parse(raw: string): { token: string; code: string; createdAt: number } | null {
    let value: unknown;
    try { value = JSON.parse(raw); } catch { return null; }
    if (value === null || typeof value !== 'object' || Array.isArray(value)) return null;
    const entry = value as Record<string, unknown>;
    const keys = Object.keys(entry);
    if (keys.length !== 3 || !keys.every(key => ['token', 'code', 'createdAt'].includes(key))) return null;
    const { token, code, createdAt } = entry;
    return typeof token === 'string' && INVITE_TOKEN.test(token) && typeof code === 'string' && code.length > 0 && code.length <= 64
        && typeof createdAt === 'number' && Number.isFinite(createdAt) ? { token, code, createdAt } : null;
}
