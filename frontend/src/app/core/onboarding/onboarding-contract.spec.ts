import { HttpHeaders, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';

import contract from '../../../../../contracts/onboarding/journey.json';
import { OnboardingApiService, parseOnboardingView, validateJourneyCommand, validatePreferencesCommand } from './onboarding-api.service';
import {
    BRANCHES,
    BRANCH_ACCEPTED,
    EXPERIENCES,
    JOURNEY_ACTIONS,
    JOURNEY_STATES,
    JourneyCommand,
    PreferencesCommand,
    STEP_BRANCH,
    STEP_KEYS,
    STEP_KEY_RULE,
    WEEKLY_TARGET
} from './onboarding.models';

/**
 * contracts/onboarding/journey.json is the wire contract shared with the backend: the closed lists, the transition rules, every
 * example view and command must agree with the frontend, so a change on either side fails here first.
 */
describe('Onboarding wire contract (contracts/onboarding/journey.json)', () => {
    const headers = new HttpHeaders({ 'Cache-Control': 'private, no-store', ETag: '"onb-1"' });
    let api: OnboardingApiService;
    let http: HttpTestingController;

    beforeEach(() => {
        TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
        api = TestBed.inject(OnboardingApiService);
        http = TestBed.inject(HttpTestingController);
    });
    afterEach(() => http.verify());

    describe('closed lists', () => {
        it('has the step keys of the registry, in the registry order, each with its branch', () => {
            expect([...STEP_KEYS]).toEqual(contract.steps.map(step => step.key));
            for (const step of contract.steps) expect(STEP_BRANCH[step.key as keyof typeof STEP_BRANCH], step.key).toBe(step.branch);
        });

        it('has the states, actions, branches, experiences and the weekly target of the contract', () => {
            expect([...JOURNEY_STATES]).toEqual(contract.constants.states);
            expect([...JOURNEY_ACTIONS]).toEqual(contract.constants.actions);
            expect([...BRANCHES]).toEqual(contract.constants.branches);
            expect([...EXPERIENCES]).toEqual(contract.constants.experiences);
            expect(WEEKLY_TARGET.min).toBe(contract.constants.weeklyTarget.min);
            expect(WEEKLY_TARGET.max).toBe(contract.constants.weeklyTarget.max);
            expect([...WEEKLY_TARGET.surveyOptions]).toEqual(contract.constants.weeklyTarget.surveyOptions);
        });

        it('takes the step and branch rules of every action from the transition table', () => {
            const table = contract.transitions as unknown as Record<string, { stepKey: string; branch: string }>;
            for (const action of JOURNEY_ACTIONS) {
                expect(STEP_KEY_RULE[action], `${action} stepKey`).toBe(table[action].stepKey);
                expect(BRANCH_ACCEPTED[action], `${action} branch`).toBe(table[action].branch !== 'forbidden');
            }
        });
    });

    describe('views', () => {
        for (const [name, view] of Object.entries(contract.views)) {
            it(`parses the ${name} view strictly`, async () => {
                expect(parseOnboardingView(view)).toEqual(view);
                const result = firstValueFrom(api.read());
                http.expectOne('/api/onboarding').flush(view, { headers });
                await expect(result).resolves.toEqual({ kind: 'view', view, etag: '"onb-1"' });
            });
        }

        it('rejects a view with an unknown member or a journey without enabled onboarding', () => {
            expect(() => parseOnboardingView({ ...contract.views.offered, extra: 1 })).toThrow();
            expect(() => parseOnboardingView({ ...contract.views.disabled, journey: contract.views.offered.journey })).toThrow();
            expect(() => parseOnboardingView({ ...contract.views.offered, journey: null })).toThrow();
        });
    });

    describe('commands', () => {
        for (const [name, command] of Object.entries(contract.commands)) {
            it(`sends the ${name} command as it is`, async () => {
                const preferences = !('action' in command);
                const result = firstValueFrom(preferences ? api.preferences(command as PreferencesCommand) : api.command(command as JourneyCommand));
                const request = http.expectOne(preferences ? '/api/onboarding/preferences' : '/api/onboarding/journey');
                expect(request.request.method).toBe('PUT');
                expect(request.request.body).toEqual(command);
                request.flush(contract.views.offered, { headers });
                await result;
            });
        }

        for (const [name, command] of Object.entries(contract.invalid)) {
            it(`refuses the ${name} command before it is sent`, () => {
                const preferences = !('action' in command);
                const check = (): unknown => preferences ? validatePreferencesCommand(command as PreferencesCommand) : validateJourneyCommand(command as JourneyCommand);
                // `unknownField` carries a PAUSE with a stray member; `emptyPreferences` and `weeklyTargetOutOfRange` have no action.
                expect(check, name).toThrow();
            });
        }
    });
});
