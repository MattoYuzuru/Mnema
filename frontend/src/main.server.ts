import { mergeApplicationConfig } from '@angular/core';
import { BootstrapContext, bootstrapApplication } from '@angular/platform-browser';
import { provideServerRendering, RenderMode, withRoutes } from '@angular/ssr';
import { AppComponent } from './app/app.component';
import { bootstrapConfig } from './app/app.bootstrap';
import publicPages from './app/core/seo/public-pages.json';

const serverConfig = mergeApplicationConfig(bootstrapConfig, {
    providers: [provideServerRendering(withRoutes([
        ...publicPages.map(page => ({ path: page.path.slice(1), renderMode: RenderMode.Prerender as const })),
        { path: '**', renderMode: RenderMode.Client }
    ]))]
});

export default (context: BootstrapContext) => bootstrapApplication(AppComponent, serverConfig, context);
