import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from '../../auth.service';
import { safeReturnUrl } from '../../auth-protocol';

export const authGuard: CanActivateFn = async (route, state) => {
    const auth = inject(AuthService);
    const router = inject(Router);

    await auth.restore();
    if (auth.status() === 'authenticated' && auth.accessToken()) {
        return true;
    }

    return router.createUrlTree(['/login'], { queryParams: { returnUrl: safeReturnUrl(state.url) } });
};
