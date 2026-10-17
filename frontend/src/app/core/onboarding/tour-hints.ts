import { Injectable, signal } from '@angular/core';

/**
 * What the community tour wants said while the learner browses the catalogue without opening a deck: the hint at the search (45 s) and
 * the offer to make their own deck (2 min). The flow sets them; the community page and the onboarding host show them.
 */
@Injectable({ providedIn: 'root' })
export class TourHints {
    readonly browse = signal(false);
    readonly stuck = signal(false);
}
