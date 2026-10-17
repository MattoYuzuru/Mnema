import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { skyFor } from './night-sky';

/**
 * The night surface of the onboarding: the ink-dark ground of the welcome and the congratulations, with the content projected on
 * it. Stars (the `✧` motif of the deck constellation, lavender and paper) belong to those two screens only; the survey and the
 * branch choice use the same ground with `[stars]="false"`. Twinkling is finite (some stars three times for 3.6 s, then rest) and
 * absent under reduced motion; in forced colours the ground is `Canvas` and the sky is not drawn. The sky is decoration:
 * `aria-hidden`, never focusable. The surface itself carries no role; the screen that uses it names its own dialog.
 */
@Component({
    selector: 'app-night-sky',
    host: { '[class.screen]': 'layout() === "screen"' },
    template: `
      @if (stars()) {
        <div class="sky" aria-hidden="true">
          @for (star of sky(); track $index) {
            <span class="star" [class.lavender]="star.tone === 'lavender'" [class.paper]="star.tone === 'paper'" [class.dot]="star.dot"
              [class.twinkle]="star.twinkle" [class.side]="star.side" [style.left.%]="star.x" [style.top.%]="star.y"
              [style.--size.rem]="star.size" [style.--turn.deg]="star.turn" [style.--alpha]="star.alpha" [style.--delay.s]="star.delay">{{ star.dot ? '' : '✧' }}</span>
          }
        </div>
      }
      <div class="content"><ng-content /></div>
    `,
    styles: [`
      :host { position: relative; display: grid; place-items: center; overflow: hidden; padding: 2rem var(--mn-page-gutter); color: var(--mn-on-night); background: var(--mn-night); }
      :host(.screen) { position: fixed; inset: 0; z-index: 40; overflow: auto; }
      .sky { position: absolute; inset: 0; pointer-events: none; overflow: hidden; }
      .star { position: absolute; font: 400 var(--size, 1.5rem)/1 Georgia, serif; transform: translate(-50%, -50%) rotate(var(--turn, 0deg)); opacity: var(--alpha, .85); }
      .star.lavender { color: var(--mn-star-lavender); }
      .star.paper { color: var(--mn-star-paper); }
      .star.dot { inline-size: .2rem; block-size: .2rem; border-radius: 50%; background: currentColor; font-size: 0; }
      .content { position: relative; display: grid; gap: 1.25rem; justify-items: center; max-inline-size: 38rem; text-align: center; }
      @media (max-width: 37.5rem) { .star.side { display: none; } }
      @media (prefers-reduced-motion: no-preference) {
        .star.twinkle { animation: twinkle 3.6s ease-in-out var(--delay, 0s) 3; }
        @keyframes twinkle { 50% { opacity: calc(var(--alpha, .85) * .35); } }
      }
      @media (forced-colors: active) {
        :host { background: Canvas; color: CanvasText; }
        .sky { display: none; }
      }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class NightSkyComponent {
    /** Identity of the sky: the same seed draws the same stars. */
    readonly seed = input('mnema-night');
    /** `false` for the survey and the branch choice. */
    readonly stars = input(true);
    /** `screen` covers the window (welcome, congratulations); `panel` fills its parent (a specimen). */
    readonly layout = input<'screen' | 'panel'>('screen');
    protected readonly sky = computed(() => skyFor(this.seed()));
}
