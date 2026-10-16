import { DOCUMENT } from '@angular/common';
import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, effect, inject, untracked, viewChild } from '@angular/core';

import { AuthService } from '../../auth.service';
import { AttentionCoordinator } from '../../core/onboarding/attention-coordinator';
import { JourneyStore } from '../../core/onboarding/journey.store';
import { onboardingText } from '../../core/onboarding/onboarding-texts';
import { TourHints } from '../../core/onboarding/tour-hints';
import { TourEngine } from '../../core/onboarding/tour-engine.service';
import { TourNudgeComponent } from '../../shared/tour-nudge.component';
import { FinaleComponent } from './finale.component';
import { FirstEntryComponent } from './first-entry.component';
import { OnboardingFlow } from './onboarding-flow.service';

/** How long a surface that waits for another modal dialog to close looks again, in ms. */
const MODAL_RECHECK_MS = 1_000;

/**
 * The place of the onboarding in the shell: loads the journey once per entry, opens the first entry when the journey asks for it and
 * the attention coordinator and the page allow, picks up a paused tour, and draws the surfaces the tour has beyond the spotlight: the
 * finale and the offer of «Своя колода». Mounted for learners only (not on the admin host); a guest has no journey, and nothing here
 * ever blocks a page: without an answer from the server there is simply no onboarding.
 */
@Component({
    selector: 'app-onboarding-host',
    imports: [FirstEntryComponent, FinaleComponent, TourNudgeComponent],
    template: `
      <app-first-entry />
      <app-finale />
      @if (hints.stuck() && engine.active() && !engine.paused()) {
        <app-tour-nudge [title]="text('community.stuck.title')" [body]="text('community.stuck.body')" [action]="text('community.stuck.action')"
          (accepted)="flow.switchToOwn()" (dismissed)="hints.stuck.set(false)" />
      }
    `,
    // The host is one of the tour's own surfaces: the offer of the community tour must stay live while a step makes the page inert.
    host: { 'data-tour-surface': '' },
    styles: [`:host { display: contents; }`],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class OnboardingHostComponent {
    protected readonly flow = inject(OnboardingFlow);
    protected readonly engine = inject(TourEngine);
    protected readonly hints = inject(TourHints);
    private readonly store = inject(JourneyStore);
    private readonly auth = inject(AuthService);
    private readonly attention = inject(AttentionCoordinator);
    private readonly document = inject(DOCUMENT);
    private readonly nudge = viewChild(TourNudgeComponent);
    private recheck: ReturnType<typeof setTimeout> | null = null;

    constructor() {
        // One read of the journey per entry, and none for a guest; signing out forgets it.
        effect(() => {
            const status = this.auth.status();
            untracked(() => {
                if (status === 'authenticated') void this.store.load();
                else if (status === 'anonymous') this.flow.reset();
            });
        });

        // The first entry opens by itself for a journey that wants it, when nothing louder is up and no other modal dialog is open. A
        // surface that waits for another modal looks again every second, and each look asks everything again (the modal may have been
        // followed by a tour or another dialog in the meantime).
        effect(() => {
            const wanted = this.flow.pendingCard();
            const open = this.flow.card() !== null;
            const authenticated = this.auth.status() === 'authenticated';
            this.attention.canShow('first-entry'); // read here so that the effect follows what the coordinator follows
            untracked(() => {
                if (this.recheck !== null) { clearTimeout(this.recheck); this.recheck = null; }
                const attempt = (): void => {
                    this.recheck = null;
                    const card = this.flow.pendingCard();
                    if (this.flow.card() !== null || card === null || this.auth.status() !== 'authenticated' || !this.attention.canShow('first-entry')) return;
                    if (this.otherModalOpen()) { this.recheck = setTimeout(attempt, MODAL_RECHECK_MS); return; }
                    this.flow.open(card);
                };
                if (open || wanted === null || !authenticated) return;
                attempt();
            });
        });

        // What is on the screen is the coordinator's to know; a feature that goes off takes the surface away.
        let wasOpen = false;
        effect(() => {
            const open = this.flow.card() !== null;
            untracked(() => {
                this.attention.report('first-entry', open);
                // The surface goes away from under the focus (a skip, a closed feature): the focus goes to the page heading, as after a route change.
                if (wasOpen && !open) {
                    queueMicrotask(() => {
                        const active = this.document.activeElement;
                        if (active === null || active === this.document.body) this.document.querySelector<HTMLElement>('#main-content h1')?.focus();
                    });
                }
                wasOpen = open;
            });
        });
        effect(() => {
            if (this.flow.card() !== null && !this.store.enabled()) untracked(() => this.flow.close());
        });
        // The feature was switched off while the page was open (a later read says so): a tour in progress is taken down with it. Not before
        // the first answer: a view that has not arrived says nothing, and a tour of another origin (the styleguide's) is not this journey's.
        effect(() => {
            const answered = this.store.view() !== null;
            const enabled = this.store.enabled();
            if (answered && !enabled && this.engine.active()) untracked(() => this.engine.stop());
        });

        // A journey paused (or left on a branch step) elsewhere shows the plaque once the page has found it.
        effect(() => {
            const journey = this.store.journey();
            const account = this.auth.user()?.accountId;
            if (journey !== null && account !== undefined) untracked(() => void this.flow.resumeIfPaused(account));
        });

        // The offer to make one's own deck takes the focus once, when it appears.
        effect(() => { if (this.hints.stuck()) untracked(() => queueMicrotask(() => this.nudge()?.focusHeading())); });

        inject(DestroyRef).onDestroy(() => { if (this.recheck !== null) clearTimeout(this.recheck); });
    }

    protected text(key: Parameters<typeof onboardingText>[0]): string { return onboardingText(key); }

    /** Another modal dialog (a complaint, a confirmation) is open: the first entry never stacks over it. */
    private otherModalOpen(): boolean {
        return Array.from(this.document.querySelectorAll('dialog[open]')).some(dialog => !dialog.closest('app-first-entry'));
    }
}
