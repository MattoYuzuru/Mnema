import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed, fakeAsync, flushMicrotasks } from '@angular/core/testing';

import { NativeMediaUploadApi, UploadView } from './native-media-upload.api';

describe('NativeMediaUploadApi', () => {
    const assetId = '0c2f05ec-8e16-464a-95d7-8fd97602d12d';
    const view: UploadView = {
        assetId, generation: 0, currentGeneration: 0, state: 'OPEN', assetState: 'PENDING_UPLOAD',
        method: 'SINGLE', declaredLength: 3, declaredMime: 'image/png', expiresAt: '2026-09-29T00:00:00Z',
        partSize: null, partCount: null, url: 'https://storage.example/signed', headers: { 'content-length': '3' },
        urlExpiresAt: '2026-09-28T12:00:00Z', parts: []
    };
    let api: NativeMediaUploadApi;
    let http: HttpTestingController;

    beforeEach(() => {
        TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
        api = TestBed.inject(NativeMediaUploadApi);
        http = TestBed.inject(HttpTestingController);
    });

    afterEach(() => http.verify());

    it('creates an owner-scoped Learning upload intent with an immutable client command identity', fakeAsync(() => {
        let received: UploadView | undefined;
        void api.intent('0c2f05ec-8e16-464a-95d7-8fd97602d12e', 'upload', 'image', 'image/png', 3)
            .then(value => { received = value; });
        const request = http.expectOne('/api/media-assets/upload-intents');
        expect(request.request.method).toBe('POST');
        expect(request.request.body).toEqual({
            intentId: '0c2f05ec-8e16-464a-95d7-8fd97602d12e', origin: 'upload', kind: 'image',
            mime: 'image/png', byteLength: 3
        });
        request.flush(view);
        flushMicrotasks();
        expect(received?.assetId).toBe(assetId);
    }));

    it('renews a single PUT URL by asset and current generation after editor recovery', fakeAsync(() => {
        let received: UploadView | undefined;
        void api.renewSingle(assetId, 0).then(value => { received = value; });
        const request = http.expectOne(`/api/media-assets/${assetId}/upload/url`);
        expect(request.request.method).toBe('POST');
        expect(request.request.body).toEqual({ generation: 0 });
        request.flush(view);
        flushMicrotasks();
        expect(received?.url).toBe(view.url);
    }));
});
