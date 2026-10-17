import { ChangeDetectionStrategy, Component } from '@angular/core';

import { onboardingText } from '../core/onboarding/onboarding-texts';

/**
 * The mark of the learner's sample deck at the head of its hub: the `.stamp` «Учебная» and the approved sentence that says what the deck is
 * for. Plain text in a paragraph (no live region, no role): the hub does not announce what is not news. Only the hub of the sample copy
 * draws it; no other deck does.
 */
@Component({
    selector: 'app-sample-plaque',
    template: `<p class="sample-plaque"><span class="stamp">{{ stamp }}</span><span>{{ plaque }}</span></p>`,
    styles: [`
      :host { display: block; }
      .sample-plaque { display: flex; flex-wrap: wrap; gap: var(--mn-space-2) var(--mn-space-3); align-items: center; margin: 0; }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class SamplePlaqueComponent {
    protected readonly stamp = onboardingText('sample.stamp');
    protected readonly plaque = onboardingText('sample.plaque');
}
