import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';

import { PlansApiService } from './plans-api.service';
import { PlansProtocolError } from './plans.models';
import { plansBody, plansHeaders } from './plans-test-data';

describe('PlansApiService', () => {
    let api: PlansApiService;
    let http: HttpTestingController;

    beforeEach(() => {
        TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
        api = TestBed.inject(PlansApiService);
        http = TestBed.inject(HttpTestingController);
    });
    afterEach(() => http.verify());

    async function load(body: unknown, headers = plansHeaders): Promise<unknown> {
        const result = firstValueFrom(api.load());
        const request = http.expectOne('/api/plans');
        expect(request.request.method).toBe('GET');
        request.flush(body as object, { headers });
        return result;
    }

    const rejection = (body: unknown, headers = plansHeaders) => load(body, headers).then(() => null, (error: unknown) => error);

    it('parses the catalogue and the current entitlement', async () => {
        const catalog = await load(plansBody({ current: 'PRO', source: 'BILLING' })) as any;
        expect(catalog.current).toEqual({ plan: 'PRO', period: 'MONTH', validUntil: '2026-10-31T21:00:00Z', autoRenew: false, source: 'BILLING' });
        expect(catalog.plans.map((entry: any) => entry.plan)).toEqual(['FREE', 'PLUS', 'PRO', 'MAX']);
        expect(catalog.plans[1]).toMatchObject({ priceRub: { month: 449, year: 5119 }, perDayRub: 15, recommendedFor: ['INTERVIEW', 'LANGUAGE', 'SELF'] });
        expect(catalog.plans[3].allowances.voiceMinutesPerMonth).toBeNull();
    });

    it('accepts a catalogue without the Max tier', async () => {
        expect(((await load(plansBody({ teaser: false }))) as any).plans).toHaveLength(3);
    });

    it('rejects a response that could be cached', async () => {
        expect(await rejection(plansBody(), plansHeaders.set('Cache-Control', 'public'))).toBeInstanceOf(PlansProtocolError);
    });

    it('rejects unknown, missing and malformed members', async () => {
        const mutations: ((body: Record<string, any>) => void)[] = [
            body => { body['extra'] = 1; },
            body => { delete body['current']; },
            body => { body['current']['plan'] = 'ULTRA'; },
            body => { body['current']['autoRenew'] = 'no'; },
            body => { body['current']['validUntil'] = 'tomorrow'; },
            body => { body['plans'] = []; },
            body => { body['plans'][1]['plan'] = 'FREE'; },
            body => { body['plans'] = body['plans'].slice(1); },
            body => { [body['plans'][1], body['plans'][2]] = [body['plans'][2], body['plans'][1]]; },
            body => { body['plans'][3]['availability'] = 'AVAILABLE'; },
            body => { body['plans'][0]['availability'] = 'TEASER'; },
            body => { body['plans'][1]['priceRub']['month'] = -1; },
            body => { body['plans'][1]['priceRub']['month'] = 449.5; },
            body => { body['plans'][1]['highlights'] = []; },
            body => { body['plans'][1]['recommendedFor'] = ['FAME']; },
            body => { body['plans'][1]['allowances']['materialsPerMonth'] = { min: 5, max: 2 }; },
            body => { body['plans'][1]['allowances']['smartPlans']['window'] = 'DAY'; },
            body => { body['plans'][1]['unknown'] = true; }
        ];
        for (const mutate of mutations) {
            const body = plansBody();
            mutate(body);
            expect(await rejection(body)).toBeInstanceOf(PlansProtocolError);
        }
    });
});
