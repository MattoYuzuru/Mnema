import { HttpClient } from '@angular/common/http';
import { of } from 'rxjs';

import { appConfig } from '../../app.config';
import { AiApiService, AiTemporarilyUnavailableError } from './ai-api.service';

describe('AiApiService', () => {
    let http: jasmine.SpyObj<HttpClient>;
    let service: AiApiService;
    let originalAiEnabled: boolean;

    beforeEach(() => {
        originalAiEnabled = appConfig.features.aiEnabled;
        http = jasmine.createSpyObj<HttpClient>('HttpClient', ['get', 'post', 'delete']);
        service = new AiApiService(http);
    });

    afterEach(() => {
        appConfig.features.aiEnabled = originalAiEnabled;
    });

    it('does not contact the AI API when the runtime feature is disabled', done => {
        appConfig.features.aiEnabled = false;

        service.getRuntimeCapabilities().subscribe({
            next: () => done.fail('expected the disabled request to fail'),
            error: error => {
                expect(error).toEqual(jasmine.any(AiTemporarilyUnavailableError));
                expect(error.code).toBe('AI_TEMPORARILY_UNAVAILABLE');
                expect(http.get).not.toHaveBeenCalled();
                done();
            }
        });
    });

    it('returns an empty provider list without an HTTP request when disabled', done => {
        appConfig.features.aiEnabled = false;

        service.listProviders().subscribe(providers => {
            expect(providers).toEqual([]);
            expect(http.get).not.toHaveBeenCalled();
            done();
        });
    });

    it('uses the configured AI API when the runtime feature is enabled', done => {
        appConfig.features.aiEnabled = true;
        const expected = { mode: 'user_keys', providers: [] };
        http.get.and.returnValue(of(expected));

        service.getRuntimeCapabilities().subscribe(capabilities => {
            expect(capabilities).toEqual(expected);
            expect(http.get).toHaveBeenCalledOnceWith(`${appConfig.aiApiBaseUrl}/runtime/capabilities`);
            done();
        });
    });
});
