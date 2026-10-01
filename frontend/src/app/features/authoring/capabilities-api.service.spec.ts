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
        expect(CAPABILITIES_UNAVAILABLE.aiAssessment.available).toBeFalse();
        expect(CAPABILITIES_UNAVAILABLE.speechToText.available).toBeFalse();
    });

    it('accepts an available capability only without a reason and an unavailable one only with a known reason', async () => {
        const reading = firstValueFrom(api.read());
        http.expectOne('/api/capabilities').flush({ aiAssessment: { available: true, reason: null },
            speechToText: { available: false, reason: 'PROVIDER_NOT_CONFIGURED' } }, { headers });
        expect(await reading).toEqual({ aiAssessment: { available: true, reason: null },
            speechToText: { available: false, reason: 'PROVIDER_NOT_CONFIGURED' } });
        for (const bad of [{ available: true, reason: 'DISABLED' }, { available: false, reason: null }, { available: false, reason: 'OTHER' },
            { available: 'yes', reason: null }, { available: true, reason: null, secret: 'x' }]) {
            const rejected = firstValueFrom(api.read());
            http.expectOne('/api/capabilities').flush({ aiAssessment: bad, speechToText: bad }, { headers });
            await expectAsync(rejected).toBeRejectedWithError(AuthoringProtocolError);
        }
        const extra = firstValueFrom(api.read());
        http.expectOne('/api/capabilities').flush({ aiAssessment: CAPABILITIES_UNAVAILABLE.aiAssessment,
            speechToText: CAPABILITIES_UNAVAILABLE.speechToText, provider: 'x' }, { headers });
        await expectAsync(extra).toBeRejectedWithError(AuthoringProtocolError);
    });
});
