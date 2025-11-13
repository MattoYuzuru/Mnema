// src/app/home-page.component.ts
import { Component } from '@angular/core';
import { NgIf } from '@angular/common';
import { RouterLink } from '@angular/router';
import { AuthService } from './auth.service';

@Component({
    standalone: true,
    selector: 'app-home-page',
    imports: [NgIf, RouterLink],
    template: `
    <section>
      <h1>Mnema</h1>
      <p>Главный экран приложения.</p>

      <button
        *ngIf="auth.status() !== 'authenticated'; else goProfile"
        class="btn primary"
        type="button"
        (click)="login()"
      >
        Войти через Google
      </button>

      <ng-template #goProfile>
        <a routerLink="/profile" class="btn primary">Открыть профиль</a>
      </ng-template>
    </section>
  `,
    styles: [
        `
      .btn.primary {
        border-radius: 999px;
        padding: 0.6rem 1.4rem;
        border: none;
        background: #111827;
        color: #fff;
        cursor: pointer;
        text-decoration: none;
      }

      .btn.primary:hover {
        background: #000;
      }
    `
    ]
})
export class HomePageComponent {
    constructor(public auth: AuthService) {}

    login(): void {
        void this.auth.beginLogin('/');
    }
}
