import { Component, OnInit, OnDestroy } from '@angular/core';
import { Router } from '@angular/router';
import { NgIf, NgSwitch, NgSwitchCase } from '@angular/common';
import { Subscription } from 'rxjs';
import { DeckWizardStateService, DeckWizardState } from './deck-wizard-state.service';
import { WizardStepperComponent } from '../../shared/components/wizard-stepper.component';
import { TemplateSelectionStepComponent } from './steps/template-selection-step.component';
import { DeckMetadataStepComponent } from './steps/deck-metadata-step.component';
import { InitialContentStepComponent } from './steps/initial-content-step.component';
import { ReviewStepComponent } from './steps/review-step.component';
import { TranslatePipe } from '../../shared/pipes/translate.pipe';
import { I18nService } from '../../core/services/i18n.service';

@Component({
    selector: 'app-deck-wizard',
    standalone: true,
    imports: [NgIf, NgSwitch, NgSwitchCase, WizardStepperComponent, TemplateSelectionStepComponent, DeckMetadataStepComponent, InitialContentStepComponent, ReviewStepComponent, TranslatePipe],
    template: `
    <div class="deck-wizard">
      <header class="wizard-header">
        <h1>{{ 'wizard.createNewDeck' | translate }}</h1>
        <app-wizard-stepper [steps]="steps" [currentStep]="state.currentStep - 1"></app-wizard-stepper>
      </header>
      <div class="deck-wizard-content" [ngSwitch]="state.currentStep">
        <app-template-selection-step *ngSwitchCase="1" (next)="wizardState.nextStep()"></app-template-selection-step>
        <app-deck-metadata-step *ngSwitchCase="2" (next)="wizardState.nextStep()" (back)="wizardState.previousStep()"></app-deck-metadata-step>
        <app-initial-content-step *ngSwitchCase="3" (next)="wizardState.nextStep()" (back)="wizardState.previousStep()"></app-initial-content-step>
        <app-review-step *ngSwitchCase="4" (finish)="onFinish($event)" (back)="wizardState.previousStep()"></app-review-step>
      </div>
    </div>
  `,
    styles: [`
      .deck-wizard { width: min(56rem, 100%); max-width: 56rem; margin: 0 auto; padding: var(--spacing-xl); }
      .wizard-header { margin-bottom: var(--spacing-xl); }
      .wizard-header h1 { font-size: 2rem; font-weight: 700; margin: 0 0 var(--spacing-lg) 0; }
      .deck-wizard-content { width: 100%; min-width: 0; background: var(--color-card-background); border: 1px solid var(--border-color); border-radius: var(--border-radius-lg); padding: var(--spacing-xl); min-height: 400px; }

      @media (max-width: 900px) {
        .deck-wizard {
          padding: var(--spacing-lg) var(--spacing-md);
        }

        .wizard-header h1 {
          font-size: 1.5rem;
        }

        .deck-wizard-content {
          padding: var(--spacing-lg);
        }
      }

      @media (max-width: 600px) {
        .deck-wizard {
          padding: var(--spacing-md) var(--spacing-sm);
        }

        .deck-wizard-content {
          padding: var(--spacing-md);
          min-height: auto;
        }
      }
    `]
})
export class DeckWizardComponent implements OnInit, OnDestroy {
    state: DeckWizardState;
    private subscription?: Subscription;

    get steps() {
        const cs = this.state.currentStep;
        return [
            { label: this.i18n.translate('wizard.stepTemplate'), completed: cs > 1 },
            { label: this.i18n.translate('wizard.stepDeckInfo'), completed: cs > 2 },
            { label: this.i18n.translate('wizard.stepContent'), completed: cs > 3 },
            { label: this.i18n.translate('wizard.stepReview'), completed: cs > 4 }
        ];
    }

    constructor(public wizardState: DeckWizardStateService, private router: Router, private i18n: I18nService) {
        this.state = this.wizardState.getCurrentState();
    }

    ngOnInit(): void {
        this.subscription = this.wizardState.getState().subscribe(state => {
            this.state = state;
        });
    }

    ngOnDestroy(): void {
        this.subscription?.unsubscribe();
    }

    onFinish(destination: 'deck' | 'home'): void {
        const { createdDeck } = this.state;
        this.wizardState.reset();
        if (destination === 'deck' && createdDeck) {
            void this.router.navigate(['/decks', createdDeck.userDeckId]);
        } else {
            void this.router.navigate(['/']);
        }
    }
}
