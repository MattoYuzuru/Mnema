import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';

import { AuthoringProtocolError } from './authoring.models';
import { CAPABILITIES_UNAVAILABLE, Capability, CapabilitiesApiService, LearningCapabilities } from './capabilities-api.service';

const KEYS = ['aiAssessment', 'speechToText', 'aiGeneration', 'textToSpeech', 'imageSearch', 'imageGeneration',
    'videoGeneration', 'webSearch'] as const;

/** The example of `getCapabilities` in contracts/generation/http.json. */
const CONTRACT_EXAMPLE: LearningCapabilities = {
    aiAssessment: { available: false, reason: 'DISABLED' },
    speechToText: { available: false, reason: 'DISABLED' },
    aiGeneration: { available: true, reason: null },
    textToSpeech: { available: false, reason: 'PROVIDER_NOT_CONFIGURED' },
    imageSearch: { available: true, reason: null },
    imageGeneration: { available: false, reason: 'DISABLED' },
    videoGeneration: { available: false, reason: 'DISABLED' },
    webSearch: { available: false, reason: 'TEMPORARILY_UNAVAILABLE' }
};

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

    async function read(body: object | string | null, flushHeaders: Record<string, string> = headers): Promise<LearningCapabilities> {
        const reading = firstValueFrom(api.read());
        http.expectOne('/api/capabilities').flush(body, { headers: flushHeaders });
        return reading;
    }

    function withAll(capability: unknown): Record<string, unknown> {
        return Object.fromEntries(KEYS.map(key => [key, capability]));
    }

    it('is closed by default for all eight capabilities', () => {
        expect(Object.keys(CAPABILITIES_UNAVAILABLE).sort()).toEqual([...KEYS].sort());
        for (const key of KEYS) expect(CAPABILITIES_UNAVAILABLE[key]).toEqual({ available: false, reason: 'DISABLED' });
    });

    it('parses the contract example exactly, including the temporary reason', async () => {
        expect(await read(CONTRACT_EXAMPLE)).toEqual(CONTRACT_EXAMPLE);
    });

    it('accepts an available capability only without a reason and an unavailable one only with a known reason', async () => {
        const available: Capability = { available: true, reason: null };
        expect(await read(withAll(available))).toEqual(withAll(available));
        for (const reason of ['DISABLED', 'PROVIDER_NOT_CONFIGURED', 'TEMPORARILY_UNAVAILABLE']) {
            const unavailable = { available: false, reason };
            expect(await read(withAll(unavailable))).toEqual(withAll(unavailable));
        }
        for (const bad of [{ available: true, reason: 'DISABLED' }, { available: false, reason: null }, { available: false, reason: 'OTHER' },
            { available: false, reason: 'temporarily_unavailable' }, { available: 'yes', reason: null }, { available: true, reason: null, secret: 'x' },
            { available: true }, null, 'x']) {
            await expect(read(withAll(bad))).rejects.toThrowError(AuthoringProtocolError);
        }
    });

    it('rejects a bad capability in any single position, not only the first', async () => {
        for (const key of KEYS) {
            await expect(read({ ...CONTRACT_EXAMPLE, [key]: { available: false, reason: 'OTHER' } })).rejects.toThrowError(AuthoringProtocolError);
        }
    });

    it('requires the exact key set: a missing, renamed or extra key is a protocol error', async () => {
        for (const key of KEYS) {
            const missing: Record<string, unknown> = { ...CONTRACT_EXAMPLE };
            delete missing[key];
            await expect(read(missing)).rejects.toThrowError(AuthoringProtocolError);
        }
        await expect(read({ aiAssessment: CAPABILITIES_UNAVAILABLE.aiAssessment, speechToText: CAPABILITIES_UNAVAILABLE.speechToText }))
            .rejects.toThrowError(AuthoringProtocolError);
        await expect(read({ ...CONTRACT_EXAMPLE, provider: 'x' })).rejects.toThrowError(AuthoringProtocolError);
        await expect(read({ ...CONTRACT_EXAMPLE, aiGeneration: undefined })).rejects.toThrowError(AuthoringProtocolError);
        await expect(read([])).rejects.toThrowError(AuthoringProtocolError);
        await expect(read(null)).rejects.toThrowError(AuthoringProtocolError);
    });

    it('rejects a cacheable response', async () => {
        await expect(read(CONTRACT_EXAMPLE, {})).rejects.toThrowError(AuthoringProtocolError);
        await expect(read(CONTRACT_EXAMPLE, { 'Cache-Control': 'private' })).rejects.toThrowError(AuthoringProtocolError);
        await expect(read(CONTRACT_EXAMPLE, { 'Cache-Control': 'public, no-store' })).rejects.toThrowError(AuthoringProtocolError);
    });
});
