import { bootstrapApplication } from '@angular/platform-browser';
import { AppComponent } from './app/app.component';
import { bootstrapConfig } from './app/app.bootstrap';

bootstrapApplication(AppComponent, bootstrapConfig).catch(err => console.error(err));
