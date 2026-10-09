import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

import { filter, map } from 'rxjs';
import { DeckConstellationComponent } from '../../shared/deck-constellation.component';
import { PublicFooterComponent } from '../../shared/public-footer.component';

import { AuthService } from '../../auth.service';
import { NotificationBellComponent } from '../notifications/notification-bell.component';
import { ToastRegionComponent } from '../notifications/toast-region.component';
import { GoalOnboardingComponent } from '../../features/goal/goal-onboarding.component';
import { PromoPopupHostComponent } from '../../features/promo/promo-popup-host.component';

@Component({
    selector: 'app-shell',
    host: { '(pointerover)': 'setWaveOrigin($event)' },
    imports: [RouterLink, RouterLinkActive, RouterOutlet, DeckConstellationComponent, NotificationBellComponent, ToastRegionComponent,
        GoalOnboardingComponent, PromoPopupHostComponent, PublicFooterComponent],
    templateUrl: './app-shell.component.html',
    styleUrl: './app-shell.component.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class AppShellComponent {
    readonly auth = inject(AuthService);
    readonly status = toSignal(this.auth.status$, { initialValue: this.auth.status() });
    readonly user = toSignal(this.auth.user$, { initialValue: this.auth.user() });
    private readonly router = inject(Router);

    readonly routeUrl = toSignal(this.router.events.pipe(
        filter(event => event instanceof NavigationEnd), map(event => event.urlAfterRedirects)
    ), { initialValue: this.router.url });
    readonly constellationSeed = computed(() => {
        const path = this.routeUrl().split('?')[0];
        const deck = /^\/decks\/([0-9a-f-]{36})(?:\/|$)/i.exec(path);
        if (deck) return deck[1].toLowerCase();
        return path === '/profile' || path === '/decks/new' ? this.user()?.accountId ?? null : null;
    });

    async logout(): Promise<void> {
        try {
            await this.auth.logout();
        } catch {
            await this.router.navigate(['/login']);
        }
    }

    focusTarget(event: Event, id: string): void {
        event.preventDefault();
        document.getElementById(id)?.focus();
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
            '.button, .feature-strip a, .item-row, .deck-row'
        );
        if (!target || (event.relatedTarget instanceof Node && target.contains(event.relatedTarget))) return;
        const bounds = target.getBoundingClientRect();
        target.style.setProperty('--mn-wave-x', `${event.clientX - bounds.left}px`);
        target.style.setProperty('--mn-wave-y', `${event.clientY - bounds.top}px`);
    }
}
