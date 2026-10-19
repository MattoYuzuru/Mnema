import { DOCUMENT } from '@angular/common';
import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, afterRenderEffect, computed, effect, inject, signal, untracked, viewChild } from '@angular/core';
import { RouterLink } from '@angular/router';

import { AuthService } from '../../auth.service';
import { AttentionCoordinator } from '../../core/onboarding/attention-coordinator';
import { JourneyStore } from '../../core/onboarding/journey.store';
import { PathReward } from '../../core/onboarding/onboarding.models';
import { onboardingText } from '../../core/onboarding/onboarding-texts';
import { PathPreferences } from '../../core/onboarding/path-preferences';
import { rewardGift, rewardPlansQuery } from '../../core/onboarding/reward-format';
import { NightSkyComponent } from '../../shared/night-sky.component';
import { RewardCodeComponent } from '../../shared/reward-code.component';

/** How long a congratulation that waits for another modal dialog to close looks again, in ms. */
const MODAL_RECHECK_MS = 1_000;

/**
 * The congratulation for a finished «Путь» (OB-402): the night with its stars, «Путь пройден» / «Вы освоили Mnema полностью», the gift with the
 * date the offer ends as text, the personal code to copy, the terms and two ways on: «Выбрать тариф» (the plans page with the code in its promo
 * field) and «Позже». No countdown, no seconds, no urgency.
 *
 * It is shown once per reward on a device: when the journey view carries an `ACTIVE` reward whose congratulation this device has not shown
 * (`PathPreferences.rewardShown`, per account), and it is marked shown the moment it opens, so a reload, the other page or «Позже» does not bring
 * it back. The reward stays on «Путь» and in the profile. It is a loud surface of the attention coordinator (`reward`): never during the tour,
 * the first entry, an open answer in Study (nor on the study route), a hint, or a promo window, and not over another modal dialog; whatever
 * holds it back, it comes up when the way is free. A learner who hid «Путь» still gets it: it is a gift with an end date, not a milestone toast.
 * It is a modal `<dialog>` named by its heading; the focus goes to the heading, `Позже` and Esc close it and return the focus to where it was.
 */
@Component({
    selector: 'app-reward-congratulation',
    imports: [NightSkyComponent, RewardCodeComponent, RouterLink],
    template: `
      @if (shown(); as reward) {
        <dialog #surface class="reward-night" aria-labelledby="reward-title" aria-describedby="reward-gift" (cancel)="later($event)" (close)="closed()">
          <app-night-sky layout="panel" seed="mnema-reward">
            <p class="eyebrow">{{ text.eyebrow }}</p>
            <h1 #heading id="reward-title" tabindex="-1">{{ text.title }}</h1>
            <p id="reward-gift" class="lead">{{ gift() }}</p>
            @if (reward.code; as code) { <app-reward-code [code]="code" tone="night" /> }
            <p class="muted">{{ text.terms }}</p>
            <div class="actions">
              @if (reward.code !== null) {
                <a class="button primary" routerLink="/plans" [queryParams]="query()" (click)="followed()">{{ text.action }}</a>
              }
              <button type="button" class="button quiet" (click)="later()">{{ text.later }}</button>
            </div>
          </app-night-sky>
        </dialog>
      }
    `,
    styleUrl: './reward-congratulation.component.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class RewardCongratulationComponent {
    private readonly store = inject(JourneyStore);
    private readonly auth = inject(AuthService);
    private readonly attention = inject(AttentionCoordinator);
    private readonly preferences = inject(PathPreferences);
    private readonly document = inject(DOCUMENT);
    private readonly surface = viewChild<ElementRef<HTMLDialogElement>>('surface');
    private readonly heading = viewChild<ElementRef<HTMLElement>>('heading');

    protected readonly text = {
        eyebrow: onboardingText('reward.eyebrow'), title: onboardingText('reward.title'), terms: onboardingText('reward.terms'),
        action: onboardingText('reward.action'), later: onboardingText('reward.later')
    };
    /** The reward on the screen now, or `null`. It is a snapshot: marking it shown does not take the card away from under the learner. */
    protected readonly shown = signal<PathReward | null>(null);
    protected readonly gift = computed(() => {
        const reward = this.shown();
        return reward === null ? '' : rewardGift(reward);
    });
    protected readonly query = computed(() => rewardPlansQuery(this.shown()?.code ?? ''));
    /** The reward this device owes a congratulation for: active, with its code, not shown here before. */
    private readonly pending = computed(() => {
        const reward = this.store.view()?.reward ?? null;
        return reward !== null && reward.state === 'ACTIVE' && reward.code !== null && !this.preferences.rewardShown(reward.issuedAt) ? reward : null;
    });
    private recheck: ReturnType<typeof setTimeout> | null = null;
    private returnTo: HTMLElement | null = null;
    private focusRequested = false;

    constructor() {
        // A reward is wanted: ask the coordinator whenever what it follows changes, and open when the way is free. Another modal dialog (a complaint,
        // a confirmation) is waited out, looking again every second; each look asks everything again.
        effect(() => {
            const wanted = this.pending();
            const authenticated = this.auth.status() === 'authenticated';
            this.attention.canShow('reward'); // read here so that the effect follows what the coordinator follows
            untracked(() => {
                this.clearRecheck();
                if (wanted === null || !authenticated || this.shown() !== null) return;
                this.attempt();
            });
        });
        // What is on the screen is the coordinator's to know.
        effect(() => {
            const open = this.shown() !== null;
            untracked(() => this.attention.report('reward', open));
        });
        // The learner signed out, or the reward is no longer the account's (another account on the same page): nothing of it stays.
        effect(() => {
            const open = this.shown();
            const reward = this.store.view()?.reward ?? null;
            if (open !== null && (reward === null || reward.issuedAt !== open.issuedAt || reward.state !== 'ACTIVE')) untracked(() => this.dismiss(false));
        });
        afterRenderEffect(() => {
            const dialog = this.surface()?.nativeElement;
            const heading = this.heading()?.nativeElement;
            if (dialog === undefined || heading === undefined) return;
            untracked(() => {
                if (!dialog.open) dialog.showModal();
                if (this.focusRequested) { this.focusRequested = false; heading.focus({ preventScroll: true }); }
            });
        });
        inject(DestroyRef).onDestroy(() => { this.clearRecheck(); this.attention.report('reward', false); });
    }

    /** «Позже» and Esc: the congratulation closes, the reward stays on «Путь», and the focus returns to where it was. */
    protected later(event?: Event): void {
        event?.preventDefault();
        this.dismiss(true);
    }

    /** Whatever else closes the dialog (a `close()` from outside, a form method): the same way out, so the congratulation and its place with the coordinator are always released. */
    protected closed(): void {
        if (this.shown() !== null) this.dismiss(true);
    }

    /** «Выбрать тариф»: the link goes to the plans, the night goes away behind it. */
    protected followed(): void {
        this.dismiss(false);
    }

    private attempt(): void {
        this.recheck = null;
        const reward = this.pending();
        if (reward === null || this.shown() !== null || this.auth.status() !== 'authenticated' || !this.attention.canShow('reward')) return;
        if (this.otherModalOpen()) { this.recheck = setTimeout(() => this.attempt(), MODAL_RECHECK_MS); return; }
        // With nothing focused the active element is the body: that is no place to return to, the heading of the page is.
        const active = this.document.activeElement;
        this.returnTo = active instanceof HTMLElement && active !== this.document.body ? active : null;
        this.focusRequested = true;
        // Shown once on this device: marked as it opens.
        this.preferences.markRewardShown(reward.issuedAt);
        this.shown.set(reward);
    }

    private dismiss(restoreFocus: boolean): void {
        const dialog = this.surface()?.nativeElement;
        // Released first: the `close` event of the dialog comes back here (`closed()`) and finds nothing left to do.
        this.shown.set(null);
        if (dialog?.open === true) dialog.close();
        const target = this.returnTo;
        this.returnTo = null;
        this.focusRequested = false;
        if (!restoreFocus) return;
        // Back to where the learner was; if that is gone, to the heading of the page, as after a route change.
        queueMicrotask(() => {
            if (target?.isConnected === true) target.focus({ preventScroll: true });
            else this.document.querySelector<HTMLElement>('#main-content h1')?.focus();
        });
    }

    private clearRecheck(): void {
        if (this.recheck !== null) { clearTimeout(this.recheck); this.recheck = null; }
    }

    private otherModalOpen(): boolean {
        return Array.from(this.document.querySelectorAll('dialog[open]')).some(dialog => !dialog.closest('app-reward-congratulation'));
    }
}
