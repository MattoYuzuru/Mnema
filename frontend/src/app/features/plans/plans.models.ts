/** Wire and view models of `GET /api/plans` (the paywall catalogue). Prices are whole rubles; instants are UTC RFC 3339. */
import type { LearningGoal } from '../goal/goal.models';

export const PLAN_IDS = ['FREE', 'PLUS', 'PRO', 'MAX'] as const;
export type PlanId = (typeof PLAN_IDS)[number];

export type PlanPeriod = 'MONTH' | 'YEAR';
/** `TEASER` is a tier shown as «В работе»: it cannot be chosen. */
export type PlanAvailability = 'AVAILABLE' | 'TEASER';
export type EntitlementSource = 'CONFIG' | 'BILLING' | 'PROMO';

/** The entitlement now. `autoRenew` is false until recurring payments exist. */
export interface PlansCurrent {
    readonly plan: PlanId;
    readonly period: PlanPeriod;
    readonly validUntil: string;
    readonly autoRenew: boolean;
    readonly source: EntitlementSource;
}

export interface PlanRange {
    readonly min: number;
    readonly max: number;
}

/** The comparison table. A null limit is unlimited within fair use; 0 means the tier does not offer it. */
export interface PlanAllowances {
    readonly materialsPerMonth: PlanRange;
    readonly voiceMinutesPerMonth: number | null;
    readonly voiceMinutesPerDay: number;
    readonly answerChecksPerMonth: number;
    readonly answerChecksPerDay: number;
    readonly podcastsPerMonth: number;
    readonly qualityImagesPerMonth: number;
    readonly factChecksPerMonth: number;
    readonly smartPlans: { readonly limit: number; readonly window: 'WEEK' | 'MONTH' };
}

export interface PlanEntry {
    readonly plan: PlanId;
    readonly availability: PlanAvailability;
    readonly priceRub: { readonly month: number; readonly year: number };
    readonly perDayRub: number;
    readonly yearDiscountPercent: number;
    /** Three human-unit lines from the allowances, ready to show. */
    readonly highlights: readonly string[];
    readonly allowances: PlanAllowances;
    /** Learning goals this tier is recommended for. */
    readonly recommendedFor: readonly LearningGoal[];
}

/** A percentage a promo code earned that billing applies to the next purchase; `plan` null means either paid tier. */
export interface PendingDiscount {
    readonly percent: number;
    readonly plan: 'PLUS' | 'PRO' | null;
    readonly validUntil: string;
}

export interface PlansCatalog {
    readonly current: PlansCurrent;
    readonly plans: readonly PlanEntry[];
    /** The variant the server assigned to this account in each enabled A/B experiment, by key. */
    readonly experiments: Readonly<Record<string, string>>;
    readonly pendingDiscount: PendingDiscount | null;
}

export class PlansProtocolError extends Error {
    constructor(message: string) {
        super(message);
        this.name = 'PlansProtocolError';
    }
}
