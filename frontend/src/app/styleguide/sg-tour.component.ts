import { ChangeDetectionStrategy, Component, DestroyRef, ViewEncapsulation, afterNextRender, inject, input, signal, viewChild } from '@angular/core';

import { onboardingText } from '../core/onboarding/onboarding-texts';
import { TourAnchorDirective } from '../core/onboarding/tour-anchor.directive';
import { TourEngine } from '../core/onboarding/tour-engine.service';
import { TourEvents } from '../core/onboarding/tour-events';
import { Box, Size } from '../core/onboarding/tour-geometry';
import { HoldToDeleteButtonComponent } from '../shared/hold-to-delete-button.component';
import { NightSkyComponent } from '../shared/night-sky.component';
import { TOUR_HOLD_MS, TourCardComponent } from '../shared/tour-card.component';
import { TourPauseComponent } from '../shared/tour-pause.component';
import { TourScrimComponent } from '../shared/tour-scrim.component';
import { DEMO_GATE_BODY, DEMO_INFO_BODY, DEMO_SLIDES_BODY, DEMO_TOUR, DemoTourPersistence } from './sg-tour-demo';
import { SgSpecimenComponent } from './sg-specimen.component';

/** «Знакомство»: the elements of the spotlight tour in every state, and a live tour over a small deck hub. */
@Component({
    selector: 'app-sg-tour',
    encapsulation: ViewEncapsulation.None,
    imports: [SgSpecimenComponent, TourCardComponent, TourScrimComponent, TourPauseComponent, NightSkyComponent, HoldToDeleteButtonComponent, TourAnchorDirective],
    templateUrl: './sg-tour.component.html',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class SgTourComponent {
    /** The page's «Спокойное движение» preview. */
    readonly calm = input(false);

    protected readonly finishLabel = onboardingText('tour.finish');
    protected readonly finishArmedName = onboardingText('tour.finish.armed');
    protected readonly holdMs = TOUR_HOLD_MS;
    protected readonly infoBody = DEMO_INFO_BODY;
    protected readonly slidesBody = DEMO_SLIDES_BODY;
    protected readonly gateBody = DEMO_GATE_BODY;
    protected readonly gate = DEMO_TOUR.steps[3].gate;

    /** The specimen of the veil: a stage of fixed size with a target, the cut-out around it. */
    protected readonly stage: Size = { width: 420, height: 220 };
    protected readonly hole: Box = { x: 36, y: 42, width: 190, height: 56 };

    protected readonly engine = inject(TourEngine);
    private readonly events = inject(TourEvents);
    private readonly persistence = new DemoTourPersistence();
    protected readonly journal = this.persistence.journal;
    protected readonly finished = signal(false);

    private readonly armed = viewChild.required<HoldToDeleteButtonComponent>('armed');
    private readonly holding = viewChild.required<HoldToDeleteButtonComponent>('holding');
    private readonly calmHolding = viewChild.required<HoldToDeleteButtonComponent>('calmHolding');

    constructor() {
        afterNextRender(() => this.showHoldStates());
        // The demonstration points at this page's mock hub: it ends with the page.
        inject(DestroyRef).onDestroy(() => { if (this.engine.definition() === DEMO_TOUR) this.engine.stop(); });
    }

    /**
     * Puts the three static buttons into their states through the component's own public signals. The first press or any press outside
     * disarms them, as it should: «Показать состояния» sets them again.
     */
    protected showHoldStates(): void {
        const armed = this.armed();
        armed.armed.set(true);
        const holding = this.holding();
        holding.armed.set(true);
        holding.holding.set(true);
        holding.waveX.set(40);
        holding.waveY.set(22);
        holding.waveSize.set(70);
        holding.elapsed.set(1_000);
        const calm = this.calmHolding();
        calm.armed.set(true);
        calm.holding.set(true);
        calm.elapsed.set(1_000);
    }

    protected startDemo(): void {
        this.engine.start(DEMO_TOUR, undefined, { persistence: this.persistence });
    }

    /** What the real page does when the learner starts the session: it tells the tour. */
    protected startStudy(): void {
        this.events.emit('study.started');
    }
}
