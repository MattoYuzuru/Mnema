/** Wire and view models of the billing contract (`contracts/billing/billing.json`). Amounts are kopecks; instants are UTC RFC 3339. */

export type PaidPlan = 'PLUS' | 'PRO';
export type CheckoutPeriod = 'MONTH';
export const ORDER_STATUSES = ['PENDING', 'PAID', 'FAILED', 'REFUNDED', 'REVIEW'] as const;
export type OrderStatus = (typeof ORDER_STATUSES)[number];

/** One purchase attempt of one plan for one period. `paymentUrl` is set only while `PENDING`, and only on a trusted bank host. */
export interface Order {
    readonly orderId: string;
    readonly plan: PaidPlan;
    readonly period: CheckoutPeriod;
    readonly status: OrderStatus;
    readonly amountKopecks: number;
    readonly listPriceKopecks: number;
    readonly discountPercent: number | null;
    readonly paymentUrl: string | null;
    readonly expiresAt: string;
    readonly createdAt: string;
    readonly paidAt: string | null;
    readonly periodStart: string | null;
    readonly periodEnd: string | null;
}

/** The stable `code` of a failed billing call; anything else (a network failure, a malformed answer) is `UNKNOWN`. */
export type BillingErrorCode = 'CAPABILITY_UNAVAILABLE' | 'BILLING_PLAN_BELOW_CURRENT' | 'RATE_LIMITED' | 'PAYMENT_PROVIDER_UNAVAILABLE'
    | 'NOT_FOUND' | 'UNKNOWN';

export const BILLING_ERROR_CODES: readonly BillingErrorCode[] = ['CAPABILITY_UNAVAILABLE', 'BILLING_PLAN_BELOW_CURRENT', 'RATE_LIMITED',
    'PAYMENT_PROVIDER_UNAVAILABLE', 'NOT_FOUND', 'UNKNOWN'];

export class BillingError extends Error {
    constructor(readonly code: BillingErrorCode) {
        super(`Billing call failed: ${code}.`);
        this.name = 'BillingError';
    }
}
