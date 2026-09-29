import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

import { AuthService } from '../../auth.service';

@Component({
    selector: 'app-shell',
    host: { '(pointerover)': 'setWaveOrigin($event)' },
    imports: [RouterLink, RouterLinkActive, RouterOutlet],
    templateUrl: './app-shell.component.html',
    styleUrl: './app-shell.component.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class AppShellComponent {
    readonly auth = inject(AuthService);
    readonly status = toSignal(this.auth.status$, { initialValue: this.auth.status() });
    readonly user = toSignal(this.auth.user$, { initialValue: this.auth.user() });
    private readonly router = inject(Router);

    async logout(): Promise<void> {
        try {
            await this.auth.logout();
        } catch {
            await this.router.navigate(['/login']);
        }
    }

    focusMain(event: Event): void {
        event.preventDefault();
        document.querySelector<HTMLElement>('#main-content')?.focus();
    }

    focusPageHeading(): void {
        queueMicrotask(() => {
            const heading = document.querySelector<HTMLElement>('#main-content h1');
            heading?.focus();
        });
    }

    setWaveOrigin(event: PointerEvent): void {
        if (!(event.target instanceof Element)) return;
        const target = event.target.closest<HTMLElement>(
            '.button, .quiet-action, .primary-action, .profile-page button, .file-label, .feature-strip a, .item-row, .deck-row'
        );
        if (!target || (event.relatedTarget instanceof Node && target.contains(event.relatedTarget))) return;
        const bounds = target.getBoundingClientRect();
        target.style.setProperty('--mn-wave-x', `${event.clientX - bounds.left}px`);
        target.style.setProperty('--mn-wave-y', `${event.clientY - bounds.top}px`);
    }
}
