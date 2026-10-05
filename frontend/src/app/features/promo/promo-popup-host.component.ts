import { DOCUMENT } from '@angular/common';
import { ChangeDetectionStrategy, Component, DestroyRef, effect, inject, untracked } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NavigationStart, Router } from '@angular/router';
import { filter } from 'rxjs';

import { PromoPopupComponent } from './promo-popup.component';
import { PromoPopupService } from './promo-popup.service';

/**
 * Renders the campaign the service wants shown, and takes it away when the learner navigates (a popup never follows a click to another page).
 * When it closes with nothing focused, focus goes to the page heading.
 */
@Component({
    selector: 'app-promo-popup-host',
    imports: [PromoPopupComponent],
    template: `
      @if (popup.campaign(); as campaign) {
        <app-promo-popup [campaign]="campaign" (accepted)="popup.accept()" (dismissed)="popup.dismiss()" (declined)="popup.decline()" />
      }
    `,
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class PromoPopupHostComponent {
    protected readonly popup = inject(PromoPopupService);

    constructor() {
        const document = inject(DOCUMENT);
        let wasOpen = false;
        // A popup that came up by itself has no element to hand focus back to. When it closes, focus goes to the page heading, as after a
        // route change, so a keyboard user continues where the page begins instead of from the top of the document.
        effect(() => {
            const open = this.popup.campaign() !== null;
            if (wasOpen && !open) {
                untracked(() => queueMicrotask(() => {
                    const active = document.activeElement;
                    if (active === null || active === document.body) document.querySelector<HTMLElement>('#main-content h1')?.focus();
                }));
            }
            wasOpen = open;
        });
        inject(Router).events.pipe(filter(event => event instanceof NavigationStart), takeUntilDestroyed(inject(DestroyRef)))
            .subscribe(() => this.popup.close());
    }
}
