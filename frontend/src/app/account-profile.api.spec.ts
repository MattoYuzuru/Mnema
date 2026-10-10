import { TestBed } from '@angular/core/testing';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AccountProfileApi } from './account-profile.api';
import { AUTH_BROWSER, BROWSER_IDENTITY_CONFIG, BrowserIdentityConfig } from './auth-browser';
import { AuthService } from './auth.service';
import { authInterceptor } from './auth.interceptor';
import fixture from '../../../contracts/identity/public-profile.json';
import { PUBLIC_PROFILE_TEXT_VERSION } from './public-profile';

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

    it('reads an existing multiline bio and accepts the saved response', () => {
        const bio = 'Учусь каждый день ✨\n\n• Математика и языки';
        api.load().subscribe(profile => expect(profile.bio).toBe(bio));
        http.expectOne('https://identity.mnema.test/api/accounts/me').flush({ ...response, bio });
        api.update({ profileUsername: 'reader', displayName: 'Reader', bio })
            .subscribe(profile => expect(profile.bio).toBe(bio));
        http.expectOne('https://identity.mnema.test/api/accounts/me').flush({ ...response, bio });
    });

    it.each([{ bio: 'x'.repeat(201) }, { bio: 'bad\u0001' }, { displayName: 'bad\nname' }])(
    'rejects invalid response fields without weakening single-line identity checks: %j', fields => {
        const failed = vi.fn();
        api.load().subscribe({ error: failed });
        http.expectOne('https://identity.mnema.test/api/accounts/me').flush({ ...response, ...fields });
        expect(failed).toHaveBeenCalledOnce();
    });

    it('reads the owner photo as a blob from the bearer-protected owner endpoint', () => {
        let received: Blob | null = null;
        api.loadAvatar().subscribe(blob => { received = blob; });
        const read = http.expectOne('https://identity.mnema.test/api/accounts/me/avatar');
        expect(read.request.method).toBe('GET');
        expect(read.request.responseType).toBe('blob');
        expect(read.request.withCredentials).toBe(false);
        expect(read.request.headers.get('Authorization')).toBe('Bearer owner-token');
        read.flush(new Blob(['image'], { type: 'image/png' }));
        expect(received).toBeInstanceOf(Blob);
    });

    it('reads and saves the public-profile consent with bearer, no cookies and the complete state', () => {
        api.loadPublicProfile().subscribe(consent => expect(consent).toEqual(fixture.consentDefault));
        const read = http.expectOne('https://identity.mnema.test/api/accounts/me/public-profile');
        expect(read.request.method).toBe('GET');
        expect(read.request.withCredentials).toBe(false);
        expect(read.request.headers.get('Authorization')).toBe('Bearer owner-token');
        read.flush(fixture.consentDefault);

        api.savePublicProfile(fixture.consentUpdate).subscribe(consent => expect(consent).toEqual(fixture.consentGranted));
        const write = http.expectOne('https://identity.mnema.test/api/accounts/me/public-profile');
        expect(write.request.method).toBe('PUT');
        expect(write.request.withCredentials).toBe(false);
        expect(write.request.headers.get('Authorization')).toBe('Bearer owner-token');
        expect(write.request.body).toEqual(fixture.consentUpdate);
        expect(fixture.consentUpdate.textVersion).toBe(PUBLIC_PROFILE_TEXT_VERSION);
        write.flush(fixture.consentGranted);
    });

    it('rejects a consent answer that is not the contract shape', () => {
        const failed = vi.fn();
        api.loadPublicProfile().subscribe({ error: failed });
        http.expectOne('https://identity.mnema.test/api/accounts/me/public-profile').flush({ ...fixture.consentDefault, extra: 1 });
        expect(failed).toHaveBeenCalledOnce();
    });
});
