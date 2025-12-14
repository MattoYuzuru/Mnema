import { Component } from '@angular/core';
import { TranslatePipe } from './shared/pipes/translate.pipe';

@Component({
    standalone: true,
    imports: [TranslatePipe],
    selector: 'app-terms-page',
    template: `
    <div class="legal-page">
      <h1>{{ 'terms.title' | translate }}</h1>
      <p class="last-updated">{{ 'terms.lastUpdated' | translate }}</p>

      <section>
        <h2>{{ 'terms.acceptance' | translate }}</h2>
        <p>{{ 'terms.acceptanceText' | translate }}</p>
      </section>

      <section>
        <h2>{{ 'terms.useLicense' | translate }}</h2>
        <p>{{ 'terms.useLicenseText' | translate }}</p>
      </section>

      <section>
        <h2>{{ 'terms.userContent' | translate }}</h2>
        <p>{{ 'terms.userContentText' | translate }}</p>
      </section>

      <section>
        <h2>{{ 'terms.prohibited' | translate }}</h2>
        <p>{{ 'terms.prohibitedText' | translate }}</p>
      </section>

      <section>
        <h2>{{ 'terms.disclaimer' | translate }}</h2>
        <p>{{ 'terms.disclaimerText' | translate }}</p>
      </section>

      <section>
        <h2>{{ 'terms.liability' | translate }}</h2>
        <p>{{ 'terms.liabilityText' | translate }}</p>
      </section>

      <section>
        <h2>{{ 'terms.changes' | translate }}</h2>
        <p>{{ 'terms.changesText' | translate }}</p>
      </section>

      <section>
        <h2>{{ 'terms.contact' | translate }}</h2>
        <p>{{ 'terms.contactText' | translate }}</p>
      </section>
    </div>
  `,
    styles: [`
      .legal-page {
        max-width: 56rem;
        margin: 0 auto;
        padding: var(--spacing-xl) 0;
      }

      h1 {
        font-size: 2rem;
        margin: 0 0 var(--spacing-sm) 0;
      }

      .last-updated {
        font-size: 0.9rem;
        color: var(--color-text-muted);
        margin: 0 0 var(--spacing-2xl) 0;
      }

      section {
        margin-bottom: var(--spacing-xl);
      }

      h2 {
        font-size: 1.5rem;
        margin: 0 0 var(--spacing-md) 0;
      }

      p {
        line-height: 1.6;
        color: var(--color-text-secondary);
        margin: 0 0 var(--spacing-md) 0;
      }
    `]
})
export class TermsPageComponent {}
