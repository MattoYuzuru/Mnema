import { afterNextRender, ApplicationConfig, inject, provideAppInitializer } from '@angular/core';
import { provideHttpClient, withInterceptors, withXhr } from '@angular/common/http';
import { provideClientHydration, withNoHttpTransferCache, withNoIncrementalHydration } from '@angular/platform-browser';
import { provideRouter, TitleStrategy, withInMemoryScrolling } from '@angular/router';
import { appRoutes } from './app.routes';
import { authInterceptor } from './auth.interceptor';
import { AuthService } from './auth.service';
import { SeoTitleStrategy } from './core/seo/seo-title.strategy';

export const bootstrapConfig: ApplicationConfig = {
    providers: [
        // Hydrate the anonymous public HTML before restoring this browser's private session.
        // Guards still await restore() immediately on private client-rendered routes.
        provideAppInitializer(() => {
            const auth = inject(AuthService);
            afterNextRender(() => { void auth.restore(); });
        }),
        provideRouter(appRoutes, withInMemoryScrolling({ anchorScrolling: 'enabled', scrollPositionRestoration: 'enabled' })),
        { provide: TitleStrategy, useClass: SeoTitleStrategy },
        // CSR entry documents have no server DOM to hydrate. No provider initialization on private direct loads.
        ...(typeof window === 'undefined' || document.querySelector('app-root[ngh]')
            ? [provideClientHydration(withNoIncrementalHydration(), withNoHttpTransferCache())] : []),
        // Keep the existing browser XHR transport and credential/CSRF semantics.
        provideHttpClient(withXhr(), withInterceptors([authInterceptor]))
    ]
};
