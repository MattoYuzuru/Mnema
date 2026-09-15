import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, Router, RouterStateSnapshot, provideRouter } from '@angular/router';
import { AuthService } from '../../auth.service';
import { authGuard } from './auth.guard';

describe('canonical authentication guard', () => {
    it('awaits restoration before deciding whether a protected deep link is accessible', async () => {
        let resolve!: () => void;
        let authenticated = false;
        TestBed.configureTestingModule({ providers: [provideRouter([]), { provide: AuthService, useValue: {
            restore: () => new Promise<void>(done => { resolve = done; }),
            status: () => authenticated ? 'authenticated' : 'pending', accessToken: () => authenticated ? 'token' : null
        } }] });
        const result = TestBed.runInInjectionContext(() => authGuard({} as ActivatedRouteSnapshot, { url: '/decks/123' } as RouterStateSnapshot));
        authenticated = true; resolve();
        expect(await result).toBeTrue();
    });

    it('returns an internal login UrlTree with preserved deep link rather than navigating imperatively', async () => {
        TestBed.configureTestingModule({ providers: [provideRouter([]), { provide: AuthService, useValue: {
            restore: async () => {}, status: () => 'anonymous', accessToken: () => null
        } }] });
        const router = TestBed.inject(Router);
        const navigate = spyOn(router, 'navigate');
        const result = await TestBed.runInInjectionContext(() => authGuard({} as ActivatedRouteSnapshot, { url: '/decks/123' } as RouterStateSnapshot));
        expect(result).toEqual(router.createUrlTree(['/login'], { queryParams: { returnUrl: '/decks/123' } }));
        expect(navigate).not.toHaveBeenCalled();
    });
});
