import { HttpErrorResponse } from '@angular/common/http';
import { signal, WritableSignal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';

import { AuthService, AuthStatus } from '../../auth.service';
import { spyObj, type SpyObj } from '../../../testing/mocks';
import { ACTIVE_POLL_MS, IDLE_POLL_MS, NotificationCenter } from './notification-center';
import { AppNotification, NotificationListResult, NotificationPage } from './notification.models';
import { NotificationsApiService } from './notifications-api.service';
import { QuietZone } from './quiet-zone';
import { ToastService } from './toast.service';

const DECK = '11111111-1111-4111-8111-111111111111';
const note = (seq: number, overrides: Partial<AppNotification> = {}): AppNotification => ({
    notificationId: `0a000000-0000-4000-8000-${String(seq).padStart(12, '0')}`, seq: String(seq), kind: 'GENERATION_READY',
    severity: 'INFO', params: { deckId: DECK, sessionId: DECK, sessionKind: 'MATERIALS', artifactCount: seq, approvableCount: seq },
    route: 'DECK', createdAt: '2026-10-02T09:00:00Z', expiresAt: '2026-11-01T09:00:00Z', ...overrides
});
const page = (items: AppNotification[], overrides: Partial<NotificationPage> = {}): NotificationListResult => ({
    kind: 'page', etag: `"etag-${overrides.unreadCount ?? 0}-${items[0]?.seq ?? 0}"`,
    page: { items, unreadCount: 0, readUpto: '0', activeWork: 0, nextCursor: null, ...overrides }
});
const notModified: NotificationListResult = { kind: 'not-modified' };

describe('NotificationCenter', () => {
    let api: SpyObj<NotificationsApiService>;
    let status: WritableSignal<AuthStatus>;
    let center: NotificationCenter;
    let toasts: ToastService;

    /** Queues the answers of consecutive `list` calls. */
    const answers = (...results: (NotificationListResult | Error)[]): void => {
        for (const result of results) {
            api.list.mockReturnValueOnce(result instanceof Error ? throwError(() => result) : of(result));
        }
    };
    const settle = async (ms = 0): Promise<void> => { await vi.advanceTimersByTimeAsync(ms); };

    async function signIn(first: NotificationListResult): Promise<void> {
        answers(first);
        status.set('authenticated');
        TestBed.tick();
        await settle();
    }

    beforeEach(() => {
        localStorage.clear();
        vi.useFakeTimers();
        api = spyObj<NotificationsApiService>({
            list: vi.fn().mockName('list'), setReadCursor: vi.fn().mockName('setReadCursor'), dismiss: vi.fn().mockName('dismiss')
        });
        status = signal<AuthStatus>('anonymous');
        TestBed.configureTestingModule({ providers: [
            { provide: NotificationsApiService, useValue: api },
            { provide: AuthService, useValue: { status } }
        ] });
        center = TestBed.inject(NotificationCenter);
        toasts = TestBed.inject(ToastService);
        TestBed.tick();
    });
    afterEach(() => { vi.useRealTimers(); localStorage.clear(); });

    it('does nothing until an account is signed in, then loads the newest page without a validator', async () => {
        await settle(IDLE_POLL_MS * 2);
        expect(api.list).not.toHaveBeenCalled();

        await signIn(page([note(42), note(41)], { unreadCount: 2, readUpto: '40' }));
        expect(api.list).toHaveBeenCalledExactlyOnceWith({ limit: 20 });
        expect(center.items().map(item => item.seq)).toEqual(['42', '41']);
        expect(center.unreadCount()).toBe(2);
        expect(center.badgeText()).toBe('2');
        expect(toasts.visible()).toEqual([]);
    });

    it('shows 99+ above 99 and nothing at zero', async () => {
        await signIn(page([note(1)], { unreadCount: 100 }));
        expect(center.badgeText()).toBe('99+');
        center.unreadCount.set(99);
        expect(center.badgeText()).toBe('99');
        center.unreadCount.set(0);
        expect(center.badgeText()).toBe('');
    });

    it('polls every 45 s with the last seq and ETag, and a 304 changes nothing', async () => {
        await signIn(page([note(42)], { unreadCount: 1 }));
        answers(notModified);
        await settle(IDLE_POLL_MS - 1);
        expect(api.list).toHaveBeenCalledTimes(1);
        await settle(1);
        expect(api.list).toHaveBeenLastCalledWith({ limit: 100, after: '42' }, '"etag-1-42"');
        expect(center.unreadCount()).toBe(1);

        answers(notModified);
        await settle(IDLE_POLL_MS);
        expect(api.list).toHaveBeenCalledTimes(3);
    });

    it('polls every 10 s while generation is running and returns to 45 s when it ends', async () => {
        await signIn(page([note(1)], { activeWork: 1 }));
        answers(page([note(1)], { activeWork: 1 }), page([note(1)], { activeWork: 0 }), notModified);
        await settle(ACTIVE_POLL_MS - 1);
        expect(api.list).toHaveBeenCalledTimes(1);
        await settle(1);
        expect(api.list).toHaveBeenCalledTimes(2);
        await settle(ACTIVE_POLL_MS);
        expect(api.list).toHaveBeenCalledTimes(3);
        await settle(ACTIVE_POLL_MS * 3);
        expect(api.list).toHaveBeenCalledTimes(3);
        await settle(IDLE_POLL_MS);
        expect(api.list).toHaveBeenCalledTimes(4);
    });

    it('pauses on a hidden tab and catches up at once when it becomes visible', async () => {
        await signIn(page([note(1)]));
        const hidden = vi.spyOn(document, 'hidden', 'get').mockReturnValue(true);
        document.dispatchEvent(new Event('visibilitychange'));
        await settle(IDLE_POLL_MS * 4);
        expect(api.list).toHaveBeenCalledTimes(1);

        answers(notModified);
        hidden.mockReturnValue(false);
        document.dispatchEvent(new Event('visibilitychange'));
        await settle();
        expect(api.list).toHaveBeenCalledTimes(2);
        expect(api.list).toHaveBeenLastCalledWith({ limit: 100, after: '1' }, '"etag-0-1"');
    });

    it('merges catch-up items, toasts the new unread ones once and advances the seq', async () => {
        await signIn(page([note(41)], { unreadCount: 1, readUpto: '40' }));
        answers(page([note(42), note(43, { severity: 'ERROR', kind: 'GENERATION_FAILED', params: { errorCode: 'REFUSAL' } })],
            { unreadCount: 3, readUpto: '40' }));
        await settle(IDLE_POLL_MS);
        expect(center.items().map(item => item.seq)).toEqual(['43', '42', '41']);
        expect(center.unreadCount()).toBe(3);
        expect(toasts.visible().map(toast => toast.id)).toEqual([note(43).notificationId, note(42).notificationId]);
        expect(toasts.visible()[0].text).toBe('Создание не удалось — ИИ отказался выполнять запрос, измените формулировку');

        answers(page([note(43)], { unreadCount: 3, readUpto: '40' }), notModified);
        await settle(IDLE_POLL_MS);
        expect(api.list).toHaveBeenLastCalledWith({ limit: 100, after: '43' }, expect.any(String));
        // The INFO toast timed out meanwhile and was not raised again; the ERROR one waits to be closed.
        expect(toasts.visible().map(toast => toast.id)).toEqual([note(43).notificationId]);
    });

    it('counts an unknown kind as unread but shows neither a toast nor an entry', async () => {
        await signIn(page([note(1)], { readUpto: '1' }));
        answers(page([note(2, { kind: 'FROM_THE_FUTURE', params: {} })], { unreadCount: 1, readUpto: '1' }));
        await settle(IDLE_POLL_MS);
        expect(center.unreadCount()).toBe(1);
        expect(center.items()).toHaveLength(2);
        expect(center.entries().map(entry => entry.notification.seq)).toEqual(['1']);
        expect(toasts.visible()).toEqual([]);
    });

    it('does not toast what is already read or what arrives while the panel is open', async () => {
        await signIn(page([note(1)], { readUpto: '1' }));
        answers(page([note(2)], { unreadCount: 0, readUpto: '2' }));
        await settle(IDLE_POLL_MS);
        expect(toasts.visible()).toEqual([]);

        api.list.mockReturnValueOnce(of(page([note(2), note(1)], { readUpto: '2' })));
        center.open();
        await settle();
        answers(page([note(3)], { unreadCount: 1, readUpto: '2' }));
        api.setReadCursor.mockReturnValue(of({ readUpto: '3', unreadCount: 0 }));
        await settle(IDLE_POLL_MS);
        expect(toasts.visible()).toEqual([]);
        expect(api.setReadCursor).toHaveBeenLastCalledWith('3');
    });

    it('follows nextCursor during catch-up with the cursor alone', async () => {
        await signIn(page([note(1)], { readUpto: '1' }));
        answers(page([note(2), note(3)], { unreadCount: 3, readUpto: '0', nextCursor: 'QTM' }),
            page([note(4)], { unreadCount: 3, readUpto: '0', nextCursor: null }));
        await settle(IDLE_POLL_MS);
        expect(api.list).toHaveBeenLastCalledWith({ limit: 100, cursor: 'QTM' });
        expect(center.items().map(item => item.seq)).toEqual(['4', '3', '2', '1']);
    });

    it('holds the toast during a quiet zone but updates the badge at once', async () => {
        await signIn(page([note(1)], { readUpto: '1' }));
        const quiet = TestBed.inject(QuietZone);
        quiet.set(true);
        TestBed.tick();
        answers(page([note(2)], { unreadCount: 1, readUpto: '1' }));
        await settle(IDLE_POLL_MS);
        expect(center.badgeText()).toBe('1');
        expect(toasts.visible()).toEqual([]);
        expect(toasts.queuedCount()).toBe(1);
        quiet.set(false);
        TestBed.tick();
        expect(toasts.visible()).toHaveLength(1);
    });

    it('opening the panel reloads, then moves the read cursor once with the newest seq on screen', async () => {
        await signIn(page([note(42), note(41)], { unreadCount: 2, readUpto: '40' }));
        api.list.mockReturnValueOnce(of(page([note(42), note(41)], { unreadCount: 2, readUpto: '40' })));
        api.setReadCursor.mockReturnValue(of({ readUpto: '42', unreadCount: 0 }));
        center.open();
        await settle();
        expect(center.freshAfter()).toBe('40');
        expect(api.list).toHaveBeenLastCalledWith({ limit: 20 });
        expect(api.setReadCursor).toHaveBeenCalledExactlyOnceWith('42');
        expect(center.readUpto()).toBe('42');
        expect(center.unreadCount()).toBe(0);

        center.close();
        api.list.mockReturnValueOnce(of(page([note(42), note(41)], { readUpto: '42' })));
        center.open();
        await settle();
        expect(api.setReadCursor).toHaveBeenCalledTimes(1);
    });

    it('keeps the badge when the cursor request fails and tries again at the next opening', async () => {
        await signIn(page([note(42)], { unreadCount: 1, readUpto: '40' }));
        api.list.mockReturnValue(of(page([note(42)], { unreadCount: 1, readUpto: '40' })));
        api.setReadCursor.mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 503 })));
        center.open();
        await settle();
        expect(center.unreadCount()).toBe(1);
        center.close();
        api.setReadCursor.mockReturnValueOnce(of({ readUpto: '42', unreadCount: 0 }));
        center.open();
        await settle();
        expect(api.setReadCursor).toHaveBeenCalledTimes(2);
        expect(center.unreadCount()).toBe(0);
    });

    it('dismisses an item, lowers the badge for an unread one and drops the stale validator', async () => {
        await signIn(page([note(42), note(41)], { unreadCount: 1, readUpto: '41' }));
        api.dismiss.mockReturnValue(of(undefined));
        await center.dismiss(note(42).notificationId);
        expect(api.dismiss).toHaveBeenCalledWith(note(42).notificationId);
        expect(center.items().map(item => item.seq)).toEqual(['41']);
        expect(center.unreadCount()).toBe(0);

        await center.dismiss(note(41).notificationId);
        expect(center.unreadCount()).toBe(0);

        answers(notModified);
        await settle(IDLE_POLL_MS);
        expect(api.list).toHaveBeenLastCalledWith({ limit: 100, after: '42' }, null);
    });

    it('treats a 404 on dismiss as done and reports any other failure without removing the item', async () => {
        await signIn(page([note(2), note(1)], { readUpto: '2' }));
        api.dismiss.mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 404 })));
        await center.dismiss(note(2).notificationId);
        expect(center.items().map(item => item.seq)).toEqual(['1']);

        api.dismiss.mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 503 })));
        await center.dismiss(note(1).notificationId);
        expect(center.items().map(item => item.seq)).toEqual(['1']);
        expect(center.panelError()).toBe('Не удалось убрать уведомление. Попробуйте ещё раз.');
    });

    it('pages to older notifications with the cursor', async () => {
        await signIn(page([note(30)], { readUpto: '30', nextCursor: 'RDMw' }));
        answers(page([note(29), note(28)], { readUpto: '30', nextCursor: null }));
        await center.loadMore();
        expect(api.list).toHaveBeenLastCalledWith({ limit: 20, cursor: 'RDMw' });
        expect(center.items().map(item => item.seq)).toEqual(['30', '29', '28']);
        expect(center.olderCursor()).toBeNull();
    });

    it('keeps polling after a failed poll', async () => {
        await signIn(page([note(1)]));
        answers(new HttpErrorResponse({ status: 503 }), notModified);
        await settle(IDLE_POLL_MS);
        await settle(IDLE_POLL_MS);
        expect(api.list).toHaveBeenCalledTimes(3);
        expect(center.items()).toHaveLength(1);
    });

    it('stops, forgets everything and clears toasts on sign-out', async () => {
        await signIn(page([note(1)], { unreadCount: 1 }));
        toasts.notify('n', 'Готово', 'ERROR', null);
        status.set('anonymous');
        TestBed.tick();
        expect(center.items()).toEqual([]);
        expect(center.unreadCount()).toBe(0);
        expect(toasts.visible()).toEqual([]);
        await settle(IDLE_POLL_MS * 3);
        expect(api.list).toHaveBeenCalledTimes(1);

        // A later sign-in starts from the first load again.
        await signIn(page([note(5)], { unreadCount: 1 }));
        expect(api.list).toHaveBeenLastCalledWith({ limit: 20 });
        expect(center.items().map(item => item.seq)).toEqual(['5']);
    });
});
