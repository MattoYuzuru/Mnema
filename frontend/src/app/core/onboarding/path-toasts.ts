import { DestroyRef, Injectable, effect, inject, signal, untracked } from '@angular/core';

import { NotificationPreferences } from '../notifications/notification-preferences';
import { pathMilestoneParams, pathMilestonesSummary } from '../notifications/notification-presenter';
import { AppNotification } from '../notifications/notification.models';
import { QuietZone } from '../notifications/quiet-zone';
import { ToastService } from '../notifications/toast.service';
import { onboardingText } from './onboarding-texts';
import { AttentionCoordinator } from './attention-coordinator';
import { JourneyStore } from './journey.store';
import { PathPreferences } from './path-preferences';

/** At most one milestone toast in this window (proposal layer 5): milestones that arrive inside it are merged into one summary. */
export const MILESTONE_TOAST_WINDOW_MS = 30_000;

interface Pending {
    readonly id: string;
    readonly done: number;
    readonly total: number;
    readonly text: string;
}

/**
 * The toasts of `PATH_MILESTONE` notifications, under the milestone policy: never while Study has an answer open (the quiet zone holds
 * them until it clears, whatever «Во время занятия» says; «только значок» still drops them), only when the attention coordinator lets a
 * milestone speak (the tour, the first entry, a hint or the promo window outrank it), and at most one toast per
 * {@link MILESTONE_TOAST_WINDOW_MS}: what arrives inside the window, or while held, waits and leaves as one toast when the way is free (the
 * ordinary sentence for one, «Путь: отмечено 3 пункта · 8 из 12» for several). A learner who hid «Путь» gets no milestone toasts (the
 * inbox still lists them). Other notification kinds do not pass through here. A milestone notification also makes the journey view
 * re-read, so the page and the profile show it.
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

    constructor() {
        // The way becoming free (the answer is given, the tour ended, the hint was closed) is a signal change: look again.
        effect(() => {
            // Nothing waits, nothing to ask: the preferences and the coordinator are read only while a toast is held.
            if (this.waiting().length === 0) return;
            this.attention.canShow('milestone');
            this.path.hidden();
            untracked(() => this.flush());
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
        this.clearTimer();
    }

    private flush(): void {
        if (this.waiting().length === 0) return;
        if (this.path.hidden()) { this.waiting.set([]); this.clearTimer(); return; }
        if (!this.attention.canShow('milestone')) return;
        const waited = this.lastShownAt === null ? MILESTONE_TOAST_WINDOW_MS : Date.now() - this.lastShownAt;
        if (waited < MILESTONE_TOAST_WINDOW_MS) { this.schedule(MILESTONE_TOAST_WINDOW_MS - waited); return; }
        this.clearTimer();
        const batch = this.waiting();
        this.waiting.set([]);
        this.lastShownAt = Date.now();
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
