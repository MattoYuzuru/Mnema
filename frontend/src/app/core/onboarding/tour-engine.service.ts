import { DestroyRef, Injectable, InjectionToken, Injector, afterNextRender, computed, effect, inject, signal, untracked } from '@angular/core';
import { Router } from '@angular/router';

import { ToastService } from '../notifications/toast.service';
import { JourneyStore } from './journey.store';
import { ActionOptions, JourneyAction, OnboardingView, STEP_BRANCH, isRefused } from './onboarding.models';
import { onboardingText } from './onboarding-texts';
import { TourAnchors } from './tour-anchors';
import { TourEvents } from './tour-events';
import { TourDefinition, TourEnd, TourStep, isActionGate } from './tour.models';

/** How long the engine waits for a step's anchor before the step shows its text variant. */
export const ANCHOR_WAIT_MS = 4_000;

/** Where the engine records progress: the `JourneyStore` in the app, a stand-in in the styleguide and in specs. */
export interface TourPersistence {
    act(action: JourneyAction, options?: ActionOptions): Promise<unknown>;
}

export const TOUR_PERSISTENCE = new InjectionToken<TourPersistence>('TOUR_PERSISTENCE', { providedIn: 'root', factory: () => inject(JourneyStore) });

/** `locating`: the anchor is not on the screen yet; `ready`: it is; `missing`: it did not appear within {@link ANCHOR_WAIT_MS}. */
export type TourPhase = 'locating' | 'ready' | 'missing';

export interface TourStartOptions {
    /** Where progress is recorded instead of the default (a demonstration or a test). */
    readonly persistence?: TourPersistence;
    /** Start folded into the pause plaque, recording nothing and going nowhere: the journey is already paused (another device paused it). */
    readonly paused?: boolean;
}

interface Run {
    readonly definition: TourDefinition;
    readonly persistence: TourPersistence;
}

/**
 * The state machine of the guided tour. It owns what the learner is on (`step`, `index`, `paused`, `phase`) and records progress
 * (STEP on every change, PAUSE and RESUME, COMPLETE on finish); it draws nothing: `TourHost` renders the veil, the card and the
 * pause plaque from these signals. The first step of a run is recorded by whoever started the journey (START or REPLAY), not here.
 * `active` is true from `start` until the run ends, including while paused.
 *
 * An action-gated step ends when the target component emits the step's event through `TourEvents`, never on a DOM click. A step
 * whose anchor does not appear within four seconds shows its text variant and never blocks the learner.
 */
@Injectable({ providedIn: 'root' })
export class TourEngine {
    private readonly anchors = inject(TourAnchors);
    private readonly events = inject(TourEvents);
    private readonly router = inject(Router);
    private readonly toasts = inject(ToastService);
    private readonly injector = inject(Injector);

    private readonly run = signal<Run | null>(null);
    private readonly position = signal(0);
    private readonly pausedState = signal(false);
    private readonly preparingState = signal(false);
    private readonly phaseState = signal<TourPhase>('locating');
    private readonly focusCounter = signal(0);
    private readonly endState = signal<TourEnd | null>(null);
    private readonly pauseCounter = signal(0);

    readonly definition = computed(() => this.run()?.definition ?? null);
    /** A tour is in progress (paused or not). */
    readonly active = computed(() => this.run() !== null);
    readonly paused = this.pausedState.asReadonly();
    /** The step is waiting for the app to prepare something (a copied deck): the card says so and the anchor timer does not run. */
    readonly preparing = this.preparingState.asReadonly();
    readonly phase = this.phaseState.asReadonly();
    /** Zero-based; «Шаг N из M» shows `index + 1`. */
    readonly index = this.position.asReadonly();
    /** The steps the learner counts: «Шаг N из M» leaves the external finale screens out. */
    readonly total = computed(() => this.definition()?.steps.filter(step => step.external !== true).length ?? 0);
    readonly step = computed<TourStep | null>(() => this.definition()?.steps[this.position()] ?? null);
    /** The element of the current step's anchor, once registered. */
    readonly target = computed(() => {
        const step = this.step();
        return step === null ? null : this.anchors.lookup(step.anchor);
    });
    /** Changes whenever focus should return to the card heading: a new step, or the tour resumed. */
    readonly focusRequest = this.focusCounter.asReadonly();
    /** Counts the pauses the learner (or a gate) asked for: the plaque takes the focus then, and not when a tour starts already folded. */
    readonly pauseRequest = this.pauseCounter.asReadonly();
    /** «Назад» is not offered before a step whose action was done: going back would ask for it again (a second deck, a second copy). */
    readonly canGoBack = computed(() => {
        const steps = this.definition()?.steps ?? [];
        const index = this.position();
        return index > 0 && !isActionGate(steps[index - 1].gate);
    });
    /** How the last run ended; a page reads it to show what follows (the congratulation after `completed`). */
    readonly lastEnd = this.endState.asReadonly();

    private waitTimer: ReturnType<typeof setTimeout> | null = null;
    private stopGate: (() => void) | null = null;
    private gateMetWhilePaused = false;

    constructor() {
        effect(() => {
            const step = this.step();
            const target = this.target();
            const paused = this.pausedState();
            const preparing = this.preparingState();
            untracked(() => this.reconcile(step, target, paused, preparing));
        });
        inject(DestroyRef).onDestroy(() => this.teardown());
    }

    /** Starts `definition` at `fromKey` (the first step by default). A run in progress is replaced without recording anything. */
    start(definition: TourDefinition, fromKey?: TourStep['key'], options: TourStartOptions = {}): void {
        if (definition.steps.length === 0) return;
        this.teardown();
        const found = fromKey === undefined ? -1 : definition.steps.findIndex(step => step.key === fromKey);
        this.endState.set(null);
        this.position.set(Math.max(0, found));
        this.run.set({ definition, persistence: options.persistence ?? this.injector.get(TOUR_PERSISTENCE) });
        // A tour that starts paused goes nowhere: the learner is wherever they are (even in a study session) until they continue.
        this.enterStep(options.paused !== true);
        if (options.paused === true) this.pausedState.set(true);
    }

    next(): void {
        const run = this.run();
        if (run === null || this.pausedState()) return;
        if (this.position() + 1 >= run.definition.steps.length) this.end('completed');
        else this.goTo(this.position() + 1);
    }

    back(): void {
        if (this.run() === null || this.pausedState() || !this.canGoBack()) return;
        this.goTo(this.position() - 1);
    }

    /** Escape: the tour folds into the pause plaque. Progress is kept; `resume` returns to the same step. */
    pause(): void {
        const run = this.run();
        const step = this.step();
        if (run === null || step === null || this.pausedState()) return;
        this.pausedState.set(true);
        this.pauseCounter.update(count => count + 1);
        this.record(run, 'PAUSE', { stepKey: step.key });
    }

    resume(): void {
        const run = this.run();
        const step = this.step();
        if (run === null || step === null || !this.pausedState()) return;
        this.pausedState.set(false);
        this.record(run, 'RESUME', { stepKey: step.key });
        this.focusCounter.update(count => count + 1);
        // The plaque brings the learner back to the place of the step, wherever the pause left them.
        this.navigateTo(step);
        if (this.gateMetWhilePaused) {
            this.gateMetWhilePaused = false;
            this.next();
        }
    }

    /**
     * Continues a paused tour at another step than the one it paused on, and records RESUME there: after the study session the tour
     * goes on at the finale, while the plaque of a session left midway still returns to the step it paused on.
     */
    resumeAt(key: TourStep['key']): void {
        const run = this.run();
        if (run === null || !this.pausedState()) return;
        const index = run.definition.steps.findIndex(step => step.key === key);
        if (index < 0) return;
        this.position.set(index);
        this.enterStep();
        this.record(run, 'RESUME', { stepKey: key });
    }

    /** «Завершить знакомство» (after the hold): records COMPLETE and says so in a toast. */
    finish(): void {
        this.end('finished');
    }

    /** Takes the tour down without recording anything (the page left, the learner signed out). */
    stop(): void {
        const run = this.run();
        if (run === null) return;
        this.teardown();
        this.endState.set({ definitionId: run.definition.id, reason: 'stopped' });
    }

    setPreparing(value: boolean): void {
        this.preparingState.set(value);
    }

    private goTo(index: number): void {
        const run = this.run();
        if (run === null) return;
        this.position.set(index);
        const step = run.definition.steps[index];
        const branch = STEP_BRANCH[step.key];
        this.record(run, 'STEP', { stepKey: step.key, ...(run.definition.branch !== null && branch === run.definition.branch ? { branch } : {}) });
        this.enterStep();
    }

    private navigateTo(step: TourStep): void {
        const route = typeof step.route === 'function' ? step.route() : step.route;
        if (route !== undefined && route !== null && this.router.url !== route) this.router.navigateByUrl(route).catch(() => undefined);
    }

    private enterStep(navigate = true): void {
        const step = this.step();
        this.clearWait();
        this.stopGate?.();
        this.stopGate = null;
        this.gateMetWhilePaused = false;
        this.phaseState.set('locating');
        this.pausedState.set(false);
        this.focusCounter.update(count => count + 1);
        if (step === null) return;
        if (navigate) this.navigateTo(step);
        if (isActionGate(step.gate)) {
            const { action, pauses } = step.gate;
            this.stopGate = this.events.listen(event => {
                if (event !== action) return false;
                if (pauses === true) this.pause();
                else if (this.pausedState()) this.gateMetWhilePaused = true;
                else this.next();
                // The page that published the event leaves the next place to the tour (it goes there itself).
                return true;
            });
        }
    }

    private end(reason: 'finished' | 'completed'): void {
        const run = this.run();
        if (run === null) return;
        this.teardown();
        this.endState.set({ definitionId: run.definition.id, reason });
        this.record(run, 'COMPLETE', {});
        // The toast waits until the card is gone: an open dialog holds back toasts.
        if (reason === 'finished') afterNextRender(() => this.toasts.echo(onboardingText('tour.finish.toast')), { injector: this.injector });
    }

    private record(run: Run, action: JourneyAction, options: ActionOptions): void {
        Promise.resolve(run.persistence.act(action, options)).then(result => {
            if (isRefused(result) && this.run() === run) this.follow(result.view);
        }).catch(() => undefined);
    }

    /**
     * The server refused a command and said where the journey really is (another tab moved it, the feature went off): the tour goes
     * there, recording nothing, instead of carrying on as if its step had been taken.
     */
    private follow(view: OnboardingView): void {
        const run = this.run();
        const journey = view.journey;
        if (run === null) return;
        if (!view.enabled || journey === null || (journey.state !== 'IN_PROGRESS' && journey.state !== 'PAUSED')) { this.stop(); return; }
        const index = journey.stepKey === null ? -1 : run.definition.steps.findIndex(step => step.key === journey.stepKey);
        if (index >= 0 && index !== this.position()) { this.position.set(index); this.enterStep(); }
        if (journey.state === 'PAUSED') this.pausedState.set(true);
        else if (this.pausedState()) { this.pausedState.set(false); this.focusCounter.update(count => count + 1); }
    }

    private reconcile(step: TourStep | null, target: HTMLElement | null, paused: boolean, preparing: boolean): void {
        if (step === null || paused) { this.clearWait(); return; }
        if (target !== null) {
            this.clearWait();
            this.phaseState.set('ready');
            return;
        }
        // The anchor is not there: wait for it, but not while the app says it is still preparing the page.
        if (this.phaseState() === 'ready') this.phaseState.set('locating');
        if (preparing) { this.clearWait(); return; }
        if (this.phaseState() === 'locating' && this.waitTimer === null) {
            this.waitTimer = setTimeout(() => {
                this.waitTimer = null;
                if (this.target() === null && !this.preparingState()) this.phaseState.set('missing');
            }, ANCHOR_WAIT_MS);
        }
    }

    private clearWait(): void {
        if (this.waitTimer !== null) clearTimeout(this.waitTimer);
        this.waitTimer = null;
    }

    private teardown(): void {
        this.clearWait();
        this.stopGate?.();
        this.stopGate = null;
        this.gateMetWhilePaused = false;
        this.run.set(null);
        this.position.set(0);
        this.pausedState.set(false);
        this.preparingState.set(false);
        this.phaseState.set('locating');
    }
}
