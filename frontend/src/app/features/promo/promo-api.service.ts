import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

import { appConfig } from '../../app.config';
import { bool, exact, instant, integer, nullable, oneOf, privateOk, protocol, text } from '../plans/wire';
import { PopupEvent, PromoCampaign, PromoProblem, PromoProblemCode, PromoRedemption } from './promo.models';

const PROBLEM_CODES: readonly PromoProblemCode[] = ['PROMO_INVALID', 'PROMO_EXHAUSTED', 'PROMO_ALREADY_USED', 'PROMO_NOT_ELIGIBLE', 'PROMO_VELOCITY',
    'RATE_LIMITED', 'IDENTITY_UNAVAILABLE'];

/**
 * HTTP boundary of promo codes and the promo popup. A redemption carries an `Idempotency-Key`: the same key with the same
 * code is the same command, so a retry after a lost answer never redeems twice. Nothing here changes an entitlement by
 * itself; the server does, and the caller reloads `/plans` to see it.
 */
@Injectable({ providedIn: 'root' })
export class PromoApiService {
    private readonly http = inject(HttpClient);
    private readonly baseUrl = appConfig.learningApiBaseUrl.replace(/\/$/u, '');

    redeem(code: string, idempotencyKey: string): Observable<PromoRedemption> {
        return this.http.post<unknown>(`${this.baseUrl}/promo-codes/redemptions`, { code },
            { observe: 'response', headers: { 'Idempotency-Key': idempotencyKey } }).pipe(map(response => {
            privateOk(response);
            return parseRedemption(response.body);
        }));
    }

    popup(): Observable<PromoCampaign | null> {
        return this.http.get<unknown>(`${this.baseUrl}/promo-popup`, { observe: 'response' }).pipe(map(response => {
            privateOk(response);
            return parsePopup(response.body);
        }));
    }

    popupEvent(campaignId: string, event: PopupEvent): Observable<void> {
        return this.http.post<void>(`${this.baseUrl}/promo-popup/events`, { campaignId, event }).pipe(map(() => undefined));
    }
}

export function parseRedemption(value: unknown): PromoRedemption {
    const type = oneOf((value as Record<string, unknown> | null)?.['type'], ['TIER_DAYS', 'TIER_MONTHS', 'DISCOUNT_PERCENT'] as const);
    const body = exact(value, type === 'DISCOUNT_PERCENT' ? ['type', 'plan', 'validUntil', 'percent', 'message']
        : ['type', 'plan', 'validUntil', 'message']);
    const percent = type === 'DISCOUNT_PERCENT' ? integer(body['percent'], 90) : null;
    if (percent === 0) throw protocol('Invalid discount.');
    return {
        type, plan: nullable(body['plan'], plan => oneOf(plan, ['PLUS', 'PRO'] as const)), validUntil: instant(body['validUntil']),
        percent, message: text(body['message'], 300)
    };
}

export function parsePopup(value: unknown): PromoCampaign | null {
    const body = exact(value, ['eligible', 'campaign']);
    if (!bool(body['eligible'])) {
        if (body['campaign'] !== null) throw protocol('Invalid popup.');
        return null;
    }
    const campaign = exact(body['campaign'], ['id', 'title', 'body', 'cta', 'code']);
    return {
        id: text(campaign['id'], 60), title: text(campaign['title'], 120), body: text(campaign['body'], 500), cta: text(campaign['cta'], 40),
        code: nullable(campaign['code'], code => text(code, 64))
    };
}

/** Reads the problem of a failed redemption: its stable `code`, the `Retry-After` of a 429, and whether the same key may be tried again. */
export function promoProblem(error: unknown): PromoProblem {
    if (!(error instanceof HttpErrorResponse)) return { code: 'UNKNOWN', retryAfterSeconds: null, retryable: false };
    const raw: unknown = (error.error as { code?: unknown } | null)?.code;
    const code = (PROBLEM_CODES as readonly unknown[]).includes(raw) ? raw as PromoProblemCode : 'UNKNOWN';
    const header = Number(error.headers?.get('Retry-After'));
    const retryAfterSeconds = code === 'RATE_LIMITED' && Number.isSafeInteger(header) && header > 0 ? header : null;
    // Status 0 is a lost answer, 5xx a server that could not decide: the command may or may not have happened.
    return { code, retryAfterSeconds, retryable: error.status === 0 || error.status >= 500 };
}
