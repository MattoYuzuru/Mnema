import { Injectable } from '@angular/core';

/** What the owner has picked in the «Сделать публичной» checklist and not published yet. */
export interface ChecklistDraft {
    readonly topicId: string;
    readonly language: string;
    readonly level: string;
    readonly tags: readonly string[];
}

/**
 * The checklist draft of each deck for the life of the page (memory only, never stored): Esc, «Отмена» or a trip to the profile
 * to fix another item must not cost the owner the topic, language, level and tags they already chose. Cleared when the deck is published.
 */
@Injectable({ providedIn: 'root' })
export class PublicationDraftStore {
    private readonly drafts = new Map<string, ChecklistDraft>();

    get(deckId: string): ChecklistDraft | null {
        return this.drafts.get(deckId) ?? null;
    }

    set(deckId: string, draft: ChecklistDraft): void {
        this.drafts.set(deckId, draft);
    }

    clear(deckId: string): void {
        this.drafts.delete(deckId);
    }
}
