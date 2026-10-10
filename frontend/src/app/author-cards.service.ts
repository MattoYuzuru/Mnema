import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { AUTH_BROWSER, BROWSER_IDENTITY_CONFIG, accountsApiBase } from './auth-browser';
import { ACCOUNT_ID, AUTHOR_CARD_BATCH_MAX, AuthorCard, PROFILE_USERNAME, parseAuthorCard, parseAuthorCardBatch } from './public-profile';

/** How long a card, or the absence of one, is trusted. The server caches public reads for the same minute. */
export const AUTHOR_CARD_TTL_MS = 60_000;
/** Above this many remembered entries the expired ones are dropped on the next request. */
const PRUNE_ABOVE = 256;

interface Settled { readonly card: AuthorCard | null; readonly expiresAt: number }
interface Waiter { readonly resolve: (card: AuthorCard | null) => void; readonly reject: (reason: unknown) => void }

/**
 * Public author cards (Identity, no bearer: the endpoints are anonymous and are not credential targets).
 *
 * Ids asked for within one microtask leave as batches of at most 50. A card, and equally a miss (hidden, no consent,
 * unknown: the server answers all alike), is remembered for 60 seconds; a failed request is not remembered, so the
 * next call tries again. The same id asked for while a request is on its way shares that request.
 */
@Injectable({ providedIn: 'root' })
export class AuthorCardsService {
    private readonly http = inject(HttpClient);
    private readonly config = inject(BROWSER_IDENTITY_CONFIG);
    private readonly browser = inject(AUTH_BROWSER);

    private readonly byId = new Map<string, Settled>();
    private readonly byName = new Map<string, Settled>();
    private readonly idRequests = new Map<string, Promise<AuthorCard | null>>();
    private readonly nameRequests = new Map<string, Promise<AuthorCard | null>>();
    private queued = new Map<string, Waiter>();

    /** Cards for the given account ids; ids without a public card are absent from the result. Rejects if a request fails. */
    async cards(ids: readonly string[]): Promise<ReadonlyMap<string, AuthorCard>> {
        const unique = [...new Set(ids.map(id => id.toLowerCase()))];
        const found = await Promise.all(unique.map(id => this.card(id)));
        const result = new Map<string, AuthorCard>();
        found.forEach(card => { if (card !== null) result.set(card.accountId, card); });
        return result;
    }

    /** One card, or `null` when the account has no public profile. */
    card(accountId: string): Promise<AuthorCard | null> {
        const id = accountId.toLowerCase();
        if (!ACCOUNT_ID.test(id)) return Promise.resolve(null);
        const cached = this.fresh(this.byId, id);
        if (cached !== undefined) return Promise.resolve(cached.card);
        const running = this.idRequests.get(id);
        if (running !== undefined) return running;
        const request = new Promise<AuthorCard | null>((resolve, reject) => {
            const first = this.queued.size === 0;
            this.queued.set(id, { resolve, reject });
            if (first) void Promise.resolve().then(() => this.flush());
        });
        this.idRequests.set(id, request);
        return request;
    }

    /** The card behind a login, or `null`. Hidden and unknown logins are indistinguishable by design. */
    byUsername(username: string): Promise<AuthorCard | null> {
        if (!PROFILE_USERNAME.test(username)) return Promise.resolve(null);
        const cached = this.fresh(this.byName, username);
        if (cached !== undefined) return Promise.resolve(cached.card);
        const running = this.nameRequests.get(username);
        if (running !== undefined) return running;
        const request = this.fetchByUsername(username).finally(() => this.nameRequests.delete(username));
        this.nameRequests.set(username, request);
        return request;
    }

    /** The public photo of a card, or `null` when the owner does not show one. */
    avatarUrl(card: AuthorCard): string | null {
        return card.avatarPresent ? `${this.base()}/profiles/${card.accountId}/avatar` : null;
    }

    private base(): string { return accountsApiBase(this.config, this.browser.origin); }

    private fresh(store: Map<string, Settled>, key: string): Settled | undefined {
        const entry = store.get(key);
        if (entry === undefined) return undefined;
        if (entry.expiresAt <= Date.now()) { store.delete(key); return undefined; }
        return entry;
    }

    private remember(store: Map<string, Settled>, key: string, card: AuthorCard | null): void {
        const now = Date.now();
        if (store.size >= PRUNE_ABOVE) store.forEach((entry, name) => { if (entry.expiresAt <= now) store.delete(name); });
        store.set(key, { card, expiresAt: now + AUTHOR_CARD_TTL_MS });
    }

    private flush(): void {
        const batch = this.queued;
        this.queued = new Map();
        const ids = [...batch.keys()];
        for (let from = 0; from < ids.length; from += AUTHOR_CARD_BATCH_MAX) {
            const chunk = ids.slice(from, from + AUTHOR_CARD_BATCH_MAX);
            void this.fetchChunk(chunk, batch);
        }
    }

    private async fetchChunk(chunk: readonly string[], waiters: ReadonlyMap<string, Waiter>): Promise<void> {
        try {
            const body = await firstValueFrom(this.http.get<unknown>(`${this.base()}/profiles`,
                { params: { ids: chunk.join(',') }, withCredentials: false }));
            const cards = new Map(parseAuthorCardBatch(body, chunk).map(card => [card.accountId, card] as const));
            for (const id of chunk) {
                const card = cards.get(id) ?? null;
                this.remember(this.byId, id, card);
                this.idRequests.delete(id);
                waiters.get(id)?.resolve(card);
            }
        } catch (error) {
            for (const id of chunk) {
                this.idRequests.delete(id);
                waiters.get(id)?.reject(error);
            }
        }
    }

    private async fetchByUsername(username: string): Promise<AuthorCard | null> {
        try {
            const body = await firstValueFrom(this.http.get<unknown>(`${this.base()}/profiles/by-username/${encodeURIComponent(username)}`,
                { withCredentials: false }));
            const card = parseAuthorCard(body);
            this.remember(this.byName, username, card);
            this.remember(this.byId, card.accountId, card);
            return card;
        } catch (error) {
            if (isNotFound(error)) { this.remember(this.byName, username, null); return null; }
            throw error;
        }
    }
}

function isNotFound(error: unknown): boolean {
    return error instanceof HttpErrorResponse && error.status === 404;
}
