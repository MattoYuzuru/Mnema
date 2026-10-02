import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';

import { UsageApiService } from './usage-api.service';
import { UsageProtocolError } from './usage.models';
import { clone, freeUsage, maxUsage, plusUsage, privateHeaders } from './usage-test-data';

describe('UsageApiService', () => {
    let api: UsageApiService;
    let http: HttpTestingController;

    beforeEach(() => {
        TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
        api = TestBed.inject(UsageApiService);
        http = TestBed.inject(HttpTestingController);
    });
    afterEach(() => http.verify());

    async function load(body: object | null, headers: Record<string, string> = privateHeaders) {
        const result = firstValueFrom(api.load());
        const request = http.expectOne('/api/usage');
        expect(request.request.method).toBe('GET');
        request.flush(body, { headers });
        return result;
    }

    async function rejection(mutate: (body: Record<string, any>) => void, base = plusUsage()): Promise<unknown> {
        mutate(base);
        return load(base).then(() => null, (error: unknown) => error);
    }

    it('parses a paid plan, Free with weekly portions and MAX with a speech velocity', async () => {
        expect(await load(plusUsage())).toMatchObject({ plan: 'PLUS', credits: { total: 360, remaining: 308, percentUsed: 14 },
            dailyBurst: { limitCredits: 126, deferredUntil: null }, weeklyUnlock: null });
        expect(await load(freeUsage())).toMatchObject({ plan: 'FREE', credits: { total: 50, unlocked: 13 },
            dailyBurst: null, weeklyUnlock: { portions: [13, 13, 12, 12], unlockedPortions: 1, nextPortionCredits: 13 },
            fairUse: { stt: { used: 52, limit: 60, warn: true } } });
        expect(await load(maxUsage())).toMatchObject({ plan: 'MAX', fairUse: { stt: { limit: null, limitToday: null, warn: false } } });
    });

    it('accepts a fully unlocked Free bar without a next unlock', async () => {
        const body = freeUsage();
        body['weeklyUnlock'] = { portions: [13, 13, 12, 12], unlockedPortions: 4, nextUnlockAt: null, nextPortionCredits: null };
        expect(await load(body)).toMatchObject({ weeklyUnlock: { unlockedPortions: 4, nextUnlockAt: null } });
    });

    it('accepts unlocked credits above the bar left by a mid-period downgrade', async () => {
        const body = freeUsage();
        body['credits'] = { total: 50, unlocked: 360, used: 8, reserved: 0, remaining: 352, percentUsed: 16 };
        expect(await load(body)).toMatchObject({ plan: 'FREE', credits: { total: 50, unlocked: 360, remaining: 352 } });
        body['credits']['remaining'] = 361;
        expect(await load(body).then(() => null, (error: unknown) => error)).toBeInstanceOf(UsageProtocolError);
    });

    it('rejects a response that could be cached or has an unexpected status', async () => {
        expect(await load(plusUsage(), { 'Cache-Control': 'public' }).then(() => null, (error: unknown) => error))
            .toBeInstanceOf(UsageProtocolError);
        const result = firstValueFrom(api.load());
        http.expectOne('/api/usage').flush(null, { status: 204, statusText: 'No Content', headers: privateHeaders });
        expect(await result.then(() => null, (error: unknown) => error)).toBeInstanceOf(UsageProtocolError);
    });

    it('rejects malformed envelopes', async () => {
        const cases: Record<string, (body: Record<string, any>) => void> = {
            'an unknown top-level field': body => { body['credits2'] = 1; },
            'a missing field': body => { delete body['caps']; },
            'an unknown plan': body => { body['plan'] = 'ULTRA'; },
            'a bad rate card': body => { body['rateCardVersion'] = '1'; },
            'a fractional credit': body => { body['credits']['used'] = 4.5; },
            'a negative credit': body => { body['credits']['remaining'] = -1; },
            'a percent above 100': body => { body['credits']['percentUsed'] = 101; },
            'remaining above unlocked': body => { body['credits']['remaining'] = 361; },
            'a credits extra field': body => { body['credits']['extra'] = 1; },
            'a string credit': body => { body['credits']['total'] = '360'; },
            'a period that ends before it starts': body => { body['period']['end'] = body['period']['start']; },
            'a bad period id': body => { body['period']['periodId'] = 'October'; },
            'a bad instant': body => { body['updatedAt'] = 'yesterday'; },
            'a non-object body part': body => { body['fairUse'] = []; },
            'an unknown bucket unit': body => { body['fairUse']['assessment']['unit'] = 'ANSWER'; },
            'a non-boolean warn': body => { body['fairUse']['stt']['warn'] = 'yes'; },
            'a lone velocity key': body => { body['fairUse']['stt']['velocityPerDay'] = 120; },
            'an unknown cap window': body => { body['caps']['podcasts']['window'] = 'YEAR'; },
            'a burst extra field': body => { body['dailyBurst']['x'] = 1; }
        };
        for (const [name, mutate] of Object.entries(cases)) {
            expect(await rejection(mutate), name).toBeInstanceOf(UsageProtocolError);
        }
        expect(await rejection(body => { body['weeklyUnlock']['unlockedPortions'] = 5; }, freeUsage())).toBeInstanceOf(UsageProtocolError);
        expect(await rejection(body => { body['weeklyUnlock']['portions'] = []; }, freeUsage())).toBeInstanceOf(UsageProtocolError);
        expect(await load(null).then(() => null, (error: unknown) => error)).toBeInstanceOf(UsageProtocolError);
        expect(clone(plusUsage())).toEqual(plusUsage());
    });
});
