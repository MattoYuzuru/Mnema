import { provideZoneChangeDetection } from '@angular/core';
import { bootstrapApplication } from '@angular/platform-browser';

import { RendererVisualHarnessComponent } from './renderer-visual-harness.component';

bootstrapApplication(RendererVisualHarnessComponent, {
    providers: [
        provideZoneChangeDetection()
    ]
}).catch(err => console.error(err));
