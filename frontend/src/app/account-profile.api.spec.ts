import { TestBed } from '@angular/core/testing';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AccountProfileApi } from './account-profile.api';
import { AUTH_BROWSER, BROWSER_IDENTITY_CONFIG, BrowserIdentityConfig } from './auth-browser';
import { AuthService } from './auth.service';
import { authInterceptor } from './auth.interceptor';

const identity: BrowserIdentityConfig = {
    authServerUrl: 'https://identity.mnema.test', identityRedirectUri: 'https://mnema.test/auth/callback',
    learningApiBaseUrl: '/api', clientId: 'mnema-web'
};
const response = { accountId: 'd2815e20-ea25-4dce-977a-66ee086f294d', email: 'reader@example.test',
    emailVerified: true, profileUsername: 'reader', displayName: 'Reader', bio: '', avatarPresent: false,
    hasPassword: true, status: 'ACTIVE' };

describe('AccountProfileApi', () => {
    let api: AccountProfileApi;
    let http: HttpTestingController;

    beforeEach(() => {
        TestBed.configureTestingModule({ providers: [
                provideHttpClient(withInterceptors([authInterceptor])), provideHttpClientTesting(),
                { provide: BROWSER_IDENTITY_CONFIG, useValue: identity },
                { provide: AUTH_BROWSER, useValue: { origin: 'https://mnema.test' } },
                { provide: AuthService, useValue: { accessToken: () => 'owner-token', expireSession: () => undefined } }
            ] });
        api = TestBed.inject(AccountProfileApi);
        http = TestBed.inject(HttpTestingController);
    });

    afterEach(() => http.verify());

    it('sends account reads and edits only to the validated Identity origin with bearer and no cookies', () => {
        api.load().subscribe(profile => expect(profile.accountId).toBe(response.accountId));
        const read = http.expectOne('https://identity.mnema.test/api/accounts/me');
        expect(read.request.withCredentials).toBe(false);
        expect(read.request.headers.get('Authorization')).toBe('Bearer owner-token');
        read.flush(response);

        api.update({ profileUsername: 'reader', displayName: 'Reader', bio: 'new' })
            .subscribe(profile => expect(profile.bio).toBe('new'));
        const edit = http.expectOne('https://identity.mnema.test/api/accounts/me');
        expect(edit.request.method).toBe('PUT');
        expect(edit.request.withCredentials).toBe(false);
        expect(edit.request.headers.get('Authorization')).toBe('Bearer owner-token');
        edit.flush({ ...response, bio: 'new' });
    });

    it('uploads avatar as multipart to native account endpoint', () => {
        api.uploadAvatar(new File(['image'], 'me.png', { type: 'image/png' })).subscribe();
        const upload = http.expectOne('https://identity.mnema.test/api/accounts/me/avatar');
        expect(upload.request.method).toBe('PUT');
        expect(upload.request.withCredentials).toBe(false);
        expect(upload.request.headers.get('Authorization')).toBe('Bearer owner-token');
        expect(upload.request.body instanceof FormData).toBe(true);
        upload.flush(null);
    });
});
