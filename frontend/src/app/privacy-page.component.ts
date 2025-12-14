import { Component } from '@angular/core';
import { TranslatePipe } from './shared/pipes/translate.pipe';

@Component({
    standalone: true,
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
export class PrivacyPageComponent {}
