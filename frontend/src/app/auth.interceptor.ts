import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { AuthService } from './auth.service';
import { appConfig } from './app.config';

export const authInterceptor: HttpInterceptorFn = (req, next) => {
    const auth = inject(AuthService);
    const token = auth.accessToken();

    const isApiRequest =
        req.url.startsWith('/api/') ||
        req.url.startsWith(appConfig.apiBaseUrl) ||
        req.url.startsWith(appConfig.coreApiBaseUrl) ||
        req.url.startsWith(appConfig.mediaApiBaseUrl);

    if (!token || !isApiRequest) {
        return next(req);
    }

    const authReq = req.clone({
        setHeaders: {
            Authorization: `Bearer ${token}`
        }
    });

    return next(authReq);
};
