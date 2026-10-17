import { DOCUMENT } from '@angular/common';
import { ChangeDetectionStrategy, Component, ElementRef, afterRenderEffect, computed, effect, inject, signal, untracked, viewChild } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';

import { onboardingText } from '../../core/onboarding/onboarding-texts';
import { TourEngine } from '../../core/onboarding/tour-engine.service';
import { Size } from '../../core/onboarding/tour-geometry';
import { linkedText, richText } from '../../core/onboarding/tour-rich-text';
import { NightSkyComponent } from '../../shared/night-sky.component';
import { TourScrimComponent } from '../../shared/tour-scrim.component';
import { PlansApiService } from '../plans/plans-api.service';
import { OnboardingFlow } from './onboarding-flow.service';
import { PATH_TARGET } from './onboarding-targets';

/**
 * The end of a branch tour, drawn for the two external steps of the definition: «ИИ в Mnema» (only where AI exists), a modal card on the
 * veil with what the Workshop and the checking do and the free monthly allowance from the plans; then the congratulation, the night with
 * its stars, with a way back to the deck. «Тарифы» is a link the learner follows, never an automatic jump (it pauses the tour first).
 * Esc pauses, like on any step. No confetti: the night is still under reduced motion.
 */
@Component({
    selector: 'app-finale',
    imports: [NightSkyComponent, TourScrimComponent, RouterLink],
    host: { '(window:resize)': 'resize()' },
    template: `
      @if (key() === 'finale.ai') {
        <app-tour-scrim [viewport]="viewport()" />
        <dialog #card class="finale-card" aria-labelledby="finale-ai-title" (cancel)="$event.preventDefault()">
          <p class="eyebrow">{{ text('ai.eyebrow') }}</p>
          <h2 #aiTitle id="finale-ai-title" tabindex="-1">{{ text('ai.title') }}</h2>
          <p>{{ text('ai.body') }}</p>
          <ul>
            <li>@for (part of workshop; track $index) { <span [class.key]="part.key">{{ part.text }}</span> }</li>
            <li>@for (part of check; track $index) { <span [class.key]="part.key">{{ part.text }}</span> }</li>
          </ul>
          @if (allowance(); as free) {
            <p class="hint">{{ free }} <a routerLink="/plans" (click)="openPlans($event)">{{ text('ai.plans') }}</a></p>
          }
          <div class="nav"><button type="button" class="button primary" (click)="engine.next()">{{ text('ai.next') }}</button></div>
        </dialog>
      } @else if (key() === 'finale.done') {
        <dialog #night class="finale-night" aria-labelledby="finale-done-title" (cancel)="$event.preventDefault()">
          <app-night-sky layout="panel" seed="mnema-congratulations">
            <p class="eyebrow">{{ text('congrats.eyebrow') }}</p>
            <h1 #doneTitle id="finale-done-title" tabindex="-1">{{ text('congrats.title') }}</h1>
            <p class="lead">{{ congratulation.before }}<a [routerLink]="path">{{ congratulation.link }}</a>{{ congratulation.after }}</p>
            <div class="actions"><button type="button" class="button primary" (click)="done()">{{ text('congrats.action') }}</button></div>
          </app-night-sky>
        </dialog>
      }
    `,
    styleUrl: './finale.component.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class FinaleComponent {
    protected readonly engine = inject(TourEngine);
    private readonly flow = inject(OnboardingFlow);
    private readonly router = inject(Router);
    private readonly document = inject(DOCUMENT);
    private readonly plans = inject(PlansApiService);
    private readonly card = viewChild<ElementRef<HTMLDialogElement>>('card');
    private readonly night = viewChild<ElementRef<HTMLDialogElement>>('night');
    private readonly aiTitle = viewChild<ElementRef<HTMLElement>>('aiTitle');
    private readonly doneTitle = viewChild<ElementRef<HTMLElement>>('doneTitle');

    protected readonly path = PATH_TARGET;
    protected readonly workshop = richText(onboardingText('ai.workshop'));
    protected readonly check = richText(onboardingText('ai.check'));
    protected readonly congratulation = linkedText(onboardingText('congrats.body'));
    protected readonly viewport = signal<Size>({ width: window.innerWidth, height: window.innerHeight });
    protected readonly allowance = signal<string | null>(null);

    /** The external step on the screen, or `null`. */
    protected readonly key = computed(() => {
        const step = this.engine.step();
        return this.engine.active() && !this.engine.paused() && step?.external === true ? step.key : null;
    });

    constructor() {
        // The free allowance is read when the card is first wanted; no allowance, no line.
        effect(() => {
            if (this.key() !== 'finale.ai') return;
            untracked(() => void this.loadAllowance());
        });
        afterRenderEffect(() => {
            const key = this.key();
            const dialog = (key === 'finale.ai' ? this.card() : key === 'finale.done' ? this.night() : undefined)?.nativeElement;
            if (dialog === undefined) return;
            untracked(() => {
                if (!dialog.open) dialog.showModal();
                (key === 'finale.ai' ? this.aiTitle() : this.doneTitle())?.nativeElement.focus({ preventScroll: true });
            });
        });
    }

    protected resize(): void {
        this.viewport.set({ width: this.document.defaultView?.innerWidth ?? 0, height: this.document.defaultView?.innerHeight ?? 0 });
    }


    protected text(key: Parameters<typeof onboardingText>[0]): string { return onboardingText(key); }

    private async loadAllowance(): Promise<void> {
        if (this.allowance() !== null) return;
        try {
            const free = (await firstValueFrom(this.plans.load())).plans.find(plan => plan.plan === 'FREE');
            if (free !== undefined && free.highlights.length > 0) {
                this.allowance.set(onboardingText('ai.free', { freeAllowance: free.highlights.map(line => line.replace(/\s+в месяц$/u, '')).join(' · ') }));
            }
        } catch { /* Without the plans there is no allowance line: the card says the rest. */ }
    }

    /** «Тарифы»: the tour waits on its plaque while the learner reads the plans. */
    protected openPlans(event: Event): void {
        event.preventDefault();
        this.engine.pause();
        void this.router.navigateByUrl('/plans');
    }

    /** «К колоде»: the tour is completed, and the learner goes back to the deck they worked on. */
    protected done(): void {
        const deck = this.flow.currentDeck();
        this.engine.next();
        void this.router.navigate(deck === null ? ['/decks'] : ['/decks', deck]);
    }
}
