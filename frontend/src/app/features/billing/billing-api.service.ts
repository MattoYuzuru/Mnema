import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, catchError, map, throwError } from 'rxjs';

import { appConfig } from '../../app.config';
import { exact, instant, integer, nullable, oneOf, privateOk, protocol } from '../plans/wire';
import { BILLING_ERROR_CODES, BillingError, BillingErrorCode, CheckoutPeriod, ORDER_STATUSES, Order, PaidPlan } from './billing.models';

const ORDER_KEYS = ['orderId', 'plan', 'period', 'status', 'amountKopecks', 'listPriceKopecks', 'discountPercent', 'paymentUrl', 'expiresAt',
    'createdAt', 'paidAt', 'periodStart', 'periodEnd', 'receiptUrl'];
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/iu;
/** The hosts of the T-Bank hosted payment form (`contracts/billing/billing.json`, createCheckout notes). */
const TRUSTED_PAYMENT_HOSTS: readonly string[] = ['pay.tbank-online.com', 'securepay.tinkoff.ru', 'securepay.tbank.ru'];
/** The printable receipt of «Мой налог»: `https://lknpd.nalog.ru/api/v1/receipt/{inn}/{receiptId}/print`. */
const RECEIPT_URL = /^https:\/\/lknpd\.nalog\.ru\/api\/v1\/receipt\/[0-9]{12}\/[A-Za-z0-9_-]{1,64}\/print$/u;
const MAX_AMOUNT_KOPECKS = 100_000_000;

/**
 * HTTP boundary of billing. `createCheckout` is idempotent through its `Idempotency-Key` (the same key and body is the same
 * order), and the amount is never sent: the server prices the order. Nothing here confirms a payment; only the order read
 * says what the bank reported. Failures arrive as a {@link BillingError} with a stable code.
 */
@Injectable({ providedIn: 'root' })
export class BillingApiService {
    private readonly http = inject(HttpClient);
    private readonly baseUrl = appConfig.learningApiBaseUrl.replace(/\/$/u, '');

    createCheckout(plan: PaidPlan, period: CheckoutPeriod, idempotencyKey: string): Observable<Order> {
        return this.http.post<unknown>(`${this.baseUrl}/billing/checkout`, { plan, period },
            { observe: 'response', headers: { 'Idempotency-Key': idempotencyKey } }).pipe(
            map(response => {
                privateOk(response, 201);
                return parseOrder(response.body);
            }),
            catchError((error: unknown) => throwError(() => billingError(error))));
    }

    getOrder(orderId: string): Observable<Order> {
        if (!isOrderId(orderId)) return throwError(() => new BillingError('NOT_FOUND'));
        return this.http.get<unknown>(`${this.baseUrl}/billing/orders/${encodeURIComponent(orderId)}`, { observe: 'response' }).pipe(
            map(response => {
                privateOk(response);
                return parseOrder(response.body);
            }),
            catchError((error: unknown) => throwError(() => billingError(error))));
    }
}

export function isOrderId(value: string): boolean { return UUID.test(value); }

/** `https` on exactly one of the bank's hosts, with no credentials and no explicit port. Anything else is never navigated to. */
export function isTrustedPaymentUrl(value: string): boolean {
    let url: URL;
    try {
        url = new URL(value);
    } catch {
        return false;
    }
    return url.protocol === 'https:' && url.username === '' && url.password === '' && url.port === ''
        && TRUSTED_PAYMENT_HOSTS.includes(url.hostname) && url.host === url.hostname;
}

export function parseOrder(value: unknown): Order {
    const body = exact(value, ORDER_KEYS);
    const orderId = body['orderId'];
    if (typeof orderId !== 'string' || !isOrderId(orderId)) throw protocol('Invalid order id.');
    const discountPercent = nullable(body['discountPercent'], percent => integer(percent, 90));
    if (discountPercent === 0) throw protocol('Invalid discount.');
    return {
        orderId, plan: oneOf(body['plan'], ['PLUS', 'PRO'] as const), period: oneOf(body['period'], ['MONTH'] as const),
        status: oneOf(body['status'], ORDER_STATUSES), amountKopecks: integer(body['amountKopecks'], MAX_AMOUNT_KOPECKS),
        listPriceKopecks: integer(body['listPriceKopecks'], MAX_AMOUNT_KOPECKS), discountPercent,
        paymentUrl: nullable(body['paymentUrl'], paymentUrl),
        expiresAt: instant(body['expiresAt']), createdAt: instant(body['createdAt']), paidAt: nullable(body['paidAt'], instant),
        periodStart: nullable(body['periodStart'], instant), periodEnd: nullable(body['periodEnd'], instant),
        receiptUrl: receiptUrl(body['receiptUrl'])
    };
}

/** The receipt link is a courtesy: anything but the tax service's print address is dropped, and the paid page stays whole. */
function receiptUrl(value: unknown): string | null {
    return typeof value === 'string' && RECEIPT_URL.test(value) ? value : null;
}

function paymentUrl(value: unknown): string {
    if (typeof value !== 'string' || !isTrustedPaymentUrl(value)) throw protocol('Untrusted payment URL.');
    return value;
}

/** The stable `code` of a Problem Details answer; a network failure or a malformed answer is `UNKNOWN`. */
export function billingError(error: unknown): BillingError {
    if (error instanceof BillingError) return error;
    if (!(error instanceof HttpErrorResponse)) return new BillingError('UNKNOWN');
    // Learning answers an unknown or foreign order with 404 RESOURCE_NOT_FOUND; the status alone decides.
    if (error.status === 404) return new BillingError('NOT_FOUND');
    const raw: unknown = (error.error as { code?: unknown } | null)?.code;
    const code: BillingErrorCode = (BILLING_ERROR_CODES as readonly unknown[]).includes(raw) ? raw as BillingErrorCode : 'UNKNOWN';
    return new BillingError(code);
}
