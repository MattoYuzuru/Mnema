import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { spyObj } from '../../../testing/mocks';
import { AuthService } from '../../auth.service';
import { DURING_STUDY_STORAGE_KEY } from '../notifications/notification-preferences';
import { AppNotification } from '../notifications/notification.models';
import { QuietZone } from '../notifications/quiet-zone';
import { ToastService } from '../notifications/toast.service';
import { AttentionCoordinator } from './attention-coordinator';
import { JourneyStore } from './journey.store';
import { OnboardingView, Rhythm } from './onboarding.models';
import { PathPreferences } from './path-preferences';
import { MILESTONE_TOAST_WINDOW_MS, PathToasts } from './path-toasts';
import { TourEngine } from './tour-engine.service';

const note = (n: number, key = 'FIRST_DECK', done = n, total = 12, overrides: Partial<AppNotification> = {}): AppNotification => ({
    notificationId: `0a000000-0000-4000-8000-${String(n).padStart(12, '0')}`, seq: String(n), kind: 'PATH_MILESTONE', severity: 'INFO',
    params: { key, done, total }, route: 'PATH', createdAt: '2026-10-22T10:00:00Z', expiresAt: '2026-11-21T10:00:00Z', ...overrides
});
const LINK = { label: 'Открыть «Путь»', commands: ['/profile', 'path'] };

describe('PathToasts (the milestone toast policy)', () => {
    let toasts: PathToasts;
    let quiet: QuietZone;
    let notify: ReturnType<typeof vi.fn>;
    let load: ReturnType<typeof vi.fn>;
    const tourActive = signal(false);
    const view = signal<OnboardingView | null>(null);
    const account = signal<{ accountId: string } | null>({ accountId: 'acc-1' });

    const flush = async (ms = 0): Promise<void> => { TestBed.tick(); await vi.advanceTimersByTimeAsync(ms); TestBed.tick(); };

    const configure = (): void => {
        TestBed.configureTestingModule({
            providers: [provideRouter([{ path: '**', children: [] }]),
                { provide: ToastService, useValue: spyObj<ToastService>({ notify }) },
                { provide: TourEngine, useValue: spyObj<TourEngine>({ active: tourActive, paused: signal(false) }) },
                { provide: JourneyStore, useValue: { load, view, error: signal(null), journey: signal(null) } },
                { provide: AuthService, useValue: { status: signal('authenticated'), user: account } }]
        });
        toasts = TestBed.inject(PathToasts);
        quiet = TestBed.inject(QuietZone);
        TestBed.tick();
    };
    /** A new injector with «Во время занятия» set to `mode` (the preference is read when the service starts). */
    const withMode = (mode: string): void => {
        localStorage.setItem(DURING_STUDY_STORAGE_KEY, mode);
        TestBed.resetTestingModule();
        notify.mockClear();
        configure();
    };

    beforeEach(() => {
        localStorage.clear();
        vi.useFakeTimers();
        tourActive.set(false);
        view.set(null);
        account.set({ accountId: 'acc-1' });
        notify = vi.fn();
        load = vi.fn(() => Promise.resolve(null));
        configure();
    });
    afterEach(() => { vi.useRealTimers(); localStorage.clear(); });

    it('shows the milestone as the ordinary sentence with the link to «Путь», at once when nothing is in the way', async () => {
        toasts.arrived(note(1, 'MEDIA_IN_MATERIAL', 6, 12));
        await flush();
        expect(notify).toHaveBeenCalledExactlyOnceWith(note(1).notificationId, 'Путь: вы добавили в материал картинку или звук · 6 из 12', 'INFO', LINK);
    });

    it('shows at most one toast per 30 s: what arrives inside the window waits and leaves as one summary when it ends', async () => {
        toasts.arrived(note(1, 'GOAL_TOLD', 1));
        await flush();
        expect(notify).toHaveBeenCalledTimes(1);
        await flush(10_000);
        toasts.arrived(note(2, 'FIRST_DECK', 2));
        await flush(5_000);
        toasts.arrived(note(3, 'FIRST_SESSION', 3));
        await flush(MILESTONE_TOAST_WINDOW_MS - 15_001);
        expect(notify).toHaveBeenCalledTimes(1);
        await flush(1);
        expect(notify).toHaveBeenCalledTimes(2);
        expect(notify).toHaveBeenLastCalledWith(`path-summary-${note(3).notificationId}`, 'Путь: отмечено 2 пункта · 3 из 12', 'INFO', LINK);
    });

    it('shows the ordinary sentence when only one milestone waited out the window, and a fresh one at once after a quiet window', async () => {
        toasts.arrived(note(1, 'GOAL_TOLD', 1));
        await flush();
        toasts.arrived(note(2, 'FIRST_DECK', 2));
        await flush(MILESTONE_TOAST_WINDOW_MS);
        expect(notify).toHaveBeenLastCalledWith(note(2).notificationId, 'Путь: у вас первая колода · 2 из 12', 'INFO', LINK);
        await flush(MILESTONE_TOAST_WINDOW_MS);
        toasts.arrived(note(3, 'FIRST_SESSION', 3));
        await flush();
        expect(notify).toHaveBeenCalledTimes(3);
    });

    it('merges the milestones of one poll into one summary at once, not a toast and a summary', async () => {
        toasts.arrived(note(1, 'GOAL_TOLD', 1), note(2, 'FIRST_DECK', 2), note(3, 'FIRST_SESSION', 3));
        await flush();
        expect(notify).toHaveBeenCalledExactlyOnceWith(`path-summary-${note(3).notificationId}`, 'Путь: отмечено 3 пункта · 3 из 12', 'INFO', LINK);
        await flush(MILESTONE_TOAST_WINDOW_MS * 2);
        expect(notify).toHaveBeenCalledTimes(1);
    });

    describe('the study session', () => {
        it.each(['AT_PAUSES', 'IMMEDIATE'])('holds a milestone while an answer is open, whatever «Во время занятия» says (%s), and shows it when the zone clears', async mode => {
            withMode(mode);
            quiet.set(true);
            toasts.arrived(note(1, 'OWN_MATERIAL', 4));
            await flush(60_000);
            expect(notify).not.toHaveBeenCalled();
            quiet.set(false);
            await flush();
            expect(notify).toHaveBeenCalledExactlyOnceWith(note(1).notificationId, 'Путь: вы написали свой материал · 4 из 12', 'INFO', LINK);
        });

        it('merges what came in while it was held into one summary', async () => {
            quiet.set(true);
            toasts.arrived(note(1, 'OWN_MATERIAL', 4));
            toasts.arrived(note(2, 'MEDIA_IN_MATERIAL', 5));
            toasts.arrived(note(3, 'CAPTURE_NOTE', 6));
            await flush(2_000);
            expect(notify).not.toHaveBeenCalled();
            quiet.set(false);
            await flush();
            expect(notify).toHaveBeenCalledExactlyOnceWith(`path-summary-${note(3).notificationId}`, 'Путь: отмечено 3 пункта · 6 из 12', 'INFO', LINK);
        });

        it('drops a milestone raised during a task when the learner chose «Только значок» (the inbox still lists it)', async () => {
            withMode('BADGE_ONLY');
            quiet.set(true);
            toasts.arrived(note(1));
            quiet.set(false);
            await flush(MILESTONE_TOAST_WINDOW_MS);
            expect(notify).not.toHaveBeenCalled();
            // Outside a task the same preference shows the toast.
            toasts.arrived(note(2));
            await flush();
            expect(notify).toHaveBeenCalledTimes(1);
        });
    });

    describe('the coordinator', () => {
        it.each([
            ['the tour', (on: boolean) => tourActive.set(on)],
            ['the first entry', (on: boolean) => TestBed.inject(AttentionCoordinator).report('first-entry', on)],
            ['an in-place hint', (on: boolean) => TestBed.inject(AttentionCoordinator).report('hint', on)],
            ['the promo window', (on: boolean) => TestBed.inject(AttentionCoordinator).report('promo', on)]
        ])('waits while %s is on the screen and speaks when it is gone', async (_name, set) => {
            set(true);
            toasts.arrived(note(1, 'TWO_DAYS', 8));
            await flush(40_000);
            expect(notify).not.toHaveBeenCalled();
            set(false);
            await flush();
            expect(notify).toHaveBeenCalledExactlyOnceWith(note(1).notificationId, 'Путь: вы позанимались во второй день · 8 из 12', 'INFO', LINK);
        });
    });

    describe('«Скрыть «Путь»»', () => {
        it('shows no milestone toast to a learner who hid the path, and forgets what was waiting', async () => {
            const preferences = TestBed.inject(PathPreferences);
            quiet.set(true);
            toasts.arrived(note(1));
            preferences.setHidden(true);
            await flush();
            quiet.set(false);
            await flush(MILESTONE_TOAST_WINDOW_MS);
            expect(notify).not.toHaveBeenCalled();
            toasts.arrived(note(2));
            await flush(MILESTONE_TOAST_WINDOW_MS);
            expect(notify).not.toHaveBeenCalled();
            preferences.setHidden(false);
            toasts.arrived(note(3));
            await flush();
            expect(notify).toHaveBeenCalledTimes(1);
        });
    });

    describe('what is not a toast', () => {
        it('ignores params that do not fit and a notification that is already waiting', async () => {
            quiet.set(true);
            toasts.arrived(note(1, 'NEW_ONE'));
            toasts.arrived(note(2, 'GOAL_TOLD', 9, 8));
            toasts.arrived(note(3, 'FIRST_DECK', 2));
            toasts.arrived(note(3, 'FIRST_DECK', 2));
            quiet.set(false);
            await flush();
            expect(notify).toHaveBeenCalledExactlyOnceWith(note(3).notificationId, 'Путь: у вас первая колода · 2 из 12', 'INFO', LINK);
        });

        it('forgets everything on sign-out: nothing of the old account is shown, and the window starts over', async () => {
            toasts.arrived(note(1, 'GOAL_TOLD', 1));
            await flush();
            toasts.arrived(note(2, 'FIRST_DECK', 2));
            toasts.reset();
            await flush(MILESTONE_TOAST_WINDOW_MS);
            expect(notify).toHaveBeenCalledTimes(1);
            toasts.arrived(note(3, 'FIRST_SESSION', 3));
            await flush();
            expect(notify).toHaveBeenCalledTimes(2);
        });
    });

    it('asks the journey to read the view again when a milestone notification arrives', () => {
        toasts.noteArrival();
        expect(load).toHaveBeenCalledExactlyOnceWith({ refresh: true });
    });
    describe('the quiet toast of the met weekly goal (OB-401)', () => {
        const RHYTHM: Rhythm = { weekStart: '2026-10-19', today: '2026-10-22', target: 3, days: ['2026-10-19', '2026-10-20'], weeksMet: 0 };
        const MET: Rhythm = { ...RHYTHM, days: ['2026-10-19', '2026-10-20', '2026-10-22'] };
        const MESSAGE = 'Цель недели выполнена: вы занимались 3\u00a0дня.';
        const show = (rhythm: Rhythm | null): void => { view.set({ enabled: true, journey: null, milestones: [], path: null, rhythm, sampleDeck: { available: false, deckId: null, updateAvailable: false, freeChecksLeft: 3 } }); TestBed.tick(); };
        const rhythmToasts = (): unknown[][] => notify.mock.calls.filter(call => String(call[0]).startsWith('rhythm-met-'));

        it('shows it when a newer view of the same week reaches the target the previous view was below, with no link and as information', async () => {
            show(RHYTHM);
            await flush();
            show(MET);
            await flush();
            expect(notify).toHaveBeenCalledExactlyOnceWith('rhythm-met-2026-10-19', MESSAGE, 'INFO', null);
        });

        it('says the days of the view that met it, in the right form of «день»', async () => {
            show({ ...RHYTHM, target: 5, days: ['2026-10-19', '2026-10-20', '2026-10-21', '2026-10-22'] });
            show({ ...RHYTHM, target: 5, days: ['2026-10-19', '2026-10-20', '2026-10-21', '2026-10-22', '2026-10-23'] });
            await flush();
            expect(notify).toHaveBeenCalledExactlyOnceWith('rhythm-met-2026-10-19', 'Цель недели выполнена: вы занимались 5\u00a0дней.', 'INFO', null);
        });

        it('shows nothing for the first view of the session: a page loaded with the week already met is no celebration', async () => {
            show(MET);
            await flush(MILESTONE_TOAST_WINDOW_MS);
            expect(notify).not.toHaveBeenCalled();
            show({ ...MET, days: [...MET.days, '2026-10-23'] });
            await flush(MILESTONE_TOAST_WINDOW_MS);
            expect(notify).not.toHaveBeenCalled();
        });

        it('shows nothing while the week stays below the target, or a view arrives that changes nothing', async () => {
            show(RHYTHM);
            show({ ...RHYTHM, days: [...RHYTHM.days] });
            show({ ...RHYTHM, today: '2026-10-23' });
            await flush(MILESTONE_TOAST_WINDOW_MS);
            expect(notify).not.toHaveBeenCalled();
        });

        it('is for the same week only: a new week that starts with a met target (a target of one) is not the crossing of the old one', async () => {
            show({ ...RHYTHM, target: 1, days: [] });
            show({ ...RHYTHM, target: 1, weekStart: '2026-10-26', today: '2026-10-26', days: ['2026-10-26'] });
            await flush();
            expect(notify).not.toHaveBeenCalled();
        });

        it('is no celebration when the target changed: a target lowered in the profile to what the week already has, or raised and met', async () => {
            show(RHYTHM);
            show({ ...RHYTHM, target: 2 });
            await flush(MILESTONE_TOAST_WINDOW_MS);
            expect(notify).not.toHaveBeenCalled();
            // The new target is the baseline: reaching it from here, unchanged, is the crossing.
            show({ ...RHYTHM, target: 3 });
            show({ ...MET, target: 3 });
            await flush();
            expect(rhythmToasts()).toHaveLength(1);
        });

        it('announces a week once: a raised and then met target in the same week is not announced again', async () => {
            show(RHYTHM);
            show(MET);
            await flush(MILESTONE_TOAST_WINDOW_MS);
            show({ ...MET, target: 5 });
            show({ ...MET, target: 5, days: [...MET.days, '2026-10-23', '2026-10-24'] });
            await flush(MILESTONE_TOAST_WINDOW_MS);
            expect(rhythmToasts()).toHaveLength(1);
        });

        it('announces the next week again', async () => {
            show(RHYTHM);
            show(MET);
            await flush(MILESTONE_TOAST_WINDOW_MS);
            show({ ...RHYTHM, weekStart: '2026-10-26', today: '2026-10-26', days: ['2026-10-26'] });
            show({ ...RHYTHM, weekStart: '2026-10-26', today: '2026-10-28', days: ['2026-10-26', '2026-10-27', '2026-10-28'] });
            await flush();
            expect(rhythmToasts().map(call => call[0])).toEqual(['rhythm-met-2026-10-19', 'rhythm-met-2026-10-26']);
        });

        it('starts over after a view without a rhythm (the flag was off for a moment): the next view is a first view', async () => {
            show(RHYTHM);
            show(null);
            show(MET);
            await flush(MILESTONE_TOAST_WINDOW_MS);
            expect(notify).not.toHaveBeenCalled();
        });

        it('waits while an answer is open and shows once, when the zone clears', async () => {
            show(RHYTHM);
            quiet.set(true);
            show(MET);
            await flush(60_000);
            expect(notify).not.toHaveBeenCalled();
            quiet.set(false);
            await flush();
            expect(notify).toHaveBeenCalledExactlyOnceWith('rhythm-met-2026-10-19', MESSAGE, 'INFO', null);
        });

        it.each([
            ['the tour', (on: boolean) => tourActive.set(on)],
            ['the first entry', (on: boolean) => TestBed.inject(AttentionCoordinator).report('first-entry', on)],
            ['an in-place hint', (on: boolean) => TestBed.inject(AttentionCoordinator).report('hint', on)]
        ])('waits for %s to leave the screen', async (_name, set) => {
            show(RHYTHM);
            set(true);
            show(MET);
            await flush(40_000);
            expect(notify).not.toHaveBeenCalled();
            set(false);
            await flush();
            expect(notify).toHaveBeenCalledTimes(1);
        });

        it('shares the 30 s window with milestone toasts: the milestone leaves first, this one in the next window, never merged into the summary', async () => {
            show(RHYTHM);
            quiet.set(true);
            toasts.arrived(note(1, 'FIRST_SESSION', 3));
            show(MET);
            quiet.set(false);
            await flush();
            expect(notify).toHaveBeenCalledExactlyOnceWith(note(1).notificationId, 'Путь: вы прошли первое занятие · 3 из 12', 'INFO', LINK);
            await flush(MILESTONE_TOAST_WINDOW_MS - 1);
            expect(notify).toHaveBeenCalledTimes(1);
            await flush(1);
            expect(notify).toHaveBeenCalledTimes(2);
            expect(notify).toHaveBeenLastCalledWith('rhythm-met-2026-10-19', MESSAGE, 'INFO', null);
        });

        it('waits out a milestone toast that was just shown, and a milestone that comes after it waits out this one', async () => {
            toasts.arrived(note(1, 'GOAL_TOLD', 1));
            await flush();
            show(RHYTHM);
            show(MET);
            await flush(MILESTONE_TOAST_WINDOW_MS - 1);
            expect(rhythmToasts()).toHaveLength(0);
            await flush(1);
            expect(rhythmToasts()).toHaveLength(1);
            toasts.arrived(note(2, 'FIRST_DECK', 2));
            await flush(MILESTONE_TOAST_WINDOW_MS - 1);
            expect(notify).toHaveBeenCalledTimes(2);
            await flush(1);
            expect(notify).toHaveBeenCalledTimes(3);
        });

        it('is not silenced by hiding «Путь» on the device: it is the weekly goal, not a milestone; the milestones waiting are forgotten', async () => {
            const preferences = TestBed.inject(PathPreferences);
            show(RHYTHM);
            quiet.set(true);
            toasts.arrived(note(1));
            show(MET);
            preferences.setHidden(true);
            quiet.set(false);
            await flush();
            expect(notify).toHaveBeenCalledExactlyOnceWith('rhythm-met-2026-10-19', MESSAGE, 'INFO', null);
            await flush(MILESTONE_TOAST_WINDOW_MS * 2);
            expect(notify).toHaveBeenCalledTimes(1);
        });

        it('forgets the previous account on sign-out: its week is not the baseline of the next', async () => {
            show(RHYTHM);
            toasts.reset();
            show(MET);
            await flush(MILESTONE_TOAST_WINDOW_MS);
            expect(notify).not.toHaveBeenCalled();
        });
    });
});
