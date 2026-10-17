import { ChangeDetectionStrategy, Component, inject } from '@angular/core';

import { JourneyStore } from '../../core/onboarding/journey.store';
import { onboardingText } from '../../core/onboarding/onboarding-texts';
import { OnboardingFlow } from './onboarding-flow.service';

/**
 * «Пройти знакомство заново», in the profile: for every signed-in account while onboarding is on, whether it ever saw the tour or not.
 * It opens the branch choice without the survey; the survey's answers are never changed by a replay and no deck is made by it.
 */
@Component({
    selector: 'app-replay-tour',
    template: `
      @if (store.enabled()) {
        <button type="button" class="button" [attr.aria-disabled]="flow.busy() ? 'true' : null" (click)="flow.replay()">{{ label }}</button>
      }
    `,
    styles: [`:host { display: contents; }`],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class ReplayTourComponent {
    protected readonly store = inject(JourneyStore);
    protected readonly flow = inject(OnboardingFlow);
    protected readonly label = onboardingText('path.replay');
}
