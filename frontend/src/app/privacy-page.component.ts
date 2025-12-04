import { Component } from '@angular/core';

@Component({
    standalone: true,
    selector: 'app-privacy-page',
    template: `
    <div class="legal-page">
      <h1>Privacy Policy</h1>
      <p class="last-updated">Last updated: December 2025</p>

      <section>
        <h2>Information We Collect</h2>
        <p>When you use Mnema, we collect information that you provide directly to us, including your email address, username, and the flashcard decks and content you create.</p>
      </section>

      <section>
        <h2>How We Use Your Information</h2>
        <p>We use the information we collect to provide, maintain, and improve our services, including to process your flashcard study sessions and track your learning progress.</p>
      </section>

      <section>
        <h2>Information Sharing</h2>
        <p>We do not sell or share your personal information with third parties except as necessary to provide our services or as required by law.</p>
      </section>

      <section>
        <h2>Data Security</h2>
        <p>We implement appropriate security measures to protect your personal information against unauthorized access, alteration, or destruction.</p>
      </section>

      <section>
        <h2>Your Rights</h2>
        <p>You have the right to access, update, or delete your personal information at any time through your account settings.</p>
      </section>

      <section>
        <h2>Contact Us</h2>
        <p>If you have any questions about this Privacy Policy, please contact us through our GitHub repository.</p>
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
