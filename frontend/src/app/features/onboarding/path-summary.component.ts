import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { RouterLink } from '@angular/router';

import { JourneyStore } from '../../core/onboarding/journey.store';
import { onboardingText } from '../../core/onboarding/onboarding-texts';
import { PathPreferences } from '../../core/onboarding/path-preferences';
import { PathProgressComponent } from '../../shared/path-progress.component';

/**
 * The block «Путь Mnema» of the profile: how far the learner is (`path.total` and a bar) and the way to the page. Only while onboarding is
 * on (the rollback switch). A learner who hid «Путь» sees the link alone, and no milestone toasts.
 */
@Component({
    selector: 'app-path-summary',
    imports: [RouterLink, PathProgressComponent],
    template: `
      @if (store.enabled()) {
        <section class="path-summary" aria-labelledby="path-summary-title">
          <h2 id="path-summary-title">{{ title }}</h2>
          @if (!preferences.hidden() && total(); as text) {
            <p class="total" aria-hidden="true">{{ text }}</p>
            <app-path-progress [label]="title" [value]="store.view()?.path?.done ?? 0" [max]="store.view()?.path?.total ?? 1" [valueText]="text" />
          }
          <p><a class="button" routerLink="/profile/path">{{ open }}</a></p>
        </section>
      }
    `,
    styles: [`
      :host { display: block; min-inline-size: 0; }
      .path-summary { display: grid; gap: .75rem; justify-items: start; min-inline-size: 0; border-block-start: 1px solid var(--mn-ink); border-block-end: 1px solid var(--mn-rule); padding: clamp(1.25rem, 3vw, 2rem); background: var(--mn-sheet); }
      h2 { margin: 0; color: var(--mn-ink); font: 500 clamp(1.8rem, 3vw, 2.25rem)/1.05 var(--mn-font-display); overflow-wrap: anywhere; }
      p { margin: 0; }
      .total { color: var(--mn-ink); font-size: 1.15rem; font-weight: 650; }
      app-path-progress { inline-size: min(100%, 16rem); }
      @media (max-width: 36rem) { .path-summary { padding-inline: 0; background: transparent; } }
      @media (forced-colors: active) { .path-summary { border-color: CanvasText; } }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class PathSummaryComponent {
    protected readonly store = inject(JourneyStore);
    protected readonly preferences = inject(PathPreferences);
    protected readonly title = onboardingText('path.title');
    protected readonly open = onboardingText('path.open');
    protected readonly total = computed(() => {
        const path = this.store.view()?.path ?? null;
        return path === null ? '' : onboardingText('path.total', { done: path.done, total: path.total });
    });
}
