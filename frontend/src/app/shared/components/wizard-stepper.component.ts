import { Component, Input } from '@angular/core';
import { NgFor, NgClass, NgIf } from '@angular/common';

export interface WizardStep {
    label: string;
    completed: boolean;
}

@Component({
    selector: 'app-wizard-stepper',
    standalone: true,
    imports: [NgFor, NgClass, NgIf],
    template: `
    <div class="wizard-stepper">
      <div class="stepper-progress">
        <div
          class="stepper-progress-bar"
          [style.width.%]="progressPercentage"
        ></div>
      </div>

      <div class="stepper-steps">
        <div
          *ngFor="let step of steps; let i = index"
          class="stepper-step"
          [ngClass]="{
            'stepper-step-active': i === currentStep,
            'stepper-step-completed': step.completed
          }"
        >
          <div class="stepper-step-marker">
            <span *ngIf="!step.completed" class="step-number">{{ i + 1 }}</span>
            <span *ngIf="step.completed" class="step-checkmark">✓</span>
          </div>
          <div class="stepper-step-label">{{ step.label }}</div>
        </div>
      </div>
    </div>
  `,
    styles: [
        `
      .wizard-stepper {
        display: flex;
        flex-direction: column;
        gap: var(--spacing-md);
      }

      .stepper-progress {
        height: 0.25rem;
        background: var(--border-color);
        border-radius: var(--border-radius-full);
        overflow: hidden;
      }

      .stepper-progress-bar {
        height: 100%;
        background: var(--color-primary-accent);
        transition: width 0.3s ease;
      }

      .stepper-steps {
        display: flex;
        justify-content: space-between;
        gap: var(--spacing-sm);
      }

      .stepper-step {
        flex: 1;
        display: flex;
        flex-direction: column;
        align-items: center;
        gap: var(--spacing-xs);
      }

      .stepper-step-marker {
        width: 2rem;
        height: 2rem;
        display: flex;
        align-items: center;
        justify-content: center;
        background: var(--color-card-background);
        border: 2px solid var(--border-color);
        border-radius: 50%;
        font-size: 0.9rem;
        font-weight: 600;
        color: var(--color-text-muted);
        transition: all 0.3s ease;
      }

      .stepper-step-active .stepper-step-marker {
        border-color: var(--color-primary-accent);
        background: var(--color-primary-accent);
        color: #fff;
      }

      .stepper-step-completed .stepper-step-marker {
        border-color: var(--color-primary-accent);
        background: var(--color-primary-accent);
        color: #fff;
      }

      .step-checkmark {
        line-height: 1;
      }

      .stepper-step-label {
        font-size: 0.85rem;
        font-weight: 500;
        color: var(--color-text-muted);
        text-align: center;
        transition: color 0.3s ease;
      }

      .stepper-step-active .stepper-step-label {
        color: var(--color-text-primary);
      }

      .stepper-step-completed .stepper-step-label {
        color: var(--color-text-primary);
      }
    `
    ]
})
export class WizardStepperComponent {
    @Input() steps: WizardStep[] = [];
    @Input() currentStep = 0;

    get progressPercentage(): number {
        if (this.steps.length === 0) return 0;
        return ((this.currentStep + 1) / this.steps.length) * 100;
    }
}
