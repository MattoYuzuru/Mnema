import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { Box, Size, scrimPath } from '../core/onboarding/tour-geometry';

/**
 * The ink veil of the spotlight tour: the viewport darkened except for a cut-out around the target. One SVG path filled by the
 * even-odd rule, so the hole is not painted and a click on the target reaches it while a click on the veil does nothing (the veil
 * is never a close button). A frame in the lavender star colour stands off the cut-out. In forced colours the veil is not relied
 * on at all: the frame becomes a `Highlight` outline around the target. Decorative: hidden from assistive technology.
 */
@Component({
    selector: 'app-tour-scrim',
    host: { 'aria-hidden': 'true', '[class.contained]': 'contained()' },
    template: `
      <svg [attr.width]="viewport().width" [attr.height]="viewport().height" [attr.viewBox]="'0 0 ' + viewport().width + ' ' + viewport().height">
        <path class="veil" fill-rule="evenodd" [attr.d]="path()" />
      </svg>
      @if (hole(); as box) {
        <span class="frame" [style.left.px]="box.x" [style.top.px]="box.y" [style.width.px]="box.width" [style.height.px]="box.height"></span>
      }
    `,
    styles: [`
      :host { position: fixed; inset: 0; z-index: 1000; pointer-events: none; }
      :host(.contained) { position: absolute; z-index: 1; }
      svg { display: block; }
      .veil { fill: var(--mn-scrim); pointer-events: auto; }
      .frame { position: absolute; pointer-events: none; border-radius: var(--mn-radius); box-shadow: 0 0 0 2px var(--mn-star-lavender); }
      @media (forced-colors: active) {
        .veil { fill: transparent; }
        .frame { box-shadow: none; outline: 3px solid Highlight; outline-offset: 2px; }
      }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class TourScrimComponent {
    /** The size the veil covers: the window for the tour, the stage for a specimen. */
    readonly viewport = input.required<Size>();
    /** The cut-out; `null` while the target is not on the screen (the veil is whole). */
    readonly hole = input<Box | null>(null);
    /** Cover the positioned parent instead of the window (the styleguide specimen). */
    readonly contained = input(false);
    /** The corner radius of the cut-out in px: the resolved `--mn-radius` (2px in the current tokens). */
    readonly radius = input(2);
    protected readonly path = computed(() => scrimPath(this.viewport(), this.hole(), this.radius()));
}
