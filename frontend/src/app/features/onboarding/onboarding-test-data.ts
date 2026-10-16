import { Provider, computed, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';

import contract from '../../../../../contracts/onboarding/journey.json';
import { AuthService } from '../../auth.service';
import { ToastService } from '../../core/notifications/toast.service';
import { JourneyStore } from '../../core/onboarding/journey.store';
import { ActionOptions, Journey, JourneyAction, OnboardingView, StepKey } from '../../core/onboarding/onboarding.models';
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

/**
 * A stand-in for `JourneyStore` that keeps the journey the way the server's transition table does, so a spec can follow a learner through
 * the commands and a «reload» (a new service over the same journey) sees what the commands left.
 */
export class FakeJourneyStore {
    readonly view = signal<OnboardingView | null>(null);
    readonly journey = computed(() => this.view()?.journey ?? null);
    readonly enabled = computed(() => this.view()?.enabled === true);
    readonly loading = signal(false);
    readonly error = signal<'load' | 'command' | null>(null);
    readonly calls: [JourneyAction, ActionOptions | undefined][] = [];
    readonly preferences: unknown[] = [];
    failNext = false;

    constructor(journey: Partial<Journey> | null = null, overrides: Partial<OnboardingView> = {}) {
        this.view.set(viewOf(journey, overrides));
    }

    load = vi.fn(() => Promise.resolve(this.view()));
    reset = vi.fn(() => this.view.set(null));

    act = vi.fn((action: JourneyAction, options?: ActionOptions): Promise<OnboardingView | null> => {
        this.calls.push([action, options]);
        if (this.failNext) { this.failNext = false; return Promise.resolve(null); }
        const journey = this.journey();
        if (journey === null) return Promise.resolve(null);
        const patch: Partial<Journey> = {};
        switch (action) {
            case 'START': Object.assign(patch, { state: 'IN_PROGRESS', stepKey: options?.stepKey ?? null }); break;
            case 'STEP': Object.assign(patch, { stepKey: options?.stepKey ?? null, branch: options?.branch ?? journey.branch }); break;
            case 'PAUSE': Object.assign(patch, { state: 'PAUSED', stepKey: options?.stepKey ?? journey.stepKey }); break;
            case 'RESUME': Object.assign(patch, { state: 'IN_PROGRESS', stepKey: options?.stepKey ?? journey.stepKey }); break;
            case 'SKIP': Object.assign(patch, { state: 'SKIPPED' }); break;
            case 'COMPLETE': Object.assign(patch, { state: 'COMPLETED', finishedAt: '2026-10-20T10:00:00Z' }); break;
            case 'REPLAY': Object.assign(patch, { state: 'IN_PROGRESS', stepKey: options?.stepKey ?? null, branch: null }); break;
            default: break;
        }
        this.view.set({ ...this.view()!, journey: { ...journey, ...patch, rowVersion: String(Number(journey.rowVersion) + 1) } });
        return Promise.resolve(this.view());
    });

    setPreferences = vi.fn((changes: { experience?: Journey['experience']; weeklyTarget?: number | null }): Promise<OnboardingView | null> => {
        this.preferences.push(changes);
        const journey = this.journey();
        if (journey === null || this.failNext) { this.failNext = false; return Promise.resolve(null); }
        this.view.set({ ...this.view()!, journey: { ...journey, ...changes } as Journey });
        return Promise.resolve(this.view());
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
    interests: { onboarding: ReturnType<typeof signal<boolean>>; ensure: ReturnType<typeof vi.fn>; save: ReturnType<typeof vi.fn>; profile: ReturnType<typeof signal<unknown>>; busy: ReturnType<typeof signal<boolean>>; error: ReturnType<typeof signal<string>> };
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
    const interests = { onboarding: signal(options.interestsOpen ?? true), ensure: vi.fn(), save: vi.fn(), profile: signal<unknown>(null), busy: signal(false), error: signal('') };
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
