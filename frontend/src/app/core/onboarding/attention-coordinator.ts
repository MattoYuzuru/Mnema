import { Injectable, Injector, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';

import { QuietZone } from '../notifications/quiet-zone';
import { JourneyStore } from './journey.store';
import { currentUrl, isStudyUrl } from './study-route';
import { TourEngine } from './tour-engine.service';

/**
 * The loud surfaces of the app, highest priority first (architecture §8): a system error, the study session, the guided tour, the first
 * entry (welcome, survey, branch choice), the promo window, the toasts of milestones. Only the highest of those on the screen speaks.
 */
export const SURFACES = ['system-error', 'study', 'tour', 'first-entry', 'promo', 'milestone'] as const;
export type Surface = typeof SURFACES[number];

/**
 * One loud surface at a time. Owners report what they have on the screen (`report`); the study session and the tour are read from where
 * they live (the quiet zone, the tour engine), so no owner can forget to say so. `canShow(surface)` is true when no
 * surface of a higher priority is on the screen; asking never shows anything.
 *
 * The promo window has two rules more: it is not asked for while the journey is `IN_PROGRESS` or `PAUSED` (the learner is in the
 * middle of getting to know the app) and not on the local day the journey finished.
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
        // The first entry also keeps out of the study route itself (the setup and the summary screens are not «a task open»).
        if (surface === 'first-entry' && isStudyUrl(this.url())) return false;
        return surface !== 'promo' || this.promoAllowedByJourney();
    }

    private isVisible(surface: Surface): boolean {
        switch (surface) {
            case 'study': return this.quiet.active();
            case 'tour': return this.engine.active() && !this.engine.paused();
            default: return this.reported().has(surface);
        }
    }

    /** No promo in the middle of the journey, and none on the day it ended. A journey that is not loaded (or off) never holds a promo back. */
    private promoAllowedByJourney(): boolean {
        const journey = this.journey.journey();
        if (journey === null) return true;
        if (journey.state === 'IN_PROGRESS' || journey.state === 'PAUSED') return false;
        return journey.finishedAt === null || !sameLocalDay(new Date(journey.finishedAt), new Date());
    }
}

function sameLocalDay(first: Date, second: Date): boolean {
    return first.getFullYear() === second.getFullYear() && first.getMonth() === second.getMonth() && first.getDate() === second.getDate();
}
