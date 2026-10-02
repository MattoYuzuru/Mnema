import { DOCUMENT } from '@angular/common';
import { DestroyRef, Injectable, computed, effect, inject, signal, untracked } from '@angular/core';

import { NotificationLink } from './notification-presenter';
import { NotificationPreferences } from './notification-preferences';
import { NotificationSeverity } from './notification.models';
import { QuietZone } from './quiet-zone';

export const MAX_VISIBLE_TOASTS = 3;
/** INFO and WARNING toasts leave by themselves after this long; ERROR stays until closed. */
export const TOAST_MS = 6000;
/** The echo of the user's own action (a client toast, not a notification). */
export const ECHO_MS = 3000;
const MODAL_RECHECK_MS = 1000;
const ANNOUNCE_MS = 1500;
const ANNOUNCE_GAP_MS = 250;

export interface Toast {
    readonly id: string;
    readonly text: string;
    readonly severity: NotificationSeverity;
    readonly link: NotificationLink | null;
    /** `null`: until closed. */
    readonly durationMs: number | null;
    readonly echo: boolean;
}

interface Timer { remaining: number; startedAt: number; handle: ReturnType<typeof setTimeout> | null }

/**
 * Transient messages. At most three are visible (newest on top), the rest wait in order. Admission is deferred while a
 * modal dialog is open (it would make the top-layer toast inert) and, for notification toasts, while a {@link QuietZone}
 * is active and the learner chose «в паузах»; with «только значок» such toasts are dropped (the notification stays in
 * the inbox). Timers pause on hover, focus inside the region and a hidden tab. Screen-reader announcements go through
 * their own queue into a persistent status region: the toasts themselves are not live.
 */
@Injectable({ providedIn: 'root' })
export class ToastService {
    private readonly document = inject(DOCUMENT);
    private readonly quiet = inject(QuietZone);
    private readonly preferences = inject(NotificationPreferences);

    /** In the order they were admitted. */
    private readonly shown = signal<readonly Toast[]>([]);
    /** Waiting for a free slot or for the quiet zone to end, in arrival order. */
    private readonly waiting = signal<readonly Toast[]>([]);
    /** Visible toasts, newest first. */
    readonly visible = computed(() => [...this.shown()].reverse());
    readonly queuedCount = computed(() => this.waiting().length);
    /** Text for the persistent `role="status"` region. */
    readonly announcement = signal('');

    private readonly timers = new Map<string, Timer>();
    private readonly announcements: string[] = [];
    private announcing = false;
    private hovered = false;
    private focused = false;
    private modalRecheck: ReturnType<typeof setTimeout> | null = null;
    private nextEcho = 0;

    constructor() {
        const destroy = inject(DestroyRef);
        const visibility = (): void => { this.syncTimers(); };
        const dialogClosed = (): void => this.pump();
        this.document.addEventListener('visibilitychange', visibility);
        // `close` does not bubble, but capture sees every dialog.
        this.document.addEventListener('close', dialogClosed, true);
        destroy.onDestroy(() => {
            this.document.removeEventListener('visibilitychange', visibility);
            this.document.removeEventListener('close', dialogClosed, true);
            this.timers.forEach(timer => { if (timer.handle !== null) clearTimeout(timer.handle); });
            if (this.modalRecheck !== null) clearTimeout(this.modalRecheck);
        });
        effect(() => {
            this.quiet.active();
            this.preferences.duringStudy();
            untracked(() => this.pump());
        });
    }

    /** A toast for a notification; `id` is the notification id, so one notification never shows twice. */
    notify(id: string, text: string, severity: NotificationSeverity, link: NotificationLink | null): void {
        if (this.known(id)) return;
        if (this.quiet.active() && this.preferences.duringStudy() === 'BADGE_ONLY') return;
        this.enqueue({ id, text, severity, link, durationMs: severity === 'ERROR' ? null : TOAST_MS, echo: false });
    }

    /** The 3 s confirmation of the user's own action, e.g. «Материал одобрен». Never held for Study. */
    echo(text: string): void {
        this.enqueue({ id: `echo-${this.nextEcho++}`, text, severity: 'INFO', link: null, durationMs: ECHO_MS, echo: true });
    }

    close(id: string): void {
        const timer = this.timers.get(id);
        if (timer?.handle != null) clearTimeout(timer.handle);
        this.timers.delete(id);
        this.shown.update(list => list.filter(toast => toast.id !== id));
        this.waiting.update(list => list.filter(toast => toast.id !== id));
        this.pump();
    }

    /** Drops every notification toast (sign-out); echoes of the user's own actions finish normally. */
    clearNotifications(): void {
        [...this.shown(), ...this.waiting()].filter(toast => !toast.echo).forEach(toast => this.close(toast.id));
    }

    setHovered(value: boolean): void { this.hovered = value; this.syncTimers(); }
    setFocused(value: boolean): void { this.focused = value; this.syncTimers(); }

    private enqueue(toast: Toast): void {
        this.waiting.update(list => [...list, toast]);
        this.pump();
    }

    private known(id: string): boolean {
        return this.shown().some(toast => toast.id === id) || this.waiting().some(toast => toast.id === id);
    }

    private held(toast: Toast): boolean {
        return !toast.echo && this.quiet.active() && this.preferences.duringStudy() === 'AT_PAUSES';
    }

    /** Admits waiting toasts, in order, while there is room and nothing holds them. */
    private pump(): void {
        if (this.waiting().length === 0) return;
        if (this.modalOpen()) { this.scheduleModalRecheck(); return; }
        let room = MAX_VISIBLE_TOASTS - this.shown().length;
        if (room <= 0) return;
        const admitted: Toast[] = [];
        const stay = this.waiting().filter(toast => {
            if (room <= 0 || this.held(toast)) return true;
            room--;
            admitted.push(toast);
            return false;
        });
        if (admitted.length === 0) return;
        this.waiting.set(stay);
        this.shown.update(list => [...list, ...admitted]);
        admitted.forEach(toast => {
            if (toast.durationMs !== null) {
                this.timers.set(toast.id, { remaining: toast.durationMs, startedAt: 0, handle: null });
            }
            this.announce(toast.text);
        });
        this.syncTimers();
    }

    private modalOpen(): boolean {
        // Any open dialog counts: a modal one makes the top-layer toast inert, and a toast over a dialog is no better.
        return this.document.querySelector('dialog[open]') !== null;
    }

    /** A modal removed from the DOM while open fires no `close`, so waiting toasts also re-check on a slow timer. */
    private scheduleModalRecheck(): void {
        if (this.modalRecheck !== null) return;
        this.modalRecheck = setTimeout(() => { this.modalRecheck = null; this.pump(); }, MODAL_RECHECK_MS);
    }

    private syncTimers(): void {
        const paused = this.hovered || this.focused || this.document.hidden;
        const now = Date.now();
        this.timers.forEach((timer, id) => {
            if (paused && timer.handle !== null) {
                clearTimeout(timer.handle);
                timer.handle = null;
                timer.remaining = Math.max(0, timer.remaining - (now - timer.startedAt));
            } else if (!paused && timer.handle === null) {
                timer.startedAt = now;
                timer.handle = setTimeout(() => this.close(id), timer.remaining);
            }
        });
    }

    /** One message at a time with a gap, so a series is read out in full instead of each replacing the previous one. */
    private announce(text: string): void {
        this.announcements.push(text);
        if (!this.announcing) this.drainAnnouncement();
    }

    private drainAnnouncement(): void {
        const text = this.announcements.shift();
        if (text === undefined) { this.announcing = false; return; }
        this.announcing = true;
        this.announcement.set(text);
        setTimeout(() => {
            this.announcement.set('');
            setTimeout(() => this.drainAnnouncement(), ANNOUNCE_GAP_MS);
        }, ANNOUNCE_MS);
    }
}
