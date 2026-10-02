import { Injectable, signal } from '@angular/core';

/** What a notification does while a Study task is open: wait for a pause, interrupt at once, or only move the badge. */
export const DURING_STUDY_MODES = ['AT_PAUSES', 'IMMEDIATE', 'BADGE_ONLY'] as const;
export type DuringStudyMode = (typeof DURING_STUDY_MODES)[number];

export const DURING_STUDY_STORAGE_KEY = 'mnema.notifications.duringStudy';

/**
 * Client-side delivery preference («Во время занятия»). Storing it on the account is not part of notifications v1
 * (contracts/notifications, "Open questions"), so it lives in `localStorage` of this browser. Storage can be absent or
 * throw (private window, blocked site data); the choice then lasts for the page's lifetime and the default applies.
 */
@Injectable({ providedIn: 'root' })
export class NotificationPreferences {
    private readonly state = signal<DuringStudyMode>(readStored());
    readonly duringStudy = this.state.asReadonly();

    setDuringStudy(mode: DuringStudyMode): void {
        if (!DURING_STUDY_MODES.includes(mode)) return;
        this.state.set(mode);
        try { localStorage.setItem(DURING_STUDY_STORAGE_KEY, mode); } catch { /* kept in memory only */ }
    }
}

function readStored(): DuringStudyMode {
    try {
        const value = localStorage.getItem(DURING_STUDY_STORAGE_KEY);
        return DURING_STUDY_MODES.find(mode => mode === value) ?? 'AT_PAUSES';
    } catch { return 'AT_PAUSES'; }
}
