// src/app/profile-page.component.ts
import { Component, OnInit } from '@angular/core';
import { NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AuthService } from './auth.service';
import { UserApiService, UserProfile } from './user-api.service';

@Component({
    standalone: true,
    selector: 'app-profile-page',
    imports: [NgIf, FormsModule, RouterLink],
    template: `
    <section *ngIf="auth.status() === 'authenticated'; else notAuth">
      <h2>Профиль</h2>

      <div *ngIf="loading">Загружаем профиль…</div>

      <div *ngIf="!loading && profile">
        <p><strong>Email:</strong> {{ profile.email }}</p>

        <form (ngSubmit)="save()" #f="ngForm">
          <label>
            Имя пользователя
            <input
              type="text"
              name="username"
              [(ngModel)]="form.username"
              required
              minlength="3"
            />
          </label>

          <label>
            О себе
            <textarea
              name="bio"
              rows="4"
              [(ngModel)]="form.bio"
            ></textarea>
          </label>

          <label>
            URL аватара
            <input
              type="url"
              name="avatarUrl"
              [(ngModel)]="form.avatarUrl"
            />
          </label>

          <button
            class="btn primary"
            type="submit"
            [disabled]="f.invalid || saving"
          >
            {{ saving ? 'Сохраняем…' : 'Сохранить' }}
          </button>
        </form>
      </div>
    </section>

    <ng-template #notAuth>
      <section>
        <h2>Профиль</h2>
        <p>Чтобы увидеть профиль, нужно войти.</p>
        <a routerLink="/login" class="btn primary">Войти</a>
      </section>
    </ng-template>
  `,
    styles: [
        `
      form {
        display: flex;
        flex-direction: column;
        gap: 1rem;
        max-width: 480px;
      }

      label {
        display: flex;
        flex-direction: column;
        gap: 0.25rem;
      }

      input,
      textarea {
        padding: 0.4rem 0.6rem;
        border-radius: 0.5rem;
        border: 1px solid #d1d5db;
        font: inherit;
      }

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
export class ProfilePageComponent implements OnInit {
    profile: UserProfile | null = null;
    loading = false;
    saving = false;

    form = {
        username: '',
        bio: '',
        avatarUrl: ''
    };

    constructor(
        public auth: AuthService,
        private api: UserApiService
    ) {}

    ngOnInit(): void {
        if (this.auth.status() !== 'authenticated') return;

        this.loading = true;
        this.api.getMe().subscribe({
            next: profile => {
                this.profile = profile;
                this.form.username = profile.username;
                this.form.bio = profile.bio ?? '';
                this.form.avatarUrl = profile.avatarUrl ?? '';
            },
            error: err => {
                console.error('Failed to load profile', err);
            },
            complete: () => {
                this.loading = false;
            }
        });
    }

    save(): void {
        if (!this.profile) return;

        this.saving = true;
        this.api
            .updateMe({
                username: this.form.username,
                bio: this.form.bio,
                avatarUrl: this.form.avatarUrl || null
            })
            .subscribe({
                next: profile => {
                    this.profile = profile;
                },
                error: err => {
                    console.error('Failed to save profile', err);
                },
                complete: () => {
                    this.saving = false;
                }
            });
    }
}
