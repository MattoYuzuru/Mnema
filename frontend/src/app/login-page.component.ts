import { Component } from '@angular/core';
import { NgIf } from '@angular/common';
import { RouterLink } from '@angular/router';
import { AuthService } from './auth.service';

@Component({
    standalone: true,
    selector: 'app-login-page',
    imports: [NgIf, RouterLink],
    template: `
    <section>
      <h2>Вход</h2>

      <p *ngIf="auth.status() === 'authenticated'">
        Вы уже вошли. <a routerLink="/profile">Перейти в профиль</a>
      </p>

      <button
        *ngIf="auth.status() !== 'authenticated'"
        class="btn primary"
        type="button"
        (click)="login()"
      >
        Войти через Google
      </button>
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
