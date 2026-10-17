import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { Observable, Subject, of, throwError } from 'rxjs';

import contract from '../../../../../contracts/onboarding/journey.json';
import { spyObj } from '../../../testing/mocks';
import { COMMAND_RETRY_DELAYS_MS, JourneyStore } from './journey.store';
import { OnboardingApiService } from './onboarding-api.service';
import { OnboardingProtocolError, OnboardingRead, OnboardingReadResult, OnboardingView } from './onboarding.models';

const offered = contract.views.offered as OnboardingView;
const paused = contract.views.paused as OnboardingView;
const disabled = contract.views.disabled as OnboardingView;
const sampleReady = contract.views.sampleReady as OnboardingView;
/** The offered view with the sample available but no copy yet: what the branch choice holds before the learner picks it. */
const sampleReadyAvailable: OnboardingView = { ...offered, sampleDeck: { ...offered.sampleDeck, available: true } };
const read = (view: OnboardingView, etag: string | null = '"v1"'): OnboardingRead => ({ view, etag });
const failure = (status: number, code?: string): HttpErrorResponse => new HttpErrorResponse({ status, error: code === undefined ? null : { code } });
const withVersion = (view: OnboardingView, rowVersion: string): OnboardingView =>
    ({ ...view, journey: { ...view.journey!, rowVersion } });

describe('JourneyStore', () => {
    let api: {
        read: ReturnType<typeof vi.fn<(etag?: string | null) => Observable<OnboardingReadResult>>>;
        command: ReturnType<typeof vi.fn<OnboardingApiService['command']>>;
        preferences: ReturnType<typeof vi.fn<OnboardingApiService['preferences']>>;
        createSampleDeck: ReturnType<typeof vi.fn<OnboardingApiService['createSampleDeck']>>;
    };
    let store: JourneyStore;

    beforeEach(() => {
        vi.useFakeTimers();
        api = { read: vi.fn(), command: vi.fn(), preferences: vi.fn(), createSampleDeck: vi.fn() };
        TestBed.configureTestingModule({ providers: [{ provide: OnboardingApiService, useValue: spyObj<OnboardingApiService>(api) }] });
        store = TestBed.inject(JourneyStore);
    });
    afterEach(() => vi.useRealTimers());

    const loaded = async (view: OnboardingView = offered): Promise<void> => {
        api.read.mockReturnValueOnce(of({ kind: 'view', ...read(view) }));
        await store.load();
    };

    describe('load', () => {
        it('reads once, shows the view through the signals and keeps the loading flag honest', async () => {
            const answer = new Subject<OnboardingReadResult>();
            api.read.mockReturnValue(answer);
            const request = store.load();
            expect(store.loading()).toBe(true);
            answer.next({ kind: 'view', ...read(offered) });
            answer.complete();
            await expect(request).resolves.toEqual(offered);
            expect(store.loading()).toBe(false);
            expect(store.view()).toEqual(offered);
            expect(store.journey()?.state).toBe('OFFERED');
            expect(store.enabled()).toBe(true);
            expect(store.error()).toBeNull();
        });

        it('shares one request between concurrent callers and does not ask again once the view is held', async () => {
            const answer = new Subject<OnboardingReadResult>();
            api.read.mockReturnValue(answer);
            const first = store.load();
            const second = store.load();
            answer.next({ kind: 'view', ...read(offered) });
            answer.complete();
            await Promise.all([first, second]);
            await store.load();
            expect(api.read).toHaveBeenCalledTimes(1);
        });

        it('asks again on refresh with the held ETag, and keeps the view on a 304', async () => {
            await loaded();
            api.read.mockReturnValueOnce(of({ kind: 'not-modified' }));
            await expect(store.load({ refresh: true })).resolves.toEqual(offered);
            expect(api.read).toHaveBeenLastCalledWith('"v1"');
            expect(store.view()).toEqual(offered);
            expect(store.error()).toBeNull();
        });

        it('replaces the view when the refresh brings a new one', async () => {
            await loaded();
            api.read.mockReturnValueOnce(of({ kind: 'view', ...read(paused, '"v2"') }));
            await store.load({ refresh: true });
            expect(store.journey()?.state).toBe('PAUSED');
            api.read.mockReturnValueOnce(of({ kind: 'not-modified' }));
            await store.load({ refresh: true });
            expect(api.read).toHaveBeenLastCalledWith('"v2"');
        });

        it('degrades to no tour on any failure: nothing throws and the error is only a flag', async () => {
            for (const error of [failure(500), failure(0), failure(401), new OnboardingProtocolError('bad')]) {
                api.read.mockReturnValueOnce(throwError(() => error));
                await expect(store.load({ refresh: true })).resolves.toBeNull();
                expect(store.error()).toBe('load');
                expect(store.journey()).toBeNull();
                expect(store.enabled()).toBe(false);
                expect(store.loading()).toBe(false);
            }
        });

        it('clears the error when a later read succeeds', async () => {
            api.read.mockReturnValueOnce(throwError(() => failure(503)));
            await store.load();
            expect(store.error()).toBe('load');
            api.read.mockReturnValueOnce(of({ kind: 'view', ...read(offered) }));
            await store.load({ refresh: true });
            expect(store.error()).toBeNull();
        });

        it('says the feature is off for a disabled view', async () => {
            await loaded(disabled);
            expect(store.enabled()).toBe(false);
            expect(store.journey()).toBeNull();
        });
    });

    describe('act', () => {
        it('sends the current row version with a fresh canonical command id and stores the answer', async () => {
            await loaded(withVersion(offered, '4'));
            api.command.mockReturnValue(of(read(paused, '"v5"')));
            await expect(store.act('START', { stepKey: 'survey.goal' })).resolves.toEqual({ status: 'applied', view: paused });
            const body = api.command.mock.calls[0][0];
            expect(body).toMatchObject({ rowVersion: '4', action: 'START', stepKey: 'survey.goal' });
            expect(body.commandId).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/u);
            expect(store.journey()?.rowVersion).toBe('7');
            expect(store.error()).toBeNull();
        });

        it('loads the view first when none is held', async () => {
            api.read.mockReturnValueOnce(of({ kind: 'view', ...read(offered) }));
            api.command.mockReturnValue(of(read(paused)));
            await store.act('PAUSE');
            expect(api.read).toHaveBeenCalledTimes(1);
            expect(api.command).toHaveBeenCalledTimes(1);
        });

        it('sends the next command with the version the previous answer carried, one command at a time', async () => {
            await loaded(withVersion(offered, '1'));
            api.command.mockReturnValueOnce(of(read(withVersion(offered, '2')))).mockReturnValueOnce(of(read(withVersion(offered, '3'))));
            const first = store.act('STEP', { stepKey: 'sample.hub' });
            const second = store.act('STEP', { stepKey: 'sample.material' });
            await Promise.all([first, second]);
            expect(api.command.mock.calls.map(call => call[0].rowVersion)).toEqual(['1', '2']);
        });

        it('retries a lost answer with the same command id and stops after the delays are used up', async () => {
            await loaded();
            api.command.mockReturnValue(throwError(() => failure(0)));
            const result = store.act('PAUSE');
            await vi.advanceTimersByTimeAsync(COMMAND_RETRY_DELAYS_MS.reduce((sum, delay) => sum + delay, 0));
            await expect(result).resolves.toEqual({ status: 'failed' });
            expect(api.command).toHaveBeenCalledTimes(1 + COMMAND_RETRY_DELAYS_MS.length);
            expect(new Set(api.command.mock.calls.map(call => call[0].commandId)).size).toBe(1);
            expect(store.error()).toBe('command');
        });

        it('succeeds on a retry after a server error and after a malformed success, keeping one command id', async () => {
            await loaded();
            api.command.mockReturnValueOnce(throwError(() => failure(503))).mockReturnValueOnce(throwError(() => new OnboardingProtocolError('bad')))
                .mockReturnValueOnce(of(read(paused)));
            const result = store.act('PAUSE');
            await vi.advanceTimersByTimeAsync(5_000);
            await expect(result).resolves.toEqual({ status: 'applied', view: paused });
            expect(new Set(api.command.mock.calls.map(call => call[0].commandId)).size).toBe(1);
            expect(store.error()).toBeNull();
        });

        it('uses a new command id for the next command', async () => {
            await loaded();
            api.command.mockReturnValue(of(read(offered)));
            await store.act('PAUSE');
            await store.act('PAUSE');
            expect(api.command.mock.calls[0][0].commandId).not.toBe(api.command.mock.calls[1][0].commandId);
        });

        it.each([412, 428, 409])('re-reads on %s and resolves with the fresh view instead of throwing', async status => {
            await loaded();
            api.command.mockReturnValue(throwError(() => failure(status, status === 409 ? 'ONBOARDING_TRANSITION' : undefined)));
            api.read.mockReturnValueOnce(of({ kind: 'view', ...read(paused, '"fresh"') }));
            await expect(store.act('RESUME')).resolves.toEqual({ status: 'refused', view: paused });
            expect(api.command).toHaveBeenCalledTimes(1);
            // The re-read does not echo the stale validator.
            expect(api.read).toHaveBeenLastCalledWith(null);
            expect(store.journey()?.state).toBe('PAUSED');
            expect(store.error()).toBeNull();
        });

        it('follows a switched-off feature: the re-read says disabled', async () => {
            await loaded();
            api.command.mockReturnValue(throwError(() => failure(409, 'ONBOARDING_UNAVAILABLE')));
            api.read.mockReturnValueOnce(of({ kind: 'view', ...read(disabled) }));
            await expect(store.act('PAUSE')).resolves.toEqual({ status: 'refused', view: disabled });
            expect(store.enabled()).toBe(false);
        });

        it('resolves null and flags the error when the re-read fails too', async () => {
            await loaded();
            api.command.mockReturnValue(throwError(() => failure(412)));
            api.read.mockReturnValueOnce(throwError(() => failure(500)));
            await expect(store.act('PAUSE')).resolves.toEqual({ status: 'failed' });
            expect(store.error()).toBe('load');
        });

        it('does not retry a refusal and never throws: the tour is simply not recorded', async () => {
            await loaded();
            api.command.mockReturnValue(throwError(() => failure(400)));
            await expect(store.act('PAUSE')).resolves.toEqual({ status: 'failed' });
            expect(api.command).toHaveBeenCalledTimes(1);
            expect(store.error()).toBe('command');
        });

        it('sends nothing for a disabled view or when the view cannot be read', async () => {
            await loaded(disabled);
            await expect(store.act('PAUSE')).resolves.toEqual({ status: 'failed' });
            expect(api.command).not.toHaveBeenCalled();
        });

        it('refuses a defective command before any request and leaves the later commands working', async () => {
            await loaded();
            await expect(store.act('SKIP', { stepKey: 'welcome' })).resolves.toEqual({ status: 'failed' });
            expect(api.command).not.toHaveBeenCalled();
            api.command.mockReturnValue(of(read(paused)));
            await expect(store.act('PAUSE')).resolves.toEqual({ status: 'applied', view: paused });
        });
    });

    describe('setPreferences', () => {
        it('sends the answers with the current version and retries with the same command id', async () => {
            await loaded(withVersion(offered, '3'));
            api.preferences.mockReturnValueOnce(throwError(() => failure(502))).mockReturnValueOnce(of(read(paused)));
            const result = store.setPreferences({ experience: 'FAMILIAR', weeklyTarget: 5 });
            await vi.advanceTimersByTimeAsync(1_000);
            await expect(result).resolves.toEqual({ status: 'applied', view: paused });
            expect(api.preferences.mock.calls.map(call => call[0])).toEqual([
                expect.objectContaining({ rowVersion: '3', experience: 'FAMILIAR', weeklyTarget: 5 }),
                expect.objectContaining({ rowVersion: '3', experience: 'FAMILIAR', weeklyTarget: 5 })
            ]);
            expect(api.preferences.mock.calls[0][0].commandId).toBe(api.preferences.mock.calls[1][0].commandId);
        });

        it('clears an answer with null and refuses a call with nothing to say', async () => {
            await loaded();
            api.preferences.mockReturnValue(of(read(offered)));
            await store.setPreferences({ weeklyTarget: null });
            expect(api.preferences.mock.calls[0][0]).toMatchObject({ weeklyTarget: null });
            expect(api.preferences.mock.calls[0][0]).not.toHaveProperty('experience');
            await expect(store.setPreferences({})).resolves.toEqual({ status: 'failed' });
            expect(api.preferences).toHaveBeenCalledTimes(1);
        });

        it('re-reads on a stale version', async () => {
            await loaded();
            api.preferences.mockReturnValue(throwError(() => failure(412)));
            api.read.mockReturnValueOnce(of({ kind: 'view', ...read(paused) }));
            await expect(store.setPreferences({ experience: 'NEW' })).resolves.toEqual({ status: 'refused', view: paused });
        });
    });

    describe('createSampleDeck', () => {
        it('asks for the copy with a canonical command id and no row version, and stores the view that names it', async () => {
            await loaded();
            api.createSampleDeck.mockReturnValue(of(read(sampleReady, '"v7"')));
            await expect(store.createSampleDeck()).resolves.toEqual({ status: 'applied', view: sampleReady });
            const body = api.createSampleDeck.mock.calls[0][0];
            expect(Object.keys(body)).toEqual(['commandId']);
            expect(body.commandId).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/u);
            expect(store.view()?.sampleDeck.deckId).toBe(sampleReady.sampleDeck.deckId);
            expect(store.error()).toBeNull();
        });

        it('retries an unknown outcome with the same command id, so a lost answer never makes a second copy', async () => {
            await loaded();
            api.createSampleDeck.mockReturnValueOnce(throwError(() => failure(0))).mockReturnValueOnce(throwError(() => failure(503)))
                .mockReturnValueOnce(of(read(sampleReady)));
            const result = store.createSampleDeck();
            await vi.advanceTimersByTimeAsync(5_000);
            await expect(result).resolves.toEqual({ status: 'applied', view: sampleReady });
            expect(new Set(api.createSampleDeck.mock.calls.map(call => call[0].commandId)).size).toBe(1);
            expect(api.createSampleDeck).toHaveBeenCalledTimes(3);
        });

        it('uses a new command id for the next request (the repeat choice is its own command and gets the live copy)', async () => {
            await loaded();
            api.createSampleDeck.mockReturnValue(of(read(sampleReady)));
            await store.createSampleDeck();
            await store.createSampleDeck();
            expect(api.createSampleDeck.mock.calls[0][0].commandId).not.toBe(api.createSampleDeck.mock.calls[1][0].commandId);
        });

        it('follows SAMPLE_DECK_UNAVAILABLE: the view is read again without the stale validator and says the sample is gone', async () => {
            await loaded(sampleReadyAvailable);
            api.createSampleDeck.mockReturnValue(throwError(() => failure(409, contract.problems.sampleUnavailable.code)));
            api.read.mockReturnValueOnce(of({ kind: 'view', ...read(offered, '"fresh"') }));
            await expect(store.createSampleDeck()).resolves.toEqual({ status: 'refused', view: offered });
            expect(api.read).toHaveBeenLastCalledWith(null);
            expect(store.view()?.sampleDeck.available).toBe(false);
            expect(api.createSampleDeck).toHaveBeenCalledTimes(1);
            expect(store.error()).toBeNull();
        });

        it('follows a switched-off onboarding the same way', async () => {
            await loaded();
            api.createSampleDeck.mockReturnValue(throwError(() => failure(409, 'ONBOARDING_UNAVAILABLE')));
            api.read.mockReturnValueOnce(of({ kind: 'view', ...read(disabled) }));
            await expect(store.createSampleDeck()).resolves.toEqual({ status: 'refused', view: disabled });
            expect(store.enabled()).toBe(false);
        });

        it('does not retry a refusal of the request itself and flags the error', async () => {
            await loaded();
            api.createSampleDeck.mockReturnValue(throwError(() => failure(403)));
            await expect(store.createSampleDeck()).resolves.toEqual({ status: 'failed' });
            expect(api.createSampleDeck).toHaveBeenCalledTimes(1);
            expect(store.error()).toBe('command');
        });

        it('drops the answer that was on its way when the account changed', async () => {
            await loaded();
            const answer = new Subject<OnboardingRead>();
            api.createSampleDeck.mockReturnValue(answer);
            const result = store.createSampleDeck();
            await vi.advanceTimersByTimeAsync(0);
            store.reset();
            answer.next(read(sampleReady, '"previous"'));
            answer.complete();
            await expect(result).resolves.toEqual({ status: 'failed' });
            expect(store.view()).toBeNull();
        });
    });

    describe('reset', () => {
        it('forgets the view and the validator, so the next account starts clean', async () => {
            await loaded();
            store.reset();
            expect(store.view()).toBeNull();
            expect(store.journey()).toBeNull();
            expect(store.enabled()).toBe(false);
            api.read.mockReturnValueOnce(of({ kind: 'view', ...read(paused, '"other"') }));
            await store.load();
            // A refresh after the reset must not echo the previous account's validator.
            api.read.mockReturnValueOnce(of({ kind: 'not-modified' }));
            await store.load({ refresh: true });
            expect(api.read).toHaveBeenLastCalledWith('"other"');
            expect(api.read.mock.calls[1][0]).toBeNull();
        });

        it('drops the answer of a read that began before the sign-out, so the next account never sees the previous journey', async () => {
            const answer = new Subject<OnboardingReadResult>();
            api.read.mockReturnValue(answer);
            const stale = store.load();
            store.reset();
            answer.next({ kind: 'view', ...read(paused, '"previous"') });
            answer.complete();
            await expect(stale).resolves.toBeNull();
            expect(store.view()).toBeNull();
            expect(store.loading()).toBe(false);
            // The next account reads for itself, without the previous account's validator.
            api.read.mockReturnValueOnce(of({ kind: 'view', ...read(offered, '"next"') }));
            await expect(store.load()).resolves.toEqual(offered);
            expect(api.read).toHaveBeenLastCalledWith(null);
        });

        it('drops the answer of a command that was on its way when the account changed, and sends nothing for it afterwards', async () => {
            await loaded();
            const answer = new Subject<OnboardingRead>();
            api.command.mockReturnValue(answer);
            const result = store.act('PAUSE');
            await vi.advanceTimersByTimeAsync(0);
            store.reset();
            answer.next(read(paused, '"previous"'));
            answer.complete();
            await expect(result).resolves.toEqual({ status: 'failed' });
            expect(store.view()).toBeNull();
            expect(store.error()).toBeNull();
        });

        it('drops the answer of a preference write that was on its way when the account changed', async () => {
            await loaded();
            const answer = new Subject<OnboardingRead>();
            api.preferences.mockReturnValue(answer);
            const result = store.setPreferences({ experience: 'NEW' });
            await vi.advanceTimersByTimeAsync(0);
            store.reset();
            answer.next(read(paused, '"previous"'));
            answer.complete();
            await expect(result).resolves.toEqual({ status: 'failed' });
            expect(store.view()).toBeNull();
        });

        it('drops a command that was still waiting behind another one when the account changed: it is sent for nobody', async () => {
            await loaded();
            const first = new Subject<OnboardingRead>();
            api.command.mockReturnValueOnce(first).mockReturnValue(of(read(paused, '"other"')));
            const running = store.act('PAUSE');
            const queued = store.act('RESUME');
            await vi.advanceTimersByTimeAsync(0);
            store.reset();
            // The next account is loaded before the queued command gets its turn.
            api.read.mockReturnValueOnce(of({ kind: 'view', ...read(offered, '"next"') }));
            await store.load();
            first.next(read(paused, '"previous"'));
            first.complete();
            await expect(running).resolves.toEqual({ status: 'failed' });
            await expect(queued).resolves.toEqual({ status: 'failed' });
            expect(api.command).toHaveBeenCalledTimes(1);
            expect(store.journey()?.state).toBe('OFFERED');
        });
    });
});
