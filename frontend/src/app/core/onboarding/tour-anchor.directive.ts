import { Directive, ElementRef, effect, inject, input } from '@angular/core';

import { TourAnchors } from './tour-anchors';

/**
 * Marks the host as a place the tour can point at: `<button mnTourAnchor="community.add">`. The key is the only contract between
 * a page and a tour definition; the element is unregistered when it is destroyed or the key changes.
 */
@Directive({
    // The product name of the directive is fixed by the onboarding architecture (§8); `app` is the lint prefix of components.
    // eslint-disable-next-line @angular-eslint/directive-selector
    selector: '[mnTourAnchor]',
    host: { '[attr.data-tour-anchor]': 'key()' }
})
export class TourAnchorDirective {
    readonly key = input.required<string>({ alias: 'mnTourAnchor' });

    constructor() {
        const anchors = inject(TourAnchors);
        const element = inject<ElementRef<HTMLElement>>(ElementRef).nativeElement;
        effect(onCleanup => onCleanup(anchors.register(this.key(), element)));
    }
}
