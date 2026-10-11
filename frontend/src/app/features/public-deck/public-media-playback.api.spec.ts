import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { PublicMediaPlaybackApi } from './public-media-playback.api';

describe('PublicMediaPlaybackApi', () => {
    const code = 'Kq7xT3mNpR';
    const assetId = '31901995-16ea-4f8b-8301-5d8e03004c72';
    const view = {
        assetId, state: 'READY',
        playback: { url: 'https://storage.example/ready.webp', expiresAt: '2099-01-01T00:00:00Z', mimeType: 'image/webp' },
        poster: null
    };
    let http: HttpTestingController;
    let api: PublicMediaPlaybackApi;

    beforeEach(() => {
        TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), PublicMediaPlaybackApi] });
        http = TestBed.inject(HttpTestingController);
        api = TestBed.inject(PublicMediaPlaybackApi);
        api.code = code;
    });
    afterEach(() => http.verify());

    it('reads the public media route of the deck and maps it to the renderer shape without a download', async () => {
        const reading = api.read(assetId);
        const request = http.expectOne(`/api/public/decks/${code}/media/${assetId}`);
        expect(request.request.method).toBe('GET');
        request.flush(view);
        expect(await reading).toEqual({ ...view, download: null });
    });

    it('rejects a shape it does not know, another asset and a deck without a code, so the renderer shows «unavailable»', async () => {
        for (const body of [{ ...view, extra: 1 }, { ...view, assetId: '00000000-0000-4000-8000-000000000001' }, { ...view, state: 'LOST' },
            { ...view, playback: { url: 'javascript:alert(1)', expiresAt: '2099-01-01T00:00:00Z', mimeType: 'image/webp' } }, 'text']) {
            const reading = api.read(assetId);
            http.expectOne(`/api/public/decks/${code}/media/${assetId}`).flush(body);
            await expect(reading).rejects.toBeDefined();
        }
        const missing = api.read(assetId);
        http.expectOne(`/api/public/decks/${code}/media/${assetId}`).flush({}, { status: 404, statusText: 'Not Found' });
        await expect(missing).rejects.toBeDefined();
        api.code = '';
        await expect(api.read(assetId)).rejects.toBeDefined();
    });
});
