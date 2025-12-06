import { Component } from '@angular/core';

@Component({
    standalone: true,
    selector: 'app-terms-page',
    template: `
    <div class="legal-page">
      <h1>Terms of Service</h1>
      <p class="last-updated">Last updated: December 2025</p>

      <section>
        <h2>Acceptance of Terms</h2>
        <p>By accessing and using Mnema, you accept and agree to be bound by the terms and provisions of this agreement.</p>
      </section>

      <section>
        <h2>Use License</h2>
        <p>Permission is granted to temporarily use Mnema for personal, non-commercial educational purposes. This is the grant of a license, not a transfer of title.</p>
      </section>

      <section>
        <h2>User Content</h2>
        <p>You retain all rights to the flashcard content you create. By making decks public, you grant other users the right to fork and use your content for their personal learning.</p>
      </section>

      <section>
        <h2>Prohibited Uses</h2>
        <p>You may not use Mnema for any illegal purpose or to violate any laws. You may not attempt to gain unauthorized access to any portion of the service.</p>
      </section>

      <section>
        <h2>Disclaimer</h2>
        <p>Mnema is provided "as is" without any representations or warranties. We do not guarantee that the service will be uninterrupted or error-free.</p>
      </section>

      <section>
        <h2>Limitation of Liability</h2>
        <p>In no event shall Mnema be liable for any damages arising out of the use or inability to use the service.</p>
      </section>

      <section>
        <h2>Changes to Terms</h2>
        <p>We reserve the right to modify these terms at any time. Continued use of the service after changes constitutes acceptance of the new terms.</p>
      </section>

      <section>
        <h2>Contact</h2>
        <p>For questions about these Terms of Service, please contact us through our GitHub repository.</p>
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
