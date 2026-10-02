import { inject, provideAppInitializer } from '@angular/core';
import { bootstrapApplication } from '@angular/platform-browser';
import { provideRouter, withInMemoryScrolling } from '@angular/router';
import { provideHttpClient, withInterceptors, withXhr } from '@angular/common/http';

import { AppComponent } from './app/app.component';
import { appRoutes } from './app/app.routes';
import { authInterceptor } from './app/auth.interceptor';
import { AuthService } from './app/auth.service';

bootstrapApplication(AppComponent, {
    providers: [
        // Public content/shell do not wait for a private API; protected guards await restore().
        provideAppInitializer(() => { void inject(AuthService).restore(); }),
        provideRouter(appRoutes, withInMemoryScrolling({ anchorScrolling: 'enabled', scrollPositionRestoration: 'enabled' })),
        // Angular 22 defaults HttpClient to fetch. XHR stays the transport so cookie/CSRF Identity calls and error semantics
        // are unchanged by the platform upgrade; moving to fetch is a separate change that needs real-browser verification.
        // (Signed-URL media uploads use their own XMLHttpRequest for upload progress, which HttpClient's fetch cannot report.)
        provideHttpClient(withXhr(), withInterceptors([authInterceptor]))
    ]
}).catch(err => console.error(err));
