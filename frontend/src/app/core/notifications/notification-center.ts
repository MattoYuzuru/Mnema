import { DOCUMENT } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { DestroyRef, Injectable, computed, effect, inject, signal, untracked } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { AuthService } from '../../auth.service';
import { InboxEntry, entriesOf } from './notification-entries';
import { AppNotification, compareSeq } from './notification.models';
import { NotificationsApiService } from './notifications-api.service';
import { presentNotification } from './notification-presenter';
import { ToastService } from './toast.service';

/** Poll cadence: idle on a visible tab, and fast while the owner has generation running. */
export const IDLE_POLL_MS = 45_000;
export const ACTIVE_POLL_MS = 10_000;
const PAGE_SIZE = 20;
const CATCH_UP_SIZE = 100;
const CATCH_UP_PAGES = 5;
/** The server keeps at most this many per account. */
const RETENTION = 200;

/**
 * The owner's notification center (contracts/notifications): server state in signals, a visibility-aware poll, the
 * read watermark and dismissal. Only a signed-in account polls. A poll is a catch-up (`after=<newest seq seen>`) with
 * `If-None-Match`, so an unchanged center is a bodyless `304`; the panel opening does a full newest-first reload.
 * Notifications that arrive during catch-up raise a toast (never the ones already waiting at sign-in).
 */
@Injectable({ providedIn: 'root' })
export class NotificationCenter {
    private readonly api = inject(NotificationsApiService);
    private readonly auth = inject(AuthService);
    private readonly toasts = inject(ToastService);
    private readonly document = inject(DOCUMENT);

    /** Newest first, including kinds this client cannot render. */
    readonly items = signal<readonly AppNotification[]>([]);
    /** What the panel lists: renderable notifications with their text and destination. */
    readonly entries = computed<readonly InboxEntry[]>(() => entriesOf(this.items()));
    readonly unreadCount = signal(0);
    readonly readUpto = signal('0');
    readonly activeWork = signal(0);
    /** Cursor of the next older page; `null` when the whole center is loaded. */
    readonly olderCursor = signal<string | null>(null);
    readonly loadingMore = signal(false);
    readonly moreError = signal<string | null>(null);
    readonly panelError = signal<string | null>(null);
    readonly panelOpen = signal(false);
    /** The read watermark at the moment the panel was opened: items above it are shown as new. */
    readonly freshAfter = signal('0');
    readonly badgeText = computed(() => {
        const count = this.unreadCount();
        return count === 0 ? '' : count > 99 ? '99+' : String(count);
    });

    private running = false;
    private epoch = 0;
    private listEpoch = 0;
    private chain: Promise<unknown> = Promise.resolve();
    /**
     * Ids dismissed in this session. A list response that was already in flight when the learner dismissed (the panel
     * reload on open, a poll) may still contain the item; it must not reappear.
     */
    private readonly dismissed = new Set<string>();
    private pollQueued = false;
    private timer: ReturnType<typeof setTimeout> | null = null;
    private lastSeq: string | null = null;
    private etag: string | null = null;

    constructor() {
        const visibility = (): void => { if (this.document.hidden) this.clearTimer(); else this.poll(); };
        this.document.addEventListener('visibilitychange', visibility);
        inject(DestroyRef).onDestroy(() => {
            this.document.removeEventListener('visibilitychange', visibility);
            this.stop();
        });
        effect(() => {
            const signedIn = this.auth.status() === 'authenticated';
            untracked(() => signedIn ? this.start() : this.stop());
        });
    }

    /** Panel opened: the badge clears for what is on screen, then the list reloads and catches anything newer. */
    open(): void {
        this.freshAfter.set(this.readUpto());
        this.panelError.set(null);
        this.panelOpen.set(true);
        // Reload first, so the watermark moves to what is really newest and the two requests never race.
        void this.serial(async epoch => {
            try { await this.load(epoch); } catch { /* offline: the list on screen is what the learner sees */ }
            await this.markShownRead();
        });
    }

    close(): void { this.panelOpen.set(false); }

    /** Older notifications, one page. */
    async loadMore(): Promise<void> {
        const cursor = this.olderCursor();
        if (cursor === null || this.loadingMore()) return;
        this.loadingMore.set(true);
        this.moreError.set(null);
        const epoch = this.epoch;
        const listEpoch = this.listEpoch;
        try {
            const result = await firstValueFrom(this.api.list({ limit: PAGE_SIZE, cursor }));
            if (epoch !== this.epoch || listEpoch !== this.listEpoch || result.kind !== 'page') return;
            const known = new Set(this.items().map(item => item.notificationId));
            this.items.update(list => [...list, ...result.page.items.filter(item => !known.has(item.notificationId) && !this.dismissed.has(item.notificationId))]);
            this.olderCursor.set(result.page.nextCursor);
        } catch {
            if (epoch === this.epoch && listEpoch === this.listEpoch) this.moreError.set('Не удалось загрузить остальные уведомления.');
        } finally { if (epoch === this.epoch && listEpoch === this.listEpoch) this.loadingMore.set(false); }
    }

    /** Removes one notification from the center (not from the toast stack). A 404 means it is already gone. */
    async dismiss(notificationId: string): Promise<void> {
        const item = this.items().find(candidate => candidate.notificationId === notificationId);
        if (item === undefined) return;
        const epoch = this.epoch;
        this.dismissed.add(notificationId);
        try {
            await firstValueFrom(this.api.dismiss(notificationId));
        } catch (error) {
            if (!(error instanceof HttpErrorResponse && error.status === 404)) {
                this.dismissed.delete(notificationId);
                if (epoch === this.epoch) this.panelError.set('Не удалось убрать уведомление. Попробуйте ещё раз.');
                return;
            }
        }
        if (epoch !== this.epoch) return;
        this.panelError.set(null);
        this.items.update(list => list.filter(candidate => candidate.notificationId !== notificationId));
        if (compareSeq(item.seq, this.readUpto()) > 0) this.unreadCount.update(count => Math.max(0, count - 1));
        // Dismissals are part of the validator; the next poll must not be answered with a stale 304.
        this.etag = null;
    }

    private start(): void {
        if (this.running) return;
        this.running = true;
        this.poll();
    }

    private stop(): void {
        this.running = false;
        this.epoch++;
        this.listEpoch++;
        this.loadingMore.set(false);
        this.moreError.set(null);
        this.pollQueued = false;
        this.clearTimer();
        this.lastSeq = null;
        this.etag = null;
        this.dismissed.clear();
        this.items.set([]);
        this.unreadCount.set(0);
        this.readUpto.set('0');
        this.activeWork.set(0);
        this.olderCursor.set(null);
        this.panelOpen.set(false);
        this.panelError.set(null);
        this.toasts.clearNotifications();
    }

    /** One poll at a time; while the first load has not happened yet a poll is that load. */
    private poll(): void {
        if (!this.running || this.pollQueued) return;
        this.pollQueued = true;
        this.clearTimer();
        void this.serial(async epoch => {
            this.pollQueued = false;
            if (this.lastSeq === null) await this.load(epoch); else await this.catchUp(epoch);
        }).finally(() => this.schedule());
    }

    private schedule(): void {
        this.clearTimer();
        if (!this.running || this.document.hidden) return;
        this.timer = setTimeout(() => { this.timer = null; this.poll(); },
            this.activeWork() > 0 ? ACTIVE_POLL_MS : IDLE_POLL_MS);
    }

    private clearTimer(): void {
        if (this.timer !== null) { clearTimeout(this.timer); this.timer = null; }
    }

    /** Runs server reads one after another; a task queued before a sign-out is skipped. Failures never break the chain. */
    private serial(task: (epoch: number) => Promise<void>): Promise<void> {
        const epoch = this.epoch;
        const next = this.chain.then(() => epoch === this.epoch ? task(epoch) : undefined).catch(() => undefined);
        this.chain = next;
        return next as Promise<void>;
    }

    /** Newest-first first page: the baseline for the badge, the panel and every later catch-up. */
    private async load(epoch: number): Promise<void> {
        const listEpoch = ++this.listEpoch;
        this.loadingMore.set(true);
        this.moreError.set(null);
        try {
            const result = await firstValueFrom(this.api.list({ limit: PAGE_SIZE }));
            if (epoch !== this.epoch || listEpoch !== this.listEpoch || result.kind !== 'page') return;
            const page = result.page;
            this.items.set(sortNewestFirst(page.items.filter(item => !this.dismissed.has(item.notificationId))));
            this.olderCursor.set(page.nextCursor);
            this.lastSeq = page.items.reduce((max, item) => compareSeq(item.seq, max) > 0 ? item.seq : max, '0');
            this.etag = result.etag;
            this.applyCounters(page.unreadCount, page.readUpto, page.activeWork);
        } finally { if (epoch === this.epoch && listEpoch === this.listEpoch) this.loadingMore.set(false); }
    }

    private async catchUp(epoch: number): Promise<void> {
        const after = this.lastSeq;
        if (after === null) return;
        const first = await firstValueFrom(this.api.list({ limit: CATCH_UP_SIZE, after }, this.etag));
        if (epoch !== this.epoch || first.kind !== 'page') return;
        let page = first.page;
        const arrived = [...page.items];
        for (let pages = 1; page.nextCursor !== null && pages < CATCH_UP_PAGES; pages++) {
            const more = await firstValueFrom(this.api.list({ limit: CATCH_UP_SIZE, cursor: page.nextCursor }));
            if (epoch !== this.epoch || more.kind !== 'page') return;
            page = more.page;
            arrived.push(...page.items);
        }
        this.etag = first.etag;
        this.lastSeq = arrived.reduce((max, item) => compareSeq(item.seq, max) > 0 ? item.seq : max, after);
        const known = new Set(this.items().map(item => item.notificationId));
        const added = arrived.filter(item => !known.has(item.notificationId) && !this.dismissed.has(item.notificationId));
        if (added.length > 0) this.items.set(sortNewestFirst([...added, ...this.items()]).slice(0, RETENTION));
        this.applyCounters(page.unreadCount, page.readUpto, page.activeWork);
        // The panel is open, or the item was already read elsewhere: nothing to interrupt with.
        if (this.panelOpen()) await this.markShownRead(); else this.toast(added);
    }

    private toast(added: readonly AppNotification[]): void {
        const readUpto = this.readUpto();
        // Catch-up arrives oldest first, so the newest ends up on top of the stack.
        for (const item of [...added].sort((a, b) => compareSeq(a.seq, b.seq))) {
            if (compareSeq(item.seq, readUpto) <= 0) continue;
            const presented = presentNotification(item);
            if (presented !== null) this.toasts.notify(item.notificationId, presented.text, presented.severity, presented.link);
        }
    }

    private applyCounters(unread: number, readUpto: string, activeWork: number): void {
        this.unreadCount.set(unread);
        this.readUpto.set(readUpto);
        this.activeWork.set(activeWork);
    }

    /** Moves the read watermark to the newest notification on screen; a no-op when it is already there. */
    private async markShownRead(): Promise<void> {
        const newest = this.items()[0]?.seq;
        if (!this.panelOpen() || newest === undefined || compareSeq(newest, this.readUpto()) <= 0) return;
        const epoch = this.epoch;
        try {
            const result = await firstValueFrom(this.api.setReadCursor(newest));
            if (epoch !== this.epoch) return;
            this.readUpto.set(result.readUpto);
            this.unreadCount.set(result.unreadCount);
        } catch {
            // Still unread on the server; the next opening tries again.
        }
    }
}

function sortNewestFirst(items: readonly AppNotification[]): AppNotification[] {
    return [...items].sort((a, b) => compareSeq(b.seq, a.seq));
}
