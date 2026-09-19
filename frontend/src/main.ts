import { inject, provideAppInitializer, provideZoneChangeDetection } from '@angular/core';
import { bootstrapApplication } from '@angular/platform-browser';
import { provideRouter } from '@angular/router';
import { provideHttpClient, withInterceptors, withXhr } from '@angular/common/http';

import { AppComponent } from './app/app.component';
import { appRoutes } from './app/app.routes';
import { authInterceptor } from './app/auth.interceptor';
import { AuthService } from './app/auth.service';

bootstrapApplication(AppComponent, {
    providers: [
        provideZoneChangeDetection(),
        // Public content/shell do not wait for a private API; protected guards await restore().
        provideAppInitializer(() => { void inject(AuthService).restore(); }),
        provideRouter(appRoutes),
        provideHttpClient(withXhr(), withInterceptors([authInterceptor]))
    ]
}).catch(err => console.error(err));
