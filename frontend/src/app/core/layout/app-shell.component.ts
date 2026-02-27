import { Component, HostListener, OnDestroy, OnInit, signal } from '@angular/core';
import { NavigationEnd, Router, RouterLink, RouterOutlet } from '@angular/router';
import { NgIf } from '@angular/common';
import { Subscription, firstValueFrom } from 'rxjs';
import { filter } from 'rxjs/operators';
import { AuthService, AuthStatus } from '../../auth.service';
import { ThemeService } from '../services/theme.service';
import { MediaApiService } from '../services/media-api.service';
import { UserApiService, UserProfile } from '../../user-api.service';
import { I18nService, Language } from '../services/i18n.service';
import { ButtonComponent } from '../../shared/components/button.component';
import { TranslatePipe } from '../../shared/pipes/translate.pipe';
import { ToastStackComponent } from '../../shared/components/toast-stack.component';

@Component({
    selector: 'app-shell',
    standalone: true,
    imports: [RouterOutlet, RouterLink, NgIf, ButtonComponent, TranslatePipe, ToastStackComponent],
    template: `
    <div class="app-shell">
      <header class="header desktop-header glass-strong">
        <div class="header-left">
          <a routerLink="/" class="logo">
            <span class="logo-text">{{ 'app.name' | translate }}</span>
          </a>
        </div>

        <div class="header-center">
          <input
            type="search"
            class="global-search"
            [value]="globalSearchQuery()"
            [placeholder]="'publicDecks.searchPlaceholder' | translate"
            [attr.aria-label]="'publicDecks.searchPlaceholder' | translate"
            (input)="onGlobalSearchInput($event)"
            (keydown.enter)="submitGlobalSearch()"
          />
        </div>

        <div class="header-right">
          <app-button
            variant="ghost"
            size="sm"
            routerLink="/my-study"
          >
            {{ 'nav.myStudy' | translate }}
          </app-button>

          <app-button
            variant="ghost"
            size="sm"
            routerLink="/create-deck"
          >
            {{ 'nav.createDeck' | translate }}
          </app-button>

          <div *ngIf="auth.status() === 'authenticated'; else loginBlock" class="user-menu">
            <button class="user-menu-trigger" type="button" (click)="toggleUserMenu()" [attr.aria-expanded]="userMenuOpen()">
              <img *ngIf="userProfile()?.avatarUrl" [src]="userProfile()!.avatarUrl" [alt]="userProfile()!.username" class="user-avatar" />
              <div *ngIf="!userProfile()?.avatarUrl" class="user-initials">
                {{ getUserInitials() }}
              </div>
              <span class="user-name">{{ userProfile()?.username || auth.user()?.email }}</span>
            </button>

            <div *ngIf="userMenuOpen()" class="user-menu-dropdown">
              <a routerLink="/profile" class="menu-item" (click)="closeUserMenu()">
                <svg width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24">
                  <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/>
                  <circle cx="12" cy="7" r="4"/>
                </svg>
                {{ 'nav.profile' | translate }}
              </a>
              <a routerLink="/settings" class="menu-item" (click)="closeUserMenu()">
                <svg width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24">
                  <circle cx="12" cy="12" r="3"/>
                  <path d="M12 1v6m0 6v6M5.64 5.64l4.24 4.24m4.24 4.24l4.24 4.24M1 12h6m6 0h6M5.64 18.36l4.24-4.24m4.24-4.24l4.24-4.24"/>
                </svg>
                {{ 'nav.settings' | translate }}
              </a>
              <button class="menu-item" type="button" (click)="logout()">
                <svg width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24">
                  <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/>
                  <polyline points="16 17 21 12 16 7"/>
                  <line x1="21" y1="12" x2="9" y2="12"/>
                </svg>
                {{ 'nav.logout' | translate }}
              </button>
            </div>
          </div>

          <ng-template #loginBlock>
            <div class="language-menu">
              <button class="language-trigger" type="button" (click)="toggleLanguageMenu()" [attr.aria-expanded]="languageMenuOpen()">
                <svg class="language-icon" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6">
                  <circle cx="12" cy="12" r="9"/>
                  <path d="M3 12h18"/>
                  <path d="M12 3c2.5 2.6 2.5 15.4 0 18"/>
                  <path d="M12 3c-2.5 2.6-2.5 15.4 0 18"/>
                </svg>
                <span class="language-current">{{ currentLanguageCode() }}</span>
              </button>
              <div *ngIf="languageMenuOpen()" class="language-dropdown">
                <button class="language-option" type="button" (click)="setLanguage('en')">
                  <span class="language-code">EN</span>
                  <span class="language-name">{{ 'language.english' | translate }}</span>
                </button>
                <button class="language-option" type="button" (click)="setLanguage('ru')">
                  <span class="language-code">RU</span>
                  <span class="language-name">{{ 'language.russian' | translate }}</span>
                </button>
              </div>
            </div>
            <app-button
              variant="primary"
              size="sm"
              (click)="login()"
            >
              {{ 'nav.login' | translate }}
            </app-button>
          </ng-template>
        </div>
      </header>

      <header class="mobile-header glass-strong">
        <button
          class="mobile-icon-btn"
          type="button"
          [attr.aria-expanded]="mobileMenuOpen()"
          aria-controls="mobile-drawer"
          [attr.aria-label]="'nav.openMenu' | translate"
          (click)="toggleMobileMenu()"
        >
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <line x1="3" y1="6" x2="21" y2="6"></line>
            <line x1="3" y1="12" x2="21" y2="12"></line>
            <line x1="3" y1="18" x2="21" y2="18"></line>
          </svg>
        </button>

        <a routerLink="/" class="mobile-logo" (click)="closeMobileMenu()">
          {{ 'app.name' | translate }}
        </a>

        <button
          class="mobile-primary-link"
          type="button"
          [attr.aria-label]="auth.status() === 'authenticated' ? ('nav.studyNow' | translate) : ('nav.login' | translate)"
          (click)="onMobilePrimaryAction()"
        >
          {{ auth.status() === 'authenticated' ? ('nav.studyNow' | translate) : ('nav.login' | translate) }}
        </button>
      </header>

      <div class="mobile-menu-overlay" [class.open]="mobileMenuOpen()" (click)="closeMobileMenu()" aria-hidden="true"></div>

      <aside
        id="mobile-drawer"
        class="mobile-drawer glass-strong"
        [class.open]="mobileMenuOpen()"
        role="dialog"
        aria-modal="true"
        [attr.aria-hidden]="!mobileMenuOpen()"
      >
        <div class="mobile-drawer-head">
          <div class="mobile-drawer-brand">
            <span class="drawer-title">{{ 'app.name' | translate }}</span>
            <span class="drawer-subtitle">{{ auth.status() === 'authenticated' ? (userProfile()?.username || auth.user()?.email) : ('nav.guest' | translate) }}</span>
          </div>
          <button class="mobile-icon-btn" type="button" [attr.aria-label]="'nav.closeMenu' | translate" (click)="closeMobileMenu()">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <line x1="18" y1="6" x2="6" y2="18"></line>
              <line x1="6" y1="6" x2="18" y2="18"></line>
            </svg>
          </button>
        </div>

        <div class="mobile-search">
          <label class="mobile-search-label">{{ 'nav.searchCatalog' | translate }}</label>
          <div class="mobile-search-row">
            <input
              type="search"
              class="global-search"
              [value]="globalSearchQuery()"
              [placeholder]="'publicDecks.searchPlaceholder' | translate"
              [attr.aria-label]="'publicDecks.searchPlaceholder' | translate"
              (input)="onGlobalSearchInput($event)"
              (keydown.enter)="submitGlobalSearchAndClose()"
            />
            <button type="button" class="search-go" (click)="submitGlobalSearchAndClose()" [attr.aria-label]="'home.searchButton' | translate">↗</button>
          </div>
        </div>

        <nav class="mobile-nav" [attr.aria-label]="'nav.menu' | translate">
          <a routerLink="/" class="mobile-nav-item" (click)="navigateFromMobileMenu()">{{ 'nav.home' | translate }}</a>
          <a routerLink="/public-decks" class="mobile-nav-item" (click)="navigateFromMobileMenu()">{{ 'nav.catalog' | translate }}</a>

          <a *ngIf="auth.status() === 'authenticated'" routerLink="/my-study" class="mobile-nav-item" (click)="navigateFromMobileMenu()">{{ 'nav.myStudy' | translate }}</a>
          <a *ngIf="auth.status() === 'authenticated'" routerLink="/decks" class="mobile-nav-item" (click)="navigateFromMobileMenu()">{{ 'nav.decks' | translate }}</a>
          <a *ngIf="auth.status() === 'authenticated'" routerLink="/create-deck" class="mobile-nav-item" (click)="navigateFromMobileMenu()">{{ 'nav.createDeck' | translate }}</a>
          <a *ngIf="auth.status() === 'authenticated'" routerLink="/templates" class="mobile-nav-item" (click)="navigateFromMobileMenu()">{{ 'nav.templates' | translate }}</a>
          <a *ngIf="auth.status() === 'authenticated'" routerLink="/profile" class="mobile-nav-item" (click)="navigateFromMobileMenu()">{{ 'nav.profile' | translate }}</a>
          <a *ngIf="auth.status() === 'authenticated'" routerLink="/settings" class="mobile-nav-item" (click)="navigateFromMobileMenu()">{{ 'nav.settings' | translate }}</a>

          <a routerLink="/privacy" class="mobile-nav-item" (click)="navigateFromMobileMenu()">{{ 'nav.privacy' | translate }}</a>
          <a routerLink="/terms" class="mobile-nav-item" (click)="navigateFromMobileMenu()">{{ 'nav.terms' | translate }}</a>
        </nav>

        <div class="mobile-drawer-footer">
          <div class="mobile-preferences">
            <div class="mobile-preference-row">
              <span class="mobile-preference-title">{{ 'settings.language' | translate }}</span>
              <div class="mobile-preference-controls">
                <button class="language-chip" type="button" [class.active]="i18n.currentLanguage === 'ru'" (click)="setLanguage('ru')">RU</button>
                <button class="language-chip" type="button" [class.active]="i18n.currentLanguage === 'en'" (click)="setLanguage('en')">EN</button>
              </div>
            </div>
            <div class="mobile-preference-row">
              <span class="mobile-preference-title">{{ 'settings.theme' | translate }}</span>
              <div class="mobile-preference-controls">
                <button
                  class="theme-mode-chip"
                  type="button"
                  [class.active]="themeService.mode() === 'light'"
                  [attr.aria-label]="'theme.light' | translate"
                  [attr.title]="'theme.light' | translate"
                  (click)="themeService.setMode('light')"
                >
                  ☀
                </button>
                <button
                  class="theme-mode-chip"
                  type="button"
                  [class.active]="themeService.mode() === 'dark'"
                  [attr.aria-label]="'theme.dark' | translate"
                  [attr.title]="'theme.dark' | translate"
                  (click)="themeService.setMode('dark')"
                >
                  ☾
                </button>
                <button
                  class="accent-chip"
                  type="button"
                  [class.active]="themeService.accent() === 'neo'"
                  (click)="themeService.setAccent('neo')"
                >
                  {{ 'theme.neo' | translate }}
                </button>
                <button
                  class="accent-chip"
                  type="button"
                  [class.active]="themeService.accent() === 'vintage'"
                  (click)="themeService.setAccent('vintage')"
                >
                  {{ 'theme.vintage' | translate }}
                </button>
              </div>
            </div>
          </div>

          <app-button
            *ngIf="auth.status() === 'authenticated'; else mobileLoginAction"
            variant="ghost"
            tone="danger"
            [fullWidth]="true"
            (click)="logoutFromMobileMenu()"
          >
            {{ 'nav.logout' | translate }}
          </app-button>

          <ng-template #mobileLoginAction>
            <app-button variant="primary" [fullWidth]="true" (click)="loginFromMobileMenu()">
              {{ 'nav.login' | translate }}
            </app-button>
          </ng-template>
        </div>
      </aside>

      <main class="main">
        <router-outlet></router-outlet>
      </main>

      <footer class="footer glass-strong">
        <div class="footer-left">
          <span class="footer-copyright">© Mnema, {{ currentYear }}</span>
        </div>

        <div class="footer-right">
          <a href="https://github.com/MattoYuzuru/Mnema" target="_blank" rel="noopener noreferrer" class="footer-link">
            {{ 'footer.github' | translate }}
          </a>
          <a href="https://github.com/MattoYuzuru/Mnema/wiki" target="_blank" rel="noopener noreferrer" class="footer-link">{{ 'footer.docs' | translate }}</a>
          <a routerLink="/privacy" class="footer-link">{{ 'nav.privacy' | translate }}</a>
          <a routerLink="/terms" class="footer-link">{{ 'nav.terms' | translate }}</a>
        </div>
      </footer>

      <app-toast-stack></app-toast-stack>
    </div>
  `,
    styles: [
        `
      .app-shell {
        min-height: 100vh;
        display: flex;
        flex-direction: column;
      }

      .header {
        display: flex;
        align-items: center;
        gap: var(--spacing-lg);
        padding: var(--spacing-md) var(--spacing-xl);
        background: var(--glass-surface-strong);
        border-bottom: 1px solid var(--glass-border);
        backdrop-filter: blur(calc(var(--glass-blur) + 6px));
        position: sticky;
        top: 0;
        z-index: 100;
        box-shadow: var(--shadow-sm);
        overflow: visible;
      }

      .desktop-header {
        display: flex;
      }

      .mobile-header {
        display: none;
      }

      .header-left {
        flex-shrink: 0;
      }

      .logo {
        display: flex;
        align-items: center;
        text-decoration: none;
        font-size: 1.3rem;
        font-weight: 700;
        color: var(--color-text-primary);
        letter-spacing: -0.02em;
      }

      .logo-text {
        background: linear-gradient(120deg, var(--color-text-primary), var(--color-primary-accent));
        -webkit-background-clip: text;
        background-clip: text;
        color: transparent;
      }

      .header-center {
        flex: 1;
        max-width: 32rem;
      }

      .global-search {
        width: 100%;
        padding: 0.75rem 1.1rem;
        border: 1px solid var(--glass-border-strong);
        border-radius: var(--border-radius-full);
        font-size: 0.95rem;
        font-family: inherit;
        background: var(--glass-surface-strong);
        color: var(--color-text-primary);
        transition: all 0.25s ease;
        backdrop-filter: blur(var(--glass-blur));
        box-shadow: inset 0 0 0 1px rgba(255, 255, 255, 0.35), 0 10px 24px rgba(15, 23, 42, 0.08);
      }

      .global-search:focus {
        outline: none;
        border-color: rgba(14, 165, 233, 0.6);
        box-shadow: var(--focus-ring);
      }

      .header-right {
        display: flex;
        align-items: center;
        gap: var(--spacing-sm);
      }

      .language-menu {
        position: relative;
      }

      .language-trigger {
        display: inline-flex;
        align-items: center;
        gap: var(--spacing-xs);
        padding: 0.45rem 0.75rem;
        border: 1px solid var(--glass-border);
        border-radius: var(--border-radius-full);
        background: var(--glass-surface);
        color: var(--color-text-primary);
        cursor: pointer;
        transition: all 0.2s ease;
        backdrop-filter: blur(var(--glass-blur));
      }

      .language-trigger:hover {
        border-color: var(--border-color-hover);
        background: var(--glass-surface-strong);
      }

      .language-current {
        font-size: 0.85rem;
        font-weight: 600;
        letter-spacing: 0.04em;
      }

      .language-dropdown {
        position: absolute;
        top: calc(100% + var(--spacing-xs));
        right: 0;
        min-width: 160px;
        background: var(--glass-surface-strong);
        border: 1px solid var(--glass-border);
        border-radius: var(--border-radius-md);
        box-shadow: var(--shadow-md);
        padding: var(--spacing-xs);
        display: flex;
        flex-direction: column;
        gap: var(--spacing-xs);
        backdrop-filter: blur(var(--glass-blur));
        z-index: 10;
      }

      .language-option {
        display: flex;
        align-items: center;
        gap: var(--spacing-sm);
        padding: var(--spacing-sm) var(--spacing-md);
        border: none;
        border-radius: var(--border-radius-sm);
        background: transparent;
        color: var(--color-text-primary);
        font-size: 0.9rem;
        cursor: pointer;
        transition: background 0.2s ease;
        font-family: inherit;
      }

      .language-option:hover {
        background: var(--glass-surface);
      }

      .language-code {
        min-width: 2rem;
        font-weight: 600;
        color: var(--color-text-secondary);
      }

      .language-name {
        color: var(--color-text-primary);
      }

      .user-menu {
        position: relative;
      }

      .user-menu-trigger {
        display: flex;
        align-items: center;
        gap: var(--spacing-sm);
        padding: 0.4rem 0.75rem;
        border: 1px solid var(--glass-border);
        border-radius: var(--border-radius-full);
        background: var(--glass-surface);
        color: var(--color-text-primary);
        font-size: 0.9rem;
        cursor: pointer;
        transition: all 0.2s ease;
        backdrop-filter: blur(var(--glass-blur));
      }

      .user-menu-trigger:hover {
        border-color: var(--border-color-hover);
      }

      .user-avatar {
        width: 28px;
        height: 28px;
        border-radius: 50%;
        object-fit: cover;
      }

      .user-initials {
        width: 28px;
        height: 28px;
        border-radius: 50%;
        background: linear-gradient(135deg, var(--color-primary-accent), var(--color-secondary-accent));
        color: #fff;
        display: flex;
        align-items: center;
        justify-content: center;
        font-size: 0.75rem;
        font-weight: 600;
      }

      .user-name {
        max-width: 120px;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }

      .user-menu-dropdown {
        position: absolute;
        top: calc(100% + var(--spacing-xs));
        right: 0;
        min-width: 180px;
        background: var(--glass-surface-strong);
        border: 1px solid var(--glass-border);
        border-radius: var(--border-radius-md);
        box-shadow: var(--shadow-md);
        padding: var(--spacing-xs);
        display: flex;
        flex-direction: column;
        gap: var(--spacing-xs);
        backdrop-filter: blur(var(--glass-blur));
      }

      .menu-item {
        display: flex;
        align-items: center;
        gap: var(--spacing-sm);
        padding: var(--spacing-sm) var(--spacing-md);
        border: none;
        border-radius: var(--border-radius-sm);
        background: transparent;
        color: var(--color-text-primary);
        font-size: 0.9rem;
        text-decoration: none;
        cursor: pointer;
        transition: background 0.2s ease;
        width: 100%;
        text-align: left;
        font-family: inherit;
      }

      .menu-item:hover {
        background: var(--glass-surface);
      }

      .menu-item svg {
        flex-shrink: 0;
      }

      .mobile-menu-overlay {
        position: fixed;
        inset: 0;
        background: rgba(9, 13, 24, 0.45);
        z-index: 120;
        opacity: 0;
        pointer-events: none;
        transition: opacity 0.24s ease;
      }

      .mobile-menu-overlay.open {
        opacity: 1;
        pointer-events: auto;
      }

      .mobile-drawer {
        position: fixed;
        top: 0;
        left: 0;
        height: 100dvh;
        width: min(86vw, 22rem);
        max-width: 22rem;
        z-index: 130;
        padding: calc(var(--spacing-md) + env(safe-area-inset-top, 0px)) var(--spacing-md) calc(var(--spacing-lg) + env(safe-area-inset-bottom, 0px));
        border-right: 1px solid var(--glass-border);
        transform: translateX(-106%);
        transition: transform 0.25s ease;
        display: flex;
        flex-direction: column;
        gap: var(--spacing-md);
        pointer-events: none;
      }

      .mobile-drawer.open {
        transform: translateX(0);
        pointer-events: auto;
      }

      .mobile-drawer-head {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: var(--spacing-sm);
      }

      .mobile-drawer-brand {
        display: flex;
        flex-direction: column;
        min-width: 0;
      }

      .drawer-title {
        font-family: var(--font-display);
        font-weight: 700;
        font-size: 1.2rem;
        letter-spacing: -0.02em;
      }

      .drawer-subtitle {
        color: var(--color-text-muted);
        font-size: 0.82rem;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }

      .mobile-icon-btn {
        width: 2.75rem;
        height: 2.75rem;
        padding: 0;
        border: 1px solid var(--glass-border);
        border-radius: 50%;
        background: var(--glass-surface);
        color: var(--color-text-primary);
        display: inline-flex;
        align-items: center;
        justify-content: center;
        cursor: pointer;
      }

      .mobile-search {
        display: flex;
        flex-direction: column;
        gap: var(--spacing-xs);
      }

      .mobile-search-label {
        font-size: 0.85rem;
        color: var(--color-text-secondary);
      }

      .mobile-search-row {
        display: grid;
        grid-template-columns: 1fr auto;
        gap: var(--spacing-xs);
      }

      .mobile-search-row .global-search {
        min-height: 2.75rem;
        height: 2.75rem;
        padding-top: 0;
        padding-bottom: 0;
      }

      .search-go {
        border: 1px solid var(--glass-border-strong);
        background: var(--glass-surface);
        color: var(--color-text-primary);
        border-radius: var(--border-radius-full);
        width: 2.75rem;
        height: 2.75rem;
        padding: 0;
        display: inline-flex;
        align-items: center;
        justify-content: center;
        font-size: 1rem;
        line-height: 1;
        cursor: pointer;
      }

      .mobile-nav {
        display: flex;
        flex-direction: column;
        gap: 0.2rem;
        overflow: auto;
      }

      .mobile-nav-item {
        min-height: 2.75rem;
        display: flex;
        align-items: center;
        padding: 0.6rem 0.75rem;
        border-radius: var(--border-radius-md);
        border: 1px solid transparent;
        color: var(--color-text-primary);
        text-decoration: none;
        font-weight: 600;
      }

      .mobile-nav-item:hover,
      .mobile-nav-item:focus-visible {
        background: var(--glass-surface);
        border-color: var(--glass-border);
      }

      .mobile-drawer-footer {
        margin-top: auto;
        display: flex;
        flex-direction: column;
        gap: var(--spacing-sm);
      }

      .mobile-preferences {
        display: flex;
        flex-direction: column;
        gap: 0.2rem;
      }

      .mobile-preference-row {
        display: grid;
        grid-template-columns: auto minmax(0, 1fr);
        gap: var(--spacing-sm);
        align-items: center;
        min-height: 2.75rem;
        padding: 0.6rem 0.75rem;
      }

      .mobile-preference-title {
        color: var(--color-text-primary);
        font-size: inherit;
        font-weight: 600;
      }

      .mobile-preference-controls {
        display: inline-flex;
        justify-content: flex-end;
        gap: var(--spacing-xs);
        flex-wrap: nowrap;
        min-width: 0;
        overflow-x: auto;
        scrollbar-width: none;
      }

      .mobile-preference-controls::-webkit-scrollbar {
        display: none;
      }

      .language-chip {
        min-width: 2.25rem;
        height: 2rem;
        padding: 0 0.55rem;
        border: 1px solid var(--glass-border);
        border-radius: var(--border-radius-full);
        background: var(--glass-surface);
        color: var(--color-text-secondary);
        cursor: pointer;
        font-weight: 700;
        flex: 0 0 auto;
      }

      .language-chip.active {
        border-color: var(--color-primary-accent);
        color: var(--color-primary-accent);
        background: rgba(14, 165, 233, 0.12);
      }

      .theme-mode-chip {
        width: 2rem;
        height: 2rem;
        border: 1px solid var(--glass-border);
        border-radius: 50%;
        background: var(--glass-surface);
        color: var(--color-text-secondary);
        font-size: 1rem;
        cursor: pointer;
        flex: 0 0 auto;
      }

      .theme-mode-chip.active {
        border-color: var(--color-primary-accent);
        color: var(--color-primary-accent);
        background: rgba(14, 165, 233, 0.12);
      }

      .accent-chip {
        min-width: 3.55rem;
        height: 2rem;
        padding: 0 0.65rem;
        border: 1px solid var(--glass-border);
        border-radius: var(--border-radius-full);
        background: var(--glass-surface);
        color: var(--color-text-secondary);
        font-size: 0.8rem;
        font-weight: 700;
        cursor: pointer;
        flex: 0 0 auto;
      }

      .accent-chip.active {
        border-color: var(--color-primary-accent);
        color: var(--color-primary-accent);
        background: rgba(14, 165, 233, 0.12);
      }

      .mobile-logo {
        text-decoration: none;
        font-family: var(--font-display);
        font-weight: 700;
        letter-spacing: -0.02em;
        color: var(--color-text-primary);
      }

      .mobile-primary-link {
        min-height: 2.5rem;
        padding: 0 0.85rem;
        border: 1px solid var(--glass-border);
        border-radius: var(--border-radius-full);
        background: var(--glass-surface);
        color: var(--color-text-primary);
        font-size: 0.82rem;
        font-weight: 700;
        letter-spacing: 0.01em;
        cursor: pointer;
        white-space: nowrap;
      }

      .main {
        flex: 1;
        padding: var(--spacing-xl);
      }

      .footer {
        display: flex;
        align-items: center;
        justify-content: space-between;
        padding: var(--spacing-lg) var(--spacing-xl);
        background: var(--glass-surface-strong);
        border-top: 1px solid var(--glass-border);
        backdrop-filter: blur(var(--glass-blur));
      }

      .footer-copyright {
        font-size: 0.9rem;
        color: var(--color-text-muted);
      }

      .footer-right {
        display: flex;
        gap: var(--spacing-lg);
      }

      .footer-link {
        font-size: 0.9rem;
        color: var(--color-text-muted);
        text-decoration: none;
        transition: color 0.2s ease;
      }

      .footer-link:hover {
        color: var(--color-text-primary);
      }

      @media (min-width: 901px) {
        .mobile-menu-overlay,
        .mobile-drawer {
          display: none;
        }
      }

      @media (max-width: 900px) {
        .desktop-header {
          display: none;
        }

        .mobile-header {
          position: sticky;
          top: 0;
          z-index: 110;
          display: grid;
          grid-template-columns: auto 1fr auto;
          align-items: center;
          gap: var(--spacing-sm);
          padding: calc(var(--spacing-xs) + env(safe-area-inset-top, 0px)) var(--spacing-sm) var(--spacing-xs);
          border-bottom: 1px solid var(--glass-border);
        }

        .main {
          padding: var(--spacing-lg) var(--spacing-md);
        }

        .footer {
          flex-direction: column;
          gap: var(--spacing-sm);
          text-align: center;
        }

        .footer-left, .footer-right {
          justify-content: center;
        }

        .footer-right {
          flex-wrap: wrap;
          gap: var(--spacing-md);
        }
      }

      @media (max-width: 480px) {
        .main {
          padding: var(--spacing-md) var(--spacing-sm);
        }

        .mobile-drawer {
          width: min(92vw, 22rem);
          padding-left: var(--spacing-sm);
          padding-right: var(--spacing-sm);
        }

        .mobile-primary-link {
          font-size: 0.78rem;
          padding: 0 0.7rem;
        }
      }

      @media (prefers-reduced-motion: reduce) {
        .mobile-menu-overlay,
        .mobile-drawer {
          transition: none;
        }
      }
    `
    ]
})
export class AppShellComponent implements OnInit, OnDestroy {
    currentYear = new Date().getFullYear();
    userMenuOpen = signal(false);
    languageMenuOpen = signal(false);
    mobileMenuOpen = signal(false);
    userProfile = signal<UserProfile | null>(null);
    globalSearchQuery = signal('');
    private authSubscription?: Subscription;
    private profileSubscription?: Subscription;
    private routerSubscription?: Subscription;

    constructor(
        public auth: AuthService,
        public themeService: ThemeService,
        private router: Router,
        private userApi: UserApiService,
        private mediaApi: MediaApiService,
        public i18n: I18nService
    ) {}

    ngOnInit(): void {
        this.auth.initFromUrlAndStorage();
        this.themeService.mode();

        if (this.auth.status() === 'authenticated') {
            this.loadUserProfile();
        }

        this.authSubscription = this.auth.status$.subscribe((status: AuthStatus) => {
            if (status === 'authenticated') {
                if (!this.userProfile()) {
                    this.loadUserProfile();
                }
            } else {
                this.userProfile.set(null);
            }
        });

        this.profileSubscription = this.userApi.profile$.subscribe(profile => {
            if (!profile) {
                this.userProfile.set(null);
                return;
            }
            void this.applyUserProfile(profile);
        });

        this.syncGlobalSearchFromUrl();
        this.routerSubscription = this.router.events
            .pipe(filter(event => event instanceof NavigationEnd))
            .subscribe(() => {
                this.syncGlobalSearchFromUrl();
                this.closeAllMenus();
            });
    }

    ngOnDestroy(): void {
        this.authSubscription?.unsubscribe();
        this.profileSubscription?.unsubscribe();
        this.routerSubscription?.unsubscribe();
        document.body.style.overflow = '';
    }

    @HostListener('document:keydown.escape')
    handleEscape(): void {
        this.closeAllMenus();
    }

    @HostListener('document:click', ['$event'])
    handleDocumentClick(event: MouseEvent): void {
        const target = event.target as HTMLElement;
        if (!target.closest('.user-menu')) {
            this.closeUserMenu();
        }
        if (!target.closest('.language-menu')) {
            this.languageMenuOpen.set(false);
        }
    }

    loadUserProfile(): void {
        this.userApi.getMe().subscribe({
            next: async profile => {
                await this.applyUserProfile(profile);
            },
            error: err => {
                console.error('Failed to load user profile', err);
            }
        });
    }

    private async applyUserProfile(profile: UserProfile): Promise<void> {
        if (profile.avatarMediaId) {
            try {
                const resolved = await firstValueFrom(this.mediaApi.resolve([profile.avatarMediaId]));
                if (resolved[0]?.url) {
                    profile = { ...profile, avatarUrl: resolved[0].url };
                }
            } catch (err) {
                console.error('Failed to resolve avatar media', err);
            }
        }
        this.userProfile.set(profile);
    }

    getUserInitials(): string {
        const profile = this.userProfile();
        if (profile?.username) {
            return profile.username.charAt(0).toUpperCase();
        }
        const email = this.auth.user()?.email;
        return email ? email.charAt(0).toUpperCase() : '?';
    }

    toggleUserMenu(): void {
        this.userMenuOpen.set(!this.userMenuOpen());
        this.languageMenuOpen.set(false);
    }

    closeUserMenu(): void {
        this.userMenuOpen.set(false);
    }

    toggleLanguageMenu(): void {
        this.languageMenuOpen.set(!this.languageMenuOpen());
        this.userMenuOpen.set(false);
    }

    setLanguage(lang: Language): void {
        this.i18n.setLanguage(lang);
        this.languageMenuOpen.set(false);
    }

    currentLanguageCode(): string {
        return this.i18n.currentLanguage === 'ru' ? 'RU' : 'EN';
    }

    login(): void {
        void this.router.navigate(['/login']);
    }

    logout(): void {
        this.closeUserMenu();
        this.auth.logout();
        void this.router.navigate(['/']);
    }

    loginFromMobileMenu(): void {
        this.closeMobileMenu();
        void this.router.navigate(['/login']);
    }

    logoutFromMobileMenu(): void {
        this.closeMobileMenu();
        this.auth.logout();
        void this.router.navigate(['/']);
    }

    onMobilePrimaryAction(): void {
        if (this.auth.status() === 'authenticated') {
            void this.router.navigate(['/my-study']);
            return;
        }
        void this.router.navigate(['/login']);
    }

    toggleMobileMenu(): void {
        this.setMobileMenuState(!this.mobileMenuOpen());
    }

    closeMobileMenu(): void {
        this.setMobileMenuState(false);
    }

    navigateFromMobileMenu(): void {
        this.closeMobileMenu();
    }

    onGlobalSearchInput(event: Event): void {
        const input = event.target as HTMLInputElement;
        this.globalSearchQuery.set(input.value);
    }

    submitGlobalSearch(): void {
        const query = this.globalSearchQuery().trim();
        const queryParams = query ? { q: query } : {};
        void this.router.navigate(['/public-decks'], { queryParams });
    }

    submitGlobalSearchAndClose(): void {
        this.submitGlobalSearch();
        this.closeMobileMenu();
    }

    private syncGlobalSearchFromUrl(): void {
        const tree = this.router.parseUrl(this.router.url);
        const primary = tree.root.children['primary'];
        const firstSegment = primary?.segments?.[0]?.path;
        if (firstSegment === 'public-decks') {
            this.globalSearchQuery.set(tree.queryParams['q'] || '');
        } else {
            this.globalSearchQuery.set('');
        }
    }

    private setMobileMenuState(open: boolean): void {
        this.mobileMenuOpen.set(open);
        document.body.style.overflow = open ? 'hidden' : '';
        if (open) {
            this.userMenuOpen.set(false);
            this.languageMenuOpen.set(false);
        }
    }

    private closeAllMenus(): void {
        this.closeUserMenu();
        this.languageMenuOpen.set(false);
        this.closeMobileMenu();
    }
}
