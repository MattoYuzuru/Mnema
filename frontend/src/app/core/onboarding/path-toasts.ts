import { DestroyRef, Injectable, effect, inject, signal, untracked } from '@angular/core';

import { NotificationPreferences } from '../notifications/notification-preferences';
import { pathMilestoneParams, pathMilestonesSummary } from '../notifications/notification-presenter';
import { AppNotification } from '../notifications/notification.models';
import { QuietZone } from '../notifications/quiet-zone';
import { ToastService } from '../notifications/toast.service';
import { onboardingText } from './onboarding-texts';
import { AttentionCoordinator } from './attention-coordinator';
import { JourneyStore } from './journey.store';
import { Rhythm } from './onboarding.models';
import { PathPreferences } from './path-preferences';
import { daysLabel } from './rhythm-format';

/** At most one milestone toast in this window (proposal layer 5): milestones that arrive inside it are merged into one summary. */
export const MILESTONE_TOAST_WINDOW_MS = 30_000;

interface Pending {
    readonly id: string;
    readonly done: number;
    readonly total: number;
    readonly text: string;
    /** The quiet toast of the met weekly goal (OB-401), not a milestone: it is never merged into a summary and does not depend on «Путь». */
    readonly rhythm?: true;
}

/**
 * The toasts of `PATH_MILESTONE` notifications, under the milestone policy: never while Study has an answer open (the quiet zone holds
 * them until it clears, whatever «Во время занятия» says; «только значок» still drops them), only when the attention coordinator lets a
 * milestone speak (the tour, the first entry, a hint or the promo window outrank it), and at most one toast per
 * {@link MILESTONE_TOAST_WINDOW_MS}: what arrives inside the window, or while held, waits and leaves as one toast when the way is free (the
 * ordinary sentence for one, «Путь: отмечено 3 пункта · 8 из 12» for several). A learner who hid «Путь» gets no milestone toasts (the
 * inbox still lists them). Other notification kinds do not pass through here. A milestone notification also makes the journey view
 * re-read, so the page and the profile show it.
 *
 * The same policy carries the one quiet toast of the weekly rhythm (OB-401): when a newer view of the **same week** reaches the target that
 * the previous view of this session was below, and the target did not change («Цель недели выполнена: вы занимались 3 дня.»). A first view
 * (a fresh page load, a week that is already met), another week, or a changed target is no celebration, and a week is celebrated once. It waits
 * for the quiet zone, the coordinator and the shared window like a milestone, but it is not a milestone: hiding «Путь» on the device does not
 * silence it (it is the weekly goal, which the profile and the summary of a session show whatever «Путь» does), and it is never merged into
 * the summary of milestones (the milestone toast leaves first, the rhythm one in the next window).
 */
@Injectable({ providedIn: 'root' })
export class PathToasts {
    private readonly toasts = inject(ToastService);
    private readonly quiet = inject(QuietZone);
    private readonly preferences = inject(NotificationPreferences);
    private readonly attention = inject(AttentionCoordinator);
    private readonly journey = inject(JourneyStore);
    private readonly path = inject(PathPreferences);

    private readonly waiting = signal<readonly Pending[]>([]);
    private lastShownAt: number | null = null;
    private timer: ReturnType<typeof setTimeout> | null = null;
    /** The rhythm of the previous view of this session; `null` before the first one, and after a view without a rhythm. */
    private lastRhythm: Rhythm | null = null;
    /** The week whose met goal was already announced in this session. */
    private announcedWeek: string | null = null;

    constructor() {
        // The way becoming free (the answer is given, the tour ended, the hint was closed) is a signal change: look again.
        effect(() => {
            // Nothing waits, nothing to ask: the preferences and the coordinator are read only while a toast is held.
            if (this.waiting().length === 0) return;
            this.attention.canShow('milestone');
            this.path.hidden();
            untracked(() => this.flush());
        });
        // A newer view of the journey: the weekly goal may have been met by it.
        effect(() => {
            const rhythm = this.journey.view()?.rhythm ?? null;
            untracked(() => this.noteRhythm(rhythm));
        });
        inject(DestroyRef).onDestroy(() => this.clearTimer());
    }

    /** A milestone notification arrived (any state of the panel): the page and the profile read the view again. */
    noteArrival(): void {
        void this.journey.load({ refresh: true });
    }

    /**
     * Milestone notifications the learner has not read yet, oldest first, that arrived together (one poll): a toast, when the policy allows.
     * They are queued together and leave as one toast (the summary for several) when the way is free.
     */
    arrived(...notifications: readonly AppNotification[]): void {
        if (this.path.hidden()) return;
        // «Только значок» drops a toast raised during a task; the notification stays in the inbox.
        if (this.quiet.active() && this.preferences.duringStudy() === 'BADGE_ONLY') return;
        const fresh: Pending[] = [];
        for (const notification of notifications) {
            const milestone = pathMilestoneParams(notification.params);
            if (milestone === null || this.waiting().some(item => item.id === notification.notificationId) || fresh.some(item => item.id === notification.notificationId)) continue;
            fresh.push({
                id: notification.notificationId, done: milestone.done, total: milestone.total,
                text: onboardingText('path.toast', { event: onboardingText(`path.event.${milestone.key}`), done: milestone.done, total: milestone.total })
            });
        }
        if (fresh.length === 0) return;
        this.waiting.update(list => [...list, ...fresh]);
        this.flush();
    }

    /** Sign-out: nothing of the previous account is shown to the next one. */
    reset(): void {
        this.waiting.set([]);
        this.lastShownAt = null;
        this.lastRhythm = null;
        this.announcedWeek = null;
        this.clearTimer();
    }

    /** The rhythm of a newer view: queues the toast of the met weekly goal when this view is the one that met it. */
    private noteRhythm(rhythm: Rhythm | null): void {
        const previous = this.lastRhythm;
        this.lastRhythm = rhythm;
        if (rhythm === null || previous === null || previous.weekStart !== rhythm.weekStart || previous.target !== rhythm.target) return;
        if (previous.days.length >= rhythm.target || rhythm.days.length < rhythm.target || this.announcedWeek === rhythm.weekStart) return;
        this.announcedWeek = rhythm.weekStart;
        const text = onboardingText('rhythm.met.toast', { days: daysLabel(rhythm.days.length) });
        this.waiting.update(list => [...list, { id: `rhythm-met-${rhythm.weekStart}`, done: 0, total: 0, text, rhythm: true }]);
        this.flush();
    }

    private flush(): void {
        // A learner who hid «Путь» gets no milestone toast; the rhythm toast is not one.
        if (this.path.hidden() && this.waiting().some(item => item.rhythm !== true)) this.waiting.update(list => list.filter(item => item.rhythm === true));
        const queue = this.waiting();
        if (queue.length === 0) { this.clearTimer(); return; }
        if (!this.attention.canShow('milestone')) return;
        const waited = this.lastShownAt === null ? MILESTONE_TOAST_WINDOW_MS : Date.now() - this.lastShownAt;
        if (waited < MILESTONE_TOAST_WINDOW_MS) { this.schedule(MILESTONE_TOAST_WINDOW_MS - waited); return; }
        this.clearTimer();
        const batch = queue.filter(item => item.rhythm !== true);
        const rhythm = queue.filter(item => item.rhythm === true);
        this.waiting.set(batch.length > 0 ? rhythm : []);
        this.lastShownAt = Date.now();
        if (batch.length === 0) {
            this.toasts.notify(rhythm[rhythm.length - 1].id, rhythm[rhythm.length - 1].text, 'INFO', null);
            return;
        }
        // The rhythm toast waits for the window this milestone toast opens.
        if (rhythm.length > 0) this.schedule(MILESTONE_TOAST_WINDOW_MS);
        const latest = batch[batch.length - 1];
        if (batch.length === 1) this.toasts.notify(latest.id, latest.text, 'INFO', { label: onboardingText('path.open'), commands: ['/profile', 'path'] });
        else {
            this.toasts.notify(`path-summary-${latest.id}`, pathMilestonesSummary(batch.length, latest.done, latest.total), 'INFO',
                { label: onboardingText('path.open'), commands: ['/profile', 'path'] });
        }
    }

    private schedule(delay: number): void {
        if (this.timer !== null) return;
        this.timer = setTimeout(() => { this.timer = null; this.flush(); }, delay);
    }

    private clearTimer(): void {
        if (this.timer !== null) { clearTimeout(this.timer); this.timer = null; }
    }
}
