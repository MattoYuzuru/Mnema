import { Component, ChangeDetectionStrategy } from '@angular/core';
import { AppShellComponent } from './core/layout/app-shell.component';

@Component({
    selector: 'app-root',
    imports: [AppShellComponent],
    changeDetection: ChangeDetectionStrategy.Eager,
    template: `<app-shell></app-shell>`
})
export class AppComponent {}
