// src/app/app.component.ts
import { Component, OnInit } from '@angular/core';
import { RouterOutlet, RouterLink } from '@angular/router';
import { NgIf } from '@angular/common';
import { AuthService } from './auth.service';

@Component({
    selector: 'app-root',
    standalone: true,
    imports: [RouterOutlet, RouterLink, NgIf],
    template: `
    <div class="app-root">
      <header class="header">
        <a routerLink="/" class="logo">Mnema</a>

        <nav class="nav">
          <a routerLink="/" class="nav-link">Главная</a>
          <a
            *ngIf="auth.status() === 'authenticated'"
            routerLink="/profile"
            class="nav-link"
          >
            Профиль
          </a>
        </nav>

        <div class="auth">
          <ng-container *ngIf="auth.status() === 'authenticated'; else loginBlock">
            <button class="btn ghost" routerLink="/profile">
              {{ auth.user()?.name || auth.user()?.email }}
            </button>
            <button class="btn ghost" type="button" (click)="logout()">Выйти</button>
          </ng-container>
          <ng-template #loginBlock>
            <button class="btn primary" routerLink="/login">Войти</button>
          </ng-template>
        </div>
      </header>

      <main class="main">
        <router-outlet></router-outlet>
      </main>
    </div>
  `,
    styles: [
        `
      .app-root {
        font-family: system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
        min-height: 100vh;
        display: flex;
        flex-direction: column;
      }

      .header {
        display: flex;
        align-items: center;
        justify-content: space-between;
        padding: 1rem 2rem;
        border-bottom: 1px solid #e5e7eb;
      }

      .logo {
        font-weight: 700;
        text-decoration: none;
        color: inherit;
      }

      .nav {
        display: flex;
        gap: 1rem;
      }

      .nav-link {
        text-decoration: none;
        color: #4b5563;
      }

      .nav-link:hover {
        color: #111827;
      }

      .auth {
        display: flex;
        gap: 0.5rem;
      }

      .btn {
        border-radius: 999px;
        padding: 0.4rem 0.9rem;
        font-size: 0.9rem;
        border: 1px solid transparent;
        cursor: pointer;
        background: transparent;
      }

      .btn.primary {
        background: #111827;
        color: #fff;
      }

      .btn.primary:hover {
        background: #000;
      }

      .btn.ghost {
        border-color: #d1d5db;
      }

      .btn.ghost:hover {
        background: #f3f4f6;
      }

      .main {
        flex: 1;
        padding: 2rem;
      }
    `
    ]
})
export class AppComponent implements OnInit {
    constructor(public auth: AuthService) {}

    ngOnInit(): void {
        this.auth.initFromUrlAndStorage();
    }

    logout(): void {
        this.auth.logout();
    }
}
