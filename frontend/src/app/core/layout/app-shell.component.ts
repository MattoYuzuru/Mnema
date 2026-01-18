import { Component, OnInit, OnDestroy, signal } from '@angular/core';
import { NavigationEnd, Router, RouterOutlet, RouterLink } from '@angular/router';
import { NgIf } from '@angular/common';
import { Subscription, firstValueFrom } from 'rxjs';
import { filter } from 'rxjs/operators';
import { AuthService, AuthStatus } from '../../auth.service';
import { ThemeService } from '../services/theme.service';
import { MediaApiService } from '../services/media-api.service';
import { UserApiService, UserProfile } from '../../user-api.service';
import { ButtonComponent } from '../../shared/components/button.component';
import { TranslatePipe } from '../../shared/pipes/translate.pipe';

@Component({
    selector: 'app-shell',
    standalone: true,
    imports: [RouterOutlet, RouterLink, NgIf, ButtonComponent, TranslatePipe],
    template: `
    <div class="app-shell">
      <header class="header glass-strong">
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

      @media (max-width: 768px) {
        .header {
          padding: var(--spacing-sm) var(--spacing-md);
          gap: var(--spacing-sm);
        }

        .header-center {
          display: none;
        }

        .header-right {
          flex-wrap: wrap;
          justify-content: flex-end;
          row-gap: var(--spacing-xs);
        }

        .user-name {
          display: none;
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
        .header {
          padding: var(--spacing-xs) var(--spacing-sm);
        }

        .logo {
          font-size: 1rem;
        }

        .logo-image {
          height: 1.5rem;
        }

        .main {
          padding: var(--spacing-md) var(--spacing-sm);
        }
      }
    `
    ]
})
export class AppShellComponent implements OnInit, OnDestroy {
    currentYear = new Date().getFullYear();
    userMenuOpen = signal(false);
    userProfile = signal<UserProfile | null>(null);
    globalSearchQuery = signal('');
    private authSubscription?: Subscription;
    private profileSubscription?: Subscription;
    private routerSubscription?: Subscription;

    constructor(
        public auth: AuthService,
        private themeService: ThemeService,
        private router: Router,
        private userApi: UserApiService,
        private mediaApi: MediaApiService
    ) {}

    ngOnInit(): void {
        this.auth.initFromUrlAndStorage();

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
            .subscribe(() => this.syncGlobalSearchFromUrl());
    }

    ngOnDestroy(): void {
        this.authSubscription?.unsubscribe();
        this.profileSubscription?.unsubscribe();
        this.routerSubscription?.unsubscribe();
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
    }

    closeUserMenu(): void {
        this.userMenuOpen.set(false);
    }

    login(): void {
        void this.router.navigate(['/login']);
    }

    logout(): void {
        this.closeUserMenu();
        this.auth.logout();
        void this.router.navigate(['/']);
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
}
