import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';

import { AuthoringProtocolError } from './authoring.models';
import { CAPABILITIES_UNAVAILABLE, CapabilitiesApiService } from './capabilities-api.service';

describe('CapabilitiesApiService', () => {
    const headers = { 'Cache-Control': 'private, no-store' };
    let api: CapabilitiesApiService;
    let http: HttpTestingController;

    beforeEach(() => {
        TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
        api = TestBed.inject(CapabilitiesApiService);
        http = TestBed.inject(HttpTestingController);
    });
    afterEach(() => http.verify());

    it('is closed by default', () => {
        expect(CAPABILITIES_UNAVAILABLE.aiAssessment.available).toBe(false);
        expect(CAPABILITIES_UNAVAILABLE.speechToText.available).toBe(false);
        expect(CAPABILITIES_UNAVAILABLE.aiGeneration.available).toBe(false);
    });

    it('accepts an available capability only without a reason and an unavailable one only with a known reason', async () => {
        const reading = firstValueFrom(api.read());
        http.expectOne('/api/capabilities').flush({ aiAssessment: { available: true, reason: null },
            speechToText: { available: false, reason: 'PROVIDER_NOT_CONFIGURED' } }, { headers });
        // aiGeneration is optional on the wire; absent means unavailable (fail closed).
        expect(await reading).toEqual({ aiAssessment: { available: true, reason: null },
            speechToText: { available: false, reason: 'PROVIDER_NOT_CONFIGURED' }, aiGeneration: CAPABILITIES_UNAVAILABLE.aiGeneration });
        for (const bad of [{ available: true, reason: 'DISABLED' }, { available: false, reason: null }, { available: false, reason: 'OTHER' },
            { available: 'yes', reason: null }, { available: true, reason: null, secret: 'x' }]) {
            const rejected = firstValueFrom(api.read());
            http.expectOne('/api/capabilities').flush({ aiAssessment: bad, speechToText: bad }, { headers });
            await expect(rejected).rejects.toThrowError(AuthoringProtocolError);
        }
        const extra = firstValueFrom(api.read());
        http.expectOne('/api/capabilities').flush({ aiAssessment: CAPABILITIES_UNAVAILABLE.aiAssessment,
            speechToText: CAPABILITIES_UNAVAILABLE.speechToText, provider: 'x' }, { headers });
        await expect(extra).rejects.toThrowError(AuthoringProtocolError);
    });

    it('reads aiGeneration when the server sends it and rejects a malformed one', async () => {
        const base = { aiAssessment: CAPABILITIES_UNAVAILABLE.aiAssessment, speechToText: CAPABILITIES_UNAVAILABLE.speechToText };
        const available = firstValueFrom(api.read());
        http.expectOne('/api/capabilities').flush({ ...base, aiGeneration: { available: true, reason: null } }, { headers });
        expect((await available).aiGeneration).toEqual({ available: true, reason: null });
        const paused = firstValueFrom(api.read());
        http.expectOne('/api/capabilities').flush({ ...base, aiGeneration: { available: false, reason: 'TEMPORARILY_UNAVAILABLE' } }, { headers });
        expect((await paused).aiGeneration).toEqual({ available: false, reason: 'TEMPORARILY_UNAVAILABLE' });
        const bad = firstValueFrom(api.read());
        http.expectOne('/api/capabilities').flush({ ...base, aiGeneration: { available: true, reason: 'DISABLED' } }, { headers });
        await expect(bad).rejects.toThrowError(AuthoringProtocolError);
    });
});
