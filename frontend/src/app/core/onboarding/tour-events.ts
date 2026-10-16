import { Injectable } from '@angular/core';

/**
 * How a page tells the tour that the learner did what an action-gated step asked: the component that owns the control calls
 * `emit('deck.copied')` when its work succeeded. DOM clicks are never the signal, because a click does not mean the action worked.
 * Events with no listener are dropped.
 */
@Injectable({ providedIn: 'root' })
export class TourEvents {
    private readonly listeners = new Set<(event: string) => void>();

    emit(event: string): void {
        for (const listener of [...this.listeners]) listener(event);
    }

    /** Listens until the returned function is called. The engine holds at most one listener, for the current gate. */
    listen(listener: (event: string) => void): () => void {
        this.listeners.add(listener);
        return () => { this.listeners.delete(listener); };
    }
}
