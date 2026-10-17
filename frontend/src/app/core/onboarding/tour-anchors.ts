import { Injectable, signal } from '@angular/core';

/**
 * Where the tour can point. Elements register under a stable key through `mnTourAnchor`, never a CSS selector, so a restyled page
 * keeps its tour. A key is added to `TOUR_ANCHOR_KEYS` (tour-anchor-keys.ts) when the page that owns it mounts the directive.
 */
@Injectable({ providedIn: 'root' })
export class TourAnchors {
    private readonly elements = signal<ReadonlyMap<string, HTMLElement>>(new Map());

    /** The element registered under `key`, or `null`. Reactive: a `computed` reading it follows registration and removal. */
    lookup(key: string): HTMLElement | null {
        return this.elements().get(key) ?? null;
    }

    /**
     * Registers `element`; a later registration of the same key replaces the earlier one (a page that re-renders may mount the new
     * element before it removes the old). The returned function removes it, and only if it is still the registered element.
     */
    register(key: string, element: HTMLElement): () => void {
        this.elements.update(current => new Map(current).set(key, element));
        return () => this.elements.update(current => {
            if (current.get(key) !== element) return current;
            const next = new Map(current);
            next.delete(key);
            return next;
        });
    }
}
