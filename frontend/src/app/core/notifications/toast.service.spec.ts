import { TestBed } from '@angular/core/testing';

import { NotificationPreferences } from './notification-preferences';
import { QuietZone } from './quiet-zone';
import { ECHO_MS, MAX_VISIBLE_TOASTS, TOAST_MS, ToastService } from './toast.service';

describe('ToastService', () => {
    let toasts: ToastService;
    let quiet: QuietZone;
    let preferences: NotificationPreferences;

    beforeEach(() => {
        localStorage.clear();
        vi.useFakeTimers();
        toasts = TestBed.inject(ToastService);
        quiet = TestBed.inject(QuietZone);
        preferences = TestBed.inject(NotificationPreferences);
        TestBed.tick();
    });
    afterEach(() => {
        vi.useRealTimers();
        document.querySelectorAll('dialog').forEach(dialog => dialog.remove());
        localStorage.clear();
    });

    const ids = (): string[] => toasts.visible().map(toast => toast.id);

    it('closes INFO and WARNING after 6 s and echoes after 3 s, but keeps an ERROR until it is closed', () => {
        toasts.notify('info', 'Готово', 'INFO', null);
        toasts.notify('warn', 'Внимание', 'WARNING', null);
        toasts.notify('error', 'Ошибка', 'ERROR', null);
        toasts.echo('Материал одобрен');
        expect(ids()).toHaveLength(MAX_VISIBLE_TOASTS);

        vi.advanceTimersByTime(TOAST_MS - 1);
        expect(ids()).toEqual(['error', 'warn', 'info']);
        vi.advanceTimersByTime(1);
        // Both timed toasts left, so the echo that waited behind them is admitted now with its own 3 s.
        expect(ids()).toEqual(['echo-0', 'error']);
        vi.advanceTimersByTime(ECHO_MS);
        vi.advanceTimersByTime(60_000);
        expect(ids()).toEqual(['error']);
        toasts.close('error');
        expect(ids()).toEqual([]);
    });

    it('shows at most three, newest first, and admits the next one when a slot frees up', () => {
        for (const id of ['a', 'b', 'c', 'd', 'e']) toasts.notify(id, `Текст ${id}`, 'ERROR', null);
        expect(ids()).toEqual(['c', 'b', 'a']);
        expect(toasts.queuedCount()).toBe(2);
        toasts.close('b');
        expect(ids()).toEqual(['d', 'c', 'a']);
        expect(toasts.queuedCount()).toBe(1);
    });

    it('never shows one notification twice', () => {
        toasts.notify('same', 'Текст', 'ERROR', null);
        toasts.notify('same', 'Текст', 'ERROR', null);
        expect(toasts.visible()).toHaveLength(1);
        expect(toasts.queuedCount()).toBe(0);
    });

    it('pauses the timers on hover and on focus and resumes with the time that was left', () => {
        toasts.notify('hover', 'Текст', 'INFO', null);
        vi.advanceTimersByTime(4000);
        toasts.setHovered(true);
        vi.advanceTimersByTime(60_000);
        expect(ids()).toEqual(['hover']);
        toasts.setHovered(false);
        vi.advanceTimersByTime(1999);
        expect(ids()).toEqual(['hover']);
        vi.advanceTimersByTime(1);
        expect(ids()).toEqual([]);

        toasts.notify('focus', 'Текст', 'WARNING', null);
        toasts.setFocused(true);
        vi.advanceTimersByTime(60_000);
        expect(ids()).toEqual(['focus']);
        toasts.setFocused(false);
        vi.advanceTimersByTime(TOAST_MS);
        expect(ids()).toEqual([]);
    });

    it('pauses while the tab is hidden', () => {
        const hidden = vi.spyOn(document, 'hidden', 'get').mockReturnValue(true);
        toasts.notify('hidden', 'Текст', 'INFO', null);
        document.dispatchEvent(new Event('visibilitychange'));
        vi.advanceTimersByTime(60_000);
        expect(ids()).toEqual(['hidden']);
        hidden.mockReturnValue(false);
        document.dispatchEvent(new Event('visibilitychange'));
        vi.advanceTimersByTime(TOAST_MS);
        expect(ids()).toEqual([]);
    });

    it('holds notification toasts in a quiet zone until the pause, but not the echo of the user\'s own action', () => {
        quiet.set(true);
        TestBed.tick();
        toasts.notify('held', 'Готово', 'INFO', null);
        toasts.echo('Сохранено');
        expect(ids()).toEqual(['echo-0']);
        expect(toasts.queuedCount()).toBe(1);

        quiet.set(false);
        TestBed.tick();
        expect(ids()).toEqual(['held', 'echo-0']);
        expect(toasts.queuedCount()).toBe(0);
    });

    it('shows at once with «сразу» and drops toasts with «только значок»', () => {
        quiet.set(true);
        preferences.setDuringStudy('IMMEDIATE');
        TestBed.tick();
        toasts.notify('now', 'Готово', 'INFO', null);
        expect(ids()).toEqual(['now']);

        preferences.setDuringStudy('BADGE_ONLY');
        TestBed.tick();
        toasts.notify('badge', 'Готово', 'INFO', null);
        quiet.set(false);
        TestBed.tick();
        expect(ids()).toEqual(['now']);
        expect(toasts.queuedCount()).toBe(0);
    });

    it('releases held toasts when the preference changes to «сразу»', () => {
        quiet.set(true);
        TestBed.tick();
        toasts.notify('held', 'Готово', 'INFO', null);
        expect(ids()).toEqual([]);
        preferences.setDuringStudy('IMMEDIATE');
        TestBed.tick();
        expect(ids()).toEqual(['held']);
    });

    it('waits while a modal dialog is open and shows the toast when it closes', () => {
        const dialog = document.createElement('dialog');
        dialog.setAttribute('open', '');
        document.body.append(dialog);
        toasts.notify('modal', 'Готово', 'INFO', null);
        expect(ids()).toEqual([]);

        dialog.removeAttribute('open');
        dialog.dispatchEvent(new Event('close'));
        expect(ids()).toEqual(['modal']);
    });

    it('re-checks on a timer when a modal disappears without a close event', () => {
        const dialog = document.createElement('dialog');
        dialog.setAttribute('open', '');
        document.body.append(dialog);
        toasts.notify('modal', 'Готово', 'INFO', null);
        dialog.remove();
        expect(ids()).toEqual([]);
        vi.advanceTimersByTime(1000);
        expect(ids()).toEqual(['modal']);
    });

    it('announces through its own queue, one message at a time', () => {
        toasts.notify('one', 'Первое', 'INFO', null);
        toasts.notify('two', 'Второе', 'INFO', null);
        expect(toasts.announcement()).toBe('Первое');
        vi.advanceTimersByTime(1500);
        expect(toasts.announcement()).toBe('');
        vi.advanceTimersByTime(250);
        expect(toasts.announcement()).toBe('Второе');
        vi.advanceTimersByTime(1500);
        expect(toasts.announcement()).toBe('');
    });

    it('clears notification toasts on sign-out but lets an echo finish', () => {
        toasts.notify('n', 'Готово', 'ERROR', null);
        toasts.echo('Сохранено');
        toasts.clearNotifications();
        expect(ids()).toEqual(['echo-0']);
    });

    it('clears only learner announcement correlations and preserves a queued local echo', () => {
        toasts.notify('one', 'Old learner message', 'ERROR', null);
        toasts.notify('two', 'Queued learner message', 'ERROR', null);
        toasts.echo('Own action saved');
        expect(toasts.announcement()).toBe('Old learner message');
        toasts.clearNotifications();
        expect(toasts.announcement()).toBe('Own action saved');
        expect(ids()).toEqual(['echo-0']);
        vi.advanceTimersByTime(1750);
        expect(toasts.announcement()).toBe('');
    });

    it('preserves an already announced echo while removing later learner messages', () => {
        toasts.echo('Own action');
        toasts.notify('one', 'Learner message', 'ERROR', null);
        toasts.clearNotifications();
        expect(toasts.announcement()).toBe('Own action');
        vi.advanceTimersByTime(1750);
        expect(toasts.announcement()).toBe('');
        expect(ids()).toEqual(['echo-0']);
    });
});
