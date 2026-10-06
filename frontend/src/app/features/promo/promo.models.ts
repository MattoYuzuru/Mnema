/** Wire and view models of the promo endpoints (`/api/promo-codes/redemptions`, `/api/promo-popup`). Instants are UTC RFC 3339. */

export type PromoType = 'TIER_DAYS' | 'TIER_MONTHS' | 'DISCOUNT_PERCENT';

/** The answer to a redemption: a tier until `validUntil` (no renewal), or a pending discount of `percent`. `message` is the server's own sentence. */
export interface PromoRedemption {
    readonly type: PromoType;
    readonly plan: 'PLUS' | 'PRO' | null;
    readonly validUntil: string;
    readonly percent: number | null;
    readonly message: string;
}

/** The stable problem codes of a refused redemption, plus the client's own for a failure without a problem body. */
export type PromoProblemCode = 'PROMO_INVALID' | 'PROMO_EXHAUSTED' | 'PROMO_ALREADY_USED' | 'PROMO_NOT_ELIGIBLE' | 'PROMO_VELOCITY'
    | 'RATE_LIMITED' | 'IDENTITY_UNAVAILABLE' | 'UNKNOWN';

export interface PromoProblem {
    readonly code: PromoProblemCode;
    /** Whole seconds from `Retry-After` of a 429, else null. */
    readonly retryAfterSeconds: number | null;
    /** The same command may be sent again with the same key: no answer reached the client, or the server could not decide. */
    readonly retryable: boolean;
}

export interface PromoCampaign {
    readonly id: string;
    readonly title: string;
    readonly body: string;
    readonly cta: string;
    /** A code the campaign offers: it is put into the promo field, never applied for the learner. */
    readonly code: string | null;
}

export type PopupEvent = 'SHOWN' | 'DISMISSED' | 'DECLINED';

export class PromoProtocolError extends Error {
    constructor(message: string) {
        super(message);
        this.name = 'PromoProtocolError';
    }
}
