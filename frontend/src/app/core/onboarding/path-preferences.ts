import { Injectable, computed, inject, signal } from '@angular/core';

import { AuthService } from '../../auth.service';

export const PATH_HIDDEN_STORAGE_PREFIX = 'mnema.path.hidden.';
export const PATH_NOTICE_STORAGE_PREFIX = 'mnema.path.noticeSeen.';
export const PATH_REWARD_STORAGE_PREFIX = 'mnema.path.rewardShown.';

/**
 * What this browser remembers about «Путь Mnema» for the signed-in account: whether the learner hid it («Скрыть «Путь»»: no milestone
 * toasts, the profile block is only a link) and whether the «Новое: Путь Mnema» notice was shown. Both are client preferences in
 * `localStorage`, one entry per account, so another account on the same device starts clean. Storage can be absent or throw (a private
 * window, blocked site data): the choice then lasts for the page's lifetime, and the notice is simply shown again next time.
 *
 * The same holds for the congratulation of the finished path (OB-402): the reward whose full-screen congratulation this device has shown is
 * remembered per account (by the instant it was issued, so a reward is never shown twice here), and the congratulation never asks again.
 */
@Injectable({ providedIn: 'root' })
export class PathPreferences {
    private readonly auth = inject(AuthService);
    private readonly account = computed(() => this.auth.user()?.accountId ?? null);
    /** What was set in this page, per account, over what the storage said (and the only memory when storage throws). */
    private readonly session = signal<ReadonlyMap<string, boolean>>(new Map());
    private readonly seenSession = signal<ReadonlySet<string>>(new Set());
    /** The rewards whose congratulation was shown in this page, per account (and the only memory when storage throws). */
    private readonly rewardSession = signal<ReadonlyMap<string, string>>(new Map());

    /** The learner hid «Путь»: no milestone toasts, and the profile block shrinks to the link. */
    readonly hidden = computed(() => {
        const account = this.account();
        if (account === null) return false;
        return this.session().get(account) ?? read(PATH_HIDDEN_STORAGE_PREFIX + account);
    });

    /** Whether the «Новое: Путь Mnema» notice was already shown to this account on this device. */
    readonly noticeSeen = computed(() => {
        const account = this.account();
        return account === null || this.seenSession().has(account) || read(PATH_NOTICE_STORAGE_PREFIX + account);
    });

    /** Whether this device has already shown the congratulation of the reward issued at `issuedAt` to this account. */
    rewardShown(issuedAt: string): boolean {
        const account = this.account();
        if (account === null) return true;
        const stored = this.rewardSession().get(account) ?? readText(PATH_REWARD_STORAGE_PREFIX + account);
        return stored === issuedAt;
    }

    /** The congratulation of the reward issued at `issuedAt` was put on the screen: not again on this device. */
    markRewardShown(issuedAt: string): void {
        const account = this.account();
        if (account === null) return;
        this.rewardSession.update(current => new Map(current).set(account, issuedAt));
        writeText(PATH_REWARD_STORAGE_PREFIX + account, issuedAt);
    }

    setHidden(hidden: boolean): void {
        const account = this.account();
        if (account === null) return;
        this.session.update(current => new Map(current).set(account, hidden));
        write(PATH_HIDDEN_STORAGE_PREFIX + account, hidden);
    }

    markNoticeSeen(): void {
        const account = this.account();
        if (account === null) return;
        this.seenSession.update(current => new Set(current).add(account));
        write(PATH_NOTICE_STORAGE_PREFIX + account, true);
    }
}

function read(key: string): boolean {
    try { return localStorage.getItem(key) === '1'; } catch { return false; }
}

function write(key: string, value: boolean): void {
    try {
        if (value) localStorage.setItem(key, '1'); else localStorage.removeItem(key);
    } catch { /* kept in memory only */ }
}

function readText(key: string): string | null {
    try { return localStorage.getItem(key); } catch { return null; }
}

function writeText(key: string, value: string): void {
    try { localStorage.setItem(key, value); } catch { /* kept in memory only */ }
}
