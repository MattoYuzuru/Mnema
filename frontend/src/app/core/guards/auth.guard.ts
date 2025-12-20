import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from '../../auth.service';

export const authGuard: CanActivateFn = (route, state) => {
    const auth = inject(AuthService);
    const router = inject(Router);

    if (auth.status() === 'authenticated') {
        return true;
    }

    void router.navigate(['/login'], { queryParams: { returnUrl: state.url } });
    return false;
};
