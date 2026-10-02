import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { authInterceptor, isCredentialTarget } from './auth.interceptor';
import { AuthService } from './auth.service';
import { BROWSER_IDENTITY_CONFIG } from './auth-browser';
import { spyObj, type SpyObj } from '../testing/mocks';

describe('canonical bearer interceptor', () => {
    let http: HttpClient;
    let mock: HttpTestingController;
    let auth: SpyObj<AuthService>;
    const identity = 'https://identity.example.test';

    beforeEach(() => {
        auth = spyObj<AuthService>({
            accessToken: vi.fn().mockName("AuthService.accessToken"),
            expireSession: vi.fn().mockName("AuthService.expireSession")
        });
        auth.accessToken.mockReturnValue('current-token');
        TestBed.configureTestingModule({ providers: [provideHttpClient(withInterceptors([authInterceptor])), provideHttpClientTesting(),
                { provide: AuthService, useValue: auth },
                { provide: BROWSER_IDENTITY_CONFIG, useValue: { authServerUrl: identity, learningApiBaseUrl: '/api' } }] });
        http = TestBed.inject(HttpClient);
        mock = TestBed.inject(HttpTestingController);
    });
    afterEach(() => mock.verify());

    it('only attaches to exact canonical Learning and Identity routes', () => {
        for (const url of ['/api/decks', '/api/decks/123', '/api/editing-drafts', '/api/editing-drafts/123',
            '/api/capture-notes', '/api/capture-notes/123/conversions',
            '/api/notifications', '/api/notifications?limit=20&after=40', '/api/notifications/read-cursor',
            '/api/notifications/0a000000-0000-4000-8000-000000000029',
            '/api/media-assets/upload-policy', '/api/media-assets/123/playback',
            '/api/capabilities', '/api/exercise-previews',
            `${identity}/api/accounts/me`, `${identity}/userinfo`]) {
            http.get(url).subscribe();
            const request = mock.expectOne(url);
            expect(request.request.headers.get('Authorization')).toBe('Bearer current-token');
            request.flush({});
        }
    });

    it('refuses legacy APIs, lookalike hosts/path prefixes and routing encodings', () => {
        for (const url of ['/api/core/decks', '/api/user/me', '/api/media/x', '/api/decks-other', '/api/editing-drafts-other',
            '/api/capture-notes-other', '/api/notifications-other', '/api/notifications%2fread-cursor', '/api/media-assets-other', '/api/other/../decks',
            '/api/capabilities/x', '/api/capabilities-other', '/api/exercise-previews/x', '/api/exercise-previews-other',
            'https://storage.example.test/bucket/source?X-Amz-Signature=abc',
            'https://identity.example.test.evil.test/api/accounts/me', `${identity}/api/accounts/me/other`,
            `${identity}/api/accounts/login`, `${identity}/oauth2/token`, '/api/%64ecks', '/api/decks%2f123',
            'https://evil.test/api/decks', '//evil.test/api/decks', 'https://user@identity.example.test/userinfo']) {
            expect(isCredentialTarget(url, identity, '/api', window.location.origin), url).toBe(false);
            http.get(url).subscribe();
            const request = mock.expectOne(url);
            expect(request.request.headers.has('Authorization'), url).toBe(false);
            request.flush({});
        }
    });

    it('binds401 expiration to the exact request token', () => {
        http.get('/api/decks').subscribe({ error: () => { } });
        mock.expectOne('/api/decks').flush({}, { status: 401, statusText: 'Unauthorized' });
        expect(auth.expireSession).toHaveBeenCalledTimes(1);
        expect(auth.expireSession).toHaveBeenCalledWith('current-token');
    });

    it('does not expire on403/503, unrelated401 or anonymous requests', () => {
        for (const status of [403, 503]) {
            http.get('/api/decks').subscribe({ error: () => { } });
            mock.expectOne('/api/decks').flush({}, { status, statusText: 'Failure' });
        }
        http.get('https://elsewhere.test').subscribe({ error: () => { } });
        mock.expectOne('https://elsewhere.test').flush({}, { status: 401, statusText: 'Unauthorized' });
        auth.accessToken.mockReturnValue(null);
        http.get('/api/decks').subscribe({ error: () => { } });
        const request = mock.expectOne('/api/decks');
        expect(request.request.headers.has('Authorization')).toBe(false);
        request.flush({}, { status: 401, statusText: 'Unauthorized' });
        expect(auth.expireSession).not.toHaveBeenCalled();
    });
});
