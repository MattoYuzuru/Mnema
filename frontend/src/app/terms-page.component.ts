import { Component, ChangeDetectionStrategy } from '@angular/core';
import { TranslatePipe } from './shared/pipes/translate.pipe';

@Component({
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
    changeDetection: ChangeDetectionStrategy.Eager,
    styles: [`
      .legal-page {
        max-width: 56rem;
        margin: 0 auto;
        padding: var(--mn-space-7) var(--mn-page-gutter);
      }

      h1 {
        font-size: clamp(2.5rem, 5vw, 3.5rem);
        margin: 0 0 var(--mn-space-2) 0;
      }

      .last-updated {
        font-size: 0.9rem;
        color: var(--mn-muted);
        margin: 0 0 var(--mn-space-7) 0;
      }

      section {
        margin-bottom: var(--mn-space-7);
      }

      h2 {
        font-size: 2rem;
        margin: 0 0 var(--mn-space-4) 0;
      }

      p {
        line-height: 1.6;
        color: var(--mn-body);
        margin: 0 0 var(--mn-space-4) 0;
      }

      @media (max-width: 768px) {
        .legal-page {
          padding-block: var(--mn-space-5);
        }

        h1 {
          font-size: 2.5rem;
        }

        h2 {
          font-size: 1.75rem;
        }
      }

      @media (max-width: 480px) {
        .legal-page {
          padding-block: var(--mn-space-4);
        }

        h1 {
          font-size: 2.25rem;
        }
      }
    `]
})
export class TermsPageComponent {}
