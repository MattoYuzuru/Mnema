import { Injectable, signal } from '@angular/core';

/**
 * The automatic updates this browser tab has marked seen (`:seen` was sent), kept so the hub hides the notice «Что нового» at once, also when the
 * owner comes back before the next read of the deck would show the server's answer. An entry is put back when its request failed, so the
 * notice never disappears for good without the server having heard of it. Nothing is stored beyond the tab.
 */
@Injectable({ providedIn: 'root' })
export class UpdateSeenState {
    private readonly ids = signal<ReadonlySet<string>>(new Set());
    readonly seen = this.ids.asReadonly();

    mark(deckRevisionId: string): void {
        this.ids.update(current => new Set(current).add(deckRevisionId.toLowerCase()));
    }

    unmark(deckRevisionId: string): void {
        this.ids.update(current => {
            const next = new Set(current);
            next.delete(deckRevisionId.toLowerCase());
            return next;
        });
    }
}
