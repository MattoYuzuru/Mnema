import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';

import contract from '../../../../../contracts/onboarding/journey.json';
import { spyObj } from '../../../testing/mocks';
import { AuthService } from '../../auth.service';
import { QuietZone } from '../notifications/quiet-zone';
import { SURFACES, Surface, AttentionCoordinator } from './attention-coordinator';
import { JourneyStore } from './journey.store';
import { Journey } from './onboarding.models';
import { TourEngine } from './tour-engine.service';

const journeyOf = (overrides: Partial<Journey>): Journey => ({ ...(contract.views.offered.journey as Journey), ...overrides });

describe('AttentionCoordinator', () => {
    let coordinator: AttentionCoordinator;
    let quiet: QuietZone;
    const active = signal(false);
    const paused = signal(false);
    const journey = signal<Journey | null>(null);
    const view = signal<object | null>(null);
    const error = signal<'load' | 'command' | null>(null);
    const authStatus = signal<'authenticated' | 'unauthenticated'>('unauthenticated');

    beforeEach(() => {
        active.set(false);
        paused.set(false);
        journey.set(null);
        view.set(null);
        error.set(null);
        authStatus.set('unauthenticated');
        TestBed.configureTestingModule({
            providers: [provideRouter([{ path: '**', children: [] }]),
                { provide: TourEngine, useValue: spyObj<TourEngine>({ active, paused }) },
                { provide: JourneyStore, useValue: { journey, view, error } }, { provide: AuthService, useValue: { status: authStatus } }]
        });
        coordinator = TestBed.inject(AttentionCoordinator);
        quiet = TestBed.inject(QuietZone);
    });
    afterEach(() => vi.useRealTimers());

    const all = (): Surface[] => SURFACES.filter(surface => coordinator.canShow(surface));

    it('lets everything speak while nothing is on the screen', () => {
        expect(all()).toEqual([...SURFACES]);
        expect(coordinator.speaking()).toBeNull();
        expect(coordinator.visible()).toEqual([]);
    });

    it('keeps the order system error > study > tour > first entry > promo > milestone', () => {
        expect([...SURFACES]).toEqual(['system-error', 'study', 'tour', 'first-entry', 'promo', 'milestone']);
    });

    it('lets only the highest surface on the screen speak, whatever else is reported', () => {
        coordinator.report('promo', true);
        coordinator.report('first-entry', true);
        expect(coordinator.speaking()).toBe('first-entry');
        coordinator.report('system-error', true);
        expect(coordinator.speaking()).toBe('system-error');
        expect(coordinator.visible()).toEqual(['system-error', 'first-entry', 'promo']);
        coordinator.report('system-error', false);
        coordinator.report('first-entry', false);
        expect(coordinator.speaking()).toBe('promo');
    });

    it('never allows a surface under a higher one, and a surface does not block itself', () => {
        const hold = (surface: Surface, on: boolean): void => {
            if (surface === 'study') quiet.set(on);
            else if (surface === 'tour') active.set(on);
            else coordinator.report(surface, on);
        };
        for (const [index, holder] of SURFACES.entries()) {
            hold(holder, true);
            expect(SURFACES.filter(surface => coordinator.canShow(surface)), holder).toEqual(SURFACES.slice(0, index + 1));
            hold(holder, false);
        }
    });

    describe('the study session', () => {
        it('is a task open in the quiet zone: no tour, first entry, promo or toast over it', () => {
            quiet.set(true);
            expect(all()).toEqual(['system-error', 'study']);
            expect(coordinator.speaking()).toBe('study');
            quiet.set(false);
            expect(all()).toEqual([...SURFACES]);
        });

        it('keeps the first entry off the study route itself, but not the promo window after a session', async () => {
            await TestBed.inject(Router).navigateByUrl('/decks/1/study');
            expect(coordinator.canShow('first-entry')).toBe(false);
            expect(coordinator.canShow('promo')).toBe(true);
            await TestBed.inject(Router).navigateByUrl('/decks');
            expect(coordinator.canShow('first-entry')).toBe(true);
        });
    });

    describe('the tour', () => {
        it('is on the screen while it runs, and keeps the first entry and the promo window away', () => {
            active.set(true);
            expect(coordinator.speaking()).toBe('tour');
            expect(all()).toEqual(['system-error', 'study', 'tour']);
        });

        it('leaves the screen when it is paused: a plaque is not a loud surface', () => {
            active.set(true);
            paused.set(true);
            expect(coordinator.visible()).toEqual([]);
            expect(coordinator.canShow('first-entry')).toBe(true);
        });
    });

    describe('promo and the journey', () => {
        it.each(['IN_PROGRESS', 'PAUSED'] as const)('is not asked for while the journey is %s', state => {
            journey.set(journeyOf({ state }));
            expect(coordinator.canShow('promo')).toBe(false);
            expect(coordinator.canShow('first-entry')).toBe(true);
        });

        it.each(['OFFERED', 'NOT_OFFERED', 'SKIPPED', 'COMPLETED'] as const)('is not held back by a %s journey', state => {
            journey.set(journeyOf({ state, autoOffer: false }));
            expect(coordinator.canShow('promo')).toBe(true);
        });

        it('waits for the first entry while the welcome is still to be shown (an auto-offered journey that was not started)', () => {
            journey.set(journeyOf({ state: 'OFFERED', autoOffer: true }));
            expect(coordinator.canShow('promo')).toBe(false);
            journey.set(journeyOf({ state: 'SKIPPED', autoOffer: true }));
            expect(coordinator.canShow('promo')).toBe(true);
        });

        describe('for a signed-in learner', () => {
            beforeEach(() => authStatus.set('authenticated'));

            it('is not asked for before the journey has been read: the first entry may be about to open', () => {
                expect(coordinator.canShow('promo')).toBe(false);
                view.set({});
                journey.set(journeyOf({ state: 'NOT_OFFERED', autoOffer: false }));
                expect(coordinator.canShow('promo')).toBe(true);
            });

            it('is not held back by a journey that failed to read or is switched off', () => {
                error.set('load');
                expect(coordinator.canShow('promo')).toBe(true);
                error.set(null);
                view.set({ enabled: false, journey: null });
                expect(coordinator.canShow('promo')).toBe(true);
            });
        });

        it('is not asked for on the local day the journey finished, and is again the next day', () => {
            vi.useFakeTimers();
            vi.setSystemTime(new Date(2026, 9, 20, 15, 0, 0));
            journey.set(journeyOf({ state: 'COMPLETED', finishedAt: new Date(2026, 9, 20, 9, 30, 0).toISOString() }));
            expect(coordinator.canShow('promo')).toBe(false);
            vi.setSystemTime(new Date(2026, 9, 21, 0, 5, 0));
            expect(coordinator.canShow('promo')).toBe(true);
        });

        it('lets a journey that is not there (guest, feature off, not read yet) hold nothing back', () => {
            journey.set(null);
            expect(coordinator.canShow('promo')).toBe(true);
        });

        it('grants the promo window and the first entry only when nothing above them is up, so the promo, the survey and the tour never speak together', () => {
            for (const tour of [false, true]) {
                for (const firstEntry of [false, true]) {
                    active.set(tour);
                    coordinator.report('first-entry', firstEntry);
                    expect(coordinator.canShow('first-entry'), `first entry with tour=${tour}`).toBe(!tour);
                    expect(coordinator.canShow('promo'), `promo with tour=${tour}, first entry=${firstEntry}`).toBe(!tour && !firstEntry);
                }
            }
        });
    });
});
