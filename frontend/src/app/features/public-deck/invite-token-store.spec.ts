import { INVITE_STORAGE_KEY, INVITE_TTL_MS, clearInvite, peekInvite, stashInvite, takeInvite } from './invite-token-store';

const TOKEN = 'q3Jx0u5QmW8bYk2LzTn7VdPaE1sGcH4rFiOe6NhA9Ug';

describe('the guest invitation token store', () => {
    beforeEach(() => sessionStorage.clear());

    it('keeps {token, code, createdAt} in one session entry, and returns it exactly once', () => {
        stashInvite(sessionStorage, TOKEN, 'Kq7xT3mNpR', 1000);
        expect(JSON.parse(sessionStorage.getItem(INVITE_STORAGE_KEY)!)).toEqual({ token: TOKEN, code: 'Kq7xT3mNpR', createdAt: 1000 });
        expect(takeInvite(sessionStorage, 'Kq7xT3mNpR', 2000)).toBe(TOKEN);
        expect(sessionStorage.getItem(INVITE_STORAGE_KEY)).toBeNull();
        expect(takeInvite(sessionStorage, 'Kq7xT3mNpR', 2000)).toBeNull();
    });

    it('expires after ten minutes, and not a moment before', () => {
        stashInvite(sessionStorage, TOKEN, 'Kq7xT3mNpR', 0);
        expect(takeInvite(sessionStorage, 'Kq7xT3mNpR', INVITE_TTL_MS)).toBe(TOKEN);
        stashInvite(sessionStorage, TOKEN, 'Kq7xT3mNpR', 0);
        expect(takeInvite(sessionStorage, 'Kq7xT3mNpR', INVITE_TTL_MS + 1)).toBeNull();
        expect(sessionStorage.getItem(INVITE_STORAGE_KEY)).toBeNull();
    });

    it('keeps an entry for another deck, and deletes entries that cannot be trusted', () => {
        stashInvite(sessionStorage, TOKEN, 'Kq7xT3mNpR', 0);
        expect(takeInvite(sessionStorage, 'Zp4WcA8vQe', 10)).toBeNull();
        expect(sessionStorage.getItem(INVITE_STORAGE_KEY)).not.toBeNull();
        for (const bad of ['not json', '[]', '{"token":"short","code":"Kq7xT3mNpR","createdAt":0}', JSON.stringify({ token: TOKEN, code: 'Kq7xT3mNpR', createdAt: 0, extra: 1 }),
            JSON.stringify({ token: TOKEN, code: 'Kq7xT3mNpR', createdAt: 5000 })]) {
            sessionStorage.setItem(INVITE_STORAGE_KEY, bad);
            expect(takeInvite(sessionStorage, 'Kq7xT3mNpR', 10), bad).toBeNull();
            expect(sessionStorage.getItem(INVITE_STORAGE_KEY), bad).toBeNull();
        }
    });

    it('peeks without consuming: true only for a fresh entry of this deck', () => {
        stashInvite(sessionStorage, TOKEN, 'Kq7xT3mNpR', 0);
        expect(peekInvite(sessionStorage, 'Kq7xT3mNpR', 10)).toBe(true);
        expect(peekInvite(sessionStorage, 'Kq7xT3mNpR', 10)).toBe(true);
        expect(peekInvite(sessionStorage, 'Zp4WcA8vQe', 10)).toBe(false);
        expect(peekInvite(sessionStorage, 'Kq7xT3mNpR', INVITE_TTL_MS + 1)).toBe(false);
        expect(sessionStorage.getItem(INVITE_STORAGE_KEY)).not.toBeNull();
        expect(peekInvite(null, 'Kq7xT3mNpR')).toBe(false);
        sessionStorage.setItem(INVITE_STORAGE_KEY, 'garbage');
        expect(peekInvite(sessionStorage, 'Kq7xT3mNpR')).toBe(false);
    });

    it('stores nothing that is not a token, and survives missing or throwing storage', () => {
        stashInvite(sessionStorage, 'not-a-token', 'Kq7xT3mNpR');
        expect(sessionStorage.getItem(INVITE_STORAGE_KEY)).toBeNull();
        expect(() => stashInvite(null, TOKEN, 'Kq7xT3mNpR')).not.toThrow();
        expect(takeInvite(null, 'Kq7xT3mNpR')).toBeNull();
        const blocked = { setItem: () => { throw new Error('blocked'); }, getItem: () => { throw new Error('blocked'); }, removeItem: () => { throw new Error('blocked'); } } as unknown as Storage;
        expect(() => stashInvite(blocked, TOKEN, 'Kq7xT3mNpR')).not.toThrow();
        expect(takeInvite(blocked, 'Kq7xT3mNpR')).toBeNull();
        expect(() => clearInvite(blocked)).not.toThrow();
        stashInvite(sessionStorage, TOKEN, 'Kq7xT3mNpR');
        clearInvite(sessionStorage);
        expect(sessionStorage.getItem(INVITE_STORAGE_KEY)).toBeNull();
    });
});
