import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';

import allowances from '../../../../../contracts/usage/allowances-v1.json';
import rateCard from '../../../../../contracts/usage/rate-card-v1.json';
import { UsageApiService } from './usage-api.service';
import { MATERIAL_MEDIUM_CREDITS, describeUsage } from './usage-view';
import { freeUsage, maxUsage, plusUsage, privateHeaders, usageContract } from './usage-test-data';

const NBSP = '\u00a0';

/**
 * contracts/usage/usage.json is the wire contract shared with the backend: the usage examples must parse strictly and
 * produce the figures the profile shows, and the client-side «≈ N материалов» weight must equal the rate card.
 */
describe('Usage wire contract (contracts/usage/usage.json)', () => {
    let api: UsageApiService;
    let http: HttpTestingController;

    beforeEach(() => {
        TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
        api = TestBed.inject(UsageApiService);
        http = TestBed.inject(HttpTestingController);
    });
    afterEach(() => http.verify());

    async function view(body: Record<string, unknown>) {
        const result = firstValueFrom(api.load());
        http.expectOne('/api/usage').flush(body, { headers: privateHeaders });
        return describeUsage(await result);
    }

    it('declares GET /api/usage as private, no-store', () => {
        const endpoint = (usageContract['endpoints'] as { operationId: string; method: string; path: string; success: { headers: object } }[])
            .find(candidate => candidate.operationId === 'getUsage')!;
        expect([endpoint.method, endpoint.path]).toEqual(['GET', '/api/usage']);
        expect(endpoint.success.headers).toEqual(privateHeaders);
    });

    it('maps the PLUS example: the whole bar open, no ticks, nothing near a limit', async () => {
        expect(await view(plusUsage())).toEqual({
            planName: 'Plus', label: 'ИИ в октябре', total: 360, used: 42, reserved: 10, unlocked: null, ticks: [],
            remaining: { count: 30, forms: ['материал', 'материала', 'материалов'] }, nextUnlock: null, resetsOn: '1 ноября',
            burstNote: null, counters: []
        });
    });

    it('maps the FREE example: weekly ticks, the next unlock on the account calendar and the speech counter above 80 %', async () => {
        const free = await view(freeUsage());
        expect(free).toMatchObject({ planName: 'Free', total: 50, used: 8, reserved: 0, unlocked: 13,
            remaining: { count: 0 }, nextUnlock: '5 октября', resetsOn: '1 ноября', burstNote: null });
        expect(free.ticks).toEqual([13 / 50, 26 / 50, 38 / 50]);
        expect(free.counters).toEqual([{ id: 'stt',
            text: `Распознавание речи: 52${NBSP}из${NBSP}60${NBSP}минут за месяц, сегодня 3${NBSP}из${NBSP}10` }]);
    });

    it('maps the MAX example: no monthly speech limit means no counter', async () => {
        const max = await view(maxUsage());
        expect(max).toMatchObject({ planName: 'Max', total: 1780, remaining: { count: 178 }, counters: [] });
    });

    it('reads the rate card weight of a medium material', () => {
        const operation = rateCard.operations.find(candidate => candidate.id === 'MATERIAL_MEDIUM');
        expect(operation?.credits).toBe(MATERIAL_MEDIUM_CREDITS);
    });

    it('keeps the Free bar equal to the sum of the portions and the plan bars equal to the allowances', () => {
        const free = usageContract['usageResponseFree'];
        expect(free.weeklyUnlock.portions.reduce((sum: number, portion: number) => sum + portion, 0)).toBe(free.credits.total);
        const plans = allowances.plans as unknown as Record<string, { monthlyCredits: number }>;
        expect(plans['FREE'].monthlyCredits).toBe(free.credits.total);
        expect(plans['PLUS'].monthlyCredits).toBe(usageContract['usageResponse'].credits.total);
    });
});
