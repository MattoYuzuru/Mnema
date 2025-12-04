import { Component, OnInit, signal } from '@angular/core';
import { Router, RouterOutlet, RouterLink } from '@angular/router';
import { NgIf } from '@angular/common';
import { AuthService } from '../../auth.service';
import { ThemeService } from '../services/theme.service';
import { UserApiService, UserProfile } from '../../user-api.service';
import { ButtonComponent } from '../../shared/components/button.component';

@Component({
    selector: 'app-shell',
    standalone: true,
    imports: [RouterOutlet, RouterLink, NgIf, ButtonComponent],
    template: `
    <div class="app-shell">
      <header class="header">
        <div class="header-left">
          <a routerLink="/" class="logo">
            <img [src]="logoUrl" alt="Mnema" class="logo-image" />
          </a>
        </div>

        <div class="header-center">
          <input
            type="search"
            class="global-search"
            placeholder="Search public decks..."
          />
        </div>

        <div class="header-right">
          <app-button
            variant="ghost"
            size="sm"
            routerLink="/decks"
          >
            My Study
          </app-button>

          <app-button
            variant="ghost"
            size="sm"
            routerLink="/create-deck"
          >
            Create Deck
          </app-button>

          <div *ngIf="auth.status() === 'authenticated'; else loginBlock" class="user-menu">
            <button class="user-menu-trigger" type="button" (click)="toggleUserMenu()">
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
                Profile
              </a>
              <a routerLink="/settings" class="menu-item" (click)="closeUserMenu()">
                <svg width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24">
                  <circle cx="12" cy="12" r="3"/>
                  <path d="M12 1v6m0 6v6M5.64 5.64l4.24 4.24m4.24 4.24l4.24 4.24M1 12h6m6 0h6M5.64 18.36l4.24-4.24m4.24-4.24l4.24-4.24"/>
                </svg>
                Settings
              </a>
              <button class="menu-item" type="button" (click)="logout()">
                <svg width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24">
                  <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/>
                  <polyline points="16 17 21 12 16 7"/>
                  <line x1="21" y1="12" x2="9" y2="12"/>
                </svg>
                Logout
              </button>
            </div>
          </div>

          <ng-template #loginBlock>
            <app-button
              variant="primary"
              size="sm"
              (click)="login()"
            >
              Login
            </app-button>
          </ng-template>
        </div>
      </header>

      <main class="main">
        <router-outlet></router-outlet>
      </main>

      <footer class="footer">
        <div class="footer-left">
          <span class="footer-copyright">© Mnema, {{ currentYear }}</span>
        </div>

        <div class="footer-right">
          <a href="https://github.com" target="_blank" rel="noopener" class="footer-link">
            GitHub
          </a>
          <a href="#" class="footer-link">Docs</a>
          <a href="#" class="footer-link">Privacy</a>
          <a href="#" class="footer-link">Terms</a>
        </div>
      </footer>
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
        background: var(--color-card-background);
        border-bottom: 1px solid var(--border-color);
        position: sticky;
        top: 0;
        z-index: 100;
      }

      .header-left {
        flex-shrink: 0;
      }

      .logo {
        display: flex;
        align-items: center;
        text-decoration: none;
        font-size: 1.25rem;
        font-weight: 700;
        color: var(--color-text-primary);
      }

      .logo-image {
        height: 2rem;
        width: auto;
      }

      .header-center {
        flex: 1;
        max-width: 32rem;
      }

      .global-search {
        width: 100%;
        padding: var(--spacing-sm) var(--spacing-md);
        border: 1px solid var(--border-color);
        border-radius: var(--border-radius-full);
        font-size: 0.9rem;
        font-family: inherit;
        background: var(--color-background);
        color: var(--color-text-primary);
        transition: all 0.2s ease;
      }

      .global-search:focus {
        outline: none;
        border-color: var(--color-primary-accent);
      }

      .header-right {
        display: flex;
        align-items: center;
        gap: var(--spacing-sm);
      }

      .user-menu {
        position: relative;
      }

      .user-menu-trigger {
        display: flex;
        align-items: center;
        gap: var(--spacing-sm);
        padding: var(--spacing-xs) var(--spacing-sm);
        border: 1px solid var(--border-color);
        border-radius: var(--border-radius-full);
        background: transparent;
        color: var(--color-text-primary);
        font-size: 0.9rem;
        cursor: pointer;
        transition: all 0.2s ease;
      }

      .user-menu-trigger:hover {
        background: var(--color-background);
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
        background: #111827;
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
        background: var(--color-card-background);
        border: 1px solid var(--border-color);
        border-radius: var(--border-radius-md);
        box-shadow: 0 4px 12px rgba(0, 0, 0, 0.1);
        padding: var(--spacing-xs);
        display: flex;
        flex-direction: column;
        gap: var(--spacing-xs);
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
        background: var(--color-background);
      }

      .menu-item svg {
        flex-shrink: 0;
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
        background: var(--color-card-background);
        border-top: 1px solid var(--border-color);
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
    `
    ]
})
export class AppShellComponent implements OnInit {
    currentYear = new Date().getFullYear();
    userMenuOpen = signal(false);
    userProfile = signal<UserProfile | null>(null);

    constructor(
        public auth: AuthService,
        private themeService: ThemeService,
        private router: Router,
        private userApi: UserApiService
    ) {}

    ngOnInit(): void {
        this.auth.initFromUrlAndStorage();

        if (this.auth.status() === 'authenticated') {
            this.loadUserProfile();
        }
    }

    get logoUrl(): string {
        const theme = this.themeService.getCurrentTheme();
        return theme?.assets.logoUrl || 'assets/logo-neo.svg';
    }

    loadUserProfile(): void {
        this.userApi.getMe().subscribe({
            next: profile => {
                this.userProfile.set(profile);
            },
            error: err => {
                console.error('Failed to load user profile', err);
            }
        });
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
    }

    closeUserMenu(): void {
        this.userMenuOpen.set(false);
    }

    login(): void {
        this.auth.beginLogin();
    }

    logout(): void {
        this.closeUserMenu();
        this.auth.logout();
        void this.router.navigate(['/']);
    }
}
