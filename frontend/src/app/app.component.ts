import { Component } from '@angular/core';

@Component({
    selector: 'app-root',
    standalone: true,
    template: `
        <main style="font-family: system-ui, sans-serif; padding: 2rem;">
            <h1>Mnema Frontend</h1>
            <p>Angular минималка поднята. 🎉</p>
            <ul>
                <li>Auth: <code>/api/auth</code></li>
                <li>User: <code>/api/user</code></li>
            </ul>
        </main>
    `
})
export class AppComponent {}
