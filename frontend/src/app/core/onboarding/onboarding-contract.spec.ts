import { HttpHeaders, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';

import contract from '../../../../../contracts/onboarding/journey.json';
import { isSampleNotShareable } from './sample-deck';
import { OnboardingApiService, parseOnboardingView, validateJourneyCommand, validatePreferencesCommand, validateSampleDeckCommand } from './onboarding-api.service';
import {
    BRANCHES,
    BRANCH_ACCEPTED,
    EXPERIENCES,
    JOURNEY_ACTIONS,
    JOURNEY_STATES,
    JourneyCommand,
    OnboardingProtocolError,
    PreferencesCommand,
    SAMPLE_FREE_AI_CHECKS,
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
            expect(WEEKLY_TARGET.default).toBe(contract.constants.weeklyTarget.default);
            expect([...WEEKLY_TARGET.surveyOptions]).toEqual(contract.constants.weeklyTarget.surveyOptions);
        });

        it('has the free AI checks of the sample deck of the contract', () => {
            expect(SAMPLE_FREE_AI_CHECKS).toBe(contract.constants.sampleDeck.freeAiChecks);
            for (const [name, view] of Object.entries(contract.views)) {
                expect(view.sampleDeck.freeChecksLeft, name).toBeGreaterThanOrEqual(0);
                expect(view.sampleDeck.freeChecksLeft, name).toBeLessThanOrEqual(contract.constants.sampleDeck.freeAiChecks);
            }
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

        it('names the sample copy only in a view where the sample is available', () => {
            const ready = parseOnboardingView(contract.views.sampleReady);
            expect(ready.sampleDeck).toEqual({ available: true, deckId: '33333333-3333-4333-8333-333333333333', updateAvailable: false, freeChecksLeft: 2 });
            expect(ready.journey).toMatchObject({ branch: 'SAMPLE', stepKey: 'sample.hub' });
        });

        it('rejects a view with an unknown member or a journey without enabled onboarding', () => {
            expect(() => parseOnboardingView({ ...contract.views.offered, extra: 1 })).toThrow();
            expect(() => parseOnboardingView({ ...contract.views.disabled, journey: contract.views.offered.journey })).toThrow();
            expect(() => parseOnboardingView({ ...contract.views.offered, journey: null })).toThrow();
        });
    });

    describe('rhythm', () => {
        const week = contract.views.rhythmWeek;
        const rhythm = week.rhythm;
        const viewWith = (patch: Record<string, unknown>): unknown => ({ ...week, rhythm: { ...rhythm, ...patch } });

        it('parses the week of the fixture: the Monday, today and the studied days of the week, ascending, with the weeks in rhythm', () => {
            const parsed = parseOnboardingView(week);
            expect(parsed.rhythm).toEqual({ weekStart: '2026-10-19', today: '2026-10-22', target: 5, days: ['2026-10-19', '2026-10-20', '2026-10-22'], weeksMet: 2 });
            expect(parsed.rhythm?.target).toBe(parsed.journey?.weeklyTarget);
        });

        it('takes no rhythm from the views whose flag is off', () => {
            for (const [name, view] of Object.entries(contract.views).filter(([name]) => name !== 'rhythmWeek')) expect(parseOnboardingView(view).rhythm, name).toBeNull();
        });

        it('accepts the edges: a week without a session, a full week, today on Monday and on Sunday, a target of one and seven', () => {
            expect(parseOnboardingView(viewWith({ days: [] })).rhythm?.days).toEqual([]);
            const all = ['2026-10-19', '2026-10-20', '2026-10-21', '2026-10-22', '2026-10-23', '2026-10-24', '2026-10-25'];
            expect(parseOnboardingView(viewWith({ days: all, target: 7, today: '2026-10-25' })).rhythm?.days).toEqual(all);
            expect(parseOnboardingView(viewWith({ today: '2026-10-19', days: ['2026-10-19'], target: 1, weeksMet: 0 })).rhythm?.target).toBe(1);
        });

        it.each<[string, Record<string, unknown>]>([
            ['a week that does not start on a Monday', { weekStart: '2026-10-20' }],
            ['a week start that is not a date', { weekStart: '2026-02-30' }],
            ['a week start with a time', { weekStart: '2026-10-19T00:00:00Z' }],
            ['today before the week', { today: '2026-10-18' }],
            ['today after the week', { today: '2026-10-26' }],
            ['today that is not a date', { today: 'today' }],
            ['a studied day before the week', { days: ['2026-10-18', '2026-10-19'] }],
            ['a studied day after the week', { days: ['2026-10-19', '2026-10-26'] }],
            ['days that descend', { days: ['2026-10-22', '2026-10-20'] }],
            ['a day twice', { days: ['2026-10-20', '2026-10-20'] }],
            ['days that are not a list', { days: '2026-10-20' }],
            ['more than seven days', { days: ['2026-10-19', '2026-10-20', '2026-10-21', '2026-10-22', '2026-10-23', '2026-10-24', '2026-10-25', '2026-10-25'] }],
            ['a target of zero', { target: 0 }],
            ['a target of eight', { target: 8 }],
            ['a fractional target', { target: 2.5 }],
            ['a target as text', { target: '5' }],
            ['negative weeks in rhythm', { weeksMet: -1 }],
            ['fractional weeks in rhythm', { weeksMet: 1.5 }],
            ['weeks in rhythm as text', { weeksMet: '2' }],
            ['a member the contract does not have', { streak: 4 }]
        ])('refuses %s as a protocol error', (_name, patch) => {
            expect(() => parseOnboardingView(viewWith(patch))).toThrow(OnboardingProtocolError);
        });

        it.each(['weekStart', 'today', 'target', 'days', 'weeksMet'])('refuses a rhythm without %s', key => {
            const { [key]: _removed, ...rest } = rhythm as Record<string, unknown>;
            expect(() => parseOnboardingView({ ...week, rhythm: rest })).toThrow(OnboardingProtocolError);
        });

        it('refuses a rhythm that is not an object', () => {
            for (const value of [[], 'week', 3, true]) expect(() => parseOnboardingView({ ...week, rhythm: value })).toThrow(OnboardingProtocolError);
        });

        it('sends the preferences of an account without a tour (row version 0) as they are, and the client accepts them', async () => {
            const command = contract.commands.preferencesWithoutTour;
            expect(command.rowVersion).toBe('0');
            expect(() => validatePreferencesCommand(command)).not.toThrow();
            const result = firstValueFrom(api.preferences(command));
            const request = http.expectOne('/api/onboarding/preferences');
            expect(request.request.body).toEqual(command);
            request.flush(week, { headers });
            await expect(result).resolves.toMatchObject({ view: { rhythm: { target: 5 } } });
        });
    });

    describe('problems', () => {
        it('has the codes the client acts on: the sample copy cannot be made, and a sample deck cannot be shared', () => {
            const { sampleUnavailable, sampleNotShareable } = contract.problems;
            expect([sampleUnavailable.status, sampleUnavailable.code]).toEqual([409, 'SAMPLE_DECK_UNAVAILABLE']);
            expect(isSampleNotShareable(sampleNotShareable.status, sampleNotShareable.code)).toBe(true);
            expect(isSampleNotShareable(sampleUnavailable.status, sampleUnavailable.code)).toBe(false);
            expect(isSampleNotShareable(403, sampleNotShareable.code)).toBe(false);
        });
    });

    describe('commands', () => {
        it('sends the sampleDeck command as it is, by POST, and takes the copy from the 201 view', async () => {
            const result = firstValueFrom(api.createSampleDeck(contract.commands.sampleDeck));
            const request = http.expectOne('/api/onboarding/sample-deck');
            expect(request.request.method).toBe('POST');
            expect(request.request.body).toEqual(contract.commands.sampleDeck);
            request.flush(contract.views.sampleReady, { status: 201, statusText: 'Created', headers });
            await expect(result).resolves.toMatchObject({ view: { sampleDeck: { deckId: contract.views.sampleReady.sampleDeck.deckId } } });
            expect(() => validateSampleDeckCommand(contract.commands.sampleDeck)).not.toThrow();
        });

        for (const [name, command] of Object.entries(contract.commands).filter(([name]) => name !== 'sampleDeck')) {
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
