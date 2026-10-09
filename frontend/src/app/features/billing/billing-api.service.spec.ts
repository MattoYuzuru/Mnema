import { HttpHeaders } from '@angular/common/http';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';

import billingContract from '../../../../../contracts/billing/billing.json';
import { PlansProtocolError } from '../plans/plans.models';
import { BillingApiService, isTrustedPaymentUrl, parseOrder } from './billing-api.service';
import { BillingError, BillingErrorCode } from './billing.models';

const examples = billingContract.examples as Record<string, Record<string, unknown>>;
const headers = new HttpHeaders({ 'Cache-Control': 'private, no-store' });
const ORDER_ID = String(examples['orderPending']['orderId']);

function example(name: string): Record<string, any> { return structuredClone(examples[name]); }

/** The contract's Order members, read from `contracts/billing/billing.json`: the reader must accept exactly these. */
const ORDER_KEYS = Object.keys(billingContract.schemas.Order);

describe('Billing wire contract (contracts/billing/billing.json)', () => {
    let api: BillingApiService;
    let http: HttpTestingController;

    beforeEach(() => {
        TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
        api = TestBed.inject(BillingApiService);
        http = TestBed.inject(HttpTestingController);
    });
    afterEach(() => http.verify());

    const rejection = <T>(result: Promise<T>) => result.then(() => null, (error: unknown) => error);

    describe('Order reader', () => {
        it('accepts the contract examples and exactly the contract key set', () => {
            for (const name of ['orderPending', 'orderPaid', 'orderDiscountedPro']) {
                expect(Object.keys(examples[name])).toEqual(ORDER_KEYS);
                expect(parseOrder(examples[name])).toEqual(examples[name]);
            }
            expect(parseOrder(examples['orderPending'])).toMatchObject({ status: 'PENDING', plan: 'PLUS', amountKopecks: 44900, paymentUrl: 'https://pay.tbank-online.com/So6mQeQB' });
            expect(parseOrder(examples['orderPaid'])).toMatchObject({ status: 'PAID', periodEnd: '2026-11-09T09:03:00Z', paymentUrl: null });
            expect(parseOrder(examples['orderDiscountedPro'])).toMatchObject({ plan: 'PRO', discountPercent: 20, amountKopecks: 79200, listPriceKopecks: 99000 });
        });

        it('rejects an extra and a missing member, a bad status and a malformed value', () => {
            const mutations: ((order: Record<string, any>) => void)[] = [
                order => { order['extra'] = 1; },
                ...ORDER_KEYS.map(key => (order: Record<string, any>) => { delete order[key]; }),
                order => { order['status'] = 'CREATED'; },
                order => { order['status'] = 'pending'; },
                order => { order['plan'] = 'FREE'; },
                order => { order['plan'] = 'MAX'; },
                order => { order['period'] = 'YEAR'; },
                order => { order['orderId'] = 'not-a-uuid'; },
                order => { order['amountKopecks'] = 449.5; },
                order => { order['amountKopecks'] = -1; },
                order => { order['discountPercent'] = 0; },
                order => { order['discountPercent'] = 91; },
                order => { order['expiresAt'] = 'tomorrow'; },
                order => { order['paidAt'] = 'yesterday'; }
            ];
            for (const mutate of mutations) {
                const order = example('orderPending');
                mutate(order);
                expect(() => parseOrder(order)).toThrow(PlansProtocolError);
            }
        });

        it('accepts a receipt link only on the tax service print address', () => {
            const receipt = String(examples['orderPaid']['receiptUrl']);
            expect(parseOrder(examples['orderPaid']).receiptUrl).toBe(receipt);
            for (const url of ['http://lknpd.nalog.ru/api/v1/receipt/770123456789/2agnbqj3tw/print', 'https://evil.example/api/v1/receipt/770123456789/2agnbqj3tw/print',
                'https://lknpd.nalog.ru.evil.example/api/v1/receipt/770123456789/2agnbqj3tw/print', 'https://user@lknpd.nalog.ru/api/v1/receipt/770123456789/2agnbqj3tw/print',
                'https://lknpd.nalog.ru:8443/api/v1/receipt/770123456789/2agnbqj3tw/print', 'https://lknpd.nalog.ru/api/v1/receipt/7701/2agnbqj3tw/print',
                'https://lknpd.nalog.ru/api/v1/receipt/770123456789/../print', 'https://lknpd.nalog.ru/api/v1/receipt/770123456789/2agnbqj3tw/print?x=1',
                'https://lknpd.nalog.ru/api/v1/receipt/770123456789/2agnbqj3tw/json', 'javascript:alert(1)', '']) {
                const order = example('orderPaid');
                order['receiptUrl'] = url;
                expect(() => parseOrder(order), url).toThrow(PlansProtocolError);
            }
        });

        it('rejects a payment URL outside the bank hosts', () => {
            for (const url of ['http://pay.tbank-online.com/x', 'https://evil.example/pay', 'https://pay.tbank-online.com.evil.example/x',
                'https://user:secret@pay.tbank-online.com/x', 'https://pay.tbank-online.com:8443/x', 'javascript:alert(1)', 'pay.tbank-online.com/x', '']) {
                const order = example('orderPending');
                order['paymentUrl'] = url;
                expect(() => parseOrder(order)).toThrow(PlansProtocolError);
            }
        });
    });

    describe('isTrustedPaymentUrl', () => {
        it('allows https on exactly the three bank hosts', () => {
            for (const host of ['pay.tbank-online.com', 'securepay.tinkoff.ru', 'securepay.tbank.ru']) {
                expect(isTrustedPaymentUrl(`https://${host}/So6mQeQB`)).toBe(true);
            }
        });

        it('refuses lookalikes, plain http, credentials, ports and garbage', () => {
            for (const url of ['https://tbank-online.com/x', 'https://sub.pay.tbank-online.com/x', 'https://pay.tbank-online.com.evil.example/x',
                'https://evil.example/https://pay.tbank-online.com/', 'http://securepay.tinkoff.ru/x', 'https://a@securepay.tinkoff.ru/x',
                'https://a:b@securepay.tinkoff.ru/x', 'https://securepay.tbank.ru:444/x', 'ftp://securepay.tbank.ru/x', '//securepay.tbank.ru/x', 'nope', '']) {
                expect(isTrustedPaymentUrl(url)).toBe(false);
            }
        });
    });

    describe('createCheckout', () => {
        it('posts the plan and period with the idempotency key and parses the 201 order', async () => {
            const result = firstValueFrom(api.createCheckout('PLUS', 'MONTH', 'key-1'));
            const request = http.expectOne('/api/billing/checkout');
            expect(request.request.method).toBe('POST');
            expect(request.request.headers.get('Idempotency-Key')).toBe('key-1');
            expect(request.request.body).toEqual(billingContract.examples.createCheckoutRequest);
            request.flush(examples['orderPending'], { status: 201, statusText: 'Created', headers });
            expect(await result).toEqual(examples['orderPending']);
        });

        it('refuses a response that is not a private 201', async () => {
            for (const [status, cache] of [[200, 'private, no-store'], [201, 'public']] as const) {
                const result = rejection(firstValueFrom(api.createCheckout('PRO', 'MONTH', 'key-2')));
                http.expectOne('/api/billing/checkout').flush(examples['orderPending'],
                    { status, statusText: 'x', headers: new HttpHeaders({ 'Cache-Control': cache }) });
                expect(await result).toMatchObject({ code: 'UNKNOWN' });
            }
        });

        it('treats a malformed 201 body as an unknown outcome (the retry reuses the key)', async () => {
            const result = rejection(firstValueFrom(api.createCheckout('PLUS', 'MONTH', 'key-3')));
            http.expectOne('/api/billing/checkout').flush({ ...examples['orderPending'], paymentUrl: 'https://evil.example/' },
                { status: 201, statusText: 'Created', headers });
            const error = await result;
            expect(error).toBeInstanceOf(BillingError);
            expect((error as BillingError).code).toBe('UNKNOWN');
        });

        it.each<[number, string, BillingErrorCode]>([
            [409, 'CAPABILITY_UNAVAILABLE', 'CAPABILITY_UNAVAILABLE'],
            [409, 'BILLING_PLAN_BELOW_CURRENT', 'BILLING_PLAN_BELOW_CURRENT'],
            [429, 'RATE_LIMITED', 'RATE_LIMITED'],
            [503, 'PAYMENT_PROVIDER_UNAVAILABLE', 'PAYMENT_PROVIDER_UNAVAILABLE'],
            [404, 'NOT_FOUND', 'NOT_FOUND'],
            [409, 'IDEMPOTENCY_CONFLICT', 'UNKNOWN'],
            [400, 'INVALID_REQUEST', 'UNKNOWN'],
            [500, 'SOMETHING_ELSE', 'UNKNOWN']
        ])('maps %i %s to %s', async (status, code, expected) => {
            const result = rejection(firstValueFrom(api.createCheckout('PLUS', 'MONTH', 'key-4')));
            http.expectOne('/api/billing/checkout').flush({ type: 'about:blank', title: 'x', status, code }, { status, statusText: 'x' });
            expect(await result).toMatchObject({ name: 'BillingError', code: expected });
        });

        it('maps a network failure and a body without a code to UNKNOWN', async () => {
            const lost = rejection(firstValueFrom(api.createCheckout('PLUS', 'MONTH', 'key-5')));
            http.expectOne('/api/billing/checkout').error(new ProgressEvent('error'));
            expect(await lost).toMatchObject({ code: 'UNKNOWN' });
            const bare = rejection(firstValueFrom(api.createCheckout('PLUS', 'MONTH', 'key-5')));
            http.expectOne('/api/billing/checkout').flush('gateway', { status: 502, statusText: 'Bad Gateway' });
            expect(await bare).toMatchObject({ code: 'UNKNOWN' });
        });
    });

    describe('getOrder', () => {
        it('reads the order by its encoded id', async () => {
            const result = firstValueFrom(api.getOrder(ORDER_ID));
            const request = http.expectOne(`/api/billing/orders/${ORDER_ID}`);
            expect(request.request.method).toBe('GET');
            request.flush(examples['orderPaid'], { headers });
            expect((await result).status).toBe('PAID');
        });

        it('never calls the server for an id that is not a UUID', async () => {
            for (const id of ['', 'abc', '../plans', `${ORDER_ID}/x`, `${ORDER_ID}?a=b`]) {
                expect(await rejection(firstValueFrom(api.getOrder(id)))).toMatchObject({ code: 'NOT_FOUND' });
            }
            http.expectNone(() => true);
        });

        it('maps NOT_FOUND and rejects a cacheable answer', async () => {
            const missing = rejection(firstValueFrom(api.getOrder(ORDER_ID)));
            http.expectOne(`/api/billing/orders/${ORDER_ID}`).flush({ code: 'RESOURCE_NOT_FOUND' }, { status: 404, statusText: 'Not Found' });
            expect(await missing).toMatchObject({ code: 'NOT_FOUND' });
            const cached = rejection(firstValueFrom(api.getOrder(ORDER_ID)));
            http.expectOne(`/api/billing/orders/${ORDER_ID}`).flush(examples['orderPaid'], { headers: new HttpHeaders({ 'Cache-Control': 'max-age=60' }) });
            expect(await cached).toMatchObject({ code: 'UNKNOWN' });
        });
    });
});
