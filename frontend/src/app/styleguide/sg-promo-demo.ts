import { HttpErrorResponse, HttpHeaders } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable, delay, of, throwError } from 'rxjs';

import { PromoRedemption } from '../features/promo/promo.models';

/**
 * The promo field of the styleguide talks to this instead of the server (the page needs no backend): «PLUS15» succeeds, «USED» says it was
 * used already, «WAIT» is rate limited, anything else is not a valid code.
 */
@Injectable()
export class DemoPromoApi {
    redeem(code: string): Observable<PromoRedemption> {
        const normalized = code.trim().toUpperCase();
        if (normalized === 'PLUS15') {
            return of({ type: 'TIER_DAYS', plan: 'PLUS', validUntil: '2026-10-20T09:00:00Z', percent: null,
                message: 'Plus до 20 октября, без автопродления.' } as PromoRedemption).pipe(delay(250));
        }
        const problem = (status: number, body: { code: string }, headers = new HttpHeaders()): Observable<never> =>
            throwError(() => new HttpErrorResponse({ status, error: body, headers }));
        if (normalized === 'USED') return problem(409, { code: 'PROMO_ALREADY_USED' });
        if (normalized === 'WAIT') return problem(429, { code: 'RATE_LIMITED' }, new HttpHeaders({ 'Retry-After': '900' }));
        return problem(422, { code: 'PROMO_INVALID' });
    }
}
