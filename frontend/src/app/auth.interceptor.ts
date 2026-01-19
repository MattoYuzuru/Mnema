import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { AuthService } from './auth.service';
import { appConfig } from './app.config';
import { catchError } from 'rxjs/operators';
import { throwError } from 'rxjs';

export const authInterceptor: HttpInterceptorFn = (req, next) => {
    const auth = inject(AuthService);
    const token = auth.accessToken();

    const isApiRequest =
        req.url.startsWith('/api/') ||
        req.url.startsWith(appConfig.apiBaseUrl) ||
        req.url.startsWith(appConfig.coreApiBaseUrl) ||
        req.url.startsWith(appConfig.mediaApiBaseUrl) ||
        req.url.startsWith(appConfig.importApiBaseUrl);

    const authEndpointBase = `${appConfig.authServerUrl}/auth/`;
    const isAuthEndpoint = req.url.startsWith(authEndpointBase);
    const isAuthProtected =
        req.url.startsWith(`${authEndpointBase}password`) ||
        req.url.startsWith(`${authEndpointBase}password/status`) ||
        req.url.startsWith(`${authEndpointBase}account`);

    if (!token || !(isApiRequest || (isAuthEndpoint && isAuthProtected))) {
        return next(req).pipe(
            catchError((error: HttpErrorResponse) => {
                if (isApiRequest && error.status === 401) {
                    auth.expireSession();
                }
                return throwError(() => error);
            })
        );
    }

    const authReq = req.clone({
        setHeaders: {
            Authorization: `Bearer ${token}`
        }
    });

    return next(authReq).pipe(
        catchError((error: HttpErrorResponse) => {
            if (error.status === 401) {
                auth.expireSession();
            }
            return throwError(() => error);
        })
    );
};
