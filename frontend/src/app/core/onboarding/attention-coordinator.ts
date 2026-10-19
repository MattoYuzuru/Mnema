import { Injectable, Injector, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';

import { AuthService } from '../../auth.service';
import { QuietZone } from '../notifications/quiet-zone';
import { JourneyStore } from './journey.store';
import { currentUrl, isStudyUrl } from './study-route';
import { TourEngine } from './tour-engine.service';

/**
 * The loud surfaces of the app, highest priority first (architecture §8): a system error, the study session, the guided tour, the first
 * entry (welcome, survey, branch choice), the in-place hint of «Путь» (the learner asked for it), the congratulation of the finished path
 * with its reward (OB-402: a full-screen night, shown once; it ranks above the promo window so that a window that opens later does not cover
 * it, and it never opens over a promo window that is already up, see `canShow`), the promo window, the toasts of milestones. Only the highest of
 * those on the screen speaks.
 */
export const SURFACES = ['system-error', 'study', 'tour', 'first-entry', 'hint', 'reward', 'promo', 'milestone'] as const;
export type Surface = typeof SURFACES[number];

/**
 * One loud surface at a time. Owners report what they have on the screen (`report`); the study session and the tour are read from where
 * they live (the quiet zone, the tour engine), so no owner can forget to say so. `canShow(surface)` is true when no
 * surface of a higher priority is on the screen; asking never shows anything.
 *
 * The reward congratulation (a loud, full-screen surface) keeps out of the study route itself like the first entry, and out of a promo window
 * that is already on the screen. It waits for the way to be free (no tour, first entry or hint; no open answer: study ranks above it) and
 * is asked again by its host whenever one of those changes.
 *
 * The promo window has more rules (`promoAllowedByJourney`): not before the journey is read, not while the first entry is waiting, not
 * while the journey is `IN_PROGRESS` or `PAUSED`, and not on the local day the journey finished.
 */
@Injectable({ providedIn: 'root' })
export class AttentionCoordinator {
    private readonly quiet = inject(QuietZone);
    private readonly router = inject(Router);
    private readonly injector = inject(Injector);
    private readonly url = currentUrl(this.router);
    private readonly reported = signal<ReadonlySet<Surface>>(new Set());
    /** The tour engine and the journey are read lazily: the promo service asks from specs and screens that never meet them. */
    private get engine(): TourEngine { return this.injector.get(TourEngine); }
    private get journey(): JourneyStore { return this.injector.get(JourneyStore); }

    /** The surfaces on the screen now, in priority order. */
    readonly visible = computed<readonly Surface[]>(() => SURFACES.filter(surface => this.isVisible(surface)));
    /** The one that speaks, or `null`. */
    readonly speaking = computed<Surface | null>(() => this.visible()[0] ?? null);

    /** An owner says that its surface is on the screen (or is gone). Study and the tour are not reported: they are read. */
    report(surface: Surface, visible: boolean): void {
        this.reported.update(current => {
            if (current.has(surface) === visible) return current;
            const next = new Set(current);
            if (visible) next.add(surface); else next.delete(surface);
            return next;
        });
    }

    /** Whether `surface` may come up now: nothing of a higher priority is on the screen. A surface does not block itself. */
    canShow(surface: Surface): boolean {
        const rank = SURFACES.indexOf(surface);
        const blocked = SURFACES.slice(0, rank).some(higher => this.isVisible(higher));
        if (blocked) return false;
        // The first entry and the reward also keep out of the study route itself (the setup and the summary screens are not «a task open»).
        if ((surface === 'first-entry' || surface === 'reward') && isStudyUrl(this.url())) return false;
        // The reward never opens over a promo window that is already up (a promo that comes later is held back by the reward's own rank).
        if (surface === 'reward' && this.isVisible('promo')) return false;
        return surface !== 'promo' || this.promoAllowedByJourney();
    }

    private isVisible(surface: Surface): boolean {
        switch (surface) {
            case 'study': return this.quiet.active();
            case 'tour': return this.engine.active() && !this.engine.paused();
            default: return this.reported().has(surface);
        }
    }

    /**
     * No promo before the journey has been read for a signed-in learner (the first entry may be about to open: a popup that arrives
     * first would sit under it), none while a first-entry card is waiting or the journey is `IN_PROGRESS` or `PAUSED`, and none on
     * the local day it finished. A journey that failed to read (or is off) never holds a promo back.
     */
    private promoAllowedByJourney(): boolean {
        const store = this.journey;
        if (this.injector.get(AuthService).status() === 'authenticated' && store.view() === null && store.error() === null) return false;
        const journey = store.journey();
        if (journey === null) return true;
        if (journey.state === 'IN_PROGRESS' || journey.state === 'PAUSED') return false;
        if (journey.state === 'OFFERED' && journey.autoOffer) return false;
        return journey.finishedAt === null || !sameLocalDay(new Date(journey.finishedAt), new Date());
    }
}

function sameLocalDay(first: Date, second: Date): boolean {
    return first.getFullYear() === second.getFullYear() && first.getMonth() === second.getMonth() && first.getDate() === second.getDate();
}
