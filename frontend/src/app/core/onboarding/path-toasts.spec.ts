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
    const account = signal<{ accountId: string } | null>({ accountId: 'acc-1' });

    const flush = async (ms = 0): Promise<void> => { TestBed.tick(); await vi.advanceTimersByTimeAsync(ms); TestBed.tick(); };

    const configure = (): void => {
        TestBed.configureTestingModule({
            providers: [provideRouter([{ path: '**', children: [] }]),
                { provide: ToastService, useValue: spyObj<ToastService>({ notify }) },
                { provide: TourEngine, useValue: spyObj<TourEngine>({ active: tourActive, paused: signal(false) }) },
                { provide: JourneyStore, useValue: { load, view: signal(null), error: signal(null), journey: signal(null) } },
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
});
