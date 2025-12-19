import { Component } from '@angular/core';
import { NgIf } from '@angular/common';
import { RouterLink } from '@angular/router';
import { AuthService } from './auth.service';
import { TranslatePipe } from './shared/pipes/translate.pipe';

@Component({
    standalone: true,
    selector: 'app-login-page',
    imports: [NgIf, RouterLink, TranslatePipe],
    template: `
    <div class="login-page">
      <div class="login-container">
        <div class="login-header">
          <h1>{{ 'login.title' | translate }}</h1>
          <p>{{ 'login.subtitle' | translate }}</p>
        </div>

        <div *ngIf="auth.status() === 'authenticated'" class="already-auth">
          <p>{{ 'login.alreadyAuthenticated' | translate }}</p>
          <a routerLink="/profile" class="link-button">{{ 'login.goToProfile' | translate }}</a>
        </div>

        <div *ngIf="auth.status() !== 'authenticated'" class="auth-blocks">
          <div class="auth-block local-auth">
            <div class="overlay-badge">{{ 'login.plannedDevelopment' | translate }}</div>
            <h2>{{ 'login.localAuthTitle' | translate }}</h2>
            <div class="form-group">
              <label>{{ 'login.email' | translate }}</label>
              <input type="email" disabled class="form-input" />
            </div>
            <div class="form-group">
              <label>{{ 'login.username' | translate }}</label>
              <input type="text" disabled class="form-input" />
            </div>
            <div class="form-group">
              <label>{{ 'login.password' | translate }}</label>
              <input type="password" disabled class="form-input" />
            </div>
          </div>

          <div class="divider"></div>

          <div class="auth-block oauth-auth">
            <h2>{{ 'login.oauthTitle' | translate }}</h2>

            <button class="oauth-button google-button" type="button" (click)="login()">
              <svg width="18" height="18" viewBox="0 0 24 24">
                <path fill="#4285F4" d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92c-.26 1.37-1.04 2.53-2.21 3.31v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.09z"/>
                <path fill="#34A853" d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z"/>
                <path fill="#FBBC05" d="M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.07H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.93l2.85-2.22.81-.62z"/>
                <path fill="#EA4335" d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.07l3.66 2.84c.87-2.6 3.3-4.53 6.16-4.53z"/>
              </svg>
              {{ 'login.signInWithGoogle' | translate }}
            </button>

            <div class="oauth-button disabled-button">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor">
                <path d="M12 0C5.37 0 0 5.37 0 12c0 5.31 3.435 9.795 8.205 11.385.6.105.825-.255.825-.57 0-.285-.015-1.23-.015-2.235-3.015.555-3.795-.735-4.035-1.41-.135-.345-.72-1.41-1.23-1.695-.42-.225-1.02-.78-.015-.795.945-.015 1.62.87 1.845 1.23 1.08 1.815 2.805 1.305 3.495.99.105-.78.42-1.305.765-1.605-2.67-.3-5.46-1.335-5.46-5.925 0-1.305.465-2.385 1.23-3.225-.12-.3-.54-1.53.12-3.18 0 0 1.005-.315 3.3 1.23.96-.27 1.98-.405 3-.405s2.04.135 3 .405c2.295-1.56 3.3-1.23 3.3-1.23.66 1.65.24 2.88.12 3.18.765.84 1.23 1.905 1.23 3.225 0 4.605-2.805 5.625-5.475 5.925.435.375.81 1.095.81 2.22 0 1.605-.015 2.895-.015 3.3 0 .315.225.69.825.57A12.02 12.02 0 0024 12c0-6.63-5.37-12-12-12z"/>
              </svg>
              {{ 'login.github' | translate }}
              <span class="planned-badge">{{ 'login.plannedDevelopment' | translate }}</span>
            </div>

            <div class="oauth-button disabled-button">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor">
                <path d="M12 0C5.373 0 0 5.373 0 12s5.373 12 12 12 12-5.373 12-12S18.627 0 12 0zm5.894 15.764c-.272.453-.762.724-1.315.724H7.421c-.553 0-1.043-.271-1.315-.724a1.562 1.562 0 01-.179-1.448l4.579-10.832c.179-.423.601-.724 1.084-.724s.905.301 1.084.724l4.579 10.832c.15.362.15.905-.18 1.448z"/>
              </svg>
              {{ 'login.yandex' | translate }}
              <span class="planned-badge">{{ 'login.plannedDevelopment' | translate }}</span>
            </div>

            <div class="oauth-button disabled-button">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor">
                <path d="M0 0v11.408h11.408V0H0zm12.594 0v11.408H24V0H12.594zM0 12.594V24h11.408V12.594H0zm12.594 0V24H24V12.594H12.594z"/>
              </svg>
              {{ 'login.microsoft' | translate }}
              <span class="planned-badge">{{ 'login.plannedDevelopment' | translate }}</span>
            </div>
          </div>
        </div>

        <div *ngIf="auth.status() !== 'authenticated'" class="consent-section">
          <label class="consent-label">
            <input type="checkbox" checked disabled class="consent-checkbox" />
            <span class="consent-text">{{ 'login.consentText' | translate }}</span>
          </label>
        </div>
      </div>
    </div>
  `,
    styles: [
        `
      .login-page {
        min-height: calc(100vh - 200px);
        display: flex;
        align-items: center;
        justify-content: center;
        padding: var(--spacing-xl);
      }

      .login-container {
        max-width: 900px;
        width: 100%;
        background: var(--color-card-background);
        border-radius: var(--border-radius-lg);
        box-shadow: var(--shadow-lg);
        padding: var(--spacing-2xl);
      }

      .login-header {
        text-align: center;
        margin-bottom: var(--spacing-2xl);
      }

      .login-header h1 {
        font-size: 2rem;
        margin: 0 0 var(--spacing-sm) 0;
        color: var(--color-text-primary);
      }

      .login-header p {
        font-size: 1rem;
        color: var(--color-text-muted);
        margin: 0;
      }

      .already-auth {
        text-align: center;
        padding: var(--spacing-xl);
      }

      .already-auth p {
        margin: 0 0 var(--spacing-md) 0;
        font-size: 1.1rem;
      }

      .link-button {
        display: inline-block;
        padding: var(--spacing-sm) var(--spacing-lg);
        background: var(--color-text-primary);
        color: var(--color-card-background);
        text-decoration: none;
        border-radius: var(--border-radius-full);
        font-weight: 500;
        transition: opacity 0.2s;
      }

      .link-button:hover {
        opacity: 0.8;
      }

      .auth-blocks {
        display: grid;
        grid-template-columns: 1fr auto 1fr;
        gap: var(--spacing-xl);
        margin-bottom: var(--spacing-xl);
      }

      .auth-block {
        position: relative;
      }

      .auth-block h2 {
        font-size: 1.25rem;
        margin: 0 0 var(--spacing-lg) 0;
        color: var(--color-text-primary);
      }

      .local-auth {
        opacity: 0.5;
        pointer-events: none;
        filter: blur(1px);
      }

      .overlay-badge {
        position: absolute;
        top: 50%;
        left: 50%;
        transform: translate(-50%, -50%);
        background: var(--color-text-primary);
        color: var(--color-card-background);
        padding: var(--spacing-sm) var(--spacing-lg);
        border-radius: var(--border-radius-full);
        font-weight: 600;
        font-size: 0.9rem;
        z-index: 10;
        pointer-events: all;
        box-shadow: var(--shadow-lg);
      }

      .form-group {
        margin-bottom: var(--spacing-md);
      }

      .form-group label {
        display: block;
        margin-bottom: var(--spacing-xs);
        font-size: 0.9rem;
        font-weight: 500;
        color: var(--color-text-primary);
      }

      .form-input {
        width: 100%;
        padding: var(--spacing-sm) var(--spacing-md);
        border: 1px solid var(--border-color);
        border-radius: var(--border-radius-md);
        font-size: 0.95rem;
        font-family: inherit;
        background: var(--color-background);
      }

      .divider {
        width: 1px;
        background: var(--border-color);
        align-self: stretch;
      }

      .oauth-auth {
        display: flex;
        flex-direction: column;
        gap: var(--spacing-md);
      }

      .oauth-button {
        display: flex;
        align-items: center;
        gap: var(--spacing-md);
        padding: var(--spacing-md) var(--spacing-lg);
        border: 1px solid var(--border-color);
        border-radius: var(--border-radius-md);
        background: var(--color-card-background);
        font-size: 0.95rem;
        font-weight: 500;
        cursor: pointer;
        transition: all 0.2s;
        position: relative;
      }

      .oauth-button:not(.disabled-button):hover {
        background: var(--color-background);
        border-color: var(--color-primary-accent);
      }

      .google-button {
        color: var(--color-text-primary);
      }

      .disabled-button {
        opacity: 0.5;
        cursor: not-allowed;
        color: var(--color-text-muted);
      }

      .planned-badge {
        margin-left: auto;
        font-size: 0.75rem;
        padding: 2px 8px;
        background: var(--color-background);
        border-radius: var(--border-radius-sm);
        color: var(--color-text-muted);
      }

      .consent-section {
        border-top: 1px solid var(--border-color);
        padding-top: var(--spacing-lg);
      }

      .consent-label {
        display: flex;
        align-items: flex-start;
        gap: var(--spacing-sm);
        cursor: default;
      }

      .consent-checkbox {
        margin-top: 4px;
        cursor: not-allowed;
      }

      .consent-text {
        font-size: 0.85rem;
        color: var(--color-text-muted);
        line-height: 1.5;
      }

      @media (max-width: 768px) {
        .login-page {
          padding: var(--spacing-md);
        }

        .login-container {
          padding: var(--spacing-lg);
        }

        .login-header h1 {
          font-size: 1.5rem;
        }

        .auth-blocks {
          grid-template-columns: 1fr;
          gap: var(--spacing-lg);
        }

        .divider {
          display: none;
        }

        .overlay-badge {
          font-size: 0.8rem;
          padding: var(--spacing-xs) var(--spacing-md);
        }
      }

      @media (max-width: 480px) {
        .login-header h1 {
          font-size: 1.25rem;
        }

        .oauth-button {
          font-size: 0.9rem;
          padding: var(--spacing-sm) var(--spacing-md);
        }
      }
    `
    ]
})
export class LoginPageComponent {
    constructor(public auth: AuthService) {}

    login(): void {
        void this.auth.beginLogin('/profile');
    }
}
