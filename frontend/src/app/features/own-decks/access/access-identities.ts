import { signal } from '@angular/core';

import type { AuthorCardsService } from '../../../author-cards.service';

/** Who an opaque account id is, as far as the owner may know: nothing but what the person published. */
export type AccessIdentity =
    | { readonly state: 'loading' }
    | { readonly state: 'card'; readonly username: string; readonly avatarSrc: string | null }
    /** No public card: no consent, hidden or unknown (the server answers all alike). */
    | { readonly state: 'none' }
    /** The lookup itself failed; the id may well have a card. */
    | { readonly state: 'failed' };

const LOADING: AccessIdentity = { state: 'loading' };

/**
 * Names for the account ids of a panel list, through the batched, consent-gated Identity author cards. Rows ask for their ids once per
 * page (`resolve`); until the answer a row reads «Загружаем имя…», a person without a card reads «Пользователь без публичного профиля»,
 * and a failed lookup is retried the next time the same id is asked for.
 */
export class AccessIdentities {
    private readonly known = signal<ReadonlyMap<string, AccessIdentity>>(new Map());

    constructor(private readonly cards: Pick<AuthorCardsService, 'cards' | 'avatarUrl'>) { }

    of(accountId: string): AccessIdentity {
        return this.known().get(accountId) ?? LOADING;
    }

    resolve(ids: readonly string[]): void {
        const wanted = [...new Set(ids)].filter(id => {
            const identity = this.known().get(id);
            return identity === undefined || identity.state === 'failed';
        });
        if (wanted.length === 0) return;
        this.set(wanted, LOADING);
        this.cards.cards(wanted).then(found => {
            const next = new Map(this.known());
            for (const id of wanted) {
                const card = found.get(id);
                next.set(id, card === undefined ? { state: 'none' } : { state: 'card', username: card.profileUsername, avatarSrc: this.cards.avatarUrl(card) });
            }
            this.known.set(next);
        }, () => this.set(wanted, { state: 'failed' }));
    }

    private set(ids: readonly string[], identity: AccessIdentity): void {
        const next = new Map(this.known());
        ids.forEach(id => next.set(id, identity));
        this.known.set(next);
    }
}
