import { Component, ChangeDetectionStrategy } from '@angular/core';
import { TranslatePipe } from './shared/pipes/translate.pipe';

@Component({
    imports: [TranslatePipe],
    selector: 'app-privacy-page',
    template: `
    <div class="legal-page">
      <h1>{{ 'privacy.title' | translate }}</h1>
      <p class="last-updated">{{ 'privacy.lastUpdated' | translate }}</p>

      <section>
        <h2>{{ 'privacy.infoCollect' | translate }}</h2>
        <p>{{ 'privacy.infoCollectText' | translate }}</p>
      </section>

      <section>
        <h2>{{ 'privacy.infoUse' | translate }}</h2>
        <p>{{ 'privacy.infoUseText' | translate }}</p>
      </section>

      <section>
        <h2>{{ 'privacy.infoSharing' | translate }}</h2>
        <p>{{ 'privacy.infoSharingText' | translate }}</p>
      </section>

      <section>
        <h2>{{ 'privacy.dataSecurity' | translate }}</h2>
        <p>{{ 'privacy.dataSecurityText' | translate }}</p>
      </section>

      <section>
        <h2>{{ 'privacy.yourRights' | translate }}</h2>
        <p>{{ 'privacy.yourRightsText' | translate }}</p>
      </section>

      <section>
        <h2>{{ 'privacy.contact' | translate }}</h2>
        <p>{{ 'privacy.contactText' | translate }}</p>
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
export class PrivacyPageComponent {}
