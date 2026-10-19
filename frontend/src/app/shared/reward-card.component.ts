import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { RouterLink } from '@angular/router';

import { PathReward } from '../core/onboarding/onboarding.models';
import { onboardingText } from '../core/onboarding/onboarding-texts';
import { rewardGift, rewardPlansQuery } from '../core/onboarding/reward-format';
import { RewardCodeComponent } from './reward-code.component';

let nextCard = 0;

/**
 * The reward of a finished «Путь» on the page of the path and in the profile (OB-402), quietly: while the reward is `ACTIVE` a paper block with
 * «Путь пройден», the gift sentence with the date the offer ends (as text, never a countdown), the code to copy, the terms and «Выбрать тариф»
 * (the plans page with the code in its promo field; the code is applied only by the learner there). A `REDEEMED` reward is one muted line;
 * an `EXPIRED` one, and none, draw nothing (no message about a loss). `level` is the heading level of the block in its page (2 on the page of
 * the path, 3 in the profile's block).
 */
@Component({
    selector: 'app-reward-card',
    imports: [RouterLink, RewardCodeComponent],
    template: `
      @if (reward(); as current) {
        @if (current.state === 'ACTIVE' && current.code !== null) {
          <section class="reward" [class.nested]="level() === 3" [attr.aria-labelledby]="headingId">
            <p class="eyebrow">{{ eyebrow }}</p>
            @if (level() === 2) { <h2 [id]="headingId">{{ title }}</h2> } @else { <h3 [id]="headingId">{{ title }}</h3> }
            <p class="gift">{{ gift() }}</p>
            <app-reward-code [code]="current.code" />
            <p class="hint">{{ terms }}</p>
            <p class="action"><a class="button primary" routerLink="/plans" [queryParams]="query()">{{ action }}</a></p>
          </section>
        } @else if (current.state === 'REDEEMED') {
          <p class="redeemed hint">{{ redeemed }}</p>
        }
      }
    `,
    styles: [`
      :host { display: block; min-inline-size: 0; }
      .reward { display: grid; gap: .75rem; justify-items: start; min-inline-size: 0; border-block-start: 1px solid var(--mn-ink); border-block-end: 1px solid var(--mn-rule); padding: clamp(1.25rem, 3vw, 2rem); background: var(--mn-sheet); }
      /* In the profile the block sits inside the sheet of «Путь Mnema»: a rule, not a second card. */
      .reward.nested { border-block-start-color: var(--mn-rule); padding: .75rem 0 0; background: transparent; }
      .eyebrow { margin: 0; }
      h2, h3 { margin: 0; color: var(--mn-ink); font: 500 clamp(1.6rem, 3vw, 2.1rem)/1.1 var(--mn-font-display); overflow-wrap: anywhere; }
      p { margin: 0; }
      .gift { color: var(--mn-ink); font-size: 1.1rem; font-weight: 600; }
      .redeemed { margin: 0; }
      @media (max-width: 36rem) { .reward { padding-inline: 0; background: transparent; } }
      @media (forced-colors: active) { .reward { border-color: CanvasText; } }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class RewardCardComponent {
    readonly reward = input.required<PathReward | null>();
    readonly level = input<2 | 3>(2);

    protected readonly headingId = `mn-reward-card-${nextCard++}`;
    protected readonly eyebrow = onboardingText('reward.eyebrow');
    protected readonly title = onboardingText('reward.title');
    protected readonly terms = onboardingText('reward.terms');
    protected readonly action = onboardingText('reward.action');
    protected readonly redeemed = onboardingText('reward.redeemed');
    protected readonly gift = computed(() => {
        const current = this.reward();
        return current === null ? '' : rewardGift(current);
    });
    protected readonly query = computed(() => rewardPlansQuery(this.reward()?.code ?? ''));
}
