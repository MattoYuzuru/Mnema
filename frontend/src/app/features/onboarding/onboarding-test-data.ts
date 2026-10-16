import { Provider, computed, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';

import contract from '../../../../../contracts/onboarding/journey.json';
import { AuthService } from '../../auth.service';
import { ToastService } from '../../core/notifications/toast.service';
import { JourneyStore } from '../../core/onboarding/journey.store';
import { ActionOptions, Journey, JourneyAction, JourneyResult, OnboardingView, StepKey } from '../../core/onboarding/onboarding.models';
import { TOUR_PERSISTENCE, TourEngine } from '../../core/onboarding/tour-engine.service';
import { TourEvents } from '../../core/onboarding/tour-events';
import { COMMUNITY_LAUNCH } from '../../shared/community-launch';
import { CAPABILITIES_UNAVAILABLE, CapabilitiesApiService, LearningCapabilities } from '../authoring/capabilities-api.service';
import { CommunityApiService } from '../community/community-api.service';
import { InterestStore } from '../community/interest-store.service';
import { LearningGoalStore } from '../goal/learning-goal.store';
import { OwnDecksApiService } from '../own-decks/own-decks-api.service';
import { OnboardingFlow } from './onboarding-flow.service';

/** Specs only. */
export const OFFERED_VIEW = contract.views.offered as OnboardingView;

export function viewOf(journey: Partial<Journey> | null, overrides: Partial<OnboardingView> = {}): OnboardingView {
    return {
        ...OFFERED_VIEW, enabled: journey !== null,
        journey: journey === null ? null : { ...(OFFERED_VIEW.journey as Journey), ...journey }, ...overrides
    };
}

interface TransitionRule {
    readonly from: Readonly<Record<string, string>>;
    readonly stepKey: 'required' | 'optional' | 'forbidden';
    readonly branch: 'optional' | 'forbidden';
    readonly sets?: readonly string[];
    readonly clears?: readonly string[];
}

const TRANSITIONS = contract.transitions as unknown as Readonly<Record<JourneyAction, TransitionRule>>;
const STEP_BRANCH = new Map<string, string | null>(contract.steps.map(step => [step.key, step.branch]));

/** Why a command is not applied, so a spec can tell a rule from a failure: `transition` is the server's 409, `invalid` its 400. */
export type Rejection = { readonly action: JourneyAction | 'PREFERENCES'; readonly reason: 'transition' | 'invalid' | 'failure' };

/**
 * A stand-in for `JourneyStore` that keeps the journey the way the server does (`contracts/onboarding/journey.json`): a command from a
 * state the transition table does not list is refused with the current view (the 409), a step the registry does not know, a step of
 * another branch, or a field the table forbids fails (the 400), and the version moves with every applied command. A spec can follow a
 * learner through the commands, and a «reload» (a new service over the same journey) sees what the commands left.
 */
export class FakeJourneyStore {
    readonly view = signal<OnboardingView | null>(null);
    readonly journey = computed(() => this.view()?.journey ?? null);
    readonly enabled = computed(() => this.view()?.enabled === true);
    readonly loading = signal(false);
    readonly error = signal<'load' | 'command' | null>(null);
    readonly calls: [JourneyAction, ActionOptions | undefined][] = [];
    readonly rejections: Rejection[] = [];
    readonly preferences: unknown[] = [];
    failNext = false;

    constructor(journey: Partial<Journey> | null = null, overrides: Partial<OnboardingView> = {}) {
        this.view.set(viewOf(journey, overrides));
    }

    load = vi.fn(() => Promise.resolve(this.view()));
    reset = vi.fn(() => this.view.set(null));

    act = vi.fn((action: JourneyAction, options?: ActionOptions): Promise<JourneyResult> => {
        this.calls.push([action, options]);
        const current = this.view();
        const journey = this.journey();
        if (current === null || journey === null) return Promise.resolve({ status: 'failed' });
        if (this.failNext) { this.failNext = false; this.rejections.push({ action, reason: 'failure' }); return Promise.resolve({ status: 'failed' }); }
        const rule = TRANSITIONS[action];
        const to = rule.from[journey.state];
        if (to === undefined) { this.rejections.push({ action, reason: 'transition' }); return Promise.resolve({ status: 'refused', view: current }); }
        const stepKey = options?.stepKey;
        const branch = options?.branch;
        const effectiveBranch = branch ?? (action === 'REPLAY' ? null : journey.branch);
        const known = stepKey === undefined || STEP_BRANCH.has(stepKey);
        const stepBranch = stepKey === undefined || !known ? null : STEP_BRANCH.get(stepKey) ?? null;
        // A step of a branch needs that branch; a step without one takes no `branch` at all (the server refuses it).
        const matches = stepKey === undefined || !known || (stepBranch === null ? branch === undefined : stepBranch === effectiveBranch);
        const allowed = (rule.stepKey !== 'required' || stepKey !== undefined) && (rule.stepKey !== 'forbidden' || stepKey === undefined)
            && (rule.branch !== 'forbidden' || branch === undefined);
        if (!known || !matches || !allowed) { this.rejections.push({ action, reason: 'invalid' }); return Promise.resolve({ status: 'failed' }); }
        const patch: { -readonly [K in keyof Journey]?: Journey[K] } = { state: to as Journey['state'], stepKey: stepKey ?? journey.stepKey };
        if (branch !== undefined) patch.branch = branch;
        if (rule.sets?.includes('finishedAt') === true) patch.finishedAt = '2026-10-20T10:00:00Z';
        if (rule.clears?.includes('finishedAt') === true) patch.finishedAt = null;
        if (action === 'REPLAY') Object.assign(patch, { branch: null, finishedAt: null, replayCount: journey.replayCount + 1, eligible: journey.state === 'NOT_OFFERED' ? false : journey.eligible });
        if (action === 'SKIP' || action === 'COMPLETE' || action === 'UNDO_FINISH') patch.stepKey = action === 'UNDO_FINISH' ? journey.stepKey : null;
        const next = { ...current, journey: { ...journey, ...patch, rowVersion: String(Number(journey.rowVersion) + 1) } };
        this.view.set(next);
        return Promise.resolve({ status: 'applied', view: next });
    });

    setPreferences = vi.fn((changes: { experience?: Journey['experience']; weeklyTarget?: number | null }): Promise<JourneyResult> => {
        this.preferences.push(changes);
        const current = this.view();
        const journey = this.journey();
        if (current === null || journey === null || this.failNext) { this.failNext = false; return Promise.resolve({ status: 'failed' }); }
        // There is no row to write the answers to until the journey exists: the server answers 409 with the current view.
        if (journey.state === 'NOT_OFFERED') { this.rejections.push({ action: 'PREFERENCES', reason: 'transition' }); return Promise.resolve({ status: 'refused', view: current }); }
        const target = changes.weeklyTarget;
        if (typeof target === 'number' && (target < contract.constants.weeklyTarget.min || target > contract.constants.weeklyTarget.max)) {
            return Promise.resolve({ status: 'failed' });
        }
        const next = { ...current, journey: { ...journey, ...changes, rowVersion: String(Number(journey.rowVersion) + 1) } as Journey };
        this.view.set(next);
        return Promise.resolve({ status: 'applied', view: next });
    });

    steps(): (StepKey | null)[] {
        return this.calls.filter(([action]) => action === 'STEP' || action === 'START').map(([, options]) => options?.stepKey ?? null);
    }
}

const AI_ON: LearningCapabilities = { ...CAPABILITIES_UNAVAILABLE, aiGeneration: { available: true, reason: null } };

export interface Setup {
    flow: OnboardingFlow;
    store: FakeJourneyStore;
    engine: TourEngine;
    events: TourEvents;
    goal: { state: ReturnType<typeof signal<string>>; answered: ReturnType<typeof signal<boolean>>; load: ReturnType<typeof vi.fn>; answer: ReturnType<typeof vi.fn> };
    interests: { onboarding: ReturnType<typeof signal<boolean>>; ensure: ReturnType<typeof vi.fn>; ready: ReturnType<typeof signal<boolean>>; save: ReturnType<typeof vi.fn>; skip: ReturnType<typeof vi.fn>; profile: ReturnType<typeof signal<unknown>>; busy: ReturnType<typeof signal<boolean>>; error: ReturnType<typeof signal<string>> };
    toasts: { notify: ReturnType<typeof vi.fn> };
    community: { facets: ReturnType<typeof vi.fn> };
    capabilities: { read: ReturnType<typeof vi.fn> };
    decks: { list: ReturnType<typeof vi.fn> };
}

export function setup(options: { journey?: Parameters<typeof viewOf>[0]; view?: Partial<typeof OFFERED_VIEW>; ai?: boolean; listed?: number | 'error'; launched?: boolean; goalAnswered?: boolean; interestsOpen?: boolean; providers?: Provider[] } = {}): Setup {
    const store = new FakeJourneyStore(options.journey === undefined ? {} : options.journey, options.view ?? {});
    const goal = {
        state: signal('ready'), answered: signal(options.goalAnswered ?? false), load: vi.fn(() => Promise.resolve()),
        answer: vi.fn(() => Promise.resolve(true))
    };
    const interests = { onboarding: signal(options.interestsOpen ?? true), ensure: vi.fn(), ready: signal(true), save: vi.fn(), skip: vi.fn(() => Promise.resolve()), profile: signal<unknown>(null), busy: signal(false), error: signal('') };
    const toasts = { notify: vi.fn(), echo: vi.fn() };
    const community = { facets: vi.fn(() => options.listed === 'error' ? throwError(() => new Error('down')) : of({ listedCount: options.listed ?? 10 })) };
    const capabilities = { read: vi.fn(() => of(options.ai === true ? AI_ON : CAPABILITIES_UNAVAILABLE)) };
    const decks = { list: vi.fn(() => of({ items: [], nextCursor: null })) };
    TestBed.configureTestingModule({
        providers: [provideRouter([]),
            { provide: JourneyStore, useValue: store }, { provide: TOUR_PERSISTENCE, useExisting: JourneyStore },
            { provide: LearningGoalStore, useValue: goal }, { provide: InterestStore, useValue: interests },
            { provide: ToastService, useValue: toasts }, { provide: CommunityApiService, useValue: community },
            { provide: CapabilitiesApiService, useValue: capabilities }, { provide: OwnDecksApiService, useValue: decks },
            { provide: COMMUNITY_LAUNCH, useValue: options.launched ?? true }, { provide: AuthService, useValue: { status: signal('authenticated'), user: signal({ accountId: 'a' }) } }, ...(options.providers ?? [])]
    });
    return { flow: TestBed.inject(OnboardingFlow), store, engine: TestBed.inject(TourEngine), events: TestBed.inject(TourEvents), goal, interests, toasts, community, capabilities, decks } as unknown as Setup;
}
