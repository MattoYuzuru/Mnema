import { Injectable } from '@angular/core';

/**
 * How a page tells the tour that the learner did what an action-gated step asked: the component that owns the control calls
 * `emit('deck.copied')` when its work succeeded. DOM clicks are never the signal, because a click does not mean the action worked.
 * Events with no listener are dropped.
 */
/** Identifiers only, never text. */
export type TourEventPayload = Readonly<Record<string, string>>;

@Injectable({ providedIn: 'root' })
export class TourEvents {
    private readonly listeners = new Set<(event: string, payload: TourEventPayload | undefined) => void>();

    /** `payload` carries what the next step needs to know (the id of the deck that was just made); nothing the learner typed. */
    emit(event: string, payload?: TourEventPayload): void {
        for (const listener of [...this.listeners]) listener(event, payload);
    }

    /** Listens until the returned function is called. The engine holds at most one listener, for the current gate; the onboarding flow another. */
    listen(listener: (event: string, payload: TourEventPayload | undefined) => void): () => void {
        this.listeners.add(listener);
        return () => { this.listeners.delete(listener); };
    }
}
