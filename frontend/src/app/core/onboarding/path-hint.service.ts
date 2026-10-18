import { DestroyRef, Injectable, computed, effect, inject, signal, untracked } from '@angular/core';
import { Router } from '@angular/router';

import { AttentionCoordinator } from './attention-coordinator';
import { currentUrl } from './study-route';
import { TourAnchors } from './tour-anchors';

/** How long the hint waits for its anchor to appear on the page before it says its sentence at the top of the page instead. */
export const HINT_ANCHOR_WAIT_MS = 4_000;

/** What the hint says and where it stands: the heading (the milestone's title), the sentence, and the key of the anchor (`mnTourAnchor`). */
export interface PathHintRequest {
    readonly title: string;
    readonly text: string;
    readonly anchor: string;
}

/** `locating`: the anchor is not on the page yet; `ready`: it is, the card stands next to it; `missing`: it did not appear in time, the card is at the top of the page. */
export type PathHintPhase = 'locating' | 'ready' | 'missing';

interface Shown {
    readonly id: number;
    readonly request: PathHintRequest;
    /** The page the hint belongs to, known once the navigation ended (`arm`): another page takes it away. */
    readonly url: string | null;
    readonly timedOut: boolean;
}

/**
 * The in-place hint of «Путь»: a short card next to the thing a milestone is about, with no veil (the learner followed a chevron and
 * should see where to act). It never starts while a louder surface is on the screen (the coordinator: Study, the tour, the first
 * entry) and speaks louder than the promo window and the milestone toasts, which wait for it. The service holds only the state: what is
 * said, where the anchor is, and which phase the hint is in; `PathHintComponent` draws it, moves the focus and listens for the keys.
 *
 * One hint at a time; a new request replaces the old one. It ends with «Понятно», Escape, the action on the anchor, another page, or
 * the anchor leaving the page.
 */
@Injectable({ providedIn: 'root' })
export class PathHint {
    private readonly anchors = inject(TourAnchors);
    private readonly attention = inject(AttentionCoordinator);
    private readonly router = inject(Router);
    private readonly url = currentUrl(this.router);

    private readonly state = signal<Shown | null>(null);
    private counter = 0;
    private timer: ReturnType<typeof setTimeout> | null = null;
    private wasReady = false;

    private readonly focusPending = signal(false);
    /** A hint was asked for and its card has not taken the focus yet: the page heading must not take it first. */
    readonly awaitingFocus = this.focusPending.asReadonly();
    /** A hint was asked for and is not over (still looking for its anchor, or on the screen). */
    readonly active = computed(() => this.state() !== null);
    readonly request = computed(() => this.state()?.request ?? null);
    /** The element of the anchor, once registered. */
    readonly target = computed(() => {
        const state = this.state();
        return state === null ? null : this.anchors.lookup(state.request.anchor);
    });
    readonly phase = computed<PathHintPhase | null>(() => {
        const state = this.state();
        if (state === null) return null;
        // Nothing is drawn before the navigation to the place has ended: the card would stand over the page the learner is leaving.
        if (state.url === null) return 'locating';
        if (this.target() !== null) return 'ready';
        return state.timedOut ? 'missing' : 'locating';
    });
    /** The card is on the screen (next to the anchor, or at the top of the page). */
    readonly visible = computed(() => this.phase() === 'ready' || this.phase() === 'missing');
    /** Identifies one hint: a new one is a new id, so the card takes the focus once per hint. */
    readonly id = computed(() => this.state()?.id ?? 0);

    constructor() {
        // The coordinator knows what is on the screen: the hint reports itself there, so the promo window and the milestone toasts wait.
        effect(() => {
            const visible = this.visible();
            untracked(() => this.attention.report('hint', visible));
        });
        // Another page (the learner went elsewhere) or the anchor leaving the page after it was there ends the hint.
        effect(() => {
            const state = this.state();
            const url = this.url();
            const ready = this.phase() === 'ready';
            untracked(() => {
                if (state === null) { this.wasReady = false; return; }
                if (state.url !== null && url !== state.url) { this.dismiss(); return; }
                if (ready) this.wasReady = true;
                else if (this.wasReady) this.dismiss();
            });
        });
        inject(DestroyRef).onDestroy(() => { this.clearTimer(); this.attention.report('hint', false); });
    }

    /**
     * Asks for a hint. `false` when a louder surface is on the screen: the learner is taken to the place all the same, without it. A request
     * replaces the one before. The wait for the anchor starts at {@link arm}, after the navigation to the place has ended.
     */
    begin(request: PathHintRequest): boolean {
        this.clearTimer();
        this.wasReady = false;
        this.focusPending.set(false);
        if (!this.attention.canShow('hint')) { this.state.set(null); return false; }
        this.state.set({ id: ++this.counter, request, url: null, timedOut: false });
        this.focusPending.set(true);
        return true;
    }

    /** The navigation to the place ended: from here the page is the hint's page, and the anchor has {@link HINT_ANCHOR_WAIT_MS} to appear. */
    arm(): void {
        const state = this.state();
        if (state === null || state.url !== null) return;
        this.state.set({ ...state, url: this.router.url });
        this.clearTimer();
        const id = state.id;
        this.timer = setTimeout(() => {
            this.timer = null;
            const current = this.state();
            if (current !== null && current.id === id) this.state.set({ ...current, timedOut: true });
        }, HINT_ANCHOR_WAIT_MS);
    }

    /** The hint is over (acknowledged, the action was done, another page, or sign-out). */
    dismiss(): void {
        this.clearTimer();
        this.wasReady = false;
        this.focusPending.set(false);
        this.state.set(null);
    }

    /** The card took the focus: the page heading may take it again after the next route change. */
    focused(): void {
        this.focusPending.set(false);
    }

    private clearTimer(): void {
        if (this.timer !== null) { clearTimeout(this.timer); this.timer = null; }
    }
}
