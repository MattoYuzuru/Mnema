import { Injectable } from '@angular/core';

/**
 * How a page tells the tour that the learner did what an action-gated step asked: the component that owns the control calls
 * `emit('deck.copied')` when its work succeeded. DOM clicks are never the signal, because a click does not mean the action worked.
 * Events with no listener are dropped.
 */
/** `true` from a listener: it took the event (a gate moved on). Anything else: it only heard it. */
export type TourListener = (event: string, payload: TourEventPayload | undefined) => boolean | void;

/** Identifiers only, never text. */
export type TourEventPayload = Readonly<Record<string, string>>;

@Injectable({ providedIn: 'root' })
export class TourEvents {
    private readonly listeners = new Set<TourListener>();

    /**
     * `payload` carries what the next step needs to know (the id of the deck that was just made); nothing the learner typed.
     * Answers whether an active gate took the event: then the tour moves the learner on (it navigates to the next step's place) and the
     * page must not navigate by itself, or the two navigations cancel each other; when nobody took it the page goes where it always goes.
     */
    emit(event: string, payload?: TourEventPayload): boolean {
        let taken = false;
        for (const listener of [...this.listeners]) taken = listener(event, payload) === true || taken;
        return taken;
    }

    /** Listens until the returned function is called. The engine holds at most one listener, for the current gate; the onboarding flow another. */
    listen(listener: TourListener): () => void {
        this.listeners.add(listener);
        return () => { this.listeners.delete(listener); };
    }
}
