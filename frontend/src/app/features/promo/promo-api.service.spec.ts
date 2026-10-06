import { HttpErrorResponse, HttpHeaders, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';

import { PromoApiService, parsePopup, parseRedemption, promoProblem } from './promo-api.service';
import { PlansProtocolError } from '../plans/plans.models';

const PRIVATE = new HttpHeaders({ 'Cache-Control': 'private, no-store' });
const KEY = '5b9a2c6e-7d34-4f6b-9a31-0d2f8e6f1c11';

describe('PromoApiService', () => {
    let api: PromoApiService;
    let http: HttpTestingController;

    beforeEach(() => {
        TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
        api = TestBed.inject(PromoApiService);
        http = TestBed.inject(HttpTestingController);
    });
    afterEach(() => http.verify());

    const tier = { type: 'TIER_DAYS', plan: 'PLUS', validUntil: '2026-10-20T09:00:00Z', message: 'Plus до 20 октября, без автопродления.' };
    const discount = { type: 'DISCOUNT_PERCENT', plan: null, validUntil: '2026-10-31T20:59:59Z', percent: 20, message: 'Скидка 20 % применится к оплате до 31 октября.' };

    it('redeems with the code and the idempotency key and parses a tier', async () => {
        const result = firstValueFrom(api.redeem('PLUS15', KEY));
        const request = http.expectOne('/api/promo-codes/redemptions');
        expect(request.request.method).toBe('POST');
        expect(request.request.body).toEqual({ code: 'PLUS15' });
        expect(request.request.headers.get('Idempotency-Key')).toBe(KEY);
        request.flush(tier, { headers: PRIVATE });
        expect(await result).toEqual({ type: 'TIER_DAYS', plan: 'PLUS', validUntil: '2026-10-20T09:00:00Z', percent: null, message: tier.message });
    });

    it('parses a discount and refuses malformed answers', () => {
        expect(parseRedemption(discount)).toMatchObject({ type: 'DISCOUNT_PERCENT', plan: null, percent: 20 });
        for (const bad of [null, [], {}, { ...tier, percent: 20 }, { ...tier, plan: 'MAX' }, { ...tier, validUntil: 'soon' }, { ...tier, message: '' },
            { ...tier, type: 'GIFT' }, { ...tier, plan: null }, { ...discount, percent: 0 }, { ...discount, percent: undefined }, { ...tier, extra: 1 }]) {
            expect(() => parseRedemption(bad), JSON.stringify(bad)).toThrow(PlansProtocolError);
        }
    });

    it('rejects an answer that could be cached', async () => {
        const result = firstValueFrom(api.redeem('PLUS15', KEY)).then(() => null, (error: unknown) => error);
        http.expectOne('/api/promo-codes/redemptions').flush(tier, { headers: new HttpHeaders({ 'Cache-Control': 'public' }) });
        expect(await result).toBeInstanceOf(PlansProtocolError);
    });

    it('reads the popup state: a campaign when eligible, null otherwise', async () => {
        const campaign = { id: 'autumn', title: 'Осенняя скидка', body: 'Plus дешевле.', cta: 'Посмотреть тарифы', code: null };
        const eligible = firstValueFrom(api.popup());
        http.expectOne('/api/promo-popup').flush({ eligible: true, campaign }, { headers: PRIVATE });
        expect(await eligible).toEqual(campaign);
        const ineligible = firstValueFrom(api.popup());
        http.expectOne('/api/promo-popup').flush({ eligible: false, campaign: null }, { headers: PRIVATE });
        expect(await ineligible).toBeNull();
        expect(() => parsePopup({ eligible: false, campaign })).toThrow(PlansProtocolError);
        expect(() => parsePopup({ eligible: true, campaign: { ...campaign, extra: 1 } })).toThrow(PlansProtocolError);
        expect(() => parsePopup({ eligible: true, campaign: null })).toThrow(PlansProtocolError);
        expect(parsePopup({ eligible: true, campaign: { ...campaign, code: 'AUTUMN-26' } })?.code).toBe('AUTUMN-26');
    });

    it('sends popup events with the campaign id', async () => {
        const sent = firstValueFrom(api.popupEvent('autumn', 'DISMISSED'));
        const request = http.expectOne('/api/promo-popup/events');
        expect(request.request.body).toEqual({ campaignId: 'autumn', event: 'DISMISSED' });
        request.flush(null, { status: 204, statusText: 'No Content', headers: new HttpHeaders({ 'Promo-Event-Recorded': 'true' }) });
        expect(await sent).toBe(true);
    });

    it('keeps false, absent or unexpected recording headers unconfirmed', async () => {
        for (const recorded of ['false', undefined, 'yes']) {
            const result = firstValueFrom(api.popupEvent('autumn', 'DECLINED'));
            http.expectOne('/api/promo-popup/events').flush(null, { status: 204, statusText: 'No Content',
                headers: new HttpHeaders(recorded === undefined ? {} : { 'Promo-Event-Recorded': recorded }) });
            expect(await result).toBe(false);
        }
    });

    it('does not confirm a popup preference on an unexpected successful status', async () => {
        const result = firstValueFrom(api.popupEvent('autumn', 'DECLINED')).then(() => null, (error: unknown) => error);
        http.expectOne('/api/promo-popup/events').flush(null, { status: 200, statusText: 'OK' });
        expect(await result).toBeInstanceOf(PlansProtocolError);
    });
});

describe('promoProblem', () => {
    const failure = (status: number, code: unknown, headers?: Record<string, string>) =>
        new HttpErrorResponse({ status, error: code === undefined ? null : { code }, headers: new HttpHeaders(headers ?? {}) });

    it('reads the stable code and the wait of a 429', () => {
        expect(promoProblem(failure(422, 'PROMO_INVALID'))).toEqual({ code: 'PROMO_INVALID', retryAfterSeconds: null, retryable: false });
        expect(promoProblem(failure(409, 'PROMO_EXHAUSTED')).code).toBe('PROMO_EXHAUSTED');
        expect(promoProblem(failure(429, 'RATE_LIMITED', { 'Retry-After': '900' }))).toEqual({ code: 'RATE_LIMITED', retryAfterSeconds: 900, retryable: false });
        expect(promoProblem(failure(429, 'RATE_LIMITED', { 'Retry-After': 'soon' })).retryAfterSeconds).toBeNull();
        expect(promoProblem(failure(429, 'RATE_LIMITED')).retryAfterSeconds).toBeNull();
    });

    it('marks a lost answer and a server that could not decide as retryable with the same key', () => {
        expect(promoProblem(failure(0, undefined)).retryable).toBe(true);
        expect(promoProblem(failure(503, 'IDENTITY_UNAVAILABLE'))).toEqual({ code: 'IDENTITY_UNAVAILABLE', retryAfterSeconds: null, retryable: true });
        expect(promoProblem(failure(500, 'INTERNAL_ERROR')).code).toBe('UNKNOWN');
        expect(promoProblem(new PlansProtocolError('Malformed successful reply')).retryable).toBe(true);
        expect(promoProblem(failure(200, undefined)).retryable).toBe(true);
    });

    it('treats anything else as unknown and not retryable', () => {
        expect(promoProblem(new Error('x'))).toEqual({ code: 'UNKNOWN', retryAfterSeconds: null, retryable: false });
        expect(promoProblem(failure(400, 42)).code).toBe('UNKNOWN');
    });
});
