import { Component } from '@angular/core';
import { AppShellComponent } from './core/layout/app-shell.component';

@Component({
    selector: 'app-root',
    standalone: true,
    imports: [AppShellComponent],
    template: `<app-shell></app-shell>`
})
export class AppComponent {}
