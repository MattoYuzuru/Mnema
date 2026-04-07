import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { authInterceptor } from './auth.interceptor';
import { AuthService } from './auth.service';

describe('authInterceptor', () => {
    let http: HttpClient;
    let httpMock: HttpTestingController;
    let authService: jasmine.SpyObj<AuthService>;

    beforeEach(() => {
        authService = jasmine.createSpyObj<AuthService>('AuthService', ['accessToken', 'expireSession']);

        TestBed.configureTestingModule({
            providers: [
                { provide: AuthService, useValue: authService },
                provideHttpClient(withInterceptors([authInterceptor])),
                provideHttpClientTesting()
            ]
        });

        http = TestBed.inject(HttpClient);
        httpMock = TestBed.inject(HttpTestingController);
    });

    afterEach(() => {
        httpMock.verify();
    });

    it('attaches bearer token to api requests', () => {
        authService.accessToken.and.returnValue('token-123');

        http.get('/api/core/decks').subscribe();

        const request = httpMock.expectOne('/api/core/decks');
        expect(request.request.headers.get('Authorization')).toBe('Bearer token-123');
        request.flush({});
    });

    it('does not attach token to unrelated requests', () => {
        authService.accessToken.and.returnValue('token-123');

        http.get('https://example.com/public').subscribe();

        const request = httpMock.expectOne('https://example.com/public');
        expect(request.request.headers.has('Authorization')).toBeFalse();
        request.flush({});
    });

    it('expires session on api 401 responses', () => {
        authService.accessToken.and.returnValue('token-123');

        http.get('/api/user/me').subscribe({
            error: () => {}
        });

        const request = httpMock.expectOne('/api/user/me');
        request.flush({ message: 'unauthorized' }, { status: 401, statusText: 'Unauthorized' });

        expect(authService.expireSession).toHaveBeenCalled();
    });
});
