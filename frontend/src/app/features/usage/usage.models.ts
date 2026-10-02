/** Wire and view models of `GET /api/usage` (contracts/usage, `usage-v1`). Credits are integers; instants are UTC RFC 3339. */

export const USAGE_PLANS = ['FREE', 'PLUS', 'PRO', 'MAX'] as const;
export type UsagePlan = (typeof USAGE_PLANS)[number];

export interface UsagePeriod {
    readonly periodId: string;
    readonly start: string;
    readonly end: string;
}

export interface UsageCredits {
    readonly total: number;
    /** What may be spent now: the whole bar on paid plans, the portions unlocked so far on Free. */
    readonly unlocked: number;
    readonly used: number;
    readonly reserved: number;
    readonly remaining: number;
    /** `(used + reserved) / total`, rounded half up. */
    readonly percentUsed: number;
}

/** Paid plans only: limits the debits of one calendar day. */
export interface UsageDailyBurst {
    readonly limitCredits: number;
    readonly debitedTodayCredits: number;
    readonly remainingTodayCredits: number;
    readonly resetsAt: string;
    readonly deferredUntil: string | null;
}

/** Free only. `nextUnlockAt` and `nextPortionCredits` are null once the whole bar is unlocked. */
export interface UsageWeeklyUnlock {
    readonly portions: readonly number[];
    readonly unlockedPortions: number;
    readonly nextUnlockAt: string | null;
    readonly nextPortionCredits: number | null;
}

/** A quiet fair-use counter outside the bar; `limit` is null where the plan has no monthly limit (MAX speech-to-text). */
export interface UsageFairUseBucket {
    readonly used: number;
    readonly limit: number | null;
    readonly usedToday: number;
    readonly limitToday: number | null;
    /** True above 80 % of the monthly limit: only then does the client show the counter. */
    readonly warn: boolean;
}

export interface UsageFairUse {
    readonly stt: UsageFairUseBucket;
    readonly assessment: UsageFairUseBucket;
}

export interface UsageSnapshot {
    readonly rateCardVersion: string;
    readonly plan: UsagePlan;
    readonly period: UsagePeriod;
    readonly credits: UsageCredits;
    readonly dailyBurst: UsageDailyBurst | null;
    readonly weeklyUnlock: UsageWeeklyUnlock | null;
    readonly fairUse: UsageFairUse;
    readonly updatedAt: string;
}

export class UsageProtocolError extends Error {
    constructor(message: string) {
        super(message);
        this.name = 'UsageProtocolError';
    }
}
