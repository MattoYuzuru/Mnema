import { DOCUMENT } from '@angular/common';
import { ApplicationRef, Injectable, inject } from '@angular/core';
import { NavigationExtras, Router } from '@angular/router';

interface TransitionDocument {
    startViewTransition?(update: () => Promise<void>): { readonly finished: Promise<unknown>; readonly ready?: Promise<unknown> };
}

/**
 * Navigation inside a same-document View Transition where the browser has the API, for the few moves that change what the
 * page is (the composer becoming the Workshop). It is feature-detected and skipped for reduced motion: without it, or with
 * it off, this is `Router.navigate`. The update callback waits for the router and for the render that follows it, so the
 * transition captures the new page and not the old one.
 */
@Injectable({ providedIn: 'root' })
export class PageTransition {
    private readonly router = inject(Router);
    private readonly app = inject(ApplicationRef);
    private readonly document = inject(DOCUMENT);

    async navigate(commands: readonly unknown[], extras?: NavigationExtras): Promise<boolean> {
        const host = this.document as unknown as TransitionDocument;
        const view = this.document.defaultView;
        const reduced = view !== null && typeof view.matchMedia === 'function'
            && view.matchMedia('(prefers-reduced-motion: reduce)').matches;
        if (typeof host.startViewTransition !== 'function' || reduced) return this.router.navigate([...commands], extras);
        let navigated = false;
        const transition = host.startViewTransition.call(this.document, async () => {
            navigated = await this.router.navigate([...commands], extras);
            await this.app.whenStable();
        });
        // `ready` rejects when the transition is skipped; that is not an error either.
        void transition.ready?.catch(() => undefined);
        // A skipped or interrupted transition is not a failed navigation.
        await transition.finished.catch(() => undefined);
        return navigated;
    }
}
